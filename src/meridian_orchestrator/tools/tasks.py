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


# ----- claim_task ---------------------------------------------------------


def _check_can_claim(
    *, caller_role: str, target_agent_role: str
) -> None:
    """Strict role-equality gate for ``claim_task`` per
    ``06-mcp-tools-phase-one.md:111`` ("caller's role must equal
    ``task.agent_role``. Implementer cannot claim Tester tasks even if
    both report to the same Engineer.").

    NOT a hierarchy walk; strict ``==``. Distinct from
    ``submit_task``'s ``manages_directly`` (one-hop adjacency query)
    and from ``get_subtasks``'s ``is_ancestor`` (transitive subtree
    query). Per-tool hierarchy-gate semantic diversity is by design;
    tracked in ``BACKLOG.md`` § watch-for-pattern (per-tool
    hierarchy-gate semantic diversity entry).

    No ``PermissionResolver`` dependency: the check has no graph
    operations to delegate. Pre-staging a method like
    ``PermissionResolver.is_same_role`` would create a false
    abstraction (the three "primitives" share input shape but not
    semantics — graph reachability vs graph adjacency vs Python ``==``
    don't constitute a coherent family per CQ1 disposition,
    M4d-task-management-surface commit 4, 2026-05-07).
    """
    if caller_role != target_agent_role:
        raise OrchestratorPermissionError(
            f"caller role {caller_role!r} cannot claim task with "
            f"agent_role={target_agent_role!r} (strict role equality "
            "required per 06-mcp-tools-phase-one.md:111; sibling-role "
            "claims rejected even within the same dispatch chain)"
        )


async def claim_task(
    *,
    storage: Storage,
    request: ClaimTaskInput,
    caller_agent_id: str,
    caller_role: str,
) -> ClaimTaskOutput:
    """Agent claims a task from the queue.

    **Hierarchy gate** (per spec line 111): strict ``caller_role ==
    task.agent_role``. No graph walk; sibling-role claims rejected
    even within the same dispatch chain (Implementer cannot claim
    Tester tasks).

    **Atomic claim** (per spec line 109 + CQ2 disposition): conditional
    UPDATE with ``WHERE status = 'pending'`` provides race protection
    at the SQL level. ``Storage.execute`` returns ``None`` (no
    rowcount via the current Storage interface; BACKLOG entry tracks
    rowcount-API extension when third instance of two-query
    atomic-update pattern surfaces). SELECT-verify after UPDATE
    confirms the claim succeeded for THIS caller — distinguishes
    a successful claim from a concurrent claim by another caller
    OR a non-pending starting state (both observe as "UPDATE matched
    0 rows"; both raise CONFLICT).

    **Atomicity** (per CQ4): full ``storage.transaction()`` wrap
    covering UPDATE + SELECT-verify + audit write. Failure
    mid-handler rolls back all writes plus the audit row.

    **Audit** (per CQ5 minimal-audit-for-minimal-input): action
    ``task.claim``, target=``task_id``, details=``{task_id,
    claimed_by}``. Symmetric with ``task.submit``'s full-input
    capture; the input is single-field so the audit details are
    correspondingly minimal.

    Raises :class:`NotFoundError` when ``task_id`` doesn't resolve;
    raises :class:`OrchestratorPermissionError` on hierarchy
    mismatch; raises :class:`ConflictError` when the conditional
    UPDATE fails (concurrent claim OR starting-from-non-pending state).
    """
    # Read task's agent_role for hierarchy check (cheap; pre-transaction).
    row = await storage.fetch_one(
        "SELECT agent_role FROM tasks WHERE id = ?",
        (request.task_id,),
    )
    if row is None:
        raise NotFoundError(f"task_id={request.task_id!r} not found")

    _check_can_claim(
        caller_role=caller_role, target_agent_role=row["agent_role"]
    )

    async with storage.transaction():
        # Atomic conditional claim. WHERE status='pending' is the race
        # protection clause — concurrent claims are mutually exclusive
        # at the SQL level.
        await storage.execute(
            "UPDATE tasks SET claimed_by = ?, "
            "claimed_at = datetime('now'), status = 'claimed' "
            "WHERE id = ? AND status = 'pending'",
            (caller_agent_id, request.task_id),
        )
        # SELECT-verify: claimed_by = ? predicate confirms THIS caller
        # claimed (distinguishes from concurrent claim by another caller);
        # status = 'claimed' predicate confirms the UPDATE matched
        # (distinguishes from starting-from-non-pending state).
        updated = await storage.fetch_one(
            "SELECT * FROM tasks WHERE id = ? AND claimed_by = ? "
            "AND status = 'claimed'",
            (request.task_id, caller_agent_id),
        )
        if updated is None:
            raise ConflictError(
                f"task_id={request.task_id!r} claim failed: task is "
                "not in pending state (already claimed by another "
                "caller, terminal, or status changed)"
            )

        await write_audit(
            storage,
            actor=caller_agent_id,
            action="task.claim",
            target=request.task_id,
            details={
                "task_id": request.task_id,
                "claimed_by": caller_agent_id,
            },
        )

    return ClaimTaskOutput(task=Task(**dict(updated)))


