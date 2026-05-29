"""Unit tests for the pure routing function in
:mod:`meridian_orchestrator.tools.budget` —
:func:`_compute_routing` and :func:`_apply_priority_delta`.

Pure-function tests: no DB, no MCP, no async. The wrapper at commit 5
(forthcoming) does the snapshot lookup + caller_role resolution and
passes inputs through to ``_compute_routing``; this file locks the
routing decisions independently of that wiring.

Coverage:

- ``_apply_priority_delta`` bounds-clamping (top, bottom, no-clamp).
- ``_compute_routing`` per (role, priority, snapshot state): each
  of the six routing rules + fallback per Q3 disposition.
- Multi-rule simultaneous-firing contract test per Q8 sub-amendment.
- Factor naming convention sentinel (Q5 sub-amendment): pct-format
  factors match ``<dimension>_at_<value>pct_<context>``.
- ``snapshot_age_<n>s`` always-emitted factor per the factor-list
  addition.
"""

from __future__ import annotations

import re
from datetime import UTC, datetime, timedelta

from meridian_orchestrator.bridge.oauth_usage import CachedSnapshot
from meridian_orchestrator.tools.budget import (
    _PCT_FACTOR_FORMAT_PATTERN,
    _PRIORITY_TIER_DELTA,
    _ROLE_DEFAULT_TIER,
    _TIER_HIERARCHY,
    OPUS_DOWNGRADE_UTILIZATION_THRESHOLD,
    CheckBudgetForTaskOutput,
    _apply_priority_delta,
    _compute_routing,
)

# ---------------------------------------------------------------------------
# Test helpers
# ---------------------------------------------------------------------------


_NOW = datetime(2026, 5, 3, 14, 0, 0, tzinfo=UTC)


def _snapshot(
    *,
    five_hour_util: float | None = 30.0,
    seven_day_opus_util: float | None = 20.0,
    extra_usage_enabled: bool | None = False,
    polled_at: datetime | None = None,
    resets_offset_hours: float = 4.0,
) -> CachedSnapshot:
    """Build a CachedSnapshot for routing tests. All counters set by
    default; pass None to omit a counter (simulating a malformed payload
    or missing counter).
    """
    polled = polled_at if polled_at is not None else _NOW
    payload: dict[str, object] = {}
    if five_hour_util is not None:
        payload["five_hour"] = {
            "utilization": five_hour_util,
            "resets_at": (
                _NOW + timedelta(hours=resets_offset_hours)
            ).isoformat(timespec="microseconds"),
        }
    if seven_day_opus_util is not None:
        payload["seven_day_opus"] = {"utilization": seven_day_opus_util}
    if extra_usage_enabled is not None:
        payload["extra_usage"] = {"is_enabled": extra_usage_enabled}
    return CachedSnapshot(
        payload=payload, polled_at=polled, null_counters=[]
    )


# ---------------------------------------------------------------------------
# _apply_priority_delta — bounds clamping
# ---------------------------------------------------------------------------


def test_apply_priority_delta_no_clamp_middle_priority() -> None:
    """priority=3 (delta 0) + Sonnet default → Sonnet, no clamp."""
    new_idx, marker = _apply_priority_delta(default_tier_idx=1, priority=3)
    assert new_idx == 1
    assert marker == "none"


def test_apply_priority_delta_upgrade_no_clamp() -> None:
    """priority=1 (delta +1) + Sonnet default → Opus, no clamp."""
    new_idx, marker = _apply_priority_delta(default_tier_idx=1, priority=1)
    assert new_idx == 2
    assert marker == "none"


def test_apply_priority_delta_downgrade_no_clamp() -> None:
    """priority=5 (delta -1) + Sonnet default → Haiku, no clamp."""
    new_idx, marker = _apply_priority_delta(default_tier_idx=1, priority=5)
    assert new_idx == 0
    assert marker == "none"


