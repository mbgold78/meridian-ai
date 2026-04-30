"""Tests for the forward-only migration runner."""

from __future__ import annotations

from collections.abc import AsyncIterator
from pathlib import Path

import pytest_asyncio

from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "test.db")
    await s.connect()
    yield s
    await s.close()


async def test_run_migrations_creates_tables(storage: SqliteStorage) -> None:
    applied = await run_migrations(storage)
    assert applied == [1]

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
        "_schema_version",
    }
    missing = expected - table_names
    assert not missing, f"Missing tables: {missing}"


async def test_run_migrations_idempotent(storage: SqliteStorage) -> None:
    first = await run_migrations(storage)
    second = await run_migrations(storage)
    assert first == [1]
    assert second == []


async def test_schema_version_records_applied(storage: SqliteStorage) -> None:
    await run_migrations(storage)
    rows = await storage.fetch_all("SELECT version FROM _schema_version ORDER BY version")
    assert [r["version"] for r in rows] == [1]


async def test_indexes_created(storage: SqliteStorage) -> None:
    await run_migrations(storage)
    rows = await storage.fetch_all(
        "SELECT name FROM sqlite_master WHERE type='index' "
        "AND name NOT LIKE 'sqlite_%' ORDER BY name"
    )
    index_names = {row["name"] for row in rows}
    expected = {
        "idx_agents_company_role",
        "idx_tasks_status_priority",
        "idx_tasks_agent_role",
        "idx_tasks_parent",
        "idx_api_usage_window",
        "idx_api_usage_agent",
        "idx_audit_timestamp",
        "idx_audit_actor",
    }
    missing = expected - index_names
    assert not missing, f"Missing indexes: {missing}"
