"""Integration test exercising the daemon's startup/shutdown lifecycle.

The full ``main()`` adds OS signal-handler wiring on top of ``_lifespan``. Signal
delivery on Windows requires console-event mechanics that don't lend themselves
to subprocess testing, so this test exercises ``_lifespan`` directly — which is
where migrations run, storage opens, and the shutdown path closes cleanly. The
signal-handler glue is exercised manually by Mike (Ctrl+C in an interactive
``uv run python -m meridian_orchestrator.main``).
"""

from __future__ import annotations

from pathlib import Path

from meridian_orchestrator.config import Settings
from meridian_orchestrator.main import _lifespan


async def test_lifespan_runs_migrations_and_closes(tmp_path: Path) -> None:
    """The lifespan context manager opens storage, runs migrations, closes cleanly."""
    settings = Settings(data_dir=tmp_path)
    async with _lifespan(settings) as storage:
        # Inside the lifespan: storage is connected and migrations are applied.
        rows = await storage.fetch_all(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
        )
        table_names = {row["name"] for row in rows}
        assert "companies" in table_names
        assert "_schema_version" in table_names

        version_rows = await storage.fetch_all("SELECT version FROM _schema_version")
        assert {r["version"] for r in version_rows} == {1, 2, 5, 6, 7}

    # After the lifespan: storage is closed, no exception raised.
    assert storage._conn is None  # type: ignore[attr-defined]


async def test_lifespan_idempotent_on_existing_db(tmp_path: Path) -> None:
    """Running the lifespan twice on the same data dir does not re-apply migrations."""
    settings = Settings(data_dir=tmp_path)
    async with _lifespan(settings):
        pass
    async with _lifespan(settings) as storage:
        version_rows = await storage.fetch_all("SELECT version FROM _schema_version")
        # Still exactly one row per migration version — not duplicated.
        assert {r["version"] for r in version_rows} == {1, 2, 5, 6, 7}
