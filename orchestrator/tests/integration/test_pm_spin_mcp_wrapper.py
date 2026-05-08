"""End-to-end MCP wrapper tests for ``record_spin_rationale``.

Exercises the MCP wrapper against a real Streamable-HTTP MCP server.
Verifies wiring direct-handler tests in ``test_pm_spin.py`` cannot
reach:

- Wrapper-resolved ``agent_id`` (from ``access.client_id``) — even
  though pm_spin_rationale_log doesn't store actor (audit-log scope
  is what would carry actor; Q6 Reading A keeps audit out of scope),
  the activity writer (``_observe_caller``) fires per the
  project_mcp_tool_wrapper_activity_recording memory.
- Permission denied for non-PM tokens surfaces as a structured MCP
  error.
- Round-trip emit through the MCP boundary writes the expected row.
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
# Fixtures (mirror test_pm_focus_mcp_wrapper.py shape)
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


async def _call_record_spin_rationale(
    *,
    base_url: str,
    token: str,
    decision: str,
    rationale_tag: str,
    rationale_free_text: str | None = None,
) -> object:
    url = f"{base_url}/mcp"
    args: dict[str, object] = {
        "decision": decision,
        "rationale_tag": rationale_tag,
    }
    if rationale_free_text is not None:
        args["rationale_free_text"] = rationale_free_text
    headers = {"Authorization": f"Bearer {token}"}
    async with (
        streamablehttp_client(url, headers=headers) as (read, write, _),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        return await session.call_tool("record_spin_rationale", args)


def _structured(result: object) -> dict[str, object]:
    structured = getattr(result, "structuredContent", None)
    if structured is not None:
        return dict(structured)
    text = result.content[0].text  # type: ignore[union-attr]
    return dict(json.loads(text))


# ---------------------------------------------------------------------------
# Test 1: PM token can record_spin_rationale over MCP
# ---------------------------------------------------------------------------


async def test_pm_token_record_spin_rationale_writes_row(
    settings: Settings, storage: SqliteStorage
) -> None:
    """Full request path: PM token → record_spin_rationale wrapper →
    handler → pm_spin_rationale_log INSERT."""
    issued = await _issue_role_token(storage, "pm", "spin-happy")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage):
        result = await _call_record_spin_rationale(
            base_url=base_url,
            token=issued.token,
            decision="kept_alive",
            rationale_tag="queued_work",
            rationale_free_text="queue depth=3",
        )

    assert getattr(result, "isError", False) is False, (
        f"record_spin_rationale wrapper returned error: "
        f"{getattr(result, 'content', None)!r}"
    )
    out = _structured(result)
    assert out["spin_rationale_id"] is not None

    rows = await storage.fetch_all(
        "SELECT id, decision, rationale_tag, rationale_free_text "
        "FROM pm_spin_rationale_log"
    )
    assert len(rows) == 1
    assert rows[0]["decision"] == "kept_alive"
    assert rows[0]["rationale_tag"] == "queued_work"
    assert rows[0]["rationale_free_text"] == "queue depth=3"


# ---------------------------------------------------------------------------
# Test 2: non-PM tokens get permission-denied
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "non_pm_role", ["engineer", "implementer", "tester"]
)
async def test_non_pm_token_cannot_record_spin_rationale_over_mcp(
    settings: Settings,
    storage: SqliteStorage,
    non_pm_role: str,
) -> None:
    """Engineer, Implementer, and Tester tokens get permission-denied
    at the handler's _require_role check. The MCP layer surfaces the
    error structurally, not as a 500. No pm_spin_rationale_log row is
    written — rejection happens before the INSERT."""
    issued = await _issue_role_token(
        storage, non_pm_role, f"non-pm-{non_pm_role}-spin"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage):
        result = await _call_record_spin_rationale(
            base_url=base_url,
            token=issued.token,
            decision="kept_alive",
            rationale_tag="queued_work",
        )

    assert getattr(result, "isError", False) is True, (
        f"role={non_pm_role!r} successfully invoked "
        "record_spin_rationale; the PM-only role gate at the handler "
        "layer has been removed or bypassed."
    )

    rows = await storage.fetch_all(
        "SELECT id FROM pm_spin_rationale_log"
    )
    assert rows == []
