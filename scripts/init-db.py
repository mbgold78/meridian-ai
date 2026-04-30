"""Initialize the Orchestrator database.

Creates the DB file under ``${MERIDIAN_ORCH_DATA_DIR}/data/`` (default
``~/.meridian-orchestrator/data/``), runs migrations, and seeds the Meridian AI
company + Substrate Product project.

Idempotent: re-running on an existing DB is safe.
"""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path

# Make src/ importable when this script is run directly without uv install.
sys.path.insert(0, str(Path(__file__).parent.parent / "src"))

from meridian_orchestrator.config import Settings
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.seed import seed_initial
from meridian_orchestrator.storage.sqlite import SqliteStorage


async def _main() -> None:
    settings = Settings()
    storage = SqliteStorage(settings.db_path)
    await storage.connect()
    try:
        applied = await run_migrations(storage)
        if applied:
            print(f"Migrations applied: {applied}")
        else:
            print("Migrations: up to date")
        company_id, project_id = await seed_initial(storage)
        print(f"Company:  Meridian AI                  ({company_id})")
        print(f"Project:  Meridian Substrate Product   ({project_id})")
        print(f"Database: {settings.db_path}")
    finally:
        await storage.close()


if __name__ == "__main__":
    asyncio.run(_main())
