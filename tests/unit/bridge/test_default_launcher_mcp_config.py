"""Unit tests for ``--mcp-config`` + ``--strict-mcp-config`` plumbing
through the dispatcher's default launcher (M5-acceptance commit 3.5 —
closes the subprocess MCP auth gap: ``claude --print`` invokes
orchestrator MCP without ``Authorization: Bearer <token>`` header by
default; FastMCP server raises
``AuthError("no authenticated context for tool call")``).

Convention 17 sub-type (c) Invariant enforcement: tests construct a
``DispatchLaunchContext``, invoke
``bridge/dispatcher.py:_default_launcher``, and assert on the
post-construction argv tuple captured from the monkeypatched
``asyncio.create_subprocess_exec``. Same fixture pattern as
``test_default_launcher_system_prompt.py`` and
``test_default_launcher_allowed_tools.py``.

CLI flag literals verified via ``claude --help`` (2026-05-08):
``--mcp-config <configs...>`` "Load MCP servers from JSON files or
strings"; ``--strict-mcp-config`` "Only use MCP servers from
--mcp-config, ignoring all other MCP configurations". Hermetic
semantics verified empirically (2026-05-08) via sentinel-at-user-scope
vs. empty-config probe.

Negative invariant (mcp_config_path None → no flags) is load-bearing:
the None default on ``DispatchLaunchContext.mcp_config_path`` keeps
test fixtures and any non-dispatcher caller path from silently
injecting the flags. Asserting the negative locks the launcher's
conditional shape against future "always-emit" drift.

Forward-looking position invariant (``--mcp-config`` slot between
``--allowedTools`` and ``-p``) protects against argv reorderings.
``--strict-mcp-config`` adjacency to ``--mcp-config <path>`` is the
CLI contract — flags that fire together must stay together.
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
    ``test_default_launcher_system_prompt.py`` +
    ``test_default_launcher_allowed_tools.py``."""
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
# Positive invariant: --mcp-config + --strict-mcp-config fire together
# when mcp_config_path is set; the path is the immediate next argv slot.
# ---------------------------------------------------------------------------


async def test_dispatcher_launcher_emits_mcp_config_and_strict_when_path_set(
    captured_subprocess_args: list[tuple[Any, ...]],
    tmp_path: Path,
) -> None:
    """When DispatchLaunchContext.mcp_config_path is non-None, argv
    includes ``--mcp-config <path> --strict-mcp-config`` — both flags
    fire together. The strict flag is load-bearing: hermetic semantics
    (verified empirically 2026-05-08) ensure the subprocess uses ONLY
    the per-dispatch JSON; user/local-scope
    registrations cannot leak in. Without ``--strict-mcp-config`` the
    flag would be additive; user-scope orchestrator registrations
    (under ``-s user``) would leak in alongside per-dispatch entries —
    both architecturally wrong AND a security regression."""
    cfg_path = tmp_path / "mcp_config.json"
    cfg_path.write_text("{}", encoding="utf-8")
    ctx = DispatchLaunchContext(
        dispatch_id="d-mcp-test",
        role="tester",
        prompt="user-level prompt",
        working_directory=tmp_path,
        token="test-token",
        effort="high",
        mcp_config_path=cfg_path,
    )
    await _default_launcher(ctx)

    assert len(captured_subprocess_args) == 1
    args = captured_subprocess_args[0]
    # Expected argv per Option A disposition (Mike, 2026-05-08, commit 3.5):
    # ("claude", "--effort", <level>, "--mcp-config", <path>, "--strict-mcp-config",
    # "-p", <prompt>) when mcp_config_path is set AND other conditional
    # flags are absent. Adjacency between --mcp-config and the path is
    # the CLI contract.
    assert args == (
        "claude",
        "--effort",
        "high",
        "--mcp-config",
        str(cfg_path),
        "--strict-mcp-config",
        "-p",
        "user-level prompt",
    ), (
        f"dispatcher launcher argv drifted: got {args!r}; "
        "M5-acceptance commit 3.5 wires --mcp-config <path> "
        "--strict-mcp-config before -p when mcp_config_path is set. "
        "Both flags must fire together (--strict-mcp-config without "
        "--mcp-config is meaningless; --mcp-config without --strict "
        "is additive and would leak user-scope registrations)."
    )


# ---------------------------------------------------------------------------
# Negative invariant: --mcp-config and --strict-mcp-config are ABSENT
# when mcp_config_path is None. Locks the conditional shape against
# "always-emit" drift; preserves test-fixture argv shape.
# ---------------------------------------------------------------------------


