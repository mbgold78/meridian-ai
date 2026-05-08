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

M5-subscription-routing extends the writer with tier-1 cost-interrogation
observability per ``BACKLOG.md`` § Observability roadmap. Four columns
on ``api_usage`` capture cohort dimensions for later baseline derivation:
``notes`` (agent self-annotation), ``workflow_id`` and ``task_type``
(cohort keys; population protocol deferred per BACKLOG), ``agent_role``
(denormalized from caller; auto-populated server-side from the
caller's resolved role so every row has the dimension from day 1).

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
from typing import Any, Literal, NamedTuple

import structlog
from pydantic import BaseModel, Field

from meridian_orchestrator.audit.logger import write_audit
from meridian_orchestrator.bridge.dispatcher import _TERMINAL_STATES
from meridian_orchestrator.bridge.oauth_usage import (
    FIELD_TO_BUCKET,
    CachedSnapshot,
)
from meridian_orchestrator.errors import PermissionError as OrchestratorPermissionError
from meridian_orchestrator.storage.datetime_utils import _coerce_naive_to_utc
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

    Tier-1 cost-interrogation observability fields (``notes``,
    ``workflow_id``, ``task_type``) are optional per BACKLOG §
    Observability roadmap. ``agent_role`` is **not** an input field —
    it's auto-populated server-side from the caller's resolved role
    so every row has the dimension from day 1 without agent burden.

    ``agent_role`` resolution timing is **call-time, not token-mint
    cached.** The wrapper's ``_resolve_caller_role_via_token`` reads
    ``access.scopes`` on every request; OAuth's
    ``_record_to_access_token`` reads ``record.role`` from the live
    keyring on every validation. A role change in the keyring would
    propagate to subsequent calls immediately rather than waiting for
    token re-issuance. Phase 1 has no role-update flow, so the
    dynamic-vs-cached distinction is moot today; the call-time
    semantics are the more correct shape.
    """

    model: str
    input_tokens: int = Field(ge=0)
    output_tokens: int = Field(ge=0)
    task_id: str | None = None
    notes: str | None = None
    workflow_id: str | None = None
    task_type: str | None = None


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


# Defensive depth limit on the parent_task_id chain walk for
# workflow_id alpha-denormalization (M5-continuous-pm-driver commit 1).
# Real production chains are 1-3 hops (PM → Engineer → Implementer);
# 100 is generous defensive-only protection against accidental cycles
# in the parent_task_id DAG. Schema doesn't enforce acyclicity (no DDL
# constraint on tasks.parent_task_id beyond the FK), so a buggy
# submit_task in the future could create a cycle. The recursive CTE
# stops at this depth and the helper returns None + emits a
# structlog WARNING. Sentinel test pins the value.
_WORKFLOW_CHAIN_MAX_DEPTH: int = 100


class ChainRootCohortKeys(NamedTuple):
    """Cohort keys resolved from the dispatch chain root.

    Both fields are None when the dispatch has no bound chain (None
    dispatch_id; NULL task_id; chain overflow). When the chain is
    walkable, ``workflow_id`` is the root task's id and ``task_type``
    is the root task's task_type column value (which may itself be
    NULL if the root task was submitted without an explicit
    task_type).

    Symmetric extension at M4d-task-management-surface commit 7
    (2026-05-07): per Q2 disposition at M4d commit 3 prep, the same
    recursive CTE that resolves workflow_id from chain root also
    resolves task_type. Server-derived wins on conflict; caller-
    supplied falls through when server-derived is None. Per CQ3
    commit 7: same conflict-logging shape (structlog WARNING; no
    audit row) for both fields.
    """

    workflow_id: str | None
    task_type: str | None


async def _resolve_chain_root_cohort_keys_from_dispatch(
    storage: Storage, dispatch_id: str | None
) -> ChainRootCohortKeys:
    """Resolve the chain-root cohort keys (workflow_id + task_type
    alpha-denormalization) by walking ``tasks.parent_task_id`` from
    the dispatch's bound task.

    Returns the chain root's ``(task_id, task_type)`` as a
    :class:`ChainRootCohortKeys` named tuple when the caller has a
    bound dispatch with a resolvable task chain. Returns
    ``ChainRootCohortKeys(None, None)`` when:

    - ``dispatch_id`` is None (caller has no bound dispatch — Mike's
      interactive OAuth PM is the canonical case)
    - the dispatch's ``task_id`` is NULL (post-M4c-supervisor-base
      dispatches may carry NULL ``task_id`` per migration 0007's
      relaxation; the supervisor-row case)
    - the chain exceeds :data:`_WORKFLOW_CHAIN_MAX_DEPTH` (cycle
      protection; emits a ``record_api_call.workflow_id_chain_overflow``
      WARNING). Real production chains are 1-3 hops; the limit is
      defensive-only.

    Uses a recursive CTE for atomic single-query resolution within
    the enclosing ``storage.transaction()`` scope. The CTE projects
    both the chain id and the task_type column; both come from the
    same chain-root row, so a single walk yields both cohort keys.

    **Symmetric extension at M4d-task-management-surface commit 7**
    per Q2 disposition (commit 3 prep, 2026-05-07): same CTE walks
    same chain; one extra column projected. Reading A (minimal
    extension) per CQ2 commit 7 disposition. Original function
    ``_resolve_workflow_id_from_dispatch`` (M5-continuous-pm-driver
    commit 1) renamed to reflect expanded scope — only one private
    caller (``record_api_call``); refactor mechanical.

    Caller is responsible for the resolution-precedence semantics
    against any caller-supplied workflow_id / task_type — see
    ``record_api_call`` handler for the Reading B disposition
    (server-derived wins when present; caller-supplied falls through
    when server-derived returns None).
    """
    if dispatch_id is None:
        return ChainRootCohortKeys(workflow_id=None, task_type=None)
    sql = """
    WITH RECURSIVE chain(id, parent_task_id, task_type, depth) AS (
        SELECT t.id, t.parent_task_id, t.task_type, 0
        FROM dispatches d
        INNER JOIN tasks t ON t.id = d.task_id
        WHERE d.id = ?
        UNION ALL
        SELECT t.id, t.parent_task_id, t.task_type, c.depth + 1
        FROM tasks t
        INNER JOIN chain c ON t.id = c.parent_task_id
        WHERE c.depth < ?
    )
    SELECT id, parent_task_id, task_type, depth FROM chain
    ORDER BY depth DESC LIMIT 1
    """
    row = await storage.fetch_one(
        sql, (dispatch_id, _WORKFLOW_CHAIN_MAX_DEPTH)
    )
    if row is None:
        return ChainRootCohortKeys(workflow_id=None, task_type=None)
    if row["parent_task_id"] is None:
        return ChainRootCohortKeys(
            workflow_id=str(row["id"]),
            task_type=row["task_type"],
        )
    # Deepest reachable row has a non-NULL parent — chain hit the
    # defensive depth limit. Almost certainly a cycle in the
    # parent_task_id DAG (real chains are 1-3 hops). Log and return
    # both fields None; cohort-key write proceeds with NULL
    # workflow_id and NULL task_type rather than failing the
    # api_usage write on a pathological chain.
    logger.warning(
        "record_api_call.workflow_id_chain_overflow",
        dispatch_id=dispatch_id,
        max_depth=_WORKFLOW_CHAIN_MAX_DEPTH,
        deepest_task_id=row["id"],
    )
    return ChainRootCohortKeys(workflow_id=None, task_type=None)


async def record_api_call(
    *,
    storage: Storage,
    request: RecordApiCallInput,
    caller_agent_id: str,
    caller_agent_role: str,
    caller_token_id: str,
) -> RecordApiCallOutput:
    """Insert an ``api_usage`` row and (when the caller is bound to a
    non-terminal dispatch) atomically increment the dispatch's
    ``actual_tokens``.

    Args:
        storage: Storage handle.
        request: Validated input — model, token counts, optional task_id,
            optional observability tier-1 fields (``notes``,
            ``workflow_id``, ``task_type``).
        caller_agent_id: Resolved at the wrapper layer from the auth
            token's client_id. Stored on the ``api_usage`` row's
            ``agent_id`` column.
        caller_agent_role: Resolved at the wrapper layer via
            :func:`server._resolve_caller_role_via_token`. Stored
            denormalized on the ``api_usage`` row's ``agent_role``
            column for cohort aggregation without a JOIN. Auto-
            populated server-side so every row has this dimension
            from day 1.
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

    **Atomicity**: dispatch SELECT + workflow_id chain-CTE + INSERT
    api_usage + conditional UPDATE dispatches + audit_log write are
    wrapped in a single ``storage.transaction()`` — partial failures
    roll back to a clean pre-call state. Without this wrapper, an
    UPDATE failure after the INSERT succeeded would leave an api_usage
    row claiming the call consumed tokens while
    ``dispatches.actual_tokens`` disagreed, silently breaking budget
    reasoning.

    **Workflow_id alpha-denormalization** (M5-continuous-pm-driver commit
    1; per F1.1 / D1.1 + Q1 Reading B disposition): the chain root of
    the caller's bound dispatch's task is resolved server-side via
    :func:`_resolve_workflow_id_from_dispatch` and stamped on
    ``api_usage.workflow_id`` at write time. **Resolution precedence**
    is Reading B: server-derived wins when present; caller-supplied
    falls through when server-derived returns None (PM-direct OAuth
    case, dispatches with NULL ``task_id``, chain-overflow defensive
    fallback). When both are present and disagree, server-derived wins
    and a ``record_api_call.workflow_id_conflict`` WARNING surfaces
    via structlog (no audit row — soft conflict at high-frequency call
    site).

    The resolution lives in the same ``storage.transaction()`` envelope
    as the dispatch SELECT and the INSERT; the chain CTE is a single
    query (no per-hop round-trip) bounded at
    :data:`_WORKFLOW_CHAIN_MAX_DEPTH` for cycle protection.

    Operator workflow_id annotations on PM-direct calls (no bound
    dispatch) preserve their semantics — Reading B's fallback path
    means caller-supplied workflow_id reaches the row when no
    server-derive is possible.
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
        # Resolve caller's active dispatch (if any) for the audit target,
        # the actual_tokens increment, and the workflow_id chain-root
        # resolution. The status-NOT-IN guard mirrors
        # bridge.activity.record_mcp_call.
        #
        # Reordered to land BEFORE the INSERT api_usage so the resolved
        # workflow_id can be stamped on the row at write time
        # (M5-continuous-pm-driver commit 1; F2.1 reordering
        # disposition). Atomicity guarantees are unchanged — the
        # surrounding storage.transaction() rolls back the INSERT and
        # audit on any failure, regardless of whether the failing step
        # is the dispatch SELECT, the workflow-chain CTE, the INSERT,
        # the conditional UPDATE, or the audit write.
        dispatch_row = await storage.fetch_one(
            "SELECT id FROM dispatches "
            f"WHERE token_id = ? AND status NOT IN ({_TERMINAL_STATES_SQL_LIST})",
            (caller_token_id,),
        )
        dispatch_id: str | None = (
            dispatch_row["id"] if dispatch_row is not None else None
        )

        # Resolve cohort keys (workflow_id + task_type) alpha-
        # denormalization: server-derived from the bound dispatch's
        # task chain root wins when present; caller-supplied falls
        # through when server-derived returns None (PM-direct OAuth
        # case + dispatches with NULL task_id). Per F1.1 / D1.1 + Q1
        # Reading B disposition (M5-continuous-pm-driver commit 1) and
        # Q2/CQ3 commit 7 symmetric extension for task_type.
        server_derived = (
            await _resolve_chain_root_cohort_keys_from_dispatch(
                storage, dispatch_id
            )
        )
        if (
            request.workflow_id is not None
            and server_derived.workflow_id is not None
            and request.workflow_id != server_derived.workflow_id
        ):
            # Soft conflict: caller annotated a workflow_id that
            # disagrees with the chain-root derivation. Server-derived
            # wins (Reading B precedence); WARNING-level structlog for
            # tier-2 observability investigation. Per Q3 Reading A
            # (commit 1) + CQ3 commit 7: no audit row (high-frequency
            # soft conflict; audit log not the right surface).
            logger.warning(
                "record_api_call.workflow_id_conflict",
                caller_supplied_workflow_id=request.workflow_id,
                server_derived_workflow_id=server_derived.workflow_id,
                caller_agent_id=caller_agent_id,
                dispatch_id=dispatch_id,
            )
        if (
            request.task_type is not None
            and server_derived.task_type is not None
            and request.task_type != server_derived.task_type
        ):
            # Symmetric task_type conflict logging per CQ3 commit 7
            # disposition. Same shape as workflow_id_conflict above.
            logger.warning(
                "record_api_call.task_type_conflict",
                caller_supplied_task_type=request.task_type,
                server_derived_task_type=server_derived.task_type,
                caller_agent_id=caller_agent_id,
                dispatch_id=dispatch_id,
            )
        resolved_workflow_id = (
            server_derived.workflow_id
            if server_derived.workflow_id is not None
            else request.workflow_id
        )
        resolved_task_type = (
            server_derived.task_type
            if server_derived.task_type is not None
            else request.task_type
        )

        await storage.execute(
            "INSERT INTO api_usage "
            "(id, agent_id, model, input_tokens, output_tokens, cost_usd, "
            "task_id, window_start, notes, workflow_id, agent_role, "
            "task_type) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                usage_id,
                caller_agent_id,
                request.model,
                request.input_tokens,
                request.output_tokens,
                cost_usd,
                request.task_id,
                window_start,
                request.notes,
                resolved_workflow_id,
                caller_agent_role,
                resolved_task_type,
            ),
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
                "notes": request.notes,
                "workflow_id": resolved_workflow_id,
                "agent_role": caller_agent_role,
                "task_type": resolved_task_type,
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
    # Naive timestamp from SQLite datetime('now') is treated as UTC;
    # tz-aware inputs preserve their offset. Shared helper extracted at
    # M5-subscription-routing commit 2.
    parsed = _coerce_naive_to_utc(parsed)
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


