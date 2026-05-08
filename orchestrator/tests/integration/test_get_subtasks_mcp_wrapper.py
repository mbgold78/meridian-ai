"""End-to-end MCP wrapper test for ``get_subtasks``.

Hybrid authorization shape (CQ3 commit 6 Reading B): raise-gate for
parent access + visibility-filter for subtask result set. NOT audited
(read-only).
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
from meridian_orchestrator.config import Settings
from meridian_orchestrator.server import serve as serve_mcp
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage

REPO_CONFIG_DIR = Path(__file__).parent.parent.parent / "config" / "agents"


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
) -> AsyncIterator[None]:
    ready = asyncio.Event()
    task = asyncio.create_task(
        serve_mcp(settings, ready=ready, storage=storage, registry=registry),
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
    settings: Settings,
) -> AsyncIterator[
    tuple[SqliteStorage, AgentRegistry, TokenStore, dict[str, str]]
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
    baseline_ids: dict[str, str] = {}
    for role in ("pm", "engineer", "implementer", "tester"):
        issued = token_store.issue(
            agent_id=None, role=role, label=f"{role}-baseline"
        )
        baseline_ids[role] = issued.record.agent_id
        await s.execute(
            "INSERT INTO agents (id, company_id, role, config_path) "
            "VALUES (?, 'co1', ?, '/dev/null')",
            (issued.record.agent_id, role),
        )

    registry = AgentRegistry(settings.agents_dir, token_store=token_store)
    async with registry.lifespan():
        yield s, registry, token_store, baseline_ids
    await s.close()


def _issue_test_token(
    token_store: TokenStore, role: str, label: str
) -> IssuedToken:
    return token_store.issue(agent_id=None, role=role, label=label)


async def _seed_agent_for_token(
    storage: SqliteStorage, issued: IssuedToken, role: str
) -> None:
    await storage.execute(
        "INSERT INTO agents (id, company_id, role, config_path) "
        "VALUES (?, 'co1', ?, '/dev/null')",
        (issued.record.agent_id, role),
    )


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
# Test: Engineer (claimed_by parent) reads subtasks; impl-not-in-chain
# raise via clause-1-fails-clause-2-ancestor-fails route is structurally
# covered by direct unit test; integration covers happy path through MCP.
# ---------------------------------------------------------------------------


async def test_engineer_get_subtasks_via_claimed_by_parent(
    settings: Settings,
    setup: tuple[
        SqliteStorage, AgentRegistry, TokenStore, dict[str, str]
    ],
) -> None:
    """Engineer claims a parent task; queries get_subtasks for the
    parent. Raise-gate (clause: claimed_by) passes; visibility-filter
    returns the implementer + tester subtasks (Engineer's subtree
    includes both)."""
    storage, registry, token_store, _baseline_ids = setup
    issued = _issue_test_token(token_store, "engineer", "eng-subtasks")
    await _seed_agent_for_token(storage, issued, "engineer")

    # Engineer-claimed parent + 2 subtasks.
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description, status, submitted_by, claimed_by) VALUES "
        "('parent-int', 'co1', 'proj1', 'engineer', 'parent', "
        "'in_progress', 'agent-pm-bogus', ?)",
        (issued.record.agent_id,),
    )
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, "
        "parent_task_id, agent_role, description, status) VALUES "
        "('sub-impl', 'co1', 'proj1', 'parent-int', 'implementer', "
        "'impl sub', 'pending'), "
        "('sub-tester', 'co1', 'proj1', 'parent-int', 'tester', "
        "'tester sub', 'pending')"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, registry):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="get_subtasks",
            args={"parent_task_id": "parent-int"},
        )

    assert getattr(result, "isError", False) is False, (
        f"get_subtasks returned error: "
        f"{getattr(result, 'content', None)!r}"
    )
    out = _structured(result)
    tasks = out["tasks"]
    assert isinstance(tasks, list)
    assert {t["id"] for t in tasks} == {"sub-impl", "sub-tester"}
