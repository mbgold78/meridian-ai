"""PM current-focus narration tools.

M5-continuous-pm-driver commit 2 ships ``pm_current_focus`` table +
three MCP tools (``declare_current_focus``, ``complete_current_focus``,
``get_current_focus``) per BACKLOG § Observability roadmap narration
tier 1 (M5-continuous-pm-driver scope).

A focus is one work-unit boundary PM declares for itself: an objective
("ship the X feature"), an output_type (one of five closed-enum values
per Q1 Reading A — `document`, `code_change`, `decision`, `review`,
`investigation`), and acceptance criteria (free-form text PM uses at
completion to self-validate the actual output against the declared
contract). When a focus is bound to a workflow (a top-level submitted
task chain), `bound_workflow_id` records the chain root; otherwise
NULL (Q8 Reading C — focus may be unbound for review/investigation
work that doesn't map to a dispatched task chain).

Three handlers + one Python-side helper:

- :func:`declare_current_focus` — handler for ``declare_current_focus``
  MCP tool. Auto-supersedes any prior incomplete focus in the same
  transaction (Q8 Reading A — the declare path's at-most-one
  invariant is preserved across Mike-interrupt-driven pivots).
- :func:`complete_current_focus` — handler for ``complete_current_focus``
  MCP tool. Closes the active focus with the loop-side validation
  result (F2.2 A1 disposition — the loop computes contract_validated +
  finding; this writer records the result).
- :func:`get_current_focus` — handler for ``get_current_focus`` MCP
  tool. Returns the active focus or None. Daemon-internal MCP read
  consumed by the continuous-loop driver subprocess at commits 7-8
  (BACKLOG § Observability roadmap scope clarification 2026-05-04 —
  daemon-internal MCP reads ship alongside their writers).
- :func:`read_current_focus` — Python-side helper. Used by the declare
  writer for dangling-detection and (post-commit-7) by other
  daemon-side daemon-internal Python consumers if any emerge. Mirrors
  :func:`tools.pm_mode.read_current_pm_mode`'s rowid-DESC ordering
  rationale.

**Atomicity** (Q7 Reading A): each handler wraps its full
state-changing sequence in a single ``storage.transaction()``.
declare wraps `read_dangling → optional UPDATE/audit → INSERT new /
audit declared`; complete wraps `read_active → UPDATE / audit completed
[+ audit contract_violation]`. Mirrors set_pm_mode and record_api_call
precedents.

**Audit-action namespace** (Q4 Reading A, four actions per Q8 update):

- ``pm.current_focus.declared`` — every successful declare (after any
  auto-supersede write). target=focus_id (the new focus).
- ``pm.current_focus.completed`` — every successful complete.
  target=focus_id. Fires alongside contract_violation when applicable.
- ``pm.current_focus.contract_violation`` — when contract_validated=False
  on complete. Same transaction as completed; target=focus_id.
- ``pm.current_focus.superseded`` — declare-when-prior-active path
  (Q8 Reading A). target=focus_id (the prior, superseded focus).
  Fires before declared in the same transaction.

Full per-action payloads per Q4 Reading A — audit log is the durable
timeline; full payloads make it self-contained without future
JOIN-through-pm_current_focus to reconstruct events.

**Permissions**: PM-only on all three handlers per
``06-mcp-tools-phase-one.md`` § ``declare_current_focus`` /
``complete_current_focus`` / ``get_current_focus``. Engineer's
mode-aware planning may eventually need read access; promote
PM-only → PM/Engineer at M5-flag-flips if that need surfaces.
"""

from __future__ import annotations

import uuid
from typing import Literal

from pydantic import BaseModel

from meridian_orchestrator.audit.logger import write_audit
from meridian_orchestrator.errors import PermissionError as OrchestratorPermissionError
from meridian_orchestrator.storage.interface import Storage

# Single source of truth for the PM-focus output_type enum. The
# migration's CHECK constraint on pm_current_focus.output_type and the
# Pydantic Literal here must stay in sync; the
# test_pm_current_focus_output_type_check_constraint sentinel test
# durably enforces alignment by inserting every value below into
# pm_current_focus and asserting all five pass the CHECK.
OutputType = Literal[
    "document", "code_change", "decision", "review", "investigation"
]

