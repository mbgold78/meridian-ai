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
from collections.abc import Callable
from datetime import UTC, datetime
from typing import Any

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
from meridian_orchestrator.bridge.activity import (
    record_mcp_call,
    record_oauth_pm_auto_flip,
)
from meridian_orchestrator.bridge.dispatcher import BridgeDispatcher
from meridian_orchestrator.bridge.supervisor_base import LongRunningSupervisor
from meridian_orchestrator.config import Settings
from meridian_orchestrator.errors import AuthError, ConflictError, NotFoundError
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
from meridian_orchestrator.tools.budget import (
    GetWindowStateInput,
    GetWindowStateOutput,
    RecordApiCallInput,
    RecordApiCallOutput,
    SnapshotProvider,
    get_window_state,
    record_api_call,
)
from meridian_orchestrator.tools.dispatch import (
    CompleteDispatchInput,
    CompleteDispatchOutput,
    DispatchImplementerInput,
    DispatchImplementerOutput,
    DispatchTesterInput,
    DispatchTesterOutput,
    EngineerDecision,
    GetDispatchEventsInput,
    GetDispatchEventsOutput,
    GetDispatchResultInput,
    GetDispatchResultOutput,
    GetDispatchStatusInput,
    GetDispatchStatusOutput,
    GetRecentDispatchesInput,
    GetRecentDispatchesOutput,
    complete_dispatch,
    dispatch_implementer,
    dispatch_tester,
    get_dispatch_events,
    get_dispatch_result,
    get_dispatch_status,
    get_recent_dispatches,
)
from meridian_orchestrator.tools.pm_mode import (
    PmMode,
    SetPmModeInput,
    SetPmModeOutput,
    set_pm_mode,
)
from meridian_orchestrator.tools.supervisor import (
    SignalCompactionInput,
    SignalCompactionOutput,
    signal_compaction,
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


def _resolve_caller_token_id() -> str:
    """Parse the bearer token from the request context and return its
    ``token_id`` (the short hex prefix).

    Mirrors :func:`_resolve_agent_id` but returns the token_id rather
    than the agent_id. Used by tool wrappers that need to look up
    dispatch context — currently only ``record_api_call``, whose
    writer (``tools/budget.py``) resolves the caller's active dispatch
    via ``WHERE token_id = ? AND status NOT IN (terminal)`` to
    atomically increment ``dispatches.actual_tokens``.

    Raises :class:`AuthError` when no access context exists or the
    bearer token isn't parseable. Both cases indicate a wrapper layer
    bug rather than a caller error.
    """
    access = get_access_token()
    if access is None:
        raise AuthError("no authenticated context for tool call")
    parsed = _parse_token(access.token)
    if parsed is None:
        raise AuthError("could not parse bearer token")
    token_id, _ = parsed
    return token_id


async def _observe_caller(
    storage: Storage | None,
    token_store: TokenStore | None,
    tool_name: str,
) -> None:
    """Single observation hook called by every MCP tool wrapper right
    after :func:`_resolve_agent_id` resolves the caller's identity.

    Fans out to two writers in a single transaction (atomicity per the
    M5-pm-mode-and-window-state commit 5 disposition — both writes
    land or neither):

    1. :func:`bridge.activity.record_mcp_call` — updates
       ``dispatches.last_activity_at`` + ``dispatches.last_tool_called``
       for dispatch-bound tokens. No-ops silently when the caller's
       token isn't bound to a dispatch row (operator OAuth tokens;
       static admin tokens) or when the bound dispatch is terminal.
    2. :func:`bridge.activity.record_oauth_pm_auto_flip` — writes a
       ``pm_mode_log`` row with ``mode='interactive',
       triggered_by='mike_message'`` when the caller is an oauth-pm
       token and the current mode warrants the transition. No-ops on
       non-oauth-pm callers, on already-interactive state, and on
       paused states.

    Per ``project_mcp_tool_wrapper_activity_recording`` memory: every
    new MCP tool wrapper in ``server.py`` must call this helper right
    after ``_resolve_agent_id``; no exceptions. The single-call-site
    invariant means future observation concerns extend this helper's
    fan-out rather than adding new wrapper-side calls — keeps the
    discipline at one site, not N.

    Skipped silently when ``storage`` or ``token_store`` is None
    (test fixtures that don't exercise the writers) or when the
    access context lacks a parseable token.

    Atomicity rationale: per the M5-pm-mode-and-window-state commit 5
    prep, both writes are wrapped in a single transaction. A failure
    in the auto-flip rolls back the activity update too — partial
    success would leave operator-visible state inconsistent (activity
    timestamp updated but auto-flip not recorded, or vice versa).
    Worth noting: ``bridge.activity.record_oauth_pm_auto_flip`` opens
    its own ``storage.transaction()`` for direct-call paths;
    SqliteStorage's transaction is reentrant, so the inner is a no-op
    when called from this outer transaction.
    """
    if storage is None or token_store is None:
        return
    access = get_access_token()
    if access is None:
        return
    parsed = _parse_token(access.token)
    if parsed is None:
        return
    token_id, _ = parsed

    record = token_store.lookup_by_token_id(token_id)

    async with storage.transaction():
        await record_mcp_call(storage, token_id, tool_name)
        if record is not None:
            await record_oauth_pm_auto_flip(storage, record)


def build_server(
    settings: Settings,
    store: TokenStore | None = None,
    registry: AgentRegistry | None = None,
    storage: Storage | None = None,
    get_engineer_supervisor: Callable[[], LongRunningSupervisor | None] | None = None,
    get_dispatcher: Callable[[], BridgeDispatcher | None] | None = None,
    get_oauth_usage_snapshot: SnapshotProvider | None = None,
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
        await _observe_caller(storage, token_store,"ping")
        return PingOutput(
            pong=echo,
            agent_id=agent_id,
            server_time=datetime.now(tz=UTC).isoformat(timespec="seconds"),
        )

    if registry is not None:
        _register_agent_tools(mcp, registry, storage, token_store)

    if storage is not None:
        _register_dispatch_query_tools(mcp, storage, token_store)
        _register_dispatch_completion_tools(mcp, storage, token_store)
        _register_budget_tools(mcp, storage, token_store)
        _register_pm_mode_tools(mcp, storage, token_store)

    if storage is not None and get_oauth_usage_snapshot is not None:
        _register_window_state_tools(
            mcp, storage, token_store, get_oauth_usage_snapshot
        )

    if storage is not None and get_dispatcher is not None and registry is not None:
        _register_dispatch_initiation_tools(
            mcp, storage, registry, token_store, get_dispatcher
        )

    if storage is not None and get_engineer_supervisor is not None:
        _register_supervisor_tools(
            mcp, storage, token_store, get_engineer_supervisor
        )

    return mcp


def _register_dispatch_initiation_tools(
    mcp: FastMCP,
    storage: Storage,
    registry: AgentRegistry,
    token_store: TokenStore,
    get_dispatcher: Callable[[], BridgeDispatcher | None],
) -> None:
    """Register dispatch_implementer and dispatch_tester (engineer-only).

    The ``get_dispatcher`` callable is evaluated lazily at each tool
    invocation. The dispatcher is constructed in ``main.py`` AFTER the
    MCP server's ready signal fires (the canonical clone provisioning
    is synchronous in the lifespan, so by the time the server accepts
    connections the dispatcher is up). The lazy callable is symmetric
    with ``get_engineer_supervisor`` and protects against early-call
    races during startup ordering.

    Permission enforcement is single-gate at handler ``_require_role``;
    wrapper layer does not double-check via PermissionResolver. Pattern
    matches query tools and supervisor tools from M4c-tooling.
    Static-token paths resolve role from OAuth scopes via
    ``_resolve_caller_role_via_token``; do not require registry.
    """

    @mcp.tool(
        name="dispatch_implementer",
        description=(
            "Engineer-only. Dispatches an Implementer sub-agent against the "
            "given spec. Runs the dispatch lifecycle through to a terminal "
            "state and returns the structured outcome. spec_path is read by "
            "the daemon at dispatch time and passed to the subprocess as "
            "its prompt."
        ),
    )
    async def _dispatch_implementer(
        task_id: str,
        spec_path: str,
        working_directory: str,
        timeout_override_seconds: int | None = None,
        keep_worktree_on_failure: bool = False,
        engineer_decisions: list[dict[str, Any]] | None = None,
    ) -> DispatchImplementerOutput:
        agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store,"dispatch_implementer")
        caller_role = _resolve_caller_role_via_token()
        dispatcher = get_dispatcher()
        if dispatcher is None:
            raise ConflictError(
                "no dispatcher is running; dispatch_implementer requires the "
                "daemon's BridgeDispatcher to be constructed (canonical clone "
                "provisioning may still be in progress)"
            )
        implementer = registry.get("implementer")
        if implementer is None:
            raise ConflictError(
                "no implementer agent is registered; cannot mint a per-dispatch "
                "token. Issue an implementer keyring token via "
                "scripts/issue-token.py before enabling Engineer dispatch."
            )
        decisions = [EngineerDecision(**d) for d in (engineer_decisions or [])]
        return await dispatch_implementer(
            dispatcher=dispatcher,
            registry=registry,
            request=DispatchImplementerInput(
                task_id=task_id,
                spec_path=spec_path,
                working_directory=working_directory,
                timeout_override_seconds=timeout_override_seconds,
                keep_worktree_on_failure=keep_worktree_on_failure,
                engineer_decisions=decisions,
            ),
            caller_agent_id=agent_id,
            caller_role=caller_role,
            implementer_agent_id=implementer.agent_id,
        )

    @mcp.tool(
        name="dispatch_tester",
        description=(
            "Engineer-only. Dispatches a Tester sub-agent to verify the "
            "specified commit_hash against the assertion_spec prompt. Runs "
            "the dispatch lifecycle through to a terminal state and returns "
            "the structured test_results."
        ),
    )
    async def _dispatch_tester(
        task_id: str,
        commit_hash: str,
        working_directory: str,
        assertion_spec: str,
        timeout_override_seconds: int | None = None,
        keep_worktree_on_failure: bool = False,
        engineer_decisions: list[dict[str, Any]] | None = None,
    ) -> DispatchTesterOutput:
        agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store,"dispatch_tester")
        caller_role = _resolve_caller_role_via_token()
        dispatcher = get_dispatcher()
        if dispatcher is None:
            raise ConflictError(
                "no dispatcher is running; dispatch_tester requires the "
                "daemon's BridgeDispatcher to be constructed"
            )
        tester = registry.get("tester")
        if tester is None:
            raise ConflictError(
                "no tester agent is registered; cannot mint a per-dispatch "
                "token. Issue a tester keyring token via "
                "scripts/issue-token.py before enabling Engineer dispatch."
            )
        decisions = [EngineerDecision(**d) for d in (engineer_decisions or [])]
        return await dispatch_tester(
            dispatcher=dispatcher,
            registry=registry,
            request=DispatchTesterInput(
                task_id=task_id,
                commit_hash=commit_hash,
                working_directory=working_directory,
                assertion_spec=assertion_spec,
                timeout_override_seconds=timeout_override_seconds,
                keep_worktree_on_failure=keep_worktree_on_failure,
                engineer_decisions=decisions,
            ),
            caller_agent_id=agent_id,
            caller_role=caller_role,
            tester_agent_id=tester.agent_id,
        )


