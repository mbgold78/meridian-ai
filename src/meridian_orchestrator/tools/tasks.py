"""Task management MCP tools.

M4d-task-management-surface ships the six task-management MCP tools
that should have landed at M4 but were caught in the phase-boundary
acceptance-criteria-coverage scan as missing surface (per
``docs/conventions/00-README.md`` § 2). Sub-milestone scope per
``docs/kickoff/06-mcp-tools-phase-one.md`` § Task management
(lines 30-128):

- ``submit_task`` — PM submits new tasks to queue (commit 3).
- ``claim_task`` — agents claim tasks from queue (commit 4).
- ``update_task_status`` — agents update task status with state-
  machine validation (commit 5).
- ``get_queue`` — agents query queue with hierarchy-scoped filters
  (commit 6).
- ``get_subtasks`` — agents query subtask hierarchy (commit 6).
- ``set_task_priority`` — operator/PM adjusts task priority (commit 7).

Commit 2 (this commit) ships the **module skeleton**: Pydantic input/
output schemas for all six tools + the flat-allowlist ``_require_role``
helper. Handlers and per-tool hierarchy helpers
(``_check_can_submit_task``, ``_check_can_claim``, etc.) land at the
commits where each tool materializes (3-7); the audit-chain integration
+ ``record_api_call`` alpha-denormalization extension for ``task_type``
cohort (per migration 0013 shipped at commit 1) lands at commit 7
alongside ``set_task_priority``.

**Pydantic boundary validation** (CQ1 / CQ2 / CQ3 dispositions,
2026-05-07):

- ``priority`` and ``complexity`` use ``Field(ge=1, le=5)`` per spec's
  ``# 1..5`` annotation. Validation at API boundary; out-of-range
  values raise ``ValidationError`` before reaching handlers.
- ``GetQueueInput.status`` is typed ``list[TaskStatus] | None``
  (stricter than spec's ``list[str]``); Pydantic Literal validates
  enum membership at the API boundary.
- ``UpdateTaskStatusInput.status`` is a six-value ``Literal`` excluding
  ``'pending'`` — there is no transition TO pending per the
  state-machine in ``docs/kickoff/05-data-model.md`` § ``tasks.status``;
  rejecting at the Pydantic boundary catches the bug class before it
  reaches state-machine logic at commit 5.
- ``GetQueueInput.limit`` capped at 500 per CQ8 disposition.

``_require_role`` is the flat-allowlist primitive — exact mirror of
``tools/dispatch.py:_require_role`` (CQ4 / Mike's watch (2) at commit 2
prep). Per-tool hierarchy helpers wrap this primitive and add
``is_descendant`` checks against
``agents.permissions.PermissionResolver`` for surfaces where hierarchy
enforcement layers on top of the flat allowlist (``submit_task``
target_role check; ``update_task_status`` caller-IS-claimed_by-or-
submitter check; etc.). **Hierarchy logic does NOT live in this
helper.**

**`task_type` semantics** are spec'd in
``docs/kickoff/06-mcp-tools-phase-one.md`` § ``submit_task``: nine-value
work-shape taxonomy (planning / dispatching / monitoring / review /
investigation / synthesis / execution / revision / uncategorized) per
Q1 disposition at M4d-task-management-surface, 2026-05-07. The
taxonomy is product-agnostic infrastructure; per-product
``work_domain`` is ``BACKLOG.md`` § Future architectural work.
"""

from __future__ import annotations

import uuid
from typing import Any, Literal

from pydantic import BaseModel, Field

from meridian_orchestrator.agents.permissions import PermissionResolver
from meridian_orchestrator.audit.logger import write_audit
from meridian_orchestrator.auth import TokenSource
from meridian_orchestrator.errors import (
    ConflictError,
    NotFoundError,
)
from meridian_orchestrator.errors import PermissionError as OrchestratorPermissionError
from meridian_orchestrator.storage.interface import Storage
from meridian_orchestrator.storage.models import Task, TaskStatus, TaskType

# ----- Pydantic schemas ----------------------------------------------------


class SubmitTaskInput(BaseModel):
    agent_role: str
    description: str
    priority: int = Field(default=2, ge=1, le=5)
    complexity: int = Field(default=3, ge=1, le=5)
    parent_task_id: str | None = None
    task_type: TaskType | None = None


class SubmitTaskOutput(BaseModel):
    task: Task


class GetQueueInput(BaseModel):
    status: list[TaskStatus] | None = None
    agent_role: str | None = None
    project_id: str | None = None
    limit: int = Field(default=50, ge=1, le=500)
    offset: int = Field(default=0, ge=0)


