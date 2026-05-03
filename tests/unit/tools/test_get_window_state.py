"""Direct-handler tests for ``tools.budget.get_window_state``.

Exercises the handler against a migrated SQLite DB with a fake
snapshot provider. End-to-end MCP wrapper coverage lives in
``tests/integration/test_get_window_state_mcp_wrapper.py``.

Coverage matrix per the M5-pm-mode-and-window-state commit 6 prep
dispositions:

- pace_recommendation pure-function table (Q1 + Q2): every value
  (normal/upgrade/conserve/rate_limited) plus the gap-filled cases
  the spec amendment makes explicit. Boundary case
  ``utilization == 100.0`` exactly (Q2 sub-question) gets its own
  contract test.
- Cold-start (Q3): snapshot=None → unknown placeholder with
  ``snapshot_age_seconds=None``. Three-state contract durably
  enforced.
- Stale snapshot: age > 120s → forced unknown.
- Bucket-null in snapshot (Q4): unknown placeholder + bucket appears
  in null_counters. Routing-layer fallback (B-1) is NOT performed at
  this layer.
- api_key_overflow (Q5): unknown placeholder regardless of snapshot
  freshness. Deferred to M5-flag-flips.
- agent_id silent-ignore (Q6): non-None agent_id in input doesn't
  change the output.
- BUCKET_FIELD_MAP bidirectional consistency sentinel (additional
  architectural concern from prep): every entry round-trips through
  ``FIELD_TO_BUCKET`` ↔ ``BUCKET_FIELD_MAP``.
- Permission: PM and Engineer allowed; Implementer/Tester rejected.
- current_pm_mode + most_recent_pm_mode_log_timestamp: populated
  from pm_mode_log; null when empty.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
import pytest_asyncio

from meridian_orchestrator.bridge.oauth_usage import (
    FIELD_TO_BUCKET,
    CachedSnapshot,
)
from meridian_orchestrator.errors import (
    PermissionError as OrchestratorPermissionError,
)
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.tools.budget import (
    BUCKET_FIELD_MAP,
    GetWindowStateInput,
    _compute_pace_recommendation,
    get_window_state,
)


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


def _make_snapshot(
    *,
    payload: dict[str, object] | None = None,
    polled_at: datetime | None = None,
    null_counters: list[str] | None = None,
) -> CachedSnapshot:
    """Build a CachedSnapshot for tests. Defaults to a payload with
    subscription_5h at 17% utilization, resets in 4 hours."""
    if polled_at is None:
        polled_at = datetime.now(tz=UTC)
    if payload is None:
        resets_at = (polled_at + timedelta(hours=4)).isoformat(
            timespec="microseconds"
        )
        payload = {
            "five_hour": {"utilization": 17.0, "resets_at": resets_at},
            "seven_day": {
                "utilization": 15.0,
                "resets_at": (
                    polled_at + timedelta(days=6)
                ).isoformat(timespec="microseconds"),
            },
            "seven_day_opus": {
                "utilization": 22.0,
                "resets_at": (
                    polled_at + timedelta(days=5)
                ).isoformat(timespec="microseconds"),
            },
            "seven_day_sonnet": {
                "utilization": 0.0,
                "resets_at": (
                    polled_at + timedelta(days=6)
                ).isoformat(timespec="microseconds"),
            },
            "extra_usage": {
                "is_enabled": False,
                "utilization": None,
            },
        }
    return CachedSnapshot(
        payload=payload,
        polled_at=polled_at,
        null_counters=null_counters or [],
    )


# ---------------------------------------------------------------------------
# pace_recommendation pure function (Q1 + Q2)
# ---------------------------------------------------------------------------


def test_pace_recommendation_normal_high_remaining_long_time() -> None:
    """> 50% remaining, > 2h to reset → normal per the spec table."""
    assert _compute_pace_recommendation(17.0, 240) == "normal"


def test_pace_recommendation_upgrade_high_remaining_short_time() -> None:
    """> 50% remaining, < 1h to reset → upgrade per the spec table."""
    assert _compute_pace_recommendation(17.0, 30) == "upgrade"


def test_pace_recommendation_normal_low_remaining_long_time() -> None:
    """< 50% remaining, > 1h to reset → normal (defer polish) per
    the spec table."""
    assert _compute_pace_recommendation(60.0, 90) == "normal"


def test_pace_recommendation_conserve_critical_remaining() -> None:
    """< 20% remaining → conserve regardless of time-to-reset."""
    assert _compute_pace_recommendation(85.0, 240) == "conserve"
    assert _compute_pace_recommendation(85.0, 30) == "conserve"


def test_pace_recommendation_rate_limited_at_100_inclusive() -> None:
    """Q2 sub-question contract: utilization == 100.0 exactly fires
    rate_limited (inclusive boundary). Encodes the precedence rule
    so a future commit accidentally tightening to ``> 100`` would
    silently let a fully-saturated counter fall through to a lower-
    priority pace recommendation (likely ``conserve``).
    """
    assert _compute_pace_recommendation(100.0, 30) == "rate_limited"
    assert _compute_pace_recommendation(100.0, 240) == "rate_limited"
    assert _compute_pace_recommendation(150.0, 60) == "rate_limited"


def test_pace_recommendation_gap_fill_50_to_50_with_2h_to_1h() -> None:
    """Q1 gap-fill contract: > 50% remaining, 1h ≤ minutes_to_reset
    ≤ 2h → normal. Spec amendment in 02-architecture.md makes this
    explicit; the implementation's precedence rule routes here."""
    assert _compute_pace_recommendation(40.0, 90) == "normal"
    assert _compute_pace_recommendation(40.0, 60) == "normal"  # boundary


