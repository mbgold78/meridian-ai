"""Unit tests for the F4 subprocess pipe drain wiring at both launchers.

Covers:
- Dispatcher launcher: ``stdout=PIPE`` when ``ctx.dispatches_root`` is
  set (β-S11-17.5, 2026-05-21); ``stdout=DEVNULL`` when no root
  (test-fixture-friendly degradation).
- Supervisor launcher: ``stdout=DEVNULL`` unconditionally per legacy
  F4 guardrail #1 (β-S11-17.5 scope excluded supervisors — the Slice 5
  cold-start hang was an Implementer/Tester surface; supervisor stdout
  capture is a separate change if/when needed).
- ``stderr=PIPE`` when ``ctx.dispatches_root`` is set; ``stderr=DEVNULL``
  otherwise. Symmetric at both launchers.
- Per-dispatch directory created before subprocess start per F4 guardrail #3.
- Dispatcher launcher returns ``DrainingSubprocess`` wrapper with both
  ``drain_task`` (stderr) and ``stdout_drain_task`` non-None when both
  drains are active; both ``None`` when no root.
- ``--debug-file`` is NOT added to claude argv per F4 guardrail #2.

The drain happy-path / cancellation / failure-isolation contracts are
tested in test_stderr_drain.py against the helpers directly. This file
verifies the launcher's wiring TO those helpers — kwargs, directory
creation, wrapper return shape.
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
from meridian_orchestrator.bridge.stderr_drain import DrainingSubprocess
from meridian_orchestrator.bridge.supervisor_base import (
    SupervisorLaunchContext,
)
from meridian_orchestrator.bridge.supervisor_base import (
    _default_launcher as supervisor_default_launcher,
)


@pytest.fixture
def captured_subprocess_kwargs(
    monkeypatch: pytest.MonkeyPatch,
) -> list[dict[str, Any]]:
    """Capture kwargs passed to asyncio.create_subprocess_exec without
    spawning. Returns a list mutated by the monkeypatched call. Sets
    ``proc.stderr = None`` on the fake to avoid the drain task awaiting
    on an AsyncMock attribute (drain task only spawns when stderr_path
    is non-None, but defensive in case of regression)."""
    captured: list[dict[str, Any]] = []

    async def fake_create(*args: Any, **kwargs: Any) -> AsyncMock:
        captured.append(
            {"args": args, **{k: v for k, v in kwargs.items()}}
        )
        proc = AsyncMock()
        proc.pid = 12345
        proc.returncode = None
        proc.stderr = None
        return proc

    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_create)
    return captured


# ---------------------------------------------------------------------------
# β-S11-17.5 + F4 stdout invariant
# ---------------------------------------------------------------------------


async def test_dispatcher_launcher_uses_pipe_stdout_when_dispatches_root_set(
    captured_subprocess_kwargs: list[dict[str, Any]],
    tmp_path: Path,
) -> None:
    """β-S11-17.5 (2026-05-21): dispatcher launcher pipes stdout to a
    per-dispatch ``stdout.log`` next to ``stderr.log`` so Claude CLI
    startup/MCP-handshake output is captured for forensic review.
    Pre-β-S11-17.5 stdout was DEVNULL — the Slice 5 cold-start hang
    blind spot. When ``dispatches_root`` is set (production main.py
    path), ``stdout=PIPE`` so the drain task can capture it.
    """
    ctx = DispatchLaunchContext(
        dispatch_id="d-stdout-test",
        role="implementer",
        prompt="x",
        working_directory=tmp_path,
        token="t",
        dispatches_root=tmp_path / "dispatches",
    )
    await dispatcher_default_launcher(ctx)

    assert (
        captured_subprocess_kwargs[0]["stdout"] == asyncio.subprocess.PIPE
    ), (
        "β-S11-17.5: dispatcher stdout MUST be PIPE'd when dispatches_root "
        "is set so the per-dispatch stdout.log drain task can capture "
        "Claude CLI output. Reverting to DEVNULL re-opens the Slice 5 "
        "forensic blind spot."
    )


async def test_dispatcher_launcher_uses_devnull_stdout_when_no_dispatches_root(
    captured_subprocess_kwargs: list[dict[str, Any]],
    tmp_path: Path,
) -> None:
    """β-S11-17.5 fallback: when ``dispatches_root`` is None (test-fixture
    shape with custom subprocess_launcher injection in callers that
    don't wire the path), stdout falls back to DEVNULL — same
    semantics as the legacy F4 stderr fallback. Preserves
    test-fixture-friendly degradation.
    """
    ctx = DispatchLaunchContext(
        dispatch_id="d-stdout-no-root",
        role="implementer",
        prompt="x",
        working_directory=tmp_path,
        token="t",
        dispatches_root=None,
    )
    await dispatcher_default_launcher(ctx)

    assert (
        captured_subprocess_kwargs[0]["stdout"] == asyncio.subprocess.DEVNULL
    )


async def test_supervisor_launcher_always_uses_devnull_stdout(
    captured_subprocess_kwargs: list[dict[str, Any]],
    tmp_path: Path,
) -> None:
    """Legacy F4 guardrail #1 — supervisor launcher unchanged by
    β-S11-17.5. Supervisors weren't in the Slice 5 incident scope; their
    stdout capture is a future change if/when an analogous diagnostic
    gap surfaces. Stays DEVNULL across both branches of dispatches_root."""
    ctx = SupervisorLaunchContext(
        dispatch_id="s-stdout-test",
        role="engineer-supervisor",
        prompt="x",
        working_directory=tmp_path,
        token="t",
        dispatches_root=tmp_path / "dispatches",
    )
    await supervisor_default_launcher(ctx)

    assert captured_subprocess_kwargs[0]["stdout"] == asyncio.subprocess.DEVNULL


# ---------------------------------------------------------------------------
# F4 stderr conditional behavior — PIPE when dispatches_root set
# ---------------------------------------------------------------------------


async def test_dispatcher_launcher_uses_pipe_stderr_when_dispatches_root_set(
    captured_subprocess_kwargs: list[dict[str, Any]],
    tmp_path: Path,
) -> None:
    """When dispatches_root is set (production main.py path), stderr=PIPE
    so the drain task can capture it to the per-dispatch log file."""
    ctx = DispatchLaunchContext(
        dispatch_id="d-pipe-test",
        role="implementer",
        prompt="x",
        working_directory=tmp_path,
        token="t",
        dispatches_root=tmp_path / "dispatches",
    )
    await dispatcher_default_launcher(ctx)

    assert captured_subprocess_kwargs[0]["stderr"] == asyncio.subprocess.PIPE


async def test_supervisor_launcher_uses_pipe_stderr_when_dispatches_root_set(
    captured_subprocess_kwargs: list[dict[str, Any]],
    tmp_path: Path,
) -> None:
    """Symmetric for supervisor_base launcher."""
    ctx = SupervisorLaunchContext(
        dispatch_id="s-pipe-test",
        role="engineer-supervisor",
        prompt="x",
        working_directory=tmp_path,
        token="t",
        dispatches_root=tmp_path / "dispatches",
    )
    await supervisor_default_launcher(ctx)

    assert captured_subprocess_kwargs[0]["stderr"] == asyncio.subprocess.PIPE


# ---------------------------------------------------------------------------
# F4 stderr fallback — DEVNULL when no dispatches_root
# ---------------------------------------------------------------------------


async def test_dispatcher_launcher_uses_devnull_stderr_when_no_dispatches_root(
    captured_subprocess_kwargs: list[dict[str, Any]],
    tmp_path: Path,
) -> None:
    """When dispatches_root is None (test fixtures with custom
    subprocess_launcher injection that don't wire the path), stderr also
    goes to DEVNULL — no drain task spawned. Backward-compat for the
    11 integration test fixtures per F4 disposition."""
    ctx = DispatchLaunchContext(
        dispatch_id="d-no-root-test",
        role="implementer",
        prompt="x",
        working_directory=tmp_path,
        token="t",
        # dispatches_root defaults to None; explicit for clarity
        dispatches_root=None,
    )
    await dispatcher_default_launcher(ctx)

    assert captured_subprocess_kwargs[0]["stderr"] == asyncio.subprocess.DEVNULL


async def test_supervisor_launcher_uses_devnull_stderr_when_no_dispatches_root(
    captured_subprocess_kwargs: list[dict[str, Any]],
    tmp_path: Path,
) -> None:
    """Symmetric for supervisor_base launcher."""
    ctx = SupervisorLaunchContext(
        dispatch_id="s-no-root-test",
        role="engineer-supervisor",
        prompt="x",
        working_directory=tmp_path,
        token="t",
        dispatches_root=None,
    )
    await supervisor_default_launcher(ctx)

    assert captured_subprocess_kwargs[0]["stderr"] == asyncio.subprocess.DEVNULL


# ---------------------------------------------------------------------------
# F4 guardrail #2: --debug-file NOT in argv
# ---------------------------------------------------------------------------


async def test_dispatcher_launcher_does_not_add_debug_file_flag(
    captured_subprocess_kwargs: list[dict[str, Any]],
    tmp_path: Path,
) -> None:
    """F4 guardrail #2: ``--debug-file`` is NOT added to the claude argv.
    It implies full debug mode (heavy log volume on every dispatch);
    wrong shape for default-on diagnostic capture. The per-dispatch
    stderr.log via stderr drain is the F4 mechanism instead."""
    ctx = DispatchLaunchContext(
        dispatch_id="d-no-debug-flag",
        role="implementer",
        prompt="x",
        working_directory=tmp_path,
        token="t",
        dispatches_root=tmp_path / "dispatches",
    )
    await dispatcher_default_launcher(ctx)

    args = captured_subprocess_kwargs[0]["args"]
    assert "--debug-file" not in args, (
        "--debug-file must NOT be in claude argv per F4 guardrail #2; "
        "it implies full debug mode which is too heavy for default-on. "
        "Per-dispatch stderr drain is the F4 diagnostic mechanism instead."
    )
    assert "--debug" not in args, (
        "--debug must NOT be enabled by default per F4 guardrail #2 spirit"
    )


async def test_supervisor_launcher_does_not_add_debug_file_flag(
    captured_subprocess_kwargs: list[dict[str, Any]],
    tmp_path: Path,
) -> None:
    """Symmetric for supervisor_base launcher."""
    ctx = SupervisorLaunchContext(
        dispatch_id="s-no-debug-flag",
        role="engineer-supervisor",
        prompt="x",
        working_directory=tmp_path,
        token="t",
        dispatches_root=tmp_path / "dispatches",
    )
    await supervisor_default_launcher(ctx)

    args = captured_subprocess_kwargs[0]["args"]
    assert "--debug-file" not in args
    assert "--debug" not in args


# ---------------------------------------------------------------------------
# F4 guardrail #3: directory created before subprocess start
# ---------------------------------------------------------------------------


async def test_dispatcher_launcher_creates_directory_before_subprocess_start(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    """F4 guardrail #3: ``<dispatches_root>/<dispatch_id>/`` MUST exist
    before create_subprocess_exec is called so the drain task can open
    its log file inside it. Verified by checking directory existence
    INSIDE the monkeypatched create_subprocess_exec — the assertion
    fires before the subprocess (would have) launched.
    """
    dispatches_root = tmp_path / "dispatches"
    dispatch_id = "d-dir-ordering"
    directory_existed_at_subprocess_start: list[bool] = []

    async def fake_create(*args: Any, **kwargs: Any) -> AsyncMock:
        directory_existed_at_subprocess_start.append(
            (dispatches_root / dispatch_id).is_dir()
        )
        proc = AsyncMock()
        proc.pid = 1
        proc.returncode = None
        proc.stderr = None
        return proc

    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_create)

    ctx = DispatchLaunchContext(
        dispatch_id=dispatch_id,
        role="implementer",
        prompt="x",
        working_directory=tmp_path,
        token="t",
        dispatches_root=dispatches_root,
    )
    await dispatcher_default_launcher(ctx)

    assert directory_existed_at_subprocess_start == [True], (
        "F4 guardrail #3: <dispatches_root>/<dispatch_id>/ must exist before "
        "subprocess starts. The launcher must call stderr_log_path (which "
        "mkdirs) before create_subprocess_exec, not after."
    )


# ---------------------------------------------------------------------------
# F4 wrapper return shape
# ---------------------------------------------------------------------------


async def test_dispatcher_launcher_returns_draining_subprocess_with_drain_task(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    """β-S11-17.5: when both stdout=PIPE and stderr=PIPE
    (dispatches_root set), the launcher spawns BOTH drain tasks and
    returns a DrainingSubprocess wrapper holding ``drain_task`` (stderr)
    AND ``stdout_drain_task``. Cleanup paths cancel both via
    ``cancel_drain_task``.
    """
    dispatches_root = tmp_path / "dispatches"

    async def fake_create(*args: Any, **kwargs: Any) -> AsyncMock:
        proc = AsyncMock()
        proc.pid = 1
        proc.returncode = None
        # Real-ish StreamReaders so the drain tasks start cleanly. Feed
        # EOF immediately so both drain tasks complete deterministically
        # without leaking pending coroutines into other tests.
        stderr_reader = asyncio.StreamReader()
        stderr_reader.feed_eof()
        proc.stderr = stderr_reader
        stdout_reader = asyncio.StreamReader()
        stdout_reader.feed_eof()
        proc.stdout = stdout_reader
        return proc

    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_create)

    ctx = DispatchLaunchContext(
        dispatch_id="d-wrap",
        role="implementer",
        prompt="x",
        working_directory=tmp_path,
        token="t",
        dispatches_root=dispatches_root,
    )
    handle = await dispatcher_default_launcher(ctx)

    assert isinstance(handle, DrainingSubprocess)
    assert handle.drain_task is not None
    assert handle.stdout_drain_task is not None, (
        "β-S11-17.5: stdout drain task MUST be spawned when "
        "dispatches_root is set; this carries the new stdout.log "
        "diagnostic surface"
    )
    # Let both drain tasks complete on EOF.
    await handle.drain_task
    await handle.stdout_drain_task
    assert handle.drain_task.done()
    assert handle.stdout_drain_task.done()


async def test_dispatcher_launcher_returns_draining_subprocess_with_no_drain_when_no_root(
    captured_subprocess_kwargs: list[dict[str, Any]],
    tmp_path: Path,
) -> None:
    """β-S11-17.5: when dispatches_root=None (no-drain path), the
    launcher still returns a DrainingSubprocess wrapper but with BOTH
    ``drain_task=None`` AND ``stdout_drain_task=None``.
    cancel_drain_task short-circuits cleanly on this case.
    """
    ctx = DispatchLaunchContext(
        dispatch_id="d-no-drain",
        role="implementer",
        prompt="x",
        working_directory=tmp_path,
        token="t",
        dispatches_root=None,
    )
    handle = await dispatcher_default_launcher(ctx)

    assert isinstance(handle, DrainingSubprocess)
    assert handle.drain_task is None
    assert handle.stdout_drain_task is None
