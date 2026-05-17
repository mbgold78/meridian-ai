"""M4c-supervisor-base lifecycle tests for ``LongRunningSupervisor`` and the
``record_mcp_call`` activity writer.

Drives a concrete subclass through:

- Heartbeat-detected hang → SIGTERM clean exit (no SIGKILL needed)
- Heartbeat-detected hang → SIGTERM ignored → SIGKILL escalation
- Respawn produces a fresh dispatches row with a new token and ``task_id=NULL``
- ``_reconstruct_state`` hook fires on every spawn (initial + respawns)
- Three respawns within the window → cap fires → ``halt()`` writes the cap
  audit row and emits structlog ERROR
- ``start()`` on a halted instance raises ``HaltedSupervisorError``

Plus the activity writer:

- ``record_mcp_call`` updates ``last_activity_at`` and ``last_tool_called`` on
  active rows
- ``record_mcp_call`` skips rows in any of the six terminal states (loops over
  the dispatcher's ``_TERMINAL_STATES`` so adding a state extends coverage
  automatically)

Fast settings: ``timeout_seconds=1``, ``check_interval=0.05``,
``grace_seconds=0.5`` (or 0.3 for the SIGKILL test). The full file runs in
~10-15 seconds.
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator, Callable
from pathlib import Path
from typing import Any

import pytest
import pytest_asyncio
import structlog.testing

from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.bridge.activity import record_mcp_call
from meridian_orchestrator.bridge.dispatcher import _TERMINAL_STATES
from meridian_orchestrator.bridge.supervisor_base import (
    HaltedSupervisorError,
    LongRunningSupervisor,
    SupervisorLaunchContext,
    SupervisorSubprocessHandle,
)
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


# ----- Fake subprocess -----------------------------------------------------


class _ControllableProc:
    """Subprocess fake whose lifecycle is driven by the test.

    ``cooperative_terminate=True`` (default): ``terminate()`` synchronously
    sets returncode=0 and signals exit — no SIGKILL needed.
    ``cooperative_terminate=False``: ``terminate()`` is a no-op (subprocess
    hangs); only ``kill()`` ends it (sets returncode=-9).
    """

    def __init__(self, *, cooperative_terminate: bool = True) -> None:
        self._returncode: int | None = None
        self._exit = asyncio.Event()
        self._cooperative_terminate = cooperative_terminate
        self.terminate_called = False
        self.kill_called = False

    @property
    def pid(self) -> int | None:
        return 42

    @property
    def returncode(self) -> int | None:
        return self._returncode

    async def wait(self) -> int:
        await self._exit.wait()
        assert self._returncode is not None
        return self._returncode

    def terminate(self) -> None:
        self.terminate_called = True
        if self._cooperative_terminate and self._returncode is None:
            self._returncode = 0
            self._exit.set()

    def kill(self) -> None:
        self.kill_called = True
        if self._returncode is None:
            self._returncode = -9
            self._exit.set()


# ----- Concrete supervisor for tests --------------------------------------


class _TestSupervisor(LongRunningSupervisor):
    """Concrete ``LongRunningSupervisor`` for tests.

    Owns a queue of fake procs the launcher hands out one at a time. Tracks
    ``_reconstruct_state`` hook calls via a counter.
    """

    def __init__(
        self, *, agent_id: str, working_directory: Path, **kwargs: Any
    ) -> None:
        self._agent_id = agent_id
        self._wd = working_directory
        self.proc_queue: list[_ControllableProc] = []
        self.reconstruction_calls = 0
        super().__init__(subprocess_launcher=self._test_launcher, **kwargs)

    async def _test_launcher(
        self, ctx: SupervisorLaunchContext
    ) -> SupervisorSubprocessHandle:
        if not self.proc_queue:
            self.proc_queue.append(_ControllableProc(cooperative_terminate=True))
        return self.proc_queue.pop(0)

    def _subprocess_prompt(self) -> str:
        return "test supervisor prompt"

    def _subprocess_allowed_tools(self) -> list[str]:
        # Test fixture: empty list; launcher conditionally inserts
        # --allowedTools only when list is non-empty per F2 wrapper-
        # ordering precedent. Production path resolves via
        # bridge.mcp_config.resolve_allowed_tools_for_role; test
        # surface doesn't exercise the allowlist composition.
        return []

    def _working_directory(self) -> Path:
        return self._wd

    def _token_role(self) -> str:
        return "engineer"

    def _token_label(self) -> str:
        return "engineer-supervisor-test"

    def _supervisor_agent_id(self) -> str:
        return self._agent_id

    async def _reconstruct_state(self) -> None:
        self.reconstruction_calls += 1


def _build_supervisor(
    storage: SqliteStorage,
    token_store: TokenStore,
    tmp_path: Path,
    *,
    timeout_seconds: int = 1,
    respawn_cap: int = 3,
    respawn_window_seconds: int = 60,
    check_interval_seconds: float = 0.05,
    grace_seconds: float = 0.5,
) -> _TestSupervisor:
    return _TestSupervisor(
        agent_id="agent-supervisor-test",
        working_directory=tmp_path / "engineer-cwd",
        storage=storage,
        token_store=token_store,
        timeout_seconds=timeout_seconds,
        respawn_cap=respawn_cap,
        respawn_window_seconds=respawn_window_seconds,
        check_interval_seconds=check_interval_seconds,
        grace_seconds=grace_seconds,
    )


async def _wait_until(
    predicate: Callable[[], bool],
    *,
    timeout: float = 5.0,
    interval: float = 0.05,
) -> None:
    """Poll ``predicate`` until True or raise ``AssertionError`` on timeout."""
    deadline = asyncio.get_event_loop().time() + timeout
    while asyncio.get_event_loop().time() < deadline:
        if predicate():
            return
        await asyncio.sleep(interval)
    raise AssertionError("timed out waiting for predicate")


async def _audit_actions_for(
    storage: SqliteStorage, target: str
) -> list[str]:
    rows = await storage.fetch_all(
        "SELECT action FROM audit_log WHERE target = ? ORDER BY timestamp, rowid",
        (target,),
    )
    return [r["action"] for r in rows]


# ----- Lifecycle tests ----------------------------------------------------


async def test_heartbeat_timeout_produces_clean_terminate_chain(
    storage: SqliteStorage, tmp_path: Path
) -> None:
    """Watchdog detects hang. ``proc.terminate()`` lands; the cooperative
    fake exits within the grace window; SIGKILL never fires. Audit chain
    contains heartbeat_timeout, token_revoked, subprocess_terminated."""
    token_store = TokenStore()
    proc = _ControllableProc(cooperative_terminate=True)

    supervisor = _build_supervisor(storage, token_store, tmp_path)
    supervisor.proc_queue = [
        proc,
        _ControllableProc(cooperative_terminate=True),
    ]

    await supervisor.start()
    timed_out_id = supervisor._current_dispatch_id
    assert timed_out_id is not None

    await _wait_until(
        lambda: proc.terminate_called
        and supervisor._current_dispatch_id is not None
        and supervisor._current_dispatch_id != timed_out_id,
        timeout=4.0,
    )

    assert proc.terminate_called
    assert not proc.kill_called
    assert proc.returncode == 0

    actions = await _audit_actions_for(storage, timed_out_id)
    assert "dispatch.heartbeat_timeout" in actions
    assert "dispatch.token_revoked" in actions
    assert "dispatch.subprocess_terminated" in actions

    row = await storage.fetch_one(
        "SELECT status, timed_out_at FROM dispatches WHERE id = ?",
        (timed_out_id,),
    )
    assert row is not None
    assert row["status"] == "timed_out"
    assert row["timed_out_at"] is not None

    await supervisor.shutdown()


async def test_heartbeat_timeout_subprocess_unresponsive_escalates_to_sigkill(
    storage: SqliteStorage, tmp_path: Path
) -> None:
    """Subprocess ignores SIGTERM. Watchdog escalates to SIGKILL after grace.
    The subprocess_terminated audit row's signal field reflects SIGKILL."""
    token_store = TokenStore()
    proc = _ControllableProc(cooperative_terminate=False)

    supervisor = _build_supervisor(
        storage, token_store, tmp_path, grace_seconds=0.3
    )
    supervisor.proc_queue = [
        proc,
        _ControllableProc(cooperative_terminate=True),
    ]

    await supervisor.start()
    timed_out_id = supervisor._current_dispatch_id
    assert timed_out_id is not None

    await _wait_until(
        lambda: proc.kill_called
        and supervisor._current_dispatch_id is not None
        and supervisor._current_dispatch_id != timed_out_id,
        timeout=5.0,
    )

    assert proc.terminate_called
    assert proc.kill_called
    assert proc.returncode == -9

    rows = await storage.fetch_all(
        "SELECT details_json FROM audit_log "
        "WHERE action = ? AND target = ?",
        ("dispatch.subprocess_terminated", timed_out_id),
    )
    assert len(rows) == 1
    details = json.loads(rows[0]["details_json"])
    assert details["signal"] == "SIGKILL"

    await supervisor.shutdown()