# ----- update_task_status -------------------------------------------------

# State-machine transition matrix per docs/kickoff/05-data-model.md:170-178.
# Maps each from-status to the set of allowed to-statuses. Terminal statuses
# (completed/failed/declined/cancelled) have empty sets — no outgoing
# transitions. Closed-enum sentinel pattern test walks the full Cartesian
# (7 from-states x 6 to-states from UpdateTaskStatusInput.status Literal =
# 42 pairs) per CQ3 Reading A disposition (M4d-task-management-surface
# commit 5, 2026-05-07): single parametrized sentinel mirrors the established
# closed-enum sentinel convention; matrix is one invariant.
_ALLOWED_TRANSITIONS: dict[TaskStatus, set[TaskStatus]] = {
    "pending": {"claimed", "cancelled"},
    "claimed": {"in_progress", "declined", "cancelled"},
    "in_progress": {"completed", "failed", "cancelled"},
    "completed": set(),
    "failed": set(),
    "declined": set(),
    "cancelled": set(),
}


def _check_can_update_task_status(
    *,
    task: dict[str, Any],
    caller_agent_id: str,
    caller_role: str,
    target_status: str,
) -> None:
    """Two-clause OR hierarchy gate for ``update_task_status`` per
    ``06-mcp-tools-phase-one.md:81`` ("caller must be ``claimed_by``
    the task **or** caller must be the role that submitted it (the
    latter only for ``cancelled``)").

    - **Clause 1** (always applies): ``task.claimed_by ==
      caller_agent_id``. Strict agent-identity check.
    - **Clause 2** (conditional on ``target_status == "cancelled"``):
      ``caller_role`` equals the role that submitted the task.
      Resolved via ``task["submitter_role"]`` (from JOIN to agents
      when ``submitted_by`` is a UUID) OR via
      ``HUMAN_LITERAL_ROLE_MAP`` (when ``submitted_by`` is a Phase 1
      literal like ``"human:mike"``).

    Helper takes the task row dict already SELECTed by the handler;
    does NOT do its own SELECT (CQ5 Reading A — permission-check-
    returns-data watch stays at 1 instance; commit 3's
    ``_check_can_submit_task_parent_ownership`` had specific
    motivation [avoiding redundant SELECT for inheritance derivation]
    that doesn't apply here).

    NOTE on submitter-role resolution: Clause 2's resolution logic
    (JOIN-or-HUMAN_LITERAL_ROLE_MAP-fallback + caller_role equality)
    is structurally identical to
    ``_check_can_submit_task_parent_ownership`` clause 2 (commit 3).
    Two-instance count for cross-tool helper extraction; tracked at
    BACKLOG.md § watch-for-pattern (submitter-role resolution shared
    logic across tools entry). If a third instance surfaces in any
    future commit (set_task_priority, get_subtasks, get_queue, or
    post-M4d work), extract to ``_resolve_submitter_role(storage,
    task_id)`` at that commit and refactor both prior call sites in
    the same commit. Threshold-of-three discipline per F4.5.2 reframe
    (Convention 4 in docs/conventions/00-README.md).
    """
    # Clause 1: agent-identity ownership.
    if task["claimed_by"] == caller_agent_id:
        return

    # Clause 2: role-level submitter authority — only applies for
    # cancelled (per spec line 81 conditional clause).
    if target_status == "cancelled":
        submitter_role = task["submitter_role"]
        if submitter_role is None:
            submitter_role = HUMAN_LITERAL_ROLE_MAP.get(
                task["submitted_by"]
            )
        if submitter_role == caller_role:
            return

    raise OrchestratorPermissionError(
        f"caller {caller_agent_id!r} (role={caller_role!r}) cannot "
        f"update status of task to {target_status!r} (clauses: "
        f"claimed_by={task['claimed_by']!r}, "
        f"submitter_role={task.get('submitter_role')!r}; clause 2 "
        "applies only when target_status='cancelled')"
    )


