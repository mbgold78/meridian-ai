"""Integration tests for the ``get_burn_rate`` handler.

Exercises :func:`meridian_orchestrator.tools.budget.get_burn_rate`
against a migrated SQLite DB. Covers:

- tokens_per_hour and cost_per_hour math.
- Open-window projection (current): projected > observed.
- Closed-window projection (last, last_24h): projected == observed
  per Q7 disposition.
- Global scope per Q8 — burn rate aggregates across all four roles
  regardless of caller role; no caller-scoped slice.
- Implementer / Tester rejected at the handler layer
  (PERMISSION_DENIED).
- ``window_resolution_fallback`` propagation.

Direct-handler tests; MCP wrapper coverage in
``test_get_burn_rate_mcp_wrapper.py``.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
import pytest_asyncio

from meridian_orchestrator.bridge.oauth_usage import CachedSnapshot
from meridian_orchestrator.errors import PermissionError as OrchestratorPermissionError
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.tools.budget import (
    GetBurnRateInput,
    get_burn_rate,
)

_PM_ID = "pm-uuid"
_ENGINEER_ID = "engineer-uuid"
_IMPLEMENTER_ID = "implementer-uuid"
_TESTER_ID = "tester-uuid"


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    await s.execute(
        "INSERT INTO companies (id, name) VALUES ('co1', 'Meridian AI')"
    )
    for agent_id, role in (
        (_PM_ID, "pm"),
        (_ENGINEER_ID, "engineer"),
        (_IMPLEMENTER_ID, "implementer"),
        (_TESTER_ID, "tester"),
    ):
        await s.execute(
            "INSERT INTO agents (id, company_id, role, config_path) "
            "VALUES (?, 'co1', ?, '/dev/null')",
            (agent_id, role),
        )
    yield s
    await s.close()


async def _insert_api_usage(
    s: SqliteStorage,
    *,
    usage_id: str,
    agent_id: str,
    agent_role: str,
    input_tokens: int,
    output_tokens: int,
    cost_usd: float,
    window_start: datetime,
) -> None:
    await s.execute(
        "INSERT INTO api_usage "
        "(id, agent_id, agent_role, model, input_tokens, output_tokens, "
        "cost_usd, window_start) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        (
            usage_id,
            agent_id,
            agent_role,
            "claude-sonnet-4-6",
            input_tokens,
            output_tokens,
            cost_usd,
            window_start.isoformat(timespec="microseconds"),
        ),
    )


def _snapshot_with_resets(
    resets_at: datetime, polled_at: datetime
) -> CachedSnapshot:
    return CachedSnapshot(
        payload={
            "five_hour": {
                "resets_at": resets_at.isoformat(timespec="microseconds"),
                "utilization": 50.0,
            }
        },
        polled_at=polled_at,
        null_counters=[],
    )


# ---------------------------------------------------------------------------
# Permission gating
# ---------------------------------------------------------------------------


async def test_get_burn_rate_implementer_denied(
    storage: SqliteStorage,
) -> None:
    """Implementer is denied at the handler layer per the spec
    permission table (yes/yes/yes/no/no for Mike/PM/Engineer/Impl/
    Tester). Burn rate is a pacing signal for higher-level routing
    decisions; impl/tester aren't authorized."""
    with pytest.raises(OrchestratorPermissionError):
        await get_burn_rate(
            storage=storage,
            get_snapshot=lambda: None,
            request=GetBurnRateInput(window="last_24h"),
            caller_role="implementer",
        )


async def test_get_burn_rate_tester_denied(storage: SqliteStorage) -> None:
    """Tester denied symmetric to implementer."""
    with pytest.raises(OrchestratorPermissionError):
        await get_burn_rate(
            storage=storage,
            get_snapshot=lambda: None,
            request=GetBurnRateInput(window="last_24h"),
            caller_role="tester",
        )


async def test_get_burn_rate_pm_allowed(storage: SqliteStorage) -> None:
    """PM can invoke get_burn_rate without raising."""
    result = await get_burn_rate(
        storage=storage,
        get_snapshot=lambda: None,
        request=GetBurnRateInput(window="last_24h"),
        caller_role="pm",
    )
    # Empty DB → zeros (rate computation handles zero observations).
    assert result.tokens_per_hour == 0.0
    assert result.projected_window_total == 0


async def test_get_burn_rate_engineer_allowed(
    storage: SqliteStorage,
) -> None:
    """Engineer can invoke get_burn_rate. Pacing decision (e.g.,
    compaction-at-80%) needs this signal."""
    result = await get_burn_rate(
        storage=storage,
        get_snapshot=lambda: None,
        request=GetBurnRateInput(window="last_24h"),
        caller_role="engineer",
    )
    assert result.tokens_per_hour == 0.0


# ---------------------------------------------------------------------------
# Math correctness
# ---------------------------------------------------------------------------


