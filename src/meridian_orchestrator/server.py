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
from pathlib import Path
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
from meridian_orchestrator.audit.sampler import (
    RecordSampleableEventInput,
    RecordSampleableEventOutput,
    Sampler,
    SourceEventKind,
    record_sampleable_event,
)
from meridian_orchestrator.auth import TokenSource, TokenStore, _parse_token
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
    BurnRateWindowLiteral,
    CheckBudgetForTaskInput,
    CheckBudgetForTaskOutput,
    GetBurnRateInput,
    GetBurnRateOutput,
    GetTokenUsageInput,
    GetTokenUsageOutput,
    GetWindowStateInput,
    GetWindowStateOutput,
    RecordApiCallInput,
    RecordApiCallOutput,
    SnapshotProvider,
    WindowLiteral,
    check_budget_for_task,
    get_burn_rate,
    get_token_usage,
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
from meridian_orchestrator.tools.pm_focus import (
    CompleteCurrentFocusInput,
    CompleteCurrentFocusOutput,
    DeclareCurrentFocusInput,
    DeclareCurrentFocusOutput,
    GetCurrentFocusOutput,
    OutputType,
    complete_current_focus,
    declare_current_focus,
    get_current_focus,
)
from meridian_orchestrator.tools.pm_handoff import (
    GetMostRecentHandoffOutput,
    WritePmHandoffInput,
    WritePmHandoffOutput,
    get_most_recent_handoff,
    write_pm_handoff,
)
from meridian_orchestrator.tools.pm_loop import (
    GetLoopStateOutput,
    WaitForNextTickOutput,
    get_loop_state,
    wait_for_next_tick,
)
from meridian_orchestrator.tools.pm_mode import (
    PmMode,
    SetPmModeInput,
    SetPmModeOutput,
    set_pm_mode,
)
from meridian_orchestrator.tools.pm_spin import (
    Decision,
    RationaleTag,
    RecordSpinRationaleInput,
    RecordSpinRationaleOutput,
    record_spin_rationale,
)
from meridian_orchestrator.tools.supervisor import (
    SignalCompactionInput,
    SignalCompactionOutput,
    signal_compaction,
)
from meridian_orchestrator.tools.tasks import (
    ClaimTaskInput,
    ClaimTaskOutput,
    GetQueueInput,
    GetQueueOutput,
    GetSubtasksInput,
    GetSubtasksOutput,
    SubmitTaskInput,
    SubmitTaskOutput,
    UpdateTaskStatusInput,
    UpdateTaskStatusOutput,
    claim_task,
    get_queue,
    get_subtasks,
    submit_task,
    update_task_status,
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


def _resolve_caller_source(token_store: TokenStore) -> TokenSource:
    """Read the caller's ``TokenRecord.source`` from the request context.

    Mirrors :func:`_resolve_caller_token_id` shape but returns the
    token's source — ``"static"`` for daemon-issued tokens (Engineer /
    Implementer / Tester / PM-continuous subprocess) or ``"oauth"`` for
    Mike-via-Claude-Code-OAuth-flow tokens. Per
    ``project_token_source_distinguishes_oauth_from_static`` memory:
    the auth chain already exposes this distinction at issuance time;
    no auth-chain modification needed for handler-side reads.

    First operational consumer at M4d-task-management-surface commit 3:
    :func:`tools.tasks.submit_task` uses ``caller_source`` to gate
    self-submit per Q9 (A) disposition (PM-as-agent self-submit
    rejected; Mike-via-OAuth allowed) and to populate ``submitted_by``
    per CQ3 disposition (OAuth → ``"human:mike"``; static →
    ``caller_agent_id``).

    Raises :class:`AuthError` when no access context exists, the bearer
    token isn't parseable, or ``token_id`` doesn't resolve to a
    record. All three are wrapper-layer bugs, not caller errors.
    """
    access = get_access_token()
    if access is None:
        raise AuthError("no authenticated context for tool call")
    parsed = _parse_token(access.token)
    if parsed is None:
        raise AuthError("could not parse bearer token")
    token_id, _ = parsed
    record = token_store.lookup_by_token_id(token_id)
    if record is None:
        raise AuthError(
            f"could not resolve TokenRecord for token_id={token_id!r}"
        )
    return record.source


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
    get_pm_supervisor: Callable[[], LongRunningSupervisor | None] | None = None,
    get_dispatcher: Callable[[], BridgeDispatcher | None] | None = None,
    get_oauth_usage_snapshot: SnapshotProvider | None = None,
    sampler: Sampler | None = None,
    get_pm_loop_tick_seconds: Callable[[], float] | None = None,
    get_pm_handoff_dir: Callable[[], Path] | None = None,
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
            _register_task_tools(mcp, registry, storage, token_store)

    if storage is not None:
        _register_dispatch_query_tools(mcp, storage, token_store)
        _register_dispatch_completion_tools(mcp, storage, token_store)
        _register_budget_tools(
            mcp, storage, token_store, get_oauth_usage_snapshot
        )
        _register_pm_mode_tools(mcp, storage, token_store)
        _register_pm_focus_tools(mcp, storage, token_store)
        _register_pm_spin_tools(mcp, storage, token_store)
        if sampler is not None:
            _register_audit_sample_tools(mcp, storage, token_store, sampler)
        if get_pm_handoff_dir is not None:
            _register_pm_handoff_tools(
                mcp, storage, token_store, get_pm_handoff_dir
            )

    if storage is not None and get_oauth_usage_snapshot is not None:
        _register_window_state_tools(
            mcp, storage, token_store, get_oauth_usage_snapshot
        )
        if get_pm_loop_tick_seconds is not None:
            _register_pm_loop_tools(
                mcp,
                storage,
                token_store,
                get_oauth_usage_snapshot,
                get_pm_loop_tick_seconds,
            )

    if storage is not None and get_dispatcher is not None and registry is not None:
        _register_dispatch_initiation_tools(
            mcp, storage, registry, token_store, get_dispatcher
        )

    if storage is not None and (
        get_engineer_supervisor is not None or get_pm_supervisor is not None
    ):
        _register_supervisor_tools(
            mcp,
            storage,
            token_store,
            get_engineer_supervisor,
            get_pm_supervisor,
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
    mcp: FastMCP,
    storage: Storage,
    token_store: TokenStore,
    get_oauth_usage_snapshot: SnapshotProvider | None,
) -> None:
    """Register budget/usage tools.

    Ships ``record_api_call`` (M5-record-api-call commit 4),
    ``get_token_usage`` and ``get_burn_rate`` (M5-subscription-routing
    commit 2), and ``check_budget_for_task`` (M5-subscription-routing
    commit 5; the pure routing function it wraps landed at commit 4
    of the same sub-milestone). ``get_window_state`` lives in
    :func:`_register_window_state_tools` because that helper was
    introduced before this one acquired a snapshot dependency; the
    two helpers may merge in a future hygiene commit.

    Permission: record_api_call is allowed for all four agents.
    get_token_usage is hierarchy-scoped (PM/Engineer subtree;
    Implementer/Tester own only) at the handler layer.
    get_burn_rate is PM/Engineer/Mike at the handler layer (single-
    gate via ``_require_role`` per the established pattern).

    The wrappers resolve identity facts (``agent_id`` via
    ``_resolve_agent_id``; ``role`` via
    ``_resolve_caller_role_via_token``; ``token_id`` via
    ``_resolve_caller_token_id`` for record_api_call) and pass them
    to the handlers as positional kwargs.

    ``get_oauth_usage_snapshot`` is optional — the wrappers register
    regardless. When ``None``, ``get_token_usage`` / ``get_burn_rate``
    fall back to calendar_24h for ``current`` / ``last`` window
    requests via the helper's snapshot-unavailable path; the response
    surfaces ``window_resolution_fallback='calendar_24h'``.
    """

    @mcp.tool(
        name="record_api_call",
        description=(
            "Record an Anthropic API call's token usage. Inserts an "
            "api_usage row with caller_agent_id, model, token counts, "
            "and computed cost_usd. When the caller is bound to an "
            "active (non-terminal) dispatch, atomically increments "
            "dispatches.actual_tokens by input_tokens + output_tokens. "
            "Optional cost-interrogation observability fields (notes, "
            "workflow_id, task_type) ride along when the caller "
            "supplies them. agent_role is auto-populated server-side "
            "from the caller's resolved role. Allowed for all four "
            "agents (PM, Engineer, Implementer, Tester)."
        ),
    )
    async def _record_api_call(
        model: str,
        input_tokens: int,
        output_tokens: int,
        task_id: str | None = None,
        notes: str | None = None,
        workflow_id: str | None = None,
        task_type: str | None = None,
    ) -> RecordApiCallOutput:
        agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store,"record_api_call")
        token_id = _resolve_caller_token_id()
        caller_role = _resolve_caller_role_via_token()
        return await record_api_call(
            storage=storage,
            request=RecordApiCallInput(
                model=model,
                input_tokens=input_tokens,
                output_tokens=output_tokens,
                task_id=task_id,
                notes=notes,
                workflow_id=workflow_id,
                task_type=task_type,
            ),
            caller_agent_id=agent_id,
            caller_agent_role=caller_role,
            caller_token_id=token_id,
        )

    # Lazy snapshot lookup: the optional get_oauth_usage_snapshot
    # callable is captured here so the wrappers below resolve it at
    # call time. When the operator hasn't set
    # CLAUDE_CODE_OAUTH_TOKEN, this is None and the handlers fall
    # back to calendar_24h for current/last window requests.
    def _snapshot_provider() -> Any:
        if get_oauth_usage_snapshot is None:
            return None
        return get_oauth_usage_snapshot()

    @mcp.tool(
        name="get_token_usage",
        description=(
            "Per-window token-usage aggregation. Hierarchy-scoped: "
            "PM/Engineer see their subtree, Implementer/Tester see "
            "only their own usage. window selects 'current' / 'last' "
            "(subscription_5h-relative) or 'today' / 'last_24h' "
            "(calendar). Output includes by_model and (when "
            "agent_id is None) by_agent breakdowns plus "
            "window_resolution_fallback='calendar_24h' when the "
            "OAuth snapshot was unavailable for current/last and "
            "the resolver fell back to a rolling 24h window."
        ),
    )
    async def _get_token_usage(
        agent_id: str | None = None,
        window: WindowLiteral = "current",
    ) -> GetTokenUsageOutput:
        caller_agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store, "get_token_usage")
        caller_role = _resolve_caller_role_via_token()
        return await get_token_usage(
            storage=storage,
            get_snapshot=_snapshot_provider,
            request=GetTokenUsageInput(agent_id=agent_id, window=window),
            caller_agent_id=caller_agent_id,
            caller_role=caller_role,
        )

    @mcp.tool(
        name="get_burn_rate",
        description=(
            "Subscription-window burn rate (tokens/hour, projected "
            "window total, cost/hour, projected window cost). "
            "Allowed for PM, Engineer, and Mike — Implementer/Tester "
            "denied. Scope is global (whole subscription), not the "
            "caller's role-scoped slice. window selects 'current' / "
            "'last' (subscription_5h-relative) or 'last_24h' "
            "(rolling). For closed windows projected equals "
            "observed; only 'current' projects forward. "
            "window_resolution_fallback='calendar_24h' signals the "
            "snapshot-unavailable fallback path."
        ),
    )
    async def _get_burn_rate(
        window: BurnRateWindowLiteral = "current",
    ) -> GetBurnRateOutput:
        _resolve_agent_id()
        await _observe_caller(storage, token_store, "get_burn_rate")
        caller_role = _resolve_caller_role_via_token()
        return await get_burn_rate(
            storage=storage,
            get_snapshot=_snapshot_provider,
            request=GetBurnRateInput(window=window),
            caller_role=caller_role,
        )

    @mcp.tool(
        name="check_budget_for_task",
        description=(
            "Routing decision for a pending task. Returns "
            "recommended_model + recommended_bucket plus structured "
            "routing_factors (queryable as data) + fallback_active "
            "flag + rationale_text (human-readable summary). "
            "Allowed for PM, Engineer, and Mike (via PM-OAuth) — "
            "Implementer/Tester denied (their model is fixed by "
            "Engineer at dispatch time; they don't choose models). "
            "complexity and priority are 1..5; bounds enforced via "
            "Pydantic. fallback_active=True surfaces when the OAuth "
            "snapshot is unavailable (None / stale / malformed); "
            "per-role defaults apply with no priority delta or Opus-"
            "downgrade. Not audited (high-frequency read-only "
            "decision); structlog DEBUG trace available for "
            "operator deep-dive."
        ),
    )
    async def _check_budget_for_task(
        complexity: int,
        priority: int,
    ) -> CheckBudgetForTaskOutput:
        _resolve_agent_id()
        await _observe_caller(storage, token_store, "check_budget_for_task")
        caller_role = _resolve_caller_role_via_token()
        snapshot = _snapshot_provider()
        # check_budget_for_task is async to match the wrapper-pattern
        # convention but its body wraps the pure _compute_routing
        # function (no I/O). Pydantic validates complexity/priority
        # bounds via the CheckBudgetForTaskInput model construction.
        return await check_budget_for_task(
            request=CheckBudgetForTaskInput(
                complexity=complexity, priority=priority
            ),
            snapshot=snapshot,
            caller_role=caller_role,
            now=datetime.now(tz=UTC),
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


def _register_pm_focus_tools(
    mcp: FastMCP, storage: Storage, token_store: TokenStore
) -> None:
    """Register declare_current_focus, complete_current_focus,
    get_current_focus (M5-continuous-pm-driver commit 2).

    Three MCP tools ship co-shipping per F3.6.C Q6 Reading A
    disposition: write surface (declare + complete) and daemon-internal
    read surface (get) co-design with the table schema. Deferring the
    read surface to commit 7 would force commit 7's prep to re-litigate
    output schema, empty-state contract, permission scope, and
    caller-context decisions that should settle here with the table
    design conversation. Per BACKLOG § Observability roadmap scope
    clarification 2026-05-04, the "read-side post-flag-flips" sequencing
    applies to operator-facing tier-2 surfaces, not daemon-internal
    subprocess reads — the continuous-loop driver subprocess at
    commits 7-8 IS the planned daemon-internal consumer of get.

    Permission: all three PM-only at the handler layer per
    ``06-mcp-tools-phase-one.md`` § ``declare_current_focus`` /
    ``complete_current_focus`` / ``get_current_focus``. Engineer
    promotion deferred per Q6 disposition.
    """

    @mcp.tool(
        name="declare_current_focus",
        description=(
            "PM only. Declare the active work-unit boundary: an "
            "objective, an output_type (one of document, code_change, "
            "decision, review, investigation), and acceptance criteria "
            "for completion-time validation. Optional bound_workflow_id "
            "ties the focus to a top-level submitted task chain. "
            "Auto-supersedes any prior incomplete focus in the same "
            "transaction (the prior row's contract_finding_text becomes "
            "'superseded'); the at-most-one-active-focus invariant is "
            "preserved across Mike-interrupt-driven pivots. Emits "
            "pm.current_focus.declared (and pm.current_focus.superseded "
            "when a prior focus was active)."
        ),
    )
    async def _declare_current_focus(
        objective: str,
        output_type: OutputType,
        acceptance_criteria: str,
        bound_workflow_id: str | None = None,
    ) -> DeclareCurrentFocusOutput:
        agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store, "declare_current_focus")
        caller_role = _resolve_caller_role_via_token()
        return await declare_current_focus(
            storage=storage,
            request=DeclareCurrentFocusInput(
                objective=objective,
                output_type=output_type,
                acceptance_criteria=acceptance_criteria,
                bound_workflow_id=bound_workflow_id,
            ),
            caller_agent_id=agent_id,
            caller_role=caller_role,
        )

    @mcp.tool(
        name="complete_current_focus",
        description=(
            "PM only. Close the active focus with the loop's validation "
            "result. contract_validated=False emits an additional "
            "pm.current_focus.contract_violation audit row alongside "
            "pm.current_focus.completed in the same transaction. "
            "Implicit-current shape: no focus_id parameter; the "
            "at-most-one invariant means the active focus is "
            "unambiguous. Raises an error when no active focus exists."
        ),
    )
    async def _complete_current_focus(
        contract_validated: bool,
        finding_text: str | None = None,
        actual_output_summary: str | None = None,
    ) -> CompleteCurrentFocusOutput:
        agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store, "complete_current_focus")
        caller_role = _resolve_caller_role_via_token()
        return await complete_current_focus(
            storage=storage,
            request=CompleteCurrentFocusInput(
                contract_validated=contract_validated,
                finding_text=finding_text,
                actual_output_summary=actual_output_summary,
            ),
            caller_agent_id=agent_id,
            caller_role=caller_role,
        )

    @mcp.tool(
        name="get_current_focus",
        description=(
            "PM only. Returns the active pm_current_focus row "
            "(current_focus field) or None when no active focus "
            "exists. Daemon-internal read consumed by the "
            "continuous-loop driver subprocess; not audited "
            "(read-only, called frequently)."
        ),
    )
    async def _get_current_focus() -> GetCurrentFocusOutput:
        _resolve_agent_id()
        await _observe_caller(storage, token_store, "get_current_focus")
        caller_role = _resolve_caller_role_via_token()
        return await get_current_focus(
            storage=storage,
            caller_role=caller_role,
        )


