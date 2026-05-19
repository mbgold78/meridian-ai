"""Direct-handler tests for ``tools.canonical``.

Exercises :func:`tools.canonical.register_canonical_clone`,
:func:`tools.canonical.list_canonical_clones`, and
:func:`tools.canonical.unregister_canonical_clone` against the real
``SqliteStorage`` and a real
:class:`bridge.canonical_registry.CanonicalCloneRegistry` — same fixture
pattern as ``tests/unit/bridge/test_canonical_registry.py``.

Path strings are derived from ``tmp_path`` so ``Path.is_absolute()``
returns ``True`` on both POSIX and Windows hosts (e.g.,
``/tmp/alpha`` is NOT absolute on Windows — it needs a drive letter).

M5-canonical-clone-mgmt slice 3, Commit 1 (per the PM-ratified spec).
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from pathlib import Path

import pytest
import pytest_asyncio
from pydantic import ValidationError as PydanticValidationError

from meridian_orchestrator.bridge.canonical_registry import (
    CanonicalAlreadyRegisteredError,
    CanonicalCloneRegistry,
    CanonicalNotFoundError,
)
from meridian_orchestrator.errors import ConflictError, ValidationError
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.tools.canonical import (
    ListCanonicalClonesInput,
    RegisterCanonicalCloneInput,
    UnregisterCanonicalCloneInput,
    list_canonical_clones,
    register_canonical_clone,
    unregister_canonical_clone,
)


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "test.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


@pytest_asyncio.fixture
async def registry(
    storage: SqliteStorage,
    tmp_path: Path,
) -> AsyncIterator[CanonicalCloneRegistry]:
    r = CanonicalCloneRegistry(storage=storage)
    # Seed 'default' so test 7's "only default" path is realistic and
    # tests 6/11/12 can exercise non-default registrations against a
    # populated table.
    await r.ensure_default(
        path=tmp_path / "default-canonical",
        upstream_url="https://example.com/default.git",
    )
    yield r


# ----- 1. register happy path ----------------------------------------------


async def test_register_canonical_clone_happy_path(
    storage: SqliteStorage,
    registry: CanonicalCloneRegistry,
    tmp_path: Path,
) -> None:
    alpha_path = tmp_path / "alpha"
    result = await register_canonical_clone(
        storage=storage,
        registry=registry,
        request=RegisterCanonicalCloneInput(
            name="alpha",
            path=str(alpha_path),
            upstream_url="https://example.com/alpha.git",
        ),
        caller_agent_id="agent-1",
    )

    assert result.canonical.name == "alpha"
    assert result.canonical.path == str(alpha_path)
    assert result.canonical.upstream_url == "https://example.com/alpha.git"
    assert result.canonical.default_branch == "main"

    row = await storage.fetch_one(
        "SELECT name, path, upstream_url, default_branch "
        "FROM canonical_clones WHERE name = ?",
        ("alpha",),
    )
    assert row is not None
    assert row["name"] == "alpha"
    assert row["path"] == str(alpha_path)
    assert row["upstream_url"] == "https://example.com/alpha.git"
    assert row["default_branch"] == "main"

    audit_row = await storage.fetch_one(
        "SELECT actor, action, target FROM audit_log "
        "WHERE action = 'canonical_clone.registered' "
        "ORDER BY timestamp DESC LIMIT 1"
    )
    assert audit_row is not None
    assert audit_row["actor"] == "agent-1"
    assert audit_row["target"] == "alpha"


# ----- 2. register rejects reserved name 'default' -------------------------


async def test_register_canonical_clone_rejects_reserved_name_default(
    storage: SqliteStorage,
    registry: CanonicalCloneRegistry,
    tmp_path: Path,
) -> None:
    with pytest.raises(ConflictError) as excinfo:
        await register_canonical_clone(
            storage=storage,
            registry=registry,
            request=RegisterCanonicalCloneInput(
                name="default",
                path=str(tmp_path / "whatever"),
                upstream_url="https://example.com/x.git",
            ),
            caller_agent_id="agent-1",
        )
    assert "reserved" in str(excinfo.value)


# ----- 3. register rejects non-absolute path -------------------------------


async def test_register_canonical_clone_rejects_non_absolute_path(
    storage: SqliteStorage,
    registry: CanonicalCloneRegistry,
) -> None:
    with pytest.raises(ValidationError) as excinfo:
        await register_canonical_clone(
            storage=storage,
            registry=registry,
            request=RegisterCanonicalCloneInput(
                name="alpha",
                path="relative/path",
                upstream_url="https://example.com/alpha.git",
            ),
            caller_agent_id="agent-1",
        )
    assert "absolute" in str(excinfo.value)


# ----- 4. register rejects duplicate name ----------------------------------


async def test_register_canonical_clone_rejects_duplicate_name(
    storage: SqliteStorage,
    registry: CanonicalCloneRegistry,
    tmp_path: Path,
) -> None:
    await register_canonical_clone(
        storage=storage,
        registry=registry,
        request=RegisterCanonicalCloneInput(
            name="alpha",
            path=str(tmp_path / "alpha"),
            upstream_url="https://example.com/alpha.git",
        ),
        caller_agent_id="agent-1",
    )
    with pytest.raises(CanonicalAlreadyRegisteredError):
        await register_canonical_clone(
            storage=storage,
            registry=registry,
            request=RegisterCanonicalCloneInput(
                name="alpha",
                path=str(tmp_path / "alpha-2"),
                upstream_url="https://example.com/alpha-2.git",
            ),
            caller_agent_id="agent-1",
        )


# ----- 5. Pydantic-level name pattern validation ---------------------------


async def test_register_canonical_clone_pydantic_validates_name_pattern(
    tmp_path: Path,
) -> None:
    abs_path = str(tmp_path / "x")
    # uppercase rejected
    with pytest.raises(PydanticValidationError):
        RegisterCanonicalCloneInput(
            name="UPPERCASE",
            path=abs_path,
            upstream_url="https://example.com/x.git",
        )
    # starts with digit rejected
    with pytest.raises(PydanticValidationError):
        RegisterCanonicalCloneInput(
            name="123starts",
            path=abs_path,
            upstream_url="https://example.com/x.git",
        )
    # length > 63 rejected
    with pytest.raises(PydanticValidationError):
        RegisterCanonicalCloneInput(
            name="a" + "x" * 63,  # 64 chars total
            path=abs_path,
            upstream_url="https://example.com/x.git",
        )


# ----- 6. list returns all rows in created_at order ------------------------


async def test_list_canonical_clones_returns_all_rows(
    storage: SqliteStorage,
    registry: CanonicalCloneRegistry,
    tmp_path: Path,
) -> None:
    await register_canonical_clone(
        storage=storage,
        registry=registry,
        request=RegisterCanonicalCloneInput(
            name="alpha",
            path=str(tmp_path / "alpha"),
            upstream_url="https://example.com/alpha.git",
        ),
        caller_agent_id="agent-1",
    )
    await register_canonical_clone(
        storage=storage,
        registry=registry,
        request=RegisterCanonicalCloneInput(
            name="beta",
            path=str(tmp_path / "beta"),
            upstream_url="https://example.com/beta.git",
        ),
        caller_agent_id="agent-1",
    )

    result = await list_canonical_clones(
        storage=storage,
        request=ListCanonicalClonesInput(),
    )

    assert len(result.canonicals) == 3
    names = [c.name for c in result.canonicals]
    assert names[0] == "default"
    assert set(names) == {"default", "alpha", "beta"}
    for entry in result.canonicals:
        assert entry.name
        assert entry.path
        assert entry.upstream_url
        assert entry.default_branch


# ----- 7. list returns single-entry when only default ----------------------


async def test_list_canonical_clones_returns_only_default_when_empty(
    storage: SqliteStorage,
    registry: CanonicalCloneRegistry,
) -> None:
    result = await list_canonical_clones(
        storage=storage,
        request=ListCanonicalClonesInput(),
    )
    assert len(result.canonicals) == 1
    assert result.canonicals[0].name == "default"


# ----- 8. list does not emit audit -----------------------------------------


async def test_list_canonical_clones_does_not_emit_audit(
    storage: SqliteStorage,
    registry: CanonicalCloneRegistry,
) -> None:
    await list_canonical_clones(
        storage=storage,
        request=ListCanonicalClonesInput(),
    )
    row = await storage.fetch_one(
        "SELECT COUNT(*) AS c FROM audit_log "
        "WHERE action LIKE 'canonical_clone.%'"
    )
    assert row is not None
    assert row["c"] == 0


# ----- 9. unregister happy path --------------------------------------------


async def test_unregister_canonical_clone_happy_path(
    storage: SqliteStorage,
    registry: CanonicalCloneRegistry,
    tmp_path: Path,
) -> None:
    await register_canonical_clone(
        storage=storage,
        registry=registry,
        request=RegisterCanonicalCloneInput(
            name="alpha",
            path=str(tmp_path / "alpha"),
            upstream_url="https://example.com/alpha.git",
        ),
        caller_agent_id="agent-1",
    )

    result = await unregister_canonical_clone(
        storage=storage,
        registry=registry,
        request=UnregisterCanonicalCloneInput(name="alpha"),
        caller_agent_id="agent-1",
    )
    assert result.unregistered_name == "alpha"

    row = await storage.fetch_one(
        "SELECT COUNT(*) AS c FROM canonical_clones WHERE name = ?",
        ("alpha",),
    )
    assert row is not None
    assert row["c"] == 0

    audit_row = await storage.fetch_one(
        "SELECT action, target FROM audit_log "
        "WHERE action = 'canonical_clone.unregistered' "
        "ORDER BY timestamp DESC LIMIT 1"
    )
    assert audit_row is not None
    assert audit_row["target"] == "alpha"


# ----- 10. unregister rejects 'default' ------------------------------------


async def test_unregister_canonical_clone_rejects_default(
    storage: SqliteStorage,
    registry: CanonicalCloneRegistry,
) -> None:
    with pytest.raises(ConflictError) as excinfo:
        await unregister_canonical_clone(
            storage=storage,
            registry=registry,
            request=UnregisterCanonicalCloneInput(name="default"),
            caller_agent_id="agent-1",
        )
    assert "reserved" in str(excinfo.value)


# ----- 11. unregister rejects when dispatch is active ----------------------


async def test_unregister_canonical_clone_rejects_when_dispatch_in_use(
    storage: SqliteStorage,
    registry: CanonicalCloneRegistry,
    tmp_path: Path,
) -> None:
    await register_canonical_clone(
        storage=storage,
        registry=registry,
        request=RegisterCanonicalCloneInput(
            name="alpha",
            path=str(tmp_path / "alpha"),
            upstream_url="https://example.com/alpha.git",
        ),
        caller_agent_id="agent-1",
    )
    await storage.execute(
        "INSERT INTO dispatches "
        "(id, role, status, working_directory, timeout_seconds, "
        "canonical_name) VALUES (?, ?, 'running', ?, ?, ?)",
        ("dispatch-1", "implementer", str(tmp_path / "wt"), 300, "alpha"),
    )

    with pytest.raises(ConflictError) as excinfo:
        await unregister_canonical_clone(
            storage=storage,
            registry=registry,
            request=UnregisterCanonicalCloneInput(name="alpha"),
            caller_agent_id="agent-1",
        )
    assert "active dispatch" in str(excinfo.value)


# ----- 12. unregister allowed when only terminal dispatches ----------------


async def test_unregister_canonical_clone_allowed_when_dispatch_terminal(
    storage: SqliteStorage,
    registry: CanonicalCloneRegistry,
    tmp_path: Path,
) -> None:
    await register_canonical_clone(
        storage=storage,
        registry=registry,
        request=RegisterCanonicalCloneInput(
            name="alpha",
            path=str(tmp_path / "alpha"),
            upstream_url="https://example.com/alpha.git",
        ),
        caller_agent_id="agent-1",
    )
    await storage.execute(
        "INSERT INTO dispatches "
        "(id, role, status, working_directory, timeout_seconds, "
        "canonical_name) VALUES (?, ?, 'completed', ?, ?, ?)",
        ("dispatch-2", "implementer", str(tmp_path / "wt"), 300, "alpha"),
    )

    result = await unregister_canonical_clone(
        storage=storage,
        registry=registry,
        request=UnregisterCanonicalCloneInput(name="alpha"),
        caller_agent_id="agent-1",
    )
    assert result.unregistered_name == "alpha"


# ----- 13. unregister surfaces CanonicalNotFoundError ----------------------


async def test_unregister_canonical_clone_not_found(
    storage: SqliteStorage,
    registry: CanonicalCloneRegistry,
) -> None:
    with pytest.raises(CanonicalNotFoundError):
        await unregister_canonical_clone(
            storage=storage,
            registry=registry,
            request=UnregisterCanonicalCloneInput(name="nonexistent"),
            caller_agent_id="agent-1",
        )
