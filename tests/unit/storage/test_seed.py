"""Tests for the initial database seed."""

from __future__ import annotations

from collections.abc import AsyncIterator
from pathlib import Path

import pytest_asyncio

from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.seed import (
    COMPANY_NAME,
    PROJECT_NAME,
    seed_initial,
)
from meridian_orchestrator.storage.sqlite import SqliteStorage


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "test.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


async def test_seed_creates_company_and_project(storage: SqliteStorage) -> None:
    company_id, project_id = await seed_initial(storage)
    assert company_id
    assert project_id

    company = await storage.fetch_one("SELECT * FROM companies WHERE id = ?", (company_id,))
    assert company is not None
    assert company["name"] == COMPANY_NAME

    project = await storage.fetch_one("SELECT * FROM projects WHERE id = ?", (project_id,))
    assert project is not None
    assert project["name"] == PROJECT_NAME
    assert project["company_id"] == company_id
    assert project["working_directory"] is not None


async def test_seed_idempotent(storage: SqliteStorage) -> None:
    cid1, pid1 = await seed_initial(storage)
    cid2, pid2 = await seed_initial(storage)
    assert cid1 == cid2
    assert pid1 == pid2

    rows = await storage.fetch_all("SELECT id FROM companies")
    assert len(rows) == 1
    rows = await storage.fetch_all("SELECT id FROM projects")
    assert len(rows) == 1
