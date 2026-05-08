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
    assert cfg.effort == "xhigh"
    assert "dispatch_implementer" in cfg.mcp_tools_allowed
    assert "/product/**" in cfg.knowledge_paths_readable
    assert "/product/substrate/work-logs/**" in cfg.knowledge_paths_writable
    assert cfg.system_prompt.startswith("You are Engineer at Meridian AI.")


def test_engineer_yaml_system_prompt_contains_compaction_trigger() -> None:
    """M5-flag-flips commit 5 contract test: engineer.yaml's system_prompt
    MUST contain the ``## Compaction trigger`` subsection wiring
    signal_compaction at subscription_5h utilization >= 80% with
    snapshot freshness gating.

    Methodology-as-moat: encoding the amendment as a contract test
    surfaces accidental yaml deletion in CI rather than allowing
    silent drift between the daemon's flag-flip behavior and the
    Engineer subprocess's prompt-driven trigger. Asserts on
    load-bearing semantic strings (subsection header, MCP tool name,
    bucket name, threshold value, staleness gate, exit-clean
    semantic) rather than full-text equality so minor wording
    refinements don't break the test while structural deletions do.
    """
    cfg = load_agent_config(REPO_CONFIG_DIR / "engineer.yaml")
    prompt = cfg.system_prompt

    # Subsection header
    assert "## Compaction trigger" in prompt
    # MCP tool name (signal_compaction handler in tools/supervisor.py)
    assert "signal_compaction" in prompt
    # Canonical bucket name per project_subscription_first_architecture
    assert "subscription_5h" in prompt
    # Threshold per spec (08-implementation-plan.md:301;
    # project_subscription_first_architecture B-2 disposition)
    assert "utilization >= 80" in prompt
    # Staleness gate per V2.1 disposition (snapshot freshness threshold)
    assert "snapshot_age_seconds <= 120" in prompt
    # Exit-clean semantic from signal_compaction's next_action return
    assert "exit_clean" in prompt


def test_load_pm_yaml_from_repo() -> None:
    cfg = load_agent_config(REPO_CONFIG_DIR / "pm.yaml")
    assert cfg.role == "pm"
    assert cfg.reports_to is None
    assert cfg.manages == ["engineer"]
    assert cfg.model_tier_default == "opus"
    assert cfg.effort == "xhigh"
    assert "notify_human" in cfg.mcp_tools_allowed


def test_pm_yaml_system_prompt_contains_compaction_lifecycle() -> None:
    """M5-flag-flips commit 6 contract test: pm.yaml's system_prompt
    MUST contain the ``## Compaction lifecycle`` subsection wiring
    ``signal_compaction`` at the ``subscription_5h`` conserve threshold
    with ``write_pm_handoff`` sequencing.

    Methodology-as-moat: encoding the existing wiring as a contract
    test surfaces accidental yaml deletion in CI rather than allowing
    silent drift between the daemon's flag-flip behavior and the PM
    subprocess's prompt-driven trigger. The PM compaction wiring
    shipped at M5-continuous-pm-driver commits 7+8 (predates this
    flag flip); the test locks the load-bearing semantic strings
    against accidental deletion at flag-flip time and beyond.

    PM-specific divergence from engineer.yaml's commit-5 amendment
    (intentional architecture, NOT drift to fix): PM uses
    ``pace_recommendation == 'conserve'`` indirection (fires at the
    same threshold as Engineer's literal ``utilization >= 80`` via
    ``tools/budget.py:_compute_pace_recommendation``'s
    ``remaining < 20%`` rule) + has a companion ``write_pm_handoff``
    call before signal_compaction (Engineer has no handoff doc) +
    anti-flapping discipline absent from engineer.yaml (PM has
    handoff-doc-staleness check; Engineer doesn't need one).

    Asserts on load-bearing semantic strings (subsection header, MCP
    tool names, bucket name, trigger condition, exit-clean semantic)
    rather than full-text equality so minor wording refinements
    don't break the test while structural deletions do.
    """
    cfg = load_agent_config(REPO_CONFIG_DIR / "pm.yaml")
    prompt = cfg.system_prompt

    # Subsection header (PM-specific: "lifecycle" not "trigger")
    assert "## Compaction lifecycle" in prompt
    # MCP tool name (signal_compaction handler in tools/supervisor.py)
    assert "signal_compaction" in prompt
    # Canonical bucket name per project_subscription_first_architecture
    assert "subscription_5h" in prompt
    # PM-specific trigger condition — pace_recommendation indirection
    # (fires at same threshold as Engineer's literal utilization >= 80
    # per _compute_pace_recommendation's remaining<20% rule)
    assert "pace_recommendation == 'conserve'" in prompt
    # PM-specific companion MCP tool — handoff doc write before
    # signal_compaction (architectural asymmetry from engineer.yaml)
    assert "write_pm_handoff" in prompt
    # Exit-clean semantic from signal_compaction's next_action return
    assert "Exit cleanly" in prompt