def _register_pm_spin_tools(
    mcp: FastMCP, storage: Storage, token_store: TokenStore
) -> None:
    """Register record_spin_rationale (M5-continuous-pm-driver commit 3).

    Single MCP tool ships at this commit per F4.2 Q5 Reading A
    disposition: PM-continuous subprocess at commits 7-8 is the
    committed consumer; co-shipping the writer with the table avoids
    prep-debt at commit 7 (mirrors commit 2's Q6 Reading A
    re-litigation-cost reasoning, asymmetric on read-side per Q4
    Reading B).

    No read-side MCP tool at commit 3 — pm_spin_rationale_log's
    consumers are aggregations (tier-2 observability roll-ups), not
    most-recent-active reads. Read surfaces ship post-M5-flag-flips
    per BACKLOG observability sequencing.

    Permission: PM-only at the handler layer per
    ``06-mcp-tools-phase-one.md`` § ``record_spin_rationale``.
    """

    @mcp.tool(
        name="record_spin_rationale",
        description=(
            "PM only. Record one loop-iteration keep-alive-vs-shutdown "
            "decision with closed-enum rationale_tag (queued_work, "
            "mike_presence, heartbeat_cheap, forgot_to_shutdown). "
            "Per-tick emission; no dedup — every iteration produces a "
            "row regardless of whether the (decision, rationale_tag) "
            "tuple matches the prior iteration. Optional "
            "rationale_free_text carries supplementary diagnostic "
            "context. Not audited — pm_spin_rationale_log IS itself "
            "the durable record."
        ),
    )
    async def _record_spin_rationale(
        decision: Decision,
        rationale_tag: RationaleTag,
        rationale_free_text: str | None = None,
    ) -> RecordSpinRationaleOutput:
        _resolve_agent_id()
        await _observe_caller(storage, token_store, "record_spin_rationale")
        caller_role = _resolve_caller_role_via_token()
        return await record_spin_rationale(
            storage=storage,
            request=RecordSpinRationaleInput(
                decision=decision,
                rationale_tag=rationale_tag,
                rationale_free_text=rationale_free_text,
            ),
            caller_role=caller_role,
        )


