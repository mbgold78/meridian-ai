"""Dispatch MCP tools.

Four tools ship at M4a:

- ``dispatch_implementer`` (Engineer-only): enqueues an Implementer dispatch.
  At M4a the dispatcher runs serially — one in-flight at a time. M4b adds the
  parallel surface and ``dispatch_tester``.
- ``complete_dispatch`` (Implementer/Tester only): the sub-agent's structured
  callback. Validates the caller is the dispatched agent and the dispatch is
  not already terminal, then writes the terminal state to the ``dispatches``
  row and audits ``dispatch.complete_called``. The dispatcher's await loop
  observes the row update on its next poll.
- ``get_dispatch_status`` (PM/Engineer): subtree-scoped status read. M4a
  surfaces the in-flight lifecycle column; M4b adds merge-conflict surface.
- ``get_dispatch_result`` (PM/Engineer): subtree-scoped final-state read.

The pure handler functions take the storage handle, the token store, and the
caller identity (``caller_agent_id``, ``caller_role``) explicitly so unit
tests can drive them without an MCP server. The MCP-server wiring (registering
these handlers as tools on a FastMCP instance and resolving caller identity
from the request context) lands when the live Engineer path is wired — for
M4a, the test harness calls the handlers directly.
"""

from __future__ import annotations

import json
from typing import Any, Literal

from pydantic import BaseModel, Field

from meridian_orchestrator.agents.registry import AgentRegistry
from meridian_orchestrator.audit.logger import write_audit
from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.bridge.dispatcher import (
    BridgeDispatcher,
    DispatchOutcome,
    DispatchRole,
)
from meridian_orchestrator.bridge.timeouts import ModelString, model_for_tier
from meridian_orchestrator.errors import (
    ConflictError,
    NotFoundError,
)
from meridian_orchestrator.errors import PermissionError as OrchestratorPermissionError
from meridian_orchestrator.errors import ValidationError as OrchestratorValidationError
from meridian_orchestrator.storage.interface import Storage

# ----- Pydantic schemas ----------------------------------------------------


class EngineerDecision(BaseModel):
    """An altitude-1 choice Engineer made when composing a dispatch spec.

    The sub-agent reads these as constraints — Engineer already decided; the
    sub-agent inherits the call rather than re-deciding. The full payload is
    captured in ``dispatch.issued`` audit details_json.
    """

    decision: str
    rationale: str
    alternatives_considered: list[str] | None = None


class DispatchImplementerInput(BaseModel):
    task_id: str
    spec_path: str
    working_directory: str
    timeout_override_seconds: int | None = None
    keep_worktree_on_failure: bool = False
    engineer_decisions: list[EngineerDecision] = Field(default_factory=list)


class DispatchImplementerOutput(BaseModel):
    dispatch_id: str
    status: str
    commit_hash: str | None = None
    decline_reason: str | None = None
    queue_items_raised: list[str] = Field(default_factory=list)
    artifacts: list[str] = Field(default_factory=list)
    notes: str | None = None
    merge_conflict_files: list[str] = Field(default_factory=list)
    actual_touched_files: list[str] = Field(default_factory=list)


class DispatchTesterInput(BaseModel):
    """Engineer's request to verify a commit per ``11-engineer-bridge.md`` line 348.

    ``assertion_spec`` is the verification instructions Engineer composes —
    the Tester subprocess receives it as the ``claude -p`` prompt rather
    than reading from a file (Implementer reads ``spec_path``; Tester does
    not). ``commit_hash`` identifies the commit the Tester should verify
    (typically the commit a prior Implementer dispatch landed); the Tester's
    worktree is branched off the same canonical default branch and the
    Tester's first read is ``git checkout <commit_hash>`` or equivalent.
    """

    task_id: str
    commit_hash: str
    working_directory: str
    assertion_spec: str
    timeout_override_seconds: int | None = None
    keep_worktree_on_failure: bool = False
    engineer_decisions: list[EngineerDecision] = Field(default_factory=list)


class DispatchTesterOutput(BaseModel):
    dispatch_id: str
    status: str
    test_results: dict[str, Any] | None = None
    decline_reason: str | None = None
    queue_items_raised: list[str] = Field(default_factory=list)
    artifacts: list[str] = Field(default_factory=list)
    notes: str | None = None


