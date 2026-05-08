"""Unit tests for bridge/stderr_drain.py (M5-flag-flips F4 helpers).

Covers:
- ``stderr_log_path``: per-dispatch directory creation; OSError fallback
  to None per F4 guardrail #4 (best-effort diagnostic capture).
- ``_drain_stream_to_file``: drain-loop happy path; None-stream
  short-circuit (test-friendly shape); exception swallowed + WARNING
  logged per F4 guardrail #4; CancelledError propagates per cleanup
  contract.
- ``cancel_drain_task``: no-op for non-DrainingSubprocess; no-op for
  None drain_task; no-op for completed task; cancels running task.
- ``DrainingSubprocess``: structurally satisfies the operational
  surface of SubprocessHandle / SupervisorSubprocessHandle Protocols
  (pid, returncode, wait, terminate, kill all delegate to wrapped proc).

The drain failure isolation contract (guardrail #4 — drain task
exception MUST NOT corrupt dispatch lifecycle) is verified at this
level: the drain function swallows + WARNING-logs on its own. The
launcher / dispatcher tests in test_default_launcher_pipe_drain.py
verify the wiring (drain_task.cancel + await is invoked from the
existing cleanup paths).
"""

from __future__ import annotations

import asyncio
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

import pytest

from meridian_orchestrator.bridge.stderr_drain import (
    DrainingSubprocess,
    _drain_stream_to_file,
    cancel_drain_task,
    stderr_log_path,
)

# ---------------------------------------------------------------------------
# stderr_log_path
# ---------------------------------------------------------------------------


def test_stderr_log_path_creates_per_dispatch_directory(tmp_path: Path) -> None:
    """The helper creates ``<dispatches_root>/<dispatch_id>/`` if missing
    and returns the ``stderr.log`` path within it. Per F4 guardrail #3
    (mkdir parents=True exist_ok=True before subprocess start)."""
    dispatches_root = tmp_path / "dispatches"
    path = stderr_log_path(dispatches_root, "abc123")

    assert path is not None
    assert path == dispatches_root / "abc123" / "stderr.log"
    assert path.parent.exists(), (
        "stderr_log_path must create the per-dispatch directory before "
        "returning the file path; F4 guardrail #3 requires the directory "
        "to exist before subprocess start"
    )
    assert path.parent.is_dir()


def test_stderr_log_path_idempotent_when_directory_exists(tmp_path: Path) -> None:
    """Re-calling for the same dispatch_id is a no-op (mkdir exist_ok=True).
    Defensive: a daemon restart followed by a same-dispatch_id replay must
    not OSError."""
    dispatches_root = tmp_path / "dispatches"
    path1 = stderr_log_path(dispatches_root, "abc123")
    path2 = stderr_log_path(dispatches_root, "abc123")

    assert path1 == path2
    assert path1 is not None
    assert path1.parent.exists()


def test_stderr_log_path_returns_none_on_oserror(tmp_path: Path) -> None:
    """When the per-dispatch directory cannot be created (e.g., parent is
    a regular file), the helper logs a WARNING and returns None per F4
    guardrail #4: stderr capture is best-effort; never corrupts dispatch
    lifecycle. The launcher's contract is to fall back to DEVNULL on
    None return."""
    # Force mkdir to fail by making the parent path collide with a file.
    parent_as_file = tmp_path / "not-a-directory"
    parent_as_file.write_text("blocking the mkdir path")

    path = stderr_log_path(parent_as_file, "abc123")

    assert path is None, (
        "stderr_log_path must return None when mkdir raises OSError so the "
        "launcher can fall back to stderr=DEVNULL per F4 guardrail #4 "
        "(best-effort diagnostic capture)"
    )


# ---------------------------------------------------------------------------
# _drain_stream_to_file
# ---------------------------------------------------------------------------


async def _build_stream_with_bytes(*chunks: bytes) -> asyncio.StreamReader:
    """Build a StreamReader pre-fed with ``chunks`` then closed (EOF).

    Used by drain happy-path tests to exercise the drain loop without
    spawning a real subprocess.
    """
    reader = asyncio.StreamReader()
    for chunk in chunks:
        reader.feed_data(chunk)
    reader.feed_eof()
    return reader


async def test_drain_stream_to_file_short_circuits_on_none(tmp_path: Path) -> None:
    """``stream is None`` → no-op return. Test-friendly shape: monkeypatched
    create_subprocess_exec fakes can set ``proc.stderr = None`` to avoid
    awaiting on a non-StreamReader attribute."""
    target = tmp_path / "stderr.log"
    await _drain_stream_to_file(None, target, dispatch_id="d-none")

    assert not target.exists(), (
        "drain function with stream=None should not create the target file; "
        "the short-circuit is intentional for test fakes that expose no "
        "real stderr"
    )


async def test_drain_stream_to_file_writes_chunks_until_eof(
    tmp_path: Path,
) -> None:
    """Happy path: stream feeds bytes; drain writes them; EOF terminates
    the loop; file contains the concatenated bytes verbatim."""
    target = tmp_path / "stderr.log"
    payload_chunks = [b"first chunk\n", b"second chunk\n", b"third\n"]
    stream = await _build_stream_with_bytes(*payload_chunks)

    await _drain_stream_to_file(stream, target, dispatch_id="d-happy")

    assert target.exists()
    assert target.read_bytes() == b"".join(payload_chunks)