class GetQueueOutput(BaseModel):
    tasks: list[Task]
    total: int


class UpdateTaskStatusInput(BaseModel):
    task_id: str
    status: Literal[
        "claimed",
        "in_progress",
        "completed",
        "failed",
        "declined",
        "cancelled",
    ]
    notes: str | None = None


class UpdateTaskStatusOutput(BaseModel):
    task: Task


class SetTaskPriorityInput(BaseModel):
    task_id: str
    priority: int = Field(ge=1, le=5)


class SetTaskPriorityOutput(BaseModel):
    task: Task


class ClaimTaskInput(BaseModel):
    task_id: str


class ClaimTaskOutput(BaseModel):
    task: Task


class GetSubtasksInput(BaseModel):
    parent_task_id: str


class GetSubtasksOutput(BaseModel):
    tasks: list[Task]


# ----- Permissions ---------------------------------------------------------


def _require_role(caller_role: str, *allowed: str) -> None:
    if caller_role not in allowed:
        raise OrchestratorPermissionError(
            f"caller role {caller_role!r} cannot invoke this tool"
        )


# ----- Submitter literals (Phase 1 simplification) -------------------------

# Literal stored in tasks.submitted_by for Mike-via-OAuth submissions per
# CQ3 Reading A disposition (M4d-task-management-surface commit 3,
# 2026-05-07). 05-data-model.md:209-216 names the literal explicitly:
# "For Mike: the literal string `human:mike` (so it's distinguishable from
# any UUID)."
HUMAN_MIKE_LITERAL = "human:mike"

# Phase 1 literal-handling for clause 2 of submit_task parent-ownership
# check (CQ10 Reading A disposition, 2026-05-07). Maps non-UUID
# submitted_by literals to their effective role for clause 2 ("the role
# that submitted the parent") resolution. Mike-via-OAuth submits as PM
# per hierarchy invariant 1 (06-mcp-tools-phase-one.md:913). Phase 2
# generalization to pattern-based "human:<name>" → role mapping tracked
# in BACKLOG.md § Future architectural work.
HUMAN_LITERAL_ROLE_MAP: dict[str, str] = {
    HUMAN_MIKE_LITERAL: "pm",
}


# ----- submit_task --------------------------------------------------------


def _check_can_submit_task(
    *,
    caller_role: str,
    target_role: str,
    caller_source: TokenSource,
    permissions: PermissionResolver,
) -> None:
    """Hierarchy + Q9 self-submit gate for ``submit_task``.

    Two gates (negative framing per CQ1 Reading A disposition):

    1. **Q9 self-submit** (handled first because the spec's "direct
       report" hierarchy strictly excludes self-submit; Q9 OAuth
       allowance is the layered exception): ``caller_role ==
       target_role`` rejects unless ``caller_source == "oauth"``.
       Mike-via-OAuth self-submit allowed; PM-as-agent self-submit
       rejected.
    2. **Hierarchy** (only applies to non-self submission): ``target_role``
       must be in caller's **direct** manages list per
       ``06-mcp-tools-phase-one.md:44`` ("caller may submit a task only
       for a role they manage (direct report). [...] PM **cannot** submit
       a task with agent_role='implementer' — that's a hierarchy
       bypass."). Uses ``permissions.manages_directly``, NOT
       ``is_descendant``: Mike→PM, PM→Engineer, Engineer→{Implementer,
       Tester} are direct edges; transitive reads like PM→Implementer
       are explicit hierarchy bypasses by spec.

    Equivalent positive framing for the gate as a whole: caller may
    submit for target IFF (target == caller AND caller_source ==
    "oauth") OR (target != caller AND target in caller's direct manages).

    Pure-Python; no DB hit. Runs outside the transaction in
    :func:`submit_task`. Parent ownership (clause 1 + clause 2 per CQ4
    Reading B) is checked separately by
    :func:`_check_can_submit_task_parent_ownership`.
    """
    if caller_role == target_role:
        if caller_source != "oauth":
            raise OrchestratorPermissionError(
                f"caller role {caller_role!r} cannot self-submit "
                "(only OAuth callers may self-submit; static-token callers "
                "are agent processes that should not enqueue work for themselves)"
            )
        # OAuth self-submit allowed — spec's "direct report" hierarchy
        # bypass acknowledged via Q9 disposition.
        return
    if not permissions.manages_directly(caller_role, target_role):  # type: ignore[arg-type]
        raise OrchestratorPermissionError(
            f"caller role {caller_role!r} cannot submit task for "
            f"target_role {target_role!r} (hierarchy bypass: target is "
            "not in caller's direct manages list per "
            "06-mcp-tools-phase-one.md:44)"
        )


