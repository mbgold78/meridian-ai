"""Unit tests for ``--append-system-prompt`` plumbing through the dispatcher's
default launcher (M5-acceptance commit 2 Finding A closure).

Convention 17 sub-type (c) Invariant enforcement: tests construct a
``DispatchLaunchContext``, invoke ``bridge/dispatcher.py:_default_launcher``,
and assert on the post-construction argv tuple captured from the
monkeypatched ``asyncio.create_subprocess_exec``. Same fixture pattern as
``test_default_launcher_effort.py`` and ``test_default_launcher_pipe_drain.py``.

Scope is the dispatcher launcher only — supervisor_base.py:_default_launcher
already loads yaml.system_prompt as the ``-p <prompt>`` positional via the
supervisor subclasses' ``_subprocess_prompt()`` hook (engineer_supervisor.py
returns ``engineer.config.system_prompt``; pm_supervisor.py returns
``pm.config.system_prompt``). Supervisors don't go through the dispatcher
path; symmetric-launcher coverage from prior commits doesn't apply here.

CLI flag literal verified via ``claude --help`` (2026-05-08):
``--append-system-prompt <prompt>`` appends the given text to Claude Code's
default system prompt. Distinct from ``--system-prompt <prompt>`` which
REPLACES the default — the append shape preserves Claude Code's built-in
tool guidance alongside the dispatched role's yaml-encoded discipline.

Negative invariant (system_prompt empty → no flag) is load-bearing: the
empty-string default on ``DispatchLaunchContext.system_prompt`` keeps test
fixtures and any non-dispatcher caller path from silently injecting a
flag without yaml-resolved content. Asserting the negative locks the
launcher's conditional shape against future "always-emit" drift.
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
    actually spawning. Mirrors the fixture in test_default_launcher_effort.py
    so future-Claude reading both files sees one pattern, not two."""
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
# Positive invariant: --append-system-prompt fires when system_prompt is
# non-empty, with the role-resolved content threaded through unchanged.
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("role", ["implementer", "tester"])
async def test_dispatcher_launcher_passes_append_system_prompt_when_non_empty(
    captured_subprocess_args: list[tuple[Any, ...]],
    role: str,
    tmp_path: Path,
) -> None:
    """For both dispatcher roles (implementer + tester), a non-empty
    ``system_prompt`` produces argv containing ``--append-system-prompt``
    immediately followed by the system_prompt content. The closure of
    Finding A (yaml.system_prompt previously not wired at dispatch)
    depends on this argv shape; without the flag the dispatched
    subprocess runs as default Claude Code with only the user-level
    prompt — the Tester's yaml-encoded verifier discipline / Implementer's
    yaml-encoded executor discipline never reach the runtime."""
    sp = "you are a sub-agent; constraints follow"
    ctx = DispatchLaunchContext(
        dispatch_id="d-sp-test",
        role=role,  # type: ignore[arg-type]
        prompt="user-level prompt",
        working_directory=tmp_path,
        token="test-token",
        effort="high",
        system_prompt=sp,
    )
    await _default_launcher(ctx)

    assert len(captured_subprocess_args) == 1
    args = captured_subprocess_args[0]
    # Expected argv per Option A disposition (Mike, 2026-05-08):
    # ("claude", "--effort", <level>, "--append-system-prompt", <sp>, "-p", <prompt>).
    # Adjacency between --append-system-prompt and its content is the
    # CLI contract; adjacency between -p and the prompt positional
    # preserves the existing argv-tail shape across launcher revisions.
    assert args == (
        "claude",
        "--effort",
        "high",
        "--append-system-prompt",
        sp,
        "-p",
        "user-level prompt",
    ), (
        f"dispatcher launcher argv drifted: got {args!r}; "
        "M5-acceptance commit 2 wires --append-system-prompt before -p "
        "for non-empty system_prompt. CLI flag literal is "
        "`--append-system-prompt <prompt>` not `--append-system-prompt=<prompt>`."
    )


# ---------------------------------------------------------------------------
# Negative invariant: --append-system-prompt is ABSENT when system_prompt
# is empty. Locks the launcher's conditional shape against future
# "always-emit" drift. Empty-default DispatchLaunchContext callers keep
# their current argv unchanged.
# ---------------------------------------------------------------------------


