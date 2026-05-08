"""Direct-handler tests for ``tools.pm_loop``.

Exercises :func:`tools.pm_loop.wait_for_next_tick` and
:func:`tools.pm_loop.get_loop_state` against a migrated SQLite DB
+ a fake OAuth-usage snapshot.

Covers:

- ``wait_for_next_tick``: sleep duration matches configured cadence;
  monkeypatch asyncio.sleep for deterministic cadence assertion (per
  F8.2 Q6-A; doesn't expand production-code-surface for testability).
  Permission gate at handler layer.
- ``get_loop_state``: aggregation composition (subscription_5h +
  subscription_7d_opus + burn_rate + current_focus); cold-start
  snapshot passes through unknown-pace placeholders cleanly; active
  pm_current_focus surfaces; permission gate.
- Settings ``pm_loop_tick_seconds``: default 60.0 + range validation.

Permission tests at MCP wrapper layer + Pydantic boundary tests live
in ``tests/integration/test_pm_loop_mcp_wrapper.py``.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest
import pytest_asyncio

from meridian_orchestrator.bridge.oauth_usage import CachedSnapshot
from meridian_orchestrator.config import Settings
from meridian_orchestrator.errors import (
    PermissionError as OrchestratorPermissionError,
)
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.tools.pm_focus import (
    DeclareCurrentFocusInput,
    declare_current_focus,
)
from meridian_orchestrator.tools.pm_loop import (
    GetLoopStateOutput,
    WaitForNextTickOutput,
    get_loop_state,
    wait_for_next_tick,
)


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


def _fake_snapshot_with_buckets() -> CachedSnapshot:
    """Build a CachedSnapshot whose bucket payload satisfies
    get_window_state's happy-path branches for both subscription_5h
    and subscription_7d_opus + has finite five_hour data for
    get_burn_rate's window-attribution path."""
    now = datetime.now(tz=UTC)
    payload: dict[str, Any] = {
        "five_hour": {
            "utilization": 25.0,
            "resets_at": (now + timedelta(hours=4)).isoformat(),
        },
        "seven_day_opus": {
            "utilization": 40.0,
            "resets_at": (now + timedelta(days=6)).isoformat(),
        },
        "extra_usage": {"is_enabled": False},
    }
    return CachedSnapshot(payload=payload, polled_at=now, null_counters=[])


def _no_snapshot() -> CachedSnapshot | None:
    """Cold-start: get_snapshot returns None; get_window_state +
    get_burn_rate fall through to unknown-placeholder paths."""
    return None


# ---------------------------------------------------------------------------
# wait_for_next_tick — sleep cadence + monkeypatch test seam
# ---------------------------------------------------------------------------