def _register_budget_tools(
    mcp: FastMCP, storage: Storage, token_store: TokenStore
) -> None:
    """Register budget/usage tools.

    Currently ships ``record_api_call`` (M5-record-api-call commit 4).
    Downstream M5-oauth-poller-and-get-window-state will add
    ``get_token_usage``, ``get_burn_rate``, ``check_budget_for_task``,
    and ``get_window_state`` to this same helper.

    Permission: record_api_call is allowed for all four agents per
    ``06-mcp-tools-phase-one.md`` § Permission summary table. No
    role check at handler or wrapper layer; any authenticated caller
    may record their own API calls. Single-gate pattern (W-3) is
    preserved by absence — no role-based gate exists at any layer
    because none is spec'd.

    The wrapper resolves both ``agent_id`` (for the api_usage row) and
    ``token_id`` (for the dispatch lookup) from the request context.
    Two distinct concerns: agent_id is the durable identity for the
    audit/usage row; token_id binds to the specific dispatch context
    the call landed inside (or no dispatch, for OAuth callers — see
    handler docstring for the three caller paths).
    """

    @mcp.tool(
        name="record_api_call",
        description=(
            "Record an Anthropic API call's token usage. Inserts an "
            "api_usage row with caller_agent_id, model, token counts, "
            "and computed cost_usd. When the caller is bound to an "
            "active (non-terminal) dispatch, atomically increments "
            "dispatches.actual_tokens by input_tokens + output_tokens. "
            "Allowed for all four agents (PM, Engineer, Implementer, "
            "Tester)."
        ),
    )
    async def _record_api_call(
        model: str,
        input_tokens: int,
        output_tokens: int,
        task_id: str | None = None,
    ) -> RecordApiCallOutput:
        agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store,"record_api_call")
        token_id = _resolve_caller_token_id()
        return await record_api_call(
            storage=storage,
            request=RecordApiCallInput(
                model=model,
                input_tokens=input_tokens,
                output_tokens=output_tokens,
                task_id=task_id,
            ),
            caller_agent_id=agent_id,
            caller_token_id=token_id,
        )