async def test_dispatcher_launcher_omits_append_system_prompt_when_empty(
    captured_subprocess_args: list[tuple[Any, ...]],
    tmp_path: Path,
) -> None:
    """The empty-string default on DispatchLaunchContext.system_prompt
    suppresses the --append-system-prompt flag entirely. This is the
    test-fixture-friendly path AND the safety rail: a launcher that
    always emits the flag — even with empty content — would inject an
    empty system prompt into Claude Code, with undefined runtime behavior.
    The conditional gate keeps non-dispatcher callers' argv shape stable."""
    ctx = DispatchLaunchContext(
        dispatch_id="d-empty-sp-test",
        role="implementer",
        prompt="user-level prompt",
        working_directory=tmp_path,
        token="test-token",
        effort="high",
        # system_prompt defaults to "" — verify the launcher omits the flag.
    )
    await _default_launcher(ctx)

    assert len(captured_subprocess_args) == 1
    args = captured_subprocess_args[0]
    # Argv matches the pre-commit-2 shape exactly — proves the wiring
    # change is non-disruptive when system_prompt is unset.
    assert args == (
        "claude",
        "--effort",
        "high",
        "-p",
        "user-level prompt",
    ), (
        f"dispatcher launcher argv drifted from empty-system_prompt baseline: "
        f"got {args!r}; the launcher must NOT emit --append-system-prompt "
        "when system_prompt is empty. Empty-default-omits invariant from "
        "M5-acceptance commit 2 disposition (Mike, 2026-05-08)."
    )
    # Defense-in-depth: explicit substring assertion in case future argv
    # construction grows additional flags between --effort and -p.
    assert "--append-system-prompt" not in args, (
        "negative invariant violated: --append-system-prompt appeared in "
        "argv with empty system_prompt. The conditional emit gate exists "
        "to keep test fixtures and non-dispatcher callers from silently "
        "injecting an empty system prompt into the subprocess."
    )


# ---------------------------------------------------------------------------
# Argv-order invariant: when the flag fires, it appears between --effort
# and -p. Pinned per Option A disposition; protects against future argv
# reorderings that move --append-system-prompt past -p (which would land
# the system_prompt content as the prompt positional).
# ---------------------------------------------------------------------------


async def test_dispatcher_launcher_append_system_prompt_position_between_effort_and_prompt(
    captured_subprocess_args: list[tuple[Any, ...]],
    tmp_path: Path,
) -> None:
    """The --append-system-prompt flag appears AFTER --effort <level>
    and BEFORE -p <prompt> per Option A argv-ordering disposition.
    Asserting on the index relationships rather than the exact argv tuple
    catches reordering even if other flags are added between in future
    commits (e.g., --mcp-config at commit 3 conditional, or
    --add-dir if substrate worktree path threading changes)."""
    ctx = DispatchLaunchContext(
        dispatch_id="d-order-test",
        role="tester",
        prompt="user-level prompt",
        working_directory=tmp_path,
        token="test-token",
        effort="xhigh",
        system_prompt="role-encoded-discipline",
    )
    await _default_launcher(ctx)

    args = captured_subprocess_args[0]
    effort_flag_idx = args.index("--effort")
    append_flag_idx = args.index("--append-system-prompt")
    prompt_flag_idx = args.index("-p")
    assert effort_flag_idx < append_flag_idx < prompt_flag_idx, (
        f"argv ordering violated Option A invariant: got "
        f"--effort@{effort_flag_idx} --append-system-prompt@{append_flag_idx} "
        f"-p@{prompt_flag_idx} in {args!r}. Disposition: --effort first, "
        "then --append-system-prompt, then -p positional last."
    )
    # Adjacency: --append-system-prompt's content is the IMMEDIATE next
    # argv element. CLI contract — `--append-system-prompt VALUE` not
    # `--append-system-prompt --other-flag VALUE`.
    assert args[append_flag_idx + 1] == "role-encoded-discipline", (
        f"--append-system-prompt and its content lost adjacency: "
        f"got {args[append_flag_idx + 1]!r} as the slot after the flag "
        f"in {args!r}. CLI flag-value adjacency is the contract."
    )


# ---------------------------------------------------------------------------
# Multiline system_prompt fidelity: yaml literal block content (179 lines
# for tester.yaml) flows through to the subprocess argv unchanged. Verifies
# the dispatch path doesn't strip newlines or encode the value differently.
# ---------------------------------------------------------------------------


async def test_dispatcher_launcher_passes_multiline_system_prompt_unchanged(
    captured_subprocess_args: list[tuple[Any, ...]],
    tmp_path: Path,
) -> None:
    """yaml.system_prompt is a multiline literal block in
    config/agents/tester.yaml + implementer.yaml. AgentConfig preserves
    the content verbatim (per agents/config.py:70-71 docstring); the
    launcher must thread it through to argv without newline stripping
    or quoting transformation. This test substitutes a representative
    multiline payload to verify byte-equal pass-through."""
    multiline_sp = (
        "You are Tester at Meridian AI.\n\n"
        "## Inputs\n"
        "- assertion_spec\n"
        "- commit_hash\n\n"
        "## Constraints\n"
        "- Read-only on the codebase\n"
    )
    ctx = DispatchLaunchContext(
        dispatch_id="d-multiline-sp-test",
        role="tester",
        prompt="x",
        working_directory=tmp_path,
        token="t",
        effort="high",
        system_prompt=multiline_sp,
    )
    await _default_launcher(ctx)

    args = captured_subprocess_args[0]
    append_idx = args.index("--append-system-prompt")
    assert args[append_idx + 1] == multiline_sp, (
        "multiline system_prompt content lost fidelity through the launcher: "
        "newlines / structure must pass through unchanged. asyncio.create_"
        "subprocess_exec accepts the value as a single argv element; the OS "
        "exec layer handles literal newlines without shell interpretation."
    )