# ===========================================================================
# get_token_usage + get_burn_rate (M5-subscription-routing commit 2)
# ===========================================================================
#
# Per-window aggregations over api_usage joined against the polled
# /api/oauth/usage snapshot for subscription-window resolution. Reading-C
# hybrid window semantics per Q1 disposition at the M5-subscription-routing
# prep:
#
# - 'current' / 'last' → subscription_5h-relative (primary pacing counter).
#   Falls back to (now - 24h, now) when snapshot is None or stale (>120s).
#   Both 'current' and 'last' fall back to the SAME calendar_24h window;
#   'last' does NOT shift backward 24h further.
# - 'today' / 'last_24h' → calendar (today_UTC_00:00 → tomorrow_UTC_00:00 /
#   rolling 24h). Snapshot-independent; never trigger fallback.
#
# Hierarchy filtering uses the denormalized api_usage.agent_role column
# (commit 1) — no JOIN required. Subtree map is hardcoded per Q3
# disposition.


# ----- Hierarchy subtree map ----------------------------------------------

# _AGENT_ROLE_SUBTREE is hardcoded rather than derived from agents.reports_to
# chains because (a) Phase 1 has fixed 4-role hierarchy; runtime derivation
# is over-engineering for static cardinality, (b) derivation would introduce
# snapshot/import ordering complexity. Single-source-of-truth contract test
# asserts the four entries + values in tests/unit/tools/test_budget.py;
# Phase 2 dynamic hierarchies refactor to recursive-CTE at that point.
#
# Deliberate exception to the "derive don't duplicate" pattern compounded
# through M5 (PmMode → PAUSED_MODES; FIELD_TO_BUCKET → BUCKET_FIELD_MAP).
# Documented as exception, not regression.
_AGENT_ROLE_SUBTREE: dict[str, frozenset[str]] = {
    "pm": frozenset({"pm", "engineer", "implementer", "tester"}),
    "engineer": frozenset({"engineer", "implementer", "tester"}),
    "implementer": frozenset({"implementer"}),
    "tester": frozenset({"tester"}),
}


