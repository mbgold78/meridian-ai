"""Registry for the daemon's set of canonical clones.

Slice 1: storage-backed registry that lets the daemon know about more
than a single canonical clone. ensure_default() materializes the
'default' row at startup; register/unregister are private at this slice
(no MCP exposure; that's Slice 3).
"""

from __future__ import annotations

import uuid
from pathlib import Path

import structlog

from meridian_orchestrator.bridge.canonical import CanonicalClone
from meridian_orchestrator.errors import OrchestratorError
from meridian_orchestrator.storage.interface import Storage

logger = structlog.get_logger(__name__)


class CanonicalNotFoundError(OrchestratorError):
    code = "CANONICAL_NOT_FOUND"


class CanonicalAlreadyRegisteredError(OrchestratorError):
    code = "CANONICAL_ALREADY_REGISTERED"


class CanonicalCloneRegistry:
    """Storage-backed registry of canonical clones. Non-audited at Slice 1."""

    def __init__(self, storage: Storage) -> None:
        self._storage = storage

    async def ensure_default(
        self, *, path: Path, upstream_url: str, default_branch: str = "main"
    ) -> CanonicalClone:
        """Idempotent: returns 'default' row, creating it on first call."""
        row = await self._storage.fetch_one(
            "SELECT path, upstream_url, default_branch FROM canonical_clones "
            "WHERE name = 'default'"
        )
        if row is not None:
            return CanonicalClone(
                path=Path(row["path"]),
                upstream_url=row["upstream_url"],
                default_branch=row["default_branch"],
            )
        await self._storage.execute(
            "INSERT INTO canonical_clones "
            "(id, name, path, upstream_url, default_branch) "
            "VALUES (?, 'default', ?, ?, ?)",
            (str(uuid.uuid4()), str(path), upstream_url, default_branch),
        )
        return CanonicalClone(
            path=path, upstream_url=upstream_url, default_branch=default_branch
        )

    async def get_by_name(self, name: str) -> CanonicalClone:
        row = await self._storage.fetch_one(
            "SELECT path, upstream_url, default_branch FROM canonical_clones "
            "WHERE name = ?",
            (name,),
        )
        if row is None:
            raise CanonicalNotFoundError(
                f"no canonical clone registered with name={name!r}"
            )
        return CanonicalClone(
            path=Path(row["path"]),
            upstream_url=row["upstream_url"],
            default_branch=row["default_branch"],
        )

    async def get_default(self) -> CanonicalClone:
        return await self.get_by_name("default")

    async def list_all(self) -> list[CanonicalClone]:
        rows = await self._storage.fetch_all(
            "SELECT path, upstream_url, default_branch FROM canonical_clones "
            "ORDER BY created_at ASC"
        )
        return [
            CanonicalClone(
                path=Path(r["path"]),
                upstream_url=r["upstream_url"],
                default_branch=r["default_branch"],
            )
            for r in rows
        ]

    async def register(
        self,
        *,
        name: str,
        path: Path,
        upstream_url: str,
        default_branch: str = "main",
    ) -> CanonicalClone:
        try:
            await self._storage.execute(
                "INSERT INTO canonical_clones "
                "(id, name, path, upstream_url, default_branch) "
                "VALUES (?, ?, ?, ?, ?)",
                (str(uuid.uuid4()), name, str(path), upstream_url, default_branch),
            )
        except Exception as exc:
            if "UNIQUE" in str(exc) or "unique" in str(exc):
                raise CanonicalAlreadyRegisteredError(
                    f"canonical clone {name!r} is already registered"
                ) from exc
            raise
        return CanonicalClone(
            path=path, upstream_url=upstream_url, default_branch=default_branch
        )

    async def unregister(self, name: str) -> None:
        existing = await self._storage.fetch_one(
            "SELECT id FROM canonical_clones WHERE name = ?", (name,)
        )
        if existing is None:
            raise CanonicalNotFoundError(
                f"no canonical clone registered with name={name!r}"
            )
        await self._storage.execute(
            "DELETE FROM canonical_clones WHERE name = ?", (name,)
        )
