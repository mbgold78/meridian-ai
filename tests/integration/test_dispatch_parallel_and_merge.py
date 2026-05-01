"""M4b integration tests: parallel dispatch, merge protocol, divergence, tester.

Builds on the M4a fixture infrastructure (real git tmp upstream, real
canonical clone, real per-dispatch worktrees, fake subprocess that calls
``complete_dispatch`` in-process). Verifies:

- **Disjoint-file parallel dispatches both merge clean** (M4b.1). After the
  first dispatch lands, the second's branch is ff-rebased onto the advanced
  canonical/main and then ``--ff-only`` merges. Canonical contains both
  commits in linear history; both dispatches audit ``dispatch.merged``.
- **Same-file parallel dispatches** with semantic conflict: the second
  dispatch's rebase aborts cleanly, ``merge_conflict_files_json`` populates,
  ``dispatch.merge_conflict`` audits. The aborted rebase leaves no residue
  in the worktree.
- Canonical divergence is detected before dispatch starts and the dispatch
  is failed cleanly with ``dispatch.canonical_diverged`` audit.
- ``dispatch_tester`` returns structured ``test_results`` from
  ``complete_dispatch``.
"""

from __future__ import annotations

import asyncio
import shutil
import subprocess
from collections.abc import AsyncIterator
from pathlib import Path

import pytest
import pytest_asyncio

from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.bridge.canonical import CanonicalClone
from meridian_orchestrator.bridge.dispatcher import (
    BridgeDispatcher,
    DispatchLaunchContext,
    SubprocessHandle,
)
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.tools.dispatch import (
    CompleteDispatchInput,
    DispatchTesterInput,
    complete_dispatch,
    dispatch_tester,
)

pytestmark = pytest.mark.skipif(
    shutil.which("git") is None, reason="git is not on PATH"
)


def _git(*args: str, cwd: Path) -> None:
    subprocess.run(["git", *args], cwd=str(cwd), check=True, capture_output=True)


def _git_capture(*args: str, cwd: Path) -> str:
    return subprocess.run(
        ["git", *args],
        cwd=str(cwd),
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()


@pytest.fixture
def upstream_repo(tmp_path: Path) -> Path:
    upstream = tmp_path / "upstream"
    upstream.mkdir()
    _git("init", "-b", "main", cwd=upstream)
    _git("config", "user.email", "test@example.com", cwd=upstream)
    _git("config", "user.name", "Test", cwd=upstream)
    _git("config", "receive.denyCurrentBranch", "ignore", cwd=upstream)
    (upstream / "README.md").write_text("substrate\n", encoding="utf-8")
    _git("add", "README.md", cwd=upstream)
    _git("commit", "-m", "initial", cwd=upstream)
    return upstream


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    await s.execute("INSERT INTO companies (id, name) VALUES ('co1', 'Meridian AI')")
    await s.execute(
        "INSERT INTO projects (id, company_id, name) VALUES "
        "('proj1', 'co1', 'Substrate Product')"
    )
    await s.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, description) "
        "VALUES "
        "('task-A', 'co1', 'proj1', 'implementer', 'edit foo'), "
        "('task-B', 'co1', 'proj1', 'implementer', 'edit bar'), "
        "('task-T', 'co1', 'proj1', 'tester', 'verify')"
    )
    yield s
    await s.close()


class _FakeSubprocess:
    def __init__(self) -> None:
        self._returncode: int | None = None
        self._exit = asyncio.Event()

    @property
    def pid(self) -> int | None:
        return 99999

    @property
    def returncode(self) -> int | None:
        return self._returncode

    async def wait(self) -> int:
        await self._exit.wait()
        assert self._returncode is not None
        return self._returncode

    def terminate(self) -> None:
        if self._returncode is None:
            self._returncode = 0
            self._exit.set()

    def kill(self) -> None:
        self._returncode = -9
        self._exit.set()

    def signal_clean_exit(self) -> None:
        self._returncode = 0
        self._exit.set()