def _register_pm_mode_tools(
    mcp: FastMCP, storage: Storage, token_store: TokenStore
) -> None:
    """Register set_pm_mode (M5-pm-mode-and-window-state commit 4).

    Currently ships ``set_pm_mode`` only — the daemon-side auto-flip
    writer (commit 5 of this sub-milestone) is daemon-internal, not
    tool-shaped: it observes MCP request context to detect Mike's
    OAuth-PM token use and writes ``pm_mode_log`` rows with
    ``triggered_by='mike_message'`` directly. No future tools are
    planned in this helper since the auto-flip is a daemon
    observation hook, not a caller-invoked surface.

    Permission: PM only at the handler layer per
    ``06-mcp-tools-phase-one.md`` § ``set_pm_mode``. The wrapper
    resolves caller role via ``_resolve_caller_role_via_token`` and
    threads it into the handler; single-gate enforcement at the
    handler matches the established pattern (dispatch tools,
    supervisor tools).
    """

    @mcp.tool(
        name="set_pm_mode",
        description=(
            "PM only. Self-report a mode transition. Writes a "
            "pm_mode_log row with triggered_by='manual' when the "
            "mode actually changes; always emits a "
            "pm_mode.transition audit row regardless of whether the "
            "row was logged (operators see PM's set_pm_mode "
            "decision frequency, not just transition events). "
            "transition_logged in the output is True when a row was "
            "inserted, False on same-mode no-op."
        ),
    )
    async def _set_pm_mode(
        mode: PmMode,
        reason: str | None = None,
    ) -> SetPmModeOutput:
        agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store,"set_pm_mode")
        caller_role = _resolve_caller_role_via_token()
        return await set_pm_mode(
            storage=storage,
            request=SetPmModeInput(mode=mode, reason=reason),
            caller_agent_id=agent_id,
            caller_role=caller_role,
        )