# ----- Window-resolution helper -------------------------------------------


WindowLiteral = Literal["current", "last", "today", "last_24h"]
BurnRateWindowLiteral = Literal["current", "last", "last_24h"]
WindowResolutionFallback = Literal["none", "calendar_24h"]


class _WindowResolution(BaseModel):
    """Internal: result of :func:`_resolve_window`."""

    window_start: datetime
    window_end: datetime
    fallback: WindowResolutionFallback


def _resolve_window(
    *,
    window: WindowLiteral,
    snapshot: CachedSnapshot | None,
    now: datetime,
) -> _WindowResolution:
    """Resolve the requested window literal to absolute (start, end)
    datetimes.

    **Reading-C hybrid semantics** per Q1 disposition:

    - ``current`` → subscription_5h current window. ``window_end`` =
      snapshot's ``five_hour.resets_at``; ``window_start`` =
      ``window_end - 5h``. Open window (now < window_end during an
      active cycle).
    - ``last`` → previous subscription_5h window.
      ``window_end`` = ``resets_at - 5h``; ``window_start`` =
      ``resets_at - 10h``. Closed window (now > window_end).
    - ``today`` → calendar today UTC. ``window_start`` =
      today_UTC_00:00; ``window_end`` = tomorrow_UTC_00:00.
      Snapshot-independent.
    - ``last_24h`` → rolling 24h. ``window_start`` = now - 24h;
      ``window_end`` = now. Snapshot-independent.

    **Cold-start / stale fallback** for ``current`` and ``last``:
    when snapshot is None or its ``age_seconds() > 120``, both windows
    fall back to the SAME ``(now - 24h, now)`` window — i.e., the
    same shape as ``last_24h``. **``last`` does NOT shift backward 24h
    further.** Future-Claude reading a response with
    ``window='last'`` + ``window_resolution_fallback='calendar_24h'``
    should NOT expect a window shifted into the previous 24h period;
    the fallback collapses both ``current`` and ``last`` onto the
    same surface. Behavior is deliberate — the fallback signals "we
    can't resolve subscription windows; here's a calendar substitute."

    ``today`` and ``last_24h`` never trigger fallback; they don't
    depend on the snapshot.

    Returns ``_WindowResolution`` with absolute UTC datetimes and a
    fallback marker.
    """
    if window in ("current", "last"):
        snapshot_unusable = (
            snapshot is None
            or snapshot.age_seconds(now=now) > _STALE_SNAPSHOT_THRESHOLD_SECONDS
        )
        if not snapshot_unusable:
            assert snapshot is not None  # narrowed for mypy
            five_hour = snapshot.payload.get("five_hour")
            if isinstance(five_hour, dict):
                resets_at_raw = five_hour.get("resets_at")
                if isinstance(resets_at_raw, str):
                    resets_at: datetime | None
                    try:
                        resets_at = datetime.fromisoformat(resets_at_raw)
                    except ValueError:
                        resets_at = None
                    if resets_at is not None:
                        resets_at = _coerce_naive_to_utc(resets_at)
                        cycle_seconds = _CYCLE_DURATION_SECONDS[
                            "subscription_5h"
                        ]
                        if window == "current":
                            window_end_dt = resets_at
                            window_start_dt = window_end_dt - timedelta(
                                seconds=cycle_seconds
                            )
                        else:  # "last"
                            window_end_dt = resets_at - timedelta(
                                seconds=cycle_seconds
                            )
                            window_start_dt = window_end_dt - timedelta(
                                seconds=cycle_seconds
                            )
                        return _WindowResolution(
                            window_start=window_start_dt,
                            window_end=window_end_dt,
                            fallback="none",
                        )
        # Fallback path: collapse both current and last onto calendar_24h.
        return _WindowResolution(
            window_start=now - timedelta(hours=24),
            window_end=now,
            fallback="calendar_24h",
        )

    if window == "today":
        today_start = now.replace(hour=0, minute=0, second=0, microsecond=0)
        tomorrow_start = today_start + timedelta(days=1)
        return _WindowResolution(
            window_start=today_start,
            window_end=tomorrow_start,
            fallback="none",
        )

    # last_24h
    return _WindowResolution(
        window_start=now - timedelta(hours=24),
        window_end=now,
        fallback="none",
    )


# ----- Aggregation helpers ------------------------------------------------


class TokenUsageBreakdown(BaseModel):
    """Inner-dict shape for ``GetTokenUsageOutput.by_model`` and
    ``by_agent`` per Q5 disposition. Mirrors top-level fields.
    """

    input_tokens: int
    output_tokens: int
    cost_usd: float


async def _aggregate_usage_totals(
    *,
    storage: Storage,
    window_start: datetime,
    window_end: datetime,
    subtree_roles: frozenset[str],
    agent_id_filter: str | None,
) -> tuple[int, int, float]:
    """Aggregate ``(input_tokens, output_tokens, cost_usd)`` over
    ``api_usage`` rows in the window, filtered to subtree roles and
    optionally a single agent_id.

    **Cost-aggregation semantics** per Q6 disposition: NULL
    ``cost_usd`` rows (unknown-model path from M5-record-api-call) are
    coalesced to 0.0 in aggregate. Aggregate ``cost_usd`` reflects
    observed-priced rows only; rows with unknown model price are NOT
    excluded from token aggregates but contribute zero to the cost
    aggregate. Operators investigating cost variance should query
    ``api_usage`` directly for ``WHERE cost_usd IS NULL`` to surface
    unknown-model rows. The aggregate-level zero-cost can be
    misread as no-cost; this helper deliberately preserves the
    spec'd ``float`` type rather than propagating NULL upward.

    Filter columns: ``window_start`` (the call timestamp; ISO 8601
    microseconds; lex-sortable) and ``agent_role`` (denormalized at
    M5-subscription-routing commit 1).
    """
    role_placeholders = ",".join("?" * len(subtree_roles))
    sql = (
        "SELECT "
        "COALESCE(SUM(input_tokens), 0) AS input_tokens, "
        "COALESCE(SUM(output_tokens), 0) AS output_tokens, "
        "COALESCE(SUM(cost_usd), 0.0) AS cost_usd "
        "FROM api_usage "
        "WHERE window_start >= ? AND window_start < ? "
        f"AND agent_role IN ({role_placeholders})"
    )
    params: list[Any] = [
        window_start.isoformat(timespec="microseconds"),
        window_end.isoformat(timespec="microseconds"),
    ]
    params.extend(sorted(subtree_roles))

    if agent_id_filter is not None:
        sql += " AND agent_id = ?"
        params.append(agent_id_filter)

    row = await storage.fetch_one(sql, tuple(params))
    if row is None:
        return (0, 0, 0.0)
    return (
        int(row["input_tokens"]),
        int(row["output_tokens"]),
        float(row["cost_usd"]),
    )


