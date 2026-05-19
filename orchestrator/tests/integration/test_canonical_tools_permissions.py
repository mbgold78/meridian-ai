"""Permission-matrix integration tests for the canonical-clone MCP tools.

Pins the Slice 3 Commit 2 authorization model: ``register_canonical_clone``,
``list_canonical_clones``, and ``unregister_canonical_clone`` are PM-only
(Option B; pm.yaml § canonical-clone block at the end of
``mcp_tools_allowed``). Engineer / Implementer / Tester tokens all surface
``PERMISSION_DENIED`` against the live MCP server.

Six tests aligned to the PM-ratified spec § 6 items 16–21:

16. PM token → register_canonical_clone → success.
17. Engineer token → register_canonical_clone → PERMISSION_DENIED.
18. Implementer token → register_canonical_clone → PERMISSION_DENIED.
19. Tester token → register_canonical_clone → PERMISSION_DENIED.
20. Engineer token → list_canonical_clones → PERMISSION_DENIED.
21. Engineer token → unregister_canonical_clone → PERMISSION_DENIED.

Fixture pattern mirrors ``tests/integration/test_submit_task_mcp_wrapper.py``
(real Streamable-HTTP MCP server via ``serve_mcp``, per-role static tokens
issued on the shared ``TokenStore``, in-memory keyring via the autouse
fixture in ``tests/conftest.py``). The canonical registry is constructed
against the test storage and passed via ``get_canonical_registry``.

Path values are derived from ``tmp_path`` so ``Path.is_absolute()`` holds
on Windows (``/tmp/alpha`` is NOT absolute on Windows; needs a drive
letter).
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import socket
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path

import pytest
import pytest_asyncio
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client

from meridian_orchestrator.agents.registry import AgentRegistry
from meridian_orchestrator.auth import IssuedToken, TokenStore
from meridian_orchestrator.bridge.canonical_registry import (
    CanonicalCloneRegistry,
)
from meridian_orchestrator.config import Settings
from meridian_orchestrator.server import serve as serve_mcp
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage

REPO_CONFIG_DIR = Path(__file__).resolve().parents[2] / "config" / "agents"


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _seed_repo_yamls(into_dir: Path) -> None:
    into_dir.mkdir(parents=True, exist_ok=True)
    for role in ("pm", "engineer", "implementer", "tester"):
        (into_dir / f"{role}.yaml").write_text(
            (REPO_CONFIG_DIR / f"{role}.yaml").read_text(encoding="utf-8"),
            encoding="utf-8",
        )


@asynccontextmanager
async def _running_server(
    settings: Settings,
    storage: SqliteStorage,
    registry: AgentRegistry,
    canonical_registry: CanonicalCloneRegistry,
) -> AsyncIterator[None]:
    ready = asyncio.Event()

    def get_canonical_registry() -> CanonicalCloneRegistry | None:
        return canonical_registry

    task = asyncio.create_task(
        serve_mcp(
            settings,
            ready=ready,
            storage=storage,
            registry=registry,
            get_canonical_registry=get_canonical_registry,
        ),
        name="mcp-test",
    )
    try:
        await asyncio.wait_for(ready.wait(), timeout=15)
        yield
    finally:
        task.cancel()
        with contextlib.suppress(TimeoutError, asyncio.CancelledError):
            await asyncio.wait_for(task, timeout=15)


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(
        data_dir=tmp_path,
        mcp_host="127.0.0.1",
        mcp_port=_free_port(),
    )


def _storage_path_for_settings(settings: Settings) -> Path:
    db_dir = settings.data_dir / "data"
    db_dir.mkdir(parents=True, exist_ok=True)
    return db_dir / "orchestrator.db"


@pytest_asyncio.fixture
async def setup(
    settings: Settings, tmp_path: Path
) -> AsyncIterator[
    tuple[SqliteStorage, AgentRegistry, TokenStore, CanonicalCloneRegistry]
]:
    """Storage + AgentRegistry + TokenStore + CanonicalCloneRegistry.

    Seeds the company/project rows, issues a baseline static token for
    each of the four roles (so registry.lifespan can resolve reports_to),
    materializes the canonical registry's 'default' row, and yields the
    pieces tests need to issue per-call tokens.
    """
    _seed_repo_yamls(settings.agents_dir)

    s = SqliteStorage(_storage_path_for_settings(settings))
    await s.connect()
    await run_migrations(s)
    await s.execute(
        "INSERT INTO companies (id, name) VALUES ('co1', 'Meridian AI')"
    )
    await s.execute(
        "INSERT INTO projects (id, company_id, name) VALUES "
        "('proj1', 'co1', 'Substrate Product')"
    )

    token_store = TokenStore()
    for role in ("pm", "engineer", "implementer", "tester"):
        issued = await token_store.issue(
            agent_id=None, role=role, label=f"{role}-baseline"
        )
        await s.execute(
            "INSERT INTO agents (id, company_id, role, config_path) "
            "VALUES (?, 'co1', ?, '/dev/null')",
            (issued.record.agent_id, role),
        )

    canonical_registry = CanonicalCloneRegistry(storage=s)
    await canonical_registry.ensure_default(
        path=tmp_path / "default-canonical",
        upstream_url="https://example.com/default.git",
    )

    registry = AgentRegistry(settings.agents_dir, token_store=token_store)
    async with registry.lifespan():
        yield s, registry, token_store, canonical_registry
    await s.close()


async def _issue_test_token(
    token_store: TokenStore,
    role: str,
    label: str,
) -> IssuedToken:
    return await token_store.issue(agent_id=None, role=role, label=label)


async def _seed_agent_for_token(
    storage: SqliteStorage, issued: IssuedToken, role: str
) -> None:
    await storage.execute(
        "INSERT INTO agents (id, company_id, role, config_path) "
        "VALUES (?, 'co1', ?, '/dev/null')",
        (issued.record.agent_id, role),
    )


# ---------------------------------------------------------------------------
# MCP client helpers
# ---------------------------------------------------------------------------


async def _call_tool(
    *,
    base_url: str,
    token: str,
    tool_name: str,
    args: dict[str, object],
) -> object:
    url = f"{base_url}/mcp"
    headers = {"Authorization": f"Bearer {token}"}
    async with (
        streamablehttp_client(url, headers=headers) as (read, write, _),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        return await session.call_tool(tool_name, args)


def _structured(result: object) -> dict[str, object]:
    structured = getattr(result, "structuredContent", None)
    if structured is not None:
        return dict(structured)
    text = result.content[0].text  # type: ignore[union-attr]
    return dict(json.loads(text))


def _error_text(result: object) -> str:
    return result.content[0].text  # type: ignore[union-attr]


# ---------------------------------------------------------------------------
# Test 16: PM static token CAN call register_canonical_clone
# ---------------------------------------------------------------------------


async def test_pm_token_can_call_register_canonical_clone(
    settings: Settings,
    setup: tuple[
        SqliteStorage, AgentRegistry, TokenStore, CanonicalCloneRegistry
    ],
    tmp_path: Path,
) -> None:
    """PM-role static token calls register_canonical_clone → success.

    Pins § 4.1 pm.yaml mcp_tools_allowed activation: the canonical
    block at the end of the list grants PM access; the wrapper's
    ``resolver.require_tool('pm', 'register_canonical_clone')`` allows
    through; the handler writes the row and the audit event.
    """
    storage, registry, token_store, _canonical_registry = setup
    issued = await _issue_test_token(token_store, "pm", "pm-register-allowed")
    await _seed_agent_for_token(storage, issued, "pm")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"
    alpha_path = tmp_path / "alpha"

    async with _running_server(
        settings, storage, registry, _canonical_registry
    ):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="register_canonical_clone",
            args={
                "name": "alpha",
                "path": str(alpha_path),
                "upstream_url": "https://example.com/alpha.git",
            },
        )

    assert getattr(result, "isError", False) is False, (
        f"PM register_canonical_clone unexpectedly errored: "
        f"{getattr(result, 'content', None)!r}"
    )
    out = _structured(result)
    canonical = out["canonical"]
    assert isinstance(canonical, dict)
    assert canonical["name"] == "alpha"
    assert canonical["path"] == str(alpha_path)
    assert canonical["upstream_url"] == "https://example.com/alpha.git"
    assert canonical["default_branch"] == "main"

    row = await storage.fetch_one(
        "SELECT name FROM canonical_clones WHERE name = ?", ("alpha",)
    )
    assert row is not None
    assert row["name"] == "alpha"


# ---------------------------------------------------------------------------
# Test 17: Engineer static token CANNOT call register_canonical_clone
# ---------------------------------------------------------------------------


async def test_engineer_token_cannot_call_register_canonical_clone(
    settings: Settings,
    setup: tuple[
        SqliteStorage, AgentRegistry, TokenStore, CanonicalCloneRegistry
    ],
    tmp_path: Path,
) -> None:
    """Engineer-role token calling register_canonical_clone surfaces
    PERMISSION_DENIED. Engineer's mcp_tools_allowed deliberately omits
    the canonical-clone tools (Option B; § 4.2 binding from Mike)."""
    storage, registry, token_store, canonical_registry = setup
    issued = await _issue_test_token(
        token_store, "engineer", "engineer-register-denied"
    )
    await _seed_agent_for_token(storage, issued, "engineer")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(
        settings, storage, registry, canonical_registry
    ):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="register_canonical_clone",
            args={
                "name": "alpha",
                "path": str(tmp_path / "alpha"),
                "upstream_url": "https://example.com/alpha.git",
            },
        )

    assert getattr(result, "isError", False) is True, (
        "Engineer register_canonical_clone should reject as "
        "permission-denied; got non-error response."
    )
    text = _error_text(result).lower()
    assert (
        "permission" in text
        or "denied" in text
        or "register_canonical_clone" in text
    ), f"expected permission-denied marker in error: {text!r}"

    # No row materialized; no audit row written.
    row = await storage.fetch_one(
        "SELECT COUNT(*) AS c FROM canonical_clones WHERE name = ?",
        ("alpha",),
    )
    assert row is not None and row["c"] == 0
    audits = await storage.fetch_all(
        "SELECT id FROM audit_log WHERE action = 'canonical_clone.registered'"
    )
    assert audits == []


# ---------------------------------------------------------------------------
# Test 18: Implementer static token CANNOT call register_canonical_clone
# ---------------------------------------------------------------------------


async def test_implementer_token_cannot_call_register_canonical_clone(
    settings: Settings,
    setup: tuple[
        SqliteStorage, AgentRegistry, TokenStore, CanonicalCloneRegistry
    ],
    tmp_path: Path,
) -> None:
    """Implementer-role token calling register_canonical_clone surfaces
    PERMISSION_DENIED. Implementer's mcp_tools_allowed omits the
    canonical-clone tools per Option B."""
    storage, registry, token_store, canonical_registry = setup
    issued = await _issue_test_token(
        token_store, "implementer", "implementer-register-denied"
    )
    await _seed_agent_for_token(storage, issued, "implementer")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(
        settings, storage, registry, canonical_registry
    ):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="register_canonical_clone",
            args={
                "name": "alpha",
                "path": str(tmp_path / "alpha"),
                "upstream_url": "https://example.com/alpha.git",
            },
        )

    assert getattr(result, "isError", False) is True, (
        "Implementer register_canonical_clone should reject as "
        "permission-denied; got non-error response."
    )
    text = _error_text(result).lower()
    assert (
        "permission" in text
        or "denied" in text
        or "register_canonical_clone" in text
    ), f"expected permission-denied marker in error: {text!r}"


# ---------------------------------------------------------------------------
# Test 19: Tester static token CANNOT call register_canonical_clone
# ---------------------------------------------------------------------------


async def test_tester_token_cannot_call_register_canonical_clone(
    settings: Settings,
    setup: tuple[
        SqliteStorage, AgentRegistry, TokenStore, CanonicalCloneRegistry
    ],
    tmp_path: Path,
) -> None:
    """Tester-role token calling register_canonical_clone surfaces
    PERMISSION_DENIED. Tester's mcp_tools_allowed omits the
    canonical-clone tools per Option B."""
    storage, registry, token_store, canonical_registry = setup
    issued = await _issue_test_token(
        token_store, "tester", "tester-register-denied"
    )
    await _seed_agent_for_token(storage, issued, "tester")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(
        settings, storage, registry, canonical_registry
    ):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="register_canonical_clone",
            args={
                "name": "alpha",
                "path": str(tmp_path / "alpha"),
                "upstream_url": "https://example.com/alpha.git",
            },
        )

    assert getattr(result, "isError", False) is True, (
        "Tester register_canonical_clone should reject as "
        "permission-denied; got non-error response."
    )
    text = _error_text(result).lower()
    assert (
        "permission" in text
        or "denied" in text
        or "register_canonical_clone" in text
    ), f"expected permission-denied marker in error: {text!r}"


# ---------------------------------------------------------------------------
# Test 20: Engineer static token CANNOT call list_canonical_clones
# ---------------------------------------------------------------------------


async def test_engineer_token_cannot_call_list_canonical_clones(
    settings: Settings,
    setup: tuple[
        SqliteStorage, AgentRegistry, TokenStore, CanonicalCloneRegistry
    ],
) -> None:
    """Engineer token → list_canonical_clones → PERMISSION_DENIED.

    Pins the binding from Mike that supersedes ``docs/clone-mgmt-roadmap.md``
    line 27 (which had floated sub-agent read-only access): Slice 3 lists
    are PM-only as well. Roadmap divergence is operator-disposition for a
    post-Slice-3 yaml-only follow-up.
    """
    storage, registry, token_store, canonical_registry = setup
    issued = await _issue_test_token(
        token_store, "engineer", "engineer-list-denied"
    )
    await _seed_agent_for_token(storage, issued, "engineer")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(
        settings, storage, registry, canonical_registry
    ):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="list_canonical_clones",
            args={},
        )

    assert getattr(result, "isError", False) is True, (
        "Engineer list_canonical_clones should reject as "
        "permission-denied; got non-error response."
    )
    text = _error_text(result).lower()
    assert (
        "permission" in text
        or "denied" in text
        or "list_canonical_clones" in text
    ), f"expected permission-denied marker in error: {text!r}"


# ---------------------------------------------------------------------------
# Test 21: Engineer static token CANNOT call unregister_canonical_clone
# ---------------------------------------------------------------------------


async def test_engineer_token_cannot_call_unregister_canonical_clone(
    settings: Settings,
    setup: tuple[
        SqliteStorage, AgentRegistry, TokenStore, CanonicalCloneRegistry
    ],
) -> None:
    """Engineer token → unregister_canonical_clone → PERMISSION_DENIED.

    Mirrors test 17 / 20 for the unregister surface."""
    storage, registry, token_store, canonical_registry = setup
    issued = await _issue_test_token(
        token_store, "engineer", "engineer-unregister-denied"
    )
    await _seed_agent_for_token(storage, issued, "engineer")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(
        settings, storage, registry, canonical_registry
    ):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="unregister_canonical_clone",
            args={"name": "alpha"},
        )

    assert getattr(result, "isError", False) is True, (
        "Engineer unregister_canonical_clone should reject as "
        "permission-denied; got non-error response."
    )
    text = _error_text(result).lower()
    assert (
        "permission" in text
        or "denied" in text
        or "unregister_canonical_clone" in text
    ), f"expected permission-denied marker in error: {text!r}"

    # No audit row for unregistered (no state change).
    audits = await storage.fetch_all(
        "SELECT id FROM audit_log "
        "WHERE action = 'canonical_clone.unregistered'"
    )
    assert audits == []
