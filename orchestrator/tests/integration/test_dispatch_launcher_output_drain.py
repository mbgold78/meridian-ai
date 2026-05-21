"""β-S11-17.5 — subprocess output capture (stdout + stderr) integration tests.

The Slice 5 cold-start hang diagnostic (2026-05-21) found that the
default launcher discarded stdout via ``stdout=DEVNULL``. Both failed
dispatches had 0-byte stderr.log files and no captured stdout, so the
Claude CLI startup signal (banner, MCP handshake trace, network retry
chatter) was unrecoverable. β-S11-17.5 promotes stdout to ``PIPE`` and
drains it to a per-dispatch ``stdout.log`` next to ``stderr.log``,
mirroring the existing M5-flag-flips F4 stderr drain.

These tests exercise the REAL ``_default_launcher`` against a tiny,
host-portable subprocess (``python -c "..."``) that writes to both
streams. They verify:

  - both ``stdout.log`` and ``stderr.log`` are created in
    ``<dispatches_root>/<dispatch_id>/``
  - both files contain the bytes the subprocess wrote to their
    respective streams (no interleaving, no swap)
  - the legacy stderr-drain behavior is intact (assertion required by
    the β-S11-17.5 implementation brief)
  - the ``DrainingSubprocess`` wrapper exposes both drain tasks and
    ``cancel_drain_task`` cancels both cleanly

The launcher is invoked directly (not through ``BridgeDispatcher``)
because the dispatcher harness depends on canonical clones and the
full lifecycle; this module isolates the drain wiring under test.

Skipped if ``python`` is not on PATH on the host.
"""

from __future__ import annotations

import asyncio
import shutil
import sys
from pathlib import Path

import pytest

from meridian_orchestrator.bridge.dispatcher import (
    DispatchLaunchContext,
    _default_launcher,
)
from meridian_orchestrator.bridge.stderr_drain import (
    DrainingSubprocess,
    cancel_drain_task,
    stderr_log_path,
    stdout_log_path,
)

# We use a python -c subprocess for cross-platform availability + no
# external dependencies. The launcher's argv hard-codes "claude" as
# argv[0]; for this isolated drain test we monkey-patch
# ``asyncio.create_subprocess_exec`` to swap argv[0] to sys.executable
# so the host doesn't need the Claude CLI on PATH.
pytestmark = pytest.mark.skipif(
    shutil.which("python") is None and not Path(sys.executable).exists(),
    reason="python interpreter not available for launcher subprocess test",
)


# ---------------------------------------------------------------------------
# stdout_log_path helper unit test
# ---------------------------------------------------------------------------


def test_stdout_log_path_creates_directory(tmp_path: Path) -> None:
    """``stdout_log_path`` returns the per-dispatch path AND ensures the
    parent directory exists. Mirrors :func:`stderr_log_path` semantics.
    """
    dispatches_root = tmp_path / "dispatches"
    dispatch_id = "abc123"

    result = stdout_log_path(dispatches_root, dispatch_id)
    assert result is not None
    assert result == dispatches_root / dispatch_id / "stdout.log"
    assert result.parent.is_dir(), (
        "stdout_log_path must create <dispatches_root>/<dispatch_id>/ "
        "idempotently — mirrors F4 guardrail #3"
    )

    # Re-invocation is idempotent.
    result2 = stdout_log_path(dispatches_root, dispatch_id)
    assert result2 == result

    # Shared parent dir with stderr.log — calling both is no-op.
    stderr_result = stderr_log_path(dispatches_root, dispatch_id)
    assert stderr_result is not None
    assert stderr_result.parent == result.parent


