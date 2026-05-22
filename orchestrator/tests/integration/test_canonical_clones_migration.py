"""Integration-level smoke tests for 0014_canonical_clones.sql.

The unit-level migration tests at ``tests/unit/storage/test_migrations.py``
exercise the same DDL at PRAGMA-introspection granularity; these
integration-level tests exercise the migration through the same
``SqliteStorage`` + ``run_migrations`` path the production daemon uses,
asserting the visible-from-outside post-migration state matches the
Slice 1 acceptance criteria.

M5-canonical-clone-mgmt slice 1.
"""

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


async def test_canonical_clones_table_exists_post_migration(
    storage: SqliteStorage,
) -> None:
    """Post-migration smoke: the canonical_clones table is present in
    sqlite_master after run_migrations completes."""
    await run_migrations(storage)
    row = await storage.fetch_one(
        "SELECT name FROM sqlite_master "
        "WHERE type='table' AND name='canonical_clones'"
    )
    assert row is not None
    assert row["name"] == "canonical_clones"


async def test_dispatches_canonical_name_column_exists_post_migration(
    storage: SqliteStorage,
) -> None:
    """Post-migration smoke: dispatches.canonical_name is queryable
    after run_migrations completes."""
    await run_migrations(storage)
    cols = await storage.fetch_all("PRAGMA table_info(dispatches)")
    col_names = {c["name"] for c in cols}
    assert "canonical_name" in col_names


async def test_all_dispatches_indexes_present_post_migration(
    storage: SqliteStorage,
) -> None:
    """Post-migration smoke: all four dispatches indexes from
    0005_dispatches.sql remain after the 0014 rewrite. Sentinel
    against a future migration silently dropping them."""
    await run_migrations(storage)
    rows = await storage.fetch_all(
        "SELECT name FROM sqlite_master "
        "WHERE type='index' AND tbl_name='dispatches' "
        "AND name NOT LIKE 'sqlite_autoindex%'"
    )
    index_names = {r["name"] for r in rows}
    expected = {
        "idx_dispatches_task",
        "idx_dispatches_status",
        "idx_dispatches_role_status",
        "idx_dispatches_role_completed",
    }
    missing = expected - index_names
    assert not missing, f"dispatches indexes missing: {missing}"


async def test_migrations_applied_up_to_14(
    storage: SqliteStorage,
) -> None:
    """Post-migration smoke: _schema_version contains version 14 after
    run_migrations. Pins the runner's behavior of advancing through
    the 0014 file in normal greenfield startup."""
    await run_migrations(storage)
    rows = await storage.fetch_all(
        "SELECT version FROM _schema_version ORDER BY version"
    )
    versions = {r["version"] for r in rows}
    assert 14 in versions
