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

from meridian_orchestrator.agents.permissions import PermissionResolver
from meridian_orchestrator.agents.registry import AgentRegistry
from meridian_orchestrator.auth import TokenStore, _parse_token
from meridian_orchestrator.bridge.activity import record_mcp_call
from meridian_orchestrator.config import Settings
from meridian_orchestrator.errors import AuthError, NotFoundError
from meridian_orchestrator.errors import PermissionError as OrchestratorPermissionError
from meridian_orchestrator.oauth import MeridianOAuthProvider, make_approval_routes
from meridian_orchestrator.storage.interface import Storage
from meridian_orchestrator.tools.agents import (
    GetAgentConfigInput,
    GetAgentConfigOutput,
    GetOrgChartInput,
    GetOrgChartOutput,
    ListAgentsInput,
    ListAgentsOutput,
    get_agent_config,
    get_org_chart,
    list_agents,
)

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


async def _record_caller_activity(
    storage: Storage | None, tool_name: str
) -> None:
    """Resolve the caller's token from the request context and update
    ``dispatches.last_activity_at`` + ``dispatches.last_tool_called``
    via :func:`bridge.activity.record_mcp_call`.

    Skipped silently when ``storage`` is None (test fixtures that don't
    need activity tracking) or when the access context lacks a parseable
    token. Two further skip cases are handled inside ``record_mcp_call``
    itself (no matching dispatch row; row in terminal state) — both
    produce zero updated rows. See ``11-engineer-bridge.md`` § `last_activity_at`
    and `last_tool_called` writer for the full contract.
    """
    if storage is None:
        return
    access = get_access_token()
    if access is None:
        return
    parsed = _parse_token(access.token)
    if parsed is None:
        return
    token_id, _ = parsed
    await record_mcp_call(storage, token_id, tool_name)


def build_server(
    settings: Settings,
    store: TokenStore | None = None,
    registry: AgentRegistry | None = None,
    storage: Storage | None = None,
) -> FastMCP:
    """Construct the FastMCP server with auth + the current tool set.

    ``registry`` is the agent registry the M3+ tools (list_agents,
    get_agent_config, get_org_chart) close over. If ``None``, those tools
    return an empty universe — useful for M2-only smoke tests but not for
    production. The ``serve()`` entry point passes a registry built and
    started in the daemon lifespan.
    """
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
        await _record_caller_activity(storage, "ping")
        return PingOutput(
            pong=echo,
            agent_id=agent_id,
            server_time=datetime.now(tz=UTC).isoformat(timespec="seconds"),
        )

    if registry is not None:
        _register_agent_tools(mcp, registry, storage)

    return mcp


def _resolve_caller_role(registry: AgentRegistry) -> str:
    """Look up the caller's agent_id in the registry and return its role.

    Raises :class:`AuthError` if the request context has no resolved agent
    (no Bearer token); raises :class:`OrchestratorPermissionError` when the
    agent_id resolves to no registered role (token issued but no YAML config
    landed yet — orphan token state).
    """
    agent_id = _resolve_agent_id()
    ra = registry.get_by_agent_id(agent_id)
    if ra is None:
        raise OrchestratorPermissionError(
            f"caller agent_id={agent_id!r} not registered"
        )
    return ra.config.role


def _register_agent_tools(
    mcp: FastMCP, registry: AgentRegistry, storage: Storage | None
) -> None:
    """Register list_agents / get_agent_config / get_org_chart on ``mcp``.

    Each tool checks the caller's per-tool allowlist via
    :class:`PermissionResolver` against the current registry snapshot. The
    snapshot is read fresh per call so hot-reloads take effect immediately.

    ``storage`` is threaded through to the activity-writer helper. ``None``
    skips activity tracking — used by test fixtures that don't need it.
    """

    @mcp.tool(
        name="list_agents",
        description="List registered agents. Optional filters by role and status.",
    )
    async def _list_agents(
        role: str | None = None, status: str | None = None
    ) -> ListAgentsOutput:
        caller_role = _resolve_caller_role(registry)
        await _record_caller_activity(storage, "list_agents")
        resolver = PermissionResolver(registry.configs_by_role())
        resolver.require_tool(caller_role, "list_agents")  # type: ignore[arg-type]
        return list_agents(registry, ListAgentsInput(role=role, status=status))

    @mcp.tool(
        name="get_agent_config",
        description="Return the public configuration of an agent (system_prompt excluded).",
    )
    async def _get_agent_config(agent_id: str) -> GetAgentConfigOutput:
        caller_role = _resolve_caller_role(registry)
        await _record_caller_activity(storage, "get_agent_config")
        resolver = PermissionResolver(registry.configs_by_role())
        resolver.require_tool(caller_role, "get_agent_config")  # type: ignore[arg-type]
        result = get_agent_config(registry, GetAgentConfigInput(agent_id=agent_id))
        if result is None:
            raise NotFoundError(f"agent_id={agent_id!r} not found")
        return result

    @mcp.tool(
        name="get_org_chart",
        description="Return the management hierarchy as a nested tree rooted at PM.",
    )
    async def _get_org_chart() -> GetOrgChartOutput:
        caller_role = _resolve_caller_role(registry)
        await _record_caller_activity(storage, "get_org_chart")
        resolver = PermissionResolver(registry.configs_by_role())
        resolver.require_tool(caller_role, "get_org_chart")  # type: ignore[arg-type]
        return get_org_chart(registry, GetOrgChartInput())


async def serve(
    settings: Settings,
    ready: asyncio.Event | None = None,
    registry: AgentRegistry | None = None,
    storage: Storage | None = None,
) -> None:
    """Run the MCP HTTP server until cancelled.

    Cancelling the awaiting task triggers a graceful uvicorn shutdown. Pass
    ``ready`` to be signalled once the listener is accepting connections (used
    by the integration test). Pass ``registry`` for M3+ tools; ``None`` keeps
    the M2-only ``ping``-tool surface for tests that don't need agent state.
    Pass ``storage`` to enable the ``last_activity_at`` writer middleware
    (M4c-supervisor-base); ``None`` is fine for tests that don't exercise
    dispatch-row state.
    """
    mcp = build_server(settings, registry=registry, storage=storage)
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
