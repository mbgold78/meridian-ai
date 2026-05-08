"""Unit tests for ``--allowedTools`` plumbing through the dispatcher's
default launcher (M5-acceptance commit 3 — closes the conditional
permission-gating gap surfaced empirically at prep cycle:
``claude --print`` mode fails closed on MCP tool invocations +
outside-cwd reads + non-standard Bash commands by default).

Convention 17 sub-type (c) Invariant enforcement: tests construct a
``DispatchLaunchContext``, invoke ``bridge/dispatcher.py:_default_launcher``,
and assert on the post-construction argv tuple captured from the
monkeypatched ``asyncio.create_subprocess_exec``. Same fixture pattern as
``test_default_launcher_system_prompt.py``.

CLI flag literal verified via ``claude --help`` (2026-05-08):
``--allowedTools, --allowed-tools <tools...>`` accepts a comma- or
space-separated list of tool names. Example: ``"Bash(git *) Edit"``.
The launcher passes the list as a single argv element with
space-separated tool names — supports B1 unrestricted Bash disposition
(Mike's D4, 2026-05-08); future B2 restricted patterns
(``Bash(git *)``) would still fit the comma-separated alternative shape.

Negative invariant (allowed_tools empty → no flag) is load-bearing:
the empty-list default on ``DispatchLaunchContext.allowed_tools`` keeps
test fixtures and any non-dispatcher caller path from silently
injecting an empty allowlist (which would deny ALL tools at the
subprocess level — substantively different from "no allowlist set;
default permission gating applies"). Asserting the negative locks
the launcher's conditional shape against future "always-emit" drift.

Forward-looking position invariant (``--allowedTools`` slot between
``--append-system-prompt`` and ``-p``) protects against argv
reorderings that would either land allowedTools content as the prompt
positional OR push ``-p`` into a position where future flags
(commit 3.5's per-dispatch ``--mcp-config``; ASK 3 BACKLOG's
``--add-dir``) can't slot in cleanly without re-validating the
position contract.
"""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock

import pytest

from meridian_orchestrator.bridge.dispatcher import (
    DispatchLaunchContext,
    _default_launcher,
)


@pytest.fixture
def captured_subprocess_args(
    monkeypatch: pytest.MonkeyPatch,
) -> list[tuple[Any, ...]]:
    """Capture positional args to asyncio.create_subprocess_exec without
    actually spawning. Mirrors the fixture in
    ``test_default_launcher_system_prompt.py`` so future-Claude reading
    both files sees one pattern, not two."""
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
# Positive invariant: --allowedTools fires when allowed_tools is non-empty,
# with the role-resolved list threaded through unchanged as a single
# space-separated argv element.
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("role", ["implementer", "tester"])
async def test_dispatcher_launcher_passes_allowed_tools_when_non_empty(
    captured_subprocess_args: list[tuple[Any, ...]],
    role: str,
    tmp_path: Path,
) -> None:
    """For both dispatcher roles (implementer + tester), a non-empty
    ``allowed_tools`` produces argv containing ``--allowedTools``
    immediately followed by the space-joined tool list. Without the
    flag, the dispatched subprocess fails closed on MCP tool
    invocations + outside-cwd reads (verified empirically at commit 3
    prep 2026-05-08); β5/β6 would surface as permission-denied across
    every tool call subject to the conditional gate."""
    tools = [
        "Read",
        "Bash",
        "mcp__meridian-orchestrator__complete_dispatch",
        "mcp__playwright__browser_navigate",
    ]
    ctx = DispatchLaunchContext(
        dispatch_id="d-at-test",
        role=role,  # type: ignore[arg-type]
        prompt="user-level prompt",
        working_directory=tmp_path,
        token="test-token",
        effort="high",
        allowed_tools=tools,
    )
    await _default_launcher(ctx)

    assert len(captured_subprocess_args) == 1
    args = captured_subprocess_args[0]
    # Expected argv per Option A disposition (Mike, 2026-05-08):
    # ("claude", "--effort", <level>, "--allowedTools", <space-joined>,
    # "-p", <prompt>) when allowed_tools is non-empty AND system_prompt
    # is empty (this test isolates the allowed_tools wiring; the
    # multi-flag composition test below covers both flags together).
    # Adjacency between --allowedTools and its content is the CLI
    # contract; adjacency between -p and the prompt positional preserves
    # the existing argv-tail shape across launcher revisions.
    assert args == (
        "claude",
        "--effort",
        "high",
        "--allowedTools",
        "Read Bash mcp__meridian-orchestrator__complete_dispatch "
        "mcp__playwright__browser_navigate",
        "-p",
        "user-level prompt",
    ), (
        f"dispatcher launcher argv drifted: got {args!r}; "
        "M5-acceptance commit 3 wires --allowedTools before -p for "
        "non-empty allowed_tools. CLI flag literal is "
        "`--allowedTools <space-joined>` (single argv element)."
    )