# Closed audit-action set for the pm.current_focus.* namespace per Q4
# Reading A + Q8 Reading A (four actions). Centralised here so the
# spec amendment in 11-engineer-bridge.md § Audit log entries and the
# audit-action sentinel test reference one source. Future-evolution
# of the namespace lands here first.
_PM_FOCUS_AUDIT_ACTIONS: dict[str, str] = {
    "declared": "pm.current_focus.declared",
    "completed": "pm.current_focus.completed",
    "contract_violation": "pm.current_focus.contract_violation",
    "superseded": "pm.current_focus.superseded",
}

# Truncation budget for `prior_objective_summary` in the superseded
# audit payload (Q4 Reading A). Soft display constraint, not a
# load-bearing decision — the full objective remains on the
# pm_current_focus row, recoverable via JOIN on target=focus_id.
#
# The budget value is approximate: any length in the [150, 500]
# range produces audit details that scan readably in narrow-column
# dashboard / log views. 200 chosen for ~30-40 words of typical
# English (two medium sentences). Empirical PM-objective length
# data isn't available at commit 2 (pm_current_focus.objective is
# net-new at this commit; pm.yaml has no objective-writing guidance;
# tasks.description and pm_mode_log.reason are free-form analogs
# without size data). Revisit if real-session data shows objectives
# systematically clustering above/below this band.
#
# Truncation is boundary-aware via :func:`_truncate_objective` —
# prefer last sentence-end (period+space) within budget, fall back
# to last whitespace, hard-truncate only on single-word overflow.
# Mid-word truncation reads worse than either boundary-aware option;
# resolved per F3.6 commit-2 Item 2 pushback (2026-05-04).
_OBJECTIVE_SUMMARY_MAX_CHARS: int = 200


# ----- Pydantic schemas ----------------------------------------------------


class PmCurrentFocus(BaseModel):
    """Full row representation. Returned by :func:`get_current_focus`
    inside a discriminated container; consumed by daemon-internal
    callers that need the complete focus state."""

    id: str
    objective: str
    output_type: OutputType
    acceptance_criteria: str
    bound_workflow_id: str | None
    declared_at: str
    completed_at: str | None
    contract_validated_bool: bool | None
    contract_finding_text: str | None


class DeclareCurrentFocusInput(BaseModel):
    objective: str
    output_type: OutputType
    acceptance_criteria: str
    bound_workflow_id: str | None = None


class DeclareCurrentFocusOutput(BaseModel):
    focus_id: str
    superseded_prior_focus_id: str | None  # set when auto-supersede fired


class CompleteCurrentFocusInput(BaseModel):
    contract_validated: bool
    finding_text: str | None = None
    actual_output_summary: str | None = None  # used for contract_violation audit


class CompleteCurrentFocusOutput(BaseModel):
    focus_id: str
    contract_validated: bool


class GetCurrentFocusOutput(BaseModel):
    current_focus: PmCurrentFocus | None  # None when no active focus


# ----- Shared lookup helper ------------------------------------------------


async def read_current_focus(storage: Storage) -> PmCurrentFocus | None:
    """Return the active ``pm_current_focus`` row (most-recent
    incomplete) as a :class:`PmCurrentFocus`, or ``None`` when no
    active focus exists.

    Q8 Reading A enforces the at-most-one-active-focus invariant —
    every declare-when-prior-active path auto-supersedes the prior in
    the same transaction. This helper trusts that invariant; if a
    future bug allows multiple incomplete rows, the rowid-DESC
    ordering deterministically returns the most recent.

    **Why ``ORDER BY rowid DESC LIMIT 1`` and not ``ORDER BY declared_at
    DESC``**: same rationale as :func:`tools.pm_mode.read_current_pm_mode`
    — declared_at has 1-second resolution from ``datetime('now')``;
    rowid breaks ties on burst-write timing. Two declares in the same
    second (a Mike-interrupt-driven race) tie on declared_at but never
    on rowid.

    Used by:

    - :func:`declare_current_focus` — dangling-detection for the
      auto-supersede path.
    - :func:`get_current_focus` — MCP-handler read path.
    - Future daemon-internal Python consumers if any emerge (commits
      7-8's loop driver consumes via MCP, not directly).
    """
    row = await storage.fetch_one(
        "SELECT id, objective, output_type, acceptance_criteria, "
        "bound_workflow_id, declared_at, completed_at, "
        "contract_validated_bool, contract_finding_text "
        "FROM pm_current_focus "
        "WHERE completed_at IS NULL "
        "ORDER BY rowid DESC LIMIT 1"
    )
    if row is None:
        return None
    contract_validated_bool: bool | None = None
    raw_validated = row["contract_validated_bool"]
    if raw_validated is not None:
        contract_validated_bool = bool(raw_validated)
    return PmCurrentFocus(
        id=row["id"],
        objective=row["objective"],
        output_type=row["output_type"],
        acceptance_criteria=row["acceptance_criteria"],
        bound_workflow_id=row["bound_workflow_id"],
        declared_at=row["declared_at"],
        completed_at=row["completed_at"],
        contract_validated_bool=contract_validated_bool,
        contract_finding_text=row["contract_finding_text"],
    )