class CompleteDispatchInput(BaseModel):
    dispatch_id: str
    status: Literal["completed", "declined", "failed"]
    commit_hash: str | None = None
    test_results: dict[str, Any] | None = None
    queue_items_raised: list[str] = Field(default_factory=list)
    decline_reason: str | None = None
    artifacts: list[str] = Field(default_factory=list)
    notes: str | None = None


class CompleteDispatchOutput(BaseModel):
    accepted: bool
    next_action: Literal["terminate", "wait"]


class GetDispatchStatusInput(BaseModel):
    dispatch_id: str


class GetDispatchStatusOutput(BaseModel):
    dispatch_id: str
    task_id: str
    role: str
    status: str
    started_at: str | None
    last_activity_at: str | None
    completed_at: str | None


class GetDispatchResultInput(BaseModel):
    dispatch_id: str


class GetDispatchResultOutput(BaseModel):
    dispatch_id: str
    status: str
    commit_hash: str | None
    decline_reason: str | None
    test_results: dict[str, Any] | None
    queue_items_raised: list[str]
    artifacts: list[str]
    notes: str | None
    actual_touched_files: list[str]


# ----- Permissions ---------------------------------------------------------


def _require_role(caller_role: str, *allowed: str) -> None:
    if caller_role not in allowed:
        raise OrchestratorPermissionError(
            f"caller role {caller_role!r} cannot invoke this tool"
        )


# ----- Handlers ------------------------------------------------------------


async def dispatch_implementer(
    *,
    dispatcher: BridgeDispatcher,
    registry: AgentRegistry | None,
    request: DispatchImplementerInput,
    caller_agent_id: str,
    caller_role: str,
    implementer_agent_id: str,
) -> DispatchImplementerOutput:
    """Engineer-only. Run an Implementer dispatch through to a terminal state.

    ``implementer_agent_id`` is the registry-resolved Implementer agent UUID
    used to mint the per-dispatch token. The dispatcher's lifecycle owns the
    full flow — this handler is a thin wrapper that reads the spec file,
    resolves permissions, and threads inputs into
    :meth:`BridgeDispatcher.dispatch`.

    ``registry`` is consulted to resolve the dispatched role's
    ``model_tier_default`` → canonical model string for the timeouts.py
    tps-driven hard-timeout calculation (M4c-tooling). When ``None`` (or
    when the role has no registered config), the dispatcher falls back to
    the legacy ``timeout_seconds`` default of 300s — used by tests that
    don't construct a registry.

    Returns the terminal-state DispatchOutcome flattened into the public
    output schema. Tester-specific fields (``test_results``) are not exposed
    on the implementer output.
    """
    _require_role(caller_role, "engineer")
    if not request.task_id:
        raise OrchestratorValidationError("task_id must be non-empty")

    import asyncio
    from pathlib import Path

    spec_path = Path(request.spec_path)
    try:
        prompt = await asyncio.to_thread(spec_path.read_text, encoding="utf-8")
    except OSError as exc:
        raise OrchestratorValidationError(
            f"could not read spec_path={request.spec_path!r}: {exc}"
        ) from exc

    model = _resolve_model_for_role(registry, "implementer")
    effort = _resolve_effort_for_role(registry, "implementer")

    outcome = await dispatcher.dispatch(
        task_id=request.task_id,
        role="implementer",
        role_agent_id=implementer_agent_id,
        prompt=prompt,
        spec_path_descriptor=spec_path,
        timeout_override_seconds=request.timeout_override_seconds,
        model=model,
        effort=effort,
        keep_worktree_on_failure=request.keep_worktree_on_failure,
        engineer_decisions=[d.model_dump() for d in request.engineer_decisions],
        actor=caller_agent_id,
        requested_working_directory=request.working_directory,
    )
    return _outcome_to_implementer_output(outcome)