def test_load_implementer_yaml_from_repo() -> None:
    cfg = load_agent_config(REPO_CONFIG_DIR / "implementer.yaml")
    assert cfg.role == "implementer"
    assert cfg.reports_to == "engineer"
    assert cfg.effort == "xhigh"
    assert "complete_dispatch" in cfg.mcp_tools_allowed
    assert cfg.knowledge_paths_writable == ["/product/substrate/work-logs/**"]


def test_load_tester_yaml_from_repo() -> None:
    cfg = load_agent_config(REPO_CONFIG_DIR / "tester.yaml")
    assert cfg.role == "tester"
    assert cfg.effort == "high"
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
        # Required per F6.2 Q2 disposition (hard-fail surfacing config
        # drift). Future-Claude removing this as "unused" without
        # context would silently violate the discipline.
        effort="high",
    )
    with pytest.raises(Exception):  # noqa: B017 - pydantic raises ValidationError on frozen
        cfg.role = "pm"  # type: ignore[misc]


# ---------------------------------------------------------------------------
# Effort field — closed-enum sentinel + hard-fail-on-missing per F6.2
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "effort_value", ["low", "medium", "high", "xhigh", "max"]
)
def test_effort_literal_accepts_all_5_values(effort_value: str) -> None:
    """The Effort Literal accepts every value the Claude Code CLI's
    --effort flag accepts (verified via `claude --help` 2026-05-05:
    low, medium, high, xhigh, max). Closed-enum sentinel pattern
    continues per F2.5 graduation note. Failure here means the
    Literal drifted from the CLI surface.

    Pattern continuation: this is the daemon's defense against typos
    flowing to subprocess args at runtime. The Pydantic Literal
    validates at AgentConfig construction; the CLI's own validation
    is a second layer that catches anything that slips through (e.g.,
    direct `--effort` injection bypassing AgentConfig).
    """
    cfg = AgentConfig(
        role="engineer",
        display_name="x",
        system_prompt="x",
        effort=effort_value,  # type: ignore[arg-type]
    )
    assert cfg.effort == effort_value


def test_effort_literal_rejects_bogus_value() -> None:
    """The Effort Literal rejects values outside the closed 5-value
    set (Q1 Reading A per F6.6.B disposition). Bogus value surfaces
    at AgentConfig construction time, not at subprocess args time —
    Argument 1 from F6.5.1 (validate at config-load time, not at
    runtime)."""
    with pytest.raises(Exception, match=r"(?i)effort"):
        AgentConfig(
            role="engineer",
            display_name="x",
            system_prompt="x",
            effort="not-a-real-effort",  # type: ignore[arg-type]
        )


def test_effort_field_required_no_default(tmp_path: Path) -> None:
    """Missing effort field in yaml fails Pydantic validation per
    F6.2 Q2 Reading A (hard-fail surfacing config drift). Operator
    editing a yaml to remove the effort line surfaces the omission
    at daemon startup.

    Sentinel test pins the contract — future-Claude adding a soft
    default to the field would silently mask config drift.
    """
    bad_yaml = """
role: engineer
display_name: Engineer
system_prompt: |
  Engineer system prompt.
"""
    p = tmp_path / "engineer.yaml"
    p.write_text(bad_yaml, encoding="utf-8")
    with pytest.raises(AgentConfigError, match=r"(?i)effort|required|missing"):
        load_agent_config(p)


def test_tester_yaml_allowed_tools_contains_playwright_and_readonly_builtins() -> None:
    """M5-acceptance commit 3 contract test (Convention 17 sub-type (b)
    Presence enforcement): tester.yaml's ``allowed_tools`` MUST contain
    the 8 prefixed Playwright MCP tools + the read-only built-in tool
    set (Read, Bash, Glob, Grep — no Edit/Write per tester read-only-
    on-codebase discipline).

    Methodology-as-moat: encoding the load-bearing tool names as a
    contract test surfaces accidental yaml deletion in CI rather than
    allowing silent drift between the dispatched-Tester subprocess's
    runtime allowlist and the yaml's documented capability surface.
    Without these entries in ``allowed_tools``, ``claude --print`` mode
    permission-gates the corresponding tools (verified empirically at
    commit 3 prep 2026-05-08); β6 (Tester invokes Playwright +
    complete_dispatch) would surface as permission-denied across every
    Playwright tool call.

    Asserts on substring presence + tool count rather than full-list
    equality so future additions (e.g., a new Playwright tool added at
    @playwright/mcp's next release) don't break the test while
    structural deletions of the load-bearing tools do.

    Edit/Write absence is asserted explicitly — load-bearing for
    tester read-only-on-codebase discipline (yaml line 154:
    "Read-only on the codebase").
    """
    cfg = load_agent_config(REPO_CONFIG_DIR / "tester.yaml")
    allowed = cfg.allowed_tools

    # Built-in read-only set (Mike's D3 disposition 2026-05-08)
    assert "Read" in allowed
    assert "Bash" in allowed
    assert "Glob" in allowed
    assert "Grep" in allowed

    # Read-only-on-codebase invariant: no Edit/Write tools
    assert "Edit" not in allowed, (
        "tester.yaml allowed_tools contains Edit — violates "
        "read-only-on-codebase discipline (yaml line 154)"
    )
    assert "Write" not in allowed, (
        "tester.yaml allowed_tools contains Write — violates "
        "read-only-on-codebase discipline (yaml line 154)"
    )
    assert "MultiEdit" not in allowed, (
        "tester.yaml allowed_tools contains MultiEdit — violates "
        "read-only-on-codebase discipline (yaml line 154)"
    )

    # Playwright MCP tools — all 8 prefixed entries (canonical naming
    # per empirical verification 2026-05-08: `mcp__<server>__<tool>`)
    expected_playwright = [
        "mcp__playwright__browser_navigate",
        "mcp__playwright__browser_snapshot",
        "mcp__playwright__browser_evaluate",
        "mcp__playwright__browser_console_messages",
        "mcp__playwright__browser_network_requests",
        "mcp__playwright__browser_wait_for",
        "mcp__playwright__browser_take_screenshot",
        "mcp__playwright__browser_close",
    ]
    for tool in expected_playwright:
        assert tool in allowed, (
            f"tester.yaml allowed_tools missing {tool!r} — β6 would "
            "surface permission-denied on this Playwright invocation. "
            "If a Playwright tool is renamed at @playwright/mcp's next "
            "release, update both this assertion and the yaml in lockstep."
        )