async def test_dispatcher_launcher_omits_mcp_config_when_path_none(
    captured_subprocess_args: list[tuple[Any, ...]],
    tmp_path: Path,
) -> None:
    """The None default on DispatchLaunchContext.mcp_config_path
    suppresses BOTH ``--mcp-config`` and ``--strict-mcp-config``
    entirely. Test fixtures with custom subprocess_launcher injections
    that don't thread per-dispatch config keep their current argv shape
    (the 11+ integration test fixtures bypass the real launcher
    entirely; this invariant is for the contract-layer test fixtures
    that DO touch _default_launcher with default DispatchLaunchContext)."""
    ctx = DispatchLaunchContext(
        dispatch_id="d-empty-mcp-test",
        role="implementer",
        prompt="user-level prompt",
        working_directory=tmp_path,
        token="test-token",
        effort="high",
        # mcp_config_path defaults to None — verify launcher omits BOTH flags.
    )
    await _default_launcher(ctx)

    assert len(captured_subprocess_args) == 1
    args = captured_subprocess_args[0]
    # Argv matches the pre-commit-3.5 shape exactly — proves the wiring
    # change is non-disruptive when mcp_config_path is unset.
    assert args == (
        "claude",
        "--effort",
        "high",
        "-p",
        "user-level prompt",
    ), (
        f"dispatcher launcher argv drifted from None-mcp_config_path baseline: "
        f"got {args!r}; the launcher must NOT emit --mcp-config or "
        "--strict-mcp-config when mcp_config_path is None. None-default-omits "
        "invariant from M5-acceptance commit 3.5 disposition (Mike, 2026-05-08)."
    )
    # Defense-in-depth: explicit substring assertions for both flags
    assert "--mcp-config" not in args, (
        "negative invariant violated: --mcp-config appeared in argv with "
        "None mcp_config_path. The conditional emit gate keeps test "
        "fixtures from silently injecting the flag with a None path."
    )
    assert "--strict-mcp-config" not in args, (
        "negative invariant violated: --strict-mcp-config appeared without "
        "--mcp-config. Strict-without-config is meaningless and indicates "
        "the conditional gate has diverged from paired-flag semantics."
    )


# ---------------------------------------------------------------------------
# Argv-order invariant: when all four conditional flag blocks fire
# (--append-system-prompt, --allowedTools, --mcp-config + --strict-mcp-config),
# Option A ordering requires:
# --effort < --append-system-prompt < --allowedTools < --mcp-config <
# --strict-mcp-config < -p. Forward-looking: protects against argv
# reorderings AND admits future slots (ASK 3 BACKLOG's --add-dir wiring).
# ---------------------------------------------------------------------------


async def test_dispatcher_launcher_full_argv_position_invariant(
    captured_subprocess_args: list[tuple[Any, ...]],
    tmp_path: Path,
) -> None:
    """When all conditional blocks fire (system_prompt + allowed_tools +
    mcp_config_path), the Option A argv ordering invariant requires:
    --effort < --append-system-prompt < --allowedTools < --mcp-config <
    --strict-mcp-config < -p. Asserting on the index relationships rather
    than the exact argv tuple catches reordering even if other flags
    are added between in future commits (e.g., --add-dir per ASK 3
    BACKLOG entry). Forward-looking position contract per Convention 17
    sub-type (c)."""
    cfg_path = tmp_path / "mcp_config.json"
    cfg_path.write_text("{}", encoding="utf-8")
    ctx = DispatchLaunchContext(
        dispatch_id="d-full-order-test",
        role="tester",
        prompt="user-level prompt",
        working_directory=tmp_path,
        token="test-token",
        effort="xhigh",
        system_prompt="role-encoded-discipline",
        allowed_tools=["Read", "Bash"],
        mcp_config_path=cfg_path,
    )
    await _default_launcher(ctx)

    args = captured_subprocess_args[0]
    effort_idx = args.index("--effort")
    append_idx = args.index("--append-system-prompt")
    allowed_idx = args.index("--allowedTools")
    mcp_idx = args.index("--mcp-config")
    strict_idx = args.index("--strict-mcp-config")
    prompt_idx = args.index("-p")
    assert (
        effort_idx
        < append_idx
        < allowed_idx
        < mcp_idx
        < strict_idx
        < prompt_idx
    ), (
        f"argv ordering violated Option A invariant: got "
        f"--effort@{effort_idx} --append-system-prompt@{append_idx} "
        f"--allowedTools@{allowed_idx} --mcp-config@{mcp_idx} "
        f"--strict-mcp-config@{strict_idx} -p@{prompt_idx} in {args!r}. "
        "Disposition: --effort first, then --append-system-prompt, "
        "then --allowedTools, then --mcp-config <path> --strict-mcp-config, "
        "then -p positional last."
    )
    # Adjacency: --mcp-config's path is the IMMEDIATE next argv element.
    assert args[mcp_idx + 1] == str(cfg_path), (
        f"--mcp-config and its path lost adjacency: "
        f"got {args[mcp_idx + 1]!r} as the slot after the flag "
        f"in {args!r}. CLI flag-value adjacency is the contract."
    )
    # --strict-mcp-config IMMEDIATELY follows the path (paired-flag
    # ordering: the strict flag belongs with its config path)
    assert args[mcp_idx + 2] == "--strict-mcp-config", (
        f"--strict-mcp-config lost paired-flag adjacency to --mcp-config: "
        f"got {args[mcp_idx + 2]!r} between --mcp-config <path> and the "
        "expected --strict-mcp-config slot. Strict semantics are paired "
        "with the config path; intervening flags would muddy the contract."
    )
