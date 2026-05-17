"""M4c-compaction tests.

Six tests covering the compaction lifecycle and the
``dispatches.status`` CHECK constraint shipped at this sub-milestone:

1. ``signal_compaction`` writes the audit row and returns ``accepted=True``.
2. Permission denial: non-engineer roles get ``PERMISSION_DENIED``.
3. Full lifecycle: signal → subprocess exits → row marked ``compacted`` →
   respawn with no respawn-cap increment.
4. Distinguishes unexpected exit (no prior signal) from compaction:
   row → ``status='failed'``, respawn-cap counter increments.
5. DB-level CHECK constraint rejects writes of invalid status values.
6. Daemon-restart edge case: in-process flag lost between signal and
   exit → exit classified as ``dispatch.subprocess_unexpected_exit``.

Tests 3, 4, and 6 use the controllable subprocess fake established in
``test_supervisor_base.py``; the supervisor runs at fast settings
(``timeout_seconds=10``, ``check_interval=0.05``) so heartbeat-timeout
doesn't fire during these scenarios — they all exercise the
subprocess-exit branch, not the timeout branch.
"""

from __future__ import annotations

import asyncio
import json
import sqlite3
from collections.abc import AsyncIterator, Callable
from pathlib import Path

import pytest
import pytest_asyncio

from meridian_orchestrator.agents.registry import AgentRegistry
from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.bridge.engineer_supervisor import EngineerSupervisor
from meridian_orchestrator.bridge.pm_supervisor import PMSupervisor
from meridian_orchestrator.bridge.supervisor_base import (
    SupervisorLaunchContext,
    SupervisorSubprocessHandle,
)
from meridian_orchestrator.config import Settings
from meridian_orchestrator.errors import PermissionError as OrchestratorPermissionError
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.tools.supervisor import (
    SignalCompactionInput,
    signal_compaction,
)

REPO_CONFIG_DIR = Path(__file__).resolve().parents[2] / "config" / "agents"


def _seed_repo_yamls(into_dir: Path) -> None:
    into_dir.mkdir(parents=True, exist_ok=True)
    for role in ("pm", "engineer", "implementer", "tester"):
        (into_dir / f"{role}.yaml").write_text(
            (REPO_CONFIG_DIR / f"{role}.yaml").read_text(encoding="utf-8"),
            encoding="utf-8",
        )


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(data_dir=tmp_path)


# ----- Fake subprocess (same shape as test_supervisor_base) ---------------


class _ControllableProc:
    def __init__(self, *, cooperative_terminate: bool = True) -> None:
        self._returncode: int | None = None
        self._exit = asyncio.Event()
        self._cooperative_terminate = cooperative_terminate
        self.terminate_called = False
        self.kill_called = False

    @property
    def pid(self) -> int | None:
        return 4242

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

    def signal_clean_exit(self) -> None:
        if self._returncode is None:
            self._returncode = 0
            self._exit.set()


async def _wait_until(
    predicate: Callable[[], bool],
    *,
    timeout: float = 5.0,
    interval: float = 0.05,
) -> None:
    deadline = asyncio.get_event_loop().time() + timeout
    while asyncio.get_event_loop().time() < deadline:
        if predicate():
            return
        await asyncio.sleep(interval)
    raise AssertionError("timed out waiting for predicate")


async def _build_supervisor(
    storage: SqliteStorage,
    settings: Settings,
    tmp_path: Path,
    *,
    service: str,
    proc_queue: list[_ControllableProc] | None = None,
) -> tuple[EngineerSupervisor, TokenStore, str]:
    """Common fixture: seed YAMLs, issue token, load registry, build
    supervisor with the supplied proc queue. Returns the supervisor, the
    token_store, and the engineer agent_id."""
    agents_dir = tmp_path / "config" / "agents"
    _seed_repo_yamls(agents_dir)
    token_store = TokenStore(service=service)
    issued = await token_store.issue(
        agent_id=None, role="engineer", label="supervisor-test"
    )
    registry = AgentRegistry(agents_dir, token_store=token_store)
    await registry.initial_load()

    queue = proc_queue if proc_queue is not None else []

    async def launcher(
        ctx: SupervisorLaunchContext,
    ) -> SupervisorSubprocessHandle:
        if not queue:
            queue.append(_ControllableProc(cooperative_terminate=True))
        return queue.pop(0)

    supervisor = EngineerSupervisor(
        registry=registry,
        working_directory=settings.engineer_supervisor_dir,
        storage=storage,
        token_store=token_store,
        subprocess_launcher=launcher,
        timeout_seconds=10,  # long enough that heartbeat doesn't fire in tests
        check_interval_seconds=0.05,
        respawn_cap=3,
        respawn_window_seconds=60,
    )
    return supervisor, token_store, issued.record.agent_id


