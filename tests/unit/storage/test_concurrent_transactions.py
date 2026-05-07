"""Concurrent transaction safety tests for SqliteStorage.

Exercises the M5-flag-flips F2 contextvars fix (2026-05-07): per-task
reentrancy isolation via ``_IN_TXN`` ContextVar plus connection-envelope
serialization via ``_txn_lock`` asyncio.Lock. Without these, the M1-shipped
shared boolean ``_in_txn`` leaked transactional state across concurrent
MCP requests on the same SqliteStorage instance.

Failure mode under the M1 bug:
  Task A enters transaction(), sets _in_txn=True, awaits BEGIN.
  Task B enters transaction(), sees _in_txn=True (A's flag), takes the
  no-op early-return path; B's writes execute on the connection while
  A's BEGIN is still active. A's writes and B's writes share A's
  envelope. Rollback in A discards B's writes too.

Tests below exercise:
  1. Concurrent isolation — A's rollback does NOT discard B's writes
  2. Concurrent serialization — multiple transactions complete cleanly
  3. Same-coroutine reentrancy — nested transaction() is still a no-op
  4. Concurrent + reentrant — A's nested + B's nested don't cross-leak
  5. ContextVar token reset — _IN_TXN cleared after exception in body

The fixtures here use the same SqliteStorage + run_migrations pattern
as ``tests/unit/storage/test_sqlite.py``; the integration-level Reading
C+ test exercising real MCP handlers under concurrent shape lives at
``tests/integration/test_concurrent_handler_transactions.py``.
"""

from __future__ import annotations

import asyncio
import uuid
from collections.abc import AsyncIterator
from pathlib import Path

import pytest
import pytest_asyncio

from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import _IN_TXN, SqliteStorage


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "test.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


async def test_concurrent_transactions_isolate_writes(
    storage: SqliteStorage,
) -> None:
    """Concurrent isolation: rollback in Task A does not discard Task B's writes.

    Sentinel shape: A starts a transaction, signals via asyncio.Event,
    yields control to let B begin its transaction during A's envelope.
    B blocks on _txn_lock with the fix; runs after A rolls back; B's
    commit lands. Without the fix (M1 shared boolean), B's INSERT lands
    inside A's envelope and disappears with A's rollback.

    **Do not "clean up" the asyncio.Event coordination or asyncio.sleep
    yield points** — they force the bug's race window to materialize.
    With sequential test patterns alone, B's transaction would never
    begin during A's in-flight envelope and the bug would never surface.
    Cheap defensive documentation per CQ3 refinement, 2026-05-07.

    Failure assertion under the bug: B's row absent. Message names the
    contract being violated.
    """
    cid_a = str(uuid.uuid4())
    cid_b = str(uuid.uuid4())

    a_started = asyncio.Event()
    a_can_rollback = asyncio.Event()

    async def task_a() -> None:
        try:
            async with storage.transaction():
                await storage.execute(
                    "INSERT INTO companies (id, name) VALUES (?, ?)",
                    (cid_a, "TaskA"),
                )
                a_started.set()
                # Yield event loop to let task_b begin its transaction
                # attempt. Without this deliberate await, the test
                # cannot exercise the concurrent-in-flight race window.
                # Do NOT remove.
                await a_can_rollback.wait()
                raise RuntimeError("force task_a rollback")
        except RuntimeError:
            pass

    async def task_b() -> None:
        await a_started.wait()
        async with storage.transaction():
            await storage.execute(
                "INSERT INTO companies (id, name) VALUES (?, ?)",
                (cid_b, "TaskB"),
            )

    a = asyncio.create_task(task_a())
    b = asyncio.create_task(task_b())
    # Let task_b queue up on the lock, then signal task_a to roll back.
    await a_started.wait()
    await asyncio.sleep(0.01)
    a_can_rollback.set()
    await asyncio.gather(a, b)

    row_a = await storage.fetch_one(
        "SELECT id FROM companies WHERE id = ?", (cid_a,)
    )
    row_b = await storage.fetch_one(
        "SELECT id FROM companies WHERE id = ?", (cid_b,)
    )
    assert row_a is None, "Task A's INSERT persisted despite rollback"
    assert row_b is not None, (
        "Task B's INSERT was discarded by Task A's rollback — "
        "reentrancy flag false-shared across concurrent tasks. "
        "Reentrancy must be per-task via _IN_TXN ContextVar so B's "
        "transaction is independent of A's envelope."
    )


async def test_concurrent_transactions_serialize_envelopes(
    storage: SqliteStorage,
) -> None:
    """Multiple concurrent transactions complete cleanly; all writes
    visible post-commit.

    Validates the asyncio.Lock serializes the BEGIN/COMMIT envelope on
    the single shared connection without errors. Without the Lock,
    overlapping BEGINs would trigger SQLite's "cannot start transaction
    within a transaction" error on the second-arriving task.
    """
    cids = [str(uuid.uuid4()) for _ in range(5)]

    async def insert(cid: str, name: str) -> None:
        async with storage.transaction():
            await storage.execute(
                "INSERT INTO companies (id, name) VALUES (?, ?)",
                (cid, name),
            )

    await asyncio.gather(
        *[insert(cid, f"Task{i}") for i, cid in enumerate(cids)]
    )

    rows = await storage.fetch_all(
        "SELECT id FROM companies WHERE id IN (?, ?, ?, ?, ?)", cids
    )
    assert len(rows) == 5


