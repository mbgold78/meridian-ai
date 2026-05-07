"""Direct schema tests for ``tools.tasks`` module skeleton.

M4d-task-management-surface commit 2 ships the Pydantic schema surface
for the six task-management MCP tools + the flat-allowlist
``_require_role`` helper. This file exercises the schemas and helper
in isolation; handler tests land at commits 3-7 in this same file as
each handler ships.

Coverage at commit 2 (per CQ-series dispositions, 2026-05-07):

- 3 schema smokes (non-trivial schemas with defaults / typed unions).
- 3 schema boundary validation tests (Pydantic Field constraints +
  Literal membership at API boundary).
- 1 ``_require_role`` test (positive + negative paths).

Output models (``SubmitTaskOutput``, ``ClaimTaskOutput``, etc.) are
not tested at this commit per CQ5: each is a ``task: Task`` wrapper;
coverage is redundant with
``tests/unit/storage/test_models.py:test_task_with_all_m4d_fields``
from commit 1.

Trivial input schemas (``ClaimTaskInput``, ``GetSubtasksInput``) are
not smoke-tested per the same scope-cap discipline — Pydantic
structural validation suffices for single-string-field models.
"""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from meridian_orchestrator.errors import PermissionError as OrchestratorPermissionError
from meridian_orchestrator.tools.tasks import (
    GetQueueInput,
    SetTaskPriorityInput,
    SubmitTaskInput,
    UpdateTaskStatusInput,
    _require_role,
)


def test_submit_task_input_defaults() -> None:
    """``SubmitTaskInput`` minimal construction: priority=2, complexity=3,
    parent_task_id=None, task_type=None per spec defaults."""
    inp = SubmitTaskInput(agent_role="engineer", description="ship X")
    assert inp.priority == 2
    assert inp.complexity == 3
    assert inp.parent_task_id is None
    assert inp.task_type is None


def test_get_queue_input_defaults() -> None:
    """``GetQueueInput`` minimal construction: status=None (no filter),
    agent_role=None, project_id=None, limit=50, offset=0 per spec."""
    inp = GetQueueInput()
    assert inp.status is None
    assert inp.agent_role is None
    assert inp.project_id is None
    assert inp.limit == 50
    assert inp.offset == 0


def test_update_task_status_input_literal_accepts_six_values() -> None:
    """``UpdateTaskStatusInput.status`` Literal walks the six valid
    transition statuses; ``'pending'`` rejects (no transition TO pending
    per ``docs/kickoff/05-data-model.md`` state machine)."""
    valid = [
        "claimed",
        "in_progress",
        "completed",
        "failed",
        "declined",
        "cancelled",
    ]
    for status in valid:
        UpdateTaskStatusInput(task_id="t1", status=status)  # type: ignore[arg-type]

    with pytest.raises(ValidationError):
        UpdateTaskStatusInput(task_id="t1", status="pending")  # type: ignore[arg-type]
    with pytest.raises(ValidationError):
        UpdateTaskStatusInput(task_id="t1", status="bogus")  # type: ignore[arg-type]


def test_priority_complexity_bounds_validation() -> None:
    """``Field(ge=1, le=5)`` enforces priority and complexity bounds at
    Pydantic boundary; out-of-range values raise ``ValidationError``.
    Covers ``SubmitTaskInput`` AND ``SetTaskPriorityInput`` priority
    Field."""
    # In-bounds: edges accepted.
    SubmitTaskInput(
        agent_role="engineer", description="x", priority=1, complexity=1
    )
    SubmitTaskInput(
        agent_role="engineer", description="x", priority=5, complexity=5
    )
    SetTaskPriorityInput(task_id="t1", priority=1)
    SetTaskPriorityInput(task_id="t1", priority=5)

    # Out-of-bounds: reject.
    for bad in (0, 6, -1, 100):
        with pytest.raises(ValidationError):
            SubmitTaskInput(
                agent_role="engineer", description="x", priority=bad
            )
        with pytest.raises(ValidationError):
            SubmitTaskInput(
                agent_role="engineer", description="x", complexity=bad
            )
        with pytest.raises(ValidationError):
            SetTaskPriorityInput(task_id="t1", priority=bad)


def test_get_queue_limit_offset_bounds_validation() -> None:
    """``GetQueueInput`` limit ``Field(ge=1, le=500)`` and offset
    ``Field(ge=0)`` enforce CQ8 disposition cap at 500 and non-negative
    offset."""
    # In-bounds.
    GetQueueInput(limit=1, offset=0)
    GetQueueInput(limit=500, offset=1000)

    # Out-of-bounds.
    with pytest.raises(ValidationError):
        GetQueueInput(limit=0)
    with pytest.raises(ValidationError):
        GetQueueInput(limit=501)
    with pytest.raises(ValidationError):
        GetQueueInput(offset=-1)


def test_get_queue_status_filter_validates_taskstatus_values() -> None:
    """``GetQueueInput.status``: ``list[TaskStatus] | None`` — Pydantic
    Literal membership validates at API boundary per CQ2. Tightens
    spec's ``list[str]`` to typed enum; unknown statuses reject."""
    # All seven TaskStatus values accepted.
    inp = GetQueueInput(
        status=[
            "pending",
            "claimed",
            "in_progress",
            "completed",
            "failed",
            "declined",
            "cancelled",
        ]
    )
    assert inp.status is not None
    assert len(inp.status) == 7

    # Subset filter accepted.
    inp = GetQueueInput(status=["pending", "claimed"])
    assert inp.status == ["pending", "claimed"]

    # Unknown status rejects.
    with pytest.raises(ValidationError):
        GetQueueInput(status=["bogus"])  # type: ignore[list-item]


def test_require_role_allows_listed_rejects_unlisted() -> None:
    """``_require_role`` flat-allowlist primitive: ``caller_role`` in
    ``*allowed`` passes; otherwise raises
    ``OrchestratorPermissionError``. Mirrors
    ``tools/dispatch.py:_require_role`` exactly; per-tool hierarchy
    helpers land at commits 3-7 alongside their handlers."""
    # Listed role passes.
    _require_role("pm", "pm", "engineer")
    _require_role("engineer", "pm", "engineer")

    # Unlisted role rejects.
    with pytest.raises(OrchestratorPermissionError):
        _require_role("implementer", "pm", "engineer")
    with pytest.raises(OrchestratorPermissionError):
        _require_role("tester", "pm", "engineer")

    # Empty allowlist rejects everything.
    with pytest.raises(OrchestratorPermissionError):
        _require_role("pm")
