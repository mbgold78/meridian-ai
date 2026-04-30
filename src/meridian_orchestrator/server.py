"""MCP server construction and lifecycle.

The Orchestrator exposes a single MCP endpoint over Streamable-HTTP at
``http://<mcp_host>:<mcp_port>/mcp``. Auth is bearer-token (per-agent token
issued via ``scripts/issue-token.py``); the verifier resolves to ``agent_id``
via ``auth.TokenStore`` and the running tool reads the resolved identity from
``mcp.server.auth.middleware.auth_context.get_access_token``.

M2 ships exactly one tool — ``ping`` — to validate the auth chain end-to-end.
M3 onward registers task/budget/knowledge/etc. tools per ``06-mcp-tools-phase-one.md``.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime

import structlog
import uvicorn
from mcp.server.auth.middleware.auth_context import get_access_token
from mcp.server.auth.provider import AccessToken, TokenVerifier
from mcp.server.auth.settings import AuthSettings
from mcp.server.fastmcp import FastMCP
from pydantic import AnyHttpUrl, BaseModel

from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.config import Settings
from meridian_orchestrator.errors import AuthError

logger = structlog.get_logger(__name__)

_LOOPBACK_HOSTS = frozenset({"127.0.0.1", "localhost", "::1", "[::1]"})


class _StoreTokenVerifier(TokenVerifier):
    """TokenVerifier backed by ``auth.TokenStore``.

    Resolves a bearer token to an ``AccessToken`` whose ``client_id`` is the
    agent_id and whose ``scopes`` carry the agent's role. Returns ``None`` for
    any failure — the FastMCP middleware then emits a 401 without exposing
    *why* the token was rejected.
    """

    def __init__(self, store: TokenStore) -> None:
        self._store = store

    async def verify_token(self, token: str) -> AccessToken | None:
        record = self._store.lookup(token)
        if record is None:
            return None
        return AccessToken(
            token=token,
            client_id=record.agent_id,
            scopes=[f"role:{record.role}"],
        )


class PingOutput(BaseModel):
    pong: str
    agent_id: str
    server_time: str


def _resolve_agent_id() -> str:
    """Read the verified agent_id off the current request context."""
    access = get_access_token()
    if access is None:
        raise AuthError("no authenticated context for tool call")
    return access.client_id


def build_server(settings: Settings, store: TokenStore | None = None) -> FastMCP:
    """Construct the FastMCP server with auth + the M2 tool set."""
    if not settings.mcp_allow_non_loopback and settings.mcp_host not in _LOOPBACK_HOSTS:
        raise ValueError(
            f"refusing to bind MCP server to non-loopback host {settings.mcp_host!r}; "
            "set MERIDIAN_ORCH_MCP_ALLOW_NON_LOOPBACK=true to override"
        )

    token_store = store if store is not None else TokenStore()
    base_url = AnyHttpUrl(f"http://{settings.mcp_host}:{settings.mcp_port}")

    mcp = FastMCP(
        name="meridian-orchestrator",
        instructions="Meridian AI Orchestrator — MCP server for the AI-staffed company.",
        token_verifier=_StoreTokenVerifier(token_store),
        auth=AuthSettings(issuer_url=base_url, resource_server_url=base_url),
        host=settings.mcp_host,
        port=settings.mcp_port,
        log_level=settings.log_level.upper(),  # type: ignore[arg-type]
    )

    @mcp.tool(
        name="ping",
        description="Round-trip health check. Echoes the input and returns the "
        "caller's resolved agent_id and the server's current UTC time.",
    )
    async def ping(echo: str) -> PingOutput:
        agent_id = _resolve_agent_id()
        return PingOutput(
            pong=echo,
            agent_id=agent_id,
            server_time=datetime.now(tz=UTC).isoformat(timespec="seconds"),
        )

    return mcp


async def serve(settings: Settings, ready: asyncio.Event | None = None) -> None:
    """Run the MCP HTTP server until cancelled.

    Cancelling the awaiting task triggers a graceful uvicorn shutdown. Pass
    ``ready`` to be signalled once the listener is accepting connections (used
    by the integration test).
    """
    mcp = build_server(settings)
    app = mcp.streamable_http_app()
    config = uvicorn.Config(
        app,
        host=settings.mcp_host,
        port=settings.mcp_port,
        log_level=settings.log_level.lower(),
        access_log=False,
        lifespan="on",
    )
    server = uvicorn.Server(config)
    server.config.load()
    server.lifespan = config.lifespan_class(config)

    serve_task = asyncio.create_task(server.serve(), name="mcp-serve")
    try:
        if ready is not None:
            while not server.started:
                if serve_task.done():
                    serve_task.result()
                    return
                await asyncio.sleep(0.05)
            ready.set()
        await serve_task
    except asyncio.CancelledError:
        logger.info("mcp.shutdown_requested")
        server.should_exit = True
        try:
            await asyncio.wait_for(serve_task, timeout=10)
        except TimeoutError:
            logger.warning("mcp.shutdown_timeout_force_close")
            server.force_exit = True
            await serve_task
        raise
