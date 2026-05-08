"""Agent routing & discovery tools: list_agents, get_agent_config, get_org_chart.

Per ``docs/kickoff/06-mcp-tools-phase-one.md`` § Routing & discovery. All
three tools are read-only and registered for every agent role; output
shapes match the kickoff schemas exactly. None are audited (high-frequency
read paths).
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field

from meridian_orchestrator.agents.registry import AgentRegistry

# ----- Pydantic schemas ----------------------------------------------------


class AgentSummary(BaseModel):
    id: str
    role: str
    reports_to: str | None
    status: str = Field(default="active")


class ListAgentsInput(BaseModel):
    role: str | None = None
    status: str | None = None


class ListAgentsOutput(BaseModel):
    agents: list[AgentSummary]


class GetAgentConfigInput(BaseModel):
    agent_id: str


class GetAgentConfigOutput(BaseModel):
    role: str
    display_name: str
    reports_to: str | None
    manages: list[str]
    mcp_tools_allowed: list[str]
    knowledge_paths_readable: list[str]
    knowledge_paths_writable: list[str]


class GetOrgChartInput(BaseModel):
    pass


class GetOrgChartOutput(BaseModel):
    chart: dict[str, Any]


# ----- Handlers -----------------------------------------------------------


def list_agents(
    registry: AgentRegistry, request: ListAgentsInput
) -> ListAgentsOutput:
    """Return all registered agents, optionally filtered by role/status.

    Phase 1 has no inactive agents (registry-loaded agents are always
    ``active``); the ``status`` filter is a forward compatibility hook.
    """
    agents: list[AgentSummary] = []
    for ra in registry.all_agents():
        if request.role is not None and ra.config.role != request.role:
            continue
        if request.status is not None and request.status != "active":
            continue
        reports_to_id: str | None = None
        if ra.config.reports_to is not None:
            parent = registry.get(ra.config.reports_to)
            reports_to_id = parent.agent_id if parent is not None else None
        agents.append(
            AgentSummary(
                id=ra.agent_id,
                role=ra.config.role,
                reports_to=reports_to_id,
                status="active",
            )
        )
    return ListAgentsOutput(agents=agents)


def get_agent_config(
    registry: AgentRegistry, request: GetAgentConfigInput
) -> GetAgentConfigOutput | None:
    """Return public config fields for ``agent_id``. Returns ``None`` when
    the id is unknown — the server maps this to ``NOT_FOUND``.

    The ``system_prompt`` is deliberately not exposed.
    """
    target = registry.get_by_agent_id(request.agent_id)
    if target is None:
        return None
    cfg = target.config
    reports_to_id: str | None = None
    if cfg.reports_to is not None:
        parent = registry.get(cfg.reports_to)
        reports_to_id = parent.agent_id if parent is not None else None
    return GetAgentConfigOutput(
        role=cfg.role,
        display_name=cfg.display_name,
        reports_to=reports_to_id,
        manages=list(cfg.manages),
        mcp_tools_allowed=list(cfg.mcp_tools_allowed),
        knowledge_paths_readable=list(cfg.knowledge_paths_readable),
        knowledge_paths_writable=list(cfg.knowledge_paths_writable),
    )


def get_org_chart(
    registry: AgentRegistry,
    _request: GetOrgChartInput,
) -> GetOrgChartOutput:
    """Return the management hierarchy as a nested tree rooted at PM.

    Output shape per kickoff ``GetOrgChartOutput.chart``: each node is
    ``{"role": str, "agent_id": str, "manages": [<subtrees>]}``. Roots that
    aren't reachable from PM (orphan agents) are emitted as a flat sibling
    list under a synthetic ``"orphans"`` key alongside the PM root, so the
    output remains a valid JSON object even with broken hierarchies.
    """
    by_role: dict[str, Any] = {ra.config.role: ra for ra in registry.all_agents()}

    def build_subtree(role_str: str) -> dict[str, Any]:
        ra = by_role.get(role_str)
        if ra is None:
            return {"role": role_str, "agent_id": None, "manages": []}
        return {
            "role": ra.config.role,
            "agent_id": ra.agent_id,
            "manages": [build_subtree(r) for r in ra.config.manages],
        }

    pm = by_role.get("pm")
    if pm is None:
        return GetOrgChartOutput(chart={"role": None, "agent_id": None, "manages": []})

    chart = build_subtree("pm")

    reachable: set[str] = set()

    def collect(node: dict[str, Any]) -> None:
        reachable.add(node["role"])
        for child in node["manages"]:
            collect(child)

    collect(chart)

    orphans = [
        {"role": ra.config.role, "agent_id": ra.agent_id, "manages": []}
        for ra in registry.all_agents()
        if ra.config.role not in reachable
    ]
    if orphans:
        return GetOrgChartOutput(chart={"root": chart, "orphans": orphans})
    return GetOrgChartOutput(chart=chart)