async def update_task_status(
    *,
    storage: Storage,
    request: UpdateTaskStatusInput,
    caller_agent_id: str,
    caller_role: str,
) -> UpdateTaskStatusOutput:
    """Agent or operator updates a task's status.

    **Hierarchy gate** (per spec line 81 + CQ5): caller must be
    ``claimed_by`` the task OR (if target is ``cancelled``) caller's
    role must equal the role that submitted the task. Two-clause OR
    with conditional second clause.

    **State-machine validation** (per spec line 83 + CQ3 Reading A):
    ``target_status`` must be in ``_ALLOWED_TRANSITIONS[current_status]``;
    otherwise raises ``ConflictError``. Matrix encoded per
    ``05-data-model.md`` § ``tasks.status``; sentinel test walks the
    full 42-pair Cartesian.

    **Atomic UPDATE** (per CQ4 + third instance of two-query pattern
    per CQ2 Reading B with renewed deferral basis): conditional UPDATE
    with ``WHERE id=? AND status=?`` provides race protection on the
    starting state — concurrent transitions are mutually exclusive.
    SELECT-verify after UPDATE confirms the transition succeeded for
    THIS caller's target state.

    **Atomicity** (per CQ4): full ``storage.transaction()`` wrap
    covering precursor SELECT + UPDATE + SELECT-verify + audit.

    **Audit** (per CQ6 Reading A): action ``task.status_change``,
    target=``task_id``, details capture ``{task_id, from_status,
    to_status, notes}`` — minimal forensic surface symmetric with
    ``task.submit`` and ``task.claim``. Forensic queries can JOIN to
    tasks table for full row state.

    Raises :class:`NotFoundError` when ``task_id`` doesn't resolve;
    :class:`OrchestratorPermissionError` on hierarchy mismatch;
    :class:`ConflictError` on invalid transition OR concurrent state
    change OR starting-from-non-expected state.
    """
    # Precursor SELECT with JOIN to agents for clause 2 submitter-role
    # resolution. Same JOIN shape as
    # _check_can_submit_task_parent_ownership (commit 3); see
    # _check_can_update_task_status docstring for cross-tool extraction
    # tracking.
    row = await storage.fetch_one(
        "SELECT t.id, t.status, t.claimed_by, t.submitted_by, "
        "a.role AS submitter_role "
        "FROM tasks t LEFT JOIN agents a ON a.id = t.submitted_by "
        "WHERE t.id = ?",
        (request.task_id,),
    )
    if row is None:
        raise NotFoundError(f"task_id={request.task_id!r} not found")

    task = dict(row)
    current_status: TaskStatus = task["status"]

    # Hierarchy gate (in-memory; cheap; pre-transaction).
    _check_can_update_task_status(
        task=task,
        caller_agent_id=caller_agent_id,
        caller_role=caller_role,
        target_status=request.status,
    )

    # State-machine validation (in-memory; cheap; pre-transaction).
    allowed = _ALLOWED_TRANSITIONS.get(current_status, set())
    if request.status not in allowed:
        raise ConflictError(
            f"task_id={request.task_id!r} invalid transition: "
            f"{current_status!r} -> {request.status!r} "
            f"(allowed from {current_status!r}: "
            f"{sorted(allowed) if allowed else '(terminal)'})"
        )

    async with storage.transaction():
        # Atomic conditional UPDATE. WHERE status=? provides race
        # protection on starting state — concurrent transitions are
        # mutually exclusive.
        await storage.execute(
            "UPDATE tasks SET status = ?, "
            "notes = COALESCE(?, notes), "
            "completed_at = CASE WHEN ? IN "
            "('completed', 'failed', 'declined', 'cancelled') "
            "THEN datetime('now') ELSE completed_at END "
            "WHERE id = ? AND status = ?",
            (
                request.status,
                request.notes,
                request.status,
                request.task_id,
                current_status,
            ),
        )
        # SELECT-verify: status = ? predicate confirms the UPDATE
        # matched (distinguishes from concurrent state change between
        # our precursor SELECT and the UPDATE). Third instance of the
        # two-query atomic-update pattern; deferred per CQ2 Reading B
        # renewed-deferral basis (BACKLOG.md § Storage rowcount-API
        # entry; trigger updated to fourth-instance-with-non-cosmetic-
        # motivation).
        updated = await storage.fetch_one(
            "SELECT * FROM tasks WHERE id = ? AND status = ?",
            (request.task_id, request.status),
        )
        if updated is None:
            raise ConflictError(
                f"task_id={request.task_id!r} status update failed: "
                f"task is not in expected starting state "
                f"{current_status!r} (concurrent state change "
                "between precursor SELECT and UPDATE)"
            )

        await write_audit(
            storage,
            actor=caller_agent_id,
            action="task.status_change",
            target=request.task_id,
            details={
                "task_id": request.task_id,
                "from_status": current_status,
                "to_status": request.status,
                "notes": request.notes,
            },
        )

    return UpdateTaskStatusOutput(task=Task(**dict(updated)))


