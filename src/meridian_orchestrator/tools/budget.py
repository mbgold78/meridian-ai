"""Budget and usage tools.

M5-record-api-call ships the writer surface:

- :func:`record_api_call` — handler for the ``record_api_call`` MCP tool.
  Inserts an ``api_usage`` row and atomically increments the caller's
  active dispatch row's ``actual_tokens`` (when the caller is bound to
  a non-terminal dispatch).
- :data:`MODEL_PRICING` — model→price table for the three Phase-1
  models. Cost per call is computed from this table at write time.
- :func:`_compute_cost_usd` — pure function over (model, input_tokens,
  output_tokens) returning the API-tier-equivalent cost in USD.

Downstream M5-oauth-usage-poller-and-get-window-state ships the
snapshot consumers (``get_token_usage``, ``get_burn_rate``,
``get_window_state``, ``check_budget_for_task``). They land in this
same module when their sub-milestone arrives.

**Cost model**: ``cost_usd`` is the **API-tier-equivalent cost** — what
this call would have cost at per-token API pricing — not actual
billing. Per the subscription-first architecture
(``project_subscription_first_architecture`` memory), Phase 1 runs on
Mike's OAuth subscription; per-token pricing only becomes real billing
when ``extra_usage.is_enabled: true`` and the call routes to
``api_key_overflow``. Future readers wanting "what did we actually owe
Anthropic this month" must derive at query time by joining the
relevant time window against the polled snapshot's
``extra_usage`` state. The same query-time-derivation pattern as bucket
attribution per ``project_subscription_first_architecture`` § B-3.

The `cost_usd` column in ``api_usage`` therefore serves as a
**cost-multiplier-for-routing-decisions** signal — useful even on
subscription calls because it lets routing-tier code compare "Opus is
~10x Sonnet on per-token pricing" without re-deriving from a price
table at query time.

**Two-token paths handled** (per ``project_subscription_first_architecture``
§ R-4):

1. PM-continuous static token / Implementer-or-Tester per-dispatch
   token → INSERTs ``api_usage`` row + INCREMENTs
   ``dispatches.actual_tokens`` for the bound dispatch.
2. Mike's OAuth PM token → INSERTs ``api_usage`` row only. No dispatch
   row matches the token; the dispatch lookup returns None and the
   increment is skipped silently. Same skip-on-no-match shape as
   :func:`bridge.activity.record_mcp_call`.
3. Caller's bound dispatch is in a terminal state → INSERTs
   ``api_usage`` row + skips the dispatch increment (a final API call
   after the dispatch terminated should not retroactively grow the
   dispatch's actual_tokens).
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

import structlog
from pydantic import BaseModel, Field

from meridian_orchestrator.audit.logger import write_audit
from meridian_orchestrator.bridge.dispatcher import _TERMINAL_STATES
from meridian_orchestrator.storage.interface import Storage

logger = structlog.get_logger(__name__)


# ----- Model→price table ---------------------------------------------------

# Per-million-token pricing for the three Phase-1 models. Tuple shape:
# (input_price_per_million, output_price_per_million) in USD.
#
# Source: docs.anthropic.com/pricing as observed during M5-record-api-call
# drafting (2026-05-01). UNVERIFIED — needs operator verification before
# the M5-flag-flips sub-milestone per BACKLOG.md (subscription tier
# identification + model pricing verification entry). Pricing may shift
# over time and Anthropic may introduce new tiers between updates; the
# table is not auto-updating.
#
# When extending this table for new models or revising prices, also
# extend tests/unit/tools/test_budget.py's coverage for the new entries.
#
# The dict is typed dict[str, tuple[float, float]] rather than
# dict[ModelString, ...] so the .get() lookup with the
# RecordApiCallInput.model field (a plain str, not Literal) typechecks
# without cast indirection. The runtime guard for unknown models lives
# in _compute_cost_usd's None-fallback path; mypy can't statically
# narrow request.model to ModelString anyway since Pydantic accepts
# arbitrary strings at the API boundary.
MODEL_PRICING: dict[str, tuple[float, float]] = {
    "claude-opus-4-7": (15.00, 75.00),
    "claude-sonnet-4-6": (3.00, 15.00),
    "claude-haiku-4-5": (1.00, 5.00),
}


# ----- Pydantic schemas ----------------------------------------------------


class RecordApiCallInput(BaseModel):
    """Input shape for ``record_api_call`` per
    ``06-mcp-tools-phase-one.md`` § ``record_api_call``.

    Per the subscription-first architecture
    (``project_subscription_first_architecture`` § B-3), the schema
    has **no ``bucket`` field** — per-bucket attribution is daemon-
    side query-time derivation from ``model`` + the polled
    ``/api/oauth/usage`` snapshot, not subprocess self-report.
    """

    model: str
    input_tokens: int = Field(ge=0)
    output_tokens: int = Field(ge=0)
    task_id: str | None = None


class RecordApiCallOutput(BaseModel):
    usage_id: str
    window_start: str


# ----- Cost computation ----------------------------------------------------


def _compute_cost_usd(
    model: str, input_tokens: int, output_tokens: int
) -> float | None:
    """Compute the API-tier-equivalent cost for a call.

    Returns ``None`` when ``model`` is not in :data:`MODEL_PRICING`.
    Null is the diagnostic for "we don't know this model's price"; the
    api_usage row is still written (with ``cost_usd`` NULL) so call
    counts and token totals remain accurate. The alternative — raising
    ValueError — would block writes, which is the wrong tradeoff
    (token data is more valuable than cost data; missing cost is a
    visible-via-NULL gap, not a silent error).

    Pricing is per-million-tokens; the formula divides by 1,000,000:
    ``cost_usd = (input_tokens * input_price + output_tokens * output_price) / 1e6``.
    """
    pricing = MODEL_PRICING.get(model)
    if pricing is None:
        return None
    input_price_per_m, output_price_per_m = pricing
    return (
        input_tokens * input_price_per_m + output_tokens * output_price_per_m
    ) / 1_000_000


# ----- Handler -------------------------------------------------------------

# IN-clause built once at module load from the dispatcher's
# _TERMINAL_STATES frozenset. Single source of truth: any new terminal
# state added in dispatcher.py automatically extends this guard.
_TERMINAL_STATES_SQL_LIST = ",".join(f"'{s}'" for s in sorted(_TERMINAL_STATES))


async def record_api_call(
    *,
    storage: Storage,
    request: RecordApiCallInput,
    caller_agent_id: str,
    caller_token_id: str,
) -> RecordApiCallOutput:
    """Insert an ``api_usage`` row and (when the caller is bound to a
    non-terminal dispatch) atomically increment the dispatch's
    ``actual_tokens``.

    Args:
        storage: Storage handle.
        request: Validated input — model, token counts, optional task_id.
        caller_agent_id: Resolved at the wrapper layer from the auth
            token's client_id. Stored on the ``api_usage`` row's
            ``agent_id`` column.
        caller_token_id: The token_id (short hex prefix) parsed from
            the auth bearer at the wrapper layer. Used to look up the
            caller's active dispatch row, mirror of
            :func:`bridge.activity.record_mcp_call`'s pattern.

    Returns:
        ``usage_id`` and ``window_start`` of the inserted row.
        ``window_start`` is the call's timestamp (ISO 8601 UTC,
        microsecond precision); per-counter window membership is
        reconstructed at query time using the polled
        ``/api/oauth/usage`` snapshot's ``resets_at`` boundaries (per
        ``project_subscription_first_architecture`` memory).

    Cost computation is per :func:`_compute_cost_usd`; ``cost_usd`` is
    NULL when the model isn't in :data:`MODEL_PRICING`.

    Dispatch increment is atomic and race-safe: the UPDATE is gated on
    ``status NOT IN (terminal)`` so a dispatch that transitions to
    terminal between the lookup and the update no-ops correctly. The
    increment uses ``COALESCE(actual_tokens, 0) + ?`` so the first
    call against a freshly-inserted dispatch (with NULL
    ``actual_tokens``) initializes the column to the call's token
    total rather than leaving it NULL.

    Audit: emits ``api.record_call`` per
    ``11-engineer-bridge.md`` § Audit log entries. The audit's target
    is the resolved dispatch_id (or NULL for OAuth callers with no
    bound dispatch). Engineer's ``get_dispatch_events`` filters by
    target = dispatch_id, so dispatch-bound calls surface in
    Engineer's iteration loop; OAuth-PM calls land in audit_log with
    target=NULL and surface only via Mike-only ``get_audit_log``
    (M6).
    """
    usage_id = uuid.uuid4().hex
    window_start = datetime.now(tz=UTC).isoformat(timespec="microseconds")
    cost_usd = _compute_cost_usd(
        request.model, request.input_tokens, request.output_tokens
    )

    if cost_usd is None:
        logger.warning(
            "record_api_call.unknown_model_pricing",
            model=request.model,
            agent_id=caller_agent_id,
            message=(
                f"model {request.model!r} not in MODEL_PRICING; api_usage row "
                "written with cost_usd=NULL. Add the model to MODEL_PRICING "
                "+ tests/unit/tools/test_budget.py if it's a legitimate "
                "Phase-1 model, or investigate the call site for typos / "
                "config drift."
            ),
        )

    await storage.execute(
        "INSERT INTO api_usage "
        "(id, agent_id, model, input_tokens, output_tokens, cost_usd, "
        "task_id, window_start) "
        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        (
            usage_id,
            caller_agent_id,
            request.model,
            request.input_tokens,
            request.output_tokens,
            cost_usd,
            request.task_id,
            window_start,
        ),
    )

    # Resolve caller's active dispatch (if any) for the audit target
    # and the actual_tokens increment. The status-NOT-IN guard mirrors
    # bridge.activity.record_mcp_call.
    dispatch_row = await storage.fetch_one(
        "SELECT id FROM dispatches "
        f"WHERE token_id = ? AND status NOT IN ({_TERMINAL_STATES_SQL_LIST})",
        (caller_token_id,),
    )
    dispatch_id: str | None = dispatch_row["id"] if dispatch_row is not None else None

    if dispatch_id is not None:
        # Atomic increment with COALESCE-around-zero per
        # project_subscription_first_architecture R-3 Reading B. The
        # status-NOT-IN guard is repeated on the UPDATE so a dispatch
        # that transitions to terminal between the SELECT and the
        # UPDATE no-ops correctly (race-safe).
        total_tokens = request.input_tokens + request.output_tokens
        await storage.execute(
            "UPDATE dispatches "
            "SET actual_tokens = COALESCE(actual_tokens, 0) + ? "
            f"WHERE id = ? AND status NOT IN ({_TERMINAL_STATES_SQL_LIST})",
            (total_tokens, dispatch_id),
        )

    await write_audit(
        storage,
        actor=caller_agent_id,
        action="api.record_call",
        target=dispatch_id,
        details={
            "usage_id": usage_id,
            "model": request.model,
            "input_tokens": request.input_tokens,
            "output_tokens": request.output_tokens,
            "cost_usd": cost_usd,
            "task_id": request.task_id,
            "dispatch_id": dispatch_id,
        },
    )

    return RecordApiCallOutput(usage_id=usage_id, window_start=window_start)


__all__ = [
    "MODEL_PRICING",
    "RecordApiCallInput",
    "RecordApiCallOutput",
    "record_api_call",
]