async def _check_can_submit_task_parent_ownership(
    *,
    storage: Storage,
    parent_task_id: str,
    caller_agent_id: str,
    caller_role: str,
) -> dict[str, Any]:
    """Two-clause OR ownership check for ``submit_task`` subtasks.

    Per ``06-mcp-tools-phase-one.md:46`` (CQ4 Reading B disposition,
    2026-05-07):

    - **Clause 1** (agent-identity): ``parent.claimed_by ==
      caller_agent_id``.
    - **Clause 2** (role-level): ``caller_role`` equals the role that
      submitted the parent. Resolved via LEFT JOIN to ``agents`` (when
      ``submitted_by`` is a UUID) OR via ``HUMAN_LITERAL_ROLE_MAP``
      (when ``submitted_by`` is a Phase 1 literal like
      ``"human:mike"`` per CQ3 Reading A + CQ10 Reading A).

    Clause 2 supports role-authority-persists-across-agent-instances
    semantics (Phase 2 multi-specialist-same-role per
    05-data-model.md:155-162; PM-A submits, dies, PM-B spawns
    subtasks under it).

    Returns the parent row dict on success — caller has access to
    ``company_id``/``project_id`` for subtask inheritance. Raises
    :class:`NotFoundError` if parent not found; raises
    :class:`OrchestratorPermissionError` if neither clause matches.
    """
    row = await storage.fetch_one(
        "SELECT t.id, t.company_id, t.project_id, t.claimed_by, "
        "t.submitted_by, a.role AS submitter_role "
        "FROM tasks t LEFT JOIN agents a ON a.id = t.submitted_by "
        "WHERE t.id = ?",
        (parent_task_id,),
    )
    if row is None:
        raise NotFoundError(f"parent_task_id={parent_task_id!r} not found")

    # Clause 1: agent-identity ownership.
    if row["claimed_by"] == caller_agent_id:
        return dict(row)

    # Clause 2: role-level authority. ``submitter_role`` from JOIN is
    # NULL when ``submitted_by`` is a non-UUID literal;
    # ``HUMAN_LITERAL_ROLE_MAP`` resolves Phase 1 literals.
    submitter_role = row["submitter_role"]
    if submitter_role is None:
        submitter_role = HUMAN_LITERAL_ROLE_MAP.get(row["submitted_by"])
    if submitter_role == caller_role:
        return dict(row)

    raise OrchestratorPermissionError(
        f"caller {caller_agent_id!r} (role={caller_role!r}) does not own "
        f"parent task {parent_task_id!r} (clauses: claimed_by="
        f"{row['claimed_by']!r}, submitter_role={submitter_role!r})"
    )


async def _derive_company_project_for_caller(
    *, storage: Storage, caller_agent_id: str
) -> tuple[str, str]:
    """Phase 1 single-project derivation for root tasks.

    Looks up caller agent's ``company_id`` from the ``agents`` table,
    then resolves the (single) project under that company.

    Phase 1 single-company-single-project assumption pinned by **runtime
    sentinel** (per CQ2 refinement, 2026-05-07): if zero or more than
    one project is found under the company, raise an explicit error.
    Silent first-row-pick would mask a Phase 2 multi-project
    introduction; the loud failure forces the architectural shift to
    update this code path (e.g., add explicit ``project_id`` input to
    ``SubmitTaskInput``).
    """
    agent_row = await storage.fetch_one(
        "SELECT company_id FROM agents WHERE id = ?", (caller_agent_id,)
    )
    if agent_row is None:
        raise NotFoundError(
            f"caller_agent_id={caller_agent_id!r} not in agents table"
        )
    company_id = agent_row["company_id"]

    project_rows = await storage.fetch_all(
        "SELECT id FROM projects WHERE company_id = ?", (company_id,)
    )
    if len(project_rows) == 0:
        raise NotFoundError(
            f"no project under company_id={company_id!r}"
        )
    if len(project_rows) > 1:
        raise ConflictError(
            f"multiple projects under company_id={company_id!r} "
            f"({len(project_rows)} found); Phase 1 single-project "
            "assumption broken; submit_task derivation needs explicit "
            "project_id input for Phase 2 multi-project."
        )
    return company_id, project_rows[0]["id"]


