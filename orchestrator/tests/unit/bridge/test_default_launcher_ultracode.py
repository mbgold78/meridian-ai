"""Unit tests for the per-project ULTRACODE ``--settings`` plumbing through
the dispatcher's default launcher (op-ultracode-daemon).

When ``DispatchLaunchContext.ultracode_enabled`` is True the launcher
appends ``--settings '{"ultracode": true}'`` to the claude argv — two
argv elements (the flag name + a compact JSON-object string). When False
(the default), the launcher omits the flag entirely so the
subscription-only argv is unchanged.

Same fixture + invariant-enforcement convention as
``test_default_launcher_effort.py`` and
``test_default_launcher_mcp_config.py``: construct a
``DispatchLaunchContext``, invoke ``bridge/dispatcher.py:_default_launcher``,
and assert on the post-construction argv tuple captured from the
monkeypatched ``asyncio.create_subprocess_exec``.

Argv-shape verification covers:
  * ON  -> ``--settings`` + JSON string present, between ``--effort`` and ``-p``.
  * OFF -> token ABSENT; argv identical to the no-ultracode baseline
    (subscription-only argv unchanged).
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

_ULTRACODE_JSON = '{"ultracode": true}'


@pytest.fixture
def captured_subprocess_args(
    monkeypatch: pytest.MonkeyPatch,
) -> list[tuple[Any, ...]]:
    """Capture positional args to asyncio.create_subprocess_exec without
    actually spawning. Mirrors the fixture in the sibling launcher tests."""
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
# Positive invariant: --settings JSON fires when ultracode_enabled is True
# ---------------------------------------------------------------------------


async def test_dispatcher_launcher_emits_settings_when_ultracode_on(
    captured_subprocess_args: list[tuple[Any, ...]],
    tmp_path: Path,
) -> None:
    """ultracode_enabled=True -> ``--settings '{"ultracode": true}'`` lands
    between ``--effort <level>`` and the ``-p <prompt>`` positional."""
    ctx = DispatchLaunchContext(
        dispatch_id="d-ultra-on",
        role="implementer",
        prompt="do the thing",
        working_directory=tmp_path,
        token="test-token",
        effort="xhigh",
        ultracode_enabled=True,
    )
    await _default_launcher(ctx)

    assert len(captured_subprocess_args) == 1
    args = captured_subprocess_args[0]
    assert args == (
        "claude",
        "--effort",
        "xhigh",
        "--settings",
        _ULTRACODE_JSON,
        "-p",
        "do the thing",
    ), f"ultracode-on argv drifted: got {args!r}"


async def test_dispatcher_launcher_settings_token_present_with_other_flags(
    captured_subprocess_args: list[tuple[Any, ...]],
    tmp_path: Path,
) -> None:
    """The ``--settings`` JSON token coexists with the other conditional
    flags (system prompt + allowed tools) and stays before ``-p``."""
    ctx = DispatchLaunchContext(
        dispatch_id="d-ultra-mixed",
        role="implementer",
        prompt="p",
        working_directory=tmp_path,
        token="test-token",
        effort="high",
        system_prompt="be disciplined",
        allowed_tools=["Bash(git *)", "Edit"],
        ultracode_enabled=True,
    )
    await _default_launcher(ctx)

    args = captured_subprocess_args[0]
    # The flag name + JSON string are adjacent.
    assert "--settings" in args
    settings_idx = args.index("--settings")
    assert args[settings_idx + 1] == _ULTRACODE_JSON
    # --settings precedes the -p positional.
    assert settings_idx < args.index("-p")
    # --effort precedes --settings (Option A ordering).
    assert args.index("--effort") < settings_idx


# ---------------------------------------------------------------------------
# Negative invariant: token absent when ultracode_enabled is False/default,
# and the OFF argv equals the no-ultracode baseline (subscription-only argv
# unchanged).
# ---------------------------------------------------------------------------


async def test_dispatcher_launcher_omits_settings_when_ultracode_off(
    captured_subprocess_args: list[tuple[Any, ...]],
    tmp_path: Path,
) -> None:
    """ultracode_enabled=False -> no ``--settings`` token; argv identical
    to the baseline that never knew about ultracode."""
    ctx = DispatchLaunchContext(
        dispatch_id="d-ultra-off",
        role="implementer",
        prompt="do the thing",
        working_directory=tmp_path,
        token="test-token",
        effort="xhigh",
        ultracode_enabled=False,
    )
    await _default_launcher(ctx)

    args = captured_subprocess_args[0]
    assert "--settings" not in args
    assert _ULTRACODE_JSON not in args
    assert args == (
        "claude",
        "--effort",
        "xhigh",
        "-p",
        "do the thing",
    ), f"ultracode-off argv must equal the subscription-only baseline: {args!r}"


async def test_dispatcher_launcher_default_is_ultracode_off(
    captured_subprocess_args: list[tuple[Any, ...]],
    tmp_path: Path,
) -> None:
    """The ultracode_enabled field defaults to False — a ctx constructed
    without it produces the no-settings baseline argv (fail-closed default
    keeps every existing caller / test fixture's argv unchanged)."""
    ctx = DispatchLaunchContext(
        dispatch_id="d-ultra-default",
        role="implementer",
        prompt="x",
        working_directory=tmp_path,
        token="test-token",
    )
    await _default_launcher(ctx)

    args = captured_subprocess_args[0]
    assert "--settings" not in args
    assert args == ("claude", "--effort", "high", "-p", "x")
