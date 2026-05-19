"""Server wiring smoke test for the canonical-clone MCP tools.

Asserts ``build_server`` registers ``register_canonical_clone``,
``list_canonical_clones``, and ``unregister_canonical_clone`` on the
FastMCP instance when ``get_canonical_registry`` is supplied
(M5-canonical-clone-mgmt slice 3, Commit 1).

Symmetric with
``tests/integration/test_dispatch_mcp_wrappers.py``'s
``test_dispatch_initiation_tools_unregistered_when_dispatcher_absent``
shape — minimal kwargs into ``build_server``, ``await mcp.list_tools()``,
asserts on the tool-name set.
"""

from __future__ import annotations

import socket
from collections.abc import AsyncIterator
from pathlib import Path

import pytest
import pytest_asyncio

from meridian_orchestrator.agents.registry import AgentRegistry
from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.bridge.canonical_registry import (
    CanonicalCloneRegistry,
)
from meridian_orchestrator.config import Settings
from meridian_orchestrator.server import build_server
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage


REPO_CONFIG_DIR = Path(__file__).resolve().parents[2] / "config" / "agents"


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


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "test.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(
        data_dir=tmp_path,
        mcp_host="127.0.0.1",
        mcp_port=_free_port(),
    )


@pytest_asyncio.fixture
async def registry(settings: Settings) -> AsyncIterator[AgentRegistry]:
    _seed_repo_yamls(settings.agents_dir)
    r = AgentRegistry(settings.agents_dir)
    async with r.lifespan():
        yield r


async def test_canonical_tools_registered_on_mcp(
    settings: Settings,
    storage: SqliteStorage,
    registry: AgentRegistry,
) -> None:
    """build_server with get_canonical_registry supplied registers the
    three canonical-clone MCP tools. Mirrors the dispatcher-absent
    smoke test pattern."""
    canonical_registry = CanonicalCloneRegistry(storage=storage)

    mcp = build_server(
        settings,
        store=TokenStore(),
        registry=registry,
        storage=storage,
        get_canonical_registry=lambda: canonical_registry,
    )
    tools = await mcp.list_tools()
    names = {t.name for t in tools}
    assert "register_canonical_clone" in names
    assert "list_canonical_clones" in names
    assert "unregister_canonical_clone" in names
