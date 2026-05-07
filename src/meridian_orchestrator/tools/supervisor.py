"""Supervisor-lifecycle MCP tools.

Distinct from :mod:`meridian_orchestrator.tools.dispatch` (which holds
sub-agent dispatch tools — work delegated to child subprocesses).
Supervisor-lifecycle tools manage the supervisor's *own* existence:
compaction signaling at M4c-compaction (Engineer); PM compaction
extension at M5-continuous-pm-driver commit 8; future supervisor-
lifecycle tools land here too.

Currently ships:

- :func:`signal_compaction` — Engineer or PM. Caller signals an
  imminent clean exit for compaction. Sets the in-process
  ``_compaction_pending`` flag on the resolved supervisor; the
  caller's subprocess is expected to exit cleanly afterward. The
  watchdog observes the clean exit and the flag, audits
  ``<role>.compaction_completed``, marks the dispatch row
  ``status='compacted'``, and respawns the supervisor fresh (no
  respawn-history increment — compaction is healthy lifecycle,
  not failure recovery).

  Permission widened from engineer-only to engineer + pm at
  M5-continuous-pm-driver commit 8 per F9.2 Q3 Reading A
  (extend ``signal_compaction`` permission). The supervisor-level
  compaction lifecycle (set ``_compaction_pending``, clean-exit,
  no cap-counter increment, respawn fresh) is genuinely identical
  for Engineer and PM; the role-distinct work happens at handoff-
  write (PM only, in :mod:`tools.pm_handoff`) and at the prompt-
  driven detection trigger (PM-yaml convention). The wrapper layer
  (``server._register_supervisor_tools``) resolves which supervisor
  to pass based on caller role; this handler is supervisor-instance-
  agnostic but role-aware for the audit-action namespace.

At M4c, Engineer's prompt logic that calls signal_compaction was
not yet wired (depended on ``record_api_call`` + ``get_window_state``
at M5). At M5-continuous-pm-driver commit 8, PM's prompt-driven
compaction trigger ships; Engineer's prompt-side wiring lands at
M5-flag-flips. Tests fire the trigger directly via this tool's
handler to exercise the full lifecycle. See ``11-engineer-bridge.md``
§ Engineer compaction + § PM compaction for the full semantics.
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
    """Engineer or PM signals an imminent clean exit for compaction.

    Validates:

    1. Caller's role is ``engineer`` or ``pm`` (PERMISSION_DENIED
       otherwise). Per F9.2 Q3 Reading A widening at
       M5-continuous-pm-driver commit 8.
    2. The supervisor has an active dispatch row (CONFLICT otherwise — the
       supervisor was just started and hasn't issued a dispatch yet, or the
       supervisor is in shutdown).
    3. The caller's resolved agent_id matches the supervisor's agent_id
       (PERMISSION_DENIED otherwise — only the supervisor's own subprocess
       can signal its own compaction).

    On success: marks ``supervisor._compaction_pending = True`` (via the
    public :meth:`mark_compaction_pending` method), audits
    ``<role>.compaction_triggered`` with the iteration_marker and
    proxy_value, returns ``next_action='exit_clean'``. The caller is
    expected to terminate cleanly (rc=0) afterward.

    **Audit-action namespace per role**: ``engineer.compaction_triggered``
    when caller is engineer; ``pm.compaction_triggered`` when caller is
    pm. The watchdog continues to write the role-prefixed
    ``<role>.compaction_completed`` audit after observing clean exit;
    namespace symmetry is preserved at the supervisor base layer.

    The watchdog will observe the clean exit and the in-process flag,
    write ``<role>.compaction_completed``, mark the dispatch row
    ``status='compacted'``, and respawn the supervisor fresh. The
    daemon-restart edge case (flag lost between signal and exit → exit
    classified as ``dispatch.subprocess_unexpected_exit``) is documented
    in ``11-engineer-bridge.md`` § Engineer compaction; the same edge
    case applies to PM compaction at the supervisor base layer.

    The wrapper at ``server._register_supervisor_tools`` resolves which
    supervisor to pass based on caller role (engineer → engineer-
    supervisor; pm → pm-supervisor). This handler is supervisor-
    instance-agnostic; the supervisor passed in already matches the
    caller's role at the wrapper layer.
    """
    _require_role(caller_role, "engineer", "pm")

    current_dispatch_id = supervisor.current_dispatch_id
    if current_dispatch_id is None:
        raise ConflictError(
            "supervisor has no active dispatch; signal_compaction requires "
            "a running supervisor"
        )

    if supervisor.supervisor_agent_id != caller_agent_id:
        raise OrchestratorPermissionError(
            "caller is not the supervisor's bound agent — only the "
            "supervisor's own subprocess may signal its compaction"
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

    # Audit action prefixed by caller role per F9.2 Q3-A audit-namespace
    # symmetry. Engineer → "engineer.compaction_triggered" (preserves
    # M4c convention; existing tests + spec text); PM →
    # "pm.compaction_triggered" (new at commit 8; matches pm.<surface>.<verb>
    # namespace established at commits 2-4).
    action = f"{caller_role}.compaction_triggered"

    await write_audit(
        storage,
        actor=caller_agent_id,
        action=action,
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
