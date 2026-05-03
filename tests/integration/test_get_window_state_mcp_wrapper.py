"""End-to-end MCP wrapper tests for ``get_window_state``.

Exercises the wrapper against a real Streamable-HTTP MCP server with
a fake snapshot provider injected via ``serve(...,
get_oauth_usage_snapshot=...)``. Direct-handler tests (pace_recommendation
table, cold-start, stale, bucket-null, agent_id silent-ignore) live
in ``tests/unit/tools/test_get_window_state.py``.

Coverage:

- PM token over MCP → structured response with populated fields.
- Engineer token over MCP → structured response.
- Implementer/Tester rejected with structured MCP error
  (parametrized).
- Invalid bucket value → Pydantic validation error at the boundary.
- Wrapper fires ``_observe_caller`` (per
  ``project_mcp_tool_wrapper_activity_recording`` memory).
"""

from __future__ import annotations

import asyncio
import contextlib
import socket
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
import pytest_asyncio
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client

from meridian_orchestrator.auth import IssuedToken, TokenStore
from meridian_orchestrator.bridge.oauth_usage import CachedSnapshot
from meridian_orchestrator.config import Settings
from meridian_orchestrator.server import serve as serve_mcp
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _make_snapshot() -> CachedSnapshot:
    """Build a fresh snapshot for the tests. subscription_5h at 17%
    utilization, resets in 4 hours."""
    polled_at = datetime.now(tz=UTC)
    resets_at = (polled_at + timedelta(hours=4)).isoformat(
        timespec="microseconds"
    )
    return CachedSnapshot(
        payload={
            "five_hour": {"utilization": 17.0, "resets_at": resets_at},
            "seven_day": {
                "utilization": 15.0,
                "resets_at": (
                    polled_at + timedelta(days=6)
                ).isoformat(timespec="microseconds"),
            },
        },
        polled_at=polled_at,
        null_counters=[],
    )


