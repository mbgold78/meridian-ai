"""HTTP route for tailing a dispatch's raw subprocess output (op-live-activity).

Powers ``GET /dispatches/{dispatch_id}/log?stream=stdout|stderr&lines=N`` so the
operator cockpit's live I/O console can surface the *raw* agent output the
daemon already drains to disk but never exposed.

Background: every dispatch subprocess has its stdout/stderr drained
continuously to ``<data_dir>/dispatches/<dispatch_id>/{stdout,stderr}.log`` by
:mod:`bridge.stderr_drain`. Nothing read those files back out — the cockpit
could see lifecycle *events* but not the actual subprocess chatter (Claude CLI
startup, MCP handshake, network-retry traces, uncaught crash tracebacks). This
route is the simplest read-only window onto that output: tail the last ``N``
lines of the requested stream.

Shape mirrors :func:`bridge.liveness_routes.make_liveness_routes` — returns
``(path, methods, handler)`` tuples to register via ``FastMCP.custom_route``.
Loopback-only enforcement comes from the daemon's ``TransportSecuritySettings``
configured in :mod:`server`; this module does not re-validate origin/host.

**Read-only + best-effort.** The handler never creates directories, never
writes, and never raises into the daemon — a missing dispatch dir, a stream
that was DEVNULL'd, or an unreadable file degrades to an empty / 404 response.
Path-traversal is blocked by rejecting any ``dispatch_id`` that is not a bare
path component.
"""

from __future__ import annotations

from collections import deque
from pathlib import Path
from typing import Any

import structlog
from starlette.requests import Request
from starlette.responses import JSONResponse, Response

from meridian_orchestrator.config import Settings

logger = structlog.get_logger(__name__)

DISPATCH_LOG_PATH = "/dispatches/{dispatch_id}/log"

#: Valid ``stream`` query values → on-disk filename.
_STREAM_FILES: dict[str, str] = {
    "stdout": "stdout.log",
    "stderr": "stderr.log",
}

#: Default + hard cap on the number of trailing lines returned. The cap bounds
#: response size for a runaway log; the console pages/polls for more if needed.
_DEFAULT_LINES = 200
_MAX_LINES = 5000


def _is_safe_component(value: str) -> bool:
    """True when ``value`` is a single, safe path component (no traversal).

    The dispatch_id is a daemon-minted UUID-ish token, never operator input in
    normal operation — but this route is reachable over HTTP, so reject
    anything containing a path separator, parent ref, or drive/root marker
    before it ever touches the filesystem.
    """
    if not value or value in (".", ".."):
        return False
    if "/" in value or "\\" in value or "\x00" in value:
        return False
    # Path() collapses a bare component to itself; anything else (absolute,
    # multi-part) is unsafe.
    return Path(value).name == value


def _tail_lines(path: Path, max_lines: int) -> tuple[list[str], int]:
    """Return ``(last_max_lines, total_lines)`` of ``path``.

    Reads the whole file then keeps a bounded tail. The drained per-dispatch
    logs are diagnostic-sized (CLI startup + MCP handshake + crash traces),
    not multi-GB, so a full read with a ``deque`` tail is the simplest correct
    approach; if these ever grow unbounded the BACKLOG note on stderr-log
    rotation covers the same surface. Decodes as UTF-8 with ``errors='replace'``
    so a partially-written multibyte line never raises.
    """
    total = 0
    tail: deque[str] = deque(maxlen=max_lines)
    with path.open("r", encoding="utf-8", errors="replace") as handle:
        for line in handle:
            total += 1
            tail.append(line.rstrip("\n"))
    return list(tail), total


def make_dispatch_log_routes(
    settings: Settings,
) -> list[tuple[str, list[str], Any]]:
    """Return ``(path, methods, handler)`` tuples for the dispatch-log tail API."""

    dispatches_root = settings.dispatches_root

    async def get_dispatch_log(request: Request) -> Response:
        dispatch_id = request.path_params.get("dispatch_id", "")
        if not _is_safe_component(dispatch_id):
            return JSONResponse(
                {"error": "invalid_dispatch_id", "dispatch_id": dispatch_id},
                status_code=400,
            )

        stream = request.query_params.get("stream", "stdout")
        filename = _STREAM_FILES.get(stream)
        if filename is None:
            return JSONResponse(
                {
                    "error": "invalid_stream",
                    "stream": stream,
                    "allowed": sorted(_STREAM_FILES),
                },
                status_code=400,
            )

        # Parse + clamp the requested line count. A non-int falls back to the
        # default rather than erroring — the console should keep working.
        raw_lines = request.query_params.get("lines")
        try:
            requested = int(raw_lines) if raw_lines is not None else _DEFAULT_LINES
        except ValueError:
            requested = _DEFAULT_LINES
        max_lines = max(1, min(requested, _MAX_LINES))

        log_path = dispatches_root / dispatch_id / filename
        if not log_path.is_file():
            # No file: dispatch never produced this stream (DEVNULL'd), the
            # dir was reaped, or an unknown dispatch_id. 404 with the stream
            # empty so the console renders "no output yet" rather than error.
            return JSONResponse(
                {
                    "dispatch_id": dispatch_id,
                    "stream": stream,
                    "exists": False,
                    "lines": [],
                    "total_lines": 0,
                },
                status_code=404,
            )

        try:
            lines, total = _tail_lines(log_path, max_lines)
        except OSError as exc:
            logger.warning(
                "dispatch_log.read_failed",
                dispatch_id=dispatch_id,
                stream=stream,
                error=str(exc),
            )
            return JSONResponse(
                {
                    "error": "read_failed",
                    "dispatch_id": dispatch_id,
                    "stream": stream,
                    "message": str(exc),
                },
                status_code=500,
            )

        return JSONResponse(
            {
                "dispatch_id": dispatch_id,
                "stream": stream,
                "exists": True,
                "lines": lines,
                "returned_lines": len(lines),
                "total_lines": total,
                "truncated": total > len(lines),
            }
        )

    return [
        (DISPATCH_LOG_PATH, ["GET"], get_dispatch_log),
    ]


__all__ = ["DISPATCH_LOG_PATH", "make_dispatch_log_routes"]
