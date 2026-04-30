"""Unit tests for agents.config — YAML loader + pydantic validation."""

from __future__ import annotations

from pathlib import Path

import pytest

from meridian_orchestrator.agents.config import (
    AgentConfig,
    AgentConfigError,
    load_agent_config,
    load_all_agent_configs,
)

REPO_CONFIG_DIR = (
    Path(__file__).resolve().parents[3] / "config" / "agents"
)


def test_load_engineer_yaml_from_repo() -> None:
    """The repo-controlled engineer.yaml validates and parses."""
    cfg = load_agent_config(REPO_CONFIG_DIR / "engineer.yaml")
    assert cfg.role == "engineer"
    assert cfg.display_name == "Engineer"
    assert cfg.reports_to == "pm"
    assert cfg.manages == ["implementer", "tester"]
    assert cfg.model_tier_default == "sonnet"
    assert "dispatch_implementer" in cfg.mcp_tools_allowed
    assert "/product/**" in cfg.knowledge_paths_readable
    assert "/product/substrate/work-logs/**" in cfg.knowledge_paths_writable
    assert cfg.system_prompt.startswith("You are Engineer at Meridian AI.")


def test_load_pm_yaml_from_repo() -> None:
    cfg = load_agent_config(REPO_CONFIG_DIR / "pm.yaml")
    assert cfg.role == "pm"
    assert cfg.reports_to is None
    assert cfg.manages == ["engineer"]
    assert cfg.model_tier_default == "opus"
    assert "notify_human" in cfg.mcp_tools_allowed


def test_load_implementer_yaml_from_repo() -> None:
    cfg = load_agent_config(REPO_CONFIG_DIR / "implementer.yaml")
    assert cfg.role == "implementer"
    assert cfg.reports_to == "engineer"
    assert "complete_dispatch" in cfg.mcp_tools_allowed
    assert cfg.knowledge_paths_writable == ["/product/substrate/work-logs/**"]


def test_load_tester_yaml_from_repo() -> None:
    cfg = load_agent_config(REPO_CONFIG_DIR / "tester.yaml")
    assert cfg.role == "tester"
    assert cfg.knowledge_paths_writable == []


def test_load_all_agent_configs(tmp_path: Path) -> None:
    """All four roles round-trip from a temp dir populated with the repo configs."""
    for role in ("pm", "engineer", "implementer", "tester"):
        (tmp_path / f"{role}.yaml").write_text(
            (REPO_CONFIG_DIR / f"{role}.yaml").read_text(encoding="utf-8"),
            encoding="utf-8",
        )
    configs = load_all_agent_configs(tmp_path)
    assert set(configs.keys()) == {"pm", "engineer", "implementer", "tester"}
    assert configs["pm"].manages == ["engineer"]
    assert configs["engineer"].reports_to == "pm"
    assert configs["implementer"].reports_to == "engineer"
    assert configs["tester"].reports_to == "engineer"


def test_load_all_ignores_unknown_stems(tmp_path: Path) -> None:
    """Files like ``notes.yaml`` are skipped without error."""
    (tmp_path / "engineer.yaml").write_text(
        (REPO_CONFIG_DIR / "engineer.yaml").read_text(encoding="utf-8"),
        encoding="utf-8",
    )
    (tmp_path / "notes.yaml").write_text("not: a real config", encoding="utf-8")
    configs = load_all_agent_configs(tmp_path)
    assert set(configs.keys()) == {"engineer"}


def test_role_mismatch_with_filename_raises(tmp_path: Path) -> None:
    """A file named pm.yaml whose internal role is engineer fails fast."""
    engineer_yaml = (REPO_CONFIG_DIR / "engineer.yaml").read_text(encoding="utf-8")
    (tmp_path / "pm.yaml").write_text(engineer_yaml, encoding="utf-8")
    with pytest.raises(AgentConfigError, match="role mismatch"):
        load_all_agent_configs(tmp_path)


def test_extra_fields_rejected(tmp_path: Path) -> None:
    """The pydantic model rejects unknown fields (extra='forbid')."""
    bad = """
role: engineer
display_name: Engineer
system_prompt: x
unexpected_field: 1
"""
    p = tmp_path / "engineer.yaml"
    p.write_text(bad, encoding="utf-8")
    with pytest.raises(AgentConfigError):
        load_agent_config(p)


def test_missing_required_field_rejected(tmp_path: Path) -> None:
    bad = """
role: engineer
# display_name missing
system_prompt: x
"""
    p = tmp_path / "engineer.yaml"
    p.write_text(bad, encoding="utf-8")
    with pytest.raises(AgentConfigError):
        load_agent_config(p)


def test_yaml_parse_error_wrapped(tmp_path: Path) -> None:
    p = tmp_path / "engineer.yaml"
    p.write_text(":\n  - this isn't [valid: YAML\n", encoding="utf-8")
    with pytest.raises(AgentConfigError, match="YAML parse"):
        load_agent_config(p)


def test_missing_file_raises() -> None:
    with pytest.raises(AgentConfigError, match="not found"):
        load_agent_config(Path("/nonexistent/path/agent.yaml"))


def test_load_all_returns_empty_for_missing_dir(tmp_path: Path) -> None:
    missing = tmp_path / "does-not-exist"
    assert load_all_agent_configs(missing) == {}


def test_agent_config_frozen() -> None:
    cfg = AgentConfig(
        role="engineer",
        display_name="x",
        system_prompt="x",
    )
    with pytest.raises(Exception):  # noqa: B017 - pydantic raises ValidationError on frozen
        cfg.role = "pm"  # type: ignore[misc]