def test_apply_priority_delta_clamps_at_top_for_pm_priority_1() -> None:
    """Q5 contract: priority=1 on Opus stays Opus (already at top).
    Sentinel against a future commit that drops the upper clamp.
    """
    new_idx, marker = _apply_priority_delta(default_tier_idx=2, priority=1)
    assert new_idx == 2, (
        f"priority=1 on Opus produced tier index {new_idx}; expected "
        "2 (Opus). Upper clamp regression: a delta of +1 took the "
        "tier above the hierarchy length without clamping. Q5 "
        "disposition: 'Priority=1 on Opus stays Opus (already at top).'"
    )
    assert marker == "top"


def test_apply_priority_delta_clamps_at_bottom_for_priority_5_on_haiku() -> None:
    """Symmetric: priority=5 on Haiku stays Haiku (already at bottom)."""
    new_idx, marker = _apply_priority_delta(default_tier_idx=0, priority=5)
    assert new_idx == 0, (
        f"priority=5 on Haiku produced tier index {new_idx}; expected "
        "0 (Haiku). Lower clamp regression. Q5 disposition: "
        "'Priority=5 on Haiku stays Haiku (already at bottom).'"
    )
    assert marker == "bottom"


# ---------------------------------------------------------------------------
# Constants sentinel
# ---------------------------------------------------------------------------


def test_priority_tier_delta_matches_q6_disposition() -> None:
    """Sentinel: ``_PRIORITY_TIER_DELTA`` encodes Q6 disposition
    ({1: +1, 2: 0, 3: 0, 4: 0, 5: -1}). A future commit that revises
    the mapping fails this test informatively.
    """
    assert _PRIORITY_TIER_DELTA == {1: 1, 2: 0, 3: 0, 4: 0, 5: -1}


def test_role_default_tier_matches_spec() -> None:
    """Sentinel: PM defaults to Opus (idx 2); Engineer / Implementer /
    Tester default to Sonnet (idx 1). Per ``06-mcp-tools-phase-one.md``
    § ``check_budget_for_task``.
    """
    assert _ROLE_DEFAULT_TIER == {
        "pm": 2, "engineer": 1, "implementer": 1, "tester": 1
    }


def test_tier_hierarchy_orders_cheapest_to_capable() -> None:
    """Sentinel: tier hierarchy ordering is Haiku → Sonnet → Opus.
    A reorder would silently invert priority semantics."""
    assert _TIER_HIERARCHY == (
        "claude-haiku-4-5",
        "claude-sonnet-4-6",
        "claude-opus-4-8",
    )


def test_opus_downgrade_threshold_is_q4_disposition() -> None:
    """Sentinel: 80% per Q4. Future hygiene-commit revision is a
    single-line edit; this test catches accidental drift."""
    assert OPUS_DOWNGRADE_UTILIZATION_THRESHOLD == 0.80


# ---------------------------------------------------------------------------
# Rule 1: normal pace, role + priority routing
# ---------------------------------------------------------------------------


def test_routing_engineer_priority_3_normal_pace_returns_sonnet() -> None:
    """Engineer + priority=3 + normal pace → Sonnet (role default,
    no priority delta). Bucket subscription_5h. Not fallback."""
    out = _compute_routing(
        complexity=3,
        priority=3,
        snapshot=_snapshot(),
        caller_role="engineer",
        now=_NOW,
    )
    assert out.recommended_model == "claude-sonnet-4-6"
    assert out.recommended_bucket == "subscription_5h"
    assert out.fallback_active is False
    assert "per_role_default_engineer" in out.routing_factors
    assert "priority_3_no_delta" in out.routing_factors


def test_routing_pm_priority_3_normal_pace_returns_opus() -> None:
    """PM default tier is Opus per spec. Priority=3 doesn't shift it."""
    out = _compute_routing(
        complexity=3,
        priority=3,
        snapshot=_snapshot(),
        caller_role="pm",
        now=_NOW,
    )
    assert out.recommended_model == "claude-opus-4-8"
    assert "per_role_default_pm" in out.routing_factors


def test_routing_engineer_priority_1_upgrades_to_opus() -> None:
    """Priority=1 + Engineer (Sonnet default) → Opus. Q5 upgrade rule."""
    out = _compute_routing(
        complexity=3,
        priority=1,
        snapshot=_snapshot(),
        caller_role="engineer",
        now=_NOW,
    )
    assert out.recommended_model == "claude-opus-4-8"
    assert "priority_1_upgrade_tier" in out.routing_factors