# ----- get_queue ----------------------------------------------------------


async def get_queue(
    *,
    storage: Storage,
    permissions: PermissionResolver,
    request: GetQueueInput,
    caller_agent_id: str,
    caller_role: str,
) -> GetQueueOutput:
    """Read-side queue query with hierarchy-scoped visibility filter.

    **Visibility-filter** (per spec line 65 + CQ3 commit 6 Reading B
    sub-category): the hierarchy gate applies as a SQL WHERE clause
    filter, NOT a raise-gate. PM/Engineer see everything in their
    inclusive subtree; Implementer/Tester see only their own claimed
    tasks.

    | Caller role | Hierarchy filter |
    |---|---|
    | pm | (no role filter; PM's subtree-inclusive covers all roles) |
    | engineer | ``agent_role IN ('engineer', 'implementer', 'tester')`` |
    | implementer | ``claimed_by = caller_agent_id`` |
    | tester | ``claimed_by = caller_agent_id`` |

    **Caller-supplied filters** (compose with hierarchy filter via AND):
    ``status`` (list[TaskStatus]); ``agent_role`` (str); ``project_id``
    (str). Per CQ5 commit 6: ``agent_role`` filter conflicting with
    caller's hierarchy scope raises PERMISSION_DENIED — e.g., implementer
    querying ``agent_role='engineer'``. Conflict check via
    ``permissions.is_descendant(caller_role, request.agent_role)``.

    **Pagination**: ``limit`` (Pydantic Field(ge=1, le=500), default
    50); ``offset`` (Field(ge=0), default 0). ``total`` (count without
    limit/offset; for pagination UX) is computed via separate
    ``COUNT(*)`` query against the same WHERE clause.

    **NOT audited** (read-only, high-frequency per spec line 67).
    """
    # Visibility-filter hierarchy clause per caller_role. Inline per
    # CQ6 Reading A (two call sites at commit 6 below threshold-of-
    # three for cross-handler extraction; threshold-of-three at
    # commit 7 if set_task_priority adds third subtree-filter call
    # site). Tracked at BACKLOG.md § task-row subtree SQL scoping
    # pattern duplication.
    if caller_role == "pm":
        hierarchy_clause = "1=1"
        hierarchy_params: list[Any] = []
    elif caller_role == "engineer":
        hierarchy_clause = (
            "agent_role IN ('engineer', 'implementer', 'tester')"
        )
        hierarchy_params = []
    elif caller_role in ("implementer", "tester"):
        hierarchy_clause = "claimed_by = ?"
        hierarchy_params = [caller_agent_id]
    else:
        raise OrchestratorPermissionError(
            f"caller role {caller_role!r} cannot invoke get_queue"
        )

    # CQ5: agent_role filter compatible with caller's hierarchy scope?
    # Use is_descendant: PM's scope = {pm, engineer, impl, tester};
    # engineer = {engineer, impl, tester}; impl/tester = {self}.
    if (
        request.agent_role is not None
        and not permissions.is_descendant(
            caller_role,  # type: ignore[arg-type]
            request.agent_role,  # type: ignore[arg-type]
        )
    ):
        raise OrchestratorPermissionError(
            f"caller role {caller_role!r} cannot filter queue by "
            f"agent_role={request.agent_role!r} (outside caller's "
            "hierarchy scope per 06-mcp-tools-phase-one.md:65)"
        )

    where_clauses: list[str] = [hierarchy_clause]
    where_params: list[Any] = list(hierarchy_params)

    if request.status is not None:
        placeholders = ",".join(["?"] * len(request.status))
        where_clauses.append(f"status IN ({placeholders})")
        where_params.extend(request.status)

    if request.agent_role is not None:
        where_clauses.append("agent_role = ?")
        where_params.append(request.agent_role)

    if request.project_id is not None:
        where_clauses.append("project_id = ?")
        where_params.append(request.project_id)

    where_sql = " AND ".join(where_clauses)

    total_row = await storage.fetch_one(
        f"SELECT COUNT(*) AS c FROM tasks WHERE {where_sql}",
        tuple(where_params),
    )
    total = int(total_row["c"]) if total_row is not None else 0

    rows = await storage.fetch_all(
        f"SELECT * FROM tasks WHERE {where_sql} "
        f"ORDER BY priority ASC, created_at ASC "
        f"LIMIT ? OFFSET ?",
        (*where_params, request.limit, request.offset),
    )
    tasks = [Task(**dict(row)) for row in rows]
    return GetQueueOutput(tasks=tasks, total=total)


