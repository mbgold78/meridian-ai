"""Tests for SqliteStorage operations."""

from __future__ import annotations

import uuid
from collections.abc import AsyncIterator
from pathlib import Path

import aiosqlite
import pytest
import pytest_asyncio

from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "test.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


async def test_connect_creates_db_file(tmp_path: Path) -> None:
    db_path = tmp_path / "subdir" / "test.db"
    s = SqliteStorage(db_path)
    await s.connect()
    try:
        assert db_path.exists()
    finally:
        await s.close()


async def test_connect_idempotent(storage: SqliteStorage) -> None:
    await storage.connect()  # already connected; second call is a no-op


async def test_close_idempotent(tmp_path: Path) -> None:
    s = SqliteStorage(tmp_path / "test.db")
    await s.connect()
    await s.close()
    await s.close()


async def test_company_round_trip(storage: SqliteStorage) -> None:
    cid = str(uuid.uuid4())
    await storage.execute(
        "INSERT INTO companies (id, name) VALUES (?, ?)",
        (cid, "Test Co"),
    )
    row = await storage.fetch_one("SELECT * FROM companies WHERE id = ?", (cid,))
    assert row is not None
    assert row["id"] == cid
    assert row["name"] == "Test Co"
    assert row["created_at"] is not None


async def test_fetch_one_returns_none_when_no_match(storage: SqliteStorage) -> None:
    row = await storage.fetch_one("SELECT * FROM companies WHERE id = ?", ("nonexistent",))
    assert row is None


async def test_fetch_all_returns_empty_list_when_no_match(storage: SqliteStorage) -> None:
    rows = await storage.fetch_all("SELECT * FROM companies")
    assert rows == []


async def test_executemany(storage: SqliteStorage) -> None:
    cid = str(uuid.uuid4())
    await storage.execute("INSERT INTO companies (id, name) VALUES (?, ?)", (cid, "C"))
    p1 = str(uuid.uuid4())
    p2 = str(uuid.uuid4())
    await storage.executemany(
        "INSERT INTO projects (id, company_id, name) VALUES (?, ?, ?)",
        [(p1, cid, "Project A"), (p2, cid, "Project B")],
    )
    rows = await storage.fetch_all(
        "SELECT name FROM projects WHERE company_id = ? ORDER BY name", (cid,)
    )
    assert [r["name"] for r in rows] == ["Project A", "Project B"]


async def test_transaction_commits_on_success(storage: SqliteStorage) -> None:
    cid = str(uuid.uuid4())
    async with storage.transaction():
        await storage.execute(
            "INSERT INTO companies (id, name) VALUES (?, ?)",
            (cid, "TxCommit"),
        )
    row = await storage.fetch_one("SELECT id FROM companies WHERE id = ?", (cid,))
    assert row is not None


async def test_transaction_rolls_back_on_exception(storage: SqliteStorage) -> None:
    cid = str(uuid.uuid4())
    with pytest.raises(RuntimeError, match="force rollback"):
        async with storage.transaction():
            await storage.execute(
                "INSERT INTO companies (id, name) VALUES (?, ?)",
                (cid, "TxRollback"),
            )
            raise RuntimeError("force rollback")
    row = await storage.fetch_one("SELECT id FROM companies WHERE id = ?", (cid,))
    assert row is None


async def test_foreign_keys_enforced(storage: SqliteStorage) -> None:
    """``PRAGMA foreign_keys = ON`` is active; FK violations raise."""
    with pytest.raises(aiosqlite.IntegrityError):
        await storage.execute(
            "INSERT INTO projects (id, company_id, name) VALUES (?, ?, ?)",
            (str(uuid.uuid4()), "nonexistent-company", "Orphan"),
        )


async def test_execute_without_connect_raises(tmp_path: Path) -> None:
    s = SqliteStorage(tmp_path / "never-connected.db")
    with pytest.raises(RuntimeError, match="not connected"):
        await s.execute("SELECT 1")


