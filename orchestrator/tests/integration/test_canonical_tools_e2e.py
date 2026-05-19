"""End-to-end round-trip integration test for the canonical-clone MCP tools.

Exercises the full wrapper-+-handler chain over a live Streamable-HTTP MCP
server with a PM-role static token (whose ``mcp_tools_allowed`` includes
the canonical-clone block per § 4.1):

1. ``register_canonical_clone(name='alpha', ...)`` → success.
2. ``list_canonical_clones()`` → exactly two entries: {'default', 'alpha'}.
3. ``unregister_canonical_clone(name='alpha')`` → success.
4. ``list_canonical_clones()`` → exactly one entry: {'default'}.

Plus audit-log assertions: matching ``canonical_clone.registered`` and
``canonical_clone.unregistered`` rows with ``target='alpha'`` are written
to ``audit_log``.

Fixture pattern mirrors
``tests/integration/test_canonical_tools_permissions.py`` and
``tests/integration/test_submit_task_mcp_wrapper.py``.

Path values are derived from ``tmp_path`` so ``Path.is_absolute()`` holds
on Windows.
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


# ---------------------------------------------------------------------------
# Test 22: PM-token round-trip register → list → unregister → list
# ---------------------------------------------------------------------------


async def test_canonical_tools_round_trip_with_pm_token(
    settings: Settings,
    setup: tuple[
        SqliteStorage, AgentRegistry, TokenStore, CanonicalCloneRegistry
    ],
    tmp_path: Path,
) -> None:
    """PM-role static token exercises the full register → list →
    unregister → list cycle over the live MCP server.

    Verifies:
    - register_canonical_clone returns a CanonicalCloneModel with the
      input fields populated.
    - list_canonical_clones returns exactly {'default', 'alpha'} after
      register.
    - unregister_canonical_clone returns the unregistered name.
    - list_canonical_clones returns exactly {'default'} after unregister.
    - audit_log has matching canonical_clone.registered and
      canonical_clone.unregistered rows with target='alpha'.
    """
    storage, registry, token_store, canonical_registry = setup
    issued = await _issue_test_token(
        token_store, "pm", "pm-round-trip"
    )
    await _seed_agent_for_token(storage, issued, "pm")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"
    alpha_path = tmp_path / "alpha"

    async with _running_server(
        settings, storage, registry, canonical_registry
    ):
        # 1. Register.
        register_result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="register_canonical_clone",
            args={
                "name": "alpha",
                "path": str(alpha_path),
                "upstream_url": "https://example.com/alpha.git",
            },
        )
        assert getattr(register_result, "isError", False) is False, (
            f"register_canonical_clone errored: "
            f"{getattr(register_result, 'content', None)!r}"
        )
        register_out = _structured(register_result)
        canonical = register_out["canonical"]
        assert isinstance(canonical, dict)
        assert canonical["name"] == "alpha"

        # 2. List → 2 entries.
        list_after_register = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="list_canonical_clones",
            args={},
        )
        assert getattr(list_after_register, "isError", False) is False
        list_out = _structured(list_after_register)
        canonicals = list_out["canonicals"]
        assert isinstance(canonicals, list)
        assert len(canonicals) == 2
        names_after_register = {c["name"] for c in canonicals}
        assert names_after_register == {"default", "alpha"}

        # 3. Unregister.
        unregister_result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="unregister_canonical_clone",
            args={"name": "alpha"},
        )
        assert getattr(unregister_result, "isError", False) is False, (
            f"unregister_canonical_clone errored: "
            f"{getattr(unregister_result, 'content', None)!r}"
        )
        unregister_out = _structured(unregister_result)
        assert unregister_out["unregistered_name"] == "alpha"

        # 4. List → 1 entry.
        list_after_unregister = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="list_canonical_clones",
            args={},
        )
        assert getattr(list_after_unregister, "isError", False) is False
        list_out_final = _structured(list_after_unregister)
        canonicals_final = list_out_final["canonicals"]
        assert isinstance(canonicals_final, list)
        assert len(canonicals_final) == 1
        assert canonicals_final[0]["name"] == "default"

    # Audit-log assertions: matching .registered + .unregistered rows
    # with target='alpha'.
    registered_audits = await storage.fetch_all(
        "SELECT target FROM audit_log "
        "WHERE action = 'canonical_clone.registered' AND target = 'alpha'"
    )
    assert len(registered_audits) == 1
    unregistered_audits = await storage.fetch_all(
        "SELECT target FROM audit_log "
        "WHERE action = 'canonical_clone.unregistered' AND target = 'alpha'"
    )
    assert len(unregistered_audits) == 1