async def test_respawn_writes_fresh_dispatch_row(
    storage: SqliteStorage, tmp_path: Path
) -> None:
    """One timeout → one respawn. New dispatches row has a fresh dispatch_id,
    fresh token, status='running', task_id IS NULL (Decision Q). Predecessor
    row is timed_out; predecessor token is revoked."""
    token_store = TokenStore()
    supervisor = _build_supervisor(storage, token_store, tmp_path)

    await supervisor.start()
    initial_dispatch_id = supervisor._current_dispatch_id
    assert initial_dispatch_id is not None
    assert supervisor._current_token is not None
    initial_token_id = supervisor._current_token.record.token_id

    await _wait_until(
        lambda: supervisor._current_dispatch_id is not None
        and supervisor._current_dispatch_id != initial_dispatch_id,
        timeout=4.0,
    )

    new_dispatch_id = supervisor._current_dispatch_id
    assert supervisor._current_token is not None
    new_token_id = supervisor._current_token.record.token_id

    assert new_dispatch_id != initial_dispatch_id
    assert new_token_id != initial_token_id

    initial_row = await storage.fetch_one(
        "SELECT status FROM dispatches WHERE id = ?", (initial_dispatch_id,)
    )
    new_row = await storage.fetch_one(
        "SELECT status, task_id FROM dispatches WHERE id = ?",
        (new_dispatch_id,),
    )
    assert initial_row is not None
    assert new_row is not None
    assert initial_row["status"] == "timed_out"
    assert new_row["status"] == "running"
    assert new_row["task_id"] is None  # Decision Q

    initial_token = await token_store.lookup_by_token_id(initial_token_id)
    assert initial_token is not None
    assert initial_token.revoked_at is not None

    new_token = await token_store.lookup_by_token_id(new_token_id)
    assert new_token is not None
    assert new_token.revoked_at is None

    await supervisor.shutdown()