def test_routing_engineer_priority_5_downgrades_to_haiku() -> None:
    """Priority=5 + Engineer (Sonnet default) → Haiku. Q5 downgrade rule."""
    out = _compute_routing(
        complexity=3,
        priority=5,
        snapshot=_snapshot(),
        caller_role="engineer",
        now=_NOW,
    )
    assert out.recommended_model == "claude-haiku-4-5"
    assert "priority_5_downgrade_tier" in out.routing_factors


def test_routing_pm_priority_1_clamps_at_opus_top() -> None:
    """Priority=1 + PM (Opus default) — already at top; clamp factor
    fires. Q5 contract."""
    out = _compute_routing(
        complexity=3,
        priority=1,
        snapshot=_snapshot(),
        caller_role="pm",
        now=_NOW,
    )
    assert out.recommended_model == "claude-opus-4-8"
    assert "tier_clamped_at_top" in out.routing_factors


def test_routing_implementer_priority_5_clamps_at_haiku_bottom() -> None:
    """Implementer (Sonnet default) + priority=5 = Haiku, no clamp.
    But priority=5 on a hypothetical Haiku-default role would clamp
    — verify with a synthetic case via _apply_priority_delta directly
    (covered by test_apply_priority_delta_clamps_at_bottom).

    Here verify the routing path doesn't fire ``tier_clamped_at_bottom``
    for priority=5 on Sonnet (because Sonnet→Haiku is in-range).
    """
    out = _compute_routing(
        complexity=3,
        priority=5,
        snapshot=_snapshot(),
        caller_role="implementer",
        now=_NOW,
    )
    assert out.recommended_model == "claude-haiku-4-5"
    assert "tier_clamped_at_bottom" not in out.routing_factors


# ---------------------------------------------------------------------------
# Rule 2: subscription_7d_opus high → Opus downgrade
# ---------------------------------------------------------------------------


def test_routing_pm_normal_priority_downgrades_when_7d_opus_high() -> None:
    """PM (Opus default) + priority>1 + 7d_opus utilization >= 80%
    → Opus downgrades to Sonnet. Rule 2."""
    out = _compute_routing(
        complexity=3,
        priority=3,  # not critical
        snapshot=_snapshot(seven_day_opus_util=85.0),
        caller_role="pm",
        now=_NOW,
    )
    assert out.recommended_model == "claude-sonnet-4-6"
    assert (
        "opus_downgraded_to_sonnet_due_to_7d_opus_high" in out.routing_factors
    )
    assert (
        "subscription_7d_opus_at_85pct_above_threshold" in out.routing_factors
    )


def test_routing_pm_priority_1_preserves_opus_when_7d_opus_high() -> None:
    """Q7 contract: ``priority=1`` (critical) preserves Opus even
    when ``subscription_7d_opus`` is above threshold. Rule 2 carve-out.
    """
    out = _compute_routing(
        complexity=3,
        priority=1,
        snapshot=_snapshot(seven_day_opus_util=85.0),
        caller_role="pm",
        now=_NOW,
    )
    assert out.recommended_model == "claude-opus-4-8"
    assert "opus_preserved_for_critical_priority" in out.routing_factors
    assert (
        "opus_downgraded_to_sonnet_due_to_7d_opus_high"
        not in out.routing_factors
    )


def test_routing_below_threshold_emits_below_threshold_factor() -> None:
    """7d_opus below 80% → ``below_threshold`` factor. Operator sees
    the threshold check ran AND its outcome."""
    out = _compute_routing(
        complexity=3,
        priority=3,
        snapshot=_snapshot(seven_day_opus_util=67.0),
        caller_role="pm",
        now=_NOW,
    )
    assert (
        "subscription_7d_opus_at_67pct_below_threshold" in out.routing_factors
    )
    # Opus preserved (no downgrade).
    assert out.recommended_model == "claude-opus-4-8"


# ---------------------------------------------------------------------------
# Rule 3: subscription_5h conserve
# ---------------------------------------------------------------------------


