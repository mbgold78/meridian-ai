"""Hard-timeout calculation for sub-agent dispatches.

Per ``11-engineer-bridge.md`` § Hard timeout calculation:

    hard_timeout_seconds = max(estimated_tokens / model_throughput_tps, 300)

Two co-located mappings:

- :data:`MODEL_FOR_TIER` — agent-config tier (``haiku`` / ``sonnet`` /
  ``opus``) → canonical model string. Single source of truth for the
  three Phase-1 model identities.
- :data:`MODEL_THROUGHPUT_TPS` — canonical model string →
  tokens-per-second starting estimate. Constants pending M4 calibration
  (the calibration runs during Mike's manual integration acceptance test
  and updates this table before M5 closes; see the spec § Hard timeout
  calculation calibration note).

The lookup chain at dispatch time:

    role (e.g., "implementer")
      → registry.get(role).config.model_tier_default     # "sonnet"
      → model_for_tier(tier)                              # "claude-sonnet-4-6"
      → calculate_timeout(model, estimated_tokens)        # max(N/60, 300)

`BridgeDispatcher.dispatch()` accepts an optional ``model`` parameter;
when provided, the dispatcher reads ``tasks.estimated_tokens`` from
storage and invokes :func:`calculate_timeout`. When ``model`` is None
(legacy callers), the dispatcher falls back to ``timeout_seconds``
(default 300s).

Calibration is a separate operator workflow — see the M4 acceptance
test in ``08-implementation-plan.md``. M4c-tooling ships the file with
starting-estimate constants; production end-to-end exercise via the MCP
wrapper layer ships at M5 alongside the dispatch-wrapper gap closure.
"""

from __future__ import annotations

from typing import Literal

ModelTier = Literal["haiku", "sonnet", "opus"]
ModelString = Literal[
    "claude-haiku-4-5",
    "claude-sonnet-4-6",
    "claude-opus-4-7",
]

# Tier → canonical model string. Source of truth for Phase-1 model
# identities. Cross-referenced with ``config/agents/*.yaml``'s
# ``model_tier_default`` field and ``11-engineer-bridge.md`` § Hard
# timeout calculation.
MODEL_FOR_TIER: dict[ModelTier, ModelString] = {
    "haiku": "claude-haiku-4-5",
    "sonnet": "claude-sonnet-4-6",
    "opus": "claude-opus-4-7",
}

# Tokens-per-second starting estimates per ``11-engineer-bridge.md:207-209``.
# Calibration pending M4 acceptance test; see the spec's calibration note.
# If/when measured-tps numbers are folded back, update both this table
# and the spec line in lockstep.
MODEL_THROUGHPUT_TPS: dict[ModelString, int] = {
    "claude-haiku-4-5": 100,
    "claude-sonnet-4-6": 60,
    "claude-opus-4-7": 30,
}

# Floor for the timeout — five minutes covers startup, auth, and a
# minimal complete_dispatch round-trip even for tiny dispatches.
HARD_TIMEOUT_FLOOR_SECONDS: int = 300

# Used when the task row's ``estimated_tokens`` is NULL (PM didn't
# specify). Conservative default — at sonnet's 60 tps this gives
# 10000/60 ≈ 167s, well under the 300s floor; in practice the floor is
# what kicks in for default-estimate tasks. Larger explicit estimates
# (e.g., 100k tokens for a long Playwright suite) extend the timeout
# correctly.
DEFAULT_ESTIMATED_TOKENS: int = 10_000


def model_for_tier(tier: ModelTier) -> ModelString:
    """Resolve an agent's ``model_tier_default`` to its canonical model string.

    Raises:
        ValueError: if ``tier`` is not one of the three Phase-1 tiers.
            Engineer/PM/Implementer/Tester YAML configs all have valid
            tiers; an unknown tier indicates either a typo in a YAML
            edit or a code-side enum drift.
    """
    if tier not in MODEL_FOR_TIER:
        raise ValueError(
            f"unknown model tier {tier!r}; expected one of "
            f"{sorted(MODEL_FOR_TIER)}"
        )
    return MODEL_FOR_TIER[tier]


def calculate_timeout(
    *,
    model: ModelString,
    estimated_tokens: int | None = None,
) -> int:
    """Return the hard-timeout seconds for a dispatch.

    Formula: ``max(estimated_tokens / model_throughput_tps,
    HARD_TIMEOUT_FLOOR_SECONDS)``.

    When ``estimated_tokens`` is None or 0 (PM didn't set a value, or
    set it to 0), falls back to :data:`DEFAULT_ESTIMATED_TOKENS`. The
    floor handles the small-task case correctly (default estimate at
    sonnet/opus tps falls below 300, floor wins).

    Negative ``estimated_tokens`` is treated as 0 (defensive — should
    never happen but the floor would catch it anyway).

    Raises:
        ValueError: if ``model`` is not in :data:`MODEL_THROUGHPUT_TPS`.
            Don't silently default to a guess — an unknown model means
            either a code bug or a config drift, and a clear error is
            more debuggable than a silently-wrong timeout.
    """
    if model not in MODEL_THROUGHPUT_TPS:
        raise ValueError(
            f"unknown model {model!r}; expected one of "
            f"{sorted(MODEL_THROUGHPUT_TPS)}"
        )
    tokens = estimated_tokens if estimated_tokens and estimated_tokens > 0 else DEFAULT_ESTIMATED_TOKENS
    tps = MODEL_THROUGHPUT_TPS[model]
    calculated = tokens // tps  # int division; truncates fractional seconds
    return max(calculated, HARD_TIMEOUT_FLOOR_SECONDS)


__all__ = [
    "DEFAULT_ESTIMATED_TOKENS",
    "HARD_TIMEOUT_FLOOR_SECONDS",
    "MODEL_FOR_TIER",
    "MODEL_THROUGHPUT_TPS",
    "ModelString",
    "ModelTier",
    "calculate_timeout",
    "model_for_tier",
]
