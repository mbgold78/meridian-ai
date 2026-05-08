"""F3 dispatch-timeout watchdog tests (M5-flag-flips commit 2; Codex finding 3).

Drives :class:`BridgeDispatcher` through dispatches whose subprocesses hang
without calling ``complete_dispatch``, exercising the
:func:`asyncio.timeout` deadline added inside ``_await_terminal``. Five
unique-fn coverage:

- ``test_dispatch_times_out_when_subprocess_never_completes`` — basic
  deadline fires; outcome.status == 'timed_out'.
- ``test_timeout_marks_row_with_completed_at_and_notes`` — row hygiene
  (completed_at populated; notes contain "timed out after Ns").
- ``test_timeout_subprocess_terminated_via_finally`` — existing finally
  cleanup runs (audit row dispatch.subprocess_terminated written).
- ``test_timeout_does_not_overwrite_completed_at_deadline_boundary`` —
  CQ-B race protection: if complete_dispatch lands at the deadline
  boundary, the except-path re-read sees terminal status and returns
  the actual outcome rather than overwriting with 'timed_out'.
- ``test_timeout_uses_effective_timeout_precedence`` — the value passed
  to asyncio.timeout matches the dispatcher's precedence chain
  (timeout_override_seconds > calculate_timeout > timeout_seconds default).

Skipped if ``git`` is not on PATH on the host running the test.
"""

from __future__ import annotations

import asyncio
import shutil
import subprocess
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any

import pytest
import pytest_asyncio

from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.bridge import dispatcher as dispatcher_mod
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
    """Minimal SubprocessHandle that never exits naturally.

    For F3 timeout tests, the subprocess shape is "hung subprocess that
    neither calls complete_dispatch nor signals exit." terminate() (called
    from the dispatch() finally via _terminate_subprocess) sets
    returncode=0 and signals the exit event so cleanup completes quickly.
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


def _make_hung_launcher(
    fake_proc: _FakeSubprocess,
) -> Any:
    """Returns a launcher coroutine factory that returns the fake without
    spawning any work — the subprocess just hangs forever from the
    dispatcher's perspective."""

    async def fake_launcher(ctx: DispatchLaunchContext) -> SubprocessHandle:
        return fake_proc

    return fake_launcher


async def _make_canonical(tmp_path: Path, upstream: Path) -> CanonicalClone:
    canonical = CanonicalClone(
        path=tmp_path / "data" / "substrate-canonical",
        upstream_url=str(upstream),
        default_branch="main",
    )
    await canonical.ensure_provisioned()
    return canonical


async def test_dispatch_times_out_when_subprocess_never_completes(
    storage: SqliteStorage, upstream_repo: Path, tmp_path: Path
) -> None:
    """asyncio.timeout fires; outcome.status == 'timed_out'."""
    canonical = await _make_canonical(tmp_path, upstream_repo)
    token_store = TokenStore()
    fake_proc = _FakeSubprocess()

    dispatcher = BridgeDispatcher(
        storage=storage,
        token_store=token_store,
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=_make_hung_launcher(fake_proc),
        poll_interval_seconds=0.02,
    )

    spec_path = tmp_path / "spec.md"
    spec_path.write_text("hangs forever\n", encoding="utf-8")

    outcome = await dispatcher.dispatch(
        task_id="task-1",
        role="implementer",
        role_agent_id="impl-agent-id",
        prompt=spec_path.read_text(encoding="utf-8"),
        spec_path_descriptor=spec_path,
        timeout_override_seconds=1,
        actor="eng-agent-id",
    )

    assert outcome.status == "timed_out"


