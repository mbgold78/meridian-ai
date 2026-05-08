"""Unit tests for ``--allowedTools`` + ``--mcp-config`` +
``--strict-mcp-config`` plumbing through the supervisor's default
launcher (M5-acceptance commit 3.6 — closes the supervisor-side
auth gap symmetric to dispatcher commit 3 + commit 3.5 wiring per
ζ-1 disposition; lifecycle anchor: per-spawn long-lived supervisor
token vs. per-dispatch short-lived dispatcher token).

Convention 17 sub-type (c) Invariant enforcement: tests construct a
``SupervisorLaunchContext``, invoke
``bridge/supervisor_base.py:_default_launcher``, and assert on the
post-construction argv tuple captured from the monkeypatched
``asyncio.create_subprocess_exec``. Same fixture pattern as
``test_default_launcher_system_prompt.py`` /
``test_default_launcher_allowed_tools.py`` /
``test_default_launcher_mcp_config.py`` (dispatcher-side equivalents).

Architectural difference from dispatcher: supervisor's yaml.system_prompt
is the ``-p <prompt>`` user message (subclass-provided via
:meth:`LongRunningSupervisor._subprocess_prompt`), NOT appended via
``--append-system-prompt``. This is intentional M3 design — supervisor's
full-prompt-as-user-message vs. dispatcher's system+user-message split.
The launcher therefore wires only ``--allowedTools`` + ``--mcp-config``
+ ``--strict-mcp-config`` (no ``--append-system-prompt``).
"""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock

import pytest

from meridian_orchestrator.bridge.supervisor_base import (
    SupervisorLaunchContext,
    _default_launcher,
)


@pytest.fixture
def captured_subprocess_args(
    monkeypatch: pytest.MonkeyPatch,
) -> list[tuple[Any, ...]]:
    """Capture positional args to asyncio.create_subprocess_exec without
    actually spawning. Mirrors the dispatcher launcher test fixture."""
    captured: list[tuple[Any, ...]] = []

    async def fake_create(*args: Any, **kwargs: Any) -> AsyncMock:
        captured.append(args)
        proc = AsyncMock()
        proc.pid = 12345
        proc.returncode = None
        proc.stderr = None
        return proc

    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_create)
    return captured


# ---------------------------------------------------------------------------
# Positive invariant: --allowedTools fires when allowed_tools non-empty.
# ---------------------------------------------------------------------------


async def test_supervisor_launcher_emits_allowed_tools_when_non_empty(
    captured_subprocess_args: list[tuple[Any, ...]],
    tmp_path: Path,
) -> None:
    """Non-empty ``allowed_tools`` produces argv containing
    ``--allowedTools`` immediately followed by the space-joined tool
    list. Without the flag, the supervisor subprocess fails closed on
    MCP tool invocations."""
    tools = ["Read", "Bash", "mcp__meridian-orchestrator__signal_compaction"]
    ctx = SupervisorLaunchContext(
        dispatch_id="d-supv-at-test",
        role="engineer-supervisor",
        prompt="<engineer system prompt>",
        working_directory=tmp_path,
        token="test-token",
        effort="xhigh",
        allowed_tools=tools,
    )
    await _default_launcher(ctx)

    args = captured_subprocess_args[0]
    assert args == (
        "claude",
        "--effort",
        "xhigh",
        "--allowedTools",
        "Read Bash mcp__meridian-orchestrator__signal_compaction",
        "-p",
        "<engineer system prompt>",
    )


# ---------------------------------------------------------------------------
# Positive invariant: --mcp-config + --strict-mcp-config fire together.
# ---------------------------------------------------------------------------


async def test_supervisor_launcher_emits_mcp_config_and_strict_when_path_set(
    captured_subprocess_args: list[tuple[Any, ...]],
    tmp_path: Path,
) -> None:
    """Non-None ``mcp_config_path`` produces argv containing
    ``--mcp-config <path> --strict-mcp-config``. Both flags fire
    together; strict mode is load-bearing for hermetic isolation."""
    cfg_path = tmp_path / "mcp_config.json"
    cfg_path.write_text("{}", encoding="utf-8")
    ctx = SupervisorLaunchContext(
        dispatch_id="d-supv-mcp-test",
        role="pm-continuous",
        prompt="<pm system prompt>",
        working_directory=tmp_path,
        token="test-token",
        effort="xhigh",
        mcp_config_path=cfg_path,
    )
    await _default_launcher(ctx)

    args = captured_subprocess_args[0]
    assert args == (
        "claude",
        "--effort",
        "xhigh",
        "--mcp-config",
        str(cfg_path),
        "--strict-mcp-config",
        "-p",
        "<pm system prompt>",
    )