def _register_window_state_tools(
    mcp: FastMCP,
    storage: Storage,
    token_store: TokenStore,
    get_oauth_usage_snapshot: SnapshotProvider,
) -> None:
    """Register get_window_state (M5-pm-mode-and-window-state commit 6).

    The ``get_oauth_usage_snapshot`` callable is evaluated lazily at
    each tool invocation. Mirrors the lazy-callable pattern used for
    ``get_dispatcher`` / ``get_engineer_supervisor``: the
    OAuthUsagePoller is constructed in ``main.py``'s lifespan after
    the MCP server starts (its first poll runs against the live
    network), so a closure-captured reference resolved at build_server
    time would always see ``None``. Python closures resolve names at
    call time, so the lambda picks up the snapshot's current value
    when invoked.

    Cold-start handling: when the poller hasn't published a successful
    snapshot yet, the callable returns ``None``. The handler treats
    this as the cold-start case and returns the unknown placeholder
    per Q3 disposition.

    Permission: PM and Engineer per ``06-mcp-tools-phase-one.md`` §
    ``get_window_state``. Implementer/Tester rejected at the handler
    layer via ``_require_role``.
    """

    @mcp.tool(
        name="get_window_state",
        description=(
            "PM/Engineer only. Per-bucket window state from the "
            "daemon's cached /api/oauth/usage snapshot. Returns "
            "utilization, window_start/end, minutes_to_reset, "
            "pace_recommendation, snapshot_age_seconds (None on "
            "cold-start), null_counters, and PM mode fields. Returns "
            "pace_recommendation='unknown' on cold-start, stale "
            "snapshot (>120s), null bucket, or api_key_overflow "
            "(deferred to M5-flag-flips). Bucket defaults to "
            "subscription_5h (the primary pacing counter)."
        ),
    )
    async def _get_window_state(
        agent_id: str | None = None,
        bucket: str = "subscription_5h",
    ) -> GetWindowStateOutput:
        caller_agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store, "get_window_state")
        caller_role = _resolve_caller_role_via_token()
        return await get_window_state(
            storage=storage,
            get_snapshot=get_oauth_usage_snapshot,
            request=GetWindowStateInput(
                agent_id=agent_id,
                bucket=bucket,  # type: ignore[arg-type]
            ),
            caller_agent_id=caller_agent_id,
            caller_role=caller_role,
        )


