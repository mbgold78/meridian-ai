"""Integration tests for the ``get_token_usage`` handler.

Exercises :func:`meridian_orchestrator.tools.budget.get_token_usage`
against a migrated SQLite DB with seeded api_usage rows. Covers:

- Window resolution combined with aggregation (current / last / today / last_24h).
- Hierarchy subtree filter (PM / Engineer / Implementer / Tester).
- Cross-subtree agent_id rejection (PERMISSION_DENIED).
- by_model and by_agent breakdown shapes per Q5 disposition.
- NULL cost_usd coalesced to zero per Q6 disposition.
- ``window_resolution_fallback='calendar_24h'`` when snapshot is None
  or stale; ``'none'`` for snapshot-independent windows.

Direct-handler tests; MCP wrapper coverage in
``test_get_token_usage_mcp_wrapper.py``.
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
    GetTokenUsageInput,
    get_token_usage,
)

# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


_PM_ID = "pm-uuid"
_ENGINEER_ID = "engineer-uuid"
_IMPLEMENTER_ID = "implementer-uuid"
_TESTER_ID = "tester-uuid"


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    """Migrated DB seeded with the four canonical agents (one per role).
    api_usage rows are inserted by individual tests as needed.
    """
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
    model: str,
    input_tokens: int,
    output_tokens: int,
    cost_usd: float | None,
    window_start: datetime,
) -> None:
    """Seed an api_usage row with explicit timestamp + agent_role.
    Mirrors record_api_call's INSERT shape (commit 1's column set).
    """
    await s.execute(
        "INSERT INTO api_usage "
        "(id, agent_id, agent_role, model, input_tokens, output_tokens, "
        "cost_usd, window_start) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        (
            usage_id,
            agent_id,
            agent_role,
            model,
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
# Aggregation correctness
# ---------------------------------------------------------------------------


async def test_get_token_usage_aggregates_within_window(
    storage: SqliteStorage,
) -> None:
    """Sum of input/output/cost across all rows in the window
    matches top-level totals."""
    now = datetime.now(tz=UTC)
    # Seed three rows within the last 24h.
    for i, (input_t, output_t, cost) in enumerate(
        [(100, 50, 0.045), (200, 100, 0.090), (300, 150, 0.135)]
    ):
        await _insert_api_usage(
            storage,
            usage_id=f"u-{i}",
            agent_id=_ENGINEER_ID,
            agent_role="engineer",
            model="claude-sonnet-4-6",
            input_tokens=input_t,
            output_tokens=output_t,
            cost_usd=cost,
            window_start=now - timedelta(hours=2),
        )

    result = await get_token_usage(
        storage=storage,
        get_snapshot=lambda: None,  # cold-start → calendar_24h
        request=GetTokenUsageInput(window="current"),
        caller_agent_id=_PM_ID,
        caller_role="pm",
    )

    assert result.input_tokens == 600
    assert result.output_tokens == 300
    assert abs(result.cost_usd - 0.270) < 1e-9
    assert result.window_resolution_fallback == "calendar_24h"


async def test_get_token_usage_filters_by_subtree_role_engineer_excludes_pm(
    storage: SqliteStorage,
) -> None:
    """Engineer's subtree filter excludes pm-role rows even when the
    pm row has matching window/timestamp."""
    now = datetime.now(tz=UTC)
    # PM row + Engineer row, both within window.
    await _insert_api_usage(
        storage,
        usage_id="u-pm",
        agent_id=_PM_ID,
        agent_role="pm",
        model="claude-opus-4-7",
        input_tokens=1000,
        output_tokens=500,
        cost_usd=0.5,
        window_start=now - timedelta(hours=1),
    )
    await _insert_api_usage(
        storage,
        usage_id="u-eng",
        agent_id=_ENGINEER_ID,
        agent_role="engineer",
        model="claude-sonnet-4-6",
        input_tokens=200,
        output_tokens=100,
        cost_usd=0.09,
        window_start=now - timedelta(hours=1),
    )

    # Engineer queries: should see only engineer + impl + tester
    # (no pm row).
    result = await get_token_usage(
        storage=storage,
        get_snapshot=lambda: None,
        request=GetTokenUsageInput(window="last_24h"),
        caller_agent_id=_ENGINEER_ID,
        caller_role="engineer",
    )
    assert result.input_tokens == 200
    assert result.output_tokens == 100
    assert "pm" not in {role for role in []} | set(result.by_agent or {})

    # PM queries: should see both rows (subtree includes all four).
    result_pm = await get_token_usage(
        storage=storage,
        get_snapshot=lambda: None,
        request=GetTokenUsageInput(window="last_24h"),
        caller_agent_id=_PM_ID,
        caller_role="pm",
    )
    assert result_pm.input_tokens == 1200
    assert result_pm.output_tokens == 600


async def test_get_token_usage_implementer_sees_only_self(
    storage: SqliteStorage,
) -> None:
    """Implementer with agent_id=None implicitly filters to its own
    agent_id — sibling tester's rows are excluded."""
    now = datetime.now(tz=UTC)
    await _insert_api_usage(
        storage,
        usage_id="u-impl",
        agent_id=_IMPLEMENTER_ID,
        agent_role="implementer",
        model="claude-sonnet-4-6",
        input_tokens=100,
        output_tokens=50,
        cost_usd=0.045,
        window_start=now - timedelta(hours=1),
    )
    await _insert_api_usage(
        storage,
        usage_id="u-tester",
        agent_id=_TESTER_ID,
        agent_role="tester",
        model="claude-sonnet-4-6",
        input_tokens=999,
        output_tokens=999,
        cost_usd=999.0,
        window_start=now - timedelta(hours=1),
    )

    result = await get_token_usage(
        storage=storage,
        get_snapshot=lambda: None,
        request=GetTokenUsageInput(window="last_24h"),
        caller_agent_id=_IMPLEMENTER_ID,
        caller_role="implementer",
    )
    assert result.input_tokens == 100
    assert result.output_tokens == 50


