"""End-to-end MCP wrapper tests for ``submit_task``.

Exercises the ``submit_task`` MCP wrapper against a real
Streamable-HTTP MCP server. Covers the wiring direct-handler tests in
``test_tasks.py`` cannot reach:

- Wrapper resolves caller identity via ``_resolve_agent_id`` +
  ``_resolve_caller_role_via_token`` + ``_resolve_caller_source`` (the
  third is new at M4d-task-management-surface commit 3 per Q9 (A)
  caller-distinction fold).
- OAuth-vs-static token distinction lands at the data layer per CQ3
  Reading A: ``submitted_by="human:mike"`` for OAuth callers,
  ``submitted_by=caller_agent_id`` for static.
- Hierarchy bypass per spec line 44: PM's token cannot submit for
  ``agent_role='implementer'`` (skipping engineer); MCP layer surfaces
  the rejection structurally.
- Subtask submission with parent ownership via clause 1 (claimed_by)
  succeeds round-trip.
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
# Fixtures (mirror test_dispatch_mcp_wrappers.py shape with task seeds)
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


@pytest_asyncio.fixture
async def setup(
    settings: Settings,
) -> AsyncIterator[tuple[SqliteStorage, AgentRegistry, TokenStore]]:
    """Storage + Registry + TokenStore with the four-role roster seeded.

    Seeds Meridian AI company + Substrate Product project + four agent
    rows (one per role); issues a static token for each role; loads the
    AgentRegistry from the repo's canonical agent yamls. Tests then
    issue per-test tokens (static or OAuth) on top of the seeded
    roster.
    """
    _seed_repo_yamls(settings.agents_dir)

    s = SqliteStorage(tmp_path_for_settings(settings))
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
    # Issue baseline static tokens for the four roles + seed agents
    # rows so registry.lifespan() can resolve reports_to.
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


def tmp_path_for_settings(settings: Settings) -> Path:
    """Storage path matches ``Settings.data_dir/data/orchestrator.db``
    convention."""
    db_dir = settings.data_dir / "data"
    db_dir.mkdir(parents=True, exist_ok=True)
    return db_dir / "orchestrator.db"


def _issue_test_token(
    token_store: TokenStore,
    role: str,
    label: str,
    *,
    source: str = "static",
) -> IssuedToken:
    return token_store.issue(
        agent_id=None,
        role=role,
        label=label,
        source=source,  # type: ignore[arg-type]
    )


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
# Test 1: PM static token → submit_task happy path
# ---------------------------------------------------------------------------


async def test_pm_static_token_submit_task_happy_path(
    settings: Settings,
    setup: tuple[SqliteStorage, AgentRegistry, TokenStore],
) -> None:
    """Full request path: PM static token → submit_task wrapper →
    handler → tasks + audit_log writes. Wrapper-resolved agent_id
    (from access.client_id) lands on the audit row's actor column;
    submitted_by=agent_id per CQ3 static path."""
    storage, registry, token_store = setup
    issued = _issue_test_token(token_store, "pm", "pm-submit-happy")
    await _seed_agent_for_token(storage, issued, "pm")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, registry):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="submit_task",
            args={
                "agent_role": "engineer",
                "description": "ship feature X",
                "task_type": "planning",
            },
        )

    assert getattr(result, "isError", False) is False, (
        f"submit_task wrapper returned error: "
        f"{getattr(result, 'content', None)!r}"
    )
    out = _structured(result)
    task = out["task"]
    assert isinstance(task, dict)
    assert task["agent_role"] == "engineer"
    assert task["task_type"] == "planning"
    assert task["submitted_by"] == issued.record.agent_id

    audits = await storage.fetch_all(
        "SELECT actor, target FROM audit_log WHERE action = 'task.submit'"
    )
    assert len(audits) == 1
    assert audits[0]["actor"] == issued.record.agent_id


# ---------------------------------------------------------------------------
# Test 2: PM-via-OAuth (Mike) self-submit → submitted_by="human:mike"
# ---------------------------------------------------------------------------


async def test_oauth_pm_self_submit_records_human_mike_literal(
    settings: Settings,
    setup: tuple[SqliteStorage, AgentRegistry, TokenStore],
) -> None:
    """Mike-via-OAuth self-submit allowed per Q9 (A); submitted_by
    populates as 'human:mike' literal per CQ3 Reading A. Verifies the
    OAuth-vs-static distinction lands at the data layer."""
    storage, registry, token_store = setup
    issued = _issue_test_token(
        token_store, "pm", "mike-oauth-self-submit", source="oauth"
    )
    await _seed_agent_for_token(storage, issued, "pm")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, registry):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="submit_task",
            args={
                "agent_role": "pm",  # self-submit
                "description": "Mike delegates a PM-shape task to himself",
            },
        )

    assert getattr(result, "isError", False) is False, (
        f"OAuth self-submit unexpectedly errored: "
        f"{getattr(result, 'content', None)!r}"
    )
    out = _structured(result)
    task = out["task"]
    assert isinstance(task, dict)
    assert task["submitted_by"] == "human:mike"

    # CQ3 belt-and-suspenders: caller_source captured in audit details.
    audit = await storage.fetch_one(
        "SELECT details_json FROM audit_log WHERE action = 'task.submit'"
    )
    assert audit is not None
    details = json.loads(audit["details_json"])
    assert details["caller_source"] == "oauth"
    assert details["submitted_by"] == "human:mike"


# ---------------------------------------------------------------------------
# Test 3: PM submitting for implementer is hierarchy bypass per spec line 44
# ---------------------------------------------------------------------------


async def test_pm_static_token_cannot_submit_for_implementer(
    settings: Settings,
    setup: tuple[SqliteStorage, AgentRegistry, TokenStore],
) -> None:
    """Spec line 44: 'PM **cannot** submit a task with
    agent_role='implementer' — that's a hierarchy bypass.' The handler's
    _check_can_submit_task uses manages_directly (NOT is_descendant);
    transitive PM→Engineer→Implementer reads reject. MCP layer surfaces
    the rejection structurally; no tasks or audit rows written."""
    storage, registry, token_store = setup
    issued = _issue_test_token(token_store, "pm", "pm-bypass-attempt")
    await _seed_agent_for_token(storage, issued, "pm")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, registry):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="submit_task",
            args={
                "agent_role": "implementer",
                "description": "should-be-rejected hierarchy bypass",
            },
        )

    assert getattr(result, "isError", False) is True, (
        "PM submitting for implementer should reject as hierarchy "
        "bypass; got non-error response."
    )

    # No tasks or task.submit audit rows.
    rows = await storage.fetch_all("SELECT id FROM tasks")
    assert rows == []
    audits = await storage.fetch_all(
        "SELECT id FROM audit_log WHERE action = 'task.submit'"
    )
    assert audits == []


# ---------------------------------------------------------------------------
# Test 4: Engineer claims parent → submits subtask via clause 1 ownership
# ---------------------------------------------------------------------------


async def test_engineer_submits_subtask_via_clause_1_claimed_by(
    settings: Settings,
    setup: tuple[SqliteStorage, AgentRegistry, TokenStore],
) -> None:
    """Engineer claims a parent task; submits an Implementer-targeted
    subtask under it. Clause 1 (caller_agent_id == parent.claimed_by)
    passes; subtask inherits parent's company_id/project_id without
    re-derivation."""
    storage, registry, token_store = setup
    issued = _issue_test_token(token_store, "engineer", "eng-subtask")
    await _seed_agent_for_token(storage, issued, "engineer")

    # PM submits a parent task; engineer claims it.
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description, submitted_by, claimed_by, status) VALUES "
        "('root-1', 'co1', 'proj1', 'engineer', 'parent', "
        "'agent-pm-bogus', ?, 'claimed')",
        (issued.record.agent_id,),
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, registry):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="submit_task",
            args={
                "agent_role": "implementer",
                "description": "implement this slice",
                "parent_task_id": "root-1",
            },
        )

    assert getattr(result, "isError", False) is False, (
        f"Subtask submission via clause 1 unexpectedly errored: "
        f"{getattr(result, 'content', None)!r}"
    )
    out = _structured(result)
    task = out["task"]
    assert isinstance(task, dict)
    assert task["parent_task_id"] == "root-1"
    assert task["company_id"] == "co1"
    assert task["project_id"] == "proj1"
    assert task["agent_role"] == "implementer"
