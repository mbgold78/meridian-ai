"""Unit tests for tools.budget — cost computation pure-function coverage.

Verifies :func:`_compute_cost_usd` and :data:`MODEL_PRICING` correctness:

- All three Phase-1 models (Opus, Sonnet, Haiku) compute correctly.
- Unknown model returns None (the diagnostic for "we don't know this
  model's price"; see :func:`_compute_cost_usd` docstring).
- Zero tokens yield zero cost (boundary).
- Asymmetric input/output pricing reflected in the formula (Opus output
  is 5x its input price; Sonnet and Haiku similarly asymmetric).

Writer integration tests (api_usage row insertion, dispatches.actual_tokens
increment, audit row emission, dispatch-vs-OAuth caller paths) live in
``tests/integration/test_record_api_call.py``.

Maintenance discipline: when extending :data:`MODEL_PRICING` for new
models or revising prices, add coverage here. The
``test_known_models_have_test_coverage`` sentinel asserts that every
model in MODEL_PRICING has a corresponding pricing test, so a future
add to the dict that forgets the test fails this file informatively.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from meridian_orchestrator.bridge.oauth_usage import CachedSnapshot
from meridian_orchestrator.tools.budget import (
    _AGENT_ROLE_SUBTREE,
    MODEL_PRICING,
    _compute_cost_usd,
    _resolve_window,
)


def test_compute_cost_usd_opus_pricing() -> None:
    """Opus: $15/M input, $75/M output. 1M input + 1M output = $90."""
    assert (
        _compute_cost_usd("claude-opus-4-7", 1_000_000, 1_000_000) == 90.00
    )


def test_compute_cost_usd_sonnet_pricing() -> None:
    """Sonnet: $3/M input, $15/M output. 1M input + 1M output = $18."""
    assert (
        _compute_cost_usd("claude-sonnet-4-6", 1_000_000, 1_000_000) == 18.00
    )


def test_compute_cost_usd_haiku_pricing() -> None:
    """Haiku: $1/M input, $5/M output. 1M input + 1M output = $6."""
    assert (
        _compute_cost_usd("claude-haiku-4-5", 1_000_000, 1_000_000) == 6.00
    )


def test_compute_cost_usd_realistic_call_size() -> None:
    """Sub-million-token call: Sonnet at 5000 input + 2000 output =
    (5000 * 3 + 2000 * 15) / 1e6 = 45000 / 1e6 = $0.045."""
    cost = _compute_cost_usd("claude-sonnet-4-6", 5000, 2000)
    assert cost is not None
    assert abs(cost - 0.045) < 1e-9


def test_compute_cost_usd_zero_tokens() -> None:
    """Zero tokens yield zero cost regardless of model."""
    assert _compute_cost_usd("claude-opus-4-7", 0, 0) == 0.0
    assert _compute_cost_usd("claude-sonnet-4-6", 0, 0) == 0.0
    assert _compute_cost_usd("claude-haiku-4-5", 0, 0) == 0.0


def test_current_phase_one_models_have_5x_output_input_ratio() -> None:
    """Empirical observation about the three currently-stubbed Phase-1
    models: output tokens cost 5x input tokens. NOT a structural
    Anthropic guarantee — Anthropic may introduce a model tier with a
    different ratio, in which case this test fails on a legitimate
    pricing-table update (not a bug).

    Kept because the ratio happens to hold across all three current
    models and a typo that breaks the ratio for one of them (e.g.,
    transposed input/output prices) would be caught here. When R-2
    verification lands or pricing is revised, update this test
    alongside MODEL_PRICING — either revise the expected ratio,
    rename the test to name the new invariant, or drop the test if
    the ratio is no longer uniform across the stubbed models.
    """
    for model in ("claude-opus-4-7", "claude-sonnet-4-6", "claude-haiku-4-5"):
        input_only = _compute_cost_usd(model, 1000, 0)
        output_only = _compute_cost_usd(model, 0, 1000)
        assert input_only is not None
        assert output_only is not None
        assert input_only > 0
        assert output_only > 0
        # Allow tiny floating-point slop in the ratio assertion.
        assert abs(output_only / input_only - 5.0) < 1e-9, (
            f"{model} output:input ratio is {output_only / input_only!r}, "
            "expected 5.0 per the empirical Phase-1 observation. If "
            "pricing was revised in MODEL_PRICING, update this test "
            "alongside (rename, revise ratio, or drop)."
        )


def test_compute_cost_usd_unknown_model_returns_none() -> None:
    """Models not in MODEL_PRICING return None — the diagnostic value
    that lets the writer record the api_usage row without cost data
    rather than blocking the write entirely."""
    assert _compute_cost_usd("claude-haiku-3", 1000, 1000) is None
    assert _compute_cost_usd("gpt-4-turbo", 1000, 1000) is None
    assert _compute_cost_usd("", 1000, 1000) is None
    assert _compute_cost_usd("claude-sonnet-4-6-20251201", 1000, 1000) is None


def test_known_models_have_test_coverage() -> None:
    """Sentinel: every model in MODEL_PRICING has a corresponding
    pricing test in this file. If a future commit adds a model to
    MODEL_PRICING without adding a pricing test, this sentinel fails
    informatively, prompting deliberate test addition.

    Implementation: keep an explicit list of tested models here; assert
    it matches MODEL_PRICING.keys(). When you add a model to
    MODEL_PRICING, add it to TESTED_MODELS and write a
    test_compute_cost_usd_<model>_pricing function.
    """
    tested_models = {
        "claude-opus-4-7",
        "claude-sonnet-4-6",
        "claude-haiku-4-5",
    }
    actual_models = set(MODEL_PRICING.keys())
    assert tested_models == actual_models, (
        f"MODEL_PRICING contains models without test coverage. "
        f"Tested: {sorted(tested_models)!r}. "
        f"Actual: {sorted(actual_models)!r}. "
        "Add a test_compute_cost_usd_<model>_pricing function for each "
        "missing model and update tested_models in this sentinel test."
    )


def test_model_pricing_values_are_positive() -> None:
    """Defensive: every pricing tuple has positive input and output
    prices. A future revision setting a price to 0 or negative would
    likely be a typo; this sentinel catches it."""
    for model, (input_price, output_price) in MODEL_PRICING.items():
        assert input_price > 0, f"{model} input price must be positive"
        assert output_price > 0, f"{model} output price must be positive"


# ---------------------------------------------------------------------------
# _AGENT_ROLE_SUBTREE — hierarchy subtree map
# ---------------------------------------------------------------------------


def test_agent_role_subtree_contains_four_roles() -> None:
    """Sentinel: the four-role hierarchy is durably encoded. Phase 2
    multi-tenancy may extend; this test guards against accidental
    drops or typos in the map.
    """
    assert set(_AGENT_ROLE_SUBTREE.keys()) == {
        "pm", "engineer", "implementer", "tester"
    }


def test_agent_role_subtree_pm_includes_all_four() -> None:
    """PM's subtree includes self + Engineer + Implementer + Tester
    (the entire hierarchy under Mike). PM with agent_id=None rolls up
    every agent's usage.
    """
    assert _AGENT_ROLE_SUBTREE["pm"] == frozenset(
        {"pm", "engineer", "implementer", "tester"}
    )


def test_agent_role_subtree_engineer_excludes_pm() -> None:
    """Engineer's subtree includes self + Implementer + Tester.
    Engineer cannot see PM's usage. The hierarchy enforcement test.
    """
    assert _AGENT_ROLE_SUBTREE["engineer"] == frozenset(
        {"engineer", "implementer", "tester"}
    )
    assert "pm" not in _AGENT_ROLE_SUBTREE["engineer"]


def test_agent_role_subtree_implementer_self_only() -> None:
    """Implementer's subtree is just implementer (own role only).
    Implementer cannot see Tester's usage even though both report to
    Engineer — sibling isolation per spec hierarchy invariant 4.
    """
    assert _AGENT_ROLE_SUBTREE["implementer"] == frozenset({"implementer"})


def test_agent_role_subtree_tester_self_only() -> None:
    """Tester's subtree is just tester. Symmetric to implementer's
    self-only scope."""
    assert _AGENT_ROLE_SUBTREE["tester"] == frozenset({"tester"})


# ---------------------------------------------------------------------------
# _resolve_window — pure function tests for window-resolution semantics
# ---------------------------------------------------------------------------


def _make_snapshot_with_resets_at(
    resets_at: datetime, polled_at: datetime
) -> CachedSnapshot:
    """Build a CachedSnapshot whose five_hour.resets_at is set; other
    fields are left empty. Suitable for _resolve_window tests that
    only need the subscription_5h cycle anchor.
    """
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


def test_resolve_window_current_with_fresh_snapshot() -> None:
    """``current`` resolves to (resets_at - 5h, resets_at) using the
    snapshot's five_hour.resets_at."""
    now = datetime(2026, 5, 3, 14, 0, 0, tzinfo=UTC)
    resets_at = datetime(2026, 5, 3, 17, 0, 0, tzinfo=UTC)  # 3h from now
    snapshot = _make_snapshot_with_resets_at(resets_at, polled_at=now)

    resolution = _resolve_window(window="current", snapshot=snapshot, now=now)

    assert resolution.window_end == resets_at
    assert resolution.window_start == resets_at - timedelta(hours=5)
    assert resolution.fallback == "none"