# ---------------------------------------------------------------------------
# Negative invariant: --allowedTools is ABSENT when allowed_tools is
# empty. Locks the launcher's conditional shape against future
# "always-emit" drift. Empty-default DispatchLaunchContext callers keep
# their current argv unchanged (test fixtures with custom
# subprocess_launcher injections that don't thread per-role allowlists).
# ---------------------------------------------------------------------------


async def test_dispatcher_launcher_omits_allowed_tools_when_empty(
    captured_subprocess_args: list[tuple[Any, ...]],
    tmp_path: Path,
) -> None:
    """The empty-list default on DispatchLaunchContext.allowed_tools
    suppresses the --allowedTools flag entirely. Substantively
    different from "empty allowlist = deny all tools" — without the
    flag, the subprocess uses Claude Code's default conditional
    permission gating (auto-allow low-risk in-cwd operations;
    fail-closed on MCP / outside-cwd / non-standard Bash). The
    conditional gate keeps non-dispatcher callers' argv shape stable."""
    ctx = DispatchLaunchContext(
        dispatch_id="d-empty-at-test",
        role="implementer",
        prompt="user-level prompt",
        working_directory=tmp_path,
        token="test-token",
        effort="high",
        # allowed_tools defaults to [] — verify the launcher omits the flag.
    )
    await _default_launcher(ctx)

    assert len(captured_subprocess_args) == 1
    args = captured_subprocess_args[0]
    # Argv matches the pre-commit-3 shape exactly — proves the wiring
    # change is non-disruptive when allowed_tools is unset.
    assert args == (
        "claude",
        "--effort",
        "high",
        "-p",
        "user-level prompt",
    ), (
        f"dispatcher launcher argv drifted from empty-allowed_tools baseline: "
        f"got {args!r}; the launcher must NOT emit --allowedTools "
        "when allowed_tools is empty. Empty-default-omits invariant from "
        "M5-acceptance commit 3 disposition (Mike, 2026-05-08)."
    )
    # Defense-in-depth: explicit substring assertion in case future argv
    # construction grows additional flags between --effort and -p.
    assert "--allowedTools" not in args, (
        "negative invariant violated: --allowedTools appeared in "
        "argv with empty allowed_tools list. The conditional emit gate "
        "exists to keep test fixtures and non-dispatcher callers from "
        "silently injecting an empty allowlist (which would deny all "
        "tools at the subprocess level)."
    )


# ---------------------------------------------------------------------------
# Argv-order invariant: when both flags fire, --allowedTools appears
# AFTER --append-system-prompt and BEFORE -p per Option A disposition.
# Forward-looking: protects against argv reorderings AND admits future
# slots (commit 3.5's per-dispatch --mcp-config; ASK 3 BACKLOG's
# --add-dir wiring) by asserting on relative ordering rather than
# exact tuple equality.
# ---------------------------------------------------------------------------


async def test_dispatcher_launcher_allowed_tools_position_between_append_and_prompt(
    captured_subprocess_args: list[tuple[Any, ...]],
    tmp_path: Path,
) -> None:
    """When both --append-system-prompt and --allowedTools fire, the
    Option A argv ordering invariant requires:
    --effort < --append-system-prompt < --allowedTools < -p.

    Asserting on the index relationships rather than the exact argv
    tuple catches reordering even if other flags are added between
    in future commits (e.g., --mcp-config at commit 3.5 conditional,
    --add-dir if substrate worktree path threading changes per ASK 3
    BACKLOG entry). Forward-looking position contract per
    Convention 17 sub-type (c) — locks the slot ordering invariant
    explicitly so future drift surfaces at gate-run rather than at
    β-time empirical discovery."""
    ctx = DispatchLaunchContext(
        dispatch_id="d-order-test",
        role="tester",
        prompt="user-level prompt",
        working_directory=tmp_path,
        token="test-token",
        effort="xhigh",
        system_prompt="role-encoded-discipline",
        allowed_tools=["Read", "Bash"],
    )
    await _default_launcher(ctx)

    args = captured_subprocess_args[0]
    effort_idx = args.index("--effort")
    append_idx = args.index("--append-system-prompt")
    allowed_idx = args.index("--allowedTools")
    prompt_idx = args.index("-p")
    assert effort_idx < append_idx < allowed_idx < prompt_idx, (
        f"argv ordering violated Option A invariant: got "
        f"--effort@{effort_idx} --append-system-prompt@{append_idx} "
        f"--allowedTools@{allowed_idx} -p@{prompt_idx} in {args!r}. "
        "Disposition: --effort first, then --append-system-prompt, "
        "then --allowedTools, then -p positional last."
    )
    # Adjacency: --allowedTools content is the IMMEDIATE next argv
    # element. CLI contract — `--allowedTools VALUE` not
    # `--allowedTools --other-flag VALUE`.
    assert args[allowed_idx + 1] == "Read Bash", (
        f"--allowedTools and its content lost adjacency: "
        f"got {args[allowed_idx + 1]!r} as the slot after the flag "
        f"in {args!r}. CLI flag-value adjacency is the contract."
    )