async def test_get_burn_rate_closed_window_projection_equals_observed(
    storage: SqliteStorage,
) -> None:
    """For closed windows (last_24h), projected_window_total ==
    observed sum. No future to project into per Q7 disposition.
    """
    now = datetime.now(tz=UTC)
    # Seed two rows totaling 1500 tokens, both within last_24h.
    for i, (input_t, output_t, cost) in enumerate(
        [(500, 250, 0.0225), (300, 450, 0.0225)]
    ):
        await _insert_api_usage(
            storage,
            usage_id=f"u-{i}",
            agent_id=_PM_ID,
            agent_role="pm",
            input_tokens=input_t,
            output_tokens=output_t,
            cost_usd=cost,
            window_start=now - timedelta(hours=12),
        )

    result = await get_burn_rate(
        storage=storage,
        get_snapshot=lambda: None,
        request=GetBurnRateInput(window="last_24h"),
        caller_role="pm",
    )

    # observed = 800 + 700 = 1500 tokens, cost = 0.045.
    # elapsed = total = 24h. tokens_per_hour = 1500 / 24 = 62.5.
    # projected = rate * 24 = 62.5 * 24 = 1500 (= observed).
    assert abs(result.tokens_per_hour - 62.5) < 1e-9
    assert result.projected_window_total == 1500
    assert abs(result.cost_per_hour - 0.045 / 24) < 1e-9
    assert abs(result.projected_window_cost - 0.045) < 1e-9


async def test_get_burn_rate_open_current_projects_forward(
    storage: SqliteStorage,
) -> None:
    """For an open ``current`` window (subscription_5h), projected
    extrapolates the rate forward to window_end. Validates the
    open-window projection math."""
    now = datetime.now(tz=UTC)
    # Set resets_at so we're 1h into the 5h cycle. window_end = now + 4h;
    # window_start = now - 1h. elapsed = 1h; total = 5h.
    resets_at = now + timedelta(hours=4)
    snapshot = _snapshot_with_resets(resets_at, polled_at=now)

    # Seed a row at 30min into the cycle: 100 tokens.
    await _insert_api_usage(
        storage,
        usage_id="u-1",
        agent_id=_PM_ID,
        agent_role="pm",
        input_tokens=60,
        output_tokens=40,
        cost_usd=0.001,
        window_start=now - timedelta(minutes=30),
    )

    result = await get_burn_rate(
        storage=storage,
        get_snapshot=lambda: snapshot,
        request=GetBurnRateInput(window="current"),
        caller_role="pm",
    )

    # observed = 100 tokens; elapsed = 1h. rate = 100 / 1 = 100 tokens/h.
    # projected = 100 * 5 = 500 tokens.
    assert abs(result.tokens_per_hour - 100.0) < 1.0  # tolerate 1tok/hr
    assert result.projected_window_total >= 480  # ~500 with slop
    # projected > observed (open-window forward projection).
    assert result.projected_window_total > 100


async def test_get_burn_rate_global_scope_includes_all_roles(
    storage: SqliteStorage,
) -> None:
    """Burn rate aggregates across ALL four roles regardless of
    caller_role per Q8 disposition. Engineer caller sees PM's tokens
    in the rate. Verifies the global-scope contract."""
    now = datetime.now(tz=UTC)
    # PM contributes 1000, Engineer contributes 500.
    await _insert_api_usage(
        storage,
        usage_id="u-pm",
        agent_id=_PM_ID,
        agent_role="pm",
        input_tokens=600,
        output_tokens=400,
        cost_usd=0.5,
        window_start=now - timedelta(hours=12),
    )
    await _insert_api_usage(
        storage,
        usage_id="u-eng",
        agent_id=_ENGINEER_ID,
        agent_role="engineer",
        input_tokens=300,
        output_tokens=200,
        cost_usd=0.05,
        window_start=now - timedelta(hours=12),
    )

    # Engineer queries — should see PM's tokens too (global scope).
    result_eng = await get_burn_rate(
        storage=storage,
        get_snapshot=lambda: None,
        request=GetBurnRateInput(window="last_24h"),
        caller_role="engineer",
    )
    # Total = 1500 / 24h = 62.5 tokens/h.
    assert abs(result_eng.tokens_per_hour - 62.5) < 1e-9
    # PM symmetric should match (same global scope).
    result_pm = await get_burn_rate(
        storage=storage,
        get_snapshot=lambda: None,
        request=GetBurnRateInput(window="last_24h"),
        caller_role="pm",
    )
    assert result_eng.tokens_per_hour == result_pm.tokens_per_hour, (
        "Engineer and PM see different burn rates — Q8's global-scope "
        "contract is broken. Both should observe the whole subscription."
    )


# ---------------------------------------------------------------------------
# Window resolution propagation
# ---------------------------------------------------------------------------


async def test_get_burn_rate_window_resolution_fallback_propagates(
    storage: SqliteStorage,
) -> None:
    """When current is requested but snapshot is None, fallback fires
    to calendar_24h and the field surfaces in the response."""
    result = await get_burn_rate(
        storage=storage,
        get_snapshot=lambda: None,
        request=GetBurnRateInput(window="current"),
        caller_role="pm",
    )
    assert result.window_resolution_fallback == "calendar_24h"


async def test_get_burn_rate_last_24h_never_falls_back(
    storage: SqliteStorage,
) -> None:
    """last_24h is calendar; never triggers fallback regardless of
    snapshot state."""
    result = await get_burn_rate(
        storage=storage,
        get_snapshot=lambda: None,
        request=GetBurnRateInput(window="last_24h"),
        caller_role="pm",
    )
    assert result.window_resolution_fallback == "none"


async def test_get_burn_rate_empty_window_returns_zero_rate(
    storage: SqliteStorage,
) -> None:
    """No api_usage rows in the window → rate = 0, projected = 0.
    Defensive: rate computation must not divide by zero or raise on
    empty windows."""
    result = await get_burn_rate(
        storage=storage,
        get_snapshot=lambda: None,
        request=GetBurnRateInput(window="last_24h"),
        caller_role="pm",
    )
    assert result.tokens_per_hour == 0.0
    assert result.projected_window_total == 0
    assert result.cost_per_hour == 0.0
    assert result.projected_window_cost == 0.0
