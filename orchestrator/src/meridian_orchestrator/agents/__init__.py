"""Agent configuration, registry, and permission framework.

Per ``docs/kickoff/07-agent-configs-phase-one.md``: each agent has a YAML
config under ``<MERIDIAN_ORCH_DATA_DIR>/config/agents/<role>.yaml``. The
:class:`registry.AgentRegistry` loads them on startup, watches for changes via
``watchfiles`` for hot-reload, and persists agent rows to the database
reusing M2 keyring token UUIDs as primary keys (see the M2/M3 reconciliation
contract in operator memory).

The :class:`permissions.PermissionResolver` enforces per-tool allowlists and
hierarchy invariants on every MCP request that requires permission checks.
"""

from __future__ import annotations

from meridian_orchestrator.agents.config import (
    AgentConfig,
    AgentConfigError,
    load_agent_config,
    load_all_agent_configs,
)
from meridian_orchestrator.agents.permissions import PermissionResolver
from meridian_orchestrator.agents.registry import AgentRegistry, RegisteredAgent

__all__ = [
    "AgentConfig",
    "AgentConfigError",
    "AgentRegistry",
    "PermissionResolver",
    "RegisteredAgent",
    "load_agent_config",
    "load_all_agent_configs",
]
