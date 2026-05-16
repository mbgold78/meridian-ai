"""Unit tests for :class:`bridge.canonical_registry.CanonicalCloneRegistry`.

Storage-backed registry exercising the real ``SqliteStorage`` against a
``tmp_path`` DB rather than mocking — same setup pattern as
``tests/unit/storage/test_migrations.py``. The Registry's behavioral
contract (idempotency, error mapping, ordering, no-audit-emission)
is what's under test, not the storage backend.

M5-canonical-clone-mgmt slice 1.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from pathlib import Path

import pytest
import pytest_asyncio

from meridian_orchestrator.bridge.canonical_registry import (
    CanonicalAlreadyRegisteredError,
    CanonicalCloneRegistry,
    CanonicalNotFoundError,
)
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "test.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


async def test_ensure_default_idempotent(storage: SqliteStorage) -> None:
    """Two ensure_default calls with the same args yield equivalent
    CanonicalClone objects and leave exactly one 'default' row in the
    table. Idempotency is load-bearing: main.py calls ensure_default
    on every startup."""
    registry = CanonicalCloneRegistry(storage=storage)
    first = await registry.ensure_default(
        path=Path("/tmp/canonical"),
        upstream_url="https://example.com/repo.git",
    )
    second = await registry.ensure_default(
        path=Path("/tmp/canonical"),
        upstream_url="https://example.com/repo.git",
    )

    assert first.path == second.path
    assert first.upstream_url == second.upstream_url
    assert first.default_branch == second.default_branch

    row = await storage.fetch_one(
        "SELECT COUNT(*) AS c FROM canonical_clones WHERE name = 'default'"
    )
    assert row is not None
    assert row["c"] == 1


async def test_ensure_default_returns_existing_on_second_call_even_with_different_args(
    storage: SqliteStorage,
) -> None:
    """First call wins; second call with different path/upstream
    returns the first row's values. Pins the idempotency semantic:
    ensure_default is "ensure exists", not "upsert". A future caller
    that wants to change the default's path/upstream must explicitly
    unregister + register (Slice 2+ surface)."""
    registry = CanonicalCloneRegistry(storage=storage)
    first = await registry.ensure_default(
        path=Path("/tmp/first"),
        upstream_url="https://example.com/first.git",
    )
    second = await registry.ensure_default(
        path=Path("/tmp/second"),
        upstream_url="https://example.com/second.git",
        default_branch="develop",
    )

    assert second.path == first.path == Path("/tmp/first")
    assert second.upstream_url == first.upstream_url == "https://example.com/first.git"
    assert second.default_branch == first.default_branch == "main"


async def test_get_by_name_returns_registered_clone(
    storage: SqliteStorage,
) -> None:
    registry = CanonicalCloneRegistry(storage=storage)
    await registry.register(
        name="alpha",
        path=Path("/tmp/alpha"),
        upstream_url="https://example.com/alpha.git",
    )
    got = await registry.get_by_name("alpha")
    assert got.path == Path("/tmp/alpha")
    assert got.upstream_url == "https://example.com/alpha.git"
    assert got.default_branch == "main"


async def test_get_by_name_raises_for_missing(
    storage: SqliteStorage,
) -> None:
    """Missing-name lookup raises a typed CanonicalNotFoundError with
    the spec'd error code so callers (and downstream MCP error
    mapping at Slice 3) can branch cleanly."""
    registry = CanonicalCloneRegistry(storage=storage)
    with pytest.raises(CanonicalNotFoundError) as excinfo:
        await registry.get_by_name("does-not-exist")
    assert excinfo.value.code == "CANONICAL_NOT_FOUND"


async def test_get_default_alias(storage: SqliteStorage) -> None:
    """get_default() is a thin alias around get_by_name('default')."""
    registry = CanonicalCloneRegistry(storage=storage)
    seeded = await registry.ensure_default(
        path=Path("/tmp/default"),
        upstream_url="https://example.com/default.git",
    )
    got = await registry.get_default()
    assert got.path == seeded.path
    assert got.upstream_url == seeded.upstream_url
    assert got.default_branch == seeded.default_branch


async def test_register_duplicate_name_raises(
    storage: SqliteStorage,
) -> None:
    """Re-registering an existing name raises
    CanonicalAlreadyRegisteredError (mapped from the underlying
    SQLite UNIQUE-constraint violation) with the spec'd code."""
    registry = CanonicalCloneRegistry(storage=storage)
    await registry.register(
        name="alpha",
        path=Path("/tmp/alpha"),
        upstream_url="https://example.com/alpha.git",
    )
    with pytest.raises(CanonicalAlreadyRegisteredError) as excinfo:
        await registry.register(
            name="alpha",
            path=Path("/tmp/alpha-2"),
            upstream_url="https://example.com/alpha-2.git",
        )
    assert excinfo.value.code == "CANONICAL_ALREADY_REGISTERED"


