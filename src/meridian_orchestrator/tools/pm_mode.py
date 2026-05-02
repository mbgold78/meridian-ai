"""PM mode-transition tools.

M5-pm-mode-and-window-state commit 4 ships :func:`set_pm_mode` — PM's
self-report tool for deliberate mode transitions
(``continuous`` ↔ ``interactive``, entering ``paused_question`` when
surfacing a blocking question, etc.). The handler writes a
``pm_mode_log`` row when the mode actually changes and always emits a
``pm_mode.transition`` audit row.

The complementary daemon-side auto-flip writer (commit 5 of this
sub-milestone) is not tool-shaped — it observes MCP request context
to detect Mike's OAuth-PM token use and writes
``pm_mode_log`` rows with ``triggered_by='mike_message'`` directly.
The two paths share the ``pm_mode_log`` table but differ in:

- **Trigger label**: ``set_pm_mode`` writes ``triggered_by='manual'``
  (operator query ``WHERE triggered_by='manual'`` filters
  PM-initiated transitions; the other enum values flag daemon-
  initiated transitions).
- **Permission**: ``set_pm_mode`` is PM-only at the handler layer;
  the auto-flip writer has no caller-role concept (daemon-internal).
- **Audit action**: ``set_pm_mode`` emits ``pm_mode.transition``;
  the auto-flip writer emits a distinct action TBD at commit 5.

**Atomicity**: the SELECT-current-mode → conditional INSERT
``pm_mode_log`` → ``write_audit`` sequence wraps in a single
``storage.transaction()`` per the project pattern established at
M5-record-api-call commit 5. Partial-failure rollback ensures
operator-visible state stays consistent — no audit row claiming a
transition that didn't actually log to ``pm_mode_log``.

**No-op write semantics**: when the requested mode equals the current
mode (most recent ``pm_mode_log`` row), the handler skips the INSERT
but still emits the audit. The audit's ``transition_logged: false``
detail flag preserves the distinction; operators tracking PM's
set_pm_mode decision frequency (not just successful transitions)
have full visibility per the Q2 disposition at the commit 4 prep.

**Initial state**: when ``pm_mode_log`` is empty (fresh DB or new
operator), the handler treats the current mode as ``None``. The
first-ever ``set_pm_mode`` call always logs regardless of mode value
— there's no implicit "continuous-by-default" baseline. Per the Q3
disposition at commit 4 prep.
"""

from __future__ import annotations

import uuid
from typing import Literal, get_args

from pydantic import BaseModel

from meridian_orchestrator.audit.logger import write_audit
from meridian_orchestrator.errors import PermissionError as OrchestratorPermissionError
from meridian_orchestrator.storage.interface import Storage

# Single source of truth for the PM mode enum. The migration's CHECK
# constraint on pm_mode_log.mode and the Pydantic Literal here must
# stay in sync; the migration's CHECK-constraint sentinel test
# (test_pm_mode_check_constraint_accepts_all_spec_modes) durably
# enforces alignment by inserting every value below into pm_mode_log
# and asserting all four pass the CHECK.
PmMode = Literal[
    "continuous", "interactive", "paused_blocked", "paused_question"
]

# Modes that indicate PM-deliberate paused state. Derived from
# :data:`PmMode` via ``typing.get_args`` so adding a new
# ``paused_*`` value to the Literal automatically extends this set —
# single source of truth pattern. Consumed by
# :func:`bridge.activity.record_oauth_pm_auto_flip` to skip auto-flip
# when PM is in a deliberate paused state. A sentinel test
# (``test_paused_modes_derived_from_pm_mode_literal``) asserts the
# derivation produces exactly the expected paused values, catching
# the case where someone renames a paused value without updating
# the prefix convention.
PAUSED_MODES: frozenset[str] = frozenset(
    m for m in get_args(PmMode) if m.startswith("paused_")
)


# ----- Pydantic schemas ----------------------------------------------------


class SetPmModeInput(BaseModel):
    mode: PmMode
    reason: str | None = None


class SetPmModeOutput(BaseModel):
    mode: str
    transition_logged: bool


# ----- Permissions ---------------------------------------------------------


