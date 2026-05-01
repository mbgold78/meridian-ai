"""M4b integration tests: parallel dispatch, merge protocol, divergence, tester.

Builds on the M4a fixture infrastructure (real git tmp upstream, real
canonical clone, real per-dispatch worktrees, fake subprocess that calls
``complete_dispatch`` in-process). Verifies:

- Two parallel Implementer dispatches: per the literal spec
  (``11-engineer-bridge.md`` § Merge protocol — "daemon does not auto-rebase"),
  the first merges fast-forward and the second's ``--ff-only`` fails because
  canonical has advanced. The second dispatch reaches ``status='completed'``
  with ``merge_conflict_files_json`` populated. **This applies to both
  disjoint-file and shared-file parallel dispatches** under spec-literal
  semantics — see M4b checkpoint surface.
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


async def test_parallel_dispatches_serialize_via_canonical_lock(
    storage: SqliteStorage, upstream_repo: Path, tmp_path: Path
) -> None:
    """Two parallel Implementer dispatches against disjoint files.

    Per spec § Merge protocol (literal): the daemon does not auto-rebase.
    The first to call complete_dispatch fast-forwards into canonical/main;
    the second's --ff-only merge fails because canonical has advanced
    beyond the dispatch branch's base. Both dispatches reach
    status='completed'; the second populates merge_conflict_files_json.

    This test asserts the literal spec behavior. The M4b operator acceptance
    expects both to merge cleanly; resolving that tension (auto-rebase
    before --ff-only, vs. amending the acceptance) is a checkpoint
    decision tracked in the M4b notes.
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

    # Exactly one merged cleanly (actual_touched_files set, no conflict);
    # the other has merge_conflict_files set and actual_touched_files empty.
    merged = [o for o in outcomes if o.actual_touched_files]
    conflicted = [o for o in outcomes if o.merge_conflict_files]
    assert len(merged) == 1, f"expected exactly 1 clean merge, got {len(merged)}"
    assert len(conflicted) == 1, (
        f"expected exactly 1 merge_conflict, got {len(conflicted)}"
    )

    merged_files = merged[0].actual_touched_files
    conflict_files = conflicted[0].merge_conflict_files
    assert merged_files in (["foo.py"], ["bar.py"])
    assert conflict_files in (["foo.py"], ["bar.py"])
    # The two should be the disjoint files.
    assert set(merged_files) != set(conflict_files)

    # Audit chain: a dispatch.merged for the winner, dispatch.merge_conflict
    # for the loser.
    audit_actions = await storage.fetch_all(
        "SELECT action FROM audit_log WHERE action IN "
        "('dispatch.merged', 'dispatch.merge_conflict')"
    )
    actions = sorted(a["action"] for a in audit_actions)
    assert actions == ["dispatch.merge_conflict", "dispatch.merged"]


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
