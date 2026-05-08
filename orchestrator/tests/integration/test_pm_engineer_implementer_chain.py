"""End-to-end chain tests covering cross-tool sequencing.

M4d-task-management-surface commit 8: chain-level e2e coverage that
per-tool integration tests at commits 3-7 cannot reach.

Three focused tests by phase per CQ1 Reading B disposition:

1. **Full-cycle happy path** — Mike (OAuth-PM) submits a task targeting
   Engineer; Engineer (static) claims it; Engineer updates status
   through the lifecycle (claimed → in_progress → completed); audit
   chain reconstructs the cycle.
2. **Double-claim within cycle raises CONFLICT** — Engineer A claims a
   pending task; Engineer B (different agent_id, same role) attempts
   claim; second claim's atomic UPDATE matches 0 rows; CONFLICT
   raised.
3. **Decline propagation via update_task_status** — Engineer claims
   task; Engineer marks declined with notes; PM observes via
   get_queue and audit chain captures the decline.

NOT a substitute for the M4d acceptance demo (per
``docs/working/M4d-acceptance-demo-criteria.md``); these tests verify
the wire-shape works and the cycle completes without manual SQL.
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
    """Storage + Registry + TokenStore with the four-role roster + a
    second engineer agent (for double-claim test) seeded."""
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
    token_store: TokenStore,
    role: str,
    label: str,
    *,
    source: str = "static",
) -> IssuedToken:
    return token_store.issue(
        agent_id=None, role=role, label=label, source=source  # type: ignore[arg-type]
    )


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
# Test 1: Full-cycle happy path (Mike-OAuth submit → Engineer-static
# claim → status lifecycle → audit chain)
# ---------------------------------------------------------------------------


async def test_full_cycle_oauth_submit_static_claim_status_lifecycle(
    settings: Settings,
    setup: tuple[
        SqliteStorage, AgentRegistry, TokenStore, dict[str, str]
    ],
) -> None:
    """Cross-tool sequencing: Mike (OAuth-PM) submits → Engineer
    (static) claims → Engineer drives status transitions claimed →
    in_progress → completed. Audit chain reconstructs all four
    state-changing events. Verifies wire-shape works end-to-end via
    real MCP path; cohort coverage (submitted_by='human:mike' for
    OAuth path) lands at the data layer."""
    storage, registry, token_store, _baseline = setup
    pm_token = _issue_test_token(
        token_store, "pm", "cycle-pm-oauth", source="oauth"
    )
    eng_token = _issue_test_token(token_store, "engineer", "cycle-eng")
    await _seed_agent_for_token(storage, pm_token, "pm")
    await _seed_agent_for_token(storage, eng_token, "engineer")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, registry):
        # Phase 1: PM (OAuth) submits engineer-targeted task.
        submit_result = await _call_tool(
            base_url=base_url,
            token=pm_token.token,
            tool_name="submit_task",
            args={
                "agent_role": "engineer",
                "description": "ship cycle test feature",
                "task_type": "execution",
            },
        )
        assert getattr(submit_result, "isError", False) is False
        submitted = _structured(submit_result)
        task = submitted["task"]
        assert isinstance(task, dict)
        task_id = str(task["id"])
        assert task["submitted_by"] == "human:mike"
        assert task["status"] == "pending"

        # Phase 2: Engineer claims pending task.
        claim_result = await _call_tool(
            base_url=base_url,
            token=eng_token.token,
            tool_name="claim_task",
            args={"task_id": task_id},
        )
        assert getattr(claim_result, "isError", False) is False
        claimed = _structured(claim_result)
        claimed_task = claimed["task"]
        assert isinstance(claimed_task, dict)
        assert claimed_task["status"] == "claimed"
        assert claimed_task["claimed_by"] == eng_token.record.agent_id

        # Phase 3: Engineer transitions claimed → in_progress.
        progress_result = await _call_tool(
            base_url=base_url,
            token=eng_token.token,
            tool_name="update_task_status",
            args={"task_id": task_id, "status": "in_progress"},
        )
        assert getattr(progress_result, "isError", False) is False

        # Phase 4: Engineer transitions in_progress → completed.
        complete_result = await _call_tool(
            base_url=base_url,
            token=eng_token.token,
            tool_name="update_task_status",
            args={"task_id": task_id, "status": "completed"},
        )
        assert getattr(complete_result, "isError", False) is False
        completed = _structured(complete_result)
        completed_task = completed["task"]
        assert isinstance(completed_task, dict)
        assert completed_task["status"] == "completed"

    # Audit chain reconstruction: 4 state-changing events captured.
    audits = await storage.fetch_all(
        "SELECT action, target FROM audit_log "
        "WHERE target = ? ORDER BY rowid",
        (task_id,),
    )
    actions = [a["action"] for a in audits]
    assert "task.submit" in actions
    assert "task.claim" in actions
    # Two task.status_change rows (claimed→in_progress, in_progress→completed).
    assert actions.count("task.status_change") == 2


# ---------------------------------------------------------------------------
# Test 2: Double-claim within cycle raises CONFLICT
# ---------------------------------------------------------------------------


async def test_double_claim_within_cycle_raises_conflict(
    settings: Settings,
    setup: tuple[
        SqliteStorage, AgentRegistry, TokenStore, dict[str, str]
    ],
) -> None:
    """Engineer A claims a pending task; Engineer B (different
    agent_id, same role) attempts claim; second claim's atomic UPDATE
    matches 0 rows because status flipped to 'claimed'. CONFLICT
    surfaces structurally via MCP. Audit chain captures only the
    successful first claim."""
    storage, registry, token_store, _baseline = setup
    pm_token = _issue_test_token(
        token_store, "pm", "double-claim-pm", source="oauth"
    )
    eng_a = _issue_test_token(token_store, "engineer", "double-claim-eng-a")
    eng_b = _issue_test_token(token_store, "engineer", "double-claim-eng-b")
    await _seed_agent_for_token(storage, pm_token, "pm")
    await _seed_agent_for_token(storage, eng_a, "engineer")
    await _seed_agent_for_token(storage, eng_b, "engineer")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, registry):
        # PM submits engineer-targeted task.
        submit_result = await _call_tool(
            base_url=base_url,
            token=pm_token.token,
            tool_name="submit_task",
            args={
                "agent_role": "engineer",
                "description": "double-claim race test",
            },
        )
        task_id = str(_structured(submit_result)["task"]["id"])  # type: ignore[index]

        # Engineer A claims successfully.
        claim_a = await _call_tool(
            base_url=base_url,
            token=eng_a.token,
            tool_name="claim_task",
            args={"task_id": task_id},
        )
        assert getattr(claim_a, "isError", False) is False

        # Engineer B's claim attempt MUST raise CONFLICT (atomic
        # WHERE status='pending' clause matches 0 rows post-A-claim).
        claim_b = await _call_tool(
            base_url=base_url,
            token=eng_b.token,
            tool_name="claim_task",
            args={"task_id": task_id},
        )
        assert getattr(claim_b, "isError", False) is True, (
            "Second claim on already-claimed task should raise "
            "CONFLICT structurally; got non-error response."
        )

    # Task remains claimed by Engineer A; Engineer B's attempt was no-op.
    row = await storage.fetch_one(
        "SELECT claimed_by, status FROM tasks WHERE id = ?", (task_id,)
    )
    assert row is not None
    assert row["claimed_by"] == eng_a.record.agent_id
    assert row["status"] == "claimed"

    # Audit chain captures only Engineer A's successful claim.
    claim_audits = await storage.fetch_all(
        "SELECT actor FROM audit_log "
        "WHERE target = ? AND action = 'task.claim'",
        (task_id,),
    )
    assert len(claim_audits) == 1
    assert claim_audits[0]["actor"] == eng_a.record.agent_id


# ---------------------------------------------------------------------------
# Test 3: Decline propagation via update_task_status
# ---------------------------------------------------------------------------


async def test_decline_propagation_via_update_task_status(
    settings: Settings,
    setup: tuple[
        SqliteStorage, AgentRegistry, TokenStore, dict[str, str]
    ],
) -> None:
    """Engineer claims a task; Engineer marks declined with notes via
    update_task_status('declined'). PM observes the decline via
    get_queue (status filter). Audit chain captures the decline event
    with from_status='claimed', to_status='declined' in
    details_json."""
    storage, registry, token_store, _baseline = setup
    pm_token = _issue_test_token(
        token_store, "pm", "decline-pm", source="oauth"
    )
    eng_token = _issue_test_token(token_store, "engineer", "decline-eng")
    await _seed_agent_for_token(storage, pm_token, "pm")
    await _seed_agent_for_token(storage, eng_token, "engineer")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, registry):
        # PM submits + Engineer claims.
        submit_result = await _call_tool(
            base_url=base_url,
            token=pm_token.token,
            tool_name="submit_task",
            args={
                "agent_role": "engineer",
                "description": "decline path test",
            },
        )
        task_id = str(_structured(submit_result)["task"]["id"])  # type: ignore[index]
        await _call_tool(
            base_url=base_url,
            token=eng_token.token,
            tool_name="claim_task",
            args={"task_id": task_id},
        )

        # Engineer declines with notes.
        decline_result = await _call_tool(
            base_url=base_url,
            token=eng_token.token,
            tool_name="update_task_status",
            args={
                "task_id": task_id,
                "status": "declined",
                "notes": "out of scope; needs refactor first",
            },
        )
        assert getattr(decline_result, "isError", False) is False
        declined = _structured(decline_result)
        declined_task = declined["task"]
        assert isinstance(declined_task, dict)
        assert declined_task["status"] == "declined"
        assert declined_task["notes"] == "out of scope; needs refactor first"

        # PM observes decline via get_queue with status filter.
        queue_result = await _call_tool(
            base_url=base_url,
            token=pm_token.token,
            tool_name="get_queue",
            args={"status": ["declined"]},
        )
        assert getattr(queue_result, "isError", False) is False
        queue = _structured(queue_result)
        tasks = queue["tasks"]
        assert isinstance(tasks, list)
        assert any(t["id"] == task_id for t in tasks)

    # Audit chain captures decline with from/to status detail.
    audit = await storage.fetch_one(
        "SELECT details_json FROM audit_log "
        "WHERE target = ? AND action = 'task.status_change' "
        "ORDER BY rowid DESC LIMIT 1",
        (task_id,),
    )
    assert audit is not None
    details = json.loads(audit["details_json"])
    assert details["from_status"] == "claimed"
    assert details["to_status"] == "declined"
    assert details["notes"] == "out of scope; needs refactor first"
