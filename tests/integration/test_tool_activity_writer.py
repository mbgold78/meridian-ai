"""End-to-end test that a tool wrapper records caller activity.

The unit-level test ``test_record_mcp_call_*`` in
``test_supervisor_base.py`` proves the helper SQL works in isolation.
This file proves the **wiring** — that the actual MCP tool wrapper in
``server.py`` calls ``_record_caller_activity`` on every authenticated
call, threading through the real auth context, the real token parser,
and the real ``record_mcp_call`` helper.

Pattern: spin up the MCP server with a real storage handle, pre-seed a
dispatch row whose ``token_id`` matches a token we then use as the
client's bearer credential, call ``ping`` via the real Streamable-HTTP
client, assert the dispatch row's activity columns are now set.

This test catches the regression where a future tool wrapper is added
without the ``_record_caller_activity`` call (memory:
``project_mcp_tool_wrapper_activity_recording``). M4c-tooling should
extend this file with a parallel test per new wrapper, or a parameterised
test covering all wrappers.
"""

from __future__ import annotations

import asyncio
import contextlib
import socket
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path

import pytest
import pytest_asyncio
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client

from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.config import Settings
from meridian_orchestrator.server import serve as serve_mcp
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage


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
        "INSERT INTO companies (id, name) VALUES ('co1', 'Test Co')"
    )
    await s.execute(
        "INSERT INTO projects (id, company_id, name) VALUES "
        "('proj1', 'co1', 'Test Project')"
    )
    await s.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, description) "
        "VALUES ('task-1', 'co1', 'proj1', 'implementer', 'test')"
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


async def _call_ping(url: str, headers: dict[str, str], echo: str) -> object:
    async with (
        streamablehttp_client(url, headers=headers) as (read, write, _),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        return await session.call_tool("ping", {"echo": echo})


async def test_ping_wrapper_records_activity_end_to_end(
    storage: SqliteStorage, settings: Settings
) -> None:
    """Issue a token, seed a dispatch row whose token_id matches, call ping
    via the real MCP client, assert the dispatch row's last_activity_at and
    last_tool_called were updated by the helper invocation in the wrapper."""
    token_store = TokenStore()
    issued = token_store.issue(
        agent_id="agent-test", role="implementer", label="e2e-activity"
    )

    # Pre-seed a dispatch row whose token_id matches the issued token.
    # Status='running' so the writer's terminal-state guard does NOT skip.
    await storage.execute(
        "INSERT INTO dispatches "
        "(id, task_id, role, status, working_directory, "
        "timeout_seconds, token_id) "
        "VALUES ('d-e2e', 'task-1', 'implementer', 'running', "
        "'/tmp/test', 300, ?)",
        (issued.record.token_id,),
    )

    # Pre-state: both activity columns NULL.
    pre = await storage.fetch_one(
        "SELECT last_activity_at, last_tool_called "
        "FROM dispatches WHERE id = ?",
        ("d-e2e",),
    )
    assert pre is not None
    assert pre["last_activity_at"] is None
    assert pre["last_tool_called"] is None

    async with _running_server(settings, storage):
        url = f"http://{settings.mcp_host}:{settings.mcp_port}/mcp"
        result = await _call_ping(
            url, {"Authorization": f"Bearer {issued.token}"}, "hello"
        )

    # Tool call succeeded.
    assert getattr(result, "isError", False) is False

    # Post-state: the wrapper's _record_caller_activity call landed.
    post = await storage.fetch_one(
        "SELECT last_activity_at, last_tool_called "
        "FROM dispatches WHERE id = ?",
        ("d-e2e",),
    )
    assert post is not None
    assert post["last_activity_at"] is not None, (
        "ping wrapper failed to update last_activity_at — "
        "_record_caller_activity is not wired correctly"
    )
    assert post["last_tool_called"] == "ping", (
        f"expected last_tool_called='ping', got {post['last_tool_called']!r} — "
        "the tool_name argument is wrong or the wrapper isn't calling the helper"
    )
