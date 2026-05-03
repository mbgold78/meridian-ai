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

Downstream sub-milestones add the snapshot consumers in two waves:

- **M5-pm-mode-and-window-state** ships ``get_window_state`` (a thin
  pass-through over the daemon's polled ``/api/oauth/usage`` snapshot;
  per-bucket utilization queries, no aggregation against ``api_usage``
  rows).
- **M5-subscription-routing** ships ``get_token_usage``,
  ``get_burn_rate``, and ``check_budget_for_task`` (per-counter
  aggregation that joins ``api_usage`` data against the polled
  snapshot's bucket boundaries at query time).

The wave split is deliberate — ``get_window_state`` only needs the
poller's cache; the aggregation tools need both the cache and the
``api_usage`` writer's data. Keeping them in separate sub-milestones
lets the simpler tool ship and start producing operational signal
before the aggregation logic lands.

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
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from typing import Any, Literal

import structlog
from pydantic import BaseModel, Field

from meridian_orchestrator.audit.logger import write_audit
from meridian_orchestrator.bridge.dispatcher import _TERMINAL_STATES
from meridian_orchestrator.bridge.oauth_usage import (
    FIELD_TO_BUCKET,
    CachedSnapshot,
)
from meridian_orchestrator.errors import PermissionError as OrchestratorPermissionError
from meridian_orchestrator.storage.interface import Storage
from meridian_orchestrator.tools.pm_mode import read_current_pm_mode

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
#
# Values are interpolated into the SQL string; values must never contain
# single quotes. Sourced from bridge.dispatcher._TERMINAL_STATES (frozenset
# of code-defined constants, never user input).
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

    **Audit-target semantics**: ``target=dispatch_id`` means the call
    landed *during* this dispatch's lifecycle, not that
    ``actual_tokens`` was incremented. The increment can no-op via the
    status-NOT-IN-terminal guard while the audit still records the
    dispatch_id (the dispatch_id resolved at SELECT time before the
    transition). Consumers reasoning about "which audits caused
    actual_tokens increments" must join against the actual_tokens
    timeline rather than infer from ``target`` alone.

    **Atomicity**: INSERT api_usage + dispatch SELECT + conditional
    UPDATE dispatches + audit_log write are wrapped in a single
    ``storage.transaction()`` — partial failures roll back to a clean
    pre-call state. Without this wrapper, an UPDATE failure after the
    INSERT succeeded would leave an api_usage row claiming the call
    consumed tokens while ``dispatches.actual_tokens`` disagreed,
    silently breaking budget reasoning.
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

    async with storage.transaction():
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
        dispatch_id: str | None = (
            dispatch_row["id"] if dispatch_row is not None else None
        )

        if dispatch_id is not None:
            # Atomic increment with COALESCE-around-zero per
            # project_subscription_first_architecture R-3 Reading B. The
            # status-NOT-IN guard is repeated on the UPDATE so a dispatch
            # that transitions to terminal between the SELECT and the
            # UPDATE no-ops correctly (race-safe). When the no-op fires,
            # the audit below still records target=dispatch_id — see the
            # audit-target-semantics note in the docstring.
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


# ===========================================================================
# get_window_state (M5-pm-mode-and-window-state commit 6)
# ===========================================================================
#
# Window-state queries over the daemon's cached /api/oauth/usage snapshot,
# joined with the most recent pm_mode_log row for Mike-presence detection.
# The handler is a thin pass-through over the cache — no per-counter
# aggregation against api_usage data; that aggregation lives in
# get_token_usage / get_burn_rate / check_budget_for_task at
# M5-subscription-routing.
#
# Q1-Q8 dispositions at the commit 6 prep:
# - Q1 (pace_recommendation table gaps): default to `normal` for unmapped
#   ranges; spec amendment in 02-architecture.md.
# - Q2 (rate_limited): utilization >= 100 (inclusive boundary).
# - Q3 (cold-start): snapshot_age_seconds is None on cold-start; output
#   schema relaxed to int | None.
# - Q4 (bucket-null): expose via null_counters; do not fall back at this
#   layer.
# - Q5 (api_key_overflow): defer to M5-flag-flips; return unknown placeholder.
# - Q6 (agent_id parameter): silently ignored in Phase 1 (forward-compat
#   hook); documented in handler docstring.
# - Q7 (poller injection): closure-captured Callable[[], CachedSnapshot|None].
# - Q8 (current_pm_mode lookup): tools.pm_mode.read_current_pm_mode helper.


# Inverse of bridge.oauth_usage.FIELD_TO_BUCKET, derived at module
# load. Single source of truth: any change to FIELD_TO_BUCKET auto-
# propagates here. Bidirectional consistency is durably enforced by
# tests/unit/tools/test_get_window_state.py
# ::test_bucket_field_map_is_inverse_of_field_to_bucket.
BUCKET_FIELD_MAP: dict[str, str] = {
    bucket: field for field, bucket in FIELD_TO_BUCKET.items()
}


# Per-bucket cycle durations in seconds. The window_start is computed
# as resets_at - cycle_duration. api_key_overflow is deliberately
# absent — its cycle is "monthly" but the spec doesn't define the
# boundary (calendar month vs rolling 30 days vs billing-cycle
# anniversary). Per Q5 disposition, api_key_overflow returns the
# unknown placeholder until M5-flag-flips resolves the boundary
# question. BACKLOG entry tracks the specific question.
_CYCLE_DURATION_SECONDS: dict[str, int] = {
    "subscription_5h": 5 * 3600,
    "subscription_7d": 7 * 86400,
    "subscription_7d_opus": 7 * 86400,
    "subscription_7d_sonnet": 7 * 86400,
}


# Pace-recommendation thresholds. The boundary semantics (inclusive
# vs exclusive) are deliberate; matching contract tests durably
# enforce each.
_RATE_LIMITED_UTILIZATION_THRESHOLD: float = 100.0   # inclusive: >= 100
_CONSERVE_REMAINING_THRESHOLD: float = 20.0          # exclusive: < 20
_UPGRADE_REMAINING_THRESHOLD: float = 50.0           # exclusive: > 50
_UPGRADE_MINUTES_TO_RESET_THRESHOLD: int = 60        # exclusive: < 60

# Snapshot-age threshold for "force unknown" per the spec (120s) and
# project_subscription_first_architecture memory § B-4.
_STALE_SNAPSHOT_THRESHOLD_SECONDS: int = 120


PaceRecommendation = Literal[
    "normal", "upgrade", "conserve", "rate_limited", "unknown"
]


SnapshotProvider = Callable[[], CachedSnapshot | None]


# ----- Pydantic schemas ----------------------------------------------------


class GetWindowStateInput(BaseModel):
    """Input shape for ``get_window_state`` per
    ``06-mcp-tools-phase-one.md`` § ``get_window_state``.

    ``agent_id`` is a forward-compat hook — Phase 1 has a single
    OAuth subscription so the field is silently ignored; all callers
    see the same global state regardless of value. Per Q6 disposition.
    """

    agent_id: str | None = None
    bucket: Literal[
        "subscription_5h",
        "subscription_7d",
        "subscription_7d_opus",
        "subscription_7d_sonnet",
        "api_key_overflow",
    ] = "subscription_5h"


class GetWindowStateOutput(BaseModel):
    """Output shape for ``get_window_state``.

    ``snapshot_age_seconds: int | None`` is the deliberate spec
    relaxation per Q3 disposition — three distinct states with three
    distinct values:

    - ``None`` → no snapshot exists (cold-start)
    - ``0`` → snapshot just polled
    - positive int → snapshot is N seconds old

    Other numeric fields (``utilization``, ``minutes_to_reset``)
    stay strictly typed; cold-start surfaces via the
    ``pace_recommendation='unknown'`` + ``snapshot_age_seconds=None``
    combination, with ``window_start`` / ``window_end`` returning
    empty strings. Callers reading ``pace_recommendation`` first
    know to ignore the other fields when the value is ``unknown``.
    """

    bucket: str
    window_start: str
    window_end: str
    utilization: float
    minutes_to_reset: int
    pace_recommendation: PaceRecommendation
    snapshot_age_seconds: int | None
    null_counters: list[str]
    current_pm_mode: str | None
    most_recent_pm_mode_log_timestamp: str | None


# ----- Permissions ---------------------------------------------------------


def _require_role(caller_role: str, *allowed: str) -> None:
    """Mirror of :func:`tools.dispatch._require_role`. Module-local
    per the established pattern in ``tools/supervisor.py`` and
    ``tools/pm_mode.py``."""
    if caller_role not in allowed:
        raise OrchestratorPermissionError(
            f"caller role {caller_role!r} cannot invoke this tool"
        )


# ----- Pace-recommendation pure function ----------------------------------


def _compute_pace_recommendation(
    utilization: float, minutes_to_reset: int
) -> PaceRecommendation:
    """Compute ``pace_recommendation`` from utilization + minutes-to-reset.

    Precedence (first matching rule wins) per ``02-architecture.md``
    § Window-aware burn (per bucket), as amended by M5-pm-mode-and-
    window-state commit 6 prep Q1 disposition:

    1. ``utilization >= 100`` → ``rate_limited`` (inclusive boundary;
       ``utilization == 100.0`` exactly fires this rule per Q2 sub-
       question).
    2. ``remaining < 20%`` → ``conserve``.
    3. ``remaining > 50%`` AND ``minutes_to_reset < 60`` → ``upgrade``.
    4. Otherwise → ``normal`` (gap-filling case for the spec table's
       previously-unmapped ranges).

    Note: this function does NOT return ``unknown``. Callers handle
    cold-start / stale / null-bucket cases by short-circuiting before
    invoking this function and constructing the unknown placeholder
    output directly.
    """
    if utilization >= _RATE_LIMITED_UTILIZATION_THRESHOLD:
        return "rate_limited"
    remaining = 100.0 - utilization
    if remaining < _CONSERVE_REMAINING_THRESHOLD:
        return "conserve"
    if (
        remaining > _UPGRADE_REMAINING_THRESHOLD
        and minutes_to_reset < _UPGRADE_MINUTES_TO_RESET_THRESHOLD
    ):
        return "upgrade"
    return "normal"


# ----- Helper: build the unknown placeholder ------------------------------


async def _build_unknown_output(
    *,
    storage: Storage,
    bucket: str,
    snapshot_age_seconds: int | None,
    null_counters: list[str],
) -> GetWindowStateOutput:
    """Build the unknown-placeholder output for cold-start, stale,
    null-bucket, and api_key_overflow cases.

    PM-mode fields are always populated from pm_mode_log regardless of
    the snapshot state — Mike-presence detection is independent of the
    OAuth poller's cache.
    """
    current_mode = await read_current_pm_mode(storage)
    most_recent_ts = await _read_most_recent_pm_mode_log_timestamp(storage)
    return GetWindowStateOutput(
        bucket=bucket,
        window_start="",
        window_end="",
        utilization=0.0,
        minutes_to_reset=0,
        pace_recommendation="unknown",
        snapshot_age_seconds=snapshot_age_seconds,
        null_counters=null_counters,
        current_pm_mode=current_mode,
        most_recent_pm_mode_log_timestamp=most_recent_ts,
    )


async def _read_most_recent_pm_mode_log_timestamp(
    storage: Storage,
) -> str | None:
    """Return the timestamp of the most recent ``pm_mode_log`` row in
    ISO 8601 UTC format, or ``None`` if the table is empty.

    pm_mode_log.timestamp is stored as SQLite ``datetime('now')``
    format ('YYYY-MM-DD HH:MM:SS', naive UTC). This helper converts to
    ISO 8601 with explicit ``+00:00`` offset for output consistency
    with other timestamp fields in the project (e.g.,
    ``api_usage.window_start`` which is written as ISO 8601 from
    Python). Format normalization happens at read time; the
    column-write path is unchanged.

    **Naive-as-UTC convention** matches the precedent at
    :meth:`bridge.supervisor_base.LongRunningSupervisor._check_and_handle_timeout`
    (see lines 726-735 there): if the parsed datetime is naive, treat
    it as UTC; if it's already tz-aware (defensive — a future writer
    might use a different format), preserve the existing offset
    rather than clobbering it. Unconditional ``.replace(tzinfo=UTC)``
    would silently corrupt tz-aware inputs by overwriting their
    offset, so this helper guards on ``tzinfo is None``.

    Uses ``ORDER BY rowid DESC LIMIT 1`` for the same-second
    determinism rationale as :func:`tools.pm_mode.read_current_pm_mode`.
    """
    row = await storage.fetch_one(
        "SELECT timestamp FROM pm_mode_log ORDER BY rowid DESC LIMIT 1"
    )
    if row is None:
        return None
    raw = row["timestamp"]
    if not isinstance(raw, str):
        return None
    try:
        parsed = datetime.fromisoformat(raw)
    except ValueError:
        # Unparseable timestamp; defensive degrade.
        return None
    if parsed.tzinfo is None:
        # Naive timestamp from SQLite datetime('now'); treat as UTC.
        parsed = parsed.replace(tzinfo=UTC)
    return parsed.isoformat(timespec="microseconds")


# ----- Handler ------------------------------------------------------------


async def get_window_state(
    *,
    storage: Storage,
    get_snapshot: SnapshotProvider,
    request: GetWindowStateInput,
    caller_agent_id: str,
    caller_role: str,
) -> GetWindowStateOutput:
    """Per-bucket window state for PM and Engineer.

    See module docstring § ``get_window_state`` for the full design
    (Q1-Q8 dispositions).

    **Branch precedence** (first matching branch returns; later
    branches do not run). Several branches have overlapping
    preconditions in production — e.g., a request for
    ``api_key_overflow`` during cold-start, or a stale snapshot whose
    requested bucket is also null in the cached payload. The order
    here is the documented dispatch:

    1. **Cold start** (``get_snapshot()`` returns None) — wins over
       everything. No data is available; ``snapshot_age_seconds``
       returns ``None`` (the explicit cold-start signal per Q3).
       The requested bucket value is irrelevant; deferred-bucket
       semantics (api_key_overflow) and null-bucket cases don't
       apply because there's no snapshot to inspect.
    2. **Stale snapshot** (``snapshot_age_seconds > 120``) — wins
       over the bucket-shape branches below. The age value is
       surfaced (informative for operator dashboards) and
       ``null_counters`` carries the cached snapshot's null list,
       but the bucket-shape decisions don't run.
    3. **api_key_overflow deferred** (Q5) — wins over bucket-null.
       Returns the unknown placeholder with the live snapshot age;
       window semantics resolve at M5-flag-flips.
    4. **Bucket-null in snapshot** (Q4) — bucket field absent, not a
       dict, or ``utilization``/``resets_at`` is null. Returns the
       unknown placeholder; the requested bucket appears in
       ``null_counters``. Routing-layer fallback (B-1 memory) is
       the consumer's concern, not this tool's.
    5. **Happy path** — utilization + window_start/window_end +
       minutes_to_reset + pace_recommendation per
       :func:`_compute_pace_recommendation`.

    PM-mode fields (``current_pm_mode``,
    ``most_recent_pm_mode_log_timestamp``) are populated from
    ``pm_mode_log`` regardless of the snapshot state — Mike-presence
    detection is independent of the OAuth poller's cache.

    **agent_id parameter** (Q6): Phase 1 has a single OAuth
    subscription; this field is silently ignored. All callers see the
    same global state regardless of value. Field exists for forward-
    compatibility with Phase 2 multi-user. ``caller_agent_id`` is
    used for permission resolution, not data scoping.

    **Permission**: PM and Engineer per spec; Implementer/Tester
    rejected with :class:`OrchestratorPermissionError`. Single-gate
    enforcement at the handler matches the established pattern
    (dispatch tools, pm_mode tool).
    """
    _require_role(caller_role, "pm", "engineer")

    # caller_agent_id is currently unused in handler logic (Phase 1
    # silent-ignore per Q6 disposition; agent_id is a forward-compat
    # hook). Recording it in a debug log gives operators a paper trail
    # of who queried which bucket without expanding the audit-log
    # surface for a read-only tool. Also satisfies ruff ARG001 by
    # binding the value to a no-op observation.
    logger.debug(
        "get_window_state.invoked",
        caller_agent_id=caller_agent_id,
        bucket=request.bucket,
    )

    snapshot = get_snapshot()

    # Cold-start: no successful poll has landed yet.
    if snapshot is None:
        return await _build_unknown_output(
            storage=storage,
            bucket=request.bucket,
            snapshot_age_seconds=None,
            null_counters=[],
        )

    # Snapshot exists; compute its age relative to now.
    age_seconds = snapshot.age_seconds()
    null_counters = list(snapshot.null_counters)

    # Stale snapshot: force unknown. The age value is still surfaced
    # so dashboards can show "X seconds stale."
    if age_seconds > _STALE_SNAPSHOT_THRESHOLD_SECONDS:
        return await _build_unknown_output(
            storage=storage,
            bucket=request.bucket,
            snapshot_age_seconds=age_seconds,
            null_counters=null_counters,
        )

    # api_key_overflow is deferred to M5-flag-flips per Q5 disposition.
    # Return the unknown placeholder with the live snapshot age (the
    # poll itself was successful; the bucket's window semantics are
    # what's deferred, not the data).
    if request.bucket == "api_key_overflow":
        return await _build_unknown_output(
            storage=storage,
            bucket=request.bucket,
            snapshot_age_seconds=age_seconds,
            null_counters=null_counters,
        )

    # Look up the bucket's data in the payload via the inverse map.
    field_name = BUCKET_FIELD_MAP.get(request.bucket)
    if field_name is None:
        # Unreachable in production (the bucket Literal restricts
        # values to BUCKET_FIELD_MAP keys), but defensive for
        # forward-compatibility if the Literal expands without the
        # map being updated.
        return await _build_unknown_output(
            storage=storage,
            bucket=request.bucket,
            snapshot_age_seconds=age_seconds,
            null_counters=null_counters,
        )

    bucket_data: Any = snapshot.payload.get(field_name)

    # Bucket-null cases per Q4: the field is None or its utilization
    # is None. Return unknown placeholder; the bucket is already in
    # null_counters via the poller's _detect_null_counters logic.
    if bucket_data is None or not isinstance(bucket_data, dict):
        return await _build_unknown_output(
            storage=storage,
            bucket=request.bucket,
            snapshot_age_seconds=age_seconds,
            null_counters=null_counters,
        )

    utilization_raw = bucket_data.get("utilization")
    resets_at_raw = bucket_data.get("resets_at")
    if utilization_raw is None or resets_at_raw is None:
        return await _build_unknown_output(
            storage=storage,
            bucket=request.bucket,
            snapshot_age_seconds=age_seconds,
            null_counters=null_counters,
        )

    utilization = float(utilization_raw)

    # Compute window boundaries from resets_at + cycle duration.
    try:
        resets_at = datetime.fromisoformat(resets_at_raw)
    except (TypeError, ValueError):
        # Malformed timestamp — defensive degrade to unknown.
        return await _build_unknown_output(
            storage=storage,
            bucket=request.bucket,
            snapshot_age_seconds=age_seconds,
            null_counters=null_counters,
        )

    cycle_seconds = _CYCLE_DURATION_SECONDS[request.bucket]
    now = datetime.now(tz=UTC)
    window_end_dt = resets_at
    window_start_dt = window_end_dt - timedelta(seconds=cycle_seconds)
    minutes_to_reset = max(
        0, int((window_end_dt - now).total_seconds() // 60)
    )

    pace = _compute_pace_recommendation(utilization, minutes_to_reset)

    current_mode = await read_current_pm_mode(storage)
    most_recent_ts = await _read_most_recent_pm_mode_log_timestamp(storage)

    return GetWindowStateOutput(
        bucket=request.bucket,
        window_start=window_start_dt.isoformat(timespec="microseconds"),
        window_end=window_end_dt.isoformat(timespec="microseconds"),
        utilization=utilization,
        minutes_to_reset=minutes_to_reset,
        pace_recommendation=pace,
        snapshot_age_seconds=age_seconds,
        null_counters=null_counters,
        current_pm_mode=current_mode,
        most_recent_pm_mode_log_timestamp=most_recent_ts,
    )


__all__ = [
    "BUCKET_FIELD_MAP",
    "MODEL_PRICING",
    "GetWindowStateInput",
    "GetWindowStateOutput",
    "PaceRecommendation",
    "RecordApiCallInput",
    "RecordApiCallOutput",
    "SnapshotProvider",
    "get_window_state",
    "record_api_call",
]