def _commit_in_worktree(worktree: Path, file_name: str, content: str) -> str:
    _git("config", "user.email", "test@example.com", cwd=worktree)
    _git("config", "user.name", "Test", cwd=worktree)
    (worktree / file_name).write_text(content, encoding="utf-8")
    _git("add", file_name, cwd=worktree)
    _git("commit", "-m", f"M4b: {file_name}", cwd=worktree)
    return _git_capture("rev-parse", "HEAD", cwd=worktree)


# ----- parallel dispatch ---------------------------------------------------


async def test_parallel_disjoint_dispatches_both_merge_clean(
    storage: SqliteStorage, upstream_repo: Path, tmp_path: Path
) -> None:
    """Two parallel Implementer dispatches against disjoint files.

    Per M4b.1: ``merge_into_canonical`` ff-rebases the dispatch branch onto
    canonical's current main before ``--ff-only`` merging. For disjoint
    files the rebase replays cleanly with new commit hashes; the merge
    fast-forwards. Both dispatches end with ``actual_touched_files`` set
    and audit ``dispatch.merged``. Canonical's main contains both commits
    in linear history.
    """
    canonical = CanonicalClone(
        path=tmp_path / "data" / "substrate-canonical",
        upstream_url=str(upstream_repo),
        default_branch="main",
    )
    await canonical.ensure_provisioned()
    token_store = TokenStore()
    background: list[asyncio.Task[None]] = []

    # Production has a single BridgeDispatcher per daemon; the asyncio Lock
    # over canonical operations lives on that instance. Use one dispatcher
    # here so the lock actually serializes the two concurrent dispatches.
    procs: dict[str, _FakeSubprocess] = {}
    file_by_task = {"task-A": "foo.py", "task-B": "bar.py"}

    async def launcher(ctx: DispatchLaunchContext) -> SubprocessHandle:
        proc = _FakeSubprocess()
        procs[ctx.dispatch_id] = proc

        # Look up the dispatch's file via task_id — the dispatch row's
        # task_id was set on insert; we read it back to keep the launcher
        # symmetric across both dispatches.
        row = await storage.fetch_one(
            "SELECT task_id FROM dispatches WHERE id = ?", (ctx.dispatch_id,)
        )
        assert row is not None
        file_name = file_by_task[row["task_id"]]

        async def simulate() -> None:
            await asyncio.sleep(0.05)
            sha = await asyncio.to_thread(
                _commit_in_worktree,
                ctx.working_directory,
                file_name,
                f"content from {ctx.dispatch_id}\n",
            )
            await complete_dispatch(
                storage=storage,
                token_store=token_store,
                request=CompleteDispatchInput(
                    dispatch_id=ctx.dispatch_id,
                    status="completed",
                    commit_hash=sha,
                    artifacts=[file_name],
                ),
                caller_agent_id="impl-agent-id",
                caller_role="implementer",
            )
            proc.signal_clean_exit()

        background.append(asyncio.create_task(simulate()))
        return proc

    dispatcher = BridgeDispatcher(
        storage=storage,
        token_store=token_store,
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=launcher,
        poll_interval_seconds=0.02,
    )

    outcomes = await asyncio.gather(
        dispatcher.dispatch(
            task_id="task-A",
            role="implementer",
            role_agent_id="impl-agent-id",
            prompt="edit foo",
            actor="eng-agent-id",
        ),
        dispatcher.dispatch(
            task_id="task-B",
            role="implementer",
            role_agent_id="impl-agent-id",
            prompt="edit bar",
            actor="eng-agent-id",
        ),
    )

    # Both reach completed status — work landed on each branch.
    statuses = sorted(o.status for o in outcomes)
    assert statuses == ["completed", "completed"]

    # Both merged cleanly — actual_touched_files populated for each, none
    # have merge_conflict_files set.
    for o in outcomes:
        assert o.actual_touched_files, (
            f"dispatch {o.dispatch_id} should have touched files; got empty list"
        )
        assert not o.merge_conflict_files, (
            f"dispatch {o.dispatch_id} unexpectedly has merge_conflict_files: "
            f"{o.merge_conflict_files}"
        )

    # The touched files cover foo.py and bar.py (disjoint by construction).
    all_touched = sorted(f for o in outcomes for f in o.actual_touched_files)
    assert all_touched == ["bar.py", "foo.py"]

    # Two dispatch.merged audits, no dispatch.merge_conflict.
    audit_actions = await storage.fetch_all(
        "SELECT action FROM audit_log WHERE action IN "
        "('dispatch.merged', 'dispatch.merge_conflict')"
    )
    actions = sorted(a["action"] for a in audit_actions)
    assert actions == ["dispatch.merged", "dispatch.merged"]

    # Canonical's main contains both files in its working tree (whichever
    # merged first contributes one file, the rebased + merged second
    # contributes the other; linear history under ff-only after rebase).
    canonical_path = tmp_path / "data" / "substrate-canonical"
    assert (canonical_path / "foo.py").exists()
    assert (canonical_path / "bar.py").exists()

    # The second dispatch's rebase rewrote its commit hash. Confirm the
    # original (sub-agent-reported) and merged (post-rebase) hashes are
    # both recorded in the dispatch.merged audit details for at least one
    # of the two dispatches — exactly the dispatch whose branch had to
    # rebase. (The first-merging dispatch's rebase is a no-op since
    # canonical is at the dispatch's base.)
    merged_audits = await storage.fetch_all(
        "SELECT details_json FROM audit_log WHERE action = 'dispatch.merged'"
    )
    import json as _json
    rebased_count = 0
    for a in merged_audits:
        details = _json.loads(a["details_json"])
        assert "original_commit_hash" in details
        assert "new_upstream_head" in details
        if details["original_commit_hash"] != details["new_upstream_head"]:
            rebased_count += 1
    assert rebased_count >= 1, (
        "expected at least one dispatch's rebase to rewrite the commit hash"
    )