def test_resolve_window_last_with_fresh_snapshot() -> None:
    """``last`` resolves to (resets_at - 10h, resets_at - 5h) — the
    previous 5h cycle. ``window_end`` is the start of the current
    cycle; ``window_start`` is 5h before that.
    """
    now = datetime(2026, 5, 3, 14, 0, 0, tzinfo=UTC)
    resets_at = datetime(2026, 5, 3, 17, 0, 0, tzinfo=UTC)
    snapshot = _make_snapshot_with_resets_at(resets_at, polled_at=now)

    resolution = _resolve_window(window="last", snapshot=snapshot, now=now)

    assert resolution.window_end == resets_at - timedelta(hours=5)
    assert resolution.window_start == resets_at - timedelta(hours=10)
    assert resolution.fallback == "none"


def test_resolve_window_today_calendar() -> None:
    """``today`` resolves to (today_UTC_00:00, tomorrow_UTC_00:00).
    Snapshot-independent. Open window — now is between start and end.
    """
    now = datetime(2026, 5, 3, 14, 30, 45, tzinfo=UTC)
    resolution = _resolve_window(window="today", snapshot=None, now=now)

    assert resolution.window_start == datetime(2026, 5, 3, 0, 0, 0, tzinfo=UTC)
    assert resolution.window_end == datetime(2026, 5, 4, 0, 0, 0, tzinfo=UTC)
    assert resolution.fallback == "none"