# ----- Permissions ---------------------------------------------------------


def _require_role(caller_role: str, *allowed: str) -> None:
    """Mirror of :func:`tools.pm_mode._require_role`."""
    if caller_role not in allowed:
        raise OrchestratorPermissionError(
            f"caller role {caller_role!r} cannot invoke this tool"
        )


# ----- Helpers for handlers ------------------------------------------------


def _truncate_objective(objective: str) -> str:
    """Truncate an objective for the superseded audit's
    `prior_objective_summary` field. Full objective remains on the
    pm_current_focus row; this is the at-a-glance audit detail.

    **Boundary-aware truncation** (per F3.6 commit-2 Item 2 pushback,
    2026-05-04): when the objective exceeds the budget, prefer the
    last sentence-end (period+space) within the budget; fall back to
    the last whitespace boundary; hard-truncate only when neither
    boundary exists in the budget (single-word overflow). Mid-word
    truncation produces audit-detail entries that read materially
    worse than either boundary-aware option.

    Budget rationale lives on :data:`_OBJECTIVE_SUMMARY_MAX_CHARS`'s
    docstring above.

    Examples:

        >>> _truncate_objective("Short objective.")
        'Short objective.'
        >>> # Long objective with sentence boundary in budget:
        >>> # truncates at last period+space, appends ellipsis.
        >>> # Long objective without sentence boundary:
        >>> # falls back to last whitespace.
        >>> # Long single-word: hard-truncates with ellipsis.
    """
    if len(objective) <= _OBJECTIVE_SUMMARY_MAX_CHARS:
        return objective

    # Reserve room for the ellipsis suffix.
    budget = _OBJECTIVE_SUMMARY_MAX_CHARS - len("...")
    head = objective[:budget]

    # Prefer last sentence boundary (period followed by space) — produces
    # the cleanest at-a-glance read since the truncation point coincides
    # with a natural pause. Look for ". " (period+space) to avoid
    # truncating after periods inside abbreviations like "e.g." that
    # don't end sentences.
    last_period = head.rfind(". ")
    if last_period > 0:
        return head[: last_period + 1] + "..."

    # Fall back to last whitespace boundary — truncates between words.
    last_space = head.rfind(" ")
    if last_space > 0:
        return head[:last_space] + "..."

    # Single-word overflow (no whitespace within budget). Hard-truncate;
    # rare in practice for natural-language objectives.
    return head + "..."


# ----- Handlers ------------------------------------------------------------


