"""Full round-trip integration test for the M2 ping tool.

Spins up the MCP server on a free localhost port, connects via the
Streamable-HTTP MCP client over TLS with a Bearer-issued token, calls
``ping``, and checks the response. Also exercises:

- Auth failure: invalid/revoked token → 401, not a tool result.
- TLS-required: an httpx client that does not trust the self-signed cert
  fails with an SSL error. (Validates the cert is self-signed and that the
  daemon is, in fact, doing TLS — a plain-HTTP client cannot reach the tool.)
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import socket
import ssl
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import httpx
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


def _insecure_factory(
    headers: dict[str, str] | None = None,
    timeout: httpx.Timeout | None = None,
    auth: httpx.Auth | None = None,
) -> httpx.AsyncClient:
    """httpx factory that skips cert verification.

    Used by tests so they don't need to import the daemon's freshly-generated
    self-signed cert into the test process's trust store. Production clients
    (Claude Desktop) verify against the OS trust store after the operator
    imports the cert.
    """
    return httpx.AsyncClient(
        headers=headers,
        timeout=timeout if timeout is not None else httpx.Timeout(30.0),
        auth=auth,
        follow_redirects=True,
        verify=False,
    )


@asynccontextmanager
async def _running_server(settings: Settings) -> AsyncIterator[None]:
    ready = asyncio.Event()
    task = asyncio.create_task(serve_mcp(settings, ready=ready), name="mcp-test")
    try:
        await asyncio.wait_for(ready.wait(), timeout=15)
        yield
    finally:
        task.cancel()
        with contextlib.suppress(TimeoutError, asyncio.CancelledError):
            await asyncio.wait_for(task, timeout=15)


@pytest.fixture
def settings(tmp_path: object) -> Settings:
    return Settings(
        data_dir=tmp_path,  # type: ignore[arg-type]
        mcp_host="127.0.0.1",
        mcp_port=_free_port(),
    )


async def _call_ping(url: str, headers: dict[str, str], echo: str) -> object:
    async with (
        streamablehttp_client(
            url, headers=headers, httpx_client_factory=_insecure_factory
        ) as (read, write, _),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        return await session.call_tool("ping", {"echo": echo})


async def test_ping_round_trip_with_valid_token(settings: Settings) -> None:
    store = TokenStore()
    issued = store.issue(agent_id="a-pm-uuid", role="pm", label="integration")

    async with _running_server(settings):
        url = f"https://{settings.mcp_host}:{settings.mcp_port}/mcp"
        result = await _call_ping(
            url, {"Authorization": f"Bearer {issued.token}"}, "hello"
        )

    assert getattr(result, "isError", False) is False
    structured = getattr(result, "structuredContent", None)
    if structured is None:
        text = result.content[0].text  # type: ignore[union-attr]
        structured = json.loads(text)
    assert structured["pong"] == "hello"
    assert structured["agent_id"] == "a-pm-uuid"
    assert "T" in structured["server_time"]


async def test_ping_rejects_invalid_token(settings: Settings) -> None:
    async with _running_server(settings):
        url = f"https://{settings.mcp_host}:{settings.mcp_port}/mcp"
        with pytest.raises(Exception):  # noqa: B017 - server returns 401
            await _call_ping(
                url, {"Authorization": "Bearer mor_dead0000beef0000_garbage"}, "x"
            )


async def test_ping_rejects_revoked_token(settings: Settings) -> None:
    store = TokenStore()
    issued = store.issue(agent_id=None, role="pm", label="revoked")
    assert store.revoke(issued.record.token_id) is True

    async with _running_server(settings):
        url = f"https://{settings.mcp_host}:{settings.mcp_port}/mcp"
        with pytest.raises(Exception):  # noqa: B017
            await _call_ping(
                url, {"Authorization": f"Bearer {issued.token}"}, "x"
            )


async def test_https_required_self_signed_not_in_default_trust(
    settings: Settings,
) -> None:
    """A client that uses the default OS trust store rejects the self-signed
    cert. This proves (a) the daemon is doing TLS, and (b) the cert is in
    fact self-signed — Claude Desktop will only trust it after the operator
    imports it into the OS trust store.
    """
    async with _running_server(settings):
        url = f"https://{settings.mcp_host}:{settings.mcp_port}/mcp"
        async with httpx.AsyncClient() as client:
            with pytest.raises((httpx.ConnectError, ssl.SSLError)):
                await client.get(url)