# ---------------------------------------------------------------------------
# Required behavioral assertion 3:
#   stdout log is created and content captured.
# Required behavioral assertion 4:
#   stderr behavior remains intact (parallel assertion in the same test).
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_launcher_drains_both_stdout_and_stderr_to_per_dispatch_files(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """β-S11-17.5 §3 + §4: the launcher must spawn the subprocess with
    BOTH stdout=PIPE and stderr=PIPE, and drain each to its own
    per-dispatch log file. Pre-β-S11-17.5 stdout was DEVNULL — the
    Slice 5 forensic blind spot.

    Mechanism:
      1. Construct a ``DispatchLaunchContext`` with a real
         ``dispatches_root`` (production wiring shape) and a benign
         prompt.
      2. Monkey-patch ``asyncio.create_subprocess_exec`` to swap argv[0]
         from ``"claude"`` to ``sys.executable`` so the test doesn't
         require Claude CLI on PATH, but preserves the rest of the argv
         (specifically the ``-p <prompt>`` positional) AND the kwargs
         the launcher passes (``stdout=PIPE`` and ``stderr=PIPE``).
      3. Rewrite the prompt to a python one-liner that emits known
         bytes to stdout and stderr.
      4. Await the launcher; await the subprocess exit; cancel the
         drain tasks via the production cancel path.
      5. Assert both log files exist AND contain exactly the bytes
         each stream emitted.
    """
    dispatch_id = "test-launcher-drain"
    dispatches_root = tmp_path / "dispatches"
    worktree = tmp_path / "worktree"
    worktree.mkdir()

    # Python one-liner that writes to stdout AND stderr, then exits 0.
    # The launcher's argv shape ends with ``-p <prompt>`` so this
    # becomes the ``-p`` positional — but we override argv[0] below so
    # the script runs as ``python -c <prompt>`` instead of
    # ``claude -p <prompt>``. The two emitted byte sequences are
    # distinct so a swap would surface.
    expected_stdout = "hello_stdout_world\n"
    expected_stderr = "hello_stderr_world\n"
    test_prompt = (
        f"import sys; "
        f"sys.stdout.write({expected_stdout!r}); sys.stdout.flush(); "
        f"sys.stderr.write({expected_stderr!r}); sys.stderr.flush()"
    )

    ctx = DispatchLaunchContext(
        dispatch_id=dispatch_id,
        role="implementer",
        prompt=test_prompt,
        working_directory=worktree,
        token="test-token",
        effort="high",
        # Force empty system_prompt + allowed_tools so the launcher's
        # conditional argv-extension blocks are skipped and the final
        # argv is exactly ``[argv0, "--effort", "high", "-p", prompt]``.
        # Then we swap argv0 → sys.executable + "-c" via the
        # monkeypatch below.
        system_prompt="",
        allowed_tools=[],
        env_extras={},
        dispatches_root=dispatches_root,
        mcp_config_path=None,
    )

    original_create = asyncio.create_subprocess_exec

    async def _swap_argv0_to_python(*argv: str, **kwargs: object) -> object:
        # argv[0] == "claude" by the launcher's hardcode; replace with
        # ``[sys.executable, "-c"]`` and discard the launcher's
        # ``--effort high`` flags + the ``-p`` positional convention —
        # we keep the final positional (the prompt) but reshape the
        # invocation to ``python -c <prompt>``.
        assert argv[0] == "claude"
        assert argv[-2] == "-p"
        new_argv = (sys.executable, "-c", argv[-1])
        return await original_create(*new_argv, **kwargs)

    monkeypatch.setattr(
        "asyncio.create_subprocess_exec", _swap_argv0_to_python
    )

    handle = await _default_launcher(ctx)
    assert isinstance(handle, DrainingSubprocess)

    # Subprocess exits on its own (one-line script). Wait for exit so
    # the drain tasks observe EOF before we cancel them.
    rc = await handle.wait()
    assert rc == 0, f"test subprocess exited rc={rc}"

    # Give the drain tasks a moment to flush the final bytes to disk
    # after EOF — drain coroutine returns from `stream.read()` with
    # zero bytes on EOF and then exits.
    await asyncio.sleep(0.1)
    await cancel_drain_task(handle)

    stdout_file = dispatches_root / dispatch_id / "stdout.log"
    stderr_file = dispatches_root / dispatch_id / "stderr.log"

    assert stdout_file.is_file(), (
        f"β-S11-17.5: per-dispatch stdout.log MUST be created at "
        f"{stdout_file} — this closes the Slice 5 forensic blind spot"
    )
    assert stderr_file.is_file(), (
        f"M5-flag-flips F4 + β-S11-17.5: per-dispatch stderr.log MUST "
        f"continue to exist alongside the new stdout.log; got missing "
        f"{stderr_file}"
    )

    captured_stdout = stdout_file.read_text(encoding="utf-8")
    captured_stderr = stderr_file.read_text(encoding="utf-8")
    assert captured_stdout == expected_stdout, (
        f"β-S11-17.5 §3: stdout content must be preserved verbatim in "
        f"stdout.log; got {captured_stdout!r}, expected {expected_stdout!r}"
    )
    assert captured_stderr == expected_stderr, (
        f"β-S11-17.5 §4: stderr behavior must remain intact in "
        f"stderr.log (no swap, no truncation); got {captured_stderr!r}, "
        f"expected {expected_stderr!r}"
    )

    # Both drain tasks should now be done — production cancel path is
    # a no-op on an already-completed task. Re-invocation is
    # idempotent.
    assert handle.drain_task is not None
    assert handle.stdout_drain_task is not None
    assert handle.drain_task.done()
    assert handle.stdout_drain_task.done()
    await cancel_drain_task(handle)  # idempotent second call


@pytest.mark.asyncio
async def test_launcher_handles_dispatches_root_none_with_devnull_fallback(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """When ``ctx.dispatches_root is None`` (test-fixture shape with
    custom subprocess_launcher injection in upstream callers), the
    launcher must still produce a working DrainingSubprocess — with
    BOTH drain tasks set to None and both streams routed to DEVNULL.

    This protects the 11+ integration test fixtures that construct
    ``BridgeDispatcher`` with custom launchers and expect the
    drain-task field to be optional. β-S11-17.5 adds a second optional
    field; this test pins both fields are None in the fallback path.
    """
    worktree = tmp_path / "worktree"
    worktree.mkdir()

    ctx = DispatchLaunchContext(
        dispatch_id="no-root-dispatch",
        role="implementer",
        prompt="import sys; sys.exit(0)",
        working_directory=worktree,
        token="t",
        effort="high",
        system_prompt="",
        allowed_tools=[],
        env_extras={},
        dispatches_root=None,  # critical: triggers DEVNULL fallback
        mcp_config_path=None,
    )

    original_create = asyncio.create_subprocess_exec

    async def _swap_argv0_to_python(*argv: str, **kwargs: object) -> object:
        assert argv[0] == "claude"
        new_argv = (sys.executable, "-c", argv[-1])
        # Verify the fallback path passes DEVNULL for both streams.
        assert kwargs.get("stdout") == asyncio.subprocess.DEVNULL, (
            "ctx.dispatches_root=None must route stdout to DEVNULL — "
            "test-fixture-compatible no-drain fallback"
        )
        assert kwargs.get("stderr") == asyncio.subprocess.DEVNULL, (
            "ctx.dispatches_root=None must route stderr to DEVNULL — "
            "preserves M5-flag-flips F4 fallback semantics"
        )
        return await original_create(*new_argv, **kwargs)

    monkeypatch.setattr(
        "asyncio.create_subprocess_exec", _swap_argv0_to_python
    )

    handle = await _default_launcher(ctx)
    assert isinstance(handle, DrainingSubprocess)
    assert handle.drain_task is None
    assert handle.stdout_drain_task is None

    rc = await handle.wait()
    assert rc == 0
    await cancel_drain_task(handle)  # no-op on the None-drain shape