async def _aggregate_usage_by_model(
    *,
    storage: Storage,
    window_start: datetime,
    window_end: datetime,
    subtree_roles: frozenset[str],
    agent_id_filter: str | None,
) -> dict[str, TokenUsageBreakdown]:
    """Per-model breakdown over the same filter as
    :func:`_aggregate_usage_totals`. NULL cost coalesced per the same
    semantics."""
    role_placeholders = ",".join("?" * len(subtree_roles))
    sql = (
        "SELECT model, "
        "COALESCE(SUM(input_tokens), 0) AS input_tokens, "
        "COALESCE(SUM(output_tokens), 0) AS output_tokens, "
        "COALESCE(SUM(cost_usd), 0.0) AS cost_usd "
        "FROM api_usage "
        "WHERE window_start >= ? AND window_start < ? "
        f"AND agent_role IN ({role_placeholders})"
    )
    params: list[Any] = [
        window_start.isoformat(timespec="microseconds"),
        window_end.isoformat(timespec="microseconds"),
    ]
    params.extend(sorted(subtree_roles))

    if agent_id_filter is not None:
        sql += " AND agent_id = ?"
        params.append(agent_id_filter)

    sql += " GROUP BY model"

    rows = await storage.fetch_all(sql, tuple(params))
    return {
        str(row["model"]): TokenUsageBreakdown(
            input_tokens=int(row["input_tokens"]),
            output_tokens=int(row["output_tokens"]),
            cost_usd=float(row["cost_usd"]),
        )
        for row in rows
    }


async def _aggregate_usage_by_agent(
    *,
    storage: Storage,
    window_start: datetime,
    window_end: datetime,
    subtree_roles: frozenset[str],
    agent_id_filter: str | None,
) -> dict[str, TokenUsageBreakdown]:
    """Per-agent breakdown. Same semantics as the by_model path."""
    role_placeholders = ",".join("?" * len(subtree_roles))
    sql = (
        "SELECT agent_id, "
        "COALESCE(SUM(input_tokens), 0) AS input_tokens, "
        "COALESCE(SUM(output_tokens), 0) AS output_tokens, "
        "COALESCE(SUM(cost_usd), 0.0) AS cost_usd "
        "FROM api_usage "
        "WHERE window_start >= ? AND window_start < ? "
        f"AND agent_role IN ({role_placeholders}) "
        "AND agent_id IS NOT NULL"
    )
    params: list[Any] = [
        window_start.isoformat(timespec="microseconds"),
        window_end.isoformat(timespec="microseconds"),
    ]
    params.extend(sorted(subtree_roles))

    if agent_id_filter is not None:
        sql += " AND agent_id = ?"
        params.append(agent_id_filter)

    sql += " GROUP BY agent_id"

    rows = await storage.fetch_all(sql, tuple(params))
    return {
        str(row["agent_id"]): TokenUsageBreakdown(
            input_tokens=int(row["input_tokens"]),
            output_tokens=int(row["output_tokens"]),
            cost_usd=float(row["cost_usd"]),
        )
        for row in rows
    }


async def _lookup_agent_role(
    storage: Storage, agent_id: str
) -> str | None:
    """Return the role for ``agent_id`` from the agents table, or
    ``None`` if no such row exists.

    Used by ``get_token_usage`` to validate that a caller-supplied
    ``agent_id`` belongs to the caller's subtree before querying.
    """
    row = await storage.fetch_one(
        "SELECT role FROM agents WHERE id = ?", (agent_id,)
    )
    if row is None:
        return None
    role = row["role"]
    return str(role) if role is not None else None


# ----- get_token_usage ----------------------------------------------------


class GetTokenUsageInput(BaseModel):
    """Input shape for ``get_token_usage`` per
    ``06-mcp-tools-phase-one.md`` § ``get_token_usage``.

    ``agent_id=None`` returns subtree rollup (PM/Engineer scope) or
    own-scope rollup (Implementer/Tester). When set, the caller is
    requesting a specific agent's usage; the handler validates the
    target is within the caller's hierarchy subtree per Q4
    disposition (cross-subtree → PERMISSION_DENIED).
    """

    agent_id: str | None = None
    window: WindowLiteral = "current"


class GetTokenUsageOutput(BaseModel):
    """Output shape for ``get_token_usage``.

    ``window_resolution_fallback`` per Q1b disposition: ``'none'`` is
    the default; ``'calendar_24h'`` signals that ``current`` or
    ``last`` was requested but the OAuth snapshot was unavailable
    (None) or stale (>120s) and the resolver fell back to a rolling
    calendar 24h window. ``today`` and ``last_24h`` never trigger
    fallback.

    ``by_agent`` is None when ``agent_id`` is set; populated as a
    dict when ``agent_id=None`` (rollup case), keyed by agent_id with
    one entry per agent that has rows in the window.
    """

    input_tokens: int
    output_tokens: int
    cost_usd: float
    by_model: dict[str, TokenUsageBreakdown]
    by_agent: dict[str, TokenUsageBreakdown] | None
    window_start: str
    window_end: str
    window_resolution_fallback: WindowResolutionFallback