async def test_state_reconstruction_hook_runs_post_respawn(
    storage: SqliteStorage, tmp_path: Path
) -> None:
    """``_reconstruct_state`` fires on every spawn (initial + respawns). After
    one timeout-driven respawn, the hook counter should be 2."""
    token_store = TokenStore()
    supervisor = _build_supervisor(storage, token_store, tmp_path)

    await supervisor.start()
    initial_id = supervisor._current_dispatch_id
    assert supervisor.reconstruction_calls == 1  # initial spawn

    await _wait_until(
        lambda: supervisor._current_dispatch_id != initial_id,
        timeout=4.0,
    )

    assert supervisor.reconstruction_calls == 2  # +1 from the respawn
    await supervisor.shutdown()


async def test_three_respawns_per_hour_cap_halts_supervisor(
    storage: SqliteStorage, tmp_path: Path
) -> None:
    """Force 4 timeouts. The first 3 trigger respawns; the 4th hits the cap
    and halts.

    Asserts:
    - ``dispatch.heartbeat_timeout_cap`` audit row present with the last 3
      respawn dispatch_ids and last_tool_called values aligned
    - ``supervisor.is_halted`` is True
    - structlog ERROR record ``supervisor.halted`` captured exactly once
    """
    token_store = TokenStore()
    supervisor = _build_supervisor(
        storage,
        token_store,
        tmp_path,
        respawn_cap=3,
        respawn_window_seconds=60,
    )
    # 1 initial + 3 respawns = 4 procs. The 4th attempt halts (no proc used).
    supervisor.proc_queue = [
        _ControllableProc(cooperative_terminate=True) for _ in range(4)
    ]

    with structlog.testing.capture_logs() as captured:
        await supervisor.start()
        await _wait_until(lambda: supervisor.is_halted, timeout=10.0)

    cap_rows = await storage.fetch_all(
        "SELECT details_json FROM audit_log WHERE action = ?",
        ("dispatch.heartbeat_timeout_cap",),
    )
    assert len(cap_rows) == 1
    cap_details = json.loads(cap_rows[0]["details_json"])
    assert cap_details["role"] == supervisor._token_role_with_label()
    assert len(cap_details["respawn_dispatch_ids"]) == 3
    assert len(cap_details["last_tool_called_per_dispatch"]) == 3
    # No tool calls were ever made — last_tool_called stays NULL on the rows
    assert all(
        v is None for v in cap_details["last_tool_called_per_dispatch"]
    )

    assert supervisor.is_halted
    assert supervisor._started is False

    halted_logs = [
        c for c in captured
        if c.get("event") == "supervisor.halted" and c.get("log_level") == "error"
    ]
    assert len(halted_logs) == 1, (
        f"expected exactly one ERROR-level supervisor.halted log; "
        f"captured: {captured!r}"
    )
    assert halted_logs[0]["recovery"] == "service restart required"


