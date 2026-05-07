"""Tests for pydantic row models."""

from __future__ import annotations

from meridian_orchestrator.storage.models import (
    Agent,
    ApiUsage,
    AuditEntry,
    Company,
    Project,
    Task,
)


def test_company_minimal() -> None:
    c = Company(id="x", name="Test", created_at="2026-04-28T00:00:00Z")
    assert c.config_json is None


def test_project_defaults() -> None:
    p = Project(
        id="p",
        company_id="c",
        name="Proj",
        created_at="2026-04-28T00:00:00Z",
    )
    assert p.status == "active"
    assert p.working_directory is None


def test_agent_reports_to_optional() -> None:
    a = Agent(
        id="a",
        company_id="c",
        role="pm",
        config_path="/etc/pm.yaml",
        created_at="2026-04-28T00:00:00Z",
    )
    assert a.reports_to is None
    assert a.status == "active"


def test_task_defaults() -> None:
    t = Task(
        id="t",
        company_id="c",
        project_id="p",
        agent_role="pm",
        description="do it",
        created_at="2026-04-28T00:00:00Z",
    )
    assert t.priority == 2
    assert t.complexity == 3
    assert t.status == "pending"
    assert t.blocking_human == 0
    assert t.parent_task_id is None
    assert t.claimed_by is None
    # M4d additions + drift fix: task_type, estimated_tokens,
    # actual_tokens default to None when not supplied (nullable
    # cohort/planning fields per 0013 / 0002 respectively).
    assert t.task_type is None
    assert t.estimated_tokens is None
    assert t.actual_tokens is None


def test_task_with_all_m4d_fields() -> None:
    """Task model accepts task_type (M4d) + estimated_tokens /
    actual_tokens (0002 drift fix) when supplied. Round-trip from
    dict simulates ``fetch_one`` result after migration 0013."""
    row = {
        "id": "t",
        "company_id": "c",
        "project_id": "p",
        "parent_task_id": None,
        "agent_role": "engineer",
        "description": "ship it",
        "priority": 1,
        "complexity": 4,
        "status": "in_progress",
        "task_type": "execution",
        "blocking_human": 0,
        "estimated_tokens": 50000,
        "actual_tokens": 47213,
        "submitted_by": "human:mike",
        "claimed_by": "agent:engineer-uuid",
        "claimed_at": "2026-05-07T00:00:00Z",
        "completed_at": None,
        "notes": None,
        "created_at": "2026-05-07T00:00:00Z",
    }
    t = Task(**row)
    assert t.task_type == "execution"
    assert t.estimated_tokens == 50000
    assert t.actual_tokens == 47213


def test_api_usage_minimal() -> None:
    u = ApiUsage(
        id="u",
        timestamp="2026-04-28T00:00:00Z",
        model="claude-opus-4-7",
        input_tokens=100,
        output_tokens=50,
        window_start="2026-04-28T00:00:00Z",
    )
    assert u.cost_usd is None
    assert u.agent_id is None


def test_audit_entry_minimal() -> None:
    e = AuditEntry(
        id="a",
        timestamp="2026-04-28T00:00:00Z",
        actor="agent:pm",
        action="task.submit",
    )
    assert e.success == 1
    assert e.error_message is None
    assert e.target is None


def test_task_round_trip_from_dict() -> None:
    """Construct from dict (simulating ``fetch_one`` result)."""
    row = {
        "id": "t",
        "company_id": "c",
        "project_id": "p",
        "parent_task_id": None,
        "agent_role": "engineer",
        "description": "ship it",
        "priority": 1,
        "complexity": 4,
        "status": "claimed",
        "blocking_human": 0,
        "submitted_by": "human:mike",
        "claimed_by": "agent:engineer-uuid",
        "claimed_at": "2026-04-28T00:00:00Z",
        "completed_at": None,
        "notes": None,
        "created_at": "2026-04-28T00:00:00Z",
    }
    t = Task(**row)
    assert t.priority == 1
    assert t.status == "claimed"
    assert t.claimed_by == "agent:engineer-uuid"