async def get_token_usage(
    *,
    storage: Storage,
    get_snapshot: SnapshotProvider,
    request: GetTokenUsageInput,
    caller_agent_id: str,
    caller_role: str,
) -> GetTokenUsageOutput:
    """Per-window token-usage aggregation for the caller's subtree.

    See module-level commit-2 docstring for window semantics + the
    hardcoded subtree exception. ``agent_id=None`` rolls up across
    the caller's subtree; a specific ``agent_id`` is validated
    against the subtree (cross-subtree raises ``PERMISSION_DENIED``
    per Q4 disposition; no permission-graph leakage in the message).

    **Implementer / Tester scope.** Both roles see only their own
    usage per the permission table. With ``agent_id=None`` the
    handler implicitly filters to ``caller_agent_id``; with an
    explicit ``agent_id`` it must equal ``caller_agent_id`` or the
    request is rejected.
    """
    if caller_role not in _AGENT_ROLE_SUBTREE:
        raise OrchestratorPermissionError(
            f"caller role {caller_role!r} cannot invoke this tool"
        )
    subtree_roles = _AGENT_ROLE_SUBTREE[caller_role]

    # Resolve agent_id filter against caller's hierarchy.
    agent_id_filter: str | None
    if request.agent_id is None:
        # Own-scope rollup for impl/tester (scope to caller's own
        # agent_id); PM/Engineer subtree rollup uses role filter only.
        agent_id_filter = (
            caller_agent_id
            if caller_role in ("implementer", "tester")
            else None
        )
    else:
        if caller_role in ("implementer", "tester"):
            if request.agent_id != caller_agent_id:
                raise OrchestratorPermissionError(
                    "agent_id out of subtree"
                )
        else:
            target_role = await _lookup_agent_role(storage, request.agent_id)
            if target_role is None or target_role not in subtree_roles:
                raise OrchestratorPermissionError(
                    "agent_id out of subtree"
                )
        agent_id_filter = request.agent_id

    # Resolve window.
    snapshot = get_snapshot()
    now = datetime.now(tz=UTC)
    resolution = _resolve_window(
        window=request.window, snapshot=snapshot, now=now
    )

    # Aggregate.
    input_tokens, output_tokens, cost_usd = await _aggregate_usage_totals(
        storage=storage,
        window_start=resolution.window_start,
        window_end=resolution.window_end,
        subtree_roles=subtree_roles,
        agent_id_filter=agent_id_filter,
    )
    by_model = await _aggregate_usage_by_model(
        storage=storage,
        window_start=resolution.window_start,
        window_end=resolution.window_end,
        subtree_roles=subtree_roles,
        agent_id_filter=agent_id_filter,
    )

    by_agent: dict[str, TokenUsageBreakdown] | None
    if request.agent_id is None:
        by_agent = await _aggregate_usage_by_agent(
            storage=storage,
            window_start=resolution.window_start,
            window_end=resolution.window_end,
            subtree_roles=subtree_roles,
            agent_id_filter=agent_id_filter,
        )
    else:
        by_agent = None

    return GetTokenUsageOutput(
        input_tokens=input_tokens,
        output_tokens=output_tokens,
        cost_usd=cost_usd,
        by_model=by_model,
        by_agent=by_agent,
        window_start=resolution.window_start.isoformat(timespec="microseconds"),
        window_end=resolution.window_end.isoformat(timespec="microseconds"),
        window_resolution_fallback=resolution.fallback,
    )


# ----- get_burn_rate ------------------------------------------------------


class GetBurnRateInput(BaseModel):
    """Input shape for ``get_burn_rate`` per
    ``06-mcp-tools-phase-one.md`` § ``get_burn_rate``.

    Note: ``today`` is not a burn-rate window — pacing decisions
    track subscription cycles (current / last) and rolling 24h, not
    calendar-day boundaries.
    """

    window: BurnRateWindowLiteral = "current"


class GetBurnRateOutput(BaseModel):
    """Output shape for ``get_burn_rate``.

    For closed windows (``last``, ``last_24h``) per Q7 disposition:
    ``projected_window_total`` and ``projected_window_cost`` equal
    the observed totals — there's no future to project into. Only
    the open ``current`` window has projected > observed.

    ``window_resolution_fallback`` mirrors ``get_token_usage``'s
    field per Q1b — ``'calendar_24h'`` signals snapshot-unavailable
    fallback for ``current`` / ``last``.
    """

    tokens_per_hour: float
    projected_window_total: int
    cost_per_hour: float
    projected_window_cost: float
    window_resolution_fallback: WindowResolutionFallback


async def get_burn_rate(
    *,
    storage: Storage,
    get_snapshot: SnapshotProvider,
    request: GetBurnRateInput,
    caller_role: str,
) -> GetBurnRateOutput:
    """Subscription-window burn rate for PM / Engineer / Mike.

    **Scope is global** per Q8 disposition: burn rate reflects the
    whole subscription's pace, not the caller's role-scoped slice.
    PM and Engineer share the subscription; their pacing decisions
    (e.g., Engineer's compaction-at-80%) need global utilization.
    Implementer / Tester are denied at the handler layer per the
    permission table.

    **Math** (closed vs. open windows):

    - For ``current`` (open): ``elapsed = now - window_start``;
      ``total = window_end - window_start`` (= 5h).
      ``rate = observed_so_far / elapsed_hours``;
      ``projected = observed + rate * (window_end - now) / 1h``.
    - For ``last`` and ``last_24h`` (closed): ``elapsed = total =
      window_length``; ``rate = observed / total_hours``;
      ``projected = observed`` (already final).

    Defensive guard: if elapsed_hours == 0 (subscription cycle just
    reset to its start at exactly ``now``), rate computation would
    divide by zero. Treat as zero rate; projected = observed.

    Note: the spec's permission table lists Mike as allowed; Mike
    connects as PM via OAuth (role=pm), so the role gate here is
    just ``pm`` + ``engineer``. There is no separate ``mike`` role.
    """
    _require_role(caller_role, "pm", "engineer")

    snapshot = get_snapshot()
    now = datetime.now(tz=UTC)
    resolution = _resolve_window(
        window=request.window, snapshot=snapshot, now=now
    )

    # Global scope: aggregate over all four roles, no agent_id filter.
    subtree_roles = _AGENT_ROLE_SUBTREE["pm"]
    input_tokens, output_tokens, cost_usd = await _aggregate_usage_totals(
        storage=storage,
        window_start=resolution.window_start,
        window_end=resolution.window_end,
        subtree_roles=subtree_roles,
        agent_id_filter=None,
    )
    observed_tokens = input_tokens + output_tokens

    total_window_seconds = (
        resolution.window_end - resolution.window_start
    ).total_seconds()
    total_window_hours = total_window_seconds / 3600 if total_window_seconds > 0 else 0.0

    # Elapsed-vs-total: 'current' is open (elapsed < total); 'last' and
    # 'last_24h' are closed (elapsed == total). The elapsed-clamp via min
    # handles the edge case where now > window_end (rare; closed window).
    elapsed_seconds = min(
        (now - resolution.window_start).total_seconds(),
        total_window_seconds,
    )
    elapsed_hours = elapsed_seconds / 3600 if elapsed_seconds > 0 else 0.0

    if elapsed_hours <= 0.0:
        tokens_per_hour = 0.0
        cost_per_hour = 0.0
        projected_window_total = observed_tokens
        projected_window_cost = cost_usd
    else:
        tokens_per_hour = observed_tokens / elapsed_hours
        cost_per_hour = cost_usd / elapsed_hours
        projected_window_total = int(tokens_per_hour * total_window_hours)
        projected_window_cost = cost_per_hour * total_window_hours

    return GetBurnRateOutput(
        tokens_per_hour=tokens_per_hour,
        projected_window_total=projected_window_total,
        cost_per_hour=cost_per_hour,
        projected_window_cost=projected_window_cost,
        window_resolution_fallback=resolution.fallback,
    )


