"""Database seeding: Meridian AI company + Substrate Product project.

Idempotent. Run on every daemon startup (after migrations) and from
``scripts/init-db.py`` for the manual-init path.
"""

from __future__ import annotations

import uuid

from meridian_orchestrator.storage.interface import Storage

SUBSTRATE_WORKING_DIR = r"C:\Users\mbgol\dev\meridian-homepoc"
COMPANY_NAME = "Meridian AI"
PROJECT_NAME = "Meridian Substrate Product"


async def seed_initial(storage: Storage) -> tuple[str, str]:
    """Seed Meridian AI company and Substrate Product project.

    Idempotent: re-running on existing rows is a no-op. Looks up by name.

    Returns:
        ``(company_id, project_id)`` UUIDs.
    """
    company = await storage.fetch_one("SELECT id FROM companies WHERE name = ?", (COMPANY_NAME,))
    if company is None:
        company_id = str(uuid.uuid4())
        await storage.execute(
            "INSERT INTO companies (id, name) VALUES (?, ?)",
            (company_id, COMPANY_NAME),
        )
    else:
        company_id = str(company["id"])

    project = await storage.fetch_one(
        "SELECT id FROM projects WHERE company_id = ? AND name = ?",
        (company_id, PROJECT_NAME),
    )
    if project is None:
        project_id = str(uuid.uuid4())
        await storage.execute(
            "INSERT INTO projects (id, company_id, name, working_directory) VALUES (?, ?, ?, ?)",
            (project_id, company_id, PROJECT_NAME, SUBSTRATE_WORKING_DIR),
        )
    else:
        project_id = str(project["id"])

    return company_id, project_id
