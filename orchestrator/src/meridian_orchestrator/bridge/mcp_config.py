"""Per-dispatch / per-spawn MCP server configuration JSON + allowed-tools
resolver for subprocess launchers.

Two launcher-composition concerns live here, both of which the dispatcher
(:mod:`bridge.dispatcher`) and the supervisors
(:mod:`bridge.supervisor_base`) need:

1. The per-dispatch JSON config (``<dispatches_root>/<dispatch_id>/
   mcp_config.json``) containing the orchestrator HTTP MCP entry (with
   ``Authorization: Bearer <token>`` header) plus, for tester dispatches
   only, the Playwright stdio MCP entry. The launcher emits
   ``--mcp-config <path> --strict-mcp-config`` so the subprocess uses
   ONLY this config — hermetic; user-scope and local-scope MCP
   registrations cannot leak in.

2. The ``--allowedTools`` argv list resolver (built-in Claude Code tools
   + prefixed orchestrator MCP tools), keyed off the registry's
   ``AgentConfig`` for the role. Combines yaml ``allowed_tools`` (built-in
   + non-orchestrator MCP) with prefixed yaml ``mcp_tools_allowed``
   (orchestrator MCP) at launcher composition time — single-source-of-
   truth (no yaml duplication of the orchestrator tool set).

Both concerns are shared between dispatcher (per-dispatch lifecycle) and
supervisor (per-spawn lifecycle); the helpers here are lifecycle-agnostic.

Why this layer exists: standard ``claude --print`` does NOT translate
``MERIDIAN_ORCHESTRATOR_TOKEN`` (set by :mod:`bridge.launch_env`) into
``Authorization: Bearer`` headers for MCP requests, so dispatched
subprocesses cannot otherwise authenticate against the orchestrator's
FastMCP server (which raises ``AuthError`` on every unauthenticated
request via the MCP SDK auth_context middleware). Plus a parallel
visibility gap: local-scope MCP registrations are not visible to
subprocess from non-home cwd. The per-dispatch JSON + strict mode
resolves both surfaces under one mechanism.

Token lifecycle: minted by ``BridgeDispatcher.dispatch`` at dispatch
start; revoked at terminal state in the same method's finally clause.
Each ``TokenStore.issue`` call generates a fresh ``token_id`` + secret;
the per-dispatch token literal flows through ``DispatchLaunchContext.token``
into the ``Authorization: Bearer`` header here. After revocation
``auth.TokenStore.lookup`` returns ``None`` for the record, so the
retained config file becomes operationally inert — caller treats the
file as fire-and-forget per :mod:`bridge.stderr_drain` convention.

Module pattern mirrors :mod:`bridge.stderr_drain`: per-dispatch path
helper that creates the parent directory + a write helper that
materializes the file. Difference: stderr.log is best-effort diagnostic
(failure → ``stderr=DEVNULL`` fallback); mcp_config.json is load-bearing
(subprocess cannot authenticate without it). Path-creation failure
surfaces as ``None`` return; caller (dispatcher) treats None as "omit
``--mcp-config`` flag entirely" — degraded path then fails at the first
orchestrator MCP call with ``AuthError`` rather than failing the
dispatch lifecycle at write time. Production paths always pass populated
``daemon_url`` + ``dispatches_root``; test fixtures with custom
``subprocess_launcher`` injections bypass this module entirely.

JSON schema:
``{"mcpServers": {"<name>": {"type": "stdio" | "http", ...}}}``. HTTP
entries take ``url`` + ``headers``; stdio entries take ``command`` +
``args``. The orchestrator's FastMCP server name
(``meridian-orchestrator``, hyphen preserved) is the JSON key for the
orchestrator entry; this must match the ``name=`` argument passed to
FastMCP at ``server.py`` construction time.

Symmetric wiring for long-lived per-spawn supervisor tokens at
:mod:`bridge.supervisor_base` is tracked in ``BACKLOG.md`` § Future
architectural work entry "Per-spawn supervisor auth wiring at
supervisor_base.py launcher".
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import TYPE_CHECKING, Literal

import structlog

if TYPE_CHECKING:
    from meridian_orchestrator.agents.registry import AgentRegistry

logger = structlog.get_logger(__name__)

# Roles whose subprocess launchers consume the helpers in this module.
# Dispatcher launches implementer + tester; supervisor launches pm +
# engineer. Tester is the only role that gets the Playwright stdio MCP
# entry in :func:`write_mcp_config`; pm + engineer + implementer omit it.
SubprocessRole = Literal["implementer", "tester", "pm", "engineer"]

# Canonical Claude Code MCP tool name prefix for the orchestrator's
# FastMCP server (registered as ``name="meridian-orchestrator"`` at
# ``server.py:368``). Hyphen preserved per the canonical Claude Code
# MCP tool naming convention (verified empirically 2026-05-08:
# subprocess permission-denial messages name tools as
# ``mcp__meridian-orchestrator__list_agents``). Spaces and dots in
# server names normalize to underscore (e.g.,
# ``mcp__claude_ai_Gmail__<tool>`` from ``claude.ai Gmail``); hyphens
# are valid identifier chars and preserved verbatim.
_ORCHESTRATOR_MCP_TOOL_PREFIX = "mcp__meridian-orchestrator__"

_PLAYWRIGHT_MCP_ENTRY: dict[str, object] = {
    "type": "stdio",
    "command": "npx",
    "args": ["@playwright/mcp@latest"],
}


def resolve_allowed_tools_for_role(
    registry: AgentRegistry | None, role: str
) -> list[str]:
    """Resolve a role's ``claude --allowedTools`` argv list.

    Combines two yaml fields with single-source-of-truth at launcher
    composition time (no yaml duplication of the orchestrator tool set):

    - ``AgentConfig.allowed_tools`` — built-in Claude Code tools (Read,
      Edit, Write, Bash, Glob, Grep) + non-orchestrator MCP tools (e.g.,
      Playwright tools for tester) with already-prefixed shape.
    - ``AgentConfig.mcp_tools_allowed`` — orchestrator MCP tools (bare
      names like ``update_task_status``, ``complete_dispatch``); this
      helper prefixes each with ``mcp__meridian-orchestrator__``.

    Returns ``[]`` when ``registry`` is None or the role has no
    registered config — empty result causes the launcher (dispatcher
    or supervisor) to omit ``--allowedTools`` entirely, preserving the
    test-fixture argv shape per ``feedback_optional_dependency_in_helpers``
    precedent. Production paths always pass a populated registry.

    Output ordering: built-in/external tools first (yaml literal order),
    orchestrator tools second (yaml literal order with prefix). Stable
    across runs so test fixtures asserting on the exact list don't
    flap.
    """
    if registry is None:
        return []
    agent = registry.get(role)  # type: ignore[arg-type]
    if agent is None:
        return []
    builtin_and_external = list(agent.config.allowed_tools)
    orchestrator_prefixed = [
        _ORCHESTRATOR_MCP_TOOL_PREFIX + tool
        for tool in agent.config.mcp_tools_allowed
    ]
    return builtin_and_external + orchestrator_prefixed


def mcp_config_path(dispatches_root: Path, dispatch_id: str) -> Path | None:
    """Compute and create the per-dispatch mcp_config.json file path.

    Returns the path on success; returns ``None`` and emits a structlog
    WARNING on :class:`OSError` (disk full, permission denied,
    parent-not-writable, etc.).

    The ``None`` return path: caller (dispatcher launcher) treats None as
    "omit ``--mcp-config`` flag entirely" — preserving the existing
    test-fixture argv shape per ``feedback_optional_dependency_in_helpers``
    precedent. Production paths always pass a real ``dispatches_root`` so
    None is operationally rare; the operational consequence of None is
    "subprocess cannot authenticate against orchestrator MCP", which
    surfaces as ``AuthError`` from FastMCP at the first orchestrator-MCP
    tool call.

    Directory layout ``<dispatches_root>/<dispatch_id>/`` is shared with
    :func:`bridge.stderr_drain.stderr_log_path` — both helpers run
    idempotent ``mkdir(parents=True, exist_ok=True)``; whichever runs
    first creates the directory; the second no-ops.
    """
    parent = dispatches_root / dispatch_id
    try:
        parent.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        logger.warning(
            "dispatch.mcp_config_dir_create_failed",
            dispatch_id=dispatch_id,
            path=str(parent),
            error=str(exc),
        )
        return None
    return parent / "mcp_config.json"


def write_mcp_config(
    path: Path,
    *,
    daemon_url: str,
    token: str,
    role: SubprocessRole,
) -> bool:
    """Write the per-dispatch / per-spawn mcp_config.json file.

    Returns ``True`` on success; ``False`` and emits a structlog WARNING
    on :class:`OSError`. ``False`` matches the :func:`mcp_config_path`
    None-return semantic at the caller level — launcher (dispatcher or
    supervisor) treats either failure as "omit ``--mcp-config`` flag" so
    the lifecycle proceeds with the same auth-failure-at-MCP-call-time
    consequence rather than failing outright.

    JSON shape::

        {
            "mcpServers": {
                "meridian-orchestrator": {
                    "type": "http",
                    "url": "<daemon_url>",
                    "headers": {"Authorization": "Bearer <token>"}
                },
                # Tester role only:
                "playwright": {
                    "type": "stdio",
                    "command": "npx",
                    "args": ["@playwright/mcp@latest"]
                }
            }
        }

    Only ``role == "tester"`` includes the Playwright entry. Implementer,
    PM, and Engineer omit it — Tester does browser-driven verification,
    the others don't.

    JSON-key naming preserves hyphens in server names: the orchestrator's
    canonical FastMCP server name is ``meridian-orchestrator`` (hyphen,
    not underscore). Claude Code's MCP tool prefix transformation
    preserves hyphens in identifier-context server names while
    normalizing spaces and dots to underscore (e.g., ``claude.ai Gmail``
    becomes ``mcp__claude_ai_Gmail__<tool>``). The JSON-key here MUST
    match the ``name=`` argument passed to FastMCP at ``server.py``
    construction time.
    """
    servers: dict[str, dict[str, object]] = {
        "meridian-orchestrator": {
            "type": "http",
            "url": daemon_url,
            "headers": {"Authorization": f"Bearer {token}"},
        },
    }
    if role == "tester":
        servers["playwright"] = dict(_PLAYWRIGHT_MCP_ENTRY)

    payload = {"mcpServers": servers}

    try:
        path.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    except OSError as exc:
        logger.warning(
            "dispatch.mcp_config_write_failed",
            path=str(path),
            error=str(exc),
        )
        return False
    return True


__all__ = [
    "SubprocessRole",
    "mcp_config_path",
    "resolve_allowed_tools_for_role",
    "write_mcp_config",
]
