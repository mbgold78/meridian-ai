"""End-to-end MCP wrapper tests for ``update_task_status``.

Exercises the ``update_task_status`` MCP wrapper against a real
Streamable-HTTP MCP server. Covers:

- Wrapper resolves caller identity via ``_resolve_agent_id`` +
  ``_resolve_caller_role_via_token`` (no caller_source needed for
  update_task_status; the cancelled-only clause 2 uses
  ``submitted_by`` JOIN to agents OR ``HUMAN_LITERAL_ROLE_MAP``,
  resolved by the handler's helper).
- Two-clause OR hierarchy gate per spec line 81: claimed_by holder
  updates status; submitter (PM) cancels via clause 2.
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
# Fixtures (mirror test_claim_task_mcp_wrapper.py shape)
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
) -> AsyncIterator[
    tuple[SqliteStorage, AgentRegistry, TokenStore, dict[str, str]]
]:
    """Storage + Registry + TokenStore with the four-role roster seeded.

    Returns the seeded baseline-token agent_ids keyed by role so tests
    can construct submitted_by references that JOIN cleanly to
    agents."""
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
# Test 1: claimed_by holder transitions claimed -> in_progress (clause 1)
# ---------------------------------------------------------------------------


async def test_claimed_by_holder_updates_status_via_clause_1(
    settings: Settings,
    setup: tuple[
        SqliteStorage, AgentRegistry, TokenStore, dict[str, str]
    ],
) -> None:
    """Full request path: Engineer static token (claimed_by holder) →
    update_task_status wrapper → handler → atomic UPDATE + audit_log
    writes. Wrapper-resolved agent_id matches task.claimed_by; clause 1
    of the hierarchy gate passes."""
    storage, registry, token_store, _baseline_ids = setup
    issued = _issue_test_token(token_store, "engineer", "eng-update-claim")
    await _seed_agent_for_token(storage, issued, "engineer")

    # Seed a claimed-by-this-engineer task in 'claimed' state.
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description, status, claimed_by) VALUES "
        "('t-update-c1', 'co1', 'proj1', 'engineer', 'in flight', "
        "'claimed', ?)",
        (issued.record.agent_id,),
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, registry):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="update_task_status",
            args={
                "task_id": "t-update-c1",
                "status": "in_progress",
                "notes": "started impl",
            },
        )

    assert getattr(result, "isError", False) is False, (
        f"update_task_status wrapper returned error: "
        f"{getattr(result, 'content', None)!r}"
    )
    out = _structured(result)
    task = out["task"]
    assert isinstance(task, dict)
    assert task["status"] == "in_progress"
    assert task["notes"] == "started impl"

    audit = await storage.fetch_one(
        "SELECT actor, target, details_json FROM audit_log "
        "WHERE action = 'task.status_change'"
    )
    assert audit is not None
    assert audit["actor"] == issued.record.agent_id
    details = json.loads(audit["details_json"])
    assert details["from_status"] == "claimed"
    assert details["to_status"] == "in_progress"


# ---------------------------------------------------------------------------
# Test 2: PM cancels via clause 2 (cancelled-only submitter-role check)
# ---------------------------------------------------------------------------


async def test_pm_submitter_cancels_via_clause_2(
    settings: Settings,
    setup: tuple[
        SqliteStorage, AgentRegistry, TokenStore, dict[str, str]
    ],
) -> None:
    """Spec line 81 clause 2 (cancelled-only): PM submitted a task;
    Engineer claimed it; PM (submitter) cancels via clause 2 even
    though PM is not claimed_by. Verifies the conditional second
    clause activates only for target_status='cancelled'."""
    storage, registry, token_store, baseline_ids = setup
    pm_issued = _issue_test_token(token_store, "pm", "pm-cancel")
    await _seed_agent_for_token(storage, pm_issued, "pm")

    # Seed a PM-submitted, Engineer-claimed task.
    eng_agent_id = baseline_ids["engineer"]
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description, status, submitted_by, claimed_by) VALUES "
        "('t-pm-cancel', 'co1', 'proj1', 'engineer', 'will-be-cancelled', "
        "'claimed', ?, ?)",
        (pm_issued.record.agent_id, eng_agent_id),
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, registry):
        result = await _call_tool(
            base_url=base_url,
            token=pm_issued.token,
            tool_name="update_task_status",
            args={
                "task_id": "t-pm-cancel",
                "status": "cancelled",
                "notes": "pivoting; abandoning",
            },
        )

    assert getattr(result, "isError", False) is False, (
        f"PM cancel via clause 2 unexpectedly errored: "
        f"{getattr(result, 'content', None)!r}"
    )
    out = _structured(result)
    task = out["task"]
    assert isinstance(task, dict)
    assert task["status"] == "cancelled"

    audit = await storage.fetch_one(
        "SELECT actor, details_json FROM audit_log "
        "WHERE action = 'task.status_change'"
    )
    assert audit is not None
    assert audit["actor"] == pm_issued.record.agent_id
    details = json.loads(audit["details_json"])
    assert details["from_status"] == "claimed"
    assert details["to_status"] == "cancelled"
