"""Unit tests for ``scripts._operator_wrapper`` — operator role-wrapper
shared logic (post-beta architectural remediation Component 3).

Convention 17 sub-type (c) Invariant enforcement: tests construct argv
without spawning subprocess; assert on argv shape per role + passthrough
forwarding; assert error surfaces (FATAL exits) for missing yaml or
missing claude binary.
"""

from __future__ import annotations

from pathlib import Path
from unittest.mock import patch

import pytest
from scripts._operator_wrapper import (
    _load_role_system_prompt,
    _project_root,
    build_argv,
)


def test_project_root_resolves_to_orchestrator_dir() -> None:
    """``_project_root`` returns the orchestrator project directory
    (parent of ``scripts/``). Required for yaml path resolution to find
    ``config/agents/<role>.yaml``."""
    root = _project_root()
    assert (root / "scripts").is_dir()
    assert (root / "config" / "agents").is_dir()


@pytest.mark.parametrize("role", ["pm", "engineer", "implementer", "tester"])
def test_load_role_system_prompt_reads_yaml(role: str) -> None:
    """All four agent yamls exist + load + expose non-empty
    system_prompt. Documents the load-bearing assumption that operator
    wrapper scripts (currently pm + engineer) can extend to additional
    roles without code change."""
    prompt = _load_role_system_prompt(role)
    assert isinstance(prompt, str)
    assert len(prompt) > 100, (
        f"{role}.yaml system_prompt is suspiciously short — likely "
        f"truncated or load-bearing content removed"
    )


def test_load_role_system_prompt_raises_on_missing_yaml(tmp_path: Path) -> None:
    """Missing yaml surfaces as ``SystemExit`` with operator-facing
    message identifying the expected path."""
    with patch(
        "scripts._operator_wrapper._project_root",
        return_value=tmp_path,
    ), pytest.raises(SystemExit, match=r"agent yaml not found"):
        _load_role_system_prompt("pm")


def test_build_argv_composition_pm() -> None:
    """build_argv produces argv with ``--dangerously-skip-permissions``
    + ``--append-system-prompt <pm.yaml content>`` + passthrough args
    forwarded after."""
    with patch(
        "scripts._operator_wrapper._resolve_claude_binary",
        return_value="/path/to/claude",
    ):
        argv = build_argv("pm", ["--resume", "session-abc"])

    assert argv[0] == "/path/to/claude"
    assert "--dangerously-skip-permissions" in argv
    assert "--append-system-prompt" in argv
    # Passthrough args appear AFTER the role-discipline flags
    assert argv[-2:] == ["--resume", "session-abc"]
    # System prompt content is the immediate next argv after
    # --append-system-prompt
    asp_idx = argv.index("--append-system-prompt")
    assert "Product Manager" in argv[asp_idx + 1] or "PM" in argv[asp_idx + 1]


def test_build_argv_no_passthrough_args() -> None:
    """build_argv with empty passthrough list still composes valid argv;
    no trailing empty / spurious entries."""
    with patch(
        "scripts._operator_wrapper._resolve_claude_binary",
        return_value="/path/to/claude",
    ):
        argv = build_argv("engineer", [])

    assert argv[0] == "/path/to/claude"
    assert argv[-1] != ""  # no trailing empty arg
    assert "--append-system-prompt" in argv


def test_build_argv_raises_on_missing_claude_binary() -> None:
    """Missing ``claude`` on PATH surfaces as ``SystemExit`` with
    operator-facing remediation hint."""
    with (
        patch(
            "scripts._operator_wrapper.shutil.which",
            return_value=None,
        ),
        pytest.raises(SystemExit, match=r"claude.*CLI not found"),
    ):
        build_argv("pm", [])
