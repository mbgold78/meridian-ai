"""End-to-end PM continuous-loop integration tests
(M5-continuous-pm-driver commit 9 closing-commit work).

Three scenario tests covering the cross-path that per-component MCP
wrapper tests don't exercise (per F10.2 Q3-B):

1. **Per-tick chained consumer sequence** — single tick, full
   consumer chain in natural ordering: get_loop_state → declare focus
   → record spin rationale → record sample event → wait_for_next_tick.
   Asserts each consumer's state-changing writes land + each consumer's
   read-side input reflects prior writes.

2. **Cross-tick chain — focus state evolution** — 3 ticks. Tick 1
   declares focus; Tick 2 leaves focus unchanged + records spin
   rationale; Tick 3 completes prior focus + declares new focus.
   Asserts focus history (auto-supersede semantics + completion
   audit shape) + spin-rationale row count progression.

3. **Conserve transition triggers compaction lifecycle** — snapshot
   starts at normal; PM's first tick observes pace='normal'; snapshot
   flips to conserve; PM's second tick observes pace='conserve' and
   runs the compaction-prep four-step sequence (author handoff → write
   handoff → signal compaction → exit). Supervisor watchdog observes
   clean exit, audits pm.compaction_completed, marks dispatch
   compacted, respawns. New PM reads handoff at session-start.

Per-component MCP wrapper tests (test_pm_loop_mcp_wrapper.py +
test_pm_focus_mcp_wrapper.py + test_pm_spin_mcp_wrapper.py +
test_audit_sample_mcp_wrapper.py + test_pm_handoff_mcp_wrapper.py)
already cover individual handler permission gates + Pydantic
boundaries + happy-path round-trip. test_pm_compaction_e2e.py
already covers the handoff-write→signal→respawn→handoff-read
cycle in isolation. This file adds the chained-sequence cross-path
that none of the above exercise together.

Per memory `feedback_integration_defects_need_production_shape_tests`:
defects only surface when independently-evolved code paths actually
integrate end-to-end. These tests use real storage + real filesystem
+ real supervisor lifecycle + a controllable snapshot provider; no
mocks of the cross-path components themselves.
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator, Callable
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest
import pytest_asyncio

from meridian_orchestrator.agents.registry import AgentRegistry
from meridian_orchestrator.audit.sampler import (
    RecordSampleableEventInput,
    Sampler,
    record_sampleable_event,
)
from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.bridge.oauth_usage import CachedSnapshot
from meridian_orchestrator.bridge.pm_supervisor import PMSupervisor
from meridian_orchestrator.bridge.supervisor_base import (
    SupervisorLaunchContext,
    SupervisorSubprocessHandle,
)
from meridian_orchestrator.config import Settings
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.tools.pm_focus import (
    CompleteCurrentFocusInput,
    DeclareCurrentFocusInput,
    complete_current_focus,
    declare_current_focus,
)
from meridian_orchestrator.tools.pm_handoff import (
    WritePmHandoffInput,
    get_most_recent_handoff,
    write_pm_handoff,
)
from meridian_orchestrator.tools.pm_loop import (
    get_loop_state,
    wait_for_next_tick,
)
from meridian_orchestrator.tools.pm_spin import (
    RecordSpinRationaleInput,
    record_spin_rationale,
)
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


# ---------------------------------------------------------------------------
# Snapshot helpers
# ---------------------------------------------------------------------------


def _make_snapshot(*, five_hour_utilization: float) -> CachedSnapshot:
    """Construct a CachedSnapshot with the given subscription_5h
    utilization value. The 7-day Opus counter and burn rate inputs
    stay at fixed mid-range values; only the 5h utilization varies
    to drive the conserve transition."""
    now = datetime.now(tz=UTC)
    payload: dict[str, Any] = {
        "five_hour": {
            "utilization": five_hour_utilization,
            "resets_at": (now + timedelta(hours=4)).isoformat(),
        },
        "seven_day_opus": {
            "utilization": 40.0,
            "resets_at": (now + timedelta(days=6)).isoformat(),
        },
        "extra_usage": {"is_enabled": False},
    }
    return CachedSnapshot(payload=payload, polled_at=now, null_counters=[])


# ---------------------------------------------------------------------------
# Test 1 — per-tick chained consumer sequence
# ---------------------------------------------------------------------------


async def test_per_tick_chained_consumer_sequence(
    storage: SqliteStorage,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """One PM tick exercises the full consumer chain:
    get_loop_state (read) → declare_current_focus (write) →
    record_spin_rationale (write) → record_sampleable_event (write) →
    wait_for_next_tick (sleep). Asserts each consumer's read-side input
    + write-side row.

    Cross-path defect surface: the handlers compose against a shared
    storage + sampler + snapshot. A defect like "spin_rationale wrote
    a row but record_sampleable_event's source_event_id query against
    pm_spin_rationale_log doesn't find it" would surface here but not
    in per-component tests.
    """
    snapshot = _make_snapshot(five_hour_utilization=30.0)

    def get_snapshot() -> CachedSnapshot:
        return snapshot

    sampler = Sampler(sampling_percentage=1.0)  # 100% — every emit is captured

    # Monkeypatch sleep so wait_for_next_tick returns immediately
    captured_sleep: list[float] = []

    async def fake_sleep(seconds: float) -> None:
        captured_sleep.append(seconds)

    monkeypatch.setattr(
        "meridian_orchestrator.tools.pm_loop.asyncio.sleep", fake_sleep
    )

    # Step 1: get_loop_state — pre-tick read; current_focus None
    pre_state = await get_loop_state(
        storage=storage,
        get_snapshot=get_snapshot,
        caller_agent_id="pm-agent-1",
        caller_role="pm",
    )
    assert pre_state.subscription_5h_state.pace_recommendation in (
        "normal",
        "upgrade",
    )
    assert pre_state.current_focus is None

    # Step 2: declare a focus
    declared = await declare_current_focus(
        storage=storage,
        request=DeclareCurrentFocusInput(
            objective="ship per-tick e2e test",
            output_type="code_change",
            acceptance_criteria="test_per_tick_chained_consumer_sequence passes",
        ),
        caller_agent_id="pm-agent-1",
        caller_role="pm",
    )

    # Step 3: record spin rationale for this tick
    spin_out = await record_spin_rationale(
        storage=storage,
        request=RecordSpinRationaleInput(
            decision="kept_alive",
            rationale_tag="queued_work",
            rationale_free_text="actively shipping commit 9 e2e",
        ),
        caller_role="pm",
    )

    # Step 4: record sampleable event for the spin emit
    # (source_event_kind='pm.spin_rationale.recorded' references
    # pm_spin_rationale_log.id per audit/sampler.py)
    await record_sampleable_event(
        storage=storage,
        sampler=sampler,
        request=RecordSampleableEventInput(
            source_event_kind="pm.spin_rationale.recorded",
            source_event_id=spin_out.spin_rationale_id,
        ),
        caller_role="pm",
    )

    # Step 5: wait_for_next_tick (mocked sleep)
    tick_out = await wait_for_next_tick(
        get_tick_seconds=lambda: 60.0,
        caller_role="pm",
    )
    assert tick_out.slept_seconds == 60.0
    assert captured_sleep == [60.0]

    # Cross-path verification: post-tick get_loop_state surfaces the
    # newly-declared focus
    post_state = await get_loop_state(
        storage=storage,
        get_snapshot=get_snapshot,
        caller_agent_id="pm-agent-1",
        caller_role="pm",
    )
    assert post_state.current_focus is not None
    assert post_state.current_focus.id == declared.focus_id
    assert post_state.current_focus.objective == "ship per-tick e2e test"

    # Cross-path verification: storage rows landed
    focus_rows = await storage.fetch_all(
        "SELECT id FROM pm_current_focus WHERE id = ?", (declared.focus_id,)
    )
    assert len(focus_rows) == 1

    spin_rows = await storage.fetch_all(
        "SELECT id, decision, rationale_tag FROM pm_spin_rationale_log "
        "WHERE id = ?",
        (spin_out.spin_rationale_id,),
    )
    assert len(spin_rows) == 1
    assert spin_rows[0]["decision"] == "kept_alive"
    assert spin_rows[0]["rationale_tag"] == "queued_work"

    sample_rows = await storage.fetch_all(
        "SELECT source_event_kind, source_event_id, re_eval_state "
        "FROM audit_sample_log "
        "WHERE source_event_kind = 'pm.spin_rationale.recorded'"
    )
    assert len(sample_rows) == 1
    assert sample_rows[0]["source_event_id"] == spin_out.spin_rationale_id
    assert sample_rows[0]["re_eval_state"] == "pending"


# ---------------------------------------------------------------------------
# Test 2 — cross-tick chain (focus state evolution)
# ---------------------------------------------------------------------------


async def test_cross_tick_chain_focus_state_evolution(
    storage: SqliteStorage,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Three PM ticks demonstrating focus state evolution:

    - Tick 1: declare focus A; record spin rationale
    - Tick 2: leave focus A active; record spin rationale (no
      focus state change)
    - Tick 3: complete focus A; declare focus B (auto-supersede if
      A weren't already completed; explicit complete-then-declare
      is the clean shape); record spin rationale

    Cross-path defect surface: focus completion + new declaration in
    the same tick must produce the right audit chain (completed →
    declared on the new id, NOT superseded). The
    auto-supersede-vs-explicit-complete distinction is a real
    subtlety in pm_focus.py.
    """
    snapshot = _make_snapshot(five_hour_utilization=30.0)

    def get_snapshot() -> CachedSnapshot:
        return snapshot

    async def fake_sleep(seconds: float) -> None:
        pass

    monkeypatch.setattr(
        "meridian_orchestrator.tools.pm_loop.asyncio.sleep", fake_sleep
    )

    # Tick 1: declare focus A + spin rationale
    focus_a = await declare_current_focus(
        storage=storage,
        request=DeclareCurrentFocusInput(
            objective="focus A",
            output_type="document",
            acceptance_criteria="doc A landed",
        ),
        caller_agent_id="pm-agent-1",
        caller_role="pm",
    )
    await record_spin_rationale(
        storage=storage,
        request=RecordSpinRationaleInput(
            decision="kept_alive", rationale_tag="queued_work"
        ),
        caller_role="pm",
    )
    await wait_for_next_tick(get_tick_seconds=lambda: 60.0, caller_role="pm")

    # Tick 2: focus state unchanged; spin rationale only
    state_t2 = await get_loop_state(
        storage=storage,
        get_snapshot=get_snapshot,
        caller_agent_id="pm-agent-1",
        caller_role="pm",
    )
    assert state_t2.current_focus is not None
    assert state_t2.current_focus.id == focus_a.focus_id

    await record_spin_rationale(
        storage=storage,
        request=RecordSpinRationaleInput(
            decision="kept_alive", rationale_tag="heartbeat_cheap"
        ),
        caller_role="pm",
    )
    await wait_for_next_tick(get_tick_seconds=lambda: 60.0, caller_role="pm")

    # Tick 3: complete focus A; declare focus B; spin rationale
    await complete_current_focus(
        storage=storage,
        request=CompleteCurrentFocusInput(
            contract_validated=True,
            actual_output_summary="doc A landed cleanly",
        ),
        caller_agent_id="pm-agent-1",
        caller_role="pm",
    )
    focus_b = await declare_current_focus(
        storage=storage,
        request=DeclareCurrentFocusInput(
            objective="focus B",
            output_type="review",
            acceptance_criteria="review B complete",
        ),
        caller_agent_id="pm-agent-1",
        caller_role="pm",
    )
    await record_spin_rationale(
        storage=storage,
        request=RecordSpinRationaleInput(
            decision="kept_alive", rationale_tag="queued_work"
        ),
        caller_role="pm",
    )
    await wait_for_next_tick(get_tick_seconds=lambda: 60.0, caller_role="pm")

    # Cross-path verification: focus A completed; focus B is active
    focus_a_row = await storage.fetch_one(
        "SELECT contract_validated_bool, completed_at FROM pm_current_focus "
        "WHERE id = ?",
        (focus_a.focus_id,),
    )
    assert focus_a_row is not None
    assert focus_a_row["contract_validated_bool"] == 1
    assert focus_a_row["completed_at"] is not None

    state_post = await get_loop_state(
        storage=storage,
        get_snapshot=get_snapshot,
        caller_agent_id="pm-agent-1",
        caller_role="pm",
    )
    assert state_post.current_focus is not None
    assert state_post.current_focus.id == focus_b.focus_id

    # Cross-path verification: 3 spin rationale rows, one per tick
    spin_count_row = await storage.fetch_one(
        "SELECT COUNT(*) AS n FROM pm_spin_rationale_log"
    )
    assert spin_count_row is not None
    assert spin_count_row["n"] == 3

    # Cross-path verification: audit chain for focus A includes
    # declared + completed; for focus B, declared (NOT superseded —
    # because A was explicitly completed first, not superseded)
    focus_a_actions = [
        r["action"]
        for r in await storage.fetch_all(
            "SELECT action FROM audit_log WHERE target = ? "
            "ORDER BY timestamp, rowid",
            (focus_a.focus_id,),
        )
    ]
    assert "pm.current_focus.declared" in focus_a_actions
    assert "pm.current_focus.completed" in focus_a_actions
    assert "pm.current_focus.superseded" not in focus_a_actions

    focus_b_actions = [
        r["action"]
        for r in await storage.fetch_all(
            "SELECT action FROM audit_log WHERE target = ?",
            (focus_b.focus_id,),
        )
    ]
    assert "pm.current_focus.declared" in focus_b_actions