async def test_post_halt_start_raises(
    storage: SqliteStorage, tmp_path: Path
) -> None:
    """After halt fires, calling start() again raises HaltedSupervisorError
    (Decision C — typed exception, not generic RuntimeError)."""
    token_store = TokenStore()
    supervisor = _build_supervisor(
        storage,
        token_store,
        tmp_path,
        respawn_cap=3,
        respawn_window_seconds=60,
    )
    supervisor.proc_queue = [
        _ControllableProc(cooperative_terminate=True) for _ in range(4)
    ]

    await supervisor.start()
    await _wait_until(lambda: supervisor.is_halted, timeout=10.0)

    with pytest.raises(HaltedSupervisorError):
        await supervisor.start()


# ----- Activity-writer tests ---------------------------------------------


async def _seed_minimal(storage: SqliteStorage) -> None:
    await storage.execute(
        "INSERT INTO companies (id, name) VALUES ('co1', 'Test Co')"
    )
    await storage.execute(
        "INSERT INTO projects (id, company_id, name) VALUES "
        "('proj1', 'co1', 'Test Project')"
    )
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, description) "
        "VALUES ('task-1', 'co1', 'proj1', 'implementer', 'test')"
    )


async def _insert_dispatch(
    storage: SqliteStorage,
    *,
    dispatch_id: str,
    status: str,
    token_id: str,
) -> None:
    await storage.execute(
        "INSERT INTO dispatches "
        "(id, task_id, role, status, working_directory, timeout_seconds, token_id) "
        "VALUES (?, 'task-1', 'implementer', ?, '/tmp/test', 300, ?)",
        (dispatch_id, status, token_id),
    )


async def test_record_mcp_call_updates_last_activity_at_for_active_dispatch(
    storage: SqliteStorage,
) -> None:
    """Active (non-terminal) dispatch row matching token_id gets both the
    timestamp and the tool_name written."""
    await _seed_minimal(storage)
    await _insert_dispatch(
        storage, dispatch_id="d1", status="running", token_id="tok_active"
    )

    pre = await storage.fetch_one(
        "SELECT last_activity_at, last_tool_called FROM dispatches WHERE id = ?",
        ("d1",),
    )
    assert pre is not None
    assert pre["last_activity_at"] is None
    assert pre["last_tool_called"] is None

    await record_mcp_call(storage, "tok_active", "ping")

    post = await storage.fetch_one(
        "SELECT last_activity_at, last_tool_called FROM dispatches WHERE id = ?",
        ("d1",),
    )
    assert post is not None
    assert post["last_activity_at"] is not None
    assert post["last_tool_called"] == "ping"


async def test_record_mcp_call_skips_terminal_state_dispatch(
    storage: SqliteStorage,
) -> None:
    """A dispatch row in any of the six terminal states must NOT have its
    activity columns updated. Loops over ``bridge.dispatcher._TERMINAL_STATES``
    so adding a new terminal state to the dispatcher's enum automatically
    extends this test's coverage (Decision G's terminal-states-list is a
    single source of truth in :func:`bridge.activity.record_mcp_call`)."""
    await _seed_minimal(storage)

    for status in sorted(_TERMINAL_STATES):
        dispatch_id = f"d-{status}"
        token_id = f"tok-{status}"
        await _insert_dispatch(
            storage, dispatch_id=dispatch_id, status=status, token_id=token_id
        )

        pre = await storage.fetch_one(
            "SELECT last_activity_at, last_tool_called FROM dispatches WHERE id = ?",
            (dispatch_id,),
        )
        assert pre is not None
        assert pre["last_activity_at"] is None
        assert pre["last_tool_called"] is None

        await record_mcp_call(storage, token_id, "ping")

        post = await storage.fetch_one(
            "SELECT last_activity_at, last_tool_called FROM dispatches WHERE id = ?",
            (dispatch_id,),
        )
        assert post is not None
        assert post["last_activity_at"] is None, (
            f"status={status!r}: activity clock should not reset for terminal rows"
        )
        assert post["last_tool_called"] is None, (
            f"status={status!r}: last_tool_called should not be written for terminal rows"
        )
