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

from meridian_orchestrator.audit.logger import write_audit
from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.bridge.dispatcher import (
    BridgeDispatcher,
    DispatchOutcome,
    DispatchRole,
)
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
    request: DispatchImplementerInput,
    caller_agent_id: str,
    caller_role: str,
    implementer_agent_id: str,
) -> DispatchImplementerOutput:
    """Engineer-only. Run an Implementer dispatch through to a terminal state.

    ``implementer_agent_id`` is the registry-resolved Implementer agent UUID
    used to mint the per-dispatch token. The dispatcher's lifecycle owns the
    full flow — this handler is a thin wrapper that resolves permissions and
    threads inputs into :meth:`BridgeDispatcher.dispatch`.

    Returns the terminal-state DispatchOutcome flattened into the public
    output schema. Tester-specific fields (``test_results``) are not exposed
    on the implementer output.
    """
    _require_role(caller_role, "engineer")
    if not request.task_id:
        raise OrchestratorValidationError("task_id must be non-empty")

    from pathlib import Path

    outcome = await dispatcher.dispatch(
        task_id=request.task_id,
        role="implementer",
        role_agent_id=implementer_agent_id,
        spec_path=Path(request.spec_path),
        timeout_override_seconds=request.timeout_override_seconds,
        keep_worktree_on_failure=request.keep_worktree_on_failure,
        engineer_decisions=[d.model_dump() for d in request.engineer_decisions],
        actor=caller_agent_id,
        requested_working_directory=request.working_directory,
    )
    return _outcome_to_implementer_output(outcome)


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


# Re-exports for the dispatchable role union and outcome shape, since the
# server-side wiring composes these with the bridge dispatcher and tests
# import them from this module for ergonomics.
__all__ = [
    "CompleteDispatchInput",
    "CompleteDispatchOutput",
    "DispatchImplementerInput",
    "DispatchImplementerOutput",
    "DispatchRole",
    "EngineerDecision",
    "GetDispatchResultInput",
    "GetDispatchResultOutput",
    "GetDispatchStatusInput",
    "GetDispatchStatusOutput",
    "complete_dispatch",
    "dispatch_implementer",
    "get_dispatch_result",
    "get_dispatch_status",
]