def _require_role(caller_role: str, *allowed: str) -> None:
    """Mirror of :func:`tools.dispatch._require_role`.

    Module-local because the helper is small enough that cross-module
    sharing isn't worth the import-graph entry; ``tools/supervisor.py``
    follows the same module-local pattern.
    """
    if caller_role not in allowed:
        raise OrchestratorPermissionError(
            f"caller role {caller_role!r} cannot invoke this tool"
        )


# ----- Handler -------------------------------------------------------------


async def set_pm_mode(
    *,
    storage: Storage,
    request: SetPmModeInput,
    caller_agent_id: str,
    caller_role: str,
) -> SetPmModeOutput:
    """PM-only handler for the ``set_pm_mode`` MCP tool.

    Args:
        storage: Storage handle.
        request: Validated input — mode (Literal of four values),
            optional reason text.
        caller_agent_id: Resolved at the wrapper layer from the
            access token's client_id. Stored on the audit row's
            ``actor`` column.
        caller_role: Resolved at the wrapper layer from the access
            token's role scope. Must be ``"pm"`` or
            :class:`OrchestratorPermissionError` is raised.

    Returns:
        :class:`SetPmModeOutput` with ``mode`` echoed back and
        ``transition_logged`` indicating whether a ``pm_mode_log``
        row was inserted (``False`` when the requested mode matches
        the current mode — same-mode no-op).

    See module docstring for the full design — atomicity, no-op
    semantics, initial-state semantics, and the ``triggered_by='manual'``
    convention for set_pm_mode-initiated writes.
    """
    _require_role(caller_role, "pm")

    # Transaction wraps the entire SELECT-current → conditional INSERT
    # → audit sequence. Deliberate: concurrent set_pm_mode calls
    # linearize through the transaction, ensuring the SELECT sees a
    # consistent "current mode" against which the transition_logged
    # decision is made. A future maintainer reducing the transaction
    # scope (e.g., to optimize write throughput by transacting only
    # the INSERT) would introduce a TOCTOU window where two concurrent
    # transitions could both see the same current mode and both log,
    # corrupting the row count and the transition_logged contract.
    # Plus the partial-failure rollback property — see module docstring
    # § Atomicity. Keep the full-sequence wrapping.
    async with storage.transaction():
        # Read current mode = most recent pm_mode_log row by rowid.
        # rowid is more reliable than timestamp for "most recent"
        # because timestamp has 1-second resolution from
        # datetime('now') — two rapid INSERTs in the same second
        # would tie on timestamp but never on rowid.
        current_row = await storage.fetch_one(
            "SELECT mode FROM pm_mode_log ORDER BY rowid DESC LIMIT 1"
        )
        current_mode = (
            current_row["mode"] if current_row is not None else None
        )

        transition_logged = current_mode != request.mode
        pm_mode_log_id: str | None = None

        if transition_logged:
            pm_mode_log_id = uuid.uuid4().hex
            # triggered_by='manual' is the deliberate choice per Q1
            # disposition at commit 4 prep: the 'manual' enum value
            # exists for set_pm_mode-initiated transitions
            # specifically. Operators querying WHERE
            # triggered_by='manual' filter for PM-deliberate writes;
            # the other enum values (mike_message, window_reset,
            # queue_empty, rate_limit) flag daemon-initiated writes.
            # Never null — leaving the column null would make the
            # 'manual' enum value permanently dead-letter.
            await storage.execute(
                "INSERT INTO pm_mode_log "
                "(id, mode, reason, triggered_by) "
                "VALUES (?, ?, ?, ?)",
                (pm_mode_log_id, request.mode, request.reason, "manual"),
            )

        # Audit always fires per Q2 disposition. PM's continuous-loop
        # logic will make speculative set_pm_mode calls; audit-on-
        # no-op gives operators visibility into PM's decision
        # frequency, not just transition events. The
        # transition_logged flag in details preserves the distinction
        # at query time.
        await write_audit(
            storage,
            actor=caller_agent_id,
            action="pm_mode.transition",
            target=pm_mode_log_id,  # null on no-op same-mode write
            details={
                "old_mode": current_mode,
                "new_mode": request.mode,
                "reason": request.reason,
                "transition_logged": transition_logged,
                "pm_mode_log_id": pm_mode_log_id,
            },
        )

    return SetPmModeOutput(
        mode=request.mode,
        transition_logged=transition_logged,
    )


__all__ = [
    "PAUSED_MODES",
    "PmMode",
    "SetPmModeInput",
    "SetPmModeOutput",
    "set_pm_mode",
]