async def test_nested_transaction_shares_outer(storage: SqliteStorage) -> None:
    cid = str(uuid.uuid4())
    # The nesting is the point of this test — reentrant transaction behavior.
    # Don't collapse into a single `with`.
    async with storage.transaction():  # noqa: SIM117
        async with storage.transaction():
            await storage.execute(
                "INSERT INTO companies (id, name) VALUES (?, ?)",
                (cid, "Nested"),
            )
    row = await storage.fetch_one("SELECT id FROM companies WHERE id = ?", (cid,))
    assert row is not None


async def test_nested_transaction_rolls_back_with_outer(
    storage: SqliteStorage,
) -> None:
    """Reentrant-transaction contract: when an exception escapes the
    outer ``async with storage.transaction():`` block, writes from
    BOTH the outer and the inner (nested) transactions roll back.

    The inner transaction's ``async with`` is a no-op (yields without
    BEGIN/COMMIT) because the outer transaction is already active;
    its writes share the outer's commit/rollback lifecycle. A
    rollback at the outer level must therefore unwind everything
    written inside both contexts.

    Failure mode this test prevents: a future change that adds
    independent SAVEPOINT-style rollback to the nested context
    (e.g., to support partial rollback) would break the current
    flat-transaction contract that ``bridge.activity.record_oauth_pm_auto_flip``
    + ``server._observe_caller`` rely on for atomic fan-out across
    the activity update + auto-flip writes.
    """
    cid_outer = str(uuid.uuid4())
    cid_inner = str(uuid.uuid4())
    with pytest.raises(RuntimeError, match="force outer rollback"):
        async with storage.transaction():
            await storage.execute(
                "INSERT INTO companies (id, name) VALUES (?, ?)",
                (cid_outer, "OuterRollback"),
            )
            async with storage.transaction():
                await storage.execute(
                    "INSERT INTO companies (id, name) VALUES (?, ?)",
                    (cid_inner, "InnerRollback"),
                )
            raise RuntimeError("force outer rollback")

    # Both rows must be absent — outer rollback unwinds the inner
    # writes too, since the inner share the outer's transaction.
    outer_row = await storage.fetch_one(
        "SELECT id FROM companies WHERE id = ?", (cid_outer,)
    )
    inner_row = await storage.fetch_one(
        "SELECT id FROM companies WHERE id = ?", (cid_inner,)
    )
    assert outer_row is None, (
        "outer transaction's INSERT persisted despite rollback; "
        "the transaction context manager's exception path is broken."
    )
    assert inner_row is None, (
        "inner transaction's INSERT persisted despite outer rollback; "
        "reentrant-transaction contract is broken — inner writes "
        "must share the outer's commit/rollback lifecycle. Without "
        "this, ``server._observe_caller``'s atomic fan-out across "
        "``record_mcp_call`` + ``record_oauth_pm_auto_flip`` cannot "
        "guarantee both-or-neither rollback semantics."
    )


async def test_nested_transaction_inner_exception_propagates_out(
    storage: SqliteStorage,
) -> None:
    """Symmetric to the nested-rollback test but with the exception
    raised inside the inner block. The inner ``async with`` is a
    no-op so the exception propagates through the inner context
    manager unchanged, hits the outer's ``except`` branch, triggers
    ROLLBACK at the outer level. All writes — outer and inner —
    must roll back together."""
    cid_outer = str(uuid.uuid4())
    cid_inner = str(uuid.uuid4())
    with pytest.raises(RuntimeError, match="force inner rollback"):
        async with storage.transaction():
            await storage.execute(
                "INSERT INTO companies (id, name) VALUES (?, ?)",
                (cid_outer, "OuterFromInner"),
            )
            async with storage.transaction():
                await storage.execute(
                    "INSERT INTO companies (id, name) VALUES (?, ?)",
                    (cid_inner, "InnerThenRaise"),
                )
                raise RuntimeError("force inner rollback")

    outer_row = await storage.fetch_one(
        "SELECT id FROM companies WHERE id = ?", (cid_outer,)
    )
    inner_row = await storage.fetch_one(
        "SELECT id FROM companies WHERE id = ?", (cid_inner,)
    )
    assert outer_row is None
    assert inner_row is None