def _register_audit_sample_tools(
    mcp: FastMCP,
    storage: Storage,
    token_store: TokenStore,
    sampler: Sampler,
) -> None:
    """Register record_sampleable_event (M5-continuous-pm-driver commit 4).

    Single MCP tool ships at this commit per F5.2 Q3 Reading A
    disposition: explicit hook tool. PM's loop driver subprocess at
    commits 7-8 calls this tool after observing sampleable events;
    sampler picks per audit_sample_percentage config and writes the
    audit_sample_log row if selected.

    The sampler instance is constructed at lifespan startup from
    Settings.audit_sample_percentage and passed directly here (not via
    closure) — the Sampler is constructed-once-and-immutable, so the
    closure-callable pattern (used for OAuthUsagePoller's late-binding
    snapshot) doesn't apply.

    Permission: PM-only at the handler layer per
    ``06-mcp-tools-phase-one.md`` § ``record_sampleable_event``.
    """

    @mcp.tool(
        name="record_sampleable_event",
        description=(
            "PM only. Hook tool: declare that a sampleable event "
            "occurred. Sampler coin-flips per audit_sample_percentage "
            "config; if selected, writes an audit_sample_log row with "
            "re_eval_state='pending' and returns sample_id; if not "
            "selected, returns sample_id=None (most common case at "
            "default-disabled config). source_event_kind must be one "
            "of: pm.current_focus.completed, "
            "pm.current_focus.contract_violation, "
            "pm.current_focus.superseded, pm.spin_rationale.recorded, "
            "dispatch.complete_called. source_event_id is the row id "
            "from the canonical source table (audit_log.id for the "
            "first four kinds; pm_spin_rationale_log.id for the fifth, "
            "since spin_rationale events have no audit_log integration "
            "per commit 3 Q6 Reading A). Not audited — "
            "audit_sample_log IS itself the durable record."
        ),
    )
    async def _record_sampleable_event(
        source_event_kind: SourceEventKind,
        source_event_id: str,
    ) -> RecordSampleableEventOutput:
        _resolve_agent_id()
        await _observe_caller(storage, token_store, "record_sampleable_event")
        caller_role = _resolve_caller_role_via_token()
        return await record_sampleable_event(
            storage=storage,
            sampler=sampler,
            request=RecordSampleableEventInput(
                source_event_kind=source_event_kind,
                source_event_id=source_event_id,
            ),
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


def _register_pm_loop_tools(
    mcp: FastMCP,
    storage: Storage,
    token_store: TokenStore,
    get_oauth_usage_snapshot: SnapshotProvider,
    get_pm_loop_tick_seconds: Callable[[], float],
) -> None:
    """Register wait_for_next_tick + get_loop_state (M5-continuous-pm-driver
    commit 7).

    Two MCP tools ship per F8.6 dispositions Q1-A + Q3-A:
    PM-continuous subprocess at this commit forward is the load-bearing
    caller; both tools bundle / pace the loop's per-iteration sequence.

    **wait_for_next_tick** is the first server-side blocking MCP tool
    in Phase 1 — sleeps for ``Settings.pm_loop_tick_seconds`` (default
    60s per F8.2 Q2-B) then returns. Cadence resolved via the
    ``get_pm_loop_tick_seconds`` closure (mirrors
    ``get_oauth_usage_snapshot`` lazy-callable pattern; lifespan-time
    config). Pattern flagged in BACKLOG.md § Architectural patterns
    to review.

    **get_loop_state** aggregates per-iteration reads (subscription_5h
    + subscription_7d_opus window state + global burn rate + active
    pm_current_focus) into one MCP response. Reduces per-iteration
    MCP read count from 3-4 to 1.

    Permission: PM-only at handler layer. Mike's interactive PM
    session governed by client-side defaults; doesn't run the
    continuous-loop iteration sequence.
    """

    @mcp.tool(
        name="wait_for_next_tick",
        description=(
            "PM only. Sleep for the configured pm_loop_tick_seconds "
            "(default 60s) then return. PM's continuous loop calls "
            "this at the end of each iteration to pace the loop. "
            "Cadence is daemon-controlled; PM-prompt-driven call "
            "pattern. Returns slept_seconds for cadence verification."
        ),
    )
    async def _wait_for_next_tick() -> WaitForNextTickOutput:
        _resolve_agent_id()
        await _observe_caller(storage, token_store, "wait_for_next_tick")
        caller_role = _resolve_caller_role_via_token()
        return await wait_for_next_tick(
            get_tick_seconds=get_pm_loop_tick_seconds,
            caller_role=caller_role,
        )

    @mcp.tool(
        name="get_loop_state",
        description=(
            "PM only. Aggregator: returns per-iteration loop state in "
            "one MCP response. Bundles subscription_5h window state "
            "(primary pacing + Mike-presence detection via "
            "current_pm_mode field), subscription_7d_opus window state "
            "(routing-tier downgrade context), global burn rate, and "
            "active pm_current_focus row. Reduces per-iteration read "
            "count from 3-4 to 1. Read-side aggregation is asymmetric "
            "vs write-side: reads share point-in-time freshness; "
            "writes have distinct audit-trail semantics that bundling "
            "would conflate."
        ),
    )
    async def _get_loop_state() -> GetLoopStateOutput:
        agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store, "get_loop_state")
        caller_role = _resolve_caller_role_via_token()
        return await get_loop_state(
            storage=storage,
            get_snapshot=get_oauth_usage_snapshot,
            caller_agent_id=agent_id,
            caller_role=caller_role,
        )