# ===========================================================================
# check_budget_for_task — pure routing function (M5-subscription-routing
# commit 4)
# ===========================================================================
#
# Pure decision function over (complexity, priority, snapshot, caller_role,
# now). No DB I/O, no MCP, no auth — wrapper at commit 5 fetches the
# snapshot, resolves caller_role, and passes inputs through; this commit
# ships the routing logic separable from the wiring.
#
# **Six routing rules** per ``06-mcp-tools-phase-one.md`` § ``check_budget_
# for_task``:
#
# 1. Normal/upgrade pace — subscription_5h, role default + priority delta.
# 2. subscription_7d_opus high (>= 80%) — Opus → Sonnet for non-critical
#    (priority > 1); Opus preserved for priority == 1.
# 3. subscription_5h conserve — pause-recommended factor; recommendation
#    unchanged (caller decides to dispatch / pause).
# 4. subscription_5h rate_limited — rate-limited factor; recommendation
#    unchanged.
# 5. Saturated, ``extra_usage.is_enabled: false`` —
#    ``subscription_saturated_no_overflow`` factor; bucket stays
#    subscription_5h (Literal-restricted output; caller queues).
# 6. Saturated, ``extra_usage.is_enabled: true`` — bucket=
#    api_key_overflow; ``routed_to_api_key_overflow`` factor.
#
# **Orthogonal-dimensions architecture** per Q8 disposition: bucket and
# model resolve from independent computations; all applicable rules
# emit factors without ordering-suppression. A multi-rule contract test
# (e.g., 5h rate_limited + 7d_opus high simultaneously) locks this
# durably.
#
# **Fallback (Q3 disposition β):** snapshot unavailable (None or stale
# > 120s) → per-role default, no priority delta, no Opus-downgrade,
# bucket=subscription_5h. ``rationale_text`` explicitly names the
# fallback state.


# ----- Constants ----------------------------------------------------------

# Subscription_7d_opus utilization threshold for the Opus → Sonnet
# downgrade rule. Per Q4 disposition: 80% as a single named constant,
# documented as "M5 stub — refined after operational observation."
# Future hygiene-commit revision is a single-line edit.
OPUS_DOWNGRADE_UTILIZATION_THRESHOLD: float = 0.80


# Tier hierarchy from cheapest to most-capable. The routing function
# resolves a tier index (0..2) and looks up the model name.
_TIER_HIERARCHY: tuple[str, ...] = (
    "claude-haiku-4-5",
    "claude-sonnet-4-6",
    "claude-opus-4-7",
)


# Per-role default tier index per spec § check_budget_for_task. PM
# defaults to Opus (highest); Engineer/Implementer/Tester default to
# Sonnet (middle).
_ROLE_DEFAULT_TIER: dict[str, int] = {
    "pm": 2,
    "engineer": 1,
    "implementer": 1,
    "tester": 1,
}


# Priority-driven tier delta per Q5/Q6 disposition. Priority=1 (highest)
# upgrades by one tier; priority=5 (lowest) downgrades by one tier;
# middle priorities (2, 3, 4) leave the tier at the role default.
# Bounds-clamping to [0, len-1] is the responsibility of
# :func:`_apply_priority_delta`.
#
# Documented as M5 stub — refined after operational observation. Don't
# ship a 5x5 (complexity, priority) matrix preemptively per Q5
# disposition; calibration without runtime data is a guess.
_PRIORITY_TIER_DELTA: dict[int, int] = {
    1: 1,
    2: 0,
    3: 0,
    4: 0,
    5: -1,
}


# ----- Factor naming convention -------------------------------------------
#
# **Stable structural prefix for percentage-embedded factors** (Q5 sub-
# amendment): ``<dimension>_at_<value>pct_<context>``. Examples:
#
# - ``subscription_5h_at_45pct_normal``
# - ``subscription_7d_opus_at_67pct_below_threshold``
# - ``subscription_7d_opus_at_85pct_above_threshold``
#
# The ``_at_`` separator + ``pct`` literal + numeric value pin the format
# so future tier-2 LIKE-pattern queries are tractable
# (``WHERE factor LIKE 'subscription_7d_opus_at_%pct_above_threshold'``).
# Sentinel test :func:`tests.unit.tools.test_budget.
# test_pct_factor_format_sentinel` enforces the format on every factor
# emitted by this module that matches ``*pct*``.
#
# Non-percentage numeric factors (e.g., ``snapshot_age_42s``) follow a
# different convention because the units differ; the ``s`` suffix marks
# seconds. The Q5 sub-amendment locks pct-format only.
_PCT_FACTOR_FORMAT_PATTERN: str = (
    r"^[a-z_0-9]+_at_\d+pct_[a-z_]+$"
)


# ----- Pydantic schemas ---------------------------------------------------


class CheckBudgetForTaskInput(BaseModel):
    """Input shape for ``check_budget_for_task`` per
    ``06-mcp-tools-phase-one.md`` § ``check_budget_for_task``.

    Per Q1 disposition (M5 stub), ``complexity`` is captured on the
    schema but does not influence the routing math at this milestone.
    PM's prompt heuristic folds task complexity into the priority
    assignment; the routing function consumes priority + per-role
    defaults. Complexity surfaces as a routing factor for
    observability tier-2 inspection.
    """

    complexity: int = Field(ge=1, le=5)
    priority: int = Field(ge=1, le=5)


class CheckBudgetForTaskOutput(BaseModel):
    """Output shape for ``check_budget_for_task`` per the architectural
    sub-amendment at the M5-subscription-routing prep.

    Three structural channels:

    - ``recommended_model`` / ``recommended_bucket`` — the routing
      decision (Literal-typed for caller compatibility).
    - ``routing_factors`` — list of named tags (queryable as data, not
      parsed from strings) describing the decision context. Pct-format
      factors follow the ``<dimension>_at_<value>pct_<context>``
      convention pinned at module level.
    - ``fallback_active: bool`` — flag for the snapshot-unavailable
      degraded path per Q3 disposition β. ``rationale_text`` also
      names the fallback explicitly when active.
    - ``rationale_text`` — human-readable summary for operator dashboards
      and the ``set_observability_level`` tier-2 path.

    Field name ``rationale_text`` (not ``rationale``) per Q4
    disposition: spec amendment lands in ``06-mcp-tools-phase-one.md`` §
    ``check_budget_for_task`` output schema in this same commit.
    """

    recommended_model: Literal[
        "claude-haiku-4-5", "claude-sonnet-4-6", "claude-opus-4-7"
    ]
    recommended_bucket: Literal["subscription_5h", "api_key_overflow"]
    routing_factors: list[str]
    fallback_active: bool
    rationale_text: str


# ----- Pure helper: priority-driven tier delta ----------------------------


_ClampMarker = Literal["none", "top", "bottom"]


def _apply_priority_delta(
    default_tier_idx: int, priority: int
) -> tuple[int, _ClampMarker]:
    """Apply priority delta to ``default_tier_idx``, clamped to
    ``[0, len(_TIER_HIERARCHY) - 1]``.

    Returns ``(clamped_idx, clamp_marker)`` where ``clamp_marker`` is:

    - ``'none'`` — no clamping was necessary (raw delta produced an
      in-range index).
    - ``'top'`` — raw delta would have exceeded the top index;
      result clamped to top.
    - ``'bottom'`` — raw delta would have gone below 0; result
      clamped to bottom.

    The clamp marker drives the ``tier_clamped_at_top`` /
    ``tier_clamped_at_bottom`` factors in the routing function.

    Per Q5/Q6 disposition: priority=1 on Opus stays Opus (already at
    top, ``clamp_marker='top'``); priority=5 on Haiku stays Haiku
    (already at bottom, ``clamp_marker='bottom'``).
    """
    delta = _PRIORITY_TIER_DELTA[priority]
    raw_idx = default_tier_idx + delta
    top_idx = len(_TIER_HIERARCHY) - 1
    if raw_idx > top_idx:
        return (top_idx, "top")
    if raw_idx < 0:
        return (0, "bottom")
    return (raw_idx, "none")


# ----- Malformed-snapshot detection ---------------------------------------