def test_routing_conserve_pace_emits_pause_factor() -> None:
    """5h utilization > 80% (conserve pace) → pause-recommended factor.
    Recommendation unchanged (caller decides to pause)."""
    out = _compute_routing(
        complexity=3,
        priority=3,
        snapshot=_snapshot(five_hour_util=85.0),
        caller_role="engineer",
        now=_NOW,
    )
    assert (
        "subscription_5h_conserve_pause_recommended" in out.routing_factors
    )
    # Recommendation unchanged: bucket=subscription_5h, model=role default.
    assert out.recommended_bucket == "subscription_5h"
    assert out.recommended_model == "claude-sonnet-4-6"


# ---------------------------------------------------------------------------
# Rules 4/5/6: rate_limited, saturation, overflow
# ---------------------------------------------------------------------------


def test_routing_rate_limited_with_overflow_routes_to_api_key_overflow() -> None:
    """5h utilization >= 100% (rate_limited) + extra_usage.is_enabled
    = True → bucket switches to api_key_overflow. Rule 6."""
    out = _compute_routing(
        complexity=3,
        priority=3,
        snapshot=_snapshot(
            five_hour_util=100.0, extra_usage_enabled=True
        ),
        caller_role="engineer",
        now=_NOW,
    )
    assert out.recommended_bucket == "api_key_overflow"
    assert "subscription_5h_rate_limited" in out.routing_factors
    assert "routed_to_api_key_overflow" in out.routing_factors


def test_routing_saturated_no_overflow_keeps_subscription_bucket() -> None:
    """5h saturated + overflow disabled → bucket stays subscription_5h
    (Literal restriction); ``subscription_saturated_no_overflow`` factor
    surfaces. Caller queues per Rule 5. Rule 5/Q3 contract."""
    out = _compute_routing(
        complexity=3,
        priority=3,
        snapshot=_snapshot(
            five_hour_util=100.0, extra_usage_enabled=False
        ),
        caller_role="engineer",
        now=_NOW,
    )
    assert out.recommended_bucket == "subscription_5h"
    assert "subscription_saturated_no_overflow" in out.routing_factors


# ---------------------------------------------------------------------------
# Q3 fallback (snapshot None or stale)
# ---------------------------------------------------------------------------


def test_routing_fallback_when_snapshot_none() -> None:
    """Cold start (snapshot=None): per-role default; no priority
    delta; no Opus-downgrade; bucket=subscription_5h;
    fallback_active=True. Q3 disposition β."""
    out = _compute_routing(
        complexity=3,
        priority=1,  # would normally upgrade
        snapshot=None,
        caller_role="engineer",
        now=_NOW,
    )
    assert out.fallback_active is True
    assert out.recommended_model == "claude-sonnet-4-6"  # role default
    assert out.recommended_bucket == "subscription_5h"
    assert "fallback_per_role_defaults" in out.routing_factors
    assert "snapshot_unavailable" in out.routing_factors
    # Priority delta NOT applied even though priority=1 would upgrade.
    assert "priority_1_upgrade_tier" not in out.routing_factors
    assert "priority_1_no_delta" in out.routing_factors


def test_routing_fallback_when_snapshot_stale() -> None:
    """Stale snapshot (>120s) triggers fallback identical to None.
    Per Q3 β."""
    out = _compute_routing(
        complexity=3,
        priority=1,
        snapshot=_snapshot(polled_at=_NOW - timedelta(seconds=200)),
        caller_role="engineer",
        now=_NOW,
    )
    assert out.fallback_active is True
    assert out.recommended_model == "claude-sonnet-4-6"
    assert "fallback_per_role_defaults" in out.routing_factors


def test_routing_rationale_text_names_fallback_explicitly() -> None:
    """Q3 sub-amendment: ``rationale_text`` explicitly names fallback
    status when active. Operators reading the response cold should
    detect the degraded path without parsing factor strings."""
    out = _compute_routing(
        complexity=3,
        priority=2,
        snapshot=None,
        caller_role="pm",
        now=_NOW,
    )
    assert "Snapshot unavailable" in out.rationale_text
    assert "per-role defaults" in out.rationale_text
    assert "Budget-aware adjustments will resume" in out.rationale_text