async def test_get_token_usage_cross_subtree_agent_id_raises_permission_denied(
    storage: SqliteStorage,
) -> None:
    """Engineer requests pm-uuid → PERMISSION_DENIED. No permission-
    graph leakage in the message (does not name what subtree pm
    belongs to).
    """
    with pytest.raises(OrchestratorPermissionError, match="out of subtree"):
        await get_token_usage(
            storage=storage,
            get_snapshot=lambda: None,
            request=GetTokenUsageInput(
                agent_id=_PM_ID, window="last_24h"
            ),
            caller_agent_id=_ENGINEER_ID,
            caller_role="engineer",
        )


async def test_get_token_usage_implementer_cross_self_agent_id_rejected(
    storage: SqliteStorage,
) -> None:
    """Implementer-1 requesting impl-2's data → PERMISSION_DENIED.
    Within-role sibling isolation (no two implementers in Phase 1,
    but the contract guards the future case).
    """
    with pytest.raises(OrchestratorPermissionError, match="out of subtree"):
        await get_token_usage(
            storage=storage,
            get_snapshot=lambda: None,
            request=GetTokenUsageInput(
                agent_id=_TESTER_ID, window="last_24h"
            ),
            caller_agent_id=_IMPLEMENTER_ID,
            caller_role="implementer",
        )


# ---------------------------------------------------------------------------
# by_model / by_agent breakdowns
# ---------------------------------------------------------------------------


