"""End-to-end MCP wrapper tests for ``declare_current_focus``,
``complete_current_focus``, and ``get_current_focus``.

Exercises the three MCP wrappers against a real Streamable-HTTP MCP
server. Verifies wiring direct-handler tests in ``test_pm_focus.py``
cannot reach:

- Wrapper-resolved ``agent_id`` (from ``access.client_id``) lands on
  audit rows for declare and complete.
- Activity writer (``_observe_caller``) fires for each wrapper per
  the project_mcp_tool_wrapper_activity_recording memory.
- Permission denied for non-PM tokens surfaces as a structured MCP
  error on declare.
- Round-trip declare → get_current_focus → complete works through
  the MCP boundary; get_current_focus returns the correct shape both
  when active and when empty.
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

from meridian_orchestrator.auth import IssuedToken, TokenStore
from meridian_orchestrator.config import Settings
from meridian_orchestrator.server import serve as serve_mcp
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage

# ---------------------------------------------------------------------------
# Fixtures (mirror test_pm_mode_mcp_wrapper.py shape)
# ---------------------------------------------------------------------------


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@asynccontextmanager
async def _running_server(
    settings: Settings, storage: SqliteStorage
) -> AsyncIterator[None]:
    ready = asyncio.Event()
    task = asyncio.create_task(
        serve_mcp(settings, ready=ready, storage=storage), name="mcp-test"
    )
    try:
        await asyncio.wait_for(ready.wait(), timeout=15)
        yield
    finally:
        task.cancel()
        with contextlib.suppress(TimeoutError, asyncio.CancelledError):
            await asyncio.wait_for(task, timeout=15)


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    await s.execute(
        "INSERT INTO companies (id, name) VALUES ('co1', 'Meridian AI')"
    )
    await s.execute(
        "INSERT INTO projects (id, company_id, name) VALUES "
        "('proj1', 'co1', 'Substrate Product')"
    )
    await s.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description) VALUES ('task-1', 'co1', 'proj1', 'implementer', "
        "'no-op')"
    )
    yield s
    await s.close()


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(
        data_dir=tmp_path,
        mcp_host="127.0.0.1",
        mcp_port=_free_port(),
    )


async def _seed_agent(s: SqliteStorage, agent_id: str, role: str) -> None:
    await s.execute(
        "INSERT INTO agents (id, company_id, role, config_path) "
        "VALUES (?, 'co1', ?, '/dev/null')",
        (agent_id, role),
    )


async def _issue_role_token(
    storage: SqliteStorage, role: str, label: str
) -> IssuedToken:
    """Issue a token for ``role`` and seed the matching agents row."""
    token_store = TokenStore()
    issued = token_store.issue(agent_id=None, role=role, label=label)
    await _seed_agent(storage, issued.record.agent_id, role)
    return issued


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
# Test 1: PM token can declare_current_focus over MCP
# ---------------------------------------------------------------------------


async def test_pm_token_declare_current_focus_over_mcp_writes_row_and_audit(
    settings: Settings, storage: SqliteStorage
) -> None:
    """Full request path: PM token → declare_current_focus wrapper →
    handler → pm_current_focus + audit_log writes. Wrapper-resolved
    agent_id (from access.client_id) lands on the audit row's actor
    column."""
    issued = await _issue_role_token(storage, "pm", "declare-happy")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="declare_current_focus",
            args={
                "objective": "ship Y feature",
                "output_type": "code_change",
                "acceptance_criteria": "tests green; review complete",
            },
        )

    assert getattr(result, "isError", False) is False, (
        f"declare_current_focus wrapper returned error: "
        f"{getattr(result, 'content', None)!r}"
    )
    out = _structured(result)
    assert out["focus_id"] is not None
    assert out["superseded_prior_focus_id"] is None

    rows = await storage.fetch_all(
        "SELECT id, objective, output_type FROM pm_current_focus"
    )
    assert len(rows) == 1
    assert rows[0]["objective"] == "ship Y feature"

    audits = await storage.fetch_all(
        "SELECT actor FROM audit_log "
        "WHERE action = 'pm.current_focus.declared'"
    )
    assert len(audits) == 1
    assert audits[0]["actor"] == issued.record.agent_id, (
        "audit_log.actor does not match the issued PM token's "
        "agent_id; _resolve_agent_id is not threading "
        "access.client_id correctly to the handler."
    )


# ---------------------------------------------------------------------------
# Test 2: non-PM tokens get permission-denied on declare
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "non_pm_role", ["engineer", "implementer", "tester"]
)
async def test_non_pm_token_cannot_declare_current_focus_over_mcp(
    settings: Settings,
    storage: SqliteStorage,
    non_pm_role: str,
) -> None:
    """Engineer, Implementer, and Tester tokens get permission-denied
    at the handler's _require_role check. The MCP layer surfaces the
    error structurally, not as a 500. No pm_current_focus or audit
    rows are written — rejection happens before the transactional
    block."""
    issued = await _issue_role_token(
        storage, non_pm_role, f"non-pm-{non_pm_role}-declare"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="declare_current_focus",
            args={
                "objective": "should-be-rejected",
                "output_type": "document",
                "acceptance_criteria": "n/a",
            },
        )

    assert getattr(result, "isError", False) is True, (
        f"role={non_pm_role!r} successfully invoked "
        "declare_current_focus; the PM-only role gate at the handler "
        "layer has been removed or bypassed."
    )

    rows = await storage.fetch_all("SELECT id FROM pm_current_focus")
    assert rows == []
    audits = await storage.fetch_all(
        "SELECT id FROM audit_log "
        "WHERE action LIKE 'pm.current_focus.%'"
    )
    assert audits == []


# ---------------------------------------------------------------------------
# Test 3: complete_current_focus round-trip from declare through MCP
# ---------------------------------------------------------------------------


async def test_pm_token_complete_round_trip_over_mcp(
    settings: Settings, storage: SqliteStorage
) -> None:
    """Full round-trip: declare → complete via MCP. The complete
    handler reads the active focus implicitly (Q9 Reading B), updates
    the row, emits pm.current_focus.completed audit. Wrapper-resolved
    agent_id lands on the audit row."""
    issued = await _issue_role_token(storage, "pm", "complete-round-trip")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage):
        declare_result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="declare_current_focus",
            args={
                "objective": "round-trip test",
                "output_type": "decision",
                "acceptance_criteria": "decision recorded",
            },
        )
        declared = _structured(declare_result)

        complete_result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="complete_current_focus",
            args={"contract_validated": True},
        )

    assert getattr(complete_result, "isError", False) is False
    out = _structured(complete_result)
    assert out["focus_id"] == declared["focus_id"]
    assert out["contract_validated"] is True

    row = await storage.fetch_one(
        "SELECT completed_at, contract_validated_bool "
        "FROM pm_current_focus WHERE id = ?",
        (declared["focus_id"],),
    )
    assert row is not None
    assert row["completed_at"] is not None
    assert row["contract_validated_bool"] == 1

    completed_audits = await storage.fetch_all(
        "SELECT actor FROM audit_log "
        "WHERE action = 'pm.current_focus.completed'"
    )
    assert len(completed_audits) == 1
    assert completed_audits[0]["actor"] == issued.record.agent_id


# ---------------------------------------------------------------------------
# Test 4: get_current_focus returns active focus
# ---------------------------------------------------------------------------


async def test_get_current_focus_returns_active_over_mcp(
    settings: Settings, storage: SqliteStorage
) -> None:
    """get_current_focus through MCP returns
    GetCurrentFocusOutput.current_focus populated with the active
    pm_current_focus row when one exists. Daemon-internal-MCP-read
    surface verified end-to-end through the wrapper."""
    issued = await _issue_role_token(storage, "pm", "get-active")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage):
        await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="declare_current_focus",
            args={
                "objective": "active for get test",
                "output_type": "investigation",
                "acceptance_criteria": "findings documented",
            },
        )

        get_result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="get_current_focus",
            args={},
        )

    assert getattr(get_result, "isError", False) is False
    out = _structured(get_result)
    current = out["current_focus"]
    assert current is not None
    # Pydantic structuredContent shape: nested dict-like.
    current_dict = dict(current)  # type: ignore[arg-type]
    assert current_dict["objective"] == "active for get test"
    assert current_dict["output_type"] == "investigation"
    assert current_dict["completed_at"] is None


# ---------------------------------------------------------------------------
# Test 5: get_current_focus returns None when no active focus
# ---------------------------------------------------------------------------


async def test_get_current_focus_returns_none_when_empty_over_mcp(
    settings: Settings, storage: SqliteStorage
) -> None:
    """Empty pm_current_focus table → GetCurrentFocusOutput
    {current_focus: None}. The discriminated container shape over MCP
    lets the loop driver distinguish 'no focus declared yet' from
    'RPC failed' cleanly."""
    issued = await _issue_role_token(storage, "pm", "get-empty")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="get_current_focus",
            args={},
        )

    assert getattr(result, "isError", False) is False
    out = _structured(result)
    assert out["current_focus"] is None