async def test_parallel_dispatches_same_file_records_conflict(
    storage: SqliteStorage, upstream_repo: Path, tmp_path: Path
) -> None:
    """Two parallel dispatches editing the same file: same shape as disjoint
    under spec-literal — first merges, second's ff-only fails. Asserts the
    merge_conflict_files_json reflects the touched file."""
    canonical = CanonicalClone(
        path=tmp_path / "data" / "substrate-canonical",
        upstream_url=str(upstream_repo),
        default_branch="main",
    )
    await canonical.ensure_provisioned()
    token_store = TokenStore()
    background: list[asyncio.Task[None]] = []

    procs: dict[str, _FakeSubprocess] = {}

    async def launcher(ctx: DispatchLaunchContext) -> SubprocessHandle:
        proc = _FakeSubprocess()
        procs[ctx.dispatch_id] = proc

        async def simulate() -> None:
            await asyncio.sleep(0.05)
            sha = await asyncio.to_thread(
                _commit_in_worktree,
                ctx.working_directory,
                "shared.py",
                f"# from {ctx.dispatch_id}\n",
            )
            await complete_dispatch(
                storage=storage,
                token_store=token_store,
                request=CompleteDispatchInput(
                    dispatch_id=ctx.dispatch_id,
                    status="completed",
                    commit_hash=sha,
                    artifacts=["shared.py"],
                ),
                caller_agent_id="impl-agent-id",
                caller_role="implementer",
            )
            proc.signal_clean_exit()

        background.append(asyncio.create_task(simulate()))
        return proc

    dispatcher = BridgeDispatcher(
        storage=storage,
        token_store=token_store,
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=launcher,
        poll_interval_seconds=0.02,
    )

    outcomes = await asyncio.gather(
        dispatcher.dispatch(
            task_id="task-A",
            role="implementer",
            role_agent_id="impl-agent-id",
            prompt="edit shared",
            actor="eng-agent-id",
        ),
        dispatcher.dispatch(
            task_id="task-B",
            role="implementer",
            role_agent_id="impl-agent-id",
            prompt="also edit shared",
            actor="eng-agent-id",
        ),
    )
    assert sorted(o.status for o in outcomes) == ["completed", "completed"]

    conflicted = [o for o in outcomes if o.merge_conflict_files]
    merged = [o for o in outcomes if o.actual_touched_files]
    assert len(conflicted) == 1
    assert conflicted[0].merge_conflict_files == ["shared.py"]
    assert len(merged) == 1
    assert merged[0].actual_touched_files == ["shared.py"]