async def _build_pm_supervisor(
    storage: SqliteStorage,
    settings: Settings,
    tmp_path: Path,
    *,
    service: str,
    proc_queue: list[_ControllableProc] | None = None,
) -> tuple[PMSupervisor, TokenStore, str]:
    """PM-supervisor fixture mirroring _build_supervisor for the
    Q3-A signal_compaction permission-widening path. Returns the
    PMSupervisor, the token_store, and the resolved pm agent_id."""
    agents_dir = tmp_path / "config" / "agents"
    _seed_repo_yamls(agents_dir)
    token_store = TokenStore(service=service)
    issued = await token_store.issue(
        agent_id=None, role="pm", label="pm-supervisor-test"
    )
    registry = AgentRegistry(agents_dir, token_store=token_store)
    await registry.initial_load()

    queue = proc_queue if proc_queue is not None else []

    async def launcher(
        ctx: SupervisorLaunchContext,
    ) -> SupervisorSubprocessHandle:
        if not queue:
            queue.append(_ControllableProc(cooperative_terminate=True))
        return queue.pop(0)

    supervisor = PMSupervisor(
        registry=registry,
        working_directory=settings.pm_continuous_dir,
        storage=storage,
        token_store=token_store,
        subprocess_launcher=launcher,
        timeout_seconds=10,
        check_interval_seconds=0.05,
        respawn_cap=3,
        respawn_window_seconds=60,
    )
    return supervisor, token_store, issued.record.agent_id


# ----- Tests --------------------------------------------------------------


