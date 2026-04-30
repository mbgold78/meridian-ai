"""MCP server construction and lifecycle.

The Orchestrator exposes a single MCP endpoint over Streamable-HTTP at
``http://<mcp_host>:<mcp_port>/mcp``. The daemon binds loopback by default;
the threat model for a single-user local-only Phase 1 daemon does not require
TLS over a loopback socket. Phase 2 will add TLS again when a non-loopback
deployment is on the table.

Two coexisting auth modes share the same token table and lookup path:

- **OAuth Authorization Server** (Mike's PM agent via Claude Code) — the
  daemon implements RFC 7591 dynamic client registration and the RFC 6749
  authorization-code flow with PKCE. See :mod:`meridian_orchestrator.oauth`.
- **Static bearer tokens** (M3+ daemon-launched Engineer/Implementer/Tester
  subagents) — issued via ``scripts/issue-token.py``, persisted in keyring.

The :class:`auth.TokenStore` is the single source of truth for both. The
provider's ``load_access_token`` accepts a Bearer token from either source.

M2 ships exactly one tool — ``ping`` — to validate the auth chain end-to-end.
M3 onward registers task/budget/knowledge/etc. tools per ``06-mcp-tools-phase-one.md``.
"""

from __future__ import annotations

import asyncio
from datetime import UTC, datetime

import structlog
import uvicorn
from mcp.server.auth.middleware.auth_context import get_access_token
from mcp.server.auth.settings import (
    AuthSettings,
    ClientRegistrationOptions,
    RevocationOptions,
)
from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from pydantic import AnyHttpUrl, BaseModel

from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.config import Settings
from meridian_orchestrator.errors import AuthError
from meridian_orchestrator.oauth import MeridianOAuthProvider, make_approval_routes

logger = structlog.get_logger(__name__)

_LOOPBACK_HOSTS = frozenset({"127.0.0.1", "localhost", "::1", "[::1]"})


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
    transport_security = TransportSecuritySettings(
        enable_dns_rebinding_protection=True,
        allowed_hosts=["127.0.0.1:*", "localhost:*", "[::1]:*"],
        allowed_origins=[
            "http://127.0.0.1:*",
            "http://localhost:*",
            "http://[::1]:*",
        ],
    )

    oauth_provider = MeridianOAuthProvider(token_store)

    mcp = FastMCP(
        name="meridian-orchestrator",
        instructions="Meridian AI Orchestrator — MCP server for the AI-staffed company.",
        auth_server_provider=oauth_provider,
        auth=AuthSettings(
            issuer_url=base_url,
            resource_server_url=base_url,
            client_registration_options=ClientRegistrationOptions(
                enabled=True,
                valid_scopes=["role:pm"],
                default_scopes=["role:pm"],
            ),
            revocation_options=RevocationOptions(enabled=True),
        ),
        transport_security=transport_security,
        host=settings.mcp_host,
        port=settings.mcp_port,
        log_level=settings.log_level.upper(),  # type: ignore[arg-type]
    )

    for path, methods, handler in make_approval_routes(oauth_provider):
        mcp.custom_route(path, methods=methods)(handler)

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