def test_routing_fresh_snapshot_at_119s_is_not_fallback() -> None:
    """Snapshot age 119s (just under threshold) is NOT fallback.
    Boundary test for the 120s _STALE_SNAPSHOT_THRESHOLD_SECONDS."""
    out = _compute_routing(
        complexity=3,
        priority=3,
        snapshot=_snapshot(polled_at=_NOW - timedelta(seconds=119)),
        caller_role="engineer",
        now=_NOW,
    )
    assert out.fallback_active is False
    assert "fallback_per_role_defaults" not in out.routing_factors


# ---------------------------------------------------------------------------
# snapshot_age_<n>s factor (factor-list addition)
# ---------------------------------------------------------------------------


def test_routing_emits_snapshot_age_factor_when_present() -> None:
    """``snapshot_age_<n>s`` always emitted when snapshot exists, even
    when not falling back. Tier-2 analysis can correlate freshness
    within the threshold (e.g., 90s vs 119s) without relying on
    fallback presence/absence."""
    out = _compute_routing(
        complexity=3,
        priority=3,
        snapshot=_snapshot(polled_at=_NOW - timedelta(seconds=42)),
        caller_role="engineer",
        now=_NOW,
    )
    assert "snapshot_age_42s" in out.routing_factors


def test_routing_emits_snapshot_unavailable_when_none() -> None:
    """``snapshot_unavailable`` replaces the snapshot_age factor when
    the snapshot is None (cold start). Distinct from a present-but-
    stale snapshot (which emits both age and fallback factors)."""
    out = _compute_routing(
        complexity=3,
        priority=3,
        snapshot=None,
        caller_role="engineer",
        now=_NOW,
    )
    assert "snapshot_unavailable" in out.routing_factors
    # snapshot_age_*s is NOT emitted (no snapshot to age).
    assert not any(
        f.startswith("snapshot_age_") for f in out.routing_factors
    )


# ---------------------------------------------------------------------------
# Multi-rule simultaneous firing (Q8 sub-amendment)
# ---------------------------------------------------------------------------


def test_routing_multi_rule_5h_rate_limited_and_7d_opus_high() -> None:
    """Q8 contract: when subscription_5h is rate_limited AND
    subscription_7d_opus is at-or-above threshold, both factor sets
    fire. No rule precedence suppresses either observation; bucket
    and model resolve from independent computations.

    Failure mode this test prevents: a future commit that adds
    implicit rule-precedence ordering (e.g., "saturated short-
    circuits the Opus-downgrade check") would silently drop the
    Opus-downgrade factor or the saturation factor depending on
    which path it hit. The orthogonal-dimensions architecture per
    Q8 disposition β requires both.
    """
    out = _compute_routing(
        complexity=3,
        priority=3,  # non-critical → eligible for Opus downgrade
        snapshot=_snapshot(
            five_hour_util=100.0,  # rate_limited
            seven_day_opus_util=85.0,  # above threshold
            extra_usage_enabled=True,  # overflow → bucket switches
        ),
        caller_role="pm",  # Opus default, would downgrade
        now=_NOW,
    )

    # All applicable factors present.
    expected_factors = {
        "subscription_5h_rate_limited",
        "subscription_5h_at_100pct_rate_limited",
        "routed_to_api_key_overflow",
        "subscription_7d_opus_at_85pct_above_threshold",
        "opus_downgraded_to_sonnet_due_to_7d_opus_high",
    }
    missing = expected_factors - set(out.routing_factors)
    assert not missing, (
        f"Multi-rule firing dropped factors: {missing!r}. "
        "Q8 orthogonal-dimensions architecture requires that all "
        "applicable rules emit their factors without ordering "
        "suppression. A future commit may have introduced implicit "
        "rule precedence (e.g., 'saturated short-circuits Opus-"
        "downgrade check'). Routing factors: "
        f"{sorted(out.routing_factors)!r}."
    )

    # Bucket resolved from saturation dimension (overflow enabled).
    assert out.recommended_bucket == "api_key_overflow"
    # Model resolved from Opus-downgrade dimension (PM Opus → Sonnet).
    assert out.recommended_model == "claude-sonnet-4-6"

    # rationale_text covers both observations.
    assert "Opus downgraded" in out.rationale_text
    assert "overflow enabled" in out.rationale_text