def _is_snapshot_fully_parseable(snapshot: CachedSnapshot) -> bool:
    """Return True when the snapshot's required counters are all
    present and parseable. False on any malformed required field.

    Required fields (driven by the routing rules):

    - ``five_hour.utilization`` (number) and ``five_hour.resets_at``
      (parseable ISO 8601 string) — needed for bucket / pace decision.
    - ``seven_day_opus.utilization`` (number) — needed for the
      Opus-downgrade rule.
    - ``extra_usage.is_enabled`` (bool) — needed for the overflow
      routing decision under saturation.

    Per the malformed-snapshot disposition (commit 4 review): ANY
    malformed required field collapses the snapshot to the fallback
    path. The routing function does not attempt per-counter graceful
    degradation; the conservative full-fallback semantics avoid the
    silent-rate_limited-miss bug class (e.g., a malformed
    ``five_hour`` would otherwise route to subscription_5h with
    unknown saturation state).

    Null-counter cases (documented Anthropic API state where a
    feature isn't enabled) also fail this check and trigger fallback
    — same treatment as contract violations. The simplification is
    deliberate; tier-2 observability that wants to distinguish nulls
    from contract violations would need a separate signal layer.
    """
    payload = snapshot.payload

    five_hour = payload.get("five_hour")
    if not isinstance(five_hour, dict):
        return False
    if not isinstance(five_hour.get("utilization"), int | float):
        return False
    resets_at_raw = five_hour.get("resets_at")
    if not isinstance(resets_at_raw, str):
        return False
    try:
        datetime.fromisoformat(resets_at_raw)
    except ValueError:
        return False

    seven_day_opus = payload.get("seven_day_opus")
    if not isinstance(seven_day_opus, dict):
        return False
    if not isinstance(seven_day_opus.get("utilization"), int | float):
        return False

    extra_usage = payload.get("extra_usage")
    if not isinstance(extra_usage, dict):
        return False
    return isinstance(extra_usage.get("is_enabled"), bool)


# ----- Pure routing function ----------------------------------------------