def _register_dispatch_completion_tools(
    mcp: FastMCP,
    storage: Storage,
    token_store: TokenStore,
) -> None:
    """Register complete_dispatch / get_dispatch_status / get_dispatch_result.

    These three handlers don't need a dispatcher or registry — they read
    and update the dispatches row directly using the token store for
    self-access enforcement. complete_dispatch is implementer/tester
    only; the status/result reads enforce subtree-scoped visibility per
    handler.

    Permission enforcement is single-gate at handler ``_require_role``
    (and ``_enforce_self_dispatch_access`` for the read tools); wrapper
    layer does not double-check via PermissionResolver. Pattern matches
    query tools and supervisor tools from M4c-tooling. Static-token
    paths resolve role from OAuth scopes via
    ``_resolve_caller_role_via_token``; do not require registry.
    """

    @mcp.tool(
        name="complete_dispatch",
        description=(
            "Implementer/Tester only. Sub-agent's structured callback to "
            "report the dispatch's terminal state. Writes status, "
            "commit_hash, test_results, queue_items_raised, decline_reason, "
            "artifacts, and notes to the dispatches row; the dispatcher's "
            "await loop observes the row update and exits cleanly."
        ),
    )
    async def _complete_dispatch(
        dispatch_id: str,
        status: str,
        commit_hash: str | None = None,
        test_results: dict[str, Any] | None = None,
        queue_items_raised: list[str] | None = None,
        decline_reason: str | None = None,
        artifacts: list[str] | None = None,
        notes: str | None = None,
    ) -> CompleteDispatchOutput:
        agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store,"complete_dispatch")
        caller_role = _resolve_caller_role_via_token()
        return await complete_dispatch(
            storage=storage,
            token_store=token_store,
            request=CompleteDispatchInput(
                dispatch_id=dispatch_id,
                status=status,  # type: ignore[arg-type]
                commit_hash=commit_hash,
                test_results=test_results,
                queue_items_raised=queue_items_raised or [],
                decline_reason=decline_reason,
                artifacts=artifacts or [],
                notes=notes,
            ),
            caller_agent_id=agent_id,
            caller_role=caller_role,
        )

    @mcp.tool(
        name="get_dispatch_status",
        description=(
            "Subtree-scoped read of a dispatch's in-flight lifecycle "
            "fields. Implementer/Tester see only their own dispatches; "
            "PM/Engineer see the full subtree."
        ),
    )
    async def _get_dispatch_status(
        dispatch_id: str,
    ) -> GetDispatchStatusOutput:
        agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store,"get_dispatch_status")
        caller_role = _resolve_caller_role_via_token()
        return await get_dispatch_status(
            storage=storage,
            token_store=token_store,
            request=GetDispatchStatusInput(dispatch_id=dispatch_id),
            caller_agent_id=agent_id,
            caller_role=caller_role,
        )

    @mcp.tool(
        name="get_dispatch_result",
        description=(
            "Subtree-scoped read of a dispatch's terminal-state result "
            "(commit_hash, decline_reason, test_results, queue items, "
            "artifacts, notes, actual_touched_files). "
            "Implementer/Tester see only their own dispatches; PM/Engineer "
            "see the full subtree."
        ),
    )
    async def _get_dispatch_result(
        dispatch_id: str,
    ) -> GetDispatchResultOutput:
        agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store,"get_dispatch_result")
        caller_role = _resolve_caller_role_via_token()
        return await get_dispatch_result(
            storage=storage,
            token_store=token_store,
            request=GetDispatchResultInput(dispatch_id=dispatch_id),
            caller_agent_id=agent_id,
            caller_role=caller_role,
        )