async def test_wait_for_next_tick_sleeps_configured_cadence(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The handler calls asyncio.sleep with the configured tick value
    (resolved via the get_tick_seconds closure). Monkeypatch
    asyncio.sleep to capture the requested duration without actually
    sleeping; assert it matches Settings.pm_loop_tick_seconds.

    F8.2 Q6-A test seam: monkeypatch is the established Python
    pattern; doesn't trip production-code-surface-for-testability
    watch."""
    captured_durations: list[float] = []

    async def fake_sleep(seconds: float) -> None:
        captured_durations.append(seconds)

    monkeypatch.setattr("meridian_orchestrator.tools.pm_loop.asyncio.sleep", fake_sleep)

    out = await wait_for_next_tick(
        get_tick_seconds=lambda: 30.0,
        caller_role="pm",
    )
    assert isinstance(out, WaitForNextTickOutput)
    assert out.slept_seconds == 30.0
    assert captured_durations == [30.0]


async def test_wait_for_next_tick_resolves_cadence_at_call_time(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The closure resolves cadence at each invocation, not at handler
    construction. Mirrors the lazy-callable pattern used for
    get_oauth_usage_snapshot. Test: change the closure's return value
    between calls; verify each call uses the current value."""
    captured: list[float] = []

    async def fake_sleep(seconds: float) -> None:
        captured.append(seconds)

    monkeypatch.setattr("meridian_orchestrator.tools.pm_loop.asyncio.sleep", fake_sleep)

    current_tick = [60.0]

    def get_tick() -> float:
        return current_tick[0]

    await wait_for_next_tick(get_tick_seconds=get_tick, caller_role="pm")
    current_tick[0] = 5.0
    await wait_for_next_tick(get_tick_seconds=get_tick, caller_role="pm")

    assert captured == [60.0, 5.0]


@pytest.mark.parametrize(
    "non_pm_role", ["engineer", "implementer", "tester"]
)
async def test_wait_for_next_tick_non_pm_role_rejected(
    non_pm_role: str,
) -> None:
    """Engineer, Implementer, Tester callers rejected at handler's
    _require_role check. No sleep called."""
    with pytest.raises(OrchestratorPermissionError, match=non_pm_role):
        await wait_for_next_tick(
            get_tick_seconds=lambda: 60.0,
            caller_role=non_pm_role,
        )


# ---------------------------------------------------------------------------
# get_loop_state — read aggregation
# ---------------------------------------------------------------------------


async def test_get_loop_state_bundles_four_reads_happy_path(
    storage: SqliteStorage,
) -> None:
    """Aggregator returns subscription_5h + subscription_7d_opus +
    burn_rate + current_focus in one response. Happy-path snapshot
    with finite bucket data; current_focus is None (no active focus
    declared yet)."""
    out = await get_loop_state(
        storage=storage,
        get_snapshot=_fake_snapshot_with_buckets,
        caller_agent_id="pm-agent",
        caller_role="pm",
    )
    assert isinstance(out, GetLoopStateOutput)
    assert out.subscription_5h_state.bucket == "subscription_5h"
    assert out.subscription_7d_opus_state.bucket == "subscription_7d_opus"
    assert out.burn_rate is not None
    assert out.current_focus is None


async def test_get_loop_state_surfaces_active_focus(
    storage: SqliteStorage,
) -> None:
    """When PM has an active pm_current_focus row, the aggregator
    surfaces it in the current_focus field. Verifies the
    read_current_focus delegation path."""
    declared = await declare_current_focus(
        storage=storage,
        request=DeclareCurrentFocusInput(
            objective="loop test focus",
            output_type="document",
            acceptance_criteria="doc landed",
        ),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    out = await get_loop_state(
        storage=storage,
        get_snapshot=_fake_snapshot_with_buckets,
        caller_agent_id="pm-agent",
        caller_role="pm",
    )
    assert out.current_focus is not None
    assert out.current_focus.id == declared.focus_id
    assert out.current_focus.objective == "loop test focus"


async def test_get_loop_state_cold_start_propagates_unknown(
    storage: SqliteStorage,
) -> None:
    """When get_snapshot returns None (cold-start), the underlying
    get_window_state + get_burn_rate handlers return their unknown-
    placeholder shapes. Aggregator passes those through cleanly
    (doesn't add new failure semantics)."""
    out = await get_loop_state(
        storage=storage,
        get_snapshot=_no_snapshot,
        caller_agent_id="pm-agent",
        caller_role="pm",
    )
    # Cold-start: pace_recommendation='unknown' across both window-state
    # reads; get_burn_rate returns its no-snapshot placeholder.
    assert out.subscription_5h_state.pace_recommendation == "unknown"
    assert out.subscription_7d_opus_state.pace_recommendation == "unknown"
    assert out.current_focus is None


@pytest.mark.parametrize(
    "non_pm_role", ["engineer", "implementer", "tester"]
)
async def test_get_loop_state_non_pm_role_rejected(
    storage: SqliteStorage,
    non_pm_role: str,
) -> None:
    """Engineer, Implementer, Tester callers rejected at handler's
    _require_role check before any read happens."""
    with pytest.raises(OrchestratorPermissionError, match=non_pm_role):
        await get_loop_state(
            storage=storage,
            get_snapshot=_fake_snapshot_with_buckets,
            caller_agent_id="some-agent",
            caller_role=non_pm_role,
        )


# ---------------------------------------------------------------------------
# Settings.pm_loop_tick_seconds — default + range validation
# ---------------------------------------------------------------------------


def test_settings_pm_loop_tick_seconds_default_is_60(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Default 60.0s per F8.2 Q2-B disposition (conservative-then-
    tune-down framing). Defensive sentinel: pins the default against
    future change without surfacing F8.2 Q2 re-disposition."""
    monkeypatch.delenv("MERIDIAN_ORCH_PM_LOOP_TICK_SECONDS", raising=False)
    settings = Settings()
    assert settings.pm_loop_tick_seconds == 60.0


def test_settings_pm_loop_tick_seconds_rejects_out_of_range(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Range guard ge=1.0 le=3600.0 per F8.2 Q2-B prose. Sub-second
    risks runaway loop; over-1-hour is effectively off (operator
    should disable via enable_pm_supervisor flag)."""
    monkeypatch.setenv("MERIDIAN_ORCH_PM_LOOP_TICK_SECONDS", "0.5")
    with pytest.raises(Exception, match=r"(?i)greater than|ge=1"):
        Settings()
    monkeypatch.setenv("MERIDIAN_ORCH_PM_LOOP_TICK_SECONDS", "7200.0")
    with pytest.raises(Exception, match=r"(?i)less than|le=3600"):
        Settings()


def test_settings_pm_loop_tick_seconds_accepts_boundary_values(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Boundary values (1.0 and 3600.0) accepted (inclusive range)."""
    monkeypatch.setenv("MERIDIAN_ORCH_PM_LOOP_TICK_SECONDS", "1.0")
    assert Settings().pm_loop_tick_seconds == 1.0
    monkeypatch.setenv("MERIDIAN_ORCH_PM_LOOP_TICK_SECONDS", "3600.0")
    assert Settings().pm_loop_tick_seconds == 3600.0


# ---------------------------------------------------------------------------
# Module-level invariants — Q4 Reading D + Q9 Reading B sentinel pins
# ---------------------------------------------------------------------------


def test_pm_supervisor_does_not_import_tools_budget_or_pm_focus() -> None:
    """F8.2 Q4 Reading D invariant pin: bridge/pm_supervisor.py must
    not import tools/budget.py or tools/pm_focus.py at commit 7.

    Architectural symmetry with EngineerSupervisor (zero tools/
    imports); single-responsibility separation (supervisor =
    lifecycle scope; loop iteration = subprocess scope via
    tools/pm_loop.py).

    Defensive sentinel: pins the architectural-separation invariant
    so future-Claude adding bridge → tools imports surfaces F8.2 Q4
    re-disposition explicitly. Not a substantive constraint on
    pm_supervisor.py's growth in general — just on importing these
    specific tools/ modules at this scope.
    """
    pm_supervisor_text = (
        Path(__file__).resolve().parents[3]
        / "src"
        / "meridian_orchestrator"
        / "bridge"
        / "pm_supervisor.py"
    ).read_text(encoding="utf-8")

    forbidden_imports = [
        "from meridian_orchestrator.tools.budget",
        "from meridian_orchestrator.tools.pm_focus",
        "import meridian_orchestrator.tools.budget",
        "import meridian_orchestrator.tools.pm_focus",
    ]
    for forbidden in forbidden_imports:
        assert forbidden not in pm_supervisor_text, (
            f"Code-inspection sentinel fired: bridge/pm_supervisor.py "
            f"contains {forbidden!r}.\n\n"
            "DISPOSITIONED INVARIANT: F8.6.A Reading D — pm_supervisor.py "
            "must NOT import tools/budget.py or tools/pm_focus.py at "
            "M5-continuous-pm-driver commit 7+. Architectural intent: "
            "supervisor scope = lifecycle (spawn / heartbeat / token / "
            "respawn / activity); loop iteration scope = subprocess via "
            "tools/pm_loop.py + pm.yaml prompt. Defenses: symmetric with "
            "EngineerSupervisor (zero tools/ imports); single-responsibility; "
            "loop-config flows via main.py lifespan closure (mirrors "
            "get_oauth_usage_snapshot precedent).\n\n"
            "DETERMINE WHICH FAILURE MODE APPLIES:\n"
            "  Mode 1 (legitimate refactor): you renamed / split / "
            "reorganized source-file paths without changing architectural "
            "intent. The pm_supervisor.py text contains the forbidden "
            "import string due to path/pattern drift, not real "
            "architectural change. FIX: update this sentinel's path / "
            "pattern. EXPECTED COST: mechanical adjustment. Track as "
            "Mode 1 fire in the diff checkpoint per the closeout § "
            "Discipline-pattern code-inspection sentinel observability "
            "convention.\n"
            "  Mode 2 (architectural drift): you actually added a real "
            "tools/budget.py or tools/pm_focus.py import to "
            "pm_supervisor.py. You're violating F8.6.A Reading D "
            "substantively. FIX: revert the architectural change, NOT "
            "the sentinel. If you have substantive reason to extend "
            "pm_supervisor.py with tools/ imports, re-litigate F8.6.A "
            "Q4 in a prep checkpoint first, then update this sentinel "
            "if the new disposition lands. Track as Mode 2 fire (a "
            "discipline win)."
        )


def test_pm_loop_module_does_not_define_read_current_helper() -> None:
    """F8.2 Q9 Reading B invariant pin: tools/pm_loop.py must not
    define a `read_most_recent_*` or `read_current_*` helper at
    commit 7.

    Hysteresis logic is PM-prompt's responsibility per Q9 Reading B;
    threshold-of-three watch for the read_current_X rowid-DESC
    helper pattern stays at 2 (read_current_pm_mode +
    read_current_focus). A future commit adding such a helper here
    must re-litigate Q9 disposition and update this sentinel."""
    pm_loop_text = (
        Path(__file__).resolve().parents[3]
        / "src"
        / "meridian_orchestrator"
        / "tools"
        / "pm_loop.py"
    ).read_text(encoding="utf-8")

    forbidden_helpers = [
        "def read_current_",
        "def read_most_recent_",
        "async def read_current_",
        "async def read_most_recent_",
    ]
    for forbidden in forbidden_helpers:
        assert forbidden not in pm_loop_text, (
            f"Code-inspection sentinel fired: tools/pm_loop.py contains "
            f"{forbidden!r}.\n\n"
            "DISPOSITIONED INVARIANT: F8.2 Q9 Reading B — tools/pm_loop.py "
            "must NOT define a `read_current_*` or `read_most_recent_*` "
            "helper at M5-continuous-pm-driver commit 7+. Architectural "
            "intent: hysteresis logic is PM-prompt's responsibility, not "
            "the loop infrastructure's. Threshold-of-three watch for "
            "the read_current_X rowid-DESC helper pattern stays at 2 "
            "(read_current_pm_mode + read_current_focus); a third "
            "instance would trip the watch and require extraction-"
            "evaluation per F4.5.1 reframe (trigger-to-evaluate, not "
            "trigger-to-extract).\n\n"
            "DETERMINE WHICH FAILURE MODE APPLIES:\n"
            "  Mode 1 (legitimate refactor): you renamed / split / "
            "reorganized source-file content without adding a real "
            "read-helper definition. The pm_loop.py text contains the "
            "forbidden pattern due to path/pattern drift (e.g., a "
            "function name that happens to start with 'read_current_' "
            "but isn't a most-recent-row helper). FIX: update this "
            "sentinel's path / pattern. EXPECTED COST: mechanical "
            "adjustment. Track as Mode 1 fire in the diff checkpoint "
            "per the closeout § Discipline-pattern code-inspection "
            "sentinel observability convention.\n"
            "  Mode 2 (architectural drift): you actually added a real "
            "read_current_X / read_most_recent_X helper to "
            "tools/pm_loop.py. You're violating F8.2 Q9 Reading B "
            "substantively + introducing a third instance of the "
            "read_current_X rowid-DESC helper pattern. FIX: revert the "
            "architectural change, NOT the sentinel. If hysteresis "
            "logic genuinely needs to move daemon-side, re-litigate "
            "F8.2 Q9 in a prep checkpoint first AND surface the "
            "threshold-of-three trigger explicitly per F4.5.1 reframe, "
            "then update this sentinel + F2.5 watch entry. Track as "
            "Mode 2 fire (a discipline win)."
        )
