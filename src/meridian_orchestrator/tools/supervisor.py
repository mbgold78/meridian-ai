"""Supervisor-lifecycle MCP tools.

Distinct from :mod:`meridian_orchestrator.tools.dispatch` (which holds
sub-agent dispatch tools — work delegated to child subprocesses).
Supervisor-lifecycle tools manage the supervisor's *own* existence:
compaction signaling at M4c-compaction; PMSupervisor mode transitions
arrive at M5; future supervisor-lifecycle tools land here too.

Currently ships:

- :func:`signal_compaction` — Engineer-only. Engineer calls this when its
  iteration-boundary check (M5: ``get_window_state(bucket='subscription_5h') >= 80%``)
  decides compaction is appropriate. Sets the in-process
  ``_compaction_pending`` flag on the resolved supervisor; Engineer's
  subprocess is expected to exit cleanly afterward. The watchdog observes
  the clean exit and the flag, audits ``engineer.compaction_completed``,
  marks the dispatch row ``status='compacted'``, and respawns the
  Engineer fresh (no respawn-history increment — compaction is healthy
  lifecycle, not failure recovery).

At M4c, no production code triggers this — Engineer's prompt logic
that calls it is wired at M5 (depends on ``record_api_call`` and
``get_window_state``). Tests fire the trigger directly via this tool's
handler to exercise the full lifecycle. See ``11-engineer-bridge.md``
§ Engineer compaction for the full semantics.
"""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

from meridian_orchestrator.audit.logger import write_audit
from meridian_orchestrator.bridge.supervisor_base import LongRunningSupervisor
from meridian_orchestrator.errors import ConflictError
from meridian_orchestrator.errors import PermissionError as OrchestratorPermissionError
from meridian_orchestrator.storage.interface import Storage


class SignalCompactionInput(BaseModel):
    iteration_marker: str = Field(
        ...,
        description=(
            "Opaque string Engineer chooses (iteration count, ISO timestamp, "
            "or any identifier that lets the operator correlate the audit "
            "row with the Engineer's work-log)."
        ),
    )
    proxy_value: float | None = Field(
        default=None,
        description=(
            "Bucket utilization (0.0-1.0) at decision time, or NULL if "
            "Engineer is using the M4c test-fixture path (no real "
            "threshold check). At M5+ this is the get_window_state-derived "
            "subscription_5h utilization."
        ),
    )


class SignalCompactionOutput(BaseModel):
    accepted: bool
    next_action: Literal["exit_clean"]


def _require_role(caller_role: str, *allowed: str) -> None:
    if caller_role not in allowed:
        raise OrchestratorPermissionError(
            f"caller role {caller_role!r} cannot invoke this tool"
        )


async def signal_compaction(
    *,
    storage: Storage,
    supervisor: LongRunningSupervisor,
    request: SignalCompactionInput,
    caller_agent_id: str,
    caller_role: str,
) -> SignalCompactionOutput:
    """Engineer signals an imminent clean exit for compaction.

    Validates:

    1. Caller's role is ``engineer`` (PERMISSION_DENIED otherwise).
    2. The supervisor has an active dispatch row (CONFLICT otherwise — the
       supervisor was just started and hasn't issued a dispatch yet, or the
       supervisor is in shutdown).
    3. The caller's resolved agent_id matches the supervisor's agent_id
       (PERMISSION_DENIED otherwise — only the engineer-supervisor's own
       Engineer can signal its own compaction).

    On success: marks ``supervisor._compaction_pending = True`` (via the
    public :meth:`mark_compaction_pending` method), audits
    ``engineer.compaction_triggered`` with the iteration_marker and
    proxy_value, returns ``next_action='exit_clean'``. The caller is
    expected to terminate cleanly (rc=0) afterward.

    The watchdog will observe the clean exit and the in-process flag,
    write ``engineer.compaction_completed``, mark the dispatch row
    ``status='compacted'``, and respawn the supervisor fresh. The
    daemon-restart edge case (flag lost between signal and exit → exit
    classified as ``dispatch.subprocess_unexpected_exit``) is documented
    in ``11-engineer-bridge.md`` § Engineer compaction.
    """
    _require_role(caller_role, "engineer")

    current_dispatch_id = supervisor.current_dispatch_id
    if current_dispatch_id is None:
        raise ConflictError(
            "supervisor has no active dispatch; signal_compaction requires "
            "a running supervisor"
        )

    if supervisor.supervisor_agent_id != caller_agent_id:
        raise OrchestratorPermissionError(
            "caller is not the supervisor's bound agent — only the "
            "supervisor's own Engineer subprocess may signal its compaction"
        )

    row = await storage.fetch_one(
        "SELECT id, role, status FROM dispatches WHERE id = ?",
        (current_dispatch_id,),
    )
    if row is None:
        raise ConflictError(
            f"supervisor's current dispatch row {current_dispatch_id!r} "
            "not found in storage"
        )
    if row["status"] != "running":
        raise ConflictError(
            f"dispatch_id={current_dispatch_id!r} is not running "
            f"(status={row['status']!r})"
        )

    supervisor.mark_compaction_pending()

    await write_audit(
        storage,
        actor=caller_agent_id,
        action="engineer.compaction_triggered",
        target=current_dispatch_id,
        details={
            "dispatch_id": current_dispatch_id,
            "iteration_marker": request.iteration_marker,
            "proxy_value": request.proxy_value,
        },
    )

    return SignalCompactionOutput(accepted=True, next_action="exit_clean")


__all__ = [
    "SignalCompactionInput",
    "SignalCompactionOutput",
    "signal_compaction",
]
