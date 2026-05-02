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

from meridian_orchestrator.tools.budget import (
    MODEL_PRICING,
    _compute_cost_usd,
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