async def test_signal_compaction_writes_audit_and_returns_accepted(
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """Direct handler test: signal_compaction validates caller, sets the
    in-process flag on the supervisor, writes engineer.compaction_triggered
    audit, returns accepted=True with next_action=exit_clean."""
    supervisor, _, agent_id = await _build_supervisor(
        storage, settings, tmp_path, service="compact-handler"
    )
    await supervisor.start()
    try:
        assert supervisor._compaction_pending is False
        dispatch_id = supervisor.current_dispatch_id
        assert dispatch_id is not None

        out = await signal_compaction(
            storage=storage,
            supervisor=supervisor,
            request=SignalCompactionInput(
                iteration_marker="test-iter-1", proxy_value=0.85
            ),
            caller_agent_id=agent_id,
            caller_role="engineer",
        )

        assert out.accepted is True
        assert out.next_action == "exit_clean"
        assert supervisor._compaction_pending is True

        rows = await storage.fetch_all(
            "SELECT details_json FROM audit_log "
            "WHERE action = 'engineer.compaction_triggered' AND target = ?",
            (dispatch_id,),
        )
        assert len(rows) == 1
        details = json.loads(rows[0]["details_json"])
        assert details["dispatch_id"] == dispatch_id
        assert details["iteration_marker"] == "test-iter-1"
        assert details["proxy_value"] == 0.85
    finally:
        await supervisor.shutdown()


async def test_signal_compaction_engineer_or_pm_only(
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """Permission denial: implementer/tester roles cannot signal
    compaction (rejected at the role gate). PM is allowed at the role
    gate per F9.2 Q3 Reading A widening (M5-continuous-pm-driver
    commit 8) but is rejected at the agent_id gate when calling against
    an engineer-supervisor whose supervisor_agent_id binds to engineer's
    agent_id, not PM's.

    Two gates exercised:

    1. ``_require_role(caller_role, "engineer", "pm")`` rejects
       implementer/tester roles regardless of agent_id.
    2. ``supervisor.supervisor_agent_id != caller_agent_id`` rejects
       PM caller against engineer-supervisor (PM agent_id wouldn't
       match engineer-supervisor's bound agent_id in production).
    """
    supervisor, _, agent_id = await _build_supervisor(
        storage, settings, tmp_path, service="compact-perms"
    )
    await supervisor.start()
    try:
        # Gate 1: role rejection. implementer/tester fail at _require_role
        # before any agent_id check.
        for bad_role in ("implementer", "tester"):
            with pytest.raises(OrchestratorPermissionError):
                await signal_compaction(
                    storage=storage,
                    supervisor=supervisor,
                    request=SignalCompactionInput(iteration_marker="x"),
                    caller_agent_id=agent_id,
                    caller_role=bad_role,
                )

        # Gate 2: agent_id rejection. PM passes role gate (allowed at
        # commit 8) but fails the agent_id check because the
        # engineer-supervisor's supervisor_agent_id binds to engineer's
        # agent_id, and a PM caller's agent_id wouldn't match.
        with pytest.raises(OrchestratorPermissionError):
            await signal_compaction(
                storage=storage,
                supervisor=supervisor,
                request=SignalCompactionInput(iteration_marker="x"),
                caller_agent_id="pm-agent-id-not-matching-supervisor",
                caller_role="pm",
            )

        # The flag stays False — none of the rejected calls flipped it
        assert supervisor._compaction_pending is False
    finally:
        await supervisor.shutdown()


async def test_subprocess_exit_after_compaction_signal_marks_compacted(
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """Full lifecycle. Signal compaction; subprocess exits cleanly; the
    watchdog observes both, audits engineer.compaction_completed, marks
    the row status='compacted', respawns. Respawn-cap counter does NOT
    increment (compaction is healthy lifecycle)."""
    initial_proc = _ControllableProc(cooperative_terminate=True)
    next_proc = _ControllableProc(cooperative_terminate=True)
    supervisor, token_store, agent_id = await _build_supervisor(
        storage,
        settings,
        tmp_path,
        service="compact-lifecycle",
        proc_queue=[initial_proc, next_proc],
    )
    await supervisor.start()
    initial_dispatch_id = supervisor.current_dispatch_id
    initial_token_id = supervisor._current_token.record.token_id  # type: ignore[union-attr]
    assert initial_dispatch_id is not None

    try:
        # Signal compaction; then trigger the fake's clean exit
        await signal_compaction(
            storage=storage,
            supervisor=supervisor,
            request=SignalCompactionInput(iteration_marker="lifecycle-1"),
            caller_agent_id=agent_id,
            caller_role="engineer",
        )
        initial_proc.signal_clean_exit()

        await _wait_until(
            lambda: supervisor.current_dispatch_id is not None
            and supervisor.current_dispatch_id != initial_dispatch_id,
            timeout=4.0,
        )

        # Original row → 'compacted'
        old_row = await storage.fetch_one(
            "SELECT status, completed_at FROM dispatches WHERE id = ?",
            (initial_dispatch_id,),
        )
        assert old_row is not None
        assert old_row["status"] == "compacted"
        assert old_row["completed_at"] is not None

        # Audit chain present
        actions = [
            r["action"]
            for r in await storage.fetch_all(
                "SELECT action FROM audit_log WHERE target = ? "
                "ORDER BY timestamp, rowid",
                (initial_dispatch_id,),
            )
        ]
        assert "engineer.compaction_triggered" in actions
        assert "engineer.compaction_completed" in actions

        # Token revoked with reason='compacted'
        revoked = await token_store.lookup_by_token_id(initial_token_id)
        assert revoked is not None
        assert revoked.revoked_at is not None
        revoke_audit = await storage.fetch_all(
            "SELECT details_json FROM audit_log WHERE action = ? AND target = ?",
            ("dispatch.token_revoked", initial_dispatch_id),
        )
        assert len(revoke_audit) == 1
        assert json.loads(revoke_audit[0]["details_json"])["reason"] == "compacted"

        # New row exists, status='running', fresh token, _compaction_pending reset
        new_row = await storage.fetch_one(
            "SELECT status FROM dispatches WHERE id = ?",
            (supervisor.current_dispatch_id,),
        )
        assert new_row is not None
        assert new_row["status"] == "running"
        assert supervisor._compaction_pending is False

        # Respawn-cap counter did NOT increment (compaction is healthy)
        assert len(supervisor._respawn_history) == 0
    finally:
        await supervisor.shutdown()


async def test_subprocess_exit_without_compaction_signal_marks_failed(
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """Inverse: subprocess exits without a prior signal_compaction call.
    Watchdog audits dispatch.subprocess_unexpected_exit, marks the row
    status='failed', respawns. Respawn-cap counter DOES increment."""
    initial_proc = _ControllableProc(cooperative_terminate=True)
    next_proc = _ControllableProc(cooperative_terminate=True)
    supervisor, _, _ = await _build_supervisor(
        storage,
        settings,
        tmp_path,
        service="compact-unexpected",
        proc_queue=[initial_proc, next_proc],
    )
    await supervisor.start()
    initial_dispatch_id = supervisor.current_dispatch_id
    assert initial_dispatch_id is not None

    try:
        # Subprocess exits with NO prior compaction signal
        assert supervisor._compaction_pending is False
        initial_proc.signal_clean_exit()

        await _wait_until(
            lambda: supervisor.current_dispatch_id is not None
            and supervisor.current_dispatch_id != initial_dispatch_id,
            timeout=4.0,
        )

        old_row = await storage.fetch_one(
            "SELECT status, notes FROM dispatches WHERE id = ?",
            (initial_dispatch_id,),
        )
        assert old_row is not None
        assert old_row["status"] == "failed"
        assert "without prior compaction signal" in (old_row["notes"] or "")

        actions = [
            r["action"]
            for r in await storage.fetch_all(
                "SELECT action FROM audit_log WHERE target = ?",
                (initial_dispatch_id,),
            )
        ]
        assert "dispatch.subprocess_unexpected_exit" in actions
        assert "engineer.compaction_completed" not in actions

        # Respawn-cap counter incremented (unexpected exit is a failure mode)
        assert len(supervisor._respawn_history) == 1
        assert (
            supervisor._respawn_history[0].triggered_by_dispatch_id
            == initial_dispatch_id
        )
    finally:
        await supervisor.shutdown()


async def test_compacted_status_check_constraint_rejects_invalid_status(
    storage: SqliteStorage,
) -> None:
    """Migration 0008's CHECK constraint rejects writes of invalid status
    values. Verifies the DB-level enforcement that pairs with the
    application-layer ``bridge.dispatcher._TERMINAL_STATES`` set."""
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

    # Valid status values work (sample 'compacted' specifically — the M4c-added one)
    await storage.execute(
        "INSERT INTO dispatches "
        "(id, task_id, role, status, working_directory, timeout_seconds) "
        "VALUES ('d-ok', 'task-1', 'implementer', 'compacted', '/tmp/test', 300)",
    )

    # Invalid status raises IntegrityError per SQLite's CHECK enforcement
    with pytest.raises(sqlite3.IntegrityError, match="CHECK"):
        await storage.execute(
            "INSERT INTO dispatches "
            "(id, task_id, role, status, working_directory, timeout_seconds) "
            "VALUES ('d-bad', 'task-1', 'implementer', 'bogus_state', "
            "'/tmp/test', 300)",
        )


# ----- M5-continuous-pm-driver commit 8 — Q3-A permission widening -----


async def test_pm_signal_compaction_writes_pm_namespaced_audit(
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """F9.2 Q3 Reading A widening: PM caller against PM supervisor
    succeeds; audit action is `pm.compaction_triggered` (role-prefixed
    per F9.2 Q3-A audit-namespace symmetry)."""
    supervisor, _, agent_id = await _build_pm_supervisor(
        storage, settings, tmp_path, service="pm-compact-handler"
    )
    await supervisor.start()
    try:
        assert supervisor._compaction_pending is False
        dispatch_id = supervisor.current_dispatch_id
        assert dispatch_id is not None

        out = await signal_compaction(
            storage=storage,
            supervisor=supervisor,
            request=SignalCompactionInput(
                iteration_marker="pm-iter-1", proxy_value=0.85
            ),
            caller_agent_id=agent_id,
            caller_role="pm",
        )

        assert out.accepted is True
        assert out.next_action == "exit_clean"
        assert supervisor._compaction_pending is True

        # Audit action is role-prefixed for PM
        rows = await storage.fetch_all(
            "SELECT details_json FROM audit_log "
            "WHERE action = 'pm.compaction_triggered' AND target = ?",
            (dispatch_id,),
        )
        assert len(rows) == 1
        details = json.loads(rows[0]["details_json"])
        assert details["dispatch_id"] == dispatch_id
        assert details["iteration_marker"] == "pm-iter-1"
        assert details["proxy_value"] == 0.85

        # Engineer-namespaced action does NOT fire for PM caller —
        # contract pin against accidental hardcoding regression.
        engineer_rows = await storage.fetch_all(
            "SELECT id FROM audit_log "
            "WHERE action = 'engineer.compaction_triggered' AND target = ?",
            (dispatch_id,),
        )
        assert len(engineer_rows) == 0
    finally:
        await supervisor.shutdown()


async def test_pm_compaction_full_lifecycle_writes_pm_namespaced_audits(
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """Full PM compaction lifecycle: signal → subprocess clean exit →
    watchdog observes both → row marked `compacted` + respawn fresh.
    Audit chain uses `pm.*` namespace (handle_compaction_completed
    derives action from self._token_role()).

    Q3-A symmetry pin: if a future commit reverts the role-derived
    action shape in supervisor_base._handle_compaction_completed back
    to a hardcoded `engineer.compaction_completed`, this test fails."""
    initial_proc = _ControllableProc(cooperative_terminate=True)
    next_proc = _ControllableProc(cooperative_terminate=True)
    supervisor, _token_store, agent_id = await _build_pm_supervisor(
        storage,
        settings,
        tmp_path,
        service="pm-compact-lifecycle",
        proc_queue=[initial_proc, next_proc],
    )
    await supervisor.start()
    initial_dispatch_id = supervisor.current_dispatch_id
    assert initial_dispatch_id is not None

    try:
        await signal_compaction(
            storage=storage,
            supervisor=supervisor,
            request=SignalCompactionInput(iteration_marker="pm-lifecycle-1"),
            caller_agent_id=agent_id,
            caller_role="pm",
        )
        initial_proc.signal_clean_exit()

        await _wait_until(
            lambda: supervisor.current_dispatch_id is not None
            and supervisor.current_dispatch_id != initial_dispatch_id,
            timeout=4.0,
        )

        # Original row → 'compacted'
        old_row = await storage.fetch_one(
            "SELECT status, completed_at FROM dispatches WHERE id = ?",
            (initial_dispatch_id,),
        )
        assert old_row is not None
        assert old_row["status"] == "compacted"

        # Audit chain present with PM namespace on both events
        actions = [
            r["action"]
            for r in await storage.fetch_all(
                "SELECT action FROM audit_log WHERE target = ? "
                "ORDER BY timestamp, rowid",
                (initial_dispatch_id,),
            )
        ]
        assert "pm.compaction_triggered" in actions
        assert "pm.compaction_completed" in actions
        # Engineer namespace explicitly absent — supervisor base's
        # action derivation is role-aware, not hardcoded
        assert "engineer.compaction_triggered" not in actions
        assert "engineer.compaction_completed" not in actions

        # Compaction-driven respawns do not increment the cap counter
        assert len(supervisor._respawn_history) == 0
    finally:
        await supervisor.shutdown()


# ----- existing edge-case test follows -------------------------------------


async def test_daemon_restart_between_signal_and_exit_marks_failed(
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """Edge case: in-process _compaction_pending flag is lost (simulating
    daemon restart between signal_compaction firing and the subprocess
    actually exiting). The exit is then classified as
    dispatch.subprocess_unexpected_exit — acceptable per the spec
    (operator correlates manually via audit log).

    Mike-specified test — validates the documented edge case in
    11-engineer-bridge.md § Engineer compaction (daemon-restart subsection)."""
    initial_proc = _ControllableProc(cooperative_terminate=True)
    next_proc = _ControllableProc(cooperative_terminate=True)
    supervisor, _, agent_id = await _build_supervisor(
        storage,
        settings,
        tmp_path,
        service="compact-restart",
        proc_queue=[initial_proc, next_proc],
    )
    await supervisor.start()
    initial_dispatch_id = supervisor.current_dispatch_id
    assert initial_dispatch_id is not None

    try:
        # Signal compaction
        await signal_compaction(
            storage=storage,
            supervisor=supervisor,
            request=SignalCompactionInput(iteration_marker="restart-edge"),
            caller_agent_id=agent_id,
            caller_role="engineer",
        )
        assert supervisor._compaction_pending is True

        # Simulate daemon restart by clearing the in-process flag manually
        # (in production: daemon process dies + restart spawns a fresh
        # supervisor instance with _compaction_pending=False; here we just
        # clear the flag in place to model the same observable state).
        supervisor._compaction_pending = False

        # Now the subprocess exits cleanly. Without the flag, the watchdog
        # treats this as unexpected.
        initial_proc.signal_clean_exit()

        await _wait_until(
            lambda: supervisor.current_dispatch_id is not None
            and supervisor.current_dispatch_id != initial_dispatch_id,
            timeout=4.0,
        )

        old_row = await storage.fetch_one(
            "SELECT status FROM dispatches WHERE id = ?",
            (initial_dispatch_id,),
        )
        assert old_row is not None
        assert old_row["status"] == "failed"

        # The audit log retains the prior engineer.compaction_triggered row
        # (operator can correlate). Both actions are present.
        actions = [
            r["action"]
            for r in await storage.fetch_all(
                "SELECT action FROM audit_log WHERE target = ?",
                (initial_dispatch_id,),
            )
        ]
        assert "engineer.compaction_triggered" in actions
        assert "dispatch.subprocess_unexpected_exit" in actions
        assert "engineer.compaction_completed" not in actions
    finally:
        await supervisor.shutdown()