def _register_pm_handoff_tools(
    mcp: FastMCP,
    storage: Storage,
    token_store: TokenStore,
    get_pm_handoff_dir: Callable[[], Path],
) -> None:
    """Register write_pm_handoff + get_most_recent_handoff
    (M5-continuous-pm-driver commit 8).

    Two MCP tools ship per F9.2 Q1 Reading B (Reading Y disposition):
    PM-continuous subprocess at compaction-trigger writes a handoff
    doc summarizing current_focus + planned-next + open-questions +
    recent-decisions-pointer + cohort-summary (per F2.3 Q1 Reading C
    PM-yaml convention); the new PM subprocess on respawn reads the
    most-recent handoff at startup.

    **write_pm_handoff** (PM-only): writes the markdown content to
    ``<handoff_dir>/<iso-timestamp>.md``; audits
    ``pm.compaction.handoff_written`` with details (path, filename,
    content_size, written_at).

    **get_most_recent_handoff** (PM-only): returns the most-recent
    handoff's content + written-at, or both None on cold-start.

    Architectural separation per F9.0 Reading Y disposition (2026-05-07):
    handoff-doc reading is subprocess-prompt concern via these MCP
    tools, NOT daemon-side ``_reconstruct_state`` override. The handoff
    directory path flows through main.py lifespan via the
    ``get_pm_handoff_dir`` closure (mirrors ``get_pm_loop_tick_seconds``
    + ``get_oauth_usage_snapshot`` precedents).

    Permission: PM-only at handler layer. Mike's interactive PM
    session governed by client-side defaults; doesn't run the
    compaction lifecycle.
    """

    @mcp.tool(
        name="write_pm_handoff",
        description=(
            "PM only. Write a compaction handoff doc to the configured "
            "handoff directory. Filename is auto-generated as a "
            "Windows-safe ISO-8601 UTC timestamp; PM authors content "
            "with required-section markdown convention per pm.yaml. "
            "Audits pm.compaction.handoff_written. Called at "
            "conserve-trigger BEFORE signal_compaction."
        ),
    )
    async def _write_pm_handoff(content: str) -> WritePmHandoffOutput:
        agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store, "write_pm_handoff")
        caller_role = _resolve_caller_role_via_token()
        return await write_pm_handoff(
            storage=storage,
            get_handoff_dir=get_pm_handoff_dir,
            request=WritePmHandoffInput(content=content),
            caller_agent_id=agent_id,
            caller_role=caller_role,
        )

    @mcp.tool(
        name="get_most_recent_handoff",
        description=(
            "PM only. Return the most-recent handoff's content + "
            "written-at, or both None on cold-start (no prior "
            "handoff). Called at PM startup to load resumed-context "
            "from the prior compaction. No audit emission "
            "(read-on-every-PM-startup volume would be operational "
            "noise without diagnostic value)."
        ),
    )
    async def _get_most_recent_handoff() -> GetMostRecentHandoffOutput:
        _resolve_agent_id()
        await _observe_caller(storage, token_store, "get_most_recent_handoff")
        caller_role = _resolve_caller_role_via_token()
        return await get_most_recent_handoff(
            get_handoff_dir=get_pm_handoff_dir,
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
    get_engineer_supervisor: Callable[[], LongRunningSupervisor | None] | None,
    get_pm_supervisor: Callable[[], LongRunningSupervisor | None] | None,
) -> None:
    """Register supervisor-lifecycle tools.

    Both supervisor closures are evaluated lazily at each tool
    invocation — the supervisor is constructed in ``main.py`` AFTER the
    MCP server starts (the supervisor's MCP calls back to the daemon
    need the server up), so a closure-captured reference would be stale
    at build_server time. The callable resolves at call time, returning
    the current supervisor or None if it hasn't been constructed yet.

    ``signal_compaction`` permission widened from engineer-only to
    engineer + pm at M5-continuous-pm-driver commit 8 per F9.2 Q3
    Reading A. The wrapper resolves the caller's role and dispatches
    to the matching supervisor (engineer → engineer-supervisor; pm →
    pm-supervisor). The handler is supervisor-instance-agnostic but
    role-aware for the audit-action namespace
    (``<role>.compaction_triggered`` per F9.2 Q3-A audit-namespace
    symmetry).

    Either or both supervisor closures may be None — wrappers
    surface a CONFLICT error when the caller's role-matching
    supervisor is absent.
    """

    @mcp.tool(
        name="signal_compaction",
        description=(
            "Engineer or PM. Signals an imminent clean exit for "
            "compaction. Sets the supervisor's compaction-pending flag; "
            "the watchdog observes the subsequent clean exit and respawns "
            "the supervisor fresh with status='compacted' (no respawn-cap "
            "increment). Audit action is role-prefixed: "
            "engineer.compaction_triggered or pm.compaction_triggered."
        ),
    )
    async def _signal_compaction(
        iteration_marker: str, proxy_value: float | None = None
    ) -> SignalCompactionOutput:
        agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store,"signal_compaction")
        caller_role = _resolve_caller_role_via_token()
        # Wrapper-layer supervisor resolution by caller role per F9.2
        # Q3 Reading A. Handler enforces the engineer/pm allowlist; this
        # check is operational (no supervisor for the caller's role
        # configured in this daemon) rather than permission-driven.
        if caller_role == "engineer":
            supervisor = (
                get_engineer_supervisor() if get_engineer_supervisor else None
            )
            missing_msg = (
                "no engineer supervisor is running; signal_compaction "
                "requires an active supervisor instance"
            )
        elif caller_role == "pm":
            supervisor = get_pm_supervisor() if get_pm_supervisor else None
            missing_msg = (
                "no PM supervisor is running; signal_compaction requires "
                "an active supervisor instance"
            )
        else:
            # Out-of-scope role; defer to handler's _require_role for
            # the canonical PERMISSION_DENIED error shape.
            supervisor = None
            missing_msg = (
                f"caller role {caller_role!r} cannot invoke signal_compaction"
            )
        if supervisor is None:
            raise ConflictError(missing_msg)
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


