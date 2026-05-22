"""Forward-only migration runner.

Migrations live in ``storage/sql/`` as ``NNNN_description.sql`` files. The runner
reads ``_schema_version`` to find applied versions, then applies any pending
migrations in order in their own transactions.

Each migration is a single SQL transaction. Mid-migration crash leaves
``_schema_version`` un-updated; next startup re-runs the same migration file.
Migrations should therefore use ``CREATE TABLE IF NOT EXISTS`` or be otherwise
idempotent so re-running on partial state is safe.
"""

from __future__ import annotations

import re
from pathlib import Path

import structlog

from meridian_orchestrator.storage.interface import Storage

logger = structlog.get_logger(__name__)

_SQL_DIR = Path(__file__).parent / "sql"
_FILENAME_PATTERN = re.compile(r"^(\d{4})_\w+\.sql$")


def discover_migrations(sql_dir: Path = _SQL_DIR) -> list[tuple[int, Path]]:
    """Return ``(version, path)`` pairs for migration files, sorted by version.

    Public helper (renamed from ``_discover_migrations`` at M5-canonical-clone-
    mgmt slice 1) so tests + other callers can derive the live migration-
    version set from a single source instead of hardcoding parallel-shape
    assertions. See BACKLOG.md migration-adjacent watch-for-pattern (line 27)
    for the resolution rationale.
    """
    migrations: list[tuple[int, Path]] = []
    if not sql_dir.exists():
        return migrations
    for path in sql_dir.iterdir():
        match = _FILENAME_PATTERN.match(path.name)
        if not match:
            continue
        migrations.append((int(match.group(1)), path))
    migrations.sort(key=lambda x: x[0])
    return migrations


async def _ensure_schema_version_table(storage: Storage) -> None:
    """Bootstrap the ``_schema_version`` table outside of any migration."""
    await storage.execute(
        "CREATE TABLE IF NOT EXISTS _schema_version ("
        "version INTEGER PRIMARY KEY, "
        "applied_at TEXT NOT NULL DEFAULT (datetime('now'))"
        ")"
    )


async def _applied_versions(storage: Storage) -> set[int]:
    rows = await storage.fetch_all("SELECT version FROM _schema_version")
    return {int(row["version"]) for row in rows}


def _split_statements(sql: str) -> list[str]:
    """Split a SQL script on ``;``, after stripping line comments.

    Naive split; sufficient for our DDL which contains no semicolons inside
    string literals or procedural blocks. If that ever changes, swap for a real
    SQL parser or use ``aiosqlite.executescript`` (which has its own implicit
    transaction semantics — read carefully before switching).
    """
    cleaned: list[str] = []
    for line in sql.splitlines():
        idx = line.find("--")
        if idx >= 0:
            line = line[:idx]
        cleaned.append(line)
    return [s.strip() for s in "\n".join(cleaned).split(";") if s.strip()]


async def run_migrations(storage: Storage, sql_dir: Path | None = None) -> list[int]:
    """Run all pending migrations.

    Returns:
        The list of versions applied during this call. Empty if up to date.
    """
    if sql_dir is None:
        sql_dir = _SQL_DIR
    await _ensure_schema_version_table(storage)
    applied = await _applied_versions(storage)
    discovered = discover_migrations(sql_dir)

    newly_applied: list[int] = []
    for version, path in discovered:
        if version in applied:
            continue
        sql = path.read_text(encoding="utf-8")
        logger.info("migration.applying", version=version, path=str(path))
        async with storage.transaction():
            for statement in _split_statements(sql):
                await storage.execute(statement)
            await storage.execute(
                "INSERT INTO _schema_version (version) VALUES (?)",
                (version,),
            )
        newly_applied.append(version)
        logger.info("migration.applied", version=version)
    return newly_applied