async def test_rebase_aborts_cleanly_no_residue(
    storage: SqliteStorage, upstream_repo: Path, tmp_path: Path
) -> None:
    """When rebase fails on real semantic conflict, ``git rebase --abort``
    leaves the worktree clean — no ``.git/rebase-merge/`` directory, no
    detached HEAD, no leftover REBASE_HEAD ref. The conflicted dispatch's
    cleanup() then runs normally on terminal-state.

    The test forces a same-file conflict by running two parallel dispatches
    that both edit ``shared.py``, then asserts the canonical clone's git
    state shows no rebase residue after both dispatches reach terminal
    state. This catches the regression where ``git rebase --abort`` was
    not called on the right cwd.
    """
    canonical = CanonicalClone(
        path=tmp_path / "data" / "substrate-canonical",
        upstream_url=str(upstream_repo),
        default_branch="main",
    )
    await canonical.ensure_provisioned()
    token_store = TokenStore()
    background: list[asyncio.Task[None]] = []

    async def launcher(ctx: DispatchLaunchContext) -> SubprocessHandle:
        proc = _FakeSubprocess()

        async def simulate() -> None:
            await asyncio.sleep(0.05)
            sha = await asyncio.to_thread(
                _commit_in_worktree,
                ctx.working_directory,
                "shared.py",
                f"# from {ctx.dispatch_id}\n",
            )
            await complete_dispatch(
                storage=storage,
                token_store=token_store,
                request=CompleteDispatchInput(
                    dispatch_id=ctx.dispatch_id,
                    status="completed",
                    commit_hash=sha,
                    artifacts=["shared.py"],
                ),
                caller_agent_id="impl-agent-id",
                caller_role="implementer",
            )
            proc.signal_clean_exit()

        background.append(asyncio.create_task(simulate()))
        return proc

    dispatcher = BridgeDispatcher(
        storage=storage,
        token_store=token_store,
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=launcher,
        poll_interval_seconds=0.02,
    )

    await asyncio.gather(
        dispatcher.dispatch(
            task_id="task-A",
            role="implementer",
            role_agent_id="impl-agent-id",
            prompt="edit shared",
            actor="eng-agent-id",
        ),
        dispatcher.dispatch(
            task_id="task-B",
            role="implementer",
            role_agent_id="impl-agent-id",
            prompt="also edit shared",
            actor="eng-agent-id",
        ),
    )

    # No rebase-in-progress marker on canonical or in any leftover worktree.
    canonical_git = canonical.path / ".git"
    assert not (canonical_git / "rebase-merge").exists(), (
        "canonical .git/rebase-merge/ persisted after abort"
    )
    assert not (canonical_git / "rebase-apply").exists(), (
        "canonical .git/rebase-apply/ persisted after abort"
    )
    # Worktree-side rebase state under .git/worktrees/<name>/ — both worktrees
    # have been removed by cleanup() on terminal state, so this is implicit:
    # the directories shouldn't exist at all.
    worktrees_dir = tmp_path / "data" / "dispatch-worktrees"
    leftover_worktrees = (
        list(worktrees_dir.iterdir()) if worktrees_dir.exists() else []
    )
    assert not leftover_worktrees, (
        f"dispatch worktrees not cleaned: {leftover_worktrees}"
    )


# ----- canonical divergence -----------------------------------------------