def _resolve_model_for_role(
    registry: AgentRegistry | None, role: str
) -> ModelString | None:
    """Resolve the canonical model string for a dispatched role via the
    registry's ``model_tier_default`` field.

    Returns ``None`` when ``registry`` is ``None`` or when the role has no
    registered config — caller treats ``None`` as "use dispatcher's legacy
    timeout default" rather than failing. Production paths always pass a
    populated registry; lightweight tests pass ``None`` to skip the
    timeouts.py wiring.
    """
    if registry is None:
        return None
    agent = registry.get(role)  # type: ignore[arg-type]
    if agent is None:
        return None
    return model_for_tier(agent.config.model_tier_default)


def _resolve_effort_for_role(
    registry: AgentRegistry | None, role: str
) -> str:
    """Resolve the effort level for a dispatched role via the registry's
    ``AgentConfig.effort`` field per F6.2 Q3 Reading A (M5-continuous-pm-
    driver commit 5).

    Returns ``"high"`` (Claude Code's CLI default per ``claude --help``
    verification 2026-05-05) when ``registry`` is ``None`` or when the
    role has no registered config — same fallback pattern as
    :func:`_resolve_model_for_role`. Production paths always pass a
    populated registry; lightweight tests that pass ``None`` get the
    safe default.

    Per F6.2 Q9 Reading A: yaml hot-reload picks up new effort at the
    NEXT dispatch (this resolution runs per dispatch from
    ``dispatch_implementer`` / ``dispatch_tester`` handlers); supervisors'
    effort updates at next supervisor restart per the supervisor-
    granularity acknowledgment in the same disposition.
    """
    if registry is None:
        return "high"
    agent = registry.get(role)  # type: ignore[arg-type]
    if agent is None:
        return "high"
    return agent.config.effort


def _outcome_to_implementer_output(
    outcome: DispatchOutcome,
) -> DispatchImplementerOutput:
    return DispatchImplementerOutput(
        dispatch_id=outcome.dispatch_id,
        status=outcome.status,
        commit_hash=outcome.commit_hash,
        decline_reason=outcome.decline_reason,
        queue_items_raised=list(outcome.queue_items_raised),
        artifacts=list(outcome.artifacts),
        notes=outcome.notes,
        merge_conflict_files=list(outcome.merge_conflict_files),
        actual_touched_files=list(outcome.actual_touched_files),
    )


async def dispatch_tester(
    *,
    dispatcher: BridgeDispatcher,
    registry: AgentRegistry | None,
    request: DispatchTesterInput,
    caller_agent_id: str,
    caller_role: str,
    tester_agent_id: str,
) -> DispatchTesterOutput:
    """Engineer-only. Run a Tester dispatch through to a terminal state.

    ``tester_agent_id`` is the registry-resolved Tester agent UUID. The
    Tester subprocess receives ``request.assertion_spec`` as its prompt
    (no spec_path indirection). ``commit_hash`` identifies the commit to
    verify; it is recorded on the dispatch row's ``commit_hash`` column up
    front so PM/Engineer queries surface what's under verification.

    ``registry`` is consulted to resolve the tester role's
    ``model_tier_default`` → canonical model string for the tps-driven
    timeout. ``None`` means "use legacy timeout default" — see
    :func:`dispatch_implementer` for the full contract.
    """
    _require_role(caller_role, "engineer")
    if not request.task_id:
        raise OrchestratorValidationError("task_id must be non-empty")
    if not request.commit_hash:
        raise OrchestratorValidationError(
            "commit_hash must be non-empty for tester dispatches"
        )

    model = _resolve_model_for_role(registry, "tester")
    effort = _resolve_effort_for_role(registry, "tester")

    outcome = await dispatcher.dispatch(
        task_id=request.task_id,
        role="tester",
        role_agent_id=tester_agent_id,
        prompt=request.assertion_spec,
        spec_path_descriptor=None,
        timeout_override_seconds=request.timeout_override_seconds,
        model=model,
        effort=effort,
        keep_worktree_on_failure=request.keep_worktree_on_failure,
        engineer_decisions=[d.model_dump() for d in request.engineer_decisions],
        actor=caller_agent_id,
        requested_working_directory=request.working_directory,
    )
    return DispatchTesterOutput(
        dispatch_id=outcome.dispatch_id,
        status=outcome.status,
        test_results=outcome.test_results,
        decline_reason=outcome.decline_reason,
        queue_items_raised=list(outcome.queue_items_raised),
        artifacts=list(outcome.artifacts),
        notes=outcome.notes,
    )