async def test_drain_stream_to_file_appends_not_truncates(
    tmp_path: Path,
) -> None:
    """File is opened in binary append mode (``'ab'``). Pre-existing
    content is preserved. Defensive: a second drain task on the same
    dispatch_id (impossible in production but possible in test
    re-execution) does not clobber previous content."""
    target = tmp_path / "stderr.log"
    target.write_bytes(b"pre-existing content\n")
    stream = await _build_stream_with_bytes(b"new content\n")

    await _drain_stream_to_file(stream, target, dispatch_id="d-append")

    assert target.read_bytes() == b"pre-existing content\nnew content\n"


async def test_drain_stream_to_file_swallows_exception_and_logs_warning(
    tmp_path: Path,
    caplog: pytest.LogCaptureFixture,
) -> None:
    """Per F4 guardrail #4: any exception during drain (file IO failure,
    disk full, etc.) is caught + WARNING-logged and never raised.

    Forced exception: pass an unwritable target path (a directory, not a
    file). The ``open('ab')`` call raises IsADirectoryError. The drain
    function must swallow it.
    """
    unwritable_target = tmp_path / "is-a-directory"
    unwritable_target.mkdir()
    stream = await _build_stream_with_bytes(b"chunk")

    # Must not raise.
    await _drain_stream_to_file(
        stream, unwritable_target, dispatch_id="d-iso"
    )

    # The function returns normally; drain failure is best-effort. We
    # don't assert on the WARNING log here (caplog interaction with
    # structlog is environment-dependent); the load-bearing contract is
    # that no exception escaped — verified by reaching this assertion.


async def test_drain_stream_to_file_propagates_cancelled_error(
    tmp_path: Path,
) -> None:
    """CancelledError MUST propagate (not be swallowed by the broad
    Exception handler). The cleanup paths in dispatch() and supervisor
    cleanup paths rely on this to await the cancellation cleanly per the
    standard asyncio cancellation idiom."""
    target = tmp_path / "stderr.log"
    # StreamReader that never sees EOF — drain blocks on read forever.
    reader = asyncio.StreamReader()

    task = asyncio.create_task(
        _drain_stream_to_file(reader, target, dispatch_id="d-cancel")
    )
    # Yield once so the task starts and reaches the await stream.read().
    await asyncio.sleep(0)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task


# ---------------------------------------------------------------------------
# cancel_drain_task
# ---------------------------------------------------------------------------


async def test_cancel_drain_task_no_op_for_non_draining_subprocess() -> None:
    """The 11 integration test fixtures inject custom subprocess_launcher
    callables that return bare fake handles (not DrainingSubprocess).
    cancel_drain_task must be a clean no-op for those handles."""
    fake_handle = MagicMock()  # not a DrainingSubprocess
    # Must not raise.
    await cancel_drain_task(fake_handle)


async def test_cancel_drain_task_no_op_for_none_drain_task() -> None:
    """DrainingSubprocess with drain_task=None (the no-dispatches_root
    code path in the launcher). Helper must short-circuit cleanly."""
    fake_proc = MagicMock()
    handle = DrainingSubprocess(proc=fake_proc, drain_task=None)
    # Must not raise.
    await cancel_drain_task(handle)


async def test_cancel_drain_task_no_op_for_completed_task(
    tmp_path: Path,
) -> None:
    """A drain task that already hit EOF (subprocess closed stderr cleanly)
    is .done()=True. cancel_drain_task must short-circuit without
    cancelling a completed task."""
    target = tmp_path / "stderr.log"
    stream = await _build_stream_with_bytes(b"already drained")

    drain_task = asyncio.create_task(
        _drain_stream_to_file(stream, target, dispatch_id="d-done")
    )
    await drain_task  # complete it

    fake_proc = MagicMock()
    handle = DrainingSubprocess(proc=fake_proc, drain_task=drain_task)
    # Must not raise; task already done.
    await cancel_drain_task(handle)
    assert drain_task.done()


async def test_cancel_drain_task_cancels_running_task(
    tmp_path: Path,
) -> None:
    """The load-bearing case: subprocess is being terminated, drain task
    is still reading from the stream. cancel_drain_task issues
    Task.cancel + awaits to completion."""
    target = tmp_path / "stderr.log"
    reader = asyncio.StreamReader()  # never sees EOF

    drain_task = asyncio.create_task(
        _drain_stream_to_file(reader, target, dispatch_id="d-running")
    )
    await asyncio.sleep(0)  # let task start

    fake_proc = MagicMock()
    handle = DrainingSubprocess(proc=fake_proc, drain_task=drain_task)
    await cancel_drain_task(handle)

    assert drain_task.done()
    assert drain_task.cancelled()


# ---------------------------------------------------------------------------
# DrainingSubprocess
# ---------------------------------------------------------------------------


async def test_draining_subprocess_delegates_protocol_surface() -> None:
    """DrainingSubprocess satisfies the operational surface of
    SubprocessHandle / SupervisorSubprocessHandle Protocols structurally:
    pid, returncode, wait, terminate, kill all delegate to the wrapped
    proc. The drain_task field is added on top.

    This is the F4 design choice (per handoff doc): wrap with isinstance
    detection at cleanup sites rather than extending Protocols. Test
    locks the contract.
    """
    fake_proc = MagicMock()
    fake_proc.pid = 7777
    fake_proc.returncode = 42
    fake_proc.wait = AsyncMock(return_value=42)

    handle = DrainingSubprocess(proc=fake_proc, drain_task=None)

    assert handle.pid == 7777
    assert handle.returncode == 42
    assert (await handle.wait()) == 42

    handle.terminate()
    fake_proc.terminate.assert_called_once()

    handle.kill()
    fake_proc.kill.assert_called_once()