# ---------------------------------------------------------------------------
# Test 3 — conserve transition triggers compaction lifecycle
# ---------------------------------------------------------------------------


class _ControllableProc:
    """Lightweight subprocess fake mirroring the supervisor-test
    pattern. Cooperative-terminate by default."""

    def __init__(self) -> None:
        self._returncode: int | None = None
        self._exit = asyncio.Event()

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
        if self._returncode is None:
            self._returncode = 0
            self._exit.set()

    def kill(self) -> None:
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


async def test_conserve_transition_triggers_compaction_lifecycle(
    storage: SqliteStorage,
    settings: Settings,
    tmp_path: Path,
) -> None:
    """End-to-end conserve transition.

    PM is in continuous-mode with active dispatch. Initial snapshot at
    30% utilization; PM's first tick observes pace='normal'. Snapshot
    flips to 85% (>= 80% conserve threshold); PM's second tick
    observes pace='conserve' and runs the compaction-prep four-step
    sequence: author handoff → write_pm_handoff → signal_compaction
    → exit clean. Supervisor watchdog observes clean exit + compaction
    flag, audits pm.compaction_completed, marks dispatch compacted,
    respawns. New PM (post-respawn) reads the handoff via
    get_most_recent_handoff and observes content matching what the
    pre-compaction PM wrote.

    Cross-path defect surface: the pace_recommendation derivation in
    get_window_state must align with the actual conserve threshold
    (80%); the sequence handoff_written→signal_compaction→clean_exit
    must produce the right audit chain in the right order; the
    new PM's get_most_recent_handoff must observe the handoff written
    by the prior PM (filesystem-cross-process consistency).

    **Test seam**: this test does NOT monkeypatch ``asyncio.sleep``
    globally because the supervisor's watchdog also uses
    ``asyncio.sleep`` for its check_interval polling — globally
    replacing sleep would break the watchdog. Instead we use a
    tiny ``tick_seconds=0.01`` value for ``wait_for_next_tick``
    so the actual sleep is near-instant. Real-clock sleeps of
    ~10ms are within the test's overall ~5s timeout budget.
    """
    handoff_dir = tmp_path / "pm-handoffs"

    def get_handoff_dir() -> Path:
        return handoff_dir

    # Mutable snapshot reference; PM observes whatever's in here
    # at each get_loop_state call
    snapshot_holder: list[CachedSnapshot] = [
        _make_snapshot(five_hour_utilization=30.0)
    ]

    def get_snapshot() -> CachedSnapshot:
        return snapshot_holder[0]

    # Setup PM supervisor with controllable proc queue
    agents_dir = tmp_path / "config" / "agents"
    _seed_repo_yamls(agents_dir)
    token_store = TokenStore(service="continuous-loop-conserve")
    issued = await token_store.issue(
        agent_id=None, role="pm", label="pm-supervisor-test"
    )
    pm_agent_id = issued.record.agent_id
    registry = AgentRegistry(agents_dir, token_store=token_store)
    await registry.initial_load()

    initial_proc = _ControllableProc()
    next_proc = _ControllableProc()
    proc_queue: list[_ControllableProc] = [initial_proc, next_proc]

    async def launcher(
        ctx: SupervisorLaunchContext,
    ) -> SupervisorSubprocessHandle:
        return proc_queue.pop(0)

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
    await supervisor.start()
    initial_dispatch_id = supervisor.current_dispatch_id
    assert initial_dispatch_id is not None

    try:
        # --- Tick 1: pace='normal'; PM continues iterating ---
        state_t1 = await get_loop_state(
            storage=storage,
            get_snapshot=get_snapshot,
            caller_agent_id=pm_agent_id,
            caller_role="pm",
        )
        assert state_t1.subscription_5h_state.pace_recommendation in (
            "normal",
            "upgrade",
        )
        await record_spin_rationale(
            storage=storage,
            request=RecordSpinRationaleInput(
                decision="kept_alive", rationale_tag="queued_work"
            ),
            caller_role="pm",
        )
        await wait_for_next_tick(
            get_tick_seconds=lambda: 0.01, caller_role="pm"
        )

        # --- Snapshot flips to conserve (>= 80% utilization) ---
        snapshot_holder[0] = _make_snapshot(five_hour_utilization=85.0)

        # --- Tick 2: pace='conserve'; compaction-prep sequence ---
        state_t2 = await get_loop_state(
            storage=storage,
            get_snapshot=get_snapshot,
            caller_agent_id=pm_agent_id,
            caller_role="pm",
        )
        assert state_t2.subscription_5h_state.pace_recommendation == "conserve"

        # Author handoff content (PM-prompt-driven; we synthesize here)
        handoff_content = (
            "# PM Compaction Handoff\n\n"
            "## current_focus\nNo active focus at conserve trigger.\n\n"
            "## planned_next\nResume continuous-mode iteration post-respawn.\n\n"
            "## open_questions\nNone.\n\n"
            "## recent_decisions_pointer\n/product/substrate/decisions/.\n\n"
            "## cohort_summary\nM5-continuous-pm-driver chain close.\n"
        )

        # Step 1 of compaction-prep: write_pm_handoff
        handoff_out = await write_pm_handoff(
            storage=storage,
            get_handoff_dir=get_handoff_dir,
            request=WritePmHandoffInput(content=handoff_content),
            caller_agent_id=pm_agent_id,
            caller_role="pm",
        )
        assert Path(handoff_out.path).exists()

        # Step 2 of compaction-prep: signal_compaction
        signal_out = await signal_compaction(
            storage=storage,
            supervisor=supervisor,
            request=SignalCompactionInput(
                iteration_marker="conserve-tick",
                proxy_value=0.85,
            ),
            caller_agent_id=pm_agent_id,
            caller_role="pm",
        )
        assert signal_out.accepted is True
        assert supervisor._compaction_pending is True

        # Step 3: subprocess clean-exits
        initial_proc.signal_clean_exit()

        # Step 4: wait for watchdog respawn
        await _wait_until(
            lambda: supervisor.current_dispatch_id is not None
            and supervisor.current_dispatch_id != initial_dispatch_id,
            timeout=4.0,
        )

        # --- Verification: full audit chain in PM namespace ---
        # handoff_written audits target=path
        path_actions = [
            r["action"]
            for r in await storage.fetch_all(
                "SELECT action FROM audit_log WHERE target = ?",
                (handoff_out.path,),
            )
        ]
        assert "pm.compaction.handoff_written" in path_actions

        # compaction events target=dispatch_id
        dispatch_actions = [
            r["action"]
            for r in await storage.fetch_all(
                "SELECT action FROM audit_log WHERE target = ? "
                "ORDER BY timestamp, rowid",
                (initial_dispatch_id,),
            )
        ]
        assert "pm.compaction_triggered" in dispatch_actions
        assert "pm.compaction_completed" in dispatch_actions
        # Engineer namespace explicitly absent (contract pin)
        assert "engineer.compaction_triggered" not in dispatch_actions
        assert "engineer.compaction_completed" not in dispatch_actions

        # Original dispatch row → 'compacted'; new row → 'running'
        old_row = await storage.fetch_one(
            "SELECT status FROM dispatches WHERE id = ?",
            (initial_dispatch_id,),
        )
        assert old_row is not None
        assert old_row["status"] == "compacted"
        new_row = await storage.fetch_one(
            "SELECT status FROM dispatches WHERE id = ?",
            (supervisor.current_dispatch_id,),
        )
        assert new_row is not None
        assert new_row["status"] == "running"

        # Compaction-respawn does not increment cap counter
        assert len(supervisor._respawn_history) == 0
        # Flag reset on fresh spawn
        assert supervisor._compaction_pending is False

        # --- Verification: new PM reads handoff at session-start ---
        # Simulates the new PM subprocess's startup
        # get_most_recent_handoff call.
        read_out = await get_most_recent_handoff(
            get_handoff_dir=get_handoff_dir,
            caller_role="pm",
        )
        assert read_out.content == handoff_content
        assert read_out.written_at == handoff_out.written_at

        # Audit details for handoff_written carry path/size for
        # operator-side handoff-quality observability
        handoff_audit = await storage.fetch_one(
            "SELECT details_json FROM audit_log "
            "WHERE action = 'pm.compaction.handoff_written' AND target = ?",
            (handoff_out.path,),
        )
        assert handoff_audit is not None
        details = json.loads(handoff_audit["details_json"])
        assert details["content_size"] == len(handoff_content)
    finally:
        await supervisor.shutdown()