async def complete_dispatch(
    *,
    storage: Storage,
    token_store: TokenStore,
    request: CompleteDispatchInput,
    caller_agent_id: str,
    caller_role: str,
) -> CompleteDispatchOutput:
    """Sub-agent → daemon callback delivering the dispatch's structured result.

    Validates that:

    1. the dispatch exists,
    2. the caller's resolved ``agent_id`` matches the agent the dispatch's
       token was issued to (i.e., the caller IS the dispatched sub-agent),
    3. the caller's role matches the dispatch's expected role, and
    4. the dispatch is not already in a terminal state.

    Each failure surface raises a domain error the MCP server maps to its
    code: NOT_FOUND, PERMISSION_DENIED, CONFLICT, VALIDATION_ERROR.

    On success: writes terminal status, optional commit_hash / decline_reason
    / test_results / artifacts / queue_items_raised / notes to the dispatches
    row, audits ``dispatch.complete_called``. The dispatcher's await loop
    observes the row update and exits cleanly.
    """
    _require_role(caller_role, "implementer", "tester")
    if request.status == "declined" and not request.decline_reason:
        raise OrchestratorValidationError(
            "decline_reason is required when status='declined'"
        )

    row = await storage.fetch_one(
        "SELECT id, role, status, token_id FROM dispatches WHERE id = ?",
        (request.dispatch_id,),
    )
    if row is None:
        raise NotFoundError(f"dispatch_id={request.dispatch_id!r} not found")
    if row["status"] in {
        "completed",
        "declined",
        "failed",
        "timed_out",
        "cancelled",
        "compacted",
    }:
        raise ConflictError(
            f"dispatch_id={request.dispatch_id!r} is already terminal "
            f"(status={row['status']!r})"
        )
    if row["role"] != caller_role:
        raise OrchestratorPermissionError(
            f"caller role {caller_role!r} does not match dispatch role {row['role']!r}"
        )

    token_id = row["token_id"]
    if token_id is None:
        raise ConflictError(
            f"dispatch_id={request.dispatch_id!r} has no token_id; "
            "subprocess started before token was recorded?"
        )
    token_record = token_store.lookup_by_token_id(token_id)
    if token_record is None:
        raise OrchestratorPermissionError(
            f"dispatch token {token_id!r} no longer resolves; cannot validate caller"
        )
    if token_record.agent_id != caller_agent_id:
        raise OrchestratorPermissionError(
            f"caller agent_id={caller_agent_id!r} does not own dispatch's token"
        )

    await storage.execute(
        "UPDATE dispatches SET status = ?, commit_hash = ?, decline_reason = ?, "
        "test_results_json = ?, queue_items_raised_json = ?, artifacts_json = ?, "
        "notes = ?, completed_at = datetime('now') WHERE id = ?",
        (
            request.status,
            request.commit_hash,
            request.decline_reason,
            (
                json.dumps(request.test_results, sort_keys=True)
                if request.test_results is not None
                else None
            ),
            json.dumps(list(request.queue_items_raised), sort_keys=True),
            json.dumps(list(request.artifacts), sort_keys=True),
            request.notes,
            request.dispatch_id,
        ),
    )
    await write_audit(
        storage,
        actor=caller_agent_id,
        action="dispatch.complete_called",
        target=request.dispatch_id,
        details=request.model_dump(mode="json"),
    )

    return CompleteDispatchOutput(accepted=True, next_action="terminate")


