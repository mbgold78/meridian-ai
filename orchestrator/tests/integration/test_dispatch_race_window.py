"""Force the SELECT-before-returncode race window in BridgeDispatcher._await_terminal.

The dispatcher's poll loop reads ``dispatches.status`` *before* checking
``proc.returncode`` so a clean-exit-after-complete_dispatch race resolves to
the row's terminal state, not a spurious ``failed`` with rc=0.

The defensive re-read at ``dispatcher.py:646-659`` closes a tighter window:
if the SELECT lands microseconds before ``complete_dispatch``'s UPDATE *and*
the subprocess exits cleanly between those two events, the iteration would
mark the dispatch failed without the re-read. This test forces exactly that
interleaving with a wrapped ``storage.fetch_one`` that fires complete_dispatch
+ subprocess clean-exit between the SELECT returning and the dispatcher
checking proc.returncode.

Skipped if ``git`` is not on PATH on the host running the test.
"""

from __future__ import annotations

import asyncio
import shutil
import subprocess
from collections.abc import AsyncIterator, Sequence
from pathlib import Path
from typing import Any

import pytest
import pytest_asyncio

from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.bridge.canonical import CanonicalClone
from meridian_orchestrator.bridge.canonical_registry import CanonicalCloneRegistry
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
        "VALUES ('task-R', 'co1', 'proj1', 'implementer', 'race window')"
    )
    yield s
    await s.close()


class _ControllableSubprocess:
    """Subprocess fake whose returncode is set externally by the test hook."""

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
    _git("config", "user.email", "test@example.com", cwd=worktree)
    _git("config", "user.name", "Test", cwd=worktree)
    (worktree / "race.txt").write_text("race-window content\n", encoding="utf-8")
    _git("add", "race.txt", cwd=worktree)
    _git("commit", "-m", message, cwd=worktree)
    return subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=str(worktree),
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()


