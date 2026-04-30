"""SQLite implementation of the Storage protocol via ``aiosqlite``."""

from __future__ import annotations

from collections.abc import AsyncIterator, Iterable, Sequence
from contextlib import AbstractAsyncContextManager, asynccontextmanager
from pathlib import Path
from typing import Any

import aiosqlite
import structlog

logger = structlog.get_logger(__name__)


class SqliteStorage:
    """Async SQLite storage with row-as-dict semantics.

    Uses ``isolation_level=None`` for explicit transaction control. The
    ``transaction()`` context manager wraps a ``BEGIN``/``COMMIT`` block; nested
    calls share the outermost transaction's lifecycle (no SAVEPOINT support in
    Phase 1 — that's tracked in BACKLOG.md).
    """

    def __init__(self, db_path: Path) -> None:
        self._db_path = db_path
        self._conn: aiosqlite.Connection | None = None
        self._in_txn = False

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
        if self._in_txn:
            yield
            return
        conn = self._require_conn()
        self._in_txn = True
        await conn.execute("BEGIN")
        try:
            yield
        except Exception:
            await conn.execute("ROLLBACK")
            raise
        else:
            await conn.execute("COMMIT")
        finally:
            self._in_txn = False