# ---------------------------------------------------------------------------
# Factor naming convention sentinel (Q5 sub-amendment)
# ---------------------------------------------------------------------------


def test_pct_factor_format_sentinel() -> None:
    """Every pct-format factor emitted by the routing function matches
    the documented stable structural prefix ``<dimension>_at_<value>
    pct_<context>``. Future tier-2 LIKE-pattern queries depend on the
    format being stable.

    Sentinel covers the realistic factor surface by exercising routes
    that emit each pct-factor type and asserting the format on every
    factor matching ``*pct*``.
    """
    pattern = re.compile(_PCT_FACTOR_FORMAT_PATTERN)

    # Cover the three pct-emitting branches: 5h normal, 5h conserve,
    # 5h rate_limited; 7d_opus above threshold; 7d_opus below threshold.
    fixtures: list[CheckBudgetForTaskOutput] = [
        _compute_routing(
            complexity=3, priority=3,
            snapshot=_snapshot(five_hour_util=30.0, seven_day_opus_util=20.0),
            caller_role="engineer", now=_NOW,
        ),
        _compute_routing(
            complexity=3, priority=3,
            snapshot=_snapshot(five_hour_util=85.0, seven_day_opus_util=85.0),
            caller_role="pm", now=_NOW,
        ),
        _compute_routing(
            complexity=3, priority=3,
            snapshot=_snapshot(
                five_hour_util=100.0, extra_usage_enabled=True,
                seven_day_opus_util=67.0,
            ),
            caller_role="engineer", now=_NOW,
        ),
    ]

    pct_factors_seen = 0
    for out in fixtures:
        for factor in out.routing_factors:
            if "pct" not in factor:
                continue
            pct_factors_seen += 1
            assert pattern.match(factor), (
                f"Factor {factor!r} violates the documented pct-format "
                "convention. Expected pattern: "
                f"{_PCT_FACTOR_FORMAT_PATTERN!r}. The Q5 sub-amendment "
                "pinned this format so future tier-2 LIKE-pattern queries "
                "are tractable; a regression here breaks downstream "
                "analysis without a runtime error."
            )

    assert pct_factors_seen >= 3, (
        f"Sentinel covered only {pct_factors_seen} pct-format factors; "
        "expected at least 3. The fixture set may have drifted; verify "
        "the routing paths still emit pct-format factors for 5h pace + "
        "7d_opus above + 7d_opus below cases."
    )


# ---------------------------------------------------------------------------
# Rationale text content
# ---------------------------------------------------------------------------


def test_rationale_text_names_role_priority_complexity_in_normal_path() -> None:
    """Operators reading rationale_text cold should see the inputs
    that drove the decision. The text composes from the structured
    factors but echoes role/priority/complexity for readability."""
    out = _compute_routing(
        complexity=4,
        priority=2,
        snapshot=_snapshot(),
        caller_role="engineer",
        now=_NOW,
    )
    assert "engineer" in out.rationale_text
    assert "priority=2" in out.rationale_text
    assert "complexity=4" in out.rationale_text


def test_rationale_text_names_overflow_when_routed() -> None:
    out = _compute_routing(
        complexity=3,
        priority=3,
        snapshot=_snapshot(
            five_hour_util=100.0, extra_usage_enabled=True
        ),
        caller_role="engineer",
        now=_NOW,
    )
    assert "overflow enabled" in out.rationale_text


def test_rationale_text_names_saturation_with_no_overflow() -> None:
    out = _compute_routing(
        complexity=3,
        priority=3,
        snapshot=_snapshot(
            five_hour_util=100.0, extra_usage_enabled=False
        ),
        caller_role="engineer",
        now=_NOW,
    )
    assert "queue" in out.rationale_text or "operator" in out.rationale_text


# ---------------------------------------------------------------------------
# Defensive: malformed snapshot payloads degrade rather than crash
# ---------------------------------------------------------------------------