def test_resolve_window_last_24h_rolling() -> None:
    """``last_24h`` resolves to (now - 24h, now). Pure rolling window;
    snapshot-independent."""
    now = datetime(2026, 5, 3, 14, 30, 45, tzinfo=UTC)
    resolution = _resolve_window(window="last_24h", snapshot=None, now=now)

    assert resolution.window_start == now - timedelta(hours=24)
    assert resolution.window_end == now
    assert resolution.fallback == "none"


def test_resolve_window_current_falls_back_when_snapshot_none() -> None:
    """Cold-start: snapshot=None → ``current`` falls back to
    (now - 24h, now) with fallback='calendar_24h'."""
    now = datetime(2026, 5, 3, 14, 0, 0, tzinfo=UTC)
    resolution = _resolve_window(window="current", snapshot=None, now=now)

    assert resolution.window_start == now - timedelta(hours=24)
    assert resolution.window_end == now
    assert resolution.fallback == "calendar_24h"


def test_resolve_window_current_falls_back_when_snapshot_stale() -> None:
    """Snapshot age > 120s → ``current`` falls back to calendar_24h.
    The 120s threshold is _STALE_SNAPSHOT_THRESHOLD_SECONDS, shared
    with get_window_state.
    """
    now = datetime(2026, 5, 3, 14, 0, 0, tzinfo=UTC)
    resets_at = datetime(2026, 5, 3, 17, 0, 0, tzinfo=UTC)
    # Snapshot polled 200 seconds ago — stale.
    polled_at = now - timedelta(seconds=200)
    snapshot = _make_snapshot_with_resets_at(resets_at, polled_at=polled_at)

    resolution = _resolve_window(window="current", snapshot=snapshot, now=now)

    assert resolution.window_start == now - timedelta(hours=24)
    assert resolution.window_end == now
    assert resolution.fallback == "calendar_24h"