# ----- get_subtasks -------------------------------------------------------


async def _check_can_read_subtasks(
    *,
    storage: Storage,
    permissions: PermissionResolver,
    parent_task_id: str,
    caller_agent_id: str,
    caller_role: str,
) -> None:
    """Raise-gate part of ``get_subtasks`` per spec line 125: caller
    must be in chain (submitter, claimed_by, OR ancestor of) the
    parent task. Raises :class:`NotFoundError` if parent doesn't
    exist; :class:`OrchestratorPermissionError` if caller not in
    chain (per CQ4 Reading A — spec wording gate-shape; dispatch.py
    precedent supports raise; operational-context confusion if
    silent-empty).

    The visibility-filter for the subtask result set is layered
    separately in :func:`get_subtasks` (matches get_queue's role-
    scoped SQL filter shape). This helper is the raise-gate part of
    the hybrid hierarchy-authorization shape per CQ3 commit 6 Reading
    B sub-category accounting.

    Helper does its own SELECT for parent (no perf cost vs handler-
    SELECT-then-pass shape; encapsulates parent-access check fully;
    permission-check-returns-data watch stays at 1).
    """
    parent = await storage.fetch_one(
        "SELECT id, agent_role, claimed_by, submitted_by FROM tasks "
        "WHERE id = ?",
        (parent_task_id,),
    )
    if parent is None:
        raise NotFoundError(
            f"parent_task_id={parent_task_id!r} not found"
        )

    # Submitter check (agent-id match).
    if parent["submitted_by"] == caller_agent_id:
        return
    # claimed_by check (agent-id match).
    if parent["claimed_by"] == caller_agent_id:
        return
    # Ancestor check: caller's role transitively manages parent's
    # agent_role (caller is an ancestor of parent's target role).
    if permissions.is_descendant(caller_role, parent["agent_role"]):  # type: ignore[arg-type]
        return

    raise OrchestratorPermissionError(
        f"caller {caller_agent_id!r} (role={caller_role!r}) not in "
        f"chain of parent_task_id={parent_task_id!r} (clauses: "
        f"submitter={parent['submitted_by']!r}, "
        f"claimed_by={parent['claimed_by']!r}, "
        f"parent_role={parent['agent_role']!r})"
    )