async def test_list_all_returns_in_created_at_order(
    storage: SqliteStorage,
) -> None:
    """list_all sorts by created_at ASC. SQLite's datetime('now')
    column default resolves at second granularity, so small sleeps
    between inserts are required to keep ordering deterministic."""
    registry = CanonicalCloneRegistry(storage=storage)
    await registry.register(
        name="first",
        path=Path("/tmp/first"),
        upstream_url="https://example.com/first.git",
    )
    await asyncio.sleep(0.01)
    await registry.register(
        name="second",
        path=Path("/tmp/second"),
        upstream_url="https://example.com/second.git",
    )
    await asyncio.sleep(0.01)
    await registry.register(
        name="third",
        path=Path("/tmp/third"),
        upstream_url="https://example.com/third.git",
    )

    all_clones = await registry.list_all()
    assert len(all_clones) == 3
    assert [c.path for c in all_clones] == [
        Path("/tmp/first"),
        Path("/tmp/second"),
        Path("/tmp/third"),
    ]


async def test_unregister_removes_row(storage: SqliteStorage) -> None:
    """unregister deletes the row; a subsequent get_by_name raises
    CanonicalNotFoundError."""
    registry = CanonicalCloneRegistry(storage=storage)
    await registry.register(
        name="alpha",
        path=Path("/tmp/alpha"),
        upstream_url="https://example.com/alpha.git",
    )
    await registry.unregister("alpha")
    with pytest.raises(CanonicalNotFoundError):
        await registry.get_by_name("alpha")


async def test_unregister_missing_raises(storage: SqliteStorage) -> None:
    """unregister of a non-existent name raises
    CanonicalNotFoundError (rather than silently no-op'ing) so the
    caller gets a clean signal."""
    registry = CanonicalCloneRegistry(storage=storage)
    with pytest.raises(CanonicalNotFoundError) as excinfo:
        await registry.unregister("never-existed")
    assert excinfo.value.code == "CANONICAL_NOT_FOUND"


async def test_registry_methods_emit_no_audit_events(
    storage: SqliteStorage,
) -> None:
    """Slice 1 ships the registry as non-audited (Slice 3 layers
    MCP exposure + audit emission on top). A sequence of every
    registry method must leave the audit_log row count unchanged."""
    before = await storage.fetch_one(
        "SELECT COUNT(*) AS c FROM audit_log"
    )
    assert before is not None
    baseline = before["c"]

    registry = CanonicalCloneRegistry(storage=storage)
    await registry.ensure_default(
        path=Path("/tmp/default"),
        upstream_url="https://example.com/default.git",
    )
    await registry.register(
        name="alpha",
        path=Path("/tmp/alpha"),
        upstream_url="https://example.com/alpha.git",
    )
    await registry.get_by_name("alpha")
    await registry.get_default()
    await registry.list_all()
    await registry.unregister("alpha")

    after = await storage.fetch_one(
        "SELECT COUNT(*) AS c FROM audit_log"
    )
    assert after is not None
    assert after["c"] == baseline
