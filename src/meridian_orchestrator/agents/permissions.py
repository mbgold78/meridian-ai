"""Permission resolver: per-tool allowlist + hierarchy enforcement.

The MCP server resolves a Bearer token to an ``agent_id`` (M2's auth chain),
looks up the agent's role + config in the registry, and calls
:meth:`PermissionResolver.check_tool` before dispatching the tool. Denied
tools raise :class:`errors.PermissionError`, which the server maps to a
``PERMISSION_DENIED`` MCP error code with a generic message — no permission
graph leakage per ``docs/kickoff/06-mcp-tools-phase-one.md``.

Hierarchy enforcement (sub-task scoping, descendant-only audit reads, etc.)
is layered on top via :meth:`is_descendant` and :meth:`is_ancestor`. Tool
implementations call those helpers when their permission check goes beyond a
flat allowlist (e.g., ``submit_task`` requires the target ``agent_role`` to
be a direct manage-relationship descendant of the caller).
"""

from __future__ import annotations

from dataclasses import dataclass

from meridian_orchestrator.agents.config import AgentConfig, AgentRole
from meridian_orchestrator.errors import PermissionError as OrchestratorPermissionError


@dataclass(frozen=True, slots=True)
class PermissionDecision:
    """Outcome of a permission check.

    ``allowed`` is the boolean answer; ``reason`` is logged but never returned
    to the caller — denial messages to MCP clients are deliberately generic.
    """

    allowed: bool
    reason: str


class PermissionResolver:
    """Resolves permission questions against a snapshot of agent configs.

    The resolver is constructed with a role-keyed dict of
    :class:`AgentConfig` instances (typically supplied by the registry's
    current-snapshot accessor). It does not hold a reference to the registry,
    so permission checks are cheap and don't race with hot reloads — each
    request gets a fresh resolver bound to the registry's current snapshot.
    """

    def __init__(self, agents: dict[AgentRole, AgentConfig]) -> None:
        self._agents = agents

    def check_tool(
        self, caller_role: AgentRole, tool_name: str
    ) -> PermissionDecision:
        """Per-tool allowlist check. Returns ``PermissionDecision``.

        Tool implementations layer their own hierarchy checks on top via
        :meth:`is_descendant` / :meth:`is_ancestor`. This method only enforces
        the flat allowlist; hierarchy is the tool's responsibility.
        """
        agent = self._agents.get(caller_role)
        if agent is None:
            return PermissionDecision(
                allowed=False, reason=f"unknown caller role {caller_role!r}"
            )
        if tool_name not in agent.mcp_tools_allowed:
            return PermissionDecision(
                allowed=False,
                reason=f"tool {tool_name!r} not in {caller_role}.mcp_tools_allowed",
            )
        return PermissionDecision(allowed=True, reason="allowed")

    def require_tool(self, caller_role: AgentRole, tool_name: str) -> None:
        """Raise :class:`OrchestratorPermissionError` if the tool isn't allowed.

        The exception's message is intentionally minimal — the error code
        (``PERMISSION_DENIED``) is what the caller sees, not the resolver's
        internal reason.
        """
        decision = self.check_tool(caller_role, tool_name)
        if not decision.allowed:
            raise OrchestratorPermissionError(
                f"permission denied for tool {tool_name!r}"
            )

    def is_descendant(
        self, caller_role: AgentRole, target_role: AgentRole
    ) -> bool:
        """True if ``target_role`` is reachable downward through the
        ``manages`` graph from ``caller_role`` (transitively)."""
        if caller_role == target_role:
            return True
        seen: set[AgentRole] = {caller_role}
        frontier: list[AgentRole] = list(self._direct_manages(caller_role))
        while frontier:
            current = frontier.pop()
            if current in seen:
                continue
            seen.add(current)
            if current == target_role:
                return True
            frontier.extend(self._direct_manages(current))
        return False

    def manages_directly(
        self, caller_role: AgentRole, target_role: AgentRole
    ) -> bool:
        """True if ``target_role`` is in ``caller_role``'s direct
        ``manages`` list — strictly one hop, NOT transitive.

        Distinct from :meth:`is_descendant` which walks the full subtree.
        Used by ``submit_task``'s hierarchy gate per
        ``06-mcp-tools-phase-one.md:44`` ("caller may submit a task only
        for a role they manage (direct report). [...] PM **cannot**
        submit a task with `agent_role='implementer'` — that's a
        hierarchy bypass."). Mike→PM, PM→Engineer, Engineer→Implementer,
        Engineer→Tester are direct edges; transitive reads (e.g.,
        PM→Implementer) are explicit hierarchy bypasses by spec.

        Self-target returns False (a role does not directly manage
        itself); the Q9 self-submit gate is layered separately on top of
        this primitive in ``tools.tasks._check_can_submit_task``.
        """
        if caller_role == target_role:
            return False
        return target_role in self._direct_manages(caller_role)

    def is_ancestor(
        self, caller_role: AgentRole, target_role: AgentRole
    ) -> bool:
        """True if ``caller_role`` is reachable downward through ``manages``
        from ``target_role`` (i.e., target manages caller transitively)."""
        return self.is_descendant(target_role, caller_role)

    def is_in_subtree(
        self, caller_role: AgentRole, target_role: AgentRole
    ) -> bool:
        """True if ``target_role`` is in the caller's subtree (caller is
        an ancestor of target, inclusive)."""
        return self.is_descendant(caller_role, target_role)

    def _direct_manages(self, role: AgentRole) -> list[AgentRole]:
        agent = self._agents.get(role)
        if agent is None:
            return []
        return list(agent.manages)
