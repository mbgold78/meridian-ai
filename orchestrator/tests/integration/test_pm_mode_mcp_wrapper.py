"""End-to-end MCP wrapper tests for ``set_pm_mode``.

Exercises the ``set_pm_mode`` MCP wrapper against a real
Streamable-HTTP MCP server. Verifies wiring that direct-handler tests
in ``test_pm_mode.py`` cannot reach:

- Wrapper-resolved ``agent_id`` (from ``access.client_id``) and
  ``caller_role`` (from OAuth scope) thread through to the handler
  and land on the audit row.
- Activity writer (``_record_caller_activity``) fires for the
  wrapper per the project_mcp_tool_wrapper_activity_recording memory.
- Permission denied for non-PM tokens surfaces as a structured MCP
  error, not a 500.
- Pydantic validation rejects invalid mode values at the boundary.

Direct-handler tests (transactional rollback regression, the three
contract tests, etc.) live in ``tests/unit/tools/test_pm_mode.py``.
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
# Fixtures
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
        "INSERT INTO tasks (id, company_id, project_id, agent_role, description) "
        "VALUES ('task-1', 'co1', 'proj1', 'implementer', 'no-op')"
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
    issued = await token_store.issue(agent_id=None, role=role, label=label)
    await _seed_agent(storage, issued.record.agent_id, role)
    return issued


# ---------------------------------------------------------------------------
# MCP client helpers
# ---------------------------------------------------------------------------


async def _call_set_pm_mode(
    *,
    base_url: str,
    token: str,
    mode: str,
    reason: str | None = None,
) -> object:
    url = f"{base_url}/mcp"
    args: dict[str, object] = {"mode": mode}
    if reason is not None:
        args["reason"] = reason
    headers = {"Authorization": f"Bearer {token}"}
    async with (
        streamablehttp_client(url, headers=headers) as (read, write, _),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        return await session.call_tool("set_pm_mode", args)


def _structured(result: object) -> dict[str, object]:
    structured = getattr(result, "structuredContent", None)
    if structured is not None:
        return dict(structured)
    text = result.content[0].text  # type: ignore[union-attr]
    return dict(json.loads(text))


# ---------------------------------------------------------------------------
# Test 1: PM token can set_pm_mode over MCP, agent_id lands on audit
# ---------------------------------------------------------------------------


async def test_pm_token_set_pm_mode_over_mcp_writes_pm_mode_log_and_audit(
    settings: Settings, storage: SqliteStorage
) -> None:
    """The full request path: PM token → set_pm_mode wrapper →
    handler → pm_mode_log + audit_log writes. The wrapper-resolved
    agent_id (from access.client_id) lands on the audit row's actor
    column."""
    issued = await _issue_role_token(storage, "pm", "set-pm-mode-happy")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage):
        result = await _call_set_pm_mode(
            base_url=base_url,
            token=issued.token,
            mode="paused_question",
            reason="surfacing blocker",
        )

    assert getattr(result, "isError", False) is False, (
        f"set_pm_mode wrapper returned error: "
        f"{getattr(result, 'content', None)!r}"
    )
    out = _structured(result)
    assert out["mode"] == "paused_question"
    assert out["transition_logged"] is True

    # pm_mode_log row written.
    rows = await storage.fetch_all(
        "SELECT mode, reason, triggered_by FROM pm_mode_log"
    )
    assert len(rows) == 1
    assert rows[0]["mode"] == "paused_question"
    assert rows[0]["triggered_by"] == "manual"

    # Audit row's actor matches wrapper-resolved agent_id.
    audits = await storage.fetch_all(
        "SELECT actor FROM audit_log WHERE action = 'pm_mode.transition'"
    )
    assert len(audits) == 1
    assert audits[0]["actor"] == issued.record.agent_id, (
        "audit_log.actor does not match the issued PM token's "
        "agent_id; _resolve_agent_id is not threading "
        "access.client_id correctly from the live request context "
        "to the handler's caller_agent_id."
    )


# ---------------------------------------------------------------------------
# Test 2: activity writer fires for the wrapper
# ---------------------------------------------------------------------------


async def test_set_pm_mode_wrapper_fires_activity_writer(
    settings: Settings, storage: SqliteStorage
) -> None:
    """Verify the wrapper calls _record_caller_activity per
    project_mcp_tool_wrapper_activity_recording memory: every MCP
    tool wrapper must update last_activity_at and last_tool_called.
    Mirror of test_record_api_call_wrapper_fires_activity_writer in
    test_record_api_call_mcp_wrapper.py.

    Activity writing is independent of the pm_mode_log INSERT —
    different writers, different columns, different tables. Both
    fire on the same call when the dispatch row matches.
    """
    issued = await _issue_role_token(storage, "pm", "set-pm-mode-activity")
    # Pre-seed a dispatch row whose token_id matches the issued token
    # so _record_caller_activity finds a non-terminal dispatch to
    # update.
    await storage.execute(
        "INSERT INTO dispatches "
        "(id, task_id, role, status, working_directory, "
        "timeout_seconds, token_id) "
        "VALUES ('d-pm-mode-act', 'task-1', 'pm-continuous', 'running', "
        "'/tmp/wt', 300, ?)",
        (issued.record.token_id,),
    )

    pre = await storage.fetch_one(
        "SELECT last_activity_at, last_tool_called FROM dispatches "
        "WHERE id = 'd-pm-mode-act'"
    )
    assert pre is not None
    assert pre["last_activity_at"] is None
    assert pre["last_tool_called"] is None

    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"
    async with _running_server(settings, storage):
        result = await _call_set_pm_mode(
            base_url=base_url, token=issued.token, mode="continuous"
        )

    assert getattr(result, "isError", False) is False

    post = await storage.fetch_one(
        "SELECT last_activity_at, last_tool_called FROM dispatches "
        "WHERE id = 'd-pm-mode-act'"
    )
    assert post is not None
    assert post["last_activity_at"] is not None, (
        "set_pm_mode wrapper failed to update last_activity_at — "
        "_record_caller_activity is not wired into the wrapper. See "
        "project_mcp_tool_wrapper_activity_recording memory: every "
        "MCP tool wrapper must call _record_caller_activity right "
        "after _resolve_agent_id."
    )
    assert post["last_tool_called"] == "set_pm_mode"


# ---------------------------------------------------------------------------
# Test 3: non-PM tokens get permission-denied
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "non_pm_role", ["engineer", "implementer", "tester"]
)
async def test_non_pm_token_cannot_set_pm_mode_over_mcp(
    settings: Settings,
    storage: SqliteStorage,
    non_pm_role: str,
) -> None:
    """Engineer, Implementer, and Tester tokens get permission-denied
    at the handler's _require_role check. The MCP layer surfaces the
    error structurally rather than as a 500. No pm_mode_log or audit
    rows are written — the rejection happens before the
    transactional block."""
    issued = await _issue_role_token(
        storage, non_pm_role, f"non-pm-{non_pm_role}"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage):
        result = await _call_set_pm_mode(
            base_url=base_url, token=issued.token, mode="continuous"
        )

    assert getattr(result, "isError", False) is True, (
        f"role={non_pm_role!r} successfully invoked set_pm_mode; "
        "the PM-only role gate at the handler layer has been "
        "removed or bypassed."
    )

    # No state changes on rejected calls.
    rows = await storage.fetch_all("SELECT id FROM pm_mode_log")
    assert rows == []
    audits = await storage.fetch_all(
        "SELECT id FROM audit_log WHERE action = 'pm_mode.transition'"
    )
    assert audits == []


# ---------------------------------------------------------------------------
# Test 4: invalid mode rejected at Pydantic boundary
# ---------------------------------------------------------------------------


async def test_invalid_mode_rejected_as_structured_error(
    settings: Settings, storage: SqliteStorage
) -> None:
    """SetPmModeInput.mode is a Literal of four values; values outside
    that set must be rejected at the Pydantic validation boundary as
    a structured MCP error, not a 500. No pm_mode_log row is written.

    Guards the input contract: a future regression that loosens the
    Literal to plain str (or that adds a wrapper-layer cast masking
    validation) would surface here as the test going green when it
    should still fail validation.
    """
    issued = await _issue_role_token(
        storage, "pm", "set-pm-mode-invalid"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage):
        result = await _call_set_pm_mode(
            base_url=base_url,
            token=issued.token,
            mode="paused",  # not in the canonical 4-value enum
        )

    assert getattr(result, "isError", False) is True, (
        "invalid mode value did not surface as a structured MCP "
        "error; Pydantic Literal constraint may have been loosened "
        "or the wrapper is masking validation errors."
    )
    rows = await storage.fetch_all("SELECT id FROM pm_mode_log")
    assert rows == [], (
        "pm_mode_log row was written despite validation failure; "
        "the wrapper invoked the handler before Pydantic rejected "
        "the input."
    )