# ---------------------------------------------------------------------------
# Negative invariant: both flag blocks ABSENT when fields are
# empty/None. Locks the launcher's conditional shape against future
# "always-emit" drift; preserves test-fixture argv shape.
# ---------------------------------------------------------------------------


async def test_supervisor_launcher_omits_both_flag_blocks_when_unset(
    captured_subprocess_args: list[tuple[Any, ...]],
    tmp_path: Path,
) -> None:
    """Default-empty ``allowed_tools`` (``[]``) + None-default
    ``mcp_config_path`` together produce the pre-commit-3.6 argv shape
    exactly. Test fixtures with custom ``subprocess_launcher`` injections
    that don't thread per-spawn config keep their current argv unchanged."""
    ctx = SupervisorLaunchContext(
        dispatch_id="d-supv-empty-test",
        role="engineer-supervisor",
        prompt="<engineer system prompt>",
        working_directory=tmp_path,
        token="test-token",
        effort="xhigh",
        # allowed_tools defaults to []; mcp_config_path defaults to None.
    )
    await _default_launcher(ctx)

    args = captured_subprocess_args[0]
    assert args == (
        "claude",
        "--effort",
        "xhigh",
        "-p",
        "<engineer system prompt>",
    ), (
        f"supervisor launcher argv drifted from default-empty baseline: "
        f"got {args!r}; the launcher must NOT emit --allowedTools or "
        "--mcp-config when their respective fields are empty/None. "
        "Default-empty-omits invariant from M5-acceptance commit 3.6."
    )
    assert "--allowedTools" not in args
    assert "--mcp-config" not in args
    assert "--strict-mcp-config" not in args


# ---------------------------------------------------------------------------
# Argv-order invariant: when both blocks fire, --allowedTools precedes
# --mcp-config which precedes --strict-mcp-config which precedes -p.
# Forward-looking position contract; protects against argv reorderings.
# ---------------------------------------------------------------------------


async def test_supervisor_launcher_full_argv_position_invariant(
    captured_subprocess_args: list[tuple[Any, ...]],
    tmp_path: Path,
) -> None:
    """When both --allowedTools and --mcp-config fire, the Option A
    argv ordering invariant requires:
    --effort < --allowedTools < --mcp-config < --strict-mcp-config < -p.

    Asserting on the index relationships rather than the exact argv tuple
    catches reordering even if other flags are added between in future
    commits. Note: supervisor argv has NO --append-system-prompt slot
    (architectural difference from dispatcher; supervisor's
    yaml.system_prompt is the user-message prompt itself)."""
    cfg_path = tmp_path / "mcp_config.json"
    cfg_path.write_text("{}", encoding="utf-8")
    ctx = SupervisorLaunchContext(
        dispatch_id="d-supv-order-test",
        role="pm-continuous",
        prompt="<pm system prompt>",
        working_directory=tmp_path,
        token="test-token",
        effort="xhigh",
        allowed_tools=["Read", "Bash"],
        mcp_config_path=cfg_path,
    )
    await _default_launcher(ctx)

    args = captured_subprocess_args[0]
    effort_idx = args.index("--effort")
    allowed_idx = args.index("--allowedTools")
    mcp_idx = args.index("--mcp-config")
    strict_idx = args.index("--strict-mcp-config")
    prompt_idx = args.index("-p")
    assert (
        effort_idx < allowed_idx < mcp_idx < strict_idx < prompt_idx
    ), (
        f"supervisor argv ordering violated Option A invariant: got "
        f"--effort@{effort_idx} --allowedTools@{allowed_idx} "
        f"--mcp-config@{mcp_idx} --strict-mcp-config@{strict_idx} "
        f"-p@{prompt_idx} in {args!r}."
    )
    # Adjacency contracts
    assert args[allowed_idx + 1] == "Read Bash"
    assert args[mcp_idx + 1] == str(cfg_path)
    assert args[mcp_idx + 2] == "--strict-mcp-config"
    # No --append-system-prompt at supervisor (architectural difference)
    assert "--append-system-prompt" not in args, (
        "supervisor argv contains --append-system-prompt; this is the "
        "dispatcher pattern. Supervisor's yaml.system_prompt is the "
        "user-message prompt directly via -p positional (intentional "
        "M3 architectural difference). If a future commit adds "
        "--append-system-prompt to supervisor side, update this "
        "assertion + verify the change is deliberate."
    )