async def test_select_before_returncode_race_honors_terminal_state(
    storage: SqliteStorage, upstream_repo: Path, tmp_path: Path
) -> None:
    """Force complete_dispatch + clean exit to land between the dispatcher's
    SELECT and its proc.returncode check. The defensive re-read at
    ``dispatcher.py:646-659`` must resolve to the row's terminal state, not
    treat the rc=0 as ``subprocess exited without complete_dispatch``.

    Mechanics:

    1. The launcher returns a controllable subprocess with ``rc=None`` and
       does NOT spawn a simulator — the subprocess never independently calls
       ``complete_dispatch`` or exits.
    2. ``storage.fetch_one`` is wrapped. When the dispatcher's poll-loop
       SELECT (``SELECT status FROM dispatches WHERE id = ?``) lands and the
       row is still ``running``, the wrapper synchronously:
         a. makes a real commit in the worktree,
         b. calls ``complete_dispatch`` (UPDATE row → ``completed``),
         c. sets ``proc.returncode = 0`` (clean exit),
       then returns the *stale* ``running`` row to the dispatcher.
    3. The dispatcher's iteration sees ``status='running'`` (stale), then
       observes ``proc.returncode != None``, and falls into the rc-check
       branch. The defensive re-read SELECT must observe ``completed`` and
       return the outcome.

    Without the defensive re-read, the dispatcher would mark the row
    ``failed`` with ``notes='subprocess exited rc=0 without complete_dispatch'``
    even though the row is plainly ``completed``. This test asserts the
    outcome is ``completed`` and the failure-path note is absent — both
    signals that the re-read fired.
    """
    canonical = CanonicalClone(
        path=tmp_path / "data" / "substrate-canonical",
        upstream_url=str(upstream_repo),
        default_branch="main",
    )
    await canonical.ensure_provisioned()
    token_store = TokenStore()

    fake_proc = _ControllableSubprocess()
    captured: dict[str, str] = {}

    async def race_launcher(ctx: DispatchLaunchContext) -> SubprocessHandle:
        # Capture the dispatch_id and worktree for the storage hook to use.
        # Do NOT spawn a simulator — the storage hook drives the lifecycle.
        captured["dispatch_id"] = ctx.dispatch_id
        captured["worktree"] = str(ctx.working_directory)
        return fake_proc

    triggered = {"fired": False}
    original_fetch_one = storage.fetch_one

    async def hooked_fetch_one(
        query: str, params: Sequence[Any] | None = None
    ) -> dict[str, Any] | None:
        result = await original_fetch_one(query, params)
        # Gate: only fire on the dispatcher's poll SELECT for *our* dispatch,
        # only on the first observation of status='running'. Matches both
        # the legacy single-column ``SELECT status FROM dispatches WHERE id``
        # and the β-S11-17 extended ``SELECT status, started_at,
        # last_tool_called, last_activity_at FROM dispatches WHERE id``;
        # excludes the β-S11-17 one-shot pre-loop ``SELECT issued_at``
        # (which has no ``status`` column) and complete_dispatch's
        # internal SELECT (different column list).
        target = captured.get("dispatch_id")
        if (
            not triggered["fired"]
            and "FROM dispatches WHERE id = ?" in query
            and "status" in query
            and "issued_at" not in query
            and target is not None
            and params is not None
            and len(params) == 1
            and params[0] == target
            and result is not None
            and result.get("status") == "running"
        ):
            triggered["fired"] = True
            # Force the race: complete_dispatch + clean exit BEFORE returning
            # the (now stale) 'running' row to the dispatcher's poll loop.
            wt = Path(captured["worktree"])
            commit_sha = await asyncio.to_thread(
                _commit_in_worktree, wt, "race-window completion"
            )
            await complete_dispatch(
                storage=storage,
                token_store=token_store,
                request=CompleteDispatchInput(
                    dispatch_id=target,
                    status="completed",
                    commit_hash=commit_sha,
                    artifacts=["race.txt"],
                    notes="race-window race forced",
                ),
                caller_agent_id="impl-agent-id",
                caller_role="implementer",
            )
            fake_proc.signal_clean_exit()
        return result

    # Bind the hook onto the storage instance the dispatcher will use.
    storage.fetch_one = hooked_fetch_one  # type: ignore[method-assign]

    canonical_registry = CanonicalCloneRegistry(storage=storage)
    await canonical_registry.ensure_default(
        path=canonical.path, upstream_url=canonical.upstream_url
    )
    dispatcher = await BridgeDispatcher.create(
        storage=storage,
        token_store=token_store,
        canonical_registry=canonical_registry,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=race_launcher,
        poll_interval_seconds=0.02,
    )

    spec_path = tmp_path / "spec.md"
    spec_path.write_text("Force the race.\n", encoding="utf-8")

    outcome = await dispatcher.dispatch(
        task_id="task-R",
        role="implementer",
        role_agent_id="impl-agent-id",
        prompt=spec_path.read_text(encoding="utf-8"),
        spec_path_descriptor=spec_path,
        actor="eng-agent-id",
    )

    # The hook actually fired — the race window was exercised.
    assert triggered["fired"], (
        "race window was not exercised; the hook never observed a 'running' "
        "poll SELECT for the dispatch"
    )

    # The dispatcher resolved to the row's terminal state, NOT to a spurious
    # 'failed' from rc=0 with a stale 'running' status read.
    assert outcome.status == "completed", (
        f"defensive re-read did not honor terminal row state; got "
        f"status={outcome.status!r} (expected 'completed'). This is exactly "
        f"the regression dispatcher.py:646-659 prevents."
    )
    assert outcome.notes == "race-window race forced", (
        f"unexpected notes={outcome.notes!r}; the failure-path note "
        f"'subprocess exited rc=0 without complete_dispatch' would appear "
        f"here if the re-read failed to honor the row state"
    )

    # Read final row directly via the original fetch_one to avoid the gated
    # hook (already fired, but the original is canonical regardless).
    final_row = await original_fetch_one(
        "SELECT status, commit_hash, notes FROM dispatches WHERE id = ?",
        (captured["dispatch_id"],),
    )
    assert final_row is not None
    assert final_row["status"] == "completed"
    assert final_row["commit_hash"] == outcome.commit_hash
    assert "without complete_dispatch" not in (final_row["notes"] or "")
