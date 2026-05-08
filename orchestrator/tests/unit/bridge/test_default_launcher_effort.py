"""Unit tests for `--effort <level>` plumbing through the default launchers
(M5-continuous-pm-driver commit 5 effort plumbing).

Both the dispatcher launcher (``bridge/dispatcher.py:_default_launcher``)
and the supervisor launcher (``bridge/supervisor_base.py:_default_launcher``)
invoke ``claude`` via ``asyncio.create_subprocess_exec``. Commit 5 adds
``--effort <level>`` before the ``-p <prompt>`` positional. These tests
monkeypatch the subprocess-creation call to capture args without actually
spawning ``claude``; verify the args list contains the expected
``--effort <level>`` flag at the correct position.

CLI flag literal verified via ``claude --help`` 2026-05-05:
``--effort <level>`` accepts low/medium/high/xhigh/max.
"""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock

import pytest

from meridian_orchestrator.bridge.dispatcher import (
    DispatchLaunchContext,
)
from meridian_orchestrator.bridge.dispatcher import (
    _default_launcher as dispatcher_default_launcher,
)
from meridian_orchestrator.bridge.supervisor_base import (
    SupervisorLaunchContext,
)
from meridian_orchestrator.bridge.supervisor_base import (
    _default_launcher as supervisor_default_launcher,
)


@pytest.fixture
def captured_subprocess_args(
    monkeypatch: pytest.MonkeyPatch,
) -> list[tuple[Any, ...]]:
    """Capture positional args to asyncio.create_subprocess_exec without
    actually spawning. Returns the captured args list (mutated by the
    monkeypatched call)."""
    captured: list[tuple[Any, ...]] = []

    async def fake_create(*args: Any, **kwargs: Any) -> AsyncMock:
        captured.append(args)
        # Return an AsyncMock that satisfies the SubprocessHandle /
        # SupervisorSubprocessHandle protocols (the launchers' return
        # type annotations).
        proc = AsyncMock()
        proc.pid = 12345
        proc.returncode = None
        return proc

    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_create)
    return captured


# ---------------------------------------------------------------------------
# Dispatcher launcher: --effort flag composition
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("effort", ["low", "medium", "high", "xhigh", "max"])
async def test_dispatcher_launcher_passes_effort_flag(
    captured_subprocess_args: list[tuple[Any, ...]],
    effort: str,
    tmp_path: Path,
) -> None:
    """For each effort value the Effort Literal admits, the dispatcher's
    _default_launcher composes a subprocess invocation with
    ``--effort <level>`` immediately before ``-p <prompt>``.

    Parametrized over all 5 values to verify the full closed-enum surface flows
    through the launcher unchanged."""
    ctx = DispatchLaunchContext(
        dispatch_id="d-effort-test",
        role="implementer",
        prompt="test prompt",
        working_directory=tmp_path,
        token="test-token",
        effort=effort,
    )
    await dispatcher_default_launcher(ctx)

    assert len(captured_subprocess_args) == 1
    args = captured_subprocess_args[0]
    # Expected argv: ("claude", "--effort", <level>, "-p", <prompt>)
    assert args == ("claude", "--effort", effort, "-p", "test prompt"), (
        f"dispatcher launcher argv drifted: got {args!r}; "
        "F6.2 Q5 verified order via `claude --help` 2026-05-05 — "
        "--effort flag must precede -p positional. CLI flag literal "
        "is `--effort <level>` not `--effort=<level>` etc."
    )


async def test_dispatcher_launcher_default_effort_is_high(
    captured_subprocess_args: list[tuple[Any, ...]],
    tmp_path: Path,
) -> None:
    """DispatchLaunchContext's effort field defaults to 'high' (matching
    Claude Code's CLI default per F6.2 Q5 verification). Ensures
    ctx-construction with no explicit effort doesn't crash and produces
    a reasonable subprocess invocation. Production paths always pass
    effort explicitly per F6.2 Q3 Reading A; this default is the
    test-fixture-friendliness shape."""
    ctx = DispatchLaunchContext(
        dispatch_id="d-default-test",
        role="implementer",
        prompt="x",
        working_directory=tmp_path,
        token="test-token",
    )
    await dispatcher_default_launcher(ctx)
    assert captured_subprocess_args[0] == (
        "claude",
        "--effort",
        "high",
        "-p",
        "x",
    )


# ---------------------------------------------------------------------------
# Supervisor launcher: --effort flag composition
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("effort", ["low", "medium", "high", "xhigh", "max"])
async def test_supervisor_launcher_passes_effort_flag(
    captured_subprocess_args: list[tuple[Any, ...]],
    effort: str,
    tmp_path: Path,
) -> None:
    """For each effort value the Effort Literal admits, the supervisor's
    _default_launcher composes a subprocess invocation with
    ``--effort <level>`` immediately before ``-p <prompt>``.

    Parametrized over all 5 values, symmetric with the dispatcher
    launcher test above. The two launchers share argv shape; verifying
    both independently catches drift if either changes without the other."""
    ctx = SupervisorLaunchContext(
        dispatch_id="s-effort-test",
        role="engineer-supervisor",
        prompt="supervisor prompt",
        working_directory=tmp_path,
        token="test-token",
        effort=effort,
    )
    await supervisor_default_launcher(ctx)

    assert len(captured_subprocess_args) == 1
    args = captured_subprocess_args[0]
    assert args == (
        "claude",
        "--effort",
        effort,
        "-p",
        "supervisor prompt",
    ), (
        f"supervisor launcher argv drifted: got {args!r}; "
        "F6.2 Q5 verified order via `claude --help` 2026-05-05. "
        "Argv shape must stay symmetric with dispatcher launcher."
    )