def test_routing_malformed_snapshot_collapses_to_fallback() -> None:
    """Per the malformed-snapshot disposition (a): missing five_hour
    counter triggers the fallback path indistinguishable from
    snapshot=None. ``fallback_active=True`` and
    ``fallback_per_role_defaults`` factor present; priority delta
    NOT applied (priority=1 would normally upgrade Engineer Sonnet
    to Opus, but fallback suppresses the delta).

    Failure mode this test prevents: a future commit that reverts to
    per-counter graceful degradation would let routing apply the
    priority delta + miss the malformed-counter signal. The
    conservative full-fallback semantics avoid the silent-rate_
    limited-miss bug class on a malformed five_hour.
    """
    out = _compute_routing(
        complexity=3,
        priority=1,  # would upgrade absent fallback
        snapshot=_snapshot(five_hour_util=None),
        caller_role="engineer",
        now=_NOW,
    )
    assert out.fallback_active is True, (
        "Malformed five_hour did not collapse to fallback. The "
        "disposition (a) requires malformed snapshots be treated "
        "identically to snapshot=None; see _compute_routing docstring."
    )
    assert "fallback_per_role_defaults" in out.routing_factors
    # Engineer's role default; priority=1 delta NOT applied under fallback.
    assert out.recommended_model == "claude-sonnet-4-6"
    assert "priority_1_no_delta" in out.routing_factors


def test_routing_malformed_seven_day_opus_collapses_to_fallback() -> None:
    """Malformed seven_day_opus counter also triggers full fallback
    per disposition (a). PM with priority=1 would normally see
    Opus-preserved-for-critical-priority logic, but fallback short-
    circuits before any counter check fires.
    """
    out = _compute_routing(
        complexity=3,
        priority=1,
        snapshot=_snapshot(seven_day_opus_util=None),
        caller_role="pm",
        now=_NOW,
    )
    assert out.fallback_active is True
    assert "fallback_per_role_defaults" in out.routing_factors
    # PM's role default; no Opus-downgrade check fires (we're in
    # fallback, not the per-role-defaults path that runs the check).
    assert out.recommended_model == "claude-opus-4-8"
    assert not any(
        "subscription_7d_opus" in f for f in out.routing_factors
    )


def test_routing_malformed_resets_at_string_collapses_to_fallback() -> None:
    """Malformed resets_at value (key present, string unparseable as
    ISO 8601) triggers fallback. Bucket stays subscription_5h
    (Literal restriction + fallback default); fallback_active=True
    surfaces the degraded path.
    """
    bad_snapshot = CachedSnapshot(
        payload={
            "five_hour": {
                "utilization": 50.0,
                "resets_at": "not-a-real-iso-string",
            },
            "seven_day_opus": {"utilization": 20.0},
            "extra_usage": {"is_enabled": False},
        },
        polled_at=_NOW,
        null_counters=[],
    )
    out = _compute_routing(
        complexity=3,
        priority=3,
        snapshot=bad_snapshot,
        caller_role="engineer",
        now=_NOW,
    )
    assert out.fallback_active is True
    assert "fallback_per_role_defaults" in out.routing_factors
    assert out.recommended_bucket == "subscription_5h"


def test_routing_malformed_extra_usage_collapses_to_fallback() -> None:
    """Missing extra_usage.is_enabled (or wrong type) collapses to
    fallback. Closes the parseability check on the third required
    field. Without this test, a future commit that drops the
    extra_usage parseability gate would silently route normally
    while the overflow decision degrades unobservably.
    """
    bad_snapshot = CachedSnapshot(
        payload={
            "five_hour": {
                "utilization": 50.0,
                "resets_at": (
                    _NOW + timedelta(hours=4)
                ).isoformat(timespec="microseconds"),
            },
            "seven_day_opus": {"utilization": 20.0},
            # extra_usage key missing entirely.
        },
        polled_at=_NOW,
        null_counters=[],
    )
    out = _compute_routing(
        complexity=3,
        priority=3,
        snapshot=bad_snapshot,
        caller_role="engineer",
        now=_NOW,
    )
    assert out.fallback_active is True
    assert "fallback_per_role_defaults" in out.routing_factors