async def test_reentrant_transaction_inside_outer_is_noop(
    storage: SqliteStorage,
) -> None:
    """Same-coroutine nested transaction() is a no-op for the inner; the
    outer envelope owns BEGIN/COMMIT.

    Regression guard: future changes to the ContextVar shape must not
    break the M1 reentrancy contract that helpers like
    ``bridge.activity.record_oauth_pm_auto_flip`` rely on for atomic
    fan-out across multiple writes.
    """
    cid = str(uuid.uuid4())
    assert _IN_TXN.get() is False
    async with storage.transaction():
        # Inside outer: _IN_TXN.get() returns True for THIS task only.
        assert _IN_TXN.get() is True
        async with storage.transaction():
            # Inner sees True, yields without nested BEGIN
            await storage.execute(
                "INSERT INTO companies (id, name) VALUES (?, ?)",
                (cid, "Reentrant"),
            )
    # Post-outer: ContextVar reset to its prior value (False)
    assert _IN_TXN.get() is False
    row = await storage.fetch_one(
        "SELECT id FROM companies WHERE id = ?", (cid,)
    )
    assert row is not None


async def test_concurrent_reentrant_does_not_leak_across_tasks(
    storage: SqliteStorage,
) -> None:
    """Load-bearing combination: concurrent tasks each doing nested
    transaction() must not cross-leak reentrancy state.

    Failure shape combining concurrency + reentrancy: under the M1
    boolean, Task A's outer transaction sets _in_txn=True; Task B sees
    True (A's flag) and treats its outer transaction as a no-op (nested);
    B's writes land inside A's envelope. With the ContextVar fix, B's
    _IN_TXN.get() returns False (B's per-task default); B issues its own
    BEGIN, blocks on the Lock until A finishes, then runs cleanly.

    Sentinel shape: A nests; A rolls back; B nests; B commits. Verify
    A's outer + inner are absent (A's rollback) AND B's outer + inner
    are present (B's commit, independent of A).
    """
    a_outer = str(uuid.uuid4())
    a_inner = str(uuid.uuid4())
    b_outer = str(uuid.uuid4())
    b_inner = str(uuid.uuid4())

    a_started = asyncio.Event()
    a_can_rollback = asyncio.Event()

    async def task_a() -> None:
        try:
            async with storage.transaction():
                await storage.execute(
                    "INSERT INTO companies (id, name) VALUES (?, ?)",
                    (a_outer, "A_outer"),
                )
                async with storage.transaction():
                    await storage.execute(
                        "INSERT INTO companies (id, name) VALUES (?, ?)",
                        (a_inner, "A_inner"),
                    )
                a_started.set()
                await a_can_rollback.wait()
                raise RuntimeError("force task_a rollback")
        except RuntimeError:
            pass

    async def task_b() -> None:
        await a_started.wait()
        async with storage.transaction():
            await storage.execute(
                "INSERT INTO companies (id, name) VALUES (?, ?)",
                (b_outer, "B_outer"),
            )
            async with storage.transaction():
                await storage.execute(
                    "INSERT INTO companies (id, name) VALUES (?, ?)",
                    (b_inner, "B_inner"),
                )

    a = asyncio.create_task(task_a())
    b = asyncio.create_task(task_b())
    await a_started.wait()
    await asyncio.sleep(0.01)
    a_can_rollback.set()
    await asyncio.gather(a, b)

    rows = await storage.fetch_all(
        "SELECT id FROM companies WHERE id IN (?, ?, ?, ?)",
        (a_outer, a_inner, b_outer, b_inner),
    )
    persisted = {r["id"] for r in rows}
    assert a_outer not in persisted, "Task A outer leaked across rollback"
    assert a_inner not in persisted, "Task A inner leaked across rollback"
    assert b_outer in persisted, (
        "Task B outer rolled back with Task A — reentrancy flag "
        "false-shared across concurrent tasks despite both being in "
        "nested transactions. ContextVar must isolate per-task."
    )
    assert b_inner in persisted, "Task B inner rolled back with Task A"


async def test_transaction_token_reset_on_exception(
    storage: SqliteStorage,
) -> None:
    """ContextVar token reset fires in finally even when exceptions
    propagate.

    Sentinel shape: an exception inside the transaction body must
    trigger ROLLBACK and reset _IN_TXN to its prior value (False), so
    subsequent transaction() calls in the same task see _IN_TXN.get()
    == False and issue fresh BEGINs.

    Regression guard: future changes to the finally-clause structure
    must keep the reset call present and reachable on every exit path.
    """
    assert _IN_TXN.get() is False
    cid = str(uuid.uuid4())
    with pytest.raises(RuntimeError, match="exception"):
        async with storage.transaction():
            await storage.execute(
                "INSERT INTO companies (id, name) VALUES (?, ?)",
                (cid, "Cleared"),
            )
            raise RuntimeError("exception inside transaction")
    # Post-exception: ContextVar must be reset to False.
    assert _IN_TXN.get() is False, (
        "ContextVar not reset after exception inside transaction body. "
        "Subsequent transactions in this task would treat themselves as "
        "nested and skip BEGIN, silently disabling transactional safety."
    )
    # Subsequent transaction issues a fresh BEGIN cleanly.
    cid2 = str(uuid.uuid4())
    async with storage.transaction():
        await storage.execute(
            "INSERT INTO companies (id, name) VALUES (?, ?)",
            (cid2, "AfterReset"),
        )
    row = await storage.fetch_one(
        "SELECT id FROM companies WHERE id = ?", (cid2,)
    )
    assert row is not None
