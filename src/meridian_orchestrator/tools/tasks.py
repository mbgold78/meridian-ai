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

from typing import Literal

from pydantic import BaseModel, Field

from meridian_orchestrator.errors import PermissionError as OrchestratorPermissionError
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


__all__ = [
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
]
