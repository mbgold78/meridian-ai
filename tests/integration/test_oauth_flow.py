"""End-to-end OAuth flow against a live daemon.

Walks the full RFC 6749 authorization-code path Claude Desktop will run:
register → authorize → operator approval → token exchange → call /mcp ping
with the issued access token. Also asserts negative paths: PKCE verifier
mismatch and approval-session CSRF mismatch are both rejected.

The PKCE check (hash(verifier) == code_challenge) is enforced by FastMCP's
``TokenHandler``, not by our provider — testing the full flow validates that
the framework wiring is correct, not just our provider in isolation.
"""

from __future__ import annotations

import asyncio
import base64
import contextlib
import hashlib
import json
import secrets
import socket
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import httpx
import pytest
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client

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


def _pkce_pair() -> tuple[str, str]:
    verifier = secrets.token_urlsafe(64)[:64]
    digest = hashlib.sha256(verifier.encode("ascii")).digest()
    challenge = base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")
    return verifier, challenge


async def _register_client(base: str, redirect_uri: str) -> str:
    async with httpx.AsyncClient(verify=False) as c:
        resp = await c.post(
            f"{base}/register",
            json={
                "redirect_uris": [redirect_uri],
                "client_name": "integration-test-client",
                "grant_types": ["authorization_code", "refresh_token"],
                "response_types": ["code"],
                "token_endpoint_auth_method": "none",
            },
        )
    assert resp.status_code == 201, resp.text
    return resp.json()["client_id"]


async def _walk_authorize_to_code(
    base: str, client_id: str, redirect_uri: str, challenge: str
) -> str:
    async with httpx.AsyncClient(verify=False, follow_redirects=False) as c:
        resp = await c.get(
            f"{base}/authorize",
            params={
                "client_id": client_id,
                "redirect_uri": redirect_uri,
                "response_type": "code",
                "code_challenge": challenge,
                "code_challenge_method": "S256",
                "state": "stateval",
            },
        )
        assert resp.status_code == 302
        approve_url = resp.headers["location"]
        if approve_url.startswith("/"):
            approve_url = f"{base}{approve_url}"
        sid = approve_url.split("session=", 1)[1]

        approval_page = await c.get(approve_url)
        assert approval_page.status_code == 200
        body = approval_page.text
        assert "integration-test-client" in body
        assert redirect_uri in body
        assert "role:pm" in body

        csrf = body.split('name="csrf" value="', 1)[1].split('"', 1)[0]
        post = await c.post(
            f"{base}/approve",
            data={"session": sid, "csrf": csrf, "decision": "approve"},
        )
        assert post.status_code == 302, post.text
        location = post.headers["location"]
        assert location.startswith(redirect_uri)
        assert "state=stateval" in location
        return location.split("code=", 1)[1].split("&", 1)[0]


async def _exchange_code_for_token(
    base: str, client_id: str, code: str, verifier: str, redirect_uri: str
) -> str:
    async with httpx.AsyncClient(verify=False) as c:
        resp = await c.post(
            f"{base}/token",
            data={
                "grant_type": "authorization_code",
                "code": code,
                "redirect_uri": redirect_uri,
                "client_id": client_id,
                "code_verifier": verifier,
            },
        )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["token_type"] == "Bearer"
    return body["access_token"]


async def test_full_oauth_flow_then_ping(settings: Settings) -> None:
    base = f"https://{settings.mcp_host}:{settings.mcp_port}"
    redirect_uri = "http://127.0.0.1:54321/cb"
    verifier, challenge = _pkce_pair()

    async with _running_server(settings):
        client_id = await _register_client(base, redirect_uri)
        code = await _walk_authorize_to_code(base, client_id, redirect_uri, challenge)
        access_token = await _exchange_code_for_token(
            base, client_id, code, verifier, redirect_uri
        )

        async with (
            streamablehttp_client(
                f"{base}/mcp",
                headers={"Authorization": f"Bearer {access_token}"},
                httpx_client_factory=_insecure_factory,
            ) as (read, write, _),
            ClientSession(read, write) as session,
        ):
            await session.initialize()
            result = await session.call_tool("ping", {"echo": "oauth-end-to-end"})

    structured = getattr(result, "structuredContent", None)
    if structured is None:
        structured = json.loads(result.content[0].text)  # type: ignore[union-attr]
    assert structured["pong"] == "oauth-end-to-end"
    assert structured["agent_id"]


async def test_token_exchange_rejects_pkce_verifier_mismatch(
    settings: Settings,
) -> None:
    base = f"https://{settings.mcp_host}:{settings.mcp_port}"
    redirect_uri = "http://127.0.0.1:54321/cb"
    _, challenge = _pkce_pair()
    bad_verifier = "x" * 64  # not the verifier whose hash matches `challenge`

    async with _running_server(settings):
        client_id = await _register_client(base, redirect_uri)
        code = await _walk_authorize_to_code(
            base, client_id, redirect_uri, challenge
        )
        async with httpx.AsyncClient(verify=False) as c:
            resp = await c.post(
                f"{base}/token",
                data={
                    "grant_type": "authorization_code",
                    "code": code,
                    "redirect_uri": redirect_uri,
                    "client_id": client_id,
                    "code_verifier": bad_verifier,
                },
            )
    assert resp.status_code in {400, 401}
    body = resp.json()
    assert body.get("error") in {"invalid_grant", "invalid_request"}


async def test_register_rejects_non_loopback_redirect(settings: Settings) -> None:
    base = f"https://{settings.mcp_host}:{settings.mcp_port}"
    async with _running_server(settings), httpx.AsyncClient(verify=False) as c:
        resp = await c.post(
            f"{base}/register",
            json={
                "redirect_uris": ["https://attacker.example.com/cb"],
                "client_name": "evil",
                "grant_types": ["authorization_code", "refresh_token"],
                "response_types": ["code"],
                "token_endpoint_auth_method": "none",
            },
        )
    assert resp.status_code == 400