async def declare_current_focus(
    *,
    storage: Storage,
    request: DeclareCurrentFocusInput,
    caller_agent_id: str,
    caller_role: str,
) -> DeclareCurrentFocusOutput:
    """PM-only handler for the ``declare_current_focus`` MCP tool.

    Atomicity envelope (Q7 Reading A): single transaction wraps the
    full sequence — read dangling → optional UPDATE prior + audit
    superseded → INSERT new + audit declared. Concurrent declare calls
    linearize through the transaction, ensuring at-most-one active
    focus is preserved against the Mike-interrupt-driven race
    described in Q8.

    Auto-supersede semantics (Q8 Reading A): when a prior incomplete
    focus exists at declare time, set its ``completed_at`` to the
    declare timestamp and ``contract_finding_text='superseded'``,
    then proceed with the new INSERT. ``contract_validated_bool``
    stays NULL on the superseded row — semantically distinct from
    "complete with validated=False" (a deliberate validation finding)
    vs "implicitly closed without validation" (the supersede shape).

    Audit shape (Q4 Reading A): full per-action payloads. Two audit
    rows on the auto-supersede path (superseded then declared); one
    audit row on the no-prior path (declared only).
    """
    _require_role(caller_role, "pm")

    new_focus_id = uuid.uuid4().hex
    superseded_prior_focus_id: str | None = None

    async with storage.transaction():
        # Dangling-detection: read the active focus (if any) inside the
        # transaction so concurrent declares linearize cleanly.
        prior = await read_current_focus(storage)

        if prior is not None:
            superseded_prior_focus_id = prior.id
            # Set completed_at to the declare timestamp (datetime('now')
            # at SQL layer for consistency with other writers' default
            # patterns) and finding_text='superseded'. contract_validated_bool
            # stays NULL — supersede is not a validation outcome.
            await storage.execute(
                "UPDATE pm_current_focus "
                "SET completed_at = datetime('now'), "
                "    contract_finding_text = 'superseded' "
                "WHERE id = ?",
                (prior.id,),
            )
            # Read back the superseded_at so the audit detail matches
            # the row's persisted value (datetime('now') resolves once
            # at SQL execution; reading back avoids drift between the
            # row and the audit detail).
            superseded_row = await storage.fetch_one(
                "SELECT completed_at FROM pm_current_focus WHERE id = ?",
                (prior.id,),
            )
            superseded_at = (
                superseded_row["completed_at"]
                if superseded_row is not None
                else None
            )
            await write_audit(
                storage,
                actor=caller_agent_id,
                action=_PM_FOCUS_AUDIT_ACTIONS["superseded"],
                target=prior.id,
                details={
                    "focus_id": prior.id,
                    "superseded_at": superseded_at,
                    "superseded_by_focus_id": new_focus_id,
                    "prior_objective_summary": _truncate_objective(
                        prior.objective
                    ),
                },
            )

        # INSERT the new focus. declared_at takes the SQL default
        # datetime('now') — same convention as pm_mode_log.timestamp.
        await storage.execute(
            "INSERT INTO pm_current_focus "
            "(id, objective, output_type, acceptance_criteria, "
            "bound_workflow_id) "
            "VALUES (?, ?, ?, ?, ?)",
            (
                new_focus_id,
                request.objective,
                request.output_type,
                request.acceptance_criteria,
                request.bound_workflow_id,
            ),
        )
        # Read back declared_at for the audit detail (same drift-avoidance
        # rationale as superseded_at above).
        new_row = await storage.fetch_one(
            "SELECT declared_at FROM pm_current_focus WHERE id = ?",
            (new_focus_id,),
        )
        declared_at = (
            new_row["declared_at"] if new_row is not None else None
        )
        await write_audit(
            storage,
            actor=caller_agent_id,
            action=_PM_FOCUS_AUDIT_ACTIONS["declared"],
            target=new_focus_id,
            details={
                "objective": request.objective,
                "output_type": request.output_type,
                "acceptance_criteria": request.acceptance_criteria,
                "bound_workflow_id": request.bound_workflow_id,
                "declared_at": declared_at,
            },
        )

    return DeclareCurrentFocusOutput(
        focus_id=new_focus_id,
        superseded_prior_focus_id=superseded_prior_focus_id,
    )