async def get_subtasks(
    *,
    storage: Storage,
    permissions: PermissionResolver,
    request: GetSubtasksInput,
    caller_agent_id: str,
    caller_role: str,
) -> GetSubtasksOutput:
    """Read-side subtask hierarchy query.

    **Hybrid authorization shape** per CQ3 commit 6 Reading B:

    1. **Raise-gate** (parent access): caller must be in parent's
       chain (submitter / claimed_by / ancestor) per spec line 125.
       :func:`_check_can_read_subtasks` raises PERMISSION_DENIED on
       no-match.
    2. **Visibility-filter** (subtask result set): same role-scoped
       SQL filter shape as get_queue. PM/Engineer see all subtasks
       under parent; Implementer/Tester see only their own claimed
       subtasks.

    **NOT audited** (read-only, high-frequency per spec line 127).
    """
    await _check_can_read_subtasks(
        storage=storage,
        permissions=permissions,
        parent_task_id=request.parent_task_id,
        caller_agent_id=caller_agent_id,
        caller_role=caller_role,
    )

    # Visibility-filter (mirrors get_queue's hierarchy clause inline
    # per CQ6 Reading A; second of two call sites tracked at
    # BACKLOG.md § task-row subtree SQL scoping pattern duplication).
    if caller_role == "pm":
        hierarchy_clause = "1=1"
        hierarchy_params: list[Any] = []
    elif caller_role == "engineer":
        hierarchy_clause = (
            "agent_role IN ('engineer', 'implementer', 'tester')"
        )
        hierarchy_params = []
    elif caller_role in ("implementer", "tester"):
        hierarchy_clause = "claimed_by = ?"
        hierarchy_params = [caller_agent_id]
    else:
        raise OrchestratorPermissionError(
            f"caller role {caller_role!r} cannot invoke get_subtasks"
        )

    rows = await storage.fetch_all(
        f"SELECT * FROM tasks WHERE parent_task_id = ? "
        f"AND {hierarchy_clause} "
        f"ORDER BY priority ASC, created_at ASC",
        (request.parent_task_id, *hierarchy_params),
    )
    tasks = [Task(**dict(row)) for row in rows]
    return GetSubtasksOutput(tasks=tasks)


# ----- set_task_priority --------------------------------------------------


async def _check_can_set_priority(
    *,
    storage: Storage,
    permissions: PermissionResolver,
    task_id: str,
    caller_agent_id: str,
    caller_role: str,
) -> dict[str, Any]:
    """Three-clause OR raise-gate per ``06-mcp-tools-phase-one.md:105``:
    "caller must be the submitter, the claimed-by, or an ancestor in
    the hierarchy."

    Per pre-prep verification at M4d commit 7 prep: spec wording
    "the **submitter**" (line 105) is **agent-id-level** — distinct
    from submit_task's "the role that submitted the parent" (line 46)
    and update_task_status's "the role that submitted it" (line 91)
    which are role-level. NO JOIN to agents; NO HUMAN_LITERAL_ROLE_MAP
    fallback. Submitter-role-resolution-shared-logic watch entry at 2
    instances does NOT increment at this commit (cross-tool extraction
    threshold-of-three remains).

    Mechanically identical 3-clause OR shape to commit 6's
    :func:`_check_can_read_subtasks` (get_subtasks parent-access).
    Cross-tool duplication at 2 instances; deferred-with-citation per
    CQ1 commit 7 Reading B disposition (mirrors commit 5's CQ1 cross-
    tool extraction-deferral precedent). New BACKLOG watch entry
    "in-chain raise-gate logic duplication" tracks the pattern;
    threshold-of-three for ``_check_can_access_task_chain`` extraction
    in any future commit (post-M4d work).

    Returns task row dict on success — handler reuses for audit
    from-priority capture (per CQ7 commit 7 + permission-check-returns-
    data per-tool-by-design rationale).
    """
    row = await storage.fetch_one(
        "SELECT id, agent_role, priority, submitted_by, claimed_by "
        "FROM tasks WHERE id = ?",
        (task_id,),
    )
    if row is None:
        raise NotFoundError(f"task_id={task_id!r} not found")

    # Clause 1: submitter (agent-id-level per spec line 105 wording).
    if row["submitted_by"] == caller_agent_id:
        return dict(row)
    # Clause 2: claimed-by (agent-id-level).
    if row["claimed_by"] == caller_agent_id:
        return dict(row)
    # Clause 3: ancestor in hierarchy (caller transitively manages
    # task's agent_role).
    if permissions.is_descendant(caller_role, row["agent_role"]):  # type: ignore[arg-type]
        return dict(row)

    raise OrchestratorPermissionError(
        f"caller {caller_agent_id!r} (role={caller_role!r}) cannot "
        f"set priority on task {task_id!r} (clauses: "
        f"submitter={row['submitted_by']!r}, "
        f"claimed_by={row['claimed_by']!r}, "
        f"task_role={row['agent_role']!r})"
    )