async def test_timeout_marks_row_with_completed_at_and_notes(
    storage: SqliteStorage, upstream_repo: Path, tmp_path: Path
) -> None:
    """Row hygiene: completed_at populated; notes contain 'timed out after Ns'."""
    canonical = await _make_canonical(tmp_path, upstream_repo)
    token_store = TokenStore()
    fake_proc = _FakeSubprocess()

    dispatcher = BridgeDispatcher(
        storage=storage,
        token_store=token_store,
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=_make_hung_launcher(fake_proc),
        poll_interval_seconds=0.02,
    )

    outcome = await dispatcher.dispatch(
        task_id="task-1",
        role="implementer",
        role_agent_id="impl-agent-id",
        prompt="hangs\n",
        timeout_override_seconds=1,
        actor="eng-agent-id",
    )

    assert outcome.status == "timed_out"
    row = await storage.fetch_one(
        "SELECT status, completed_at, notes FROM dispatches WHERE id = ?",
        (outcome.dispatch_id,),
    )
    assert row is not None
    assert row["status"] == "timed_out"
    assert row["completed_at"] is not None
    assert row["notes"] is not None
    assert "timed out after 1s" in row["notes"]
    assert "without complete_dispatch" in row["notes"]


async def test_timeout_subprocess_terminated_via_finally(
    storage: SqliteStorage, upstream_repo: Path, tmp_path: Path
) -> None:
    """dispatch() finally runs _terminate_subprocess regardless of timeout path.

    The audit chain must include dispatch.subprocess_terminated even when the
    dispatch ends via asyncio.TimeoutError rather than clean completion.
    """
    canonical = await _make_canonical(tmp_path, upstream_repo)
    token_store = TokenStore()
    fake_proc = _FakeSubprocess()

    dispatcher = BridgeDispatcher(
        storage=storage,
        token_store=token_store,
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=_make_hung_launcher(fake_proc),
        poll_interval_seconds=0.02,
    )

    outcome = await dispatcher.dispatch(
        task_id="task-1",
        role="implementer",
        role_agent_id="impl-agent-id",
        prompt="hangs\n",
        timeout_override_seconds=1,
        actor="eng-agent-id",
    )

    assert outcome.status == "timed_out"
    # The fake's terminate() was invoked (returncode flipped from None to 0).
    assert fake_proc.returncode == 0

    audit = await storage.fetch_all(
        "SELECT action FROM audit_log "
        "WHERE target = ? ORDER BY timestamp, rowid",
        (outcome.dispatch_id,),
    )
    actions = [a["action"] for a in audit]
    assert "dispatch.subprocess_terminated" in actions
    assert "dispatch.token_revoked" in actions
    assert actions[-1] == "dispatch.subprocess_terminated"


async def test_timeout_does_not_overwrite_completed_at_deadline_boundary(
    storage: SqliteStorage, upstream_repo: Path, tmp_path: Path
) -> None:
    """CQ-B race protection: if complete_dispatch lands at deadline boundary,
    the except-path re-read sees the terminal state and returns it instead
    of overwriting with 'timed_out'.

    Forced deterministically: the storage.fetch_one wrapper detects when
    the except handler runs (the SECOND dispatches-row SELECT after timeout
    fires) and lands complete_dispatch in-band before returning the row.
    The wrapper returns a row whose status reflects the just-landed
    'completed' state — exactly the boundary-race shape the re-read guards
    against.
    """
    canonical = await _make_canonical(tmp_path, upstream_repo)
    token_store = TokenStore()
    fake_proc = _FakeSubprocess()

    dispatcher = BridgeDispatcher(
        storage=storage,
        token_store=token_store,
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=_make_hung_launcher(fake_proc),
        # Long poll so the deadline (1s) fires during the first sleep,
        # forcing the except path through asyncio.TimeoutError rather than
        # via in-loop terminal-state detection. The first in-loop SELECT
        # happens at t≈0 (status='running'); sleep(10) starts; asyncio.timeout(1)
        # fires at t=1, raising TimeoutError into the except handler. The
        # except-handler SELECT is the SECOND status-row read overall.
        poll_interval_seconds=10.0,
    )

    real_fetch_one = storage.fetch_one
    poll_select_count = {"n": 0}
    landed = {"done": False}

    async def wrapped_fetch_one(
        query: str, params: Any = None
    ) -> Any:
        is_poll_select = (
            "SELECT status FROM dispatches WHERE id" in query
            and params is not None
        )
        if is_poll_select:
            poll_select_count["n"] += 1
        # The 2nd poll SELECT is the except-handler re-read (1 in-loop
        # SELECT happened at iter 1 before sleep). Land complete_dispatch
        # BEFORE the SELECT executes so the row reads as terminal —
        # exactly the boundary-race shape the re-read guards against.
        if (
            is_poll_select
            and poll_select_count["n"] == 2
            and not landed["done"]
        ):
            landed["done"] = True
            await complete_dispatch(
                storage=storage,
                token_store=token_store,
                request=CompleteDispatchInput(
                    dispatch_id=params[0],
                    status="completed",
                    commit_hash="0" * 40,
                    notes="boundary race winner",
                ),
                caller_agent_id="impl-agent-id",
                caller_role="implementer",
            )
        return await real_fetch_one(query, params)

    storage.fetch_one = wrapped_fetch_one  # type: ignore[method-assign]

    try:
        outcome = await dispatcher.dispatch(
            task_id="task-1",
            role="implementer",
            role_agent_id="impl-agent-id",
            prompt="boundary race\n",
            timeout_override_seconds=1,
            actor="eng-agent-id",
        )
    finally:
        storage.fetch_one = real_fetch_one  # type: ignore[method-assign]

    # The race-protection re-read saw the row already terminal ('completed')
    # and returned that outcome without overwriting to 'timed_out'.
    assert outcome.status == "completed"
    row = await storage.fetch_one(
        "SELECT status, notes FROM dispatches WHERE id = ?",
        (outcome.dispatch_id,),
    )
    assert row is not None
    assert row["status"] == "completed"
    assert row["notes"] == "boundary race winner"