def test_pace_recommendation_gap_fill_20_to_50_short_time() -> None:
    """Q1 gap-fill contract: 20% ≤ remaining ≤ 50%, < 1h to reset →
    normal (NOT upgrade because remaining isn't > 50%; NOT conserve
    because remaining isn't < 20%)."""
    assert _compute_pace_recommendation(60.0, 30) == "normal"  # 40% rem
    assert _compute_pace_recommendation(75.0, 45) == "normal"  # 25% rem


def test_pace_recommendation_boundary_remaining_exactly_50() -> None:
    """remaining == 50% exactly: NOT upgrade (rule requires > 50)."""
    assert _compute_pace_recommendation(50.0, 30) == "normal"


def test_pace_recommendation_boundary_remaining_exactly_20() -> None:
    """remaining == 20% exactly: NOT conserve (rule requires < 20)."""
    assert _compute_pace_recommendation(80.0, 240) == "normal"


# ---------------------------------------------------------------------------
# BUCKET_FIELD_MAP bidirectional consistency sentinel
# ---------------------------------------------------------------------------


def test_bucket_field_map_is_inverse_of_field_to_bucket() -> None:
    """BUCKET_FIELD_MAP must be the literal inverse of FIELD_TO_BUCKET.
    Single-source-of-truth pattern per the architectural concern at
    commit 6 prep — derive, don't duplicate.

    Failure mode this test prevents: a future commit defining
    BUCKET_FIELD_MAP independently as a hand-maintained dict (the
    natural anti-pattern when adding a new bucket: "I'll just add it
    to both"). Either drift mode (one map adds a bucket the other
    doesn't; one map renames a value the other doesn't) fails this
    sentinel.
    """
    # Forward: every field in FIELD_TO_BUCKET round-trips through the
    # inverse to itself.
    for field, bucket in FIELD_TO_BUCKET.items():
        assert bucket in BUCKET_FIELD_MAP, (
            f"bucket {bucket!r} (mapped from field {field!r}) is not "
            "a key in BUCKET_FIELD_MAP. The inverse derivation in "
            "tools/budget.py was modified to drop entries."
        )
        assert BUCKET_FIELD_MAP[bucket] == field, (
            f"BUCKET_FIELD_MAP[{bucket!r}] is "
            f"{BUCKET_FIELD_MAP[bucket]!r}, expected {field!r}. "
            "The inverse derivation is no longer a literal inverse."
        )

    # Reverse: every bucket in BUCKET_FIELD_MAP round-trips through
    # FIELD_TO_BUCKET to itself.
    for bucket, field in BUCKET_FIELD_MAP.items():
        assert field in FIELD_TO_BUCKET, (
            f"field {field!r} (mapped from bucket {bucket!r}) is not "
            "a key in FIELD_TO_BUCKET. BUCKET_FIELD_MAP has an entry "
            "without a corresponding source — the maps drifted."
        )
        assert FIELD_TO_BUCKET[field] == bucket, (
            f"FIELD_TO_BUCKET[{field!r}] is "
            f"{FIELD_TO_BUCKET[field]!r}, expected {bucket!r}."
        )

    # Cardinality: same number of entries (no many-to-one mappings).
    assert len(FIELD_TO_BUCKET) == len(BUCKET_FIELD_MAP), (
        f"FIELD_TO_BUCKET has {len(FIELD_TO_BUCKET)} entries; "
        f"BUCKET_FIELD_MAP has {len(BUCKET_FIELD_MAP)}. The maps are "
        "no longer 1:1 — a many-to-one mapping was introduced and the "
        "inverse derivation can't represent it. Reconsider the "
        "architectural choice (was inverse derivation appropriate?)."
    )


