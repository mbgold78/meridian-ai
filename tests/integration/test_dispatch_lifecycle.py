"""End-to-end serial-dispatch lifecycle test (M4a).

Drives :class:`BridgeDispatcher` through one Implementer dispatch using:

- A real local git repo as the substrate upstream (tmp_path/upstream)
- A real canonical clone (tmp_path/canonical) cloning that upstream
- A real per-dispatch worktree (tmp_path/worktrees/<dispatch_id>)
- A *fake* Claude Code subprocess that, on launch, makes a real commit inside
  the worktree and then calls ``complete_dispatch`` via the in-process pure
  handler. The fake exits with rc=0 once ``complete_dispatch`` has landed.

The point of the test is the full lifecycle: dispatch row transitions
queued → running → completed; canonical clone is provisioned and refreshed;
worktree is provisioned and cleaned up; per-dispatch token is issued and then
revoked; full audit chain is written.

Skipped if ``git`` is not on PATH on the host running the test.
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
    complete_dispatch,
)

pytestmark = pytest.mark.skipif(
    shutil.which("git") is None, reason="git is not on PATH"
)


def _git(*args: str, cwd: Path) -> None:
    subprocess.run(["git", *args], cwd=str(cwd), check=True, capture_output=True)


@pytest.fixture
def upstream_repo(tmp_path: Path) -> Path:
    """A non-bare git repo simulating Mike's substrate upstream.

    Has one commit on ``main`` so the canonical clone can fast-forward
    against ``origin/main`` after fetch.
    """
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
        "VALUES ('task-1', 'co1', 'proj1', 'implementer', 'no-op')"
    )
    yield s
    await s.close()


class _FakeSubprocess:
    """Minimal SubprocessHandle the dispatcher accepts.

    Set the ``returncode`` directly to simulate exit; ``wait()`` resolves on
    the internal exit event. ``terminate``/``kill`` set returncode and signal
    the event so the dispatcher's terminate-on-finally path completes.
    """

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


def _commit_in_worktree(worktree: Path, message: str) -> str:
    """Make a real commit on the dispatch branch and return the new sha."""
    _git("config", "user.email", "test@example.com", cwd=worktree)
    _git("config", "user.name", "Test", cwd=worktree)
    (worktree / "feature.txt").write_text("dispatch wrote this\n", encoding="utf-8")
    _git("add", "feature.txt", cwd=worktree)
    _git("commit", "-m", message, cwd=worktree)
    sha = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=str(worktree),
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    return sha


async def test_serial_implementer_dispatch_completes(
    storage: SqliteStorage, upstream_repo: Path, tmp_path: Path
) -> None:
    canonical = CanonicalClone(
        path=tmp_path / "data" / "substrate-canonical",
        upstream_url=str(upstream_repo),
        default_branch="main",
    )
    await canonical.ensure_provisioned()
    token_store = TokenStore()

    fake_proc = _FakeSubprocess()
    background: list[asyncio.Task[None]] = []

    async def fake_launcher(ctx: DispatchLaunchContext) -> SubprocessHandle:
        async def simulate() -> None:
            # Let the dispatcher's await loop start polling.
            await asyncio.sleep(0)
            commit_sha = await asyncio.to_thread(
                _commit_in_worktree, ctx.working_directory, "M4a: feature.txt"
            )
            await complete_dispatch(
                storage=storage,
                token_store=token_store,
                request=CompleteDispatchInput(
                    dispatch_id=ctx.dispatch_id,
                    status="completed",
                    commit_hash=commit_sha,
                    artifacts=["feature.txt"],
                    notes="M4a serial happy path",
                ),
                caller_agent_id="impl-agent-id",
                caller_role="implementer",
            )
            fake_proc.signal_clean_exit()

        background.append(asyncio.create_task(simulate()))
        return fake_proc

    dispatcher = BridgeDispatcher(
        storage=storage,
        token_store=token_store,
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=fake_launcher,
        poll_interval_seconds=0.02,
    )

    spec_path = tmp_path / "spec.md"
    spec_path.write_text("Implement feature X.\n", encoding="utf-8")

    outcome = await dispatcher.dispatch(
        task_id="task-1",
        role="implementer",
        role_agent_id="impl-agent-id",
        spec_path=spec_path,
        actor="eng-agent-id",
        requested_working_directory=str(upstream_repo),
    )

    assert outcome.status == "completed"
    assert outcome.commit_hash is not None
    assert len(outcome.commit_hash) == 40
    assert outcome.notes == "M4a serial happy path"
    assert outcome.artifacts == ["feature.txt"]

    row = await storage.fetch_one(
        "SELECT status, commit_hash, completed_at, started_at, token_id, "
        "subprocess_pid FROM dispatches WHERE id = ?",
        (outcome.dispatch_id,),
    )
    assert row is not None
    assert row["status"] == "completed"
    assert row["commit_hash"] == outcome.commit_hash
    assert row["completed_at"] is not None
    assert row["started_at"] is not None
    assert row["subprocess_pid"] == 99999

    # The worktree was cleaned up post-completion.
    worktree_path = tmp_path / "data" / "dispatch-worktrees" / outcome.dispatch_id
    assert not worktree_path.exists()

    # The token was revoked.
    assert row["token_id"] is not None
    revoked = token_store.lookup_by_token_id(row["token_id"])
    assert revoked is not None
    assert revoked.revoked_at is not None

    # Full audit chain is present and ordered.
    audit = await storage.fetch_all(
        "SELECT action, actor FROM audit_log "
        "WHERE target = ? ORDER BY timestamp, rowid",
        (outcome.dispatch_id,),
    )
    actions = [a["action"] for a in audit]
    assert actions[0] == "dispatch.issued"
    assert "dispatch.token_created" in actions
    assert "dispatch.subprocess_started" in actions
    assert "dispatch.complete_called" in actions
    assert "dispatch.token_revoked" in actions
    assert actions[-1] == "dispatch.subprocess_terminated"
    # The Engineer's agent_id is the actor on dispatch.issued.
    issued = next(a for a in audit if a["action"] == "dispatch.issued")
    assert issued["actor"] == "eng-agent-id"


async def test_subprocess_exits_without_complete_dispatch_marks_failed(
    storage: SqliteStorage, upstream_repo: Path, tmp_path: Path
) -> None:
    """If the subprocess exits before complete_dispatch, the dispatch is failed."""
    canonical = CanonicalClone(
        path=tmp_path / "data" / "substrate-canonical",
        upstream_url=str(upstream_repo),
        default_branch="main",
    )
    await canonical.ensure_provisioned()
    token_store = TokenStore()

    fake_proc = _FakeSubprocess()
    background: list[asyncio.Task[None]] = []

    async def fake_launcher(ctx: DispatchLaunchContext) -> SubprocessHandle:
        async def simulate() -> None:
            # Exit immediately without calling complete_dispatch.
            await asyncio.sleep(0)
            fake_proc.signal_clean_exit()

        background.append(asyncio.create_task(simulate()))
        return fake_proc

    dispatcher = BridgeDispatcher(
        storage=storage,
        token_store=token_store,
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=fake_launcher,
        poll_interval_seconds=0.02,
    )

    spec_path = tmp_path / "spec.md"
    spec_path.write_text("never completes.\n", encoding="utf-8")

    outcome = await dispatcher.dispatch(
        task_id="task-1",
        role="implementer",
        role_agent_id="impl-agent-id",
        spec_path=spec_path,
        actor="eng-agent-id",
    )

    assert outcome.status == "failed"
    row = await storage.fetch_one(
        "SELECT status, notes FROM dispatches WHERE id = ?",
        (outcome.dispatch_id,),
    )
    assert row is not None
    assert row["status"] == "failed"
    assert row["notes"] is not None
    assert "without complete_dispatch" in row["notes"]
