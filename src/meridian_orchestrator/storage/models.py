"""Pydantic row models matching the SQLite schema.

These mirror the schema in ``storage/sql/0001_initial.sql``. Used for type-safe
row construction from dict-row results (see ``SqliteStorage.fetch_one``) and for
serialization in MCP tool outputs.
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel

TaskStatus = Literal[
    "pending",
    "claimed",
    "in_progress",
    "completed",
    "failed",
    "declined",
    "cancelled",
]


class Company(BaseModel):
    id: str
    name: str
    config_json: str | None = None
    created_at: str


class Project(BaseModel):
    id: str
    company_id: str
    name: str
    working_directory: str | None = None
    status: str = "active"
    created_at: str


class Agent(BaseModel):
    id: str
    company_id: str
    role: str
    config_path: str
    reports_to: str | None = None
    status: str = "active"
    created_at: str


class Task(BaseModel):
    id: str
    company_id: str
    project_id: str
    parent_task_id: str | None = None
    agent_role: str
    description: str
    priority: int = 2
    complexity: int = 3
    status: TaskStatus = "pending"
    blocking_human: int = 0
    submitted_by: str | None = None
    claimed_by: str | None = None
    claimed_at: str | None = None
    completed_at: str | None = None
    notes: str | None = None
    created_at: str


class ApiUsage(BaseModel):
    id: str
    timestamp: str
    agent_id: str | None = None
    model: str
    input_tokens: int
    output_tokens: int
    cost_usd: float | None = None
    task_id: str | None = None
    window_start: str


class AuditEntry(BaseModel):
    id: str
    timestamp: str
    actor: str
    action: str
    target: str | None = None
    details_json: str | None = None
    success: int = 1
    error_message: str | None = None