# ---------------------------------------------------------------------------
# Cold-start (Q3): snapshot=None
# ---------------------------------------------------------------------------


async def test_cold_start_returns_unknown_placeholder_with_none_age(
    storage: SqliteStorage,
) -> None:
    """When the snapshot provider returns None (poller hasn't published
    a successful poll yet), get_window_state returns the unknown
    placeholder with snapshot_age_seconds=None per Q3 disposition.

    Three-state contract for snapshot_age_seconds:
    - None → no snapshot exists (this test)
    - 0 → snapshot just polled
    - positive int → snapshot is N seconds old

    Failure mode this test prevents: a future commit substituting a
    sentinel int (e.g., -1 or 999999) for the cold-start signal would
    re-introduce the magic-number-sentinel anti-pattern that Q3
    deliberately rejected.
    """
    out = await get_window_state(
        storage=storage,
        get_snapshot=lambda: None,
        request=GetWindowStateInput(bucket="subscription_5h"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    assert out.bucket == "subscription_5h"
    assert out.pace_recommendation == "unknown"
    assert out.snapshot_age_seconds is None, (
        f"snapshot_age_seconds is {out.snapshot_age_seconds!r}, "
        "expected None. The Q3 three-state contract requires None "
        "specifically for cold-start; sentinel ints would conflate "
        "cold-start with stale-but-present-snapshot."
    )
    assert out.utilization == 0.0
    assert out.minutes_to_reset == 0
    assert out.window_start == ""
    assert out.window_end == ""
    assert out.null_counters == []


# ---------------------------------------------------------------------------
# Stale snapshot: age > 120s
# ---------------------------------------------------------------------------


async def test_stale_snapshot_forces_unknown(
    storage: SqliteStorage,
) -> None:
    """A snapshot older than 120s forces pace_recommendation='unknown'
    regardless of the bucket's actual utilization. snapshot_age_seconds
    is still surfaced (informative) so dashboards can display
    'X seconds stale.'"""
    polled_at = datetime.now(tz=UTC) - timedelta(seconds=200)
    snapshot = _make_snapshot(polled_at=polled_at)
    out = await get_window_state(
        storage=storage,
        get_snapshot=lambda: snapshot,
        request=GetWindowStateInput(bucket="subscription_5h"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    assert out.pace_recommendation == "unknown"
    assert out.snapshot_age_seconds is not None
    assert out.snapshot_age_seconds >= 200


async def test_fresh_snapshot_does_not_force_unknown(
    storage: SqliteStorage,
) -> None:
    """Boundary check: a snapshot just at the 120s threshold (not
    over) should still produce a real pace_recommendation. Threshold
    is `> 120` (exclusive), not `>= 120`."""
    polled_at = datetime.now(tz=UTC) - timedelta(seconds=119)
    snapshot = _make_snapshot(polled_at=polled_at)
    out = await get_window_state(
        storage=storage,
        get_snapshot=lambda: snapshot,
        request=GetWindowStateInput(bucket="subscription_5h"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )
    assert out.pace_recommendation != "unknown"


# ---------------------------------------------------------------------------
# api_key_overflow (Q5): deferred to M5-flag-flips
# ---------------------------------------------------------------------------


async def test_cold_start_dominates_api_key_overflow_branch(
    storage: SqliteStorage,
) -> None:
    """Branch-precedence contract: cold-start wins over the
    api_key_overflow deferral branch. Both branches return the
    unknown placeholder, but they're distinguishable by
    ``snapshot_age_seconds``:

    - Cold-start → ``snapshot_age_seconds=None`` (no snapshot exists)
    - api_key_overflow with fresh snapshot → ``snapshot_age_seconds``
      is the actual age (poll succeeded; only the bucket's window
      semantics are deferred)

    A request that's BOTH (cold-start + api_key_overflow bucket)
    must return the cold-start signal (``None``), proving cold-start
    dispatches first.

    Failure mode this test prevents: a future commit reordering the
    branches (e.g., checking api_key_overflow before cold-start)
    would silently start returning ``snapshot_age_seconds=0`` (or
    similar) for cold-start + api_key_overflow callers, masking the
    cold-start state. Operators reading dashboards would see "fresh
    snapshot, but bucket deferred" when the truth is "no poll has
    landed yet; the bucket's deferral is moot."
    """
    out = await get_window_state(
        storage=storage,
        get_snapshot=lambda: None,  # cold-start
        request=GetWindowStateInput(bucket="api_key_overflow"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    assert out.pace_recommendation == "unknown"
    assert out.snapshot_age_seconds is None, (
        f"snapshot_age_seconds is {out.snapshot_age_seconds!r}; "
        "expected None. Branch precedence is broken — the "
        "api_key_overflow branch dispatched before cold-start, "
        "masking the cold-start signal. Cold-start must win over "
        "all other branches per the handler's documented precedence."
    )
    assert out.bucket == "api_key_overflow"


async def test_stale_snapshot_dominates_api_key_overflow_branch(
    storage: SqliteStorage,
) -> None:
    """Branch-precedence contract: stale wins over api_key_overflow.
    A stale snapshot whose requested bucket is api_key_overflow
    returns the stale-with-age unknown, not the api_key_overflow
    deferral. Distinguishable by ``snapshot_age_seconds`` value:
    stale returns the actual age (>120); api_key_overflow on a fresh
    snapshot would return the actual age too, but the stale path
    runs first regardless.

    The two branches produce equivalent surface output (unknown +
    age), so this test really only catches reordering that would
    change behavior under a different condition (e.g., a future
    api_key_overflow branch that returned something other than the
    unknown placeholder). Worth keeping as documentation of the
    invariant.
    """
    polled_at = datetime.now(tz=UTC) - timedelta(seconds=200)
    snapshot = _make_snapshot(polled_at=polled_at)
    out = await get_window_state(
        storage=storage,
        get_snapshot=lambda: snapshot,
        request=GetWindowStateInput(bucket="api_key_overflow"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )
    assert out.pace_recommendation == "unknown"
    assert out.snapshot_age_seconds is not None
    assert out.snapshot_age_seconds >= 200


async def test_api_key_overflow_returns_unknown_per_q5_disposition(
    storage: SqliteStorage,
) -> None:
    """Q5 disposition: api_key_overflow window-cycle semantics are
    deferred to M5-flag-flips. get_window_state(bucket='api_key_overflow')
    returns the unknown placeholder regardless of whether
    extra_usage.is_enabled and regardless of utilization values.

    Failure mode this test prevents: a future commit that
    accidentally adds api_key_overflow to _CYCLE_DURATION_SECONDS
    (e.g., assuming 30 days) without resolving the calendar-vs-rolling
    question would silently start producing window_start/window_end
    values that may not match the operator's actual billing cycle.
    """
    snapshot = _make_snapshot(
        payload={
            "five_hour": {"utilization": 17.0, "resets_at": "..."},
            "extra_usage": {
                "is_enabled": True,
                "utilization": 50.0,
                "monthly_limit": 100,
                "used_credits": 50,
            },
        },
    )
    out = await get_window_state(
        storage=storage,
        get_snapshot=lambda: snapshot,
        request=GetWindowStateInput(bucket="api_key_overflow"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )
    assert out.pace_recommendation == "unknown", (
        "api_key_overflow returned a real pace_recommendation; the "
        "Q5 disposition deferred this to M5-flag-flips. A future "
        "commit prematurely implementing api_key_overflow window "
        "semantics would fail this test until the BACKLOG-tracked "
        "calendar-vs-rolling question is resolved."
    )
    assert out.bucket == "api_key_overflow"


# ---------------------------------------------------------------------------
# Bucket-null in snapshot (Q4)
# ---------------------------------------------------------------------------


async def test_bucket_null_field_returns_unknown(
    storage: SqliteStorage,
) -> None:
    """Per Q4 disposition: when the requested bucket's field is null
    in the snapshot (e.g., seven_day_opus), return unknown placeholder.
    Routing-layer fallback (B-1 memory) lives at a higher layer with
    full information; this tool exposes the null state via
    null_counters and lets consumers decide.
    """
    polled_at = datetime.now(tz=UTC)
    payload: dict[str, object] = {
        "five_hour": {
            "utilization": 17.0,
            "resets_at": (polled_at + timedelta(hours=4)).isoformat(
                timespec="microseconds"
            ),
        },
        "seven_day_opus": None,  # null field
    }
    snapshot = _make_snapshot(
        payload=payload,
        polled_at=polled_at,
        null_counters=["subscription_7d_opus"],
    )

    out = await get_window_state(
        storage=storage,
        get_snapshot=lambda: snapshot,
        request=GetWindowStateInput(bucket="subscription_7d_opus"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    assert out.pace_recommendation == "unknown"
    assert "subscription_7d_opus" in out.null_counters
    assert out.bucket == "subscription_7d_opus"


async def test_bucket_present_but_utilization_null_returns_unknown(
    storage: SqliteStorage,
) -> None:
    """The other null shape per Experiment 4: field is present but
    utilization is null (e.g., extra_usage when is_enabled is False).
    The handler treats this the same as a null field — unknown
    placeholder."""
    polled_at = datetime.now(tz=UTC)
    payload: dict[str, object] = {
        "five_hour": {
            "utilization": 17.0,
            "resets_at": (polled_at + timedelta(hours=4)).isoformat(
                timespec="microseconds"
            ),
        },
        "seven_day_sonnet": {"utilization": None, "resets_at": None},
    }
    snapshot = _make_snapshot(
        payload=payload,
        polled_at=polled_at,
        null_counters=["subscription_7d_sonnet"],
    )
    out = await get_window_state(
        storage=storage,
        get_snapshot=lambda: snapshot,
        request=GetWindowStateInput(bucket="subscription_7d_sonnet"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )
    assert out.pace_recommendation == "unknown"
    assert "subscription_7d_sonnet" in out.null_counters


async def test_bucket_null_does_not_fall_back_to_overall_counter(
    storage: SqliteStorage,
) -> None:
    """Q4 contract test: when seven_day_opus is null, the handler
    does NOT silently fall back to seven_day's data. The B-1 fallback
    rule from project_subscription_first_architecture memory is a
    routing-layer concern; get_window_state stays at the data-exposure
    layer.

    Failure mode this test prevents: a future commit implementing
    the B-1 fallback at this layer would mask the null state from
    callers who explicitly want to know "is this counter present?"
    """
    polled_at = datetime.now(tz=UTC)
    payload: dict[str, object] = {
        "five_hour": {
            "utilization": 17.0,
            "resets_at": (polled_at + timedelta(hours=4)).isoformat(
                timespec="microseconds"
            ),
        },
        "seven_day": {
            "utilization": 15.0,
            "resets_at": (polled_at + timedelta(days=6)).isoformat(
                timespec="microseconds"
            ),
        },
        "seven_day_opus": None,
    }
    snapshot = _make_snapshot(
        payload=payload,
        polled_at=polled_at,
        null_counters=["subscription_7d_opus"],
    )
    out = await get_window_state(
        storage=storage,
        get_snapshot=lambda: snapshot,
        request=GetWindowStateInput(bucket="subscription_7d_opus"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )
    assert out.pace_recommendation == "unknown", (
        "seven_day_opus was null but the handler returned a real "
        "pace_recommendation — the routing-layer fallback (B-1) "
        "appears to have been implemented at this layer, masking "
        "the null state from callers."
    )
    assert out.utilization == 0.0  # not 15.0 (seven_day's value)


# ---------------------------------------------------------------------------
# Happy path
# ---------------------------------------------------------------------------


async def test_subscription_5h_happy_path(
    storage: SqliteStorage,
) -> None:
    """Standard case: subscription_5h at 17% utilization, resets in
    ~4 hours → normal pace, populated window/utilization/timestamps."""
    polled_at = datetime.now(tz=UTC)
    snapshot = _make_snapshot(polled_at=polled_at)
    out = await get_window_state(
        storage=storage,
        get_snapshot=lambda: snapshot,
        request=GetWindowStateInput(bucket="subscription_5h"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    assert out.bucket == "subscription_5h"
    assert out.pace_recommendation == "normal"
    assert out.utilization == 17.0
    assert out.minutes_to_reset > 0
    assert out.window_start != ""
    assert out.window_end != ""
    assert out.snapshot_age_seconds is not None
    assert out.snapshot_age_seconds >= 0


async def test_window_start_is_5h_before_window_end_for_subscription_5h(
    storage: SqliteStorage,
) -> None:
    """Cycle-duration computation contract: window_start = window_end
    - cycle_duration. For subscription_5h, the cycle is 5 hours
    (18000 seconds)."""
    polled_at = datetime.now(tz=UTC)
    resets_at = polled_at + timedelta(hours=4)
    payload: dict[str, object] = {
        "five_hour": {
            "utilization": 17.0,
            "resets_at": resets_at.isoformat(timespec="microseconds"),
        },
    }
    snapshot = _make_snapshot(payload=payload, polled_at=polled_at)
    out = await get_window_state(
        storage=storage,
        get_snapshot=lambda: snapshot,
        request=GetWindowStateInput(bucket="subscription_5h"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    window_start = datetime.fromisoformat(out.window_start)
    window_end = datetime.fromisoformat(out.window_end)
    delta = window_end - window_start
    assert delta == timedelta(hours=5), (
        f"window_start to window_end is {delta}, expected 5 hours "
        "for subscription_5h. _CYCLE_DURATION_SECONDS may have drifted."
    )


async def test_window_start_is_7d_before_window_end_for_subscription_7d(
    storage: SqliteStorage,
) -> None:
    """Same cycle-duration check for subscription_7d (7 days)."""
    polled_at = datetime.now(tz=UTC)
    resets_at = polled_at + timedelta(days=5)
    payload: dict[str, object] = {
        "seven_day": {
            "utilization": 15.0,
            "resets_at": resets_at.isoformat(timespec="microseconds"),
        },
    }
    snapshot = _make_snapshot(payload=payload, polled_at=polled_at)
    out = await get_window_state(
        storage=storage,
        get_snapshot=lambda: snapshot,
        request=GetWindowStateInput(bucket="subscription_7d"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )
    window_start = datetime.fromisoformat(out.window_start)
    window_end = datetime.fromisoformat(out.window_end)
    assert (window_end - window_start) == timedelta(days=7)


# ---------------------------------------------------------------------------
# agent_id silent-ignore (Q6 contract)
# ---------------------------------------------------------------------------


async def test_agent_id_silently_ignored_per_q6_disposition(
    storage: SqliteStorage,
) -> None:
    """Q6 contract: agent_id is a forward-compat hook in Phase 1.
    Passing different agent_id values must produce the same output
    (Phase 1 has a single OAuth subscription; per-agent scoping is
    not implemented).

    Failure mode this test prevents: a future commit implementing
    per-agent_id scoping at this layer without coordinating with the
    Phase 2 multi-user spec would silently change the response based
    on agent_id input, breaking the documented Phase 1 silent-ignore
    contract.
    """
    polled_at = datetime.now(tz=UTC)
    snapshot = _make_snapshot(polled_at=polled_at)

    out_none = await get_window_state(
        storage=storage,
        get_snapshot=lambda: snapshot,
        request=GetWindowStateInput(
            bucket="subscription_5h", agent_id=None
        ),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )
    out_specific = await get_window_state(
        storage=storage,
        get_snapshot=lambda: snapshot,
        request=GetWindowStateInput(
            bucket="subscription_5h", agent_id="some-other-agent"
        ),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )
    out_caller = await get_window_state(
        storage=storage,
        get_snapshot=lambda: snapshot,
        request=GetWindowStateInput(
            bucket="subscription_5h", agent_id="pm-agent"
        ),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    # All three responses are identical (the global subscription
    # state is the same; agent_id doesn't scope).
    assert out_none.utilization == out_specific.utilization == out_caller.utilization
    assert out_none.bucket == out_specific.bucket == out_caller.bucket
    assert out_none.window_end == out_specific.window_end == out_caller.window_end


# ---------------------------------------------------------------------------
# Permission
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("allowed_role", ["pm", "engineer"])
async def test_pm_and_engineer_can_invoke(
    storage: SqliteStorage, allowed_role: str
) -> None:
    """PM and Engineer are explicitly allowed per the spec."""
    snapshot = _make_snapshot()
    out = await get_window_state(
        storage=storage,
        get_snapshot=lambda: snapshot,
        request=GetWindowStateInput(bucket="subscription_5h"),
        caller_agent_id=f"{allowed_role}-agent",
        caller_role=allowed_role,
    )
    assert out.pace_recommendation in (
        "normal",
        "upgrade",
        "conserve",
        "rate_limited",
        "unknown",
    )


@pytest.mark.parametrize(
    "rejected_role", ["implementer", "tester"]
)
async def test_implementer_tester_rejected(
    storage: SqliteStorage, rejected_role: str
) -> None:
    """Implementer and Tester are rejected per the spec —
    they read their own usage via get_token_usage, not subscription-
    level window state."""
    snapshot = _make_snapshot()
    with pytest.raises(OrchestratorPermissionError, match=rejected_role):
        await get_window_state(
            storage=storage,
            get_snapshot=lambda: snapshot,
            request=GetWindowStateInput(bucket="subscription_5h"),
            caller_agent_id=f"{rejected_role}-agent",
            caller_role=rejected_role,
        )


# ---------------------------------------------------------------------------
# PM mode fields
# ---------------------------------------------------------------------------


async def test_current_pm_mode_populated_from_pm_mode_log(
    storage: SqliteStorage,
) -> None:
    """current_pm_mode = most recent pm_mode_log row's mode.
    most_recent_pm_mode_log_timestamp = that row's timestamp in
    ISO 8601 UTC format."""
    await storage.execute(
        "INSERT INTO pm_mode_log (id, mode, triggered_by) "
        "VALUES (?, ?, ?)",
        ("seed-1", "interactive", "mike_message"),
    )

    snapshot = _make_snapshot()
    out = await get_window_state(
        storage=storage,
        get_snapshot=lambda: snapshot,
        request=GetWindowStateInput(bucket="subscription_5h"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    assert out.current_pm_mode == "interactive"
    assert out.most_recent_pm_mode_log_timestamp is not None
    # ISO 8601 with timezone offset — verify by re-parsing.
    parsed = datetime.fromisoformat(out.most_recent_pm_mode_log_timestamp)
    assert parsed.tzinfo is not None, (
        "most_recent_pm_mode_log_timestamp lacks timezone offset; "
        "the conversion from SQLite datetime('now') format to ISO "
        "8601 UTC dropped the +00:00 marker."
    )
    # Stronger: assert the offset is UTC specifically (timedelta(0)),
    # not just "some timezone." A future regression that misinterprets
    # the SQLite naive timestamp as local time would silently produce
    # a non-UTC offset (or a wrong wall-clock value with +00:00),
    # invisible to the weaker tzinfo-not-None check.
    offset = parsed.utcoffset()
    assert offset is not None
    assert offset == timedelta(0), (
        f"most_recent_pm_mode_log_timestamp offset is {offset}; "
        "expected timedelta(0) (UTC). The naive-as-UTC convention "
        "in _read_most_recent_pm_mode_log_timestamp has drifted — "
        "either the helper is interpreting SQLite naive timestamps "
        "as local time, or it's emitting a non-UTC offset."
    )


async def test_pm_mode_log_timestamp_round_trips_naive_utc_correctly(
    storage: SqliteStorage,
) -> None:
    """Insert a known SQLite-naive timestamp (the format
    ``datetime('now')`` produces) and assert the helper converts it
    to the exact-equivalent ISO 8601 UTC string. Verifies the
    naive-as-UTC convention preserves the wall-clock value rather
    than shifting it by a local-timezone offset.

    Failure mode this test prevents: a regression where the helper
    misinterprets the naive timestamp as local time would produce
    output that differs from the input by the local-timezone offset
    — invisible on a UTC-running CI box but catastrophic on a
    developer machine in a non-UTC zone.
    """
    # SQLite datetime('now') format: 'YYYY-MM-DD HH:MM:SS'.
    # Choose a fixed value so the assertion is deterministic.
    naive_ts = "2026-05-02 19:30:45"
    expected_iso = "2026-05-02T19:30:45+00:00"

    await storage.execute(
        "INSERT INTO pm_mode_log (id, mode, triggered_by, timestamp) "
        "VALUES (?, ?, ?, ?)",
        ("known-ts", "continuous", "manual", naive_ts),
    )

    snapshot = _make_snapshot()
    out = await get_window_state(
        storage=storage,
        get_snapshot=lambda: snapshot,
        request=GetWindowStateInput(bucket="subscription_5h"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    actual_iso = out.most_recent_pm_mode_log_timestamp
    assert actual_iso is not None

    # Parse both and compare as datetime values to be robust against
    # microsecond-precision formatting differences ('19:30:45' vs
    # '19:30:45.000000'). The wall-clock value must match exactly.
    actual_dt = datetime.fromisoformat(actual_iso)
    expected_dt = datetime.fromisoformat(expected_iso)
    assert actual_dt == expected_dt, (
        f"timestamp round-trip mismatch: stored {naive_ts!r} "
        f"(expected interpretation: UTC {expected_iso!r}); helper "
        f"returned {actual_iso!r}. The naive-as-UTC convention is "
        "broken — likely the helper interpreted the naive timestamp "
        "as local time, shifting the wall-clock by the local "
        "timezone offset."
    )
    # Also verify the offset marker is explicit UTC.
    assert actual_dt.utcoffset() == timedelta(0)


async def test_pm_mode_log_timestamp_preserves_tz_aware_input(
    storage: SqliteStorage,
) -> None:
    """Defensive contract: if a future writer stores a tz-aware ISO
    string in pm_mode_log.timestamp (e.g., a migration to ISO format,
    or a manual SQL insert with a timezone offset), the helper must
    preserve the offset rather than clobbering it with UTC.

    Failure mode this test prevents: a future commit reverting to
    unconditional ``.replace(tzinfo=UTC)`` would silently corrupt
    tz-aware inputs by overwriting their offset (e.g., a stored
    ``+05:00`` timestamp would emerge as ``+00:00`` with the same
    wall-clock numbers — wrong by 5 hours of real time).
    """
    # Insert a tz-aware ISO timestamp directly. This is currently a
    # defensive case (the column's default produces naive); the
    # helper must still handle it correctly.
    tz_aware_ts = "2026-05-02T19:30:45+05:00"
    await storage.execute(
        "INSERT INTO pm_mode_log (id, mode, triggered_by, timestamp) "
        "VALUES (?, ?, ?, ?)",
        ("tz-aware", "continuous", "manual", tz_aware_ts),
    )

    snapshot = _make_snapshot()
    out = await get_window_state(
        storage=storage,
        get_snapshot=lambda: snapshot,
        request=GetWindowStateInput(bucket="subscription_5h"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    actual_iso = out.most_recent_pm_mode_log_timestamp
    assert actual_iso is not None
    actual_dt = datetime.fromisoformat(actual_iso)
    # The wall-clock and offset must both match the input. Compared
    # as a datetime so re-emitting at microsecond precision doesn't
    # break the equality.
    expected_dt = datetime.fromisoformat(tz_aware_ts)
    assert actual_dt == expected_dt, (
        f"tz-aware input {tz_aware_ts!r} did not round-trip; helper "
        f"returned {actual_iso!r} (parsed: {actual_dt!r}). The "
        "naive-as-UTC defensive pattern is broken — the helper is "
        "unconditionally replacing the timezone with UTC, "
        "corrupting tz-aware inputs."
    )


async def test_pm_mode_fields_null_when_pm_mode_log_empty(
    storage: SqliteStorage,
) -> None:
    """When pm_mode_log is empty, both PM mode fields return None.
    Q3 None-baseline contract for pm_mode_log applies to this read
    path too."""
    snapshot = _make_snapshot()
    out = await get_window_state(
        storage=storage,
        get_snapshot=lambda: snapshot,
        request=GetWindowStateInput(bucket="subscription_5h"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )
    assert out.current_pm_mode is None
    assert out.most_recent_pm_mode_log_timestamp is None


async def test_cold_start_still_populates_pm_mode_fields(
    storage: SqliteStorage,
) -> None:
    """Mike-presence detection is independent of the OAuth poller's
    snapshot. Even on cold-start (snapshot=None), pm_mode_log is
    queried and the fields are populated."""
    await storage.execute(
        "INSERT INTO pm_mode_log (id, mode, triggered_by) "
        "VALUES (?, ?, ?)",
        ("cold-start-seed", "interactive", "mike_message"),
    )

    out = await get_window_state(
        storage=storage,
        get_snapshot=lambda: None,
        request=GetWindowStateInput(bucket="subscription_5h"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    assert out.pace_recommendation == "unknown"
    assert out.snapshot_age_seconds is None
    # PM mode fields populated even though the OAuth snapshot is absent.
    assert out.current_pm_mode == "interactive"
    assert out.most_recent_pm_mode_log_timestamp is not None