def test_implementer_yaml_allowed_tools_contains_full_filesystem_set() -> None:
    """M5-acceptance commit 3 contract test (Convention 17 sub-type (b)
    Presence enforcement): implementer.yaml's ``allowed_tools`` MUST
    contain the full file-system tool set (Read, Edit, Write, MultiEdit,
    Bash, Glob, Grep) per implementer.yaml's prose at line 56:
    "you have file-system tools (Edit, Write, Read, Glob, Grep, Bash)
    for the worktree's contents".

    Methodology-as-moat: encoding the file-system tool set as a contract
    test surfaces accidental yaml deletion in CI rather than allowing
    silent drift between the dispatched-Implementer subprocess's runtime
    allowlist and the yaml's documented capability surface. Without
    these entries, ``claude --print`` mode permission-gates the
    corresponding tools (verified empirically at commit 3 prep
    2026-05-08); β5 (Implementer produces commit on dispatch branch)
    would surface as permission-denied on file edits / git Bash
    invocations.

    Implementer does NOT include Playwright tools — Tester does that
    work. Asserting absence of Playwright-prefixed entries locks the
    role-asymmetry against accidental drift.
    """
    cfg = load_agent_config(REPO_CONFIG_DIR / "implementer.yaml")
    allowed = cfg.allowed_tools

    # Full file-system set per implementer.yaml prose line 56 + Mike's
    # D3 disposition 2026-05-08 (MultiEdit added beyond yaml prose)
    expected_builtins = ["Read", "Edit", "Write", "MultiEdit", "Bash", "Glob", "Grep"]
    for tool in expected_builtins:
        assert tool in allowed, (
            f"implementer.yaml allowed_tools missing {tool!r} — β5 "
            "would surface permission-denied on this file-system "
            "operation. Yaml prose at line 56 documents the full set."
        )

    # Role-asymmetry invariant: no Playwright entries (Tester does
    # browser-driven verification; Implementer does code changes)
    playwright_entries = [t for t in allowed if t.startswith("mcp__playwright__")]
    assert playwright_entries == [], (
        f"implementer.yaml allowed_tools contains Playwright entries "
        f"{playwright_entries!r} — violates role-asymmetry: Tester does "
        "browser-driven verification, Implementer does code changes. "
        "If a future commit deliberately gives Implementer browser "
        "access, update this assertion in lockstep."
    )


def test_per_role_effort_assignments_per_addendum() -> None:
    """All four repo-controlled yamls match the
    orchestrator-utilization-addendum's per-role assignments:
    PM/Engineer/Implementer at xhigh, Tester at high.

    Sentinel test pins the assignment table; failure here means a
    yaml drifted from the addendum without the docs being updated
    in lockstep (commit 6 spec amendment ships the rationale doc;
    if a future commit changes a yaml's effort, the addendum must
    be amended in lockstep).
    """
    expected = {
        "pm": "xhigh",
        "engineer": "xhigh",
        "implementer": "xhigh",
        "tester": "high",
    }
    for role, expected_effort in expected.items():
        cfg = load_agent_config(REPO_CONFIG_DIR / f"{role}.yaml")
        assert cfg.effort == expected_effort, (
            f"{role}.yaml effort drifted from addendum: "
            f"got {cfg.effort!r}, expected {expected_effort!r}. "
            "If this is a deliberate change, update the addendum doc "
            "(orchestrator-utilization-addendum.md + commit 6 hybrid "
            "amendment) in lockstep."
        )