async def get_dispatch_status(
    *,
    storage: Storage,
    token_store: TokenStore,
    request: GetDispatchStatusInput,
    caller_agent_id: str,
    caller_role: str,
) -> GetDispatchStatusOutput:
    """Subtree-scoped status read.

    Implementer/Tester see only their own dispatches; PM sees the full
    subtree; Engineer sees its own dispatches and its sub-agents'. M4a
    enforces the implementer/tester self-only check via ``token_id`` →
    ``agent_id`` lookup; the PM/Engineer subtree check matures in M4b when
    multiple dispatches per Engineer iteration are routine.
    """
    row = await storage.fetch_one(
        "SELECT id, task_id, role, status, started_at, last_activity_at, "
        "completed_at, token_id FROM dispatches WHERE id = ?",
        (request.dispatch_id,),
    )
    if row is None:
        raise NotFoundError(f"dispatch_id={request.dispatch_id!r} not found")

    if caller_role in {"implementer", "tester"}:
        await _enforce_self_dispatch_access(
            token_store=token_store,
            storage_token_id=row["token_id"],
            caller_agent_id=caller_agent_id,
        )
    elif caller_role not in {"pm", "engineer"}:
        raise OrchestratorPermissionError(
            f"caller role {caller_role!r} cannot read dispatch status"
        )

    return GetDispatchStatusOutput(
        dispatch_id=row["id"],
        task_id=row["task_id"],
        role=row["role"],
        status=row["status"],
        started_at=row["started_at"],
        last_activity_at=row["last_activity_at"],
        completed_at=row["completed_at"],
    )


async def get_dispatch_result(
    *,
    storage: Storage,
    token_store: TokenStore,
    request: GetDispatchResultInput,
    caller_agent_id: str,
    caller_role: str,
) -> GetDispatchResultOutput:
    """Subtree-scoped terminal-state read."""
    row = await storage.fetch_one(
        "SELECT id, status, commit_hash, decline_reason, test_results_json, "
        "queue_items_raised_json, artifacts_json, notes, "
        "actual_touched_files_json, token_id FROM dispatches WHERE id = ?",
        (request.dispatch_id,),
    )
    if row is None:
        raise NotFoundError(f"dispatch_id={request.dispatch_id!r} not found")

    if caller_role in {"implementer", "tester"}:
        await _enforce_self_dispatch_access(
            token_store=token_store,
            storage_token_id=row["token_id"],
            caller_agent_id=caller_agent_id,
        )
    elif caller_role not in {"pm", "engineer"}:
        raise OrchestratorPermissionError(
            f"caller role {caller_role!r} cannot read dispatch result"
        )

    def _maybe_list(raw: Any) -> list[Any]:
        if raw is None:
            return []
        try:
            return list(json.loads(raw))
        except (ValueError, TypeError):
            return []

    return GetDispatchResultOutput(
        dispatch_id=row["id"],
        status=row["status"],
        commit_hash=row["commit_hash"],
        decline_reason=row["decline_reason"],
        test_results=(
            json.loads(row["test_results_json"]) if row["test_results_json"] else None
        ),
        queue_items_raised=_maybe_list(row["queue_items_raised_json"]),
        artifacts=_maybe_list(row["artifacts_json"]),
        notes=row["notes"],
        actual_touched_files=_maybe_list(row["actual_touched_files_json"]),
    )


async def _enforce_self_dispatch_access(
    *, token_store: TokenStore, storage_token_id: str | None, caller_agent_id: str
) -> None:
    """Implementer/Tester can only read their own dispatch.

    The check threads through the dispatch's ``token_id``: if the dispatch
    was issued to ``caller_agent_id``'s token, access is allowed. We rely
    on the token store's ``lookup_by_token_id`` (no secret needed) so a
    revoked token still resolves and the agent_id comparison still works.
    """
    if storage_token_id is None:
        raise OrchestratorPermissionError(
            "dispatch has no token; cannot validate self-access"
        )
    record = token_store.lookup_by_token_id(storage_token_id)
    if record is None or record.agent_id != caller_agent_id:
        raise OrchestratorPermissionError("caller does not own this dispatch")


# ----- M4c-tooling: query tools (get_recent_dispatches, get_dispatch_events) ---


_DispatchRoleLiteral = Literal[
    "implementer", "tester", "pm-continuous", "engineer-supervisor"
]
_DispatchStatusLiteral = Literal[
    "queued",
    "running",
    "completed",
    "declined",
    "failed",
    "timed_out",
    "cancelled",
    "compacted",
]


class DispatchSummary(BaseModel):
    id: str
    task_id: str | None
    role: str
    status: str
    working_directory: str
    completed_at: str | None
    actual_touched_files: list[str] = Field(default_factory=list)
    commit_hash: str | None