async def set_task_priority(
    *,
    storage: Storage,
    permissions: PermissionResolver,
    request: SetTaskPriorityInput,
    caller_agent_id: str,
    caller_role: str,
) -> SetTaskPriorityOutput:
    """Update a task's priority value.

    **Hierarchy gate** (per spec line 105 + CQ7 commit 7): 3-clause
    OR via :func:`_check_can_set_priority` — caller is submitter
    (agent-id) OR claimed-by (agent-id) OR ancestor (transitive role
    via ``permissions.is_descendant``).

    **Atomicity** (per CQ4): full ``storage.transaction()`` wrap
    covering UPDATE + audit. **No SELECT-verify clause** per CQ6
    commit 7 Reading A — priority is an operator-level idempotent
    change; concurrent set_task_priority calls land last-writer-wins
    (semantically acceptable; operator's latest priority is desired).
    Two-query atomic-update pattern watch stays at 3 instances; this
    commit doesn't trip the threshold-of-four-with-non-cosmetic-
    motivation trigger.

    **Audit** (per CQ7 commit 7 minimal-input-capture): action
    ``task.priority_change``, target=``task_id``, details capture
    ``{task_id, from_priority, to_priority}``. Symmetric with
    task.submit / task.claim / task.status_change minimal shape.

    The ``_check_can_set_priority`` helper returns the task row dict
    (per permission-check-returns-data per-tool-by-design rationale);
    handler reuses ``priority`` for audit from-priority capture
    without a redundant SELECT.

    Raises :class:`NotFoundError` when ``task_id`` doesn't resolve;
    :class:`OrchestratorPermissionError` on hierarchy mismatch.
    """
    task = await _check_can_set_priority(
        storage=storage,
        permissions=permissions,
        task_id=request.task_id,
        caller_agent_id=caller_agent_id,
        caller_role=caller_role,
    )
    from_priority = task["priority"]

    async with storage.transaction():
        await storage.execute(
            "UPDATE tasks SET priority = ? WHERE id = ?",
            (request.priority, request.task_id),
        )
        # Read back full row for handler output. No race-protection
        # clause needed — priority is an operator-level idempotent
        # change per CQ6 commit 7.
        updated = await storage.fetch_one(
            "SELECT * FROM tasks WHERE id = ?",
            (request.task_id,),
        )
        assert updated is not None  # row existed at gate-check time

        await write_audit(
            storage,
            actor=caller_agent_id,
            action="task.priority_change",
            target=request.task_id,
            details={
                "task_id": request.task_id,
                "from_priority": from_priority,
                "to_priority": request.priority,
            },
        )

    return SetTaskPriorityOutput(task=Task(**dict(updated)))


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
    "claim_task",
    "get_queue",
    "get_subtasks",
    "set_task_priority",
    "submit_task",
    "update_task_status",
]