async def complete_current_focus(
    *,
    storage: Storage,
    request: CompleteCurrentFocusInput,
    caller_agent_id: str,
    caller_role: str,
) -> CompleteCurrentFocusOutput:
    """PM-only handler for the ``complete_current_focus`` MCP tool.

    Implicit-current shape (Q9 Reading B): no focus_id parameter. The
    handler reads the active focus via :func:`read_current_focus` and
    completes that one. PM has at-most-one active focus per Q8
    invariant; id-disambiguation has no value.

    No-active-focus error (Q9 disposition prose): raise
    :class:`ValueError` when no active focus exists. The MCP wrapper
    surfaces this as a structured error rather than a 500. PM's loop
    is expected to declare-then-complete; calling complete without a
    prior declare is a contract violation in the loop's logic, not a
    silent no-op the way same-mode set_pm_mode is.

    Contract-validation audit (Q4 Reading A): when
    ``contract_validated=False``, emit two audit rows in the same
    transaction — ``pm.current_focus.completed`` AND
    ``pm.current_focus.contract_violation``. The completed audit
    records the closure event; the contract_violation audit records
    the finding type for tier-2 narration evolution. Both target
    focus_id; payloads diverge per Q4 spec.
    """
    _require_role(caller_role, "pm")

    async with storage.transaction():
        active = await read_current_focus(storage)
        if active is None:
            raise ValueError(
                "no active pm_current_focus to complete; PM must declare "
                "a focus before completing one"
            )

        # UPDATE the active row with completion state. SQLite stores
        # bool as INTEGER (0/1).
        validated_int = 1 if request.contract_validated else 0
        await storage.execute(
            "UPDATE pm_current_focus "
            "SET completed_at = datetime('now'), "
            "    contract_validated_bool = ?, "
            "    contract_finding_text = ? "
            "WHERE id = ?",
            (validated_int, request.finding_text, active.id),
        )
        # Read back completed_at for audit detail consistency.
        completed_row = await storage.fetch_one(
            "SELECT completed_at FROM pm_current_focus WHERE id = ?",
            (active.id,),
        )
        completed_at = (
            completed_row["completed_at"]
            if completed_row is not None
            else None
        )

        await write_audit(
            storage,
            actor=caller_agent_id,
            action=_PM_FOCUS_AUDIT_ACTIONS["completed"],
            target=active.id,
            details={
                "focus_id": active.id,
                "contract_validated": request.contract_validated,
                "finding_text": request.finding_text,
                "completed_at": completed_at,
                "declared_at_for_join": active.declared_at,
            },
        )

        if not request.contract_validated:
            # Same transaction; contract_violation audit fires alongside
            # completed when the loop's validation logic surfaced a
            # mismatch between actual_output and acceptance_criteria.
            await write_audit(
                storage,
                actor=caller_agent_id,
                action=_PM_FOCUS_AUDIT_ACTIONS["contract_violation"],
                target=active.id,
                details={
                    "focus_id": active.id,
                    "contract_validated": False,
                    "finding_text": request.finding_text,
                    "actual_output_summary": request.actual_output_summary,
                },
            )

    return CompleteCurrentFocusOutput(
        focus_id=active.id,
        contract_validated=request.contract_validated,
    )


async def get_current_focus(
    *,
    storage: Storage,
    caller_role: str,
) -> GetCurrentFocusOutput:
    """PM-only handler for the ``get_current_focus`` MCP tool.

    Daemon-internal MCP read consumed by the continuous-loop driver
    subprocess at commits 7-8 (loop runs as Claude Code subprocess;
    cannot import :func:`read_current_focus` directly). Per
    BACKLOG § Observability roadmap scope clarification 2026-05-04,
    daemon-internal MCP reads ship alongside their writers — the
    "read-side post-flag-flips" sequencing applies to operator-facing
    tier-2 surfaces, not daemon-internal subprocess reads.

    Empty-state contract (Q6 disposition prose): returns
    ``GetCurrentFocusOutput(current_focus=None)`` when no active focus
    exists. The discriminated container shape lets the loop driver
    distinguish "no focus declared yet" from "RPC failed" cleanly.

    Permission scope: PM-only. Engineer's mode-aware planning may
    eventually need read access (mirror Q4 set_pm_mode disposition
    pattern); promote at M5-flag-flips if Engineer's continuous-loop
    spec lands with that need.

    Not audited — read-only, called frequently from PM's loop. Mirrors
    get_window_state's not-audited convention.
    """
    _require_role(caller_role, "pm")
    current = await read_current_focus(storage)
    return GetCurrentFocusOutput(current_focus=current)


__all__ = [
    "CompleteCurrentFocusInput",
    "CompleteCurrentFocusOutput",
    "DeclareCurrentFocusInput",
    "DeclareCurrentFocusOutput",
    "GetCurrentFocusOutput",
    "OutputType",
    "PmCurrentFocus",
    "complete_current_focus",
    "declare_current_focus",
    "get_current_focus",
    "read_current_focus",
]
