"""Audit-schema-contract tests for the canonical-clone audit event_kinds.

Pins the documented shape (action / target / details_json keys + types)
for the two event_kinds emitted by ``tools.canonical``:

- ``canonical_clone.registered`` — payload
  ``{default_branch, name, path, upstream_url}``, all strings.
- ``canonical_clone.unregistered`` — payload ``{name}``, string.

Mirrors β-S11-4 audit-schema-contract discipline (commit ``5475c2d``).

M5-canonical-clone-mgmt slice 3, Commit 1.
"""

from __future__ import annotations

import json
from collections.abc import AsyncIterator
from pathlib import Path

import pytest_asyncio

from meridian_orchestrator.audit.logger import write_audit
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "test.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


async def test_canonical_clone_registered_audit_schema(
    storage: SqliteStorage,
) -> None:
    """The canonical_clone.registered audit row payload contains
    exactly {default_branch, name, path, upstream_url} as JSON
    string-typed fields. Schema-contract test mirrors β-S11-4
    discipline."""
    await write_audit(
        storage,
        actor="test_actor",
        action="canonical_clone.registered",
        target="alpha",
        details={
            "default_branch": "main",
            "name": "alpha",
            "path": "/tmp/alpha",
            "upstream_url": "https://example.com/alpha.git",
        },
    )
    row = await storage.fetch_one(
        "SELECT action, target, details_json FROM audit_log "
        "WHERE action = 'canonical_clone.registered' "
        "ORDER BY timestamp DESC LIMIT 1"
    )
    assert row is not None
    assert row["action"] == "canonical_clone.registered"
    assert row["target"] == "alpha"
    payload = json.loads(row["details_json"])
    assert set(payload.keys()) == {
        "default_branch",
        "name",
        "path",
        "upstream_url",
    }
    assert all(isinstance(v, str) for v in payload.values())


async def test_canonical_clone_unregistered_audit_schema(
    storage: SqliteStorage,
) -> None:
    """Minimal payload — only the name. Forensic-query path for
    path/url is the matching .registered row, not the .unregistered
    row."""
    await write_audit(
        storage,
        actor="test_actor",
        action="canonical_clone.unregistered",
        target="alpha",
        details={"name": "alpha"},
    )
    row = await storage.fetch_one(
        "SELECT action, target, details_json FROM audit_log "
        "WHERE action = 'canonical_clone.unregistered' "
        "ORDER BY timestamp DESC LIMIT 1"
    )
    assert row is not None
    assert row["action"] == "canonical_clone.unregistered"
    assert row["target"] == "alpha"
    payload = json.loads(row["details_json"])
    assert set(payload.keys()) == {"name"}
    assert payload["name"] == "alpha"
