"""Unit tests for bridge.timeouts.

Validates calculate_timeout's formula (max(estimated_tokens / tps, 300))
and the tier→model mapping. Calibration of the tps constants themselves
is a separate operator workflow during M4 acceptance testing — see the
spec § Hard timeout calculation calibration note.
"""

from __future__ import annotations

import pytest

from meridian_orchestrator.bridge.timeouts import (
    DEFAULT_ESTIMATED_TOKENS,
    HARD_TIMEOUT_FLOOR_SECONDS,
    MODEL_FOR_TIER,
    MODEL_THROUGHPUT_TPS,
    calculate_timeout,
    model_for_tier,
)

# ----- model_for_tier -----------------------------------------------------


def test_model_for_tier_resolves_three_phase_one_tiers() -> None:
    assert model_for_tier("haiku") == "claude-haiku-4-5"
    assert model_for_tier("sonnet") == "claude-sonnet-4-6"
    assert model_for_tier("opus") == "claude-opus-4-8"


def test_model_for_tier_unknown_tier_raises() -> None:
    with pytest.raises(ValueError, match="unknown model tier"):
        model_for_tier("turbo")  # type: ignore[arg-type]


def test_model_for_tier_table_aligns_with_tps_table() -> None:
    """Every tier maps to a model that has a tps entry — catches drift
    between the two co-located tables."""
    for tier, model in MODEL_FOR_TIER.items():
        assert model in MODEL_THROUGHPUT_TPS, (
            f"tier {tier!r} maps to model {model!r}, "
            f"which is missing from MODEL_THROUGHPUT_TPS"
        )


# ----- calculate_timeout: tps-driven path --------------------------------


def test_calculate_timeout_haiku_long_task() -> None:
    """Haiku at 100 tps with 60000 estimated tokens → 600s (above floor)."""
    assert calculate_timeout(model="claude-haiku-4-5", estimated_tokens=60_000) == 600


def test_calculate_timeout_sonnet_long_task() -> None:
    """Sonnet at 60 tps with 60000 estimated tokens → 1000s (above floor)."""
    assert (
        calculate_timeout(model="claude-sonnet-4-6", estimated_tokens=60_000) == 1000
    )


def test_calculate_timeout_opus_long_task() -> None:
    """Opus at 30 tps with 60000 estimated tokens → 2000s (above floor)."""
    assert calculate_timeout(model="claude-opus-4-8", estimated_tokens=60_000) == 2000


# ----- calculate_timeout: floor enforcement -------------------------------


def test_calculate_timeout_short_task_hits_floor() -> None:
    """Sonnet at 60 tps with 1000 tokens → 16s calculated, floor lifts to 300."""
    assert (
        calculate_timeout(model="claude-sonnet-4-6", estimated_tokens=1000)
        == HARD_TIMEOUT_FLOOR_SECONDS
    )


def test_calculate_timeout_zero_tokens_uses_default_then_floor() -> None:
    """estimated_tokens=0 falls back to DEFAULT_ESTIMATED_TOKENS (10000),
    which at sonnet's 60 tps yields ~167s, below the 300s floor."""
    assert (
        calculate_timeout(model="claude-sonnet-4-6", estimated_tokens=0)
        == HARD_TIMEOUT_FLOOR_SECONDS
    )


def test_calculate_timeout_none_tokens_uses_default_then_floor() -> None:
    """Same as zero — None means PM didn't set a value; default + floor."""
    assert (
        calculate_timeout(model="claude-sonnet-4-6", estimated_tokens=None)
        == HARD_TIMEOUT_FLOOR_SECONDS
    )


def test_calculate_timeout_negative_tokens_treated_as_default() -> None:
    """Defensive — negative values shouldn't propagate through the formula."""
    assert (
        calculate_timeout(model="claude-sonnet-4-6", estimated_tokens=-500)
        == HARD_TIMEOUT_FLOOR_SECONDS
    )


def test_default_estimated_tokens_at_sonnet_hits_floor() -> None:
    """Sanity: DEFAULT_ESTIMATED_TOKENS / sonnet_tps < HARD_TIMEOUT_FLOOR_SECONDS.

    Sonnet is the default tier for Engineer/Implementer/Tester
    (per agents/*.yaml). At 60 tps, 10000 default tokens yields ~167s,
    well under the 300s floor — small default-estimated tasks at the
    common tier hit the floor.

    Note: at Opus's 30 tps, the default 10000 tokens computes to 333s
    (above the floor). PM's tier-default is opus, but at M4c sub-agent
    dispatches (the only tier that goes through this code path) use
    Implementer's tier (sonnet), so the floor is what matters in
    practice. If a future Phase 1 change routes opus dispatches through
    this path, the spec note about "300s floor for tiny dispatches"
    becomes inaccurate at that tier — refresh the spec rather than
    the constants."""
    sonnet_tps = MODEL_THROUGHPUT_TPS["claude-sonnet-4-6"]
    assert (
        DEFAULT_ESTIMATED_TOKENS // sonnet_tps < HARD_TIMEOUT_FLOOR_SECONDS
    )


# ----- calculate_timeout: error cases -------------------------------------


def test_calculate_timeout_unknown_model_raises() -> None:
    """Don't silently default — an unknown model is either a code bug or a
    config drift; explicit error is more debuggable than a silently-wrong
    timeout."""
    with pytest.raises(ValueError, match="unknown model"):
        calculate_timeout(
            model="claude-future-5-0",  # type: ignore[arg-type]
            estimated_tokens=10_000,
        )
