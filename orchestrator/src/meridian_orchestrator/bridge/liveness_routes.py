"""HTTP routes for the AutonomyLivenessWatchdog read API.

Powers ``GET /api/liveness`` consumed by orchestrator-ui's Walk-Away
bar and Human Action Inbox. Returns the active (unresolved) liveness
violations with enough metadata for the UI to render severity,
project, task, dispatch, age, last observed event, and recommended
action.

Operator Fix Window 2026-05-26. Symmetric in shape to
:func:`oauth.make_approval_routes` — returns ``(path, methods,
handler)`` tuples to register via ``FastMCP.custom_route``.

Loopback-only enforcement comes from the daemon's
``TransportSecuritySettings`` configured in :mod:`server`; this module
does not re-validate origin/host.
"""

from __future__ import annotations

from datetime import UTC, datetime
from typing import Any

from starlette.requests import Request
from starlette.responses import JSONResponse, Response

from meridian_orchestrator.bridge.autonomy_liveness_watchdog import (
    fetch_active_violations,
)
from meridian_orchestrator.storage.interface import Storage

LIVENESS_PATH = "/api/liveness"


def make_liveness_routes(
    storage: Storage,
) -> list[tuple[str, list[str], Any]]:
    """Return ``(path, methods, handler)`` tuples for the liveness API."""

    async def get_liveness(_request: Request) -> Response:
        try:
            violations = await fetch_active_violations(storage)
        except Exception as exc:
            return JSONResponse(
                {
                    "error": "fetch_failed",
                    "message": str(exc),
                    "type": type(exc).__name__,
                },
                status_code=500,
            )
        return JSONResponse(
            {
                "server_time": datetime.now(tz=UTC).isoformat(timespec="seconds"),
                "violations": violations,
                "count": len(violations),
            }
        )

    return [
        (LIVENESS_PATH, ["GET"], get_liveness),
    ]


__all__ = ["LIVENESS_PATH", "make_liveness_routes"]