async def test_get_token_usage_by_model_breakdown(
    storage: SqliteStorage,
) -> None:
    """by_model entries match per-model SUM(input/output/cost). Sonnet
    + Opus rows produce two entries with separate aggregates."""
    now = datetime.now(tz=UTC)
    await _insert_api_usage(
        storage,
        usage_id="u-sonnet",
        agent_id=_ENGINEER_ID,
        agent_role="engineer",
        model="claude-sonnet-4-6",
        input_tokens=1000,
        output_tokens=500,
        cost_usd=0.045,
        window_start=now - timedelta(hours=1),
    )
    await _insert_api_usage(
        storage,
        usage_id="u-opus",
        agent_id=_PM_ID,
        agent_role="pm",
        model="claude-opus-4-7",
        input_tokens=2000,
        output_tokens=1000,
        cost_usd=0.105,
        window_start=now - timedelta(hours=1),
    )

    result = await get_token_usage(
        storage=storage,
        get_snapshot=lambda: None,
        request=GetTokenUsageInput(window="last_24h"),
        caller_agent_id=_PM_ID,
        caller_role="pm",
    )

    assert set(result.by_model.keys()) == {
        "claude-sonnet-4-6", "claude-opus-4-7"
    }
    sonnet = result.by_model["claude-sonnet-4-6"]
    assert sonnet.input_tokens == 1000
    assert sonnet.output_tokens == 500
    assert abs(sonnet.cost_usd - 0.045) < 1e-9
    opus = result.by_model["claude-opus-4-7"]
    assert opus.input_tokens == 2000
    assert opus.output_tokens == 1000


async def test_get_token_usage_by_agent_populated_when_agent_id_none(
    storage: SqliteStorage,
) -> None:
    """by_agent populates with one entry per agent when agent_id=None.
    PM rollup over four agents shows four entries (when each has
    rows)."""
    now = datetime.now(tz=UTC)
    for agent_id, role, tokens in (
        (_PM_ID, "pm", 100),
        (_ENGINEER_ID, "engineer", 200),
        (_IMPLEMENTER_ID, "implementer", 300),
        (_TESTER_ID, "tester", 400),
    ):
        await _insert_api_usage(
            storage,
            usage_id=f"u-{role}",
            agent_id=agent_id,
            agent_role=role,
            model="claude-sonnet-4-6",
            input_tokens=tokens,
            output_tokens=tokens // 2,
            cost_usd=0.0,
            window_start=now - timedelta(hours=1),
        )

    result = await get_token_usage(
        storage=storage,
        get_snapshot=lambda: None,
        request=GetTokenUsageInput(window="last_24h"),
        caller_agent_id=_PM_ID,
        caller_role="pm",
    )

    assert result.by_agent is not None
    assert set(result.by_agent.keys()) == {
        _PM_ID, _ENGINEER_ID, _IMPLEMENTER_ID, _TESTER_ID
    }
    assert result.by_agent[_PM_ID].input_tokens == 100
    assert result.by_agent[_TESTER_ID].input_tokens == 400


async def test_get_token_usage_by_agent_is_none_when_agent_id_set(
    storage: SqliteStorage,
) -> None:
    """When agent_id is explicit, by_agent is None — the response is
    single-agent and doesn't need a per-agent breakdown."""
    now = datetime.now(tz=UTC)
    await _insert_api_usage(
        storage,
        usage_id="u-1",
        agent_id=_ENGINEER_ID,
        agent_role="engineer",
        model="claude-sonnet-4-6",
        input_tokens=100,
        output_tokens=50,
        cost_usd=0.045,
        window_start=now - timedelta(hours=1),
    )

    result = await get_token_usage(
        storage=storage,
        get_snapshot=lambda: None,
        request=GetTokenUsageInput(
            agent_id=_ENGINEER_ID, window="last_24h"
        ),
        caller_agent_id=_PM_ID,
        caller_role="pm",
    )

    assert result.by_agent is None
    assert result.input_tokens == 100


# ---------------------------------------------------------------------------
# Cost-aggregation NULL semantics
# ---------------------------------------------------------------------------