def _compute_routing(
    *,
    complexity: int,
    priority: int,
    snapshot: CachedSnapshot | None,
    caller_role: str,
    now: datetime,
) -> CheckBudgetForTaskOutput:
    """Compute the routing decision from inputs alone — no I/O, no
    side effects.

    See module-level commit-4 docstring for the six rules and the
    orthogonal-dimensions architecture (bucket and model resolve from
    independent computations; all applicable rules emit factors).

    **Snapshot states:**

    - ``None`` → fallback per Q3 β (per-role default; no priority
      delta; no Opus-downgrade; bucket=subscription_5h;
      ``fallback_active=True``).
    - present, age > 120s (stale) → fallback (same as None).
    - present, fresh, malformed required counter → fallback (same as
      None). Malformed snapshot payloads are treated identically to
      ``snapshot=None`` (silent fallback to per-role-defaults). Future
      poller contract violations would surface as
      ``fallback_active=True`` indistinguishable from cold-start. The
      observability loss is deliberate per the commit-4 disposition;
      tier-2 distinction would need a separate signal layer.
    - present, fresh, all counters parseable → full routing logic
      engages.

    Args:
        complexity: 1..5. Captured but not consumed in routing math
            at M5 (Q1 disposition); emitted as a factor for
            observability.
        priority: 1..5. Drives the priority-tier delta per
            :data:`_PRIORITY_TIER_DELTA`.
        snapshot: The polled OAuth snapshot or ``None`` (cold start).
        caller_role: One of ``'pm'``, ``'engineer'``, ``'implementer'``,
            ``'tester'``. Wrapper layer validates upstream; KeyError
            here surfaces a wrapper bug.
        now: Wall-clock UTC, passed explicitly per Q10 for testability.

    Returns ``CheckBudgetForTaskOutput`` with structured factors,
    fallback flag, and human-readable rationale.
    """
    factors: list[str] = []

    # Always-emitted context factors.
    factors.append(f"complexity_{complexity}")
    factors.append(f"priority_{priority}")
    factors.append(f"per_role_default_{caller_role}")

    # Snapshot age factor: surfaces freshness even when not falling back
    # so tier-2 analysis can correlate "90s-old snapshot fired normal
    # routing" vs "200s-old fired fallback."
    if snapshot is None:
        factors.append("snapshot_unavailable")
        is_fallback = True
        snapshot_age_seconds: int | None = None
    else:
        snapshot_age_seconds = snapshot.age_seconds(now=now)
        factors.append(f"snapshot_age_{snapshot_age_seconds}s")
        is_stale = snapshot_age_seconds > _STALE_SNAPSHOT_THRESHOLD_SECONDS
        # Malformed-snapshot collapse per disposition (a): any
        # required-counter parsing failure triggers the fallback path,
        # indistinguishable from cold-start. See routing docstring's
        # snapshot-states table.
        is_malformed = (not is_stale) and not _is_snapshot_fully_parseable(
            snapshot
        )
        is_fallback = is_stale or is_malformed

    default_tier_idx = _ROLE_DEFAULT_TIER[caller_role]

    # ----- Model resolution (independent dimension) -----

    if is_fallback:
        # Per-role default; no priority delta; no Opus-downgrade.
        model_tier_idx = default_tier_idx
        factors.append("fallback_per_role_defaults")
        # Priority factor still emitted even though no delta applied —
        # the input is still observed. Distinguish from delta-bearing
        # case via the explicit "no_delta" suffix.
        factors.append(f"priority_{priority}_no_delta")
    else:
        # Apply priority delta with bounds clamp.
        model_tier_idx, clamp_marker = _apply_priority_delta(
            default_tier_idx, priority
        )
        delta = _PRIORITY_TIER_DELTA[priority]
        if delta == 0:
            factors.append(f"priority_{priority}_no_delta")
        elif delta > 0:
            factors.append(f"priority_{priority}_upgrade_tier")
        else:
            factors.append(f"priority_{priority}_downgrade_tier")

        if clamp_marker == "top":
            factors.append("tier_clamped_at_top")
        elif clamp_marker == "bottom":
            factors.append("tier_clamped_at_bottom")

        # Opus-downgrade rule (rule 2). Only applies when the resolved
        # model would be Opus AND subscription_7d_opus utilization is
        # at-or-above threshold. Q7 disposition: "non-critical" =
        # priority > 1; priority == 1 preserves Opus.
        assert snapshot is not None  # narrowed
        seven_day_opus = snapshot.payload.get("seven_day_opus")
        if isinstance(seven_day_opus, dict):
            opus_util_raw = seven_day_opus.get("utilization")
            if isinstance(opus_util_raw, int | float):
                opus_util = float(opus_util_raw)
                opus_pct = int(opus_util)
                threshold_pct = int(
                    OPUS_DOWNGRADE_UTILIZATION_THRESHOLD * 100
                )
                if opus_util >= threshold_pct:
                    factors.append(
                        f"subscription_7d_opus_at_{opus_pct}pct_above_threshold"
                    )
                    if model_tier_idx == 2:  # Opus
                        if priority == 1:
                            factors.append(
                                "opus_preserved_for_critical_priority"
                            )
                        else:
                            model_tier_idx = 1  # Sonnet
                            factors.append(
                                "opus_downgraded_to_sonnet_due_to_7d_opus_high"
                            )
                else:
                    factors.append(
                        f"subscription_7d_opus_at_{opus_pct}pct_below_threshold"
                    )

    recommended_model = _TIER_HIERARCHY[model_tier_idx]

    # ----- Bucket resolution (independent dimension) -----

    bucket: Literal["subscription_5h", "api_key_overflow"] = "subscription_5h"
    pace_for_rationale: str | None = None
    five_hour_pct: int | None = None

    if not is_fallback:
        assert snapshot is not None  # narrowed
        five_hour = snapshot.payload.get("five_hour")
        if isinstance(five_hour, dict):
            util_raw = five_hour.get("utilization")
            resets_at_raw = five_hour.get("resets_at")
            if isinstance(util_raw, int | float) and isinstance(
                resets_at_raw, str
            ):
                resets_at: datetime | None
                try:
                    resets_at = datetime.fromisoformat(resets_at_raw)
                except ValueError:
                    resets_at = None
                if resets_at is not None:
                    resets_at = _coerce_naive_to_utc(resets_at)
                    util = float(util_raw)
                    five_hour_pct = int(util)
                    minutes_to_reset = max(
                        0, int((resets_at - now).total_seconds() // 60)
                    )
                    pace = _compute_pace_recommendation(util, minutes_to_reset)
                    pace_for_rationale = pace
                    factors.append(
                        f"subscription_5h_at_{five_hour_pct}pct_{pace}"
                    )

                    if pace == "conserve":
                        factors.append(
                            "subscription_5h_conserve_pause_recommended"
                        )
                    elif pace == "rate_limited":
                        factors.append("subscription_5h_rate_limited")
                        # Rule 5/6: saturated. Check overflow.
                        extra_usage = snapshot.payload.get("extra_usage")
                        is_enabled = (
                            isinstance(extra_usage, dict)
                            and extra_usage.get("is_enabled") is True
                        )
                        if is_enabled:
                            bucket = "api_key_overflow"
                            factors.append("routed_to_api_key_overflow")
                        else:
                            factors.append(
                                "subscription_saturated_no_overflow"
                            )

    # ----- Rationale composition -----

    rationale_text = _build_rationale_text(
        is_fallback=is_fallback,
        recommended_model=recommended_model,
        recommended_bucket=bucket,
        caller_role=caller_role,
        priority=priority,
        complexity=complexity,
        five_hour_pct=five_hour_pct,
        pace=pace_for_rationale,
        factors=factors,
    )

    return CheckBudgetForTaskOutput(
        recommended_model=recommended_model,  # type: ignore[arg-type]
        recommended_bucket=bucket,
        routing_factors=factors,
        fallback_active=is_fallback,
        rationale_text=rationale_text,
    )


async def check_budget_for_task(
    *,
    request: CheckBudgetForTaskInput,
    snapshot: CachedSnapshot | None,
    caller_role: str,
    now: datetime,
) -> CheckBudgetForTaskOutput:
    """Public async handler for ``check_budget_for_task`` per
    ``06-mcp-tools-phase-one.md`` § ``check_budget_for_task``.

    Thin async wrapper over the pure :func:`_compute_routing`
    decision function. Async signature matches the wrapper-pattern
    convention used by the other budget tools (record_api_call,
    get_token_usage, get_burn_rate); no I/O is performed —
    ``_compute_routing`` is a pure decision function over the
    supplied inputs (commit 4 disposition).

    **Permission gate** per the spec permission table at
    ``06-mcp-tools-phase-one.md:629`` (Mike/PM/Engineer permitted;
    Implementer/Tester denied). The Implementer/Tester denial is
    deliberate: only roles that originate work or dispatch sub-
    agents need the budget signal; Implementer/Tester run with the
    model Engineer chose at dispatch time and don't make their own
    routing decisions.

    **Observability via structlog DEBUG** (commit 5 disposition):
    high-frequency read-only routing decisions don't warrant audit
    log writes (spec: "Not audited"); the DEBUG trace surfaces
    structured fields for tier-2 analysis when an operator turns up
    log level. The architectural sub-amendment's
    ``routing_factors`` field at the API boundary is the always-on
    structured signal.
    """
    _require_role(caller_role, "pm", "engineer")
    output = _compute_routing(
        complexity=request.complexity,
        priority=request.priority,
        snapshot=snapshot,
        caller_role=caller_role,
        now=now,
    )
    logger.debug(
        "check_budget_for_task.decision",
        complexity=request.complexity,
        priority=request.priority,
        caller_role=caller_role,
        recommended_model=output.recommended_model,
        recommended_bucket=output.recommended_bucket,
        fallback_active=output.fallback_active,
        routing_factors=output.routing_factors,
    )
    return output


def _build_rationale_text(
    *,
    is_fallback: bool,
    recommended_model: str,
    recommended_bucket: str,
    caller_role: str,
    priority: int,
    complexity: int,
    five_hour_pct: int | None,
    pace: str | None,
    factors: list[str],
) -> str:
    """Compose a human-readable summary from the routing decision +
    observed factors.

    The text explicitly names the fallback state when active per the
    Q3 sub-amendment ("rationale_text explicitly names fallback status
    when active") so operators reading the response cold can detect
    the degraded path without parsing factor strings.
    """
    parts: list[str] = []

    if is_fallback:
        parts.append(
            "Snapshot unavailable; routing on per-role defaults "
            f"({caller_role}={recommended_model}). Budget-aware "
            "adjustments will resume when snapshot recovers."
        )
        return " ".join(parts)

    parts.append(
        f"Routed to {recommended_bucket} with {recommended_model} "
        f"(role={caller_role}, priority={priority}, "
        f"complexity={complexity})."
    )

    if "opus_downgraded_to_sonnet_due_to_7d_opus_high" in factors:
        parts.append(
            "Opus downgraded to Sonnet for non-critical priority "
            "(subscription_7d_opus above threshold)."
        )
    elif "opus_preserved_for_critical_priority" in factors:
        parts.append(
            "Opus preserved for priority=1 task despite "
            "subscription_7d_opus above threshold."
        )

    if "subscription_5h_conserve_pause_recommended" in factors:
        parts.append(
            "subscription_5h in conserve pace; caller should consider "
            "pausing new high-token dispatches."
        )
    elif "routed_to_api_key_overflow" in factors:
        parts.append(
            "subscription_5h saturated and overflow enabled; per-call "
            "cost surfaces to operator."
        )
    elif "subscription_saturated_no_overflow" in factors:
        parts.append(
            "subscription_5h saturated and overflow disabled; caller "
            "should queue and surface to operator."
        )
    elif pace is not None and five_hour_pct is not None:
        parts.append(
            f"subscription_5h at {five_hour_pct}% ({pace} pace)."
        )

    return " ".join(parts)


__all__ = [
    "BUCKET_FIELD_MAP",
    "MODEL_PRICING",
    "OPUS_DOWNGRADE_UTILIZATION_THRESHOLD",
    "BurnRateWindowLiteral",
    "CheckBudgetForTaskInput",
    "CheckBudgetForTaskOutput",
    "GetBurnRateInput",
    "GetBurnRateOutput",
    "GetTokenUsageInput",
    "GetTokenUsageOutput",
    "GetWindowStateInput",
    "GetWindowStateOutput",
    "PaceRecommendation",
    "RecordApiCallInput",
    "RecordApiCallOutput",
    "SnapshotProvider",
    "TokenUsageBreakdown",
    "WindowLiteral",
    "WindowResolutionFallback",
    "check_budget_for_task",
    "get_burn_rate",
    "get_token_usage",
    "get_window_state",
    "record_api_call",
]