def _register_task_tools(
    mcp: FastMCP,
    registry: AgentRegistry,
    storage: Storage,
    token_store: TokenStore,
) -> None:
    """Register task-management tools on ``mcp``.

    M4d-task-management-surface ships six task-management MCP tools across
    commits 3-7; this commit (commit 3) registers ``submit_task``. Future
    commits 4-7 register ``claim_task``, ``update_task_status``,
    ``get_queue``, ``get_subtasks``, and ``set_task_priority`` by extending
    this function.

    Wrapper resolves caller identity via :func:`_resolve_agent_id` +
    :func:`_resolve_caller_role_via_token` + :func:`_resolve_caller_source`
    (the third is new at M4d commit 3 per Q9 (A) caller-distinction fold);
    builds :class:`PermissionResolver` from the registry's current
    snapshot; threads everything into the pure handler. Per-tool
    flat-allowlist gate is applied via the registered agent's
    ``mcp_tools_allowed`` list (caller's role must include
    ``"submit_task"``).
    """

    @mcp.tool(
        name="submit_task",
        description=(
            "Submit a new task to the queue. Hierarchy gate: caller may "
            "submit only for a role they manage (PM → Engineer; Engineer "
            "→ Implementer/Tester; Mike → PM). Self-submit allowed only "
            "for OAuth callers (Mike-via-OAuth). For sub-tasks "
            "(parent_task_id set), caller must be claimed_by the parent "
            "OR caller's role must equal the role that submitted the "
            "parent. task_type taxonomy describes work-shape (cognitive "
            "operation), not work-domain (product/area). Audited as "
            "task.submit."
        ),
    )
    async def _submit_task(
        agent_role: str,
        description: str,
        priority: int = 2,
        complexity: int = 3,
        parent_task_id: str | None = None,
        task_type: str | None = None,
    ) -> SubmitTaskOutput:
        agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store, "submit_task")
        caller_role = _resolve_caller_role_via_token()
        caller_source = _resolve_caller_source(token_store)
        resolver = PermissionResolver(registry.configs_by_role())
        resolver.require_tool(caller_role, "submit_task")  # type: ignore[arg-type]
        request = SubmitTaskInput(
            agent_role=agent_role,
            description=description,
            priority=priority,
            complexity=complexity,
            parent_task_id=parent_task_id,
            task_type=task_type,  # type: ignore[arg-type]  # Pydantic Literal validates at construction
        )
        return await submit_task(
            storage=storage,
            permissions=resolver,
            request=request,
            caller_agent_id=agent_id,
            caller_role=caller_role,
            caller_source=caller_source,
        )

    @mcp.tool(
        name="claim_task",
        description=(
            "Claim a pending task from the queue. Hierarchy gate: "
            "caller's role must equal task.agent_role exactly (sibling-"
            "role claims rejected even within the same dispatch chain). "
            "Atomic conditional UPDATE provides race protection — "
            "concurrent claims are mutually exclusive; failed claims "
            "raise CONFLICT (already claimed OR starting-from-non-"
            "pending state). Audited as task.claim."
        ),
    )
    async def _claim_task(task_id: str) -> ClaimTaskOutput:
        agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store, "claim_task")
        caller_role = _resolve_caller_role_via_token()
        resolver = PermissionResolver(registry.configs_by_role())
        resolver.require_tool(caller_role, "claim_task")  # type: ignore[arg-type]
        return await claim_task(
            storage=storage,
            request=ClaimTaskInput(task_id=task_id),
            caller_agent_id=agent_id,
            caller_role=caller_role,
        )

    @mcp.tool(
        name="update_task_status",
        description=(
            "Update a task's status. Hierarchy gate (two-clause OR): "
            "caller must be claimed_by the task OR (only for "
            "target='cancelled') caller's role must equal the role "
            "that submitted the task. State-machine validation per "
            "05-data-model.md tasks.status: invalid transitions "
            "(e.g., 'completed' -> 'claimed' or any transition out of "
            "a terminal state) raise CONFLICT. Atomic conditional "
            "UPDATE with race protection on starting state. Audited "
            "as task.status_change with from/to states."
        ),
    )
    async def _update_task_status(
        task_id: str,
        status: str,
        notes: str | None = None,
    ) -> UpdateTaskStatusOutput:
        agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store, "update_task_status")
        caller_role = _resolve_caller_role_via_token()
        resolver = PermissionResolver(registry.configs_by_role())
        resolver.require_tool(caller_role, "update_task_status")  # type: ignore[arg-type]
        return await update_task_status(
            storage=storage,
            request=UpdateTaskStatusInput(
                task_id=task_id,
                status=status,  # type: ignore[arg-type]  # Pydantic Literal validates at construction
                notes=notes,
            ),
            caller_agent_id=agent_id,
            caller_role=caller_role,
        )

    @mcp.tool(
        name="get_queue",
        description=(
            "Read-side queue query with hierarchy-scoped visibility "
            "filter. PM/Engineer see everything in their inclusive "
            "subtree; Implementer/Tester see only their own claimed "
            "tasks. Optional caller-supplied filters: status (list), "
            "agent_role (str), project_id (str). agent_role filter "
            "outside caller's hierarchy scope raises PERMISSION_DENIED. "
            "Pagination via limit/offset; total field reports count "
            "without limit/offset. NOT audited (read-only, high-"
            "frequency)."
        ),
    )
    async def _get_queue(
        status: list[str] | None = None,
        agent_role: str | None = None,
        project_id: str | None = None,
        limit: int = 50,
        offset: int = 0,
    ) -> GetQueueOutput:
        agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store, "get_queue")
        caller_role = _resolve_caller_role_via_token()
        resolver = PermissionResolver(registry.configs_by_role())
        resolver.require_tool(caller_role, "get_queue")  # type: ignore[arg-type]
        return await get_queue(
            storage=storage,
            permissions=resolver,
            request=GetQueueInput(
                status=status,  # type: ignore[arg-type]  # Pydantic Literal validates at construction
                agent_role=agent_role,
                project_id=project_id,
                limit=limit,
                offset=offset,
            ),
            caller_agent_id=agent_id,
            caller_role=caller_role,
        )

    @mcp.tool(
        name="get_subtasks",
        description=(
            "Read-side subtask hierarchy query. Hybrid authorization "
            "shape: raise-gate (caller must be in parent's chain — "
            "submitter / claimed_by / ancestor) + visibility-filter "
            "(subtask result set filtered by caller's hierarchy scope, "
            "same shape as get_queue). NOT audited (read-only, high-"
            "frequency)."
        ),
    )
    async def _get_subtasks(
        parent_task_id: str,
    ) -> GetSubtasksOutput:
        agent_id = _resolve_agent_id()
        await _observe_caller(storage, token_store, "get_subtasks")
        caller_role = _resolve_caller_role_via_token()
        resolver = PermissionResolver(registry.configs_by_role())
        resolver.require_tool(caller_role, "get_subtasks")  # type: ignore[arg-type]
        return await get_subtasks(
            storage=storage,
            permissions=resolver,
            request=GetSubtasksInput(parent_task_id=parent_task_id),
            caller_agent_id=agent_id,
            caller_role=caller_role,
        )


async def serve(
    settings: Settings,
    ready: asyncio.Event | None = None,
    registry: AgentRegistry | None = None,
    storage: Storage | None = None,
    get_engineer_supervisor: Callable[[], LongRunningSupervisor | None] | None = None,
    get_pm_supervisor: Callable[[], LongRunningSupervisor | None] | None = None,
    get_dispatcher: Callable[[], BridgeDispatcher | None] | None = None,
    get_oauth_usage_snapshot: SnapshotProvider | None = None,
    sampler: Sampler | None = None,
    get_pm_loop_tick_seconds: Callable[[], float] | None = None,
    get_pm_handoff_dir: Callable[[], Path] | None = None,
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
        get_pm_supervisor=get_pm_supervisor,
        get_dispatcher=get_dispatcher,
        get_oauth_usage_snapshot=get_oauth_usage_snapshot,
        sampler=sampler,
        get_pm_loop_tick_seconds=get_pm_loop_tick_seconds,
        get_pm_handoff_dir=get_pm_handoff_dir,
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