@asynccontextmanager
async def _running_server(
    settings: Settings,
    storage: SqliteStorage,
    snapshot: CachedSnapshot | None,
) -> AsyncIterator[None]:
    ready = asyncio.Event()
    task = asyncio.create_task(
        serve_mcp(
            settings,
            ready=ready,
            storage=storage,
            get_oauth_usage_snapshot=lambda: snapshot,
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
        "VALUES ('task-1', 'co1', 'proj1', 'pm', 'no-op')"
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
    token_store = TokenStore()
    issued = token_store.issue(agent_id=None, role=role, label=label)
    await _seed_agent(storage, issued.record.agent_id, role)
    return issued


async def _call_get_window_state(
    *,
    base_url: str,
    token: str,
    bucket: str = "subscription_5h",
    agent_id: str | None = None,
) -> object:
    url = f"{base_url}/mcp"
    args: dict[str, object] = {"bucket": bucket}
    if agent_id is not None:
        args["agent_id"] = agent_id
    headers = {"Authorization": f"Bearer {token}"}
    async with (
        streamablehttp_client(url, headers=headers) as (read, write, _),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        return await session.call_tool("get_window_state", args)


# ---------------------------------------------------------------------------
# PM and Engineer can invoke
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("allowed_role", ["pm", "engineer"])
async def test_allowed_role_can_invoke_get_window_state_over_mcp(
    settings: Settings, storage: SqliteStorage, allowed_role: str
) -> None:
    """PM and Engineer tokens get a structured response."""
    issued = await _issue_role_token(
        storage, allowed_role, f"{allowed_role}-window-state"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, _make_snapshot()):
        result = await _call_get_window_state(
            base_url=base_url, token=issued.token
        )

    assert getattr(result, "isError", False) is False, (
        f"role={allowed_role!r} got an MCP error: "
        f"{getattr(result, 'content', None)!r}"
    )


# ---------------------------------------------------------------------------
# Implementer / Tester rejected
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "rejected_role", ["implementer", "tester"]
)
async def test_implementer_tester_rejected_over_mcp(
    settings: Settings, storage: SqliteStorage, rejected_role: str
) -> None:
    """Implementer and Tester are not in the get_window_state
    allowlist per spec; the handler's _require_role rejects them and
    the wrapper surfaces the error structurally (not a 500).

    Implementer/Tester read their own usage via get_token_usage at
    M5-subscription-routing, not subscription-level state."""
    issued = await _issue_role_token(
        storage, rejected_role, f"{rejected_role}-window-state"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, _make_snapshot()):
        result = await _call_get_window_state(
            base_url=base_url, token=issued.token
        )

    assert getattr(result, "isError", False) is True


# ---------------------------------------------------------------------------
# Invalid bucket rejected at Pydantic boundary
# ---------------------------------------------------------------------------


async def test_invalid_bucket_rejected_as_structured_error(
    settings: Settings, storage: SqliteStorage
) -> None:
    """The bucket Literal restricts values to the 5-value enum; passing
    an unknown value must be rejected at the Pydantic validation
    boundary as a structured error, not a 500."""
    issued = await _issue_role_token(
        storage, "pm", "pm-invalid-bucket"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, _make_snapshot()):
        result = await _call_get_window_state(
            base_url=base_url,
            token=issued.token,
            bucket="not_a_real_bucket",
        )

    assert getattr(result, "isError", False) is True


# ---------------------------------------------------------------------------
# Wrapper fires _observe_caller
# ---------------------------------------------------------------------------


async def test_get_window_state_wrapper_fires_observe_caller(
    settings: Settings, storage: SqliteStorage
) -> None:
    """Per project_mcp_tool_wrapper_activity_recording memory, every
    MCP tool wrapper must call _observe_caller right after
    _resolve_agent_id. Pre-seed a dispatch row whose token_id matches
    the issued token, call get_window_state over MCP, observe that
    dispatches.last_activity_at + last_tool_called were updated.

    Mirror of test_record_api_call_wrapper_fires_activity_writer in
    test_record_api_call_mcp_wrapper.py — same shape, this wrapper.
    """
    issued = await _issue_role_token(
        storage, "engineer", "engineer-observe-caller"
    )
    await storage.execute(
        "INSERT INTO dispatches "
        "(id, task_id, role, status, working_directory, "
        "timeout_seconds, token_id) "
        "VALUES ('d-window-state', 'task-1', 'engineer-supervisor', "
        "'running', '/tmp/wt', 300, ?)",
        (issued.record.token_id,),
    )

    pre = await storage.fetch_one(
        "SELECT last_activity_at, last_tool_called FROM dispatches "
        "WHERE id = 'd-window-state'"
    )
    assert pre is not None
    assert pre["last_activity_at"] is None
    assert pre["last_tool_called"] is None

    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"
    async with _running_server(settings, storage, _make_snapshot()):
        result = await _call_get_window_state(
            base_url=base_url, token=issued.token
        )

    assert getattr(result, "isError", False) is False

    post = await storage.fetch_one(
        "SELECT last_activity_at, last_tool_called FROM dispatches "
        "WHERE id = 'd-window-state'"
    )
    assert post is not None
    assert post["last_activity_at"] is not None, (
        "get_window_state wrapper failed to update last_activity_at "
        "— _observe_caller is not wired into the wrapper."
    )
    assert post["last_tool_called"] == "get_window_state"


# ---------------------------------------------------------------------------
# Cold-start path through the wire
# ---------------------------------------------------------------------------


async def test_cold_start_returns_unknown_over_mcp(
    settings: Settings, storage: SqliteStorage
) -> None:
    """When the server's get_oauth_usage_snapshot returns None (cold
    start at production startup, before the first poll), the wrapper
    surfaces the unknown placeholder via MCP. Verifies the closure
    pattern threads None correctly through to the handler."""
    issued = await _issue_role_token(storage, "pm", "pm-cold-start")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, snapshot=None):
        result = await _call_get_window_state(
            base_url=base_url, token=issued.token
        )

    assert getattr(result, "isError", False) is False
    structured = getattr(result, "structuredContent", None)
    assert structured is not None
    assert structured["pace_recommendation"] == "unknown"
    assert structured["snapshot_age_seconds"] is None