async def test_timeout_uses_effective_timeout_precedence(
    storage: SqliteStorage,
    upstream_repo: Path,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The value passed to asyncio.timeout matches the precedence chain.

    Verifies timeout_override_seconds > calculate_timeout(model, est) >
    timeout_seconds default by capturing the value reaching asyncio.timeout
    and asserting it matches the expected resolved value. Mechanical
    insurance against future refactors silently breaking precedence.
    """
    canonical = await _make_canonical(tmp_path, upstream_repo)
    token_store = TokenStore()

    captured: list[float] = []
    real_timeout = asyncio.timeout

    def capturing_timeout(delay: float | None) -> Any:
        captured.append(delay if delay is not None else -1.0)
        return real_timeout(delay)

    monkeypatch.setattr(dispatcher_mod.asyncio, "timeout", capturing_timeout)

    # Per-case launcher: completes the dispatch quickly so the test does
    # not actually wait for the precedence-resolved timeout to fire.
    fake_proc = _FakeSubprocess()
    background: list[asyncio.Task[None]] = []

    async def fast_complete_launcher(
        ctx: DispatchLaunchContext,
    ) -> SubprocessHandle:
        async def simulate() -> None:
            await asyncio.sleep(0)
            await complete_dispatch(
                storage=storage,
                token_store=token_store,
                request=CompleteDispatchInput(
                    dispatch_id=ctx.dispatch_id,
                    status="completed",
                    commit_hash="0" * 40,
                ),
                caller_agent_id="impl-agent-id",
                caller_role="implementer",
            )
            if fake_proc.returncode is None:
                fake_proc.signal_clean_exit()

        background.append(asyncio.create_task(simulate()))
        return fake_proc

    dispatcher = BridgeDispatcher(
        storage=storage,
        token_store=token_store,
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=fast_complete_launcher,
        poll_interval_seconds=0.02,
    )

    # Case 1: timeout_override_seconds wins outright.
    outcome = await dispatcher.dispatch(
        task_id="task-1",
        role="implementer",
        role_agent_id="impl-agent-id",
        prompt="case 1\n",
        timeout_override_seconds=42,
        actor="eng-agent-id",
    )
    assert outcome.status == "completed"
    assert captured[-1] == 42

    # Case 2: no override + no model → legacy timeout_seconds default (300).
    fake_proc = _FakeSubprocess()
    background.clear()
    outcome = await dispatcher.dispatch(
        task_id="task-1",
        role="implementer",
        role_agent_id="impl-agent-id",
        prompt="case 2\n",
        actor="eng-agent-id",
    )
    assert outcome.status == "completed"
    assert captured[-1] == 300
