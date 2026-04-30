"""Storage backend protocol.

Implementations: ``SqliteStorage`` (Phase 1). Future: ``PostgresStorage`` post-Phase-2.
The Protocol is intentionally generic — domain-specific repositories are thin layers
added in later milestones as their tools land.
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from contextlib import AbstractAsyncContextManager
from typing import Any, Protocol, runtime_checkable


@runtime_checkable
class Storage(Protocol):
    """Async storage interface."""

    async def connect(self) -> None:
        """Open the connection. Idempotent."""
        ...

    async def close(self) -> None:
        """Close the connection. Idempotent."""
        ...

    async def execute(self, query: str, params: Sequence[Any] | None = None) -> None:
        """Execute a single statement (INSERT/UPDATE/DELETE/DDL)."""
        ...

    async def executemany(self, query: str, params_seq: Iterable[Sequence[Any]]) -> None:
        """Execute a single statement repeatedly with different parameter rows."""
        ...

    async def fetch_one(
        self, query: str, params: Sequence[Any] | None = None
    ) -> dict[str, Any] | None:
        """Fetch a single row as a dict; ``None`` if no rows match."""
        ...

    async def fetch_all(
        self, query: str, params: Sequence[Any] | None = None
    ) -> list[dict[str, Any]]:
        """Fetch all matching rows as a list of dicts."""
        ...

    def transaction(self) -> AbstractAsyncContextManager[None]:
        """Async context manager wrapping a transaction.

        Commits on clean exit, rolls back on exception. Nested calls share the
        outermost transaction's commit/rollback.
        """
        ...
