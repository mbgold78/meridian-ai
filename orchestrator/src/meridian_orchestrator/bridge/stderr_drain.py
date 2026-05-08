"""Per-dispatch stderr drain task for subprocess launchers (M5-flag-flips F4).

Both ``bridge/dispatcher.py:_default_launcher`` and
``bridge/supervisor_base.py:_default_launcher`` use the same drain pattern:
``stdout=DEVNULL`` (operationally noise per the ``complete_dispatch``
architectural choice in ``11-engineer-bridge.md`` § Long-running
supervisors), ``stderr=PIPE`` captured continuously to a per-dispatch log
file under ``<data_dir>/dispatches/<dispatch_id>/stderr.log``.

The module exists to fix Codex Finding 4 (PIPE without drain → OS buffer
fills → child blocks; F3 timeout watchdog catches the hang as a timeout).
Without F4, the engineer-supervisor flag flip at M5-flag-flips commit 5
would activate the same vulnerability for every dispatch that produces
stderr output.

Design choices (per F4 disposition, M5-flag-flips commit 3):

- **Drain failures are best-effort.** File IO errors, disk full, permission
  denied — all caught + WARNING-logged, never propagated to dispatch
  lifecycle. Dispatch row state and audit chain unaffected by drain failures.
  The capture is diagnostic support; losing diagnostics never breaks the
  primary lifecycle.
- **Cancellation lives in existing cleanup paths.** ``BridgeDispatcher.dispatch()``
  finally clause + ``LongRunningSupervisor``'s shutdown / heartbeat-timeout /
  unexpected-exit / compaction-completed paths each call
  :func:`cancel_drain_task` after subprocess termination. No parallel
  cleanup lifecycle.
- **No Protocol extension.** :class:`DrainingSubprocess` is a concrete
  dataclass wrapper. The existing ``SubprocessHandle`` /
  ``SupervisorSubprocessHandle`` Protocols stay clean (just the operational
  surface: pid, returncode, wait, terminate, kill). Cleanup paths use
  ``isinstance(proc, DrainingSubprocess)`` to detect drain task presence
  + cancel. Test fakes (custom ``subprocess_launcher`` injections across
  11 integration test files) don't have to implement a new Protocol field.

See ``BACKLOG.md`` Codex Finding 4 + the F4 disposition in M5-flag-flips
commit 3 handoff doc for the architectural decision (Reading C: drain
stderr to per-dispatch file, DEVNULL stdout).
"""

from __future__ import annotations

import asyncio
import contextlib
from dataclasses import dataclass
from pathlib import Path

import structlog

logger = structlog.get_logger(__name__)

_DRAIN_CHUNK_SIZE = 4096


def stderr_log_path(dispatches_root: Path, dispatch_id: str) -> Path | None:
    """Compute and create the per-dispatch stderr log file path.

    Returns the path on success; returns ``None`` and emits a structlog
    WARNING on :class:`OSError` (disk full, permission denied,
    parent-not-writable, etc.).

    The ``None`` return path implements F4 guardrail #4: stderr capture is
    best-effort diagnostic support; if the directory cannot be created the
    launcher falls back to ``stderr=DEVNULL`` rather than failing the
    dispatch. Operational consequence: a misconfigured ``data_dir`` or full
    disk degrades to no-stderr-capture instead of unable-to-dispatch.

    The directory layout is ``<dispatches_root>/<dispatch_id>/`` per the F4
    destination decision (operator-friendly: inspectable via ``ls``,
    grep-able via standard tools, no new MCP query surface).
    ``mkdir(parents=True, exist_ok=True)`` is the standard idempotent shape
    per F4 guardrail #3.
    """
    parent = dispatches_root / dispatch_id
    try:
        parent.mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        logger.warning(
            "dispatch.stderr_log_dir_create_failed",
            dispatch_id=dispatch_id,
            path=str(parent),
            error=str(exc),
        )
        return None
    return parent / "stderr.log"


async def _drain_stream_to_file(
    stream: asyncio.StreamReader | None,
    path: Path,
    *,
    dispatch_id: str,
) -> None:
    """Copy bytes from ``stream`` to ``path`` until EOF or cancellation.

    Best-effort per F4 guardrail #4: any exception from file IO or stream
    read is logged at WARNING and swallowed so the dispatch lifecycle is
    unaffected. :class:`asyncio.CancelledError` propagates so the cleanup
    path can :func:`asyncio.Task.cancel` and ``await`` to completion.

    ``stream is None`` short-circuits to no-op. This is the test-friendly
    shape: monkeypatched ``asyncio.create_subprocess_exec`` fakes can set
    ``proc.stderr = None`` to avoid the drain task awaiting a
    non-StreamReader attribute. Production paths always pass a real
    :class:`asyncio.StreamReader` because the launcher only spawns this
    drain task when ``stderr=PIPE`` was set on the subprocess.
    """
    if stream is None:
        return
    try:
        with path.open("ab") as f:
            while True:
                chunk = await stream.read(_DRAIN_CHUNK_SIZE)
                if not chunk:
                    return
                f.write(chunk)
                f.flush()
    except asyncio.CancelledError:
        raise
    except Exception as exc:
        logger.warning(
            "dispatch.stderr_drain_failed",
            dispatch_id=dispatch_id,
            path=str(path),
            error=str(exc),
        )


@dataclass
class DrainingSubprocess:
    """Wraps an :class:`asyncio.subprocess.Process` with its stderr drain task.

    Satisfies the ``SubprocessHandle`` / ``SupervisorSubprocessHandle``
    Protocols structurally (pid, returncode, wait, terminate, kill) AND
    exposes ``drain_task`` for cancellation in existing cleanup paths.

    Production launchers return this. Test launchers (injected via
    ``subprocess_launcher`` constructor parameter) return whatever shape
    they want — :func:`cancel_drain_task` uses :func:`isinstance` to
    detect this concrete class and short-circuits for non-draining handles.
    """

    proc: asyncio.subprocess.Process
    drain_task: asyncio.Task[None] | None

    @property
    def pid(self) -> int | None:
        return self.proc.pid

    @property
    def returncode(self) -> int | None:
        return self.proc.returncode

    async def wait(self) -> int:
        return await self.proc.wait()

    def terminate(self) -> None:
        self.proc.terminate()

    def kill(self) -> None:
        self.proc.kill()


async def cancel_drain_task(handle: object) -> None:
    """Cancel the stderr drain task associated with ``handle``, if any.

    Idempotent: a non-:class:`DrainingSubprocess` handle, a
    :class:`DrainingSubprocess` with ``drain_task=None``, or an
    already-completed task all return immediately. Suppresses
    :class:`asyncio.CancelledError` from awaiting the cancelled task per
    the standard asyncio cancellation idiom.

    Called from ``BridgeDispatcher.dispatch()``'s finally clause and from
    ``LongRunningSupervisor``'s cleanup paths (shutdown,
    heartbeat-timeout, unexpected-exit, compaction-completed) per F4
    guardrail #5 (drain task cleanup lives in existing cleanup anchors,
    not parallel lifecycle).

    The :func:`isinstance` check protects against test fakes that don't
    use the wrapper — the existing 11 integration tests construct
    ``BridgeDispatcher`` / supervisors with custom ``subprocess_launcher``
    callables that return bare fake handles. Those paths see this helper
    as a no-op.
    """
    if not isinstance(handle, DrainingSubprocess):
        return
    drain_task = handle.drain_task
    if drain_task is None or drain_task.done():
        return
    drain_task.cancel()
    with contextlib.suppress(asyncio.CancelledError):
        await drain_task