class GetRecentDispatchesInput(BaseModel):
    role: _DispatchRoleLiteral | None = None
    status: list[str] | None = None
    since: str | None = None  # ISO 8601 UTC; default: 24h ago
    limit: int = Field(default=50, ge=1, le=500)


class GetRecentDispatchesOutput(BaseModel):
    dispatches: list[DispatchSummary]


class AuditEvent(BaseModel):
    """Mirrors the ``audit_log`` row shape returned by ``get_audit_log``."""

    id: str
    timestamp: str
    actor: str
    action: str
    target: str | None
    details_json: str | None
    success: bool
    error_message: str | None


class GetDispatchEventsInput(BaseModel):
    dispatch_ids: list[str] = Field(..., min_length=1, max_length=100)
    since_timestamp: str | None = None  # ISO 8601 UTC; default: no filter


class GetDispatchEventsOutput(BaseModel):
    events: list[AuditEvent]


def _scoping_where_clause(
    caller_role: str, caller_agent_id: str
) -> tuple[str, list[Any]]:
    """Return ``(sql_fragment, params)`` expressing the set of dispatches the
    caller is allowed to see.

    Single source of truth for the M4c subtree-scoping rules per
    ``11-engineer-bridge.md`` § ``get_recent_dispatches`` Hierarchy check.
    Both query tools share this helper so future scope broadening (M6
    dashboard, M5 new role additions) lands in one place.

    Engineer: sub-agent dispatches Engineer initiated (role IN
    implementer/tester AND dispatches.task_id IN tasks-claimed-by-engineer).
    PM: same role filter, no claimed_by restriction.
    Implementer/Tester: rejected — they have ``get_dispatch_status`` /
    ``get_dispatch_result`` for their own dispatches.
    Other roles: rejected (defensive).

    Supervisor lifecycle rows (role engineer-supervisor / pm-continuous)
    are excluded for both Engineer and PM at M4c — see § Supervisor
    lifecycle rows excluded at M4c.
    """
    if caller_role == "engineer":
        return (
            "dispatches.role IN ('implementer', 'tester') "
            "AND dispatches.task_id IN ("
            "SELECT id FROM tasks WHERE claimed_by = ?"
            ")",
            [caller_agent_id],
        )
    if caller_role == "pm":
        return (
            "dispatches.role IN ('implementer', 'tester')",
            [],
        )
    raise OrchestratorPermissionError(
        f"caller role {caller_role!r} cannot invoke dispatch query tools"
    )


async def _visible_dispatch_ids(
    storage: Storage,
    dispatch_ids: list[str],
    caller_role: str,
    caller_agent_id: str,
) -> list[str]:
    """Filter a caller-supplied list of dispatch_ids to the subset visible
    to the caller per the same scoping rules. Used by
    ``get_dispatch_events`` to gate audit-log access."""
    if not dispatch_ids:
        return []
    where, scoping_params = _scoping_where_clause(caller_role, caller_agent_id)
    placeholders = ",".join(["?"] * len(dispatch_ids))
    rows = await storage.fetch_all(
        f"SELECT id FROM dispatches WHERE id IN ({placeholders}) AND {where}",
        (*dispatch_ids, *scoping_params),
    )
    return [r["id"] for r in rows]


