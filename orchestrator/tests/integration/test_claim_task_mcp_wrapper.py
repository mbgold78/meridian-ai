"""End-to-end MCP wrapper tests for ``claim_task``.

Exercises the ``claim_task`` MCP wrapper against a real
Streamable-HTTP MCP server. Covers the wiring direct-handler tests in
``test_tasks.py`` cannot reach:

- Wrapper resolves caller identity via ``_resolve_agent_id`` +
  ``_resolve_caller_role_via_token`` (no caller_source needed for
  claim_task; clean handler shape per F2.1 sub-category framework).
- Strict role-equality gate per spec line 111: Engineer-targeted
  tasks claimable only by engineer-role tokens; sibling-role
  rejections (Implementer cannot claim Tester) surface as MCP
  permission errors.
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


# ---------------------------------------------------------------------------
# Fixtures (mirror test_submit_task_mcp_wrapper.py shape)
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
) -> AsyncIterator[tuple[SqliteStorage, AgentRegistry, TokenStore]]:
    """Storage + Registry + TokenStore with the four-role roster seeded.

    Mirrors test_submit_task_mcp_wrapper.py's setup fixture.
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
        issued = token_store.issue(
            agent_id=None, role=role, label=f"{role}-baseline"
        )
        await s.execute(
            "INSERT INTO agents (id, company_id, role, config_path) "
            "VALUES (?, 'co1', ?, '/dev/null')",
            (issued.record.agent_id, role),
        )

    registry = AgentRegistry(settings.agents_dir, token_store=token_store)
    async with registry.lifespan():
        yield s, registry, token_store
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


async def _seed_pending_task(
    storage: SqliteStorage, *, task_id: str, agent_role: str
) -> None:
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description, submitted_by, status) VALUES "
        "(?, 'co1', 'proj1', ?, 'integration-test pending task', "
        "'agent-pm-bogus', 'pending')",
        (task_id, agent_role),
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
# Test 1: Engineer claims engineer-targeted pending task → happy path
# ---------------------------------------------------------------------------


async def test_engineer_claims_engineer_task_happy_path(
    settings: Settings,
    setup: tuple[SqliteStorage, AgentRegistry, TokenStore],
) -> None:
    """Full request path: Engineer static token → claim_task wrapper →
    handler → atomic UPDATE + audit_log writes. Wrapper-resolved
    agent_id (from access.client_id) lands as task.claimed_by AND on
    the audit row's actor column."""
    storage, registry, token_store = setup
    issued = _issue_test_token(token_store, "engineer", "eng-claim-happy")
    await _seed_agent_for_token(storage, issued, "engineer")
    await _seed_pending_task(
        storage, task_id="t-eng-claim", agent_role="engineer"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, registry):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="claim_task",
            args={"task_id": "t-eng-claim"},
        )

    assert getattr(result, "isError", False) is False, (
        f"claim_task wrapper returned error: "
        f"{getattr(result, 'content', None)!r}"
    )
    out = _structured(result)
    task = out["task"]
    assert isinstance(task, dict)
    assert task["status"] == "claimed"
    assert task["claimed_by"] == issued.record.agent_id

    audit = await storage.fetch_one(
        "SELECT actor, target FROM audit_log "
        "WHERE action = 'task.claim'"
    )
    assert audit is not None
    assert audit["actor"] == issued.record.agent_id
    assert audit["target"] == "t-eng-claim"


# ---------------------------------------------------------------------------
# Test 2: Implementer cannot claim Tester task (sibling-role rejection)
# ---------------------------------------------------------------------------


async def test_implementer_cannot_claim_tester_task(
    settings: Settings,
    setup: tuple[SqliteStorage, AgentRegistry, TokenStore],
) -> None:
    """Spec line 111 strict role-equality: Implementer cannot claim
    Tester tasks even if both report to the same Engineer. Sibling-
    role mismatch rejects at the handler's _check_can_claim gate; MCP
    layer surfaces the rejection structurally; no UPDATE attempted;
    no audit row."""
    storage, registry, token_store = setup
    issued = _issue_test_token(
        token_store, "implementer", "impl-claim-bypass-attempt"
    )
    await _seed_agent_for_token(storage, issued, "implementer")
    await _seed_pending_task(
        storage, task_id="t-tester-target", agent_role="tester"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, registry):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="claim_task",
            args={"task_id": "t-tester-target"},
        )

    assert getattr(result, "isError", False) is True, (
        "Implementer claiming Tester task should reject as strict "
        "role-equality violation; got non-error response."
    )

    # Task remains pending; no audit row.
    row = await storage.fetch_one(
        "SELECT status, claimed_by FROM tasks WHERE id = 't-tester-target'"
    )
    assert row is not None
    assert row["status"] == "pending"
    assert row["claimed_by"] is None
    audits = await storage.fetch_all(
        "SELECT id FROM audit_log WHERE action = 'task.claim'"
    )
    assert audits == []
