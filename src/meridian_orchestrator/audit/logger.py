"""Audit log writer.

Every state-changing tool and every load-bearing bridge lifecycle event writes
to ``audit_log`` via :func:`write_audit`. The chain from ``task.submit`` →
``task.claim`` → ``dispatch.issued`` → ``dispatch.subprocess_started`` →
``dispatch.complete_called`` → ``dispatch.subprocess_terminated`` →
``task.status_change`` is the load-bearing trail Mike inspects via
``get_audit_log`` and Engineer inspects via ``get_dispatch_events``.

The writer is intentionally minimal at M4a — write a row, return the id.
Higher-level audit-event-construction helpers (per-action input shapes for
``dispatch.issued``, ``dispatch.complete_called``, etc.) are expected to live
alongside the call sites that emit them; centralising them here would couple
the audit module to every tool's domain types.
"""

from __future__ import annotations

import json
import uuid
from typing import Any

from meridian_orchestrator.storage.interface import Storage


async def write_audit(
    storage: Storage,
    *,
    actor: str,
    action: str,
    target: str | None = None,
    details: dict[str, Any] | None = None,
    success: bool = True,
    error_message: str | None = None,
) -> str:
    """Write a single ``audit_log`` row.

    ``actor`` is the resolved agent_id (or ``"system"`` for daemon-internal
    events that have no MCP-authenticated caller — bridge lifecycle steps,
    canonical-clone provisioning, watchdog respawns). ``action`` follows the
    namespaced verb convention (``task.submit``, ``dispatch.issued``, etc.)
    enumerated in ``11-engineer-bridge.md`` § Audit log entries. ``details``
    is JSON-encoded; pass the per-action payload from that table.

    Returns the generated row id (a UUID hex). Tests assert on it; callers
    that don't need the id can ignore the return value.

    Audit writes do not start their own transaction — when called from inside
    a tool's transaction the row participates in that transaction's commit
    or rollback. Callers that want best-effort audit despite a tool failure
    should wrap their state-changing block separately and emit the audit row
    on the failure path explicitly.
    """
    audit_id = uuid.uuid4().hex
    details_json = json.dumps(details, sort_keys=True) if details is not None else None
    await storage.execute(
        "INSERT INTO audit_log (id, actor, action, target, details_json, success, error_message) "
        "VALUES (?, ?, ?, ?, ?, ?, ?)",
        (
            audit_id,
            actor,
            action,
            target,
            details_json,
            1 if success else 0,
            error_message,
        ),
    )
    return audit_id