def test_resolve_window_last_fallback_does_not_shift_backward_24h() -> None:
    """Critical contract: when ``last`` falls back to calendar_24h,
    the window is (now - 24h, now) — the SAME shape as ``current``'s
    fallback. ``last`` does NOT additionally shift backward by 24
    hours into a (now - 48h, now - 24h) window. Future-Claude reading
    a response with window='last' + fallback='calendar_24h' must NOT
    expect a shifted-back window.

    Failure here means the fallback path forked between current and
    last — operator interpretation breaks because the documented
    "fallback collapses both onto same surface" contract is violated.
    """
    now = datetime(2026, 5, 3, 14, 0, 0, tzinfo=UTC)
    resolution = _resolve_window(window="last", snapshot=None, now=now)

    assert resolution.window_start == now - timedelta(hours=24), (
        "last fell back to a shifted window — expected (now - 24h, "
        "now), same as current's fallback. Documented contract: both "
        "current and last collapse onto the same calendar_24h surface "
        "when the snapshot is unavailable; 'last' does NOT shift "
        "backward 24h further. See _resolve_window docstring."
    )
    assert resolution.window_end == now
    assert resolution.fallback == "calendar_24h"


def test_resolve_window_today_unaffected_by_snapshot_state() -> None:
    """``today`` doesn't depend on the snapshot — fallback is always
    'none' regardless of snapshot state."""
    now = datetime(2026, 5, 3, 14, 30, 45, tzinfo=UTC)
    today_start = datetime(2026, 5, 3, 0, 0, 0, tzinfo=UTC)
    tomorrow_start = datetime(2026, 5, 4, 0, 0, 0, tzinfo=UTC)

    for snapshot in (
        None,
        _make_snapshot_with_resets_at(
            datetime(2026, 5, 3, 17, 0, 0, tzinfo=UTC), polled_at=now
        ),
    ):
        resolution = _resolve_window(window="today", snapshot=snapshot, now=now)
        assert resolution.window_start == today_start
        assert resolution.window_end == tomorrow_start
        assert resolution.fallback == "none"


def test_resolve_window_last_24h_unaffected_by_snapshot_state() -> None:
    """``last_24h`` is rolling and snapshot-independent — fallback
    stays 'none' regardless of snapshot."""
    now = datetime(2026, 5, 3, 14, 0, 0, tzinfo=UTC)
    for snapshot in (
        None,
        _make_snapshot_with_resets_at(
            datetime(2026, 5, 3, 17, 0, 0, tzinfo=UTC), polled_at=now
        ),
    ):
        resolution = _resolve_window(
            window="last_24h", snapshot=snapshot, now=now
        )
        assert resolution.window_start == now - timedelta(hours=24)
        assert resolution.window_end == now
        assert resolution.fallback == "none"


def test_resolve_window_handles_malformed_resets_at() -> None:
    """If snapshot.payload['five_hour']['resets_at'] is malformed,
    the resolver falls back to calendar_24h rather than raising.
    Defensive degradation — production should never see this path,
    but a corrupt poll payload should not crash window resolution.
    """
    now = datetime(2026, 5, 3, 14, 0, 0, tzinfo=UTC)
    bad_snapshot = CachedSnapshot(
        payload={"five_hour": {"resets_at": "not-a-real-iso-string"}},
        polled_at=now,
        null_counters=[],
    )

    resolution = _resolve_window(
        window="current", snapshot=bad_snapshot, now=now
    )
    assert resolution.fallback == "calendar_24h"
    assert resolution.window_start == now - timedelta(hours=24)
    assert resolution.window_end == now



def test_workflow_chain_max_depth_sentinel() -> None:
    """Pin _WORKFLOW_CHAIN_MAX_DEPTH at 100. The constant bounds the
    recursive CTE in _resolve_workflow_id_from_dispatch (M5-continuous-
    pm-driver commit 1) for cycle protection. Real production chains
    are 1-3 hops; 100 is generous defensive-only protection.

    Sentinel pattern matches _PRIORITY_TIER_DELTA / _TIER_HIERARCHY /
    _AGENT_ROLE_SUBTREE from M5-subscription-routing — the value is a
    deliberate design decision and a future change should be
    deliberate (failing this sentinel forces the diff to surface the
    rationale).
    """
    from meridian_orchestrator.tools.budget import _WORKFLOW_CHAIN_MAX_DEPTH

    assert _WORKFLOW_CHAIN_MAX_DEPTH == 100, (
        f"_WORKFLOW_CHAIN_MAX_DEPTH={_WORKFLOW_CHAIN_MAX_DEPTH}, expected "
        "100. The constant bounds the recursive CTE depth in "
        "_resolve_workflow_id_from_dispatch. Tightening is fine in "
        "principle but should be deliberate; a future commit changing "
        "this value must update this sentinel and surface the rationale "
        "in its prep checkpoint."
    )
