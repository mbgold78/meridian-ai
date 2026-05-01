"""Tests for the forward-only migration runner."""

from __future__ import annotations

from collections.abc import AsyncIterator
from pathlib import Path

import pytest_asyncio

from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage

# Shipped migrations:
#   0001_initial.sql                          (M1)
#   0002_task_planning.sql                    (M4a)
#   0005_dispatches.sql                       (M4a)
#   0006_dispatches_last_tool_called.sql      (M4c-supervisor-base)
#   0007_dispatches_task_id_nullable.sql      (M4c-supervisor-base)
# The reservation gap (0003 ships at M5; 0004 at M6) is intentional; the
# migrations are independent so out-of-ship-order numbering is safe.
EXPECTED_VERSIONS = [1, 2, 5, 6, 7]


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "test.db")
    await s.connect()
    yield s
    await s.close()


async def test_run_migrations_creates_tables(storage: SqliteStorage) -> None:
    applied = await run_migrations(storage)
    assert applied == EXPECTED_VERSIONS

    rows = await storage.fetch_all(
        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
    )
    table_names = {row["name"] for row in rows}
    expected = {
        "companies",
        "projects",
        "agents",
        "tasks",
        "api_usage",
        "audit_log",
        "dispatches",
        "_schema_version",
    }
    missing = expected - table_names
    assert not missing, f"Missing tables: {missing}"


async def test_run_migrations_idempotent(storage: SqliteStorage) -> None:
    first = await run_migrations(storage)
    second = await run_migrations(storage)
    assert first == EXPECTED_VERSIONS
    assert second == []


async def test_schema_version_records_applied(storage: SqliteStorage) -> None:
    await run_migrations(storage)
    rows = await storage.fetch_all(
        "SELECT version FROM _schema_version ORDER BY version"
    )
    assert [r["version"] for r in rows] == EXPECTED_VERSIONS


async def test_indexes_created(storage: SqliteStorage) -> None:
    await run_migrations(storage)
    rows = await storage.fetch_all(
        "SELECT name FROM sqlite_master WHERE type='index' "
        "AND name NOT LIKE 'sqlite_%' ORDER BY name"
    )
    index_names = {row["name"] for row in rows}
    expected = {
        # 0001
        "idx_agents_company_role",
        "idx_tasks_status_priority",
        "idx_tasks_agent_role",
        "idx_tasks_parent",
        "idx_api_usage_window",
        "idx_api_usage_agent",
        "idx_audit_timestamp",
        "idx_audit_actor",
        # 0005 — dispatches indexes
        "idx_dispatches_task",
        "idx_dispatches_status",
        "idx_dispatches_role_status",
        "idx_dispatches_role_completed",
    }
    missing = expected - index_names
    assert not missing, f"Missing indexes: {missing}"


async def test_tasks_has_token_planning_columns(storage: SqliteStorage) -> None:
    """0002_task_planning.sql adds estimated_tokens and actual_tokens to tasks."""
    await run_migrations(storage)
    cols = await storage.fetch_all("PRAGMA table_info(tasks)")
    col_names = {c["name"] for c in cols}
    assert "estimated_tokens" in col_names
    assert "actual_tokens" in col_names


async def test_dispatches_columns_match_spec(storage: SqliteStorage) -> None:
    """0005_dispatches.sql columns enumerated in 11-engineer-bridge.md § Storage shape."""
    await run_migrations(storage)
    cols = await storage.fetch_all("PRAGMA table_info(dispatches)")
    col_names = {c["name"] for c in cols}
    expected = {
        "id",
        "task_id",
        "role",
        "status",
        "working_directory",
        "spec_path",
        "commit_hash",
        "decline_reason",
        "estimated_tokens",
        "actual_tokens",
        "timeout_seconds",
        "timeout_override_seconds",
        "keep_worktree_on_failure",
        "token_id",
        "subprocess_pid",
        "engineer_decisions_json",
        "issued_at",
        "started_at",
        "last_activity_at",
        "completed_at",
        "timed_out_at",
        "merge_conflict_files_json",
        "actual_touched_files_json",
        "artifacts_json",
        "test_results_json",
        "queue_items_raised_json",
        "notes",
        # 0006 — added at M4c-supervisor-base for the activity writer
        "last_tool_called",
    }
    missing = expected - col_names
    assert not missing, f"Missing columns on dispatches: {missing}"


async def test_role_completed_index_is_partial(storage: SqliteStorage) -> None:
    """idx_dispatches_role_completed has a WHERE status='completed' predicate
    so Engineer's substrate-touch-map scans only walk completed rows."""
    await run_migrations(storage)
    row = await storage.fetch_one(
        "SELECT sql FROM sqlite_master "
        "WHERE type='index' AND name='idx_dispatches_role_completed'"
    )
    assert row is not None
    assert "WHERE status='completed'" in row["sql"]