async def test_get_token_usage_null_cost_coalesces_to_zero(
    storage: SqliteStorage,
) -> None:
    """Rows with NULL cost_usd contribute zero to the cost aggregate
    but full token totals. Encodes the Q6 disposition (COALESCE-to-
    zero) durably; future commit that propagates NULL upward fails
    this test informatively.
    """
    now = datetime.now(tz=UTC)
    # Row 1: known model + cost.
    await _insert_api_usage(
        storage,
        usage_id="u-priced",
        agent_id=_ENGINEER_ID,
        agent_role="engineer",
        model="claude-sonnet-4-6",
        input_tokens=100,
        output_tokens=50,
        cost_usd=0.045,
        window_start=now - timedelta(hours=1),
    )
    # Row 2: unknown model, NULL cost.
    await _insert_api_usage(
        storage,
        usage_id="u-null-cost",
        agent_id=_ENGINEER_ID,
        agent_role="engineer",
        model="unknown-future-model",
        input_tokens=200,
        output_tokens=100,
        cost_usd=None,
        window_start=now - timedelta(hours=1),
    )

    result = await get_token_usage(
        storage=storage,
        get_snapshot=lambda: None,
        request=GetTokenUsageInput(window="last_24h"),
        caller_agent_id=_PM_ID,
        caller_role="pm",
    )

    # Tokens summed across both rows (300 input, 150 output).
    assert result.input_tokens == 300
    assert result.output_tokens == 150
    # Cost is the priced row only (0.045); unknown-model row coalesced
    # to 0.0. The aggregate float is observed-priced-rows-only — see
    # _aggregate_usage_totals docstring for the operator-investigation
    # pointer (query api_usage WHERE cost_usd IS NULL).
    assert abs(result.cost_usd - 0.045) < 1e-9


# ---------------------------------------------------------------------------
# Window resolution + handler integration
# ---------------------------------------------------------------------------


async def test_get_token_usage_window_resolution_fallback_when_snapshot_none(
    storage: SqliteStorage,
) -> None:
    """Cold-start (snapshot=None) for window='current' triggers
    calendar_24h fallback. Output's window_resolution_fallback signals
    the path."""
    result = await get_token_usage(
        storage=storage,
        get_snapshot=lambda: None,
        request=GetTokenUsageInput(window="current"),
        caller_agent_id=_PM_ID,
        caller_role="pm",
    )
    assert result.window_resolution_fallback == "calendar_24h"


async def test_get_token_usage_window_resolution_none_for_calendar_windows(
    storage: SqliteStorage,
) -> None:
    """Calendar windows (today, last_24h) never trigger fallback —
    they don't depend on the snapshot."""
    for window_name in ("today", "last_24h"):
        result = await get_token_usage(
            storage=storage,
            get_snapshot=lambda: None,  # snapshot unavailable
            request=GetTokenUsageInput(window=window_name),  # type: ignore[arg-type]
            caller_agent_id=_PM_ID,
            caller_role="pm",
        )
        assert result.window_resolution_fallback == "none", (
            f"window={window_name!r} produced fallback "
            f"{result.window_resolution_fallback!r}; calendar windows "
            "are snapshot-independent and should never fall back."
        )


async def test_get_token_usage_with_fresh_snapshot_uses_subscription_window(
    storage: SqliteStorage,
) -> None:
    """When snapshot is fresh, window='current' resolves to the
    subscription_5h cycle bounds (not calendar_24h). fallback='none'.
    Verifies the snapshot-present path end-to-end through the handler.
    """
    now = datetime.now(tz=UTC)
    resets_at = now + timedelta(hours=2)
    snapshot = _snapshot_with_resets(resets_at, polled_at=now)

    result = await get_token_usage(
        storage=storage,
        get_snapshot=lambda: snapshot,
        request=GetTokenUsageInput(window="current"),
        caller_agent_id=_PM_ID,
        caller_role="pm",
    )

    assert result.window_resolution_fallback == "none"
    # Window end is the next resets_at; window start is 5h earlier.
    expected_end = resets_at.isoformat(timespec="microseconds")
    expected_start = (resets_at - timedelta(hours=5)).isoformat(
        timespec="microseconds"
    )
    assert result.window_end == expected_end
    assert result.window_start == expected_start
