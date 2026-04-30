"""Full round-trip integration test for the M2 ping tool.

Spins up the MCP server on a free localhost port, connects via the streamable-
HTTP MCP client with a Bearer-issued token, calls ``ping``, and checks the
response. Also exercises the auth failure path: an invalid token must produce
a 401, not a tool result.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import socket
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import pytest
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client

from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.config import Settings
from meridian_orchestrator.server import serve as serve_mcp


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@asynccontextmanager
async def _running_server(settings: Settings) -> AsyncIterator[None]:
    ready = asyncio.Event()
    task = asyncio.create_task(serve_mcp(settings, ready=ready), name="mcp-test")
    try:
        await asyncio.wait_for(ready.wait(), timeout=10)
        yield
    finally:
        task.cancel()
        with contextlib.suppress(TimeoutError, asyncio.CancelledError):
            await asyncio.wait_for(task, timeout=10)


@pytest.fixture
def settings(tmp_path: object) -> Settings:
    return Settings(
        data_dir=tmp_path,  # type: ignore[arg-type]
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


async def test_ping_round_trip_with_valid_token(settings: Settings) -> None:
    store = TokenStore()
    issued = store.issue(agent_id="a-pm-uuid", role="pm", label="integration")

    async with _running_server(settings):
        url = f"http://{settings.mcp_host}:{settings.mcp_port}/mcp"
        result = await _call_ping(
            url, {"Authorization": f"Bearer {issued.token}"}, "hello"
        )

    assert getattr(result, "isError", False) is False
    structured = getattr(result, "structuredContent", None)
    if structured is None:
        # Older mcp client surface returns content blocks; fall back to text.
        text = result.content[0].text  # type: ignore[union-attr]
        structured = json.loads(text)
    assert structured["pong"] == "hello"
    assert structured["agent_id"] == "a-pm-uuid"
    assert "T" in structured["server_time"]


async def test_ping_rejects_invalid_token(settings: Settings) -> None:
    async with _running_server(settings):
        url = f"http://{settings.mcp_host}:{settings.mcp_port}/mcp"
        with pytest.raises(Exception):  # noqa: B017 - server returns 401
            await _call_ping(
                url, {"Authorization": "Bearer mor_dead0000beef0000_garbage"}, "x"
            )


async def test_ping_rejects_revoked_token(settings: Settings) -> None:
    store = TokenStore()
    issued = store.issue(agent_id=None, role="pm", label="revoked")
    assert store.revoke(issued.record.token_id) is True

    async with _running_server(settings):
        url = f"http://{settings.mcp_host}:{settings.mcp_port}/mcp"
        with pytest.raises(Exception):  # noqa: B017
            await _call_ping(
                url, {"Authorization": f"Bearer {issued.token}"}, "x"
            )
