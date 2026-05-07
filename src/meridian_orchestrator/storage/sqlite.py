"""SQLite implementation of the Storage protocol via ``aiosqlite``."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Iterable, Sequence
from contextlib import AbstractAsyncContextManager, asynccontextmanager
from contextvars import ContextVar
from pathlib import Path
from typing import Any

import aiosqlite
import structlog

logger = structlog.get_logger(__name__)


# Module-level ContextVar for per-asyncio-task transaction reentrancy
# tracking. Replaces the M1-shipped instance-level boolean ``_in_txn``
# which leaked across concurrent MCP requests on the shared SqliteStorage
# instance — Codex finding 2 from M5-subscription-routing round 1,
# addressed at M5-flag-flips F2 contextvars fix (2026-05-07).
#
# Module-level (not instance-level) per Python docs guidance: "Context
# Variables should be created at the top module level." Phase 1 storage
# is a singleton in production; multi-instance scenarios are theoretical.
# ``default=False`` ensures every fresh asyncio task sees "not in
# transaction" initially, matching the M1 boolean's default.
_IN_TXN: ContextVar[bool] = ContextVar("storage._in_txn", default=False)


class SqliteStorage:
    """Async SQLite storage with row-as-dict semantics.

    Uses ``isolation_level=None`` for explicit transaction control. The
    ``transaction()`` context manager wraps a ``BEGIN``/``COMMIT`` block; nested
    calls share the outermost transaction's lifecycle (no SAVEPOINT support in
    Phase 1 — that's tracked in BACKLOG.md).

    **Concurrent transaction safety** (M5-flag-flips F2): per-task
    reentrancy state is tracked via the module-level ``_IN_TXN``
    ContextVar; the BEGIN/COMMIT envelope on the single shared aiosqlite
    connection is serialized via ``self._txn_lock`` (asyncio.Lock).
    Without the Lock, concurrent tasks would issue overlapping BEGINs on
    the same connection and trigger SQLite "cannot start transaction
    within a transaction" errors. Lock acquisition MUST precede the
    ContextVar set so a task waiting on the Lock observes accurate
    reentrancy state during its wait window.
    """

    def __init__(self, db_path: Path) -> None:
        self._db_path = db_path
        self._conn: aiosqlite.Connection | None = None
        self._txn_lock: asyncio.Lock = asyncio.Lock()

    async def connect(self) -> None:
        """Open the connection. Idempotent."""
        if self._conn is not None:
            return
        self._db_path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = await aiosqlite.connect(self._db_path, isolation_level=None)
        self._conn.row_factory = aiosqlite.Row
        await self._conn.execute("PRAGMA foreign_keys = ON")
        await self._conn.execute("PRAGMA journal_mode = WAL")
        logger.info("storage.connected", db_path=str(self._db_path))

    async def close(self) -> None:
        """Close the connection. Idempotent."""
        if self._conn is None:
            return
        await self._conn.close()
        self._conn = None
        logger.info("storage.closed")

    def _require_conn(self) -> aiosqlite.Connection:
        if self._conn is None:
            raise RuntimeError("Storage not connected; call connect() first")
        return self._conn

    async def execute(self, query: str, params: Sequence[Any] | None = None) -> None:
        conn = self._require_conn()
        await conn.execute(query, params or ())

    async def executemany(self, query: str, params_seq: Iterable[Sequence[Any]]) -> None:
        conn = self._require_conn()
        await conn.executemany(query, params_seq)

    async def fetch_one(
        self, query: str, params: Sequence[Any] | None = None
    ) -> dict[str, Any] | None:
        conn = self._require_conn()
        async with conn.execute(query, params or ()) as cursor:
            row = await cursor.fetchone()
            return dict(row) if row is not None else None

    async def fetch_all(
        self, query: str, params: Sequence[Any] | None = None
    ) -> list[dict[str, Any]]:
        conn = self._require_conn()
        async with conn.execute(query, params or ()) as cursor:
            rows = await cursor.fetchall()
            return [dict(r) for r in rows]

    def transaction(self) -> AbstractAsyncContextManager[None]:
        """Return an async context manager that wraps a transaction.

        Commits on clean exit, rolls back on exception. Reentrant: nested calls
        share the outermost transaction.
        """
        return self._transaction()

    @asynccontextmanager
    async def _transaction(self) -> AsyncIterator[None]:
        # Reentrancy: per-task ContextVar means concurrent tasks see
        # independent state; nested calls within a single task share the
        # outermost envelope. This early-return path preserves the
        # existing reentrancy contract (helpers like
        # ``bridge.activity.record_oauth_pm_auto_flip`` and
        # ``record_api_call`` rely on inner transactions being no-ops
        # when wrapped by an outer envelope).
        if _IN_TXN.get():
            yield
            return
        conn = self._require_conn()
        # Ordering rationale (M5-flag-flips F2 contextvars fix, 2026-05-07):
        # Lock acquisition MUST precede the ContextVar set. Setting
        # _IN_TXN before lock acquisition would mark the task as "in
        # transaction" while it is still waiting on the connection-
        # envelope lock — semantically incorrect (no BEGIN issued yet)
        # and could mis-route a nested invocation arriving during the
        # wait window. Acquire envelope-serialization first; mark
        # transaction state second; issue BEGIN third. Reset the
        # ContextVar in finally so it clears even if BEGIN itself
        # raises (latent bug in the M1 boolean shape: BEGIN-fail left
        # ``_in_txn=True`` permanently because the finally clause did
        # not include the BEGIN call).
        async with self._txn_lock:
            token = _IN_TXN.set(True)
            try:
                await conn.execute("BEGIN")
                try:
                    yield
                except Exception:
                    await conn.execute("ROLLBACK")
                    raise
                else:
                    await conn.execute("COMMIT")
            finally:
                _IN_TXN.reset(token)
