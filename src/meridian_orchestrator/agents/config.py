"""Agent YAML config loading + pydantic validation.

Schema lives in :class:`AgentConfig` below. The YAML structure mirrors the
canonical files under ``config/agents/`` in the repo (and the runtime location
``<data_dir>/config/agents/``). Fields:

- ``id`` — agent UUID (set at registry seed time from keyring token UUID per
  the M2/M3 reconciliation contract; ``None`` in the on-disk YAML)
- ``role`` — one of ``pm``, ``engineer``, ``implementer``, ``tester``
- ``display_name`` — human-readable label
- ``reports_to`` — role of the parent agent (``null`` for PM, the root)
- ``manages`` — list of roles this agent dispatches to
- ``model_tier_default`` — routing baseline for ``check_budget_for_task``
- ``system_prompt`` — the agent's full instruction text
- ``mcp_tools_allowed`` — explicit allowlist (no wildcards; exact tool names)
- ``knowledge_paths_readable`` / ``knowledge_paths_writable`` — glob patterns

The on-disk YAML uses role names for ``reports_to`` / ``manages`` because the
file is human-edited and stable across reseeding; the registry resolves these
to UUIDs after seeding all agents.
"""

from __future__ import annotations

from pathlib import Path
from typing import Literal

import yaml
from pydantic import BaseModel, ConfigDict, Field, ValidationError


class AgentConfigError(ValueError):
    """Raised when an agent YAML file is malformed or fails validation."""


AgentRole = Literal["pm", "engineer", "implementer", "tester"]
ModelTier = Literal["haiku", "sonnet", "opus"]


class AgentConfig(BaseModel):
    """Validated agent configuration loaded from a single YAML file.

    The ``id`` and resolved ``reports_to_id`` / ``manages_ids`` UUIDs are set
    by the registry after all configs are loaded; the YAML on disk only
    carries role names. ``system_prompt`` is preserved verbatim including
    leading/trailing whitespace within the YAML literal block.
    """

    model_config = ConfigDict(extra="forbid", frozen=True)

    id: str | None = Field(default=None)
    role: AgentRole
    display_name: str
    reports_to: AgentRole | None = Field(default=None)
    manages: list[AgentRole] = Field(default_factory=list)
    model_tier_default: ModelTier = Field(default="sonnet")
    system_prompt: str
    mcp_tools_allowed: list[str] = Field(default_factory=list)
    knowledge_paths_readable: list[str] = Field(default_factory=list)
    knowledge_paths_writable: list[str] = Field(default_factory=list)


def load_agent_config(path: Path) -> AgentConfig:
    """Load and validate a single agent YAML file.

    Raises ``AgentConfigError`` with a path-prefixed message on YAML parse
    failure or pydantic validation failure.
    """
    if not path.exists():
        raise AgentConfigError(f"agent config file not found: {path}")
    try:
        raw = yaml.safe_load(path.read_text(encoding="utf-8"))
    except yaml.YAMLError as exc:
        raise AgentConfigError(f"YAML parse error in {path}: {exc}") from exc
    if not isinstance(raw, dict):
        raise AgentConfigError(
            f"agent config {path} must be a YAML mapping, got {type(raw).__name__}"
        )
    try:
        return AgentConfig.model_validate(raw)
    except ValidationError as exc:
        raise AgentConfigError(f"validation error in {path}: {exc}") from exc


def load_all_agent_configs(agents_dir: Path) -> dict[AgentRole, AgentConfig]:
    """Load every ``<role>.yaml`` under ``agents_dir`` into a role-keyed dict.

    Files whose stem does not match a known role are ignored (with no error)
    so operators can drop notes / experimental configs alongside without
    breaking the load. Files whose internal ``role`` field disagrees with
    their filename stem raise ``AgentConfigError`` — that's a real mismatch.
    """
    if not agents_dir.is_dir():
        return {}
    configs: dict[AgentRole, AgentConfig] = {}
    for path in sorted(agents_dir.glob("*.yaml")):
        stem = path.stem
        if stem not in {"pm", "engineer", "implementer", "tester"}:
            continue
        cfg = load_agent_config(path)
        if cfg.role != stem:
            raise AgentConfigError(
                f"role mismatch in {path}: filename stem={stem!r} but "
                f"file declares role={cfg.role!r}"
            )
        configs[cfg.role] = cfg
    return configs