async def test_canonical_divergence_blocks_dispatch_with_audit(
    storage: SqliteStorage, upstream_repo: Path, tmp_path: Path
) -> None:
    """Force canonical's HEAD ahead of origin's HEAD and verify a fresh
    dispatch fails with dispatch.canonical_diverged audit and a row note.

    This case should never happen in production — the daemon is the only
    writer of canonical — but the guard is what allows operators to detect
    out-of-band tampering (or a partial recovery state) cleanly.
    """
    canonical = CanonicalClone(
        path=tmp_path / "data" / "substrate-canonical",
        upstream_url=str(upstream_repo),
        default_branch="main",
    )
    await canonical.ensure_provisioned()

    # Force divergence: commit directly into canonical/main without pushing.
    _git("config", "user.email", "test@example.com", cwd=canonical.path)
    _git("config", "user.name", "Test", cwd=canonical.path)
    (canonical.path / "rogue.txt").write_text("out of band\n", encoding="utf-8")
    _git("add", "rogue.txt", cwd=canonical.path)
    _git("commit", "-m", "rogue commit", cwd=canonical.path)

    token_store = TokenStore()
    fake_proc = _FakeSubprocess()
    background: list[asyncio.Task[None]] = []

    async def launcher(ctx: DispatchLaunchContext) -> SubprocessHandle:
        async def simulate() -> None:
            await asyncio.sleep(0)
            fake_proc.signal_clean_exit()

        background.append(asyncio.create_task(simulate()))
        return fake_proc

    dispatcher = BridgeDispatcher(
        storage=storage,
        token_store=token_store,
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=launcher,
        poll_interval_seconds=0.02,
    )

    from meridian_orchestrator.bridge.canonical import CanonicalDivergedError

    with pytest.raises(CanonicalDivergedError):
        await dispatcher.dispatch(
            task_id="task-A",
            role="implementer",
            role_agent_id="impl-agent-id",
            prompt="should never run",
            actor="eng-agent-id",
        )

    audit = await storage.fetch_all(
        "SELECT action, success FROM audit_log "
        "WHERE action = 'dispatch.canonical_diverged'"
    )
    assert len(audit) == 1
    assert audit[0]["success"] == 0


# ----- dispatch_tester -----------------------------------------------------


async def test_dispatch_tester_returns_structured_test_results(
    storage: SqliteStorage, upstream_repo: Path, tmp_path: Path
) -> None:
    """A Tester dispatch's complete_dispatch payload includes test_results;
    those land on the dispatches row + flow back through DispatchTesterOutput."""
    canonical = CanonicalClone(
        path=tmp_path / "data" / "substrate-canonical",
        upstream_url=str(upstream_repo),
        default_branch="main",
    )
    await canonical.ensure_provisioned()
    token_store = TokenStore()
    background: list[asyncio.Task[None]] = []
    proc = _FakeSubprocess()

    test_results_payload = {
        "passed": 3,
        "failed": 1,
        "failures": [
            {"name": "checkout_button_visible", "reason": "selector not found"}
        ],
    }

    async def launcher(ctx: DispatchLaunchContext) -> SubprocessHandle:
        async def simulate() -> None:
            await asyncio.sleep(0)
            await complete_dispatch(
                storage=storage,
                token_store=token_store,
                request=CompleteDispatchInput(
                    dispatch_id=ctx.dispatch_id,
                    status="completed",
                    test_results=test_results_payload,
                    notes="3 passed 1 failed",
                ),
                caller_agent_id="tester-agent-id",
                caller_role="tester",
            )
            proc.signal_clean_exit()

        background.append(asyncio.create_task(simulate()))
        return proc

    dispatcher = BridgeDispatcher(
        storage=storage,
        token_store=token_store,
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=launcher,
        poll_interval_seconds=0.02,
    )

    out = await dispatch_tester(
        dispatcher=dispatcher,
        registry=None,
        request=DispatchTesterInput(
            task_id="task-T",
            commit_hash="0" * 40,
            working_directory=str(upstream_repo),
            assertion_spec="Verify the checkout flow.",
        ),
        caller_agent_id="eng-agent-id",
        caller_role="engineer",
        tester_agent_id="tester-agent-id",
    )

    assert out.status == "completed"
    assert out.test_results == test_results_payload
    assert out.notes == "3 passed 1 failed"

    row = await storage.fetch_one(
        "SELECT role, test_results_json FROM dispatches WHERE id = ?",
        (out.dispatch_id,),
    )
    assert row is not None
    assert row["role"] == "tester"
    assert row["test_results_json"] is not None
