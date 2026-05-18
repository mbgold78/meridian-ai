"""β-S11-4 commit A — supervisor cycle-completed classification tests.

Tests for the new ``dispatch.supervisor_cycle_completed`` audit event and
the ``_handle_supervisor_cycle_completed`` helper added to
``LongRunningSupervisor`` in β-S11-4 commit A. See
``docs/specs/beta-S11-4-supervisor-cycle-completed.md`` § 11 (Tests 1, 2)
and § 12 (Tests 5, 6).

Test 1 (healthy cycles): 5 consecutive clean ``rc=0`` exits with
``last_tool_called`` populated route to the new helper. Supervisor stays
``_started``; ``_respawn_history`` stays empty (A3 invariant).

Test 2 (audit schema contract): exercise the 6-field schema (§ 8) end-
to-end against one cycle; assert ``compaction_triggered is False``
invariant (A4).

Test 5 (unexpected-exit regression): ``rc=-9`` crash exits without prior
MCP activity still route to the existing failure-cap path. Mirrors
``test_supervisor_base.py::test_three_respawns_per_hour_cap_halts_supervisor``.

Test 6 (mixed-mode A3 invariant): healthy cycles interleaved with
unexpected exits do NOT clear or decrement the failure-recovery cap
counter. Healthy cycles add zero to ``_respawn_history``; cap still
fires once enough crashes accumulate.

Note on cap arithmetic (spec § 12 ambiguity): the existing cap logic is
``len(_respawn_history) >= cap`` checked *before* the append, with the
audit row written *before* the cap check. With ``cap=3``, the supervisor
respawns on detection events 1-3 (appending to history each time) and
halts on event 4 — yielding 4 ``dispatch.subprocess_unexpected_exit``
audit rows and ``len(_respawn_history) == 3`` at halt. This file's Test
5 and Test 6 follow the actual cap arithmetic; see complete_dispatch
notes for the surfaced spec deviation.
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator, Callable
from datetime import timedelta
from pathlib import Path

import pytest
import pytest_asyncio

from meridian_orchestrator.agents.registry import AgentRegistry
from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.bridge.activity import record_mcp_call
from meridian_orchestrator.bridge.engineer_supervisor import EngineerSupervisor
from meridian_orchestrator.bridge.supervisor_base import (
    HaltedSupervisorError,
    SupervisorLaunchContext,
    SupervisorSubprocessHandle,
)
from meridian_orchestrator.config import Settings
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage

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


# ----- Fake subprocess ----------------------------------------------------


class _ControllableProc:
    """Subprocess fake whose lifecycle is driven by the test.

    Mirrors ``test_compaction.py``'s extended fake: adds
    :meth:`signal_clean_exit` (rc=0) and :meth:`signal_crash_exit` (rc=-9)
    so the test can drive subprocess-exit detection at deterministic
    points without involving the watchdog's terminate/kill chain.
    """

    def __init__(self) -> None:
        self._returncode: int | None = None
        self._exit = asyncio.Event()
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
        if self._returncode is None:
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

    def signal_crash_exit(self) -> None:
        if self._returncode is None:
            self._returncode = -9
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
    proc_queue: list[_ControllableProc],
    cycle_completed_cap: int = 10,
    cycle_completed_window_seconds: int = 60,
) -> EngineerSupervisor:
    agents_dir = tmp_path / "config" / "agents"
    _seed_repo_yamls(agents_dir)
    token_store = TokenStore(service=service)
    await token_store.issue(
        agent_id=None, role="engineer", label="supervisor-test"
    )
    registry = AgentRegistry(agents_dir, token_store=token_store)
    await registry.initial_load()

    async def launcher(
        ctx: SupervisorLaunchContext,
    ) -> SupervisorSubprocessHandle:
        if not proc_queue:
            proc_queue.append(_ControllableProc())
        return proc_queue.pop(0)

    supervisor = EngineerSupervisor(
        registry=registry,
        working_directory=settings.engineer_supervisor_dir,
        storage=storage,
        token_store=token_store,
        subprocess_launcher=launcher,
        # Long enough that heartbeat-timeout does not fire under the
        # test's per-cycle cadence; short check_interval keeps the
        # watchdog's exit-detection latency under ~50ms.
        timeout_seconds=10,
        check_interval_seconds=0.05,
        respawn_cap=3,
        respawn_window_seconds=60,
    )
    # β-S11-4 commit B spin-guard: test fixture overrides applied
    # post-construction because the A7 uniform mandate keeps the
    # ``cycle_completed_cap`` / ``cycle_completed_window_seconds``
    # parameters off ``EngineerSupervisor.__init__`` (defaults inherit
    # via super() in production). Test fixture defaults sit above any
    # non-spin-guard test's cycle count so Tests 1/2/6 exercise
    # healthy-cycle behavior without tripping the guard; Test 3
    # overrides to cap=3, window=1 to exercise the guard.
    supervisor._cycle_completed_cap = cycle_completed_cap
    supervisor._cycle_completed_window = timedelta(
        seconds=cycle_completed_window_seconds
    )
    return supervisor


async def _drive_healthy_cycle(
    supervisor: EngineerSupervisor,
    storage: SqliteStorage,
    proc: _ControllableProc,
    *,
    tool_name: str = "get_window_state",
    wait_for_halt: bool = False,
) -> str:
    """Populate ``last_tool_called`` via record_mcp_call, then signal
    clean exit. Wait for respawn (default) or halt (``wait_for_halt``).
    Returns the dispatch_id that just completed (pre-respawn)."""
    pre_id = supervisor._current_dispatch_id
    assert pre_id is not None
    token = supervisor._current_token
    assert token is not None
    await record_mcp_call(storage, token.record.token_id, tool_name)
    proc.signal_clean_exit()
    if wait_for_halt:
        await _wait_until(lambda: supervisor.is_halted, timeout=4.0)
    else:
        await _wait_until(
            lambda: supervisor._current_dispatch_id is not None
            and supervisor._current_dispatch_id != pre_id,
            timeout=4.0,
        )
    return pre_id


async def _drive_unexpected_exit(
    supervisor: EngineerSupervisor,
    proc: _ControllableProc,
    *,
    wait_for_halt: bool = False,
) -> str:
    """Trigger an rc=-9 crash exit without prior MCP activity. Wait for
    the supervisor to either respawn (default) or halt (wait_for_halt)."""
    pre_id = supervisor._current_dispatch_id
    assert pre_id is not None
    proc.signal_crash_exit()
    if wait_for_halt:
        await _wait_until(lambda: supervisor.is_halted, timeout=4.0)
    else:
        await _wait_until(
            lambda: supervisor._current_dispatch_id is not None
            and supervisor._current_dispatch_id != pre_id,
            timeout=4.0,
        )
    return pre_id


# ----- Tests --------------------------------------------------------------


async def test_repeated_healthy_cycles_do_not_halt_supervisor(
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """Spec § 11 Test 1: 5 healthy cycle-completed exits in sequence
    leave the supervisor started, respawn-history empty (A3), and
    produce 5 status='completed' dispatch rows + 5 audit rows of the
    new ``dispatch.supervisor_cycle_completed`` action.

    Commit B addendum: ``_cycle_history`` grows by 5 (one entry per
    cycle) and the spin guard does NOT fire — the test fixture's
    ``cycle_completed_cap`` (10) sits above the cycle count.
    """
    # 1 initial + 5 respawn = 6 procs
    procs = [_ControllableProc() for _ in range(6)]
    proc_queue = list(procs)
    supervisor = await _build_supervisor(
        storage,
        settings,
        tmp_path,
        service="cycle-completed-5x",
        proc_queue=proc_queue,
    )

    await supervisor.start()
    try:
        for i in range(5):
            await _drive_healthy_cycle(supervisor, storage, procs[i])

        assert supervisor.is_halted is False
        assert supervisor._started is True
        assert len(supervisor._respawn_history) == 0
        assert len(supervisor._cycle_history) == 5

        completed_rows = await storage.fetch_all(
            "SELECT id FROM dispatches WHERE role = ? AND status = 'completed'",
            ("engineer-supervisor",),
        )
        assert len(completed_rows) == 5

        cycle_audits = await storage.fetch_all(
            "SELECT id FROM audit_log "
            "WHERE action = 'dispatch.supervisor_cycle_completed'"
        )
        assert len(cycle_audits) == 5

        unexpected = await storage.fetch_all(
            "SELECT id FROM audit_log "
            "WHERE action = 'dispatch.subprocess_unexpected_exit'"
        )
        assert len(unexpected) == 0

        # No spin-guard halt fired (window not exceeded under realistic cadence)
        spin_halt = await storage.fetch_all(
            "SELECT 1 FROM audit_log WHERE action = ?",
            ("dispatch.supervisor_cycle_spin_halt",),
        )
        assert len(spin_halt) == 0
    finally:
        await supervisor.shutdown()


async def test_audit_event_schema_contract(
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """Spec § 11 Test 2 / § 8: drive one cycle. Assert the single
    ``dispatch.supervisor_cycle_completed`` audit row has all 6 schema
    fields with correct types and the A4 ``compaction_triggered is
    False`` invariant.
    """
    procs = [_ControllableProc() for _ in range(2)]
    proc_queue = list(procs)
    supervisor = await _build_supervisor(
        storage,
        settings,
        tmp_path,
        service="cycle-completed-schema",
        proc_queue=proc_queue,
    )

    await supervisor.start()
    pre_id = supervisor._current_dispatch_id
    try:
        cycle_id = await _drive_healthy_cycle(supervisor, storage, procs[0])
        assert cycle_id == pre_id

        rows = await storage.fetch_all(
            "SELECT target, details_json FROM audit_log "
            "WHERE action = 'dispatch.supervisor_cycle_completed'"
        )
        assert len(rows) == 1
        assert rows[0]["target"] == cycle_id

        details = json.loads(rows[0]["details_json"])
        assert set(details.keys()) == {
            "supervisor_role",
            "dispatch_id",
            "last_tool_called",
            "cycle_duration_seconds",
            "rc",
            "compaction_triggered",
        }
        assert details["supervisor_role"] == "engineer-supervisor"
        assert isinstance(details["dispatch_id"], str)
        assert details["dispatch_id"] == cycle_id
        assert details["last_tool_called"] == "get_window_state"
        assert details["rc"] == 0
        # A4 invariant: cycle-completed never coincides with compaction
        # (compaction priority routes those exits to <role>.compaction_completed).
        assert details["compaction_triggered"] is False
        cd = details["cycle_duration_seconds"]
        assert cd is None or (isinstance(cd, float) and cd >= 0.0)
    finally:
        await supervisor.shutdown()


async def test_unexpected_exits_still_halt_supervisor(
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """Spec § 12 Test 5 (regression guard): rc=-9 crash exits without
    prior MCP activity still route to the failure path and trip the
    respawn-cap halt. Mirrors
    ``test_supervisor_base.py::test_three_respawns_per_hour_cap_halts_supervisor``
    cap arithmetic — with cap=3, the supervisor halts on the 4th
    detection event (3 prior appends to history + 1 cap-fire). β-S11-4
    commit A does not change the cap path; the new classification
    branch is bypassed because ``rc != 0``.

    Deviation from spec § 12 Test 5 literal text: assertions adapted
    to the actual cap arithmetic (4 unexpected_exit audit rows, not 3
    — the spec assertion "exactly 3" is mathematically inconsistent
    with the documented `len >= cap` check ordering). Surfaced via
    complete_dispatch.queue_items_raised.
    """
    procs = [_ControllableProc() for _ in range(4)]
    proc_queue = list(procs)
    supervisor = await _build_supervisor(
        storage,
        settings,
        tmp_path,
        service="cycle-completed-unex-cap",
        proc_queue=proc_queue,
    )

    await supervisor.start()
    try:
        await _drive_unexpected_exit(supervisor, procs[0])
        await _drive_unexpected_exit(supervisor, procs[1])
        await _drive_unexpected_exit(supervisor, procs[2])
        await _drive_unexpected_exit(
            supervisor, procs[3], wait_for_halt=True
        )

        assert supervisor.is_halted is True
        assert len(supervisor._respawn_history) == 3

        unex_rows = await storage.fetch_all(
            "SELECT id FROM audit_log "
            "WHERE action = 'dispatch.subprocess_unexpected_exit'"
        )
        assert len(unex_rows) == 4

        cap_rows = await storage.fetch_all(
            "SELECT id FROM audit_log "
            "WHERE action = 'dispatch.heartbeat_timeout_cap'"
        )
        assert len(cap_rows) == 1

        cycle_rows = await storage.fetch_all(
            "SELECT id FROM audit_log "
            "WHERE action = 'dispatch.supervisor_cycle_completed'"
        )
        assert len(cycle_rows) == 0
    finally:
        await supervisor.shutdown()


async def test_healthy_cycles_do_not_clear_unexpected_exit_history(
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """Spec § 12 Test 6 (mixed-mode A3-invariant guard): healthy cycle-
    completed exits interleaved with unexpected exits do NOT clear or
    decrement ``_respawn_history``. The cap-fire path still trips once
    enough crashes accumulate.

    Deviation from spec § 12 Test 6 literal text: same cap-arithmetic
    shape as Test 5 — to actually halt with cap=3, four unexpected
    exits are required (not three). Sequence here: 2 unex → 2 healthy
    → 2 unex (4 unex total, halt fires on the 4th). Surfaced via
    complete_dispatch.queue_items_raised.
    """
    # 1 initial + 5 respawn = 6 procs
    procs = [_ControllableProc() for _ in range(6)]
    proc_queue = list(procs)
    supervisor = await _build_supervisor(
        storage,
        settings,
        tmp_path,
        service="cycle-completed-mixed",
        proc_queue=proc_queue,
    )

    await supervisor.start()
    try:
        # 2 unexpected exits → history grows to 2
        await _drive_unexpected_exit(supervisor, procs[0])
        await _drive_unexpected_exit(supervisor, procs[1])
        assert len(supervisor._respawn_history) == 2

        # 2 healthy cycles → history stays at 2 (A3 invariant)
        await _drive_healthy_cycle(supervisor, storage, procs[2])
        await _drive_healthy_cycle(supervisor, storage, procs[3])
        assert len(supervisor._respawn_history) == 2

        # 3rd unexpected → append (history=3), still not halted
        await _drive_unexpected_exit(supervisor, procs[4])
        assert len(supervisor._respawn_history) == 3
        assert supervisor.is_halted is False

        # 4th unexpected → cap fires (3 >= 3) → halt
        await _drive_unexpected_exit(
            supervisor, procs[5], wait_for_halt=True
        )

        assert supervisor.is_halted is True
        assert len(supervisor._respawn_history) == 3

        cycle_rows = await storage.fetch_all(
            "SELECT id FROM audit_log "
            "WHERE action = 'dispatch.supervisor_cycle_completed'"
        )
        assert len(cycle_rows) == 2

        unex_rows = await storage.fetch_all(
            "SELECT id FROM audit_log "
            "WHERE action = 'dispatch.subprocess_unexpected_exit'"
        )
        assert len(unex_rows) == 4
    finally:
        await supervisor.shutdown()


async def test_spin_guard_halts_supervisor_after_N_fast_cycles(  # noqa: N802
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """Spec § 11 Test 3 (commit B): drive N=3 cycle-completed exits
    inside a W=1s window. The 3rd cycle's append makes
    ``len(_cycle_history) == 3``; the cap check (``>= 3``) fires and
    ``_halt_spin_guard`` writes a ``dispatch.supervisor_cycle_spin_halt``
    audit row, sets ``_halted = True`` and ``_started = False``. The 3
    cycle-completed audit rows are still written (audit precedes the
    spin-guard block by construction).
    """
    # 1 initial + 2 respawns = 3 procs (the 3rd cycle halts instead
    # of respawning, so no proc is consumed for a 4th launch).
    procs = [_ControllableProc() for _ in range(3)]
    proc_queue = list(procs)
    supervisor = await _build_supervisor(
        storage,
        settings,
        tmp_path,
        service="cycle-completed-spin-halt",
        proc_queue=proc_queue,
        cycle_completed_cap=3,
        cycle_completed_window_seconds=1,
    )

    await supervisor.start()
    try:
        await _drive_healthy_cycle(supervisor, storage, procs[0])
        await _drive_healthy_cycle(supervisor, storage, procs[1])
        await _drive_healthy_cycle(
            supervisor, storage, procs[2], wait_for_halt=True
        )

        assert supervisor.is_halted is True
        assert supervisor._started is False

        spin_halt_rows = await storage.fetch_all(
            "SELECT target, details_json FROM audit_log "
            "WHERE action = 'dispatch.supervisor_cycle_spin_halt'"
        )
        assert len(spin_halt_rows) == 1

        details = json.loads(spin_halt_rows[0]["details_json"])
        assert isinstance(details["dispatch_ids"], list)
        assert len(details["dispatch_ids"]) == 3
        assert all(
            isinstance(d, str) and d for d in details["dispatch_ids"]
        )
        assert details["n"] == 3
        assert details["window_seconds"] == 1

        cycle_rows = await storage.fetch_all(
            "SELECT id FROM audit_log "
            "WHERE action = 'dispatch.supervisor_cycle_completed'"
        )
        assert len(cycle_rows) == 3

        with pytest.raises(HaltedSupervisorError):
            await supervisor.start()
    finally:
        await supervisor.shutdown()