def _register_dispatch_query_tools(
    mcp: FastMCP, storage: Storage, token_store: TokenStore
) -> None:
    """Register get_recent_dispatches and get_dispatch_events.

    Both tools share the ``_scoping_where_clause`` helper in
    ``tools/dispatch.py`` (single source of truth for the M4c
    subtree-scoping rules). PM and Engineer can call; Implementer/Tester
    are rejected at the handler.
    """

    @mcp.tool(
        name="get_recent_dispatches",
        description=(
            "Read-only query of recent dispatches in the caller's subtree. "
            "Engineer sees sub-agent dispatches it initiated; PM sees "
            "sub-agent dispatches across all tasks. Supervisor lifecycle "
            "rows are excluded at M4c."
        ),
    )
    async def _get_recent_dispatches(
        role: str | None = None,
        status: list[str] | None = None,
        since: str | None = None,
        limit: int = 50,
    ) -> GetRecentDispatchesOutput:
        agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store,"get_recent_dispatches")
        caller_role = _resolve_caller_role_via_token()
        return await get_recent_dispatches(
            storage=storage,
            request=GetRecentDispatchesInput(
                role=role,  # type: ignore[arg-type]
                status=status,
                since=since,
                limit=limit,
            ),
            caller_agent_id=agent_id,
            caller_role=caller_role,
        )

    @mcp.tool(
        name="get_dispatch_events",
        description=(
            "Read-only query of audit events for the requested dispatch_ids. "
            "Caller-supplied IDs are filtered through the same subtree-scoping "
            "as get_recent_dispatches before audit-log access."
        ),
    )
    async def _get_dispatch_events(
        dispatch_ids: list[str], since_timestamp: str | None = None
    ) -> GetDispatchEventsOutput:
        agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store,"get_dispatch_events")
        caller_role = _resolve_caller_role_via_token()
        return await get_dispatch_events(
            storage=storage,
            request=GetDispatchEventsInput(
                dispatch_ids=dispatch_ids, since_timestamp=since_timestamp
            ),
            caller_agent_id=agent_id,
            caller_role=caller_role,
        )