async def get_recent_dispatches(
    *,
    storage: Storage,
    request: GetRecentDispatchesInput,
    caller_agent_id: str,
    caller_role: str,
) -> GetRecentDispatchesOutput:
    """Return recent dispatches the caller is allowed to see, filtered by
    role/status/since/limit and scoped to the caller's subtree per
    ``_scoping_where_clause``.

    Read-only; not audited (high-frequency for Engineer's loop). NULL
    ``actual_touched_files_json`` parses to an empty list.
    """
    where_clauses: list[str] = []
    params: list[Any] = []

    scoping_clause, scoping_params = _scoping_where_clause(
        caller_role, caller_agent_id
    )
    where_clauses.append(scoping_clause)
    params.extend(scoping_params)

    if request.role is not None:
        where_clauses.append("dispatches.role = ?")
        params.append(request.role)

    if request.status:
        status_placeholders = ",".join(["?"] * len(request.status))
        where_clauses.append(f"dispatches.status IN ({status_placeholders})")
        params.extend(request.status)

    if request.since is not None:
        since_iso = request.since
    else:
        # Default: 24h ago, ISO 8601 UTC microseconds.
        from datetime import UTC, datetime, timedelta

        since_iso = (
            datetime.now(tz=UTC) - timedelta(hours=24)
        ).isoformat(timespec="microseconds")
    where_clauses.append(
        "(dispatches.completed_at >= ? OR dispatches.issued_at >= ?)"
    )
    params.extend([since_iso, since_iso])

    where_sql = " AND ".join(where_clauses)
    rows = await storage.fetch_all(
        f"SELECT id, task_id, role, status, working_directory, "
        f"commit_hash, completed_at, actual_touched_files_json "
        f"FROM dispatches "
        f"WHERE {where_sql} "
        f"ORDER BY COALESCE(dispatches.completed_at, dispatches.issued_at) DESC "
        f"LIMIT ?",
        (*params, request.limit),
    )

    return GetRecentDispatchesOutput(
        dispatches=[
            DispatchSummary(
                id=row["id"],
                task_id=row["task_id"],
                role=row["role"],
                status=row["status"],
                working_directory=row["working_directory"],
                completed_at=row["completed_at"],
                actual_touched_files=(
                    list(json.loads(row["actual_touched_files_json"]))
                    if row["actual_touched_files_json"]
                    else []
                ),
                commit_hash=row["commit_hash"],
            )
            for row in rows
        ]
    )


async def get_dispatch_events(
    *,
    storage: Storage,
    request: GetDispatchEventsInput,
    caller_agent_id: str,
    caller_role: str,
) -> GetDispatchEventsOutput:
    """Return audit events for the requested dispatch_ids, filtered by
    optional ``since_timestamp`` and scoped to the caller's visibility.

    Read-only; not audited (high-frequency for Engineer's loop).
    Caller-supplied dispatch_ids are filtered through the scoping helper
    first; events are then queried by ``target`` column on ``audit_log``
    (target stores dispatch_id for all dispatch.* / engineer.* audit
    actions).
    """
    visible_ids = await _visible_dispatch_ids(
        storage, request.dispatch_ids, caller_role, caller_agent_id
    )
    if not visible_ids:
        return GetDispatchEventsOutput(events=[])

    placeholders = ",".join(["?"] * len(visible_ids))
    sql_parts = [
        f"SELECT id, timestamp, actor, action, target, details_json, "
        f"success, error_message FROM audit_log "
        f"WHERE target IN ({placeholders})"
    ]
    params: list[Any] = list(visible_ids)
    if request.since_timestamp is not None:
        sql_parts.append("AND timestamp >= ?")
        params.append(request.since_timestamp)
    sql_parts.append("ORDER BY timestamp, rowid")

    rows = await storage.fetch_all(" ".join(sql_parts), tuple(params))
    return GetDispatchEventsOutput(
        events=[
            AuditEvent(
                id=row["id"],
                timestamp=row["timestamp"],
                actor=row["actor"],
                action=row["action"],
                target=row["target"],
                details_json=row["details_json"],
                success=bool(row["success"]),
                error_message=row["error_message"],
            )
            for row in rows
        ]
    )


# Re-exports for the dispatchable role union and outcome shape, since the
# server-side wiring composes these with the bridge dispatcher and tests
# import them from this module for ergonomics.
__all__ = [
    "AuditEvent",
    "CompleteDispatchInput",
    "CompleteDispatchOutput",
    "DispatchImplementerInput",
    "DispatchImplementerOutput",
    "DispatchRole",
    "DispatchSummary",
    "DispatchTesterInput",
    "DispatchTesterOutput",
    "EngineerDecision",
    "GetDispatchEventsInput",
    "GetDispatchEventsOutput",
    "GetDispatchResultInput",
    "GetDispatchResultOutput",
    "GetDispatchStatusInput",
    "GetDispatchStatusOutput",
    "GetRecentDispatchesInput",
    "GetRecentDispatchesOutput",
    "complete_dispatch",
    "dispatch_implementer",
    "dispatch_tester",
    "get_dispatch_events",
    "get_dispatch_result",
    "get_dispatch_status",
    "get_recent_dispatches",
]