async def submit_task(
    *,
    storage: Storage,
    permissions: PermissionResolver,
    request: SubmitTaskInput,
    caller_agent_id: str,
    caller_role: str,
    caller_source: TokenSource,
) -> SubmitTaskOutput:
    """PM/Engineer submit a new task to the queue.

    **Hierarchy gate** (per CQ1 disposition): caller may submit only for
    a role they manage (``is_descendant``); self-submit allowed only
    for OAuth callers (Mike-via-OAuth) per Q9 (A) disposition.
    PM-as-agent self-submit rejects with PERMISSION_DENIED.

    **Sub-task ownership** (per CQ4 Reading B + CQ10 Reading A): when
    ``parent_task_id`` is set, two-clause OR — caller is
    ``parent.claimed_by`` OR caller's role equals the role that
    submitted the parent (resolved via JOIN to agents OR via
    ``HUMAN_LITERAL_ROLE_MAP`` for Phase 1 literals).

    **Company / project derivation** (per CQ2 with runtime sentinel):
    sub-tasks inherit ``company_id``/``project_id`` from parent; root
    tasks derive from caller agent's company + the (sole) project under
    it. Phase 1 single-project assumption is sentinel-pinned —
    multi-project state raises ``ConflictError`` rather than silent
    first-row-pick.

    **submitted_by** (per CQ3 Reading A): ``caller_source == "oauth"``
    → ``"human:mike"`` literal; ``caller_source == "static"`` →
    ``caller_agent_id``.

    **Audit** (per Q7 + CQ3 belt-and-suspenders): action
    ``task.submit``, target=``task_id``; ``details_json`` carries full
    request input PLUS ``submitted_by`` PLUS ``caller_source`` (so
    forensic queries can correlate without TokenRecord lookup) PLUS
    the new ``task_id``.

    **Atomicity** (per CQ6): full ``storage.transaction()`` wrap
    covering parent-ownership lookup / company-project derivation /
    INSERT / audit write. Failure mid-handler rolls back all writes
    plus the audit row.
    """
    # Hierarchy + Q9 (pure-Python; cheap; outside transaction).
    _check_can_submit_task(
        caller_role=caller_role,
        target_role=request.agent_role,
        caller_source=caller_source,
        permissions=permissions,
    )

    async with storage.transaction():
        # Resolve company/project — from parent (subtask) or caller
        # (root task).
        if request.parent_task_id is not None:
            parent = await _check_can_submit_task_parent_ownership(
                storage=storage,
                parent_task_id=request.parent_task_id,
                caller_agent_id=caller_agent_id,
                caller_role=caller_role,
            )
            company_id = parent["company_id"]
            project_id = parent["project_id"]
        else:
            company_id, project_id = await _derive_company_project_for_caller(
                storage=storage, caller_agent_id=caller_agent_id
            )

        # CQ3 Reading A: submitted_by literal for OAuth, agent_id for
        # static.
        submitted_by = (
            HUMAN_MIKE_LITERAL if caller_source == "oauth" else caller_agent_id
        )

        task_id = str(uuid.uuid4())
        await storage.execute(
            "INSERT INTO tasks (id, company_id, project_id, parent_task_id, "
            "agent_role, description, priority, complexity, task_type, "
            "submitted_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                task_id,
                company_id,
                project_id,
                request.parent_task_id,
                request.agent_role,
                request.description,
                request.priority,
                request.complexity,
                request.task_type,
                submitted_by,
            ),
        )

        row = await storage.fetch_one(
            "SELECT * FROM tasks WHERE id = ?", (task_id,)
        )
        assert row is not None  # just inserted; SELECT cannot miss
        task = Task(**dict(row))

        # Audit per Q7 + CQ3 belt-and-suspenders: full input + derived
        # fields + caller_source for forensic correlation.
        details = request.model_dump(mode="json")
        details["task_id"] = task_id
        details["submitted_by"] = submitted_by
        details["caller_source"] = caller_source
        await write_audit(
            storage,
            actor=caller_agent_id,
            action="task.submit",
            target=task_id,
            details=details,
        )

    return SubmitTaskOutput(task=task)


__all__ = [
    "HUMAN_LITERAL_ROLE_MAP",
    "HUMAN_MIKE_LITERAL",
    "ClaimTaskInput",
    "ClaimTaskOutput",
    "GetQueueInput",
    "GetQueueOutput",
    "GetSubtasksInput",
    "GetSubtasksOutput",
    "SetTaskPriorityInput",
    "SetTaskPriorityOutput",
    "SubmitTaskInput",
    "SubmitTaskOutput",
    "UpdateTaskStatusInput",
    "UpdateTaskStatusOutput",
    "submit_task",
]