def _register_supervisor_tools(
    mcp: FastMCP,
    storage: Storage,
    token_store: TokenStore,
    get_engineer_supervisor: Callable[[], LongRunningSupervisor | None],
) -> None:
    """Register supervisor-lifecycle tools.

    The ``get_engineer_supervisor`` callable is evaluated lazily at each
    tool invocation. The supervisor is constructed in ``main.py`` AFTER
    the MCP server starts (the supervisor's MCP calls back to the daemon
    need the server up), so a closure-captured reference would be stale
    at build_server time. The callable resolves at call time, returning
    the current supervisor or None if it hasn't been constructed yet.
    """

    @mcp.tool(
        name="signal_compaction",
        description=(
            "Engineer-only. Signals an imminent clean exit for compaction. "
            "Sets the supervisor's compaction-pending flag; the watchdog "
            "observes the subsequent clean exit and respawns the Engineer "
            "fresh with status='compacted' (no respawn-cap increment)."
        ),
    )
    async def _signal_compaction(
        iteration_marker: str, proxy_value: float | None = None
    ) -> SignalCompactionOutput:
        agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store,"signal_compaction")
        caller_role = _resolve_caller_role_via_token()
        supervisor = get_engineer_supervisor()
        if supervisor is None:
            raise ConflictError(
                "no engineer supervisor is running; signal_compaction "
                "requires an active supervisor instance"
            )
        return await signal_compaction(
            storage=storage,
            supervisor=supervisor,
            request=SignalCompactionInput(
                iteration_marker=iteration_marker, proxy_value=proxy_value
            ),
            caller_agent_id=agent_id,
            caller_role=caller_role,
        )


def _resolve_caller_role_via_token() -> str:
    """Resolve the caller's role from the OAuth scope claims on the access
    token. The OAuth provider sets ``scopes=[f'role:{record.role}']`` per
    ``oauth.py:_record_to_access_token``; we extract the role from there
    instead of going through the registry (the registry path requires a
    YAML config to be loaded, which isn't a guarantee for static-token
    callers like the engineer-supervisor).
    """
    access = get_access_token()
    if access is None:
        raise AuthError("no authenticated context for tool call")
    for scope in access.scopes:
        if scope.startswith("role:"):
            return scope.removeprefix("role:")
    raise AuthError("access token has no role scope")


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
    mcp: FastMCP,
    registry: AgentRegistry,
    storage: Storage | None,
    token_store: TokenStore | None,
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
        await _observe_caller(storage, token_store,"list_agents")
        resolver = PermissionResolver(registry.configs_by_role())
        resolver.require_tool(caller_role, "list_agents")  # type: ignore[arg-type]
        return list_agents(registry, ListAgentsInput(role=role, status=status))

    @mcp.tool(
        name="get_agent_config",
        description="Return the public configuration of an agent (system_prompt excluded).",
    )
    async def _get_agent_config(agent_id: str) -> GetAgentConfigOutput:
        caller_role = _resolve_caller_role(registry)
        await _observe_caller(storage, token_store,"get_agent_config")
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
        await _observe_caller(storage, token_store,"get_org_chart")
        resolver = PermissionResolver(registry.configs_by_role())
        resolver.require_tool(caller_role, "get_org_chart")  # type: ignore[arg-type]
        return get_org_chart(registry, GetOrgChartInput())


async def serve(
    settings: Settings,
    ready: asyncio.Event | None = None,
    registry: AgentRegistry | None = None,
    storage: Storage | None = None,
    get_engineer_supervisor: Callable[[], LongRunningSupervisor | None] | None = None,
    get_dispatcher: Callable[[], BridgeDispatcher | None] | None = None,
    get_oauth_usage_snapshot: SnapshotProvider | None = None,
) -> None:
    """Run the MCP HTTP server until cancelled.

    Cancelling the awaiting task triggers a graceful uvicorn shutdown. Pass
    ``ready`` to be signalled once the listener is accepting connections (used
    by the integration test). Pass ``registry`` for M3+ tools; ``None`` keeps
    the M2-only ``ping``-tool surface for tests that don't need agent state.
    Pass ``storage`` to enable the ``last_activity_at`` writer middleware
    (M4c-supervisor-base); ``None`` is fine for tests that don't exercise
    dispatch-row state. Pass ``get_dispatcher`` to register the M5
    dispatch-initiation wrappers (dispatch_implementer, dispatch_tester);
    ``None`` keeps those tools unregistered for tests that don't need
    them.
    """
    mcp = build_server(
        settings,
        registry=registry,
        storage=storage,
        get_engineer_supervisor=get_engineer_supervisor,
        get_dispatcher=get_dispatcher,
        get_oauth_usage_snapshot=get_oauth_usage_snapshot,
    )
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
