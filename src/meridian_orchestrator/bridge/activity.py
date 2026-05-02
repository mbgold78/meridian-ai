"""Activity-writer helpers for daemon-side observation of MCP calls.

Two writer concerns share this module:

1. :func:`record_mcp_call` — updates ``dispatches.last_activity_at`` and
   ``dispatches.last_tool_called`` for dispatch-bound tokens. Single
   point of write per ``docs/kickoff/11-engineer-bridge.md`` §
   `last_activity_at` and `last_tool_called` writer.
2. :func:`record_oauth_pm_auto_flip` — writes a ``pm_mode_log`` row
   with ``mode='interactive', triggered_by='mike_message'`` when an
   OAuth-PM caller (Mike) is observed and the current mode warrants
   the transition. Single point of write for daemon-initiated
   ``pm_mode_log`` rows per
   ``docs/kickoff/05-data-model.md`` § ``pm_mode_log``.

Tool wrappers in ``server.py`` call :func:`server._observe_caller`
immediately after ``_resolve_agent_id`` resolves the caller's token;
``_observe_caller`` fans out to both writers in a single transaction
(activity update + auto-flip atomicity, per
``project_mcp_tool_wrapper_activity_recording`` memory). Any future
code that bypasses this fan-out to write either surface directly is
incorrect by spec.

**:func:`record_mcp_call` skip cases** (both produce zero updated rows):

1. Tokens with no associated dispatch row (operator OAuth tokens;
   static admin tokens not bound to a dispatch). Resolved by the
   ``token_id = ?`` clause matching nothing.
2. Tokens whose dispatch row is in a terminal state. A subprocess
   making a final MCP call after termination must not reset the
   activity clock for a row whose lifecycle is over. Resolved by
   ``status NOT IN (...)``.

The terminal-states list is imported from :mod:`bridge.dispatcher` so
the two stay in sync — adding a new terminal state in the dispatcher
automatically extends this guard.

**:func:`record_oauth_pm_auto_flip` skip cases** (no INSERT, no
audit):

1. Caller is not an oauth-pm token (``source != 'oauth'`` or
   ``role != 'pm'``). Static-issued PM tokens (e.g.,
   pm-continuous supervisor) never trigger auto-flip; only Mike's
   OAuth-issued PM token does. Static-PM is the daemon-internal PM
   subprocess; its activity isn't "Mike's presence" in the spec
   sense.
2. Current mode is already ``interactive`` (Reading B per the
   M5-pm-mode-and-window-state commit 5 prep): write only on
   transition, not every request. Subsequent requests in the same
   interactive stretch produce no pm_mode_log writes.
3. Current mode is ``paused_blocked`` or ``paused_question``
   (Reading B on Q2): paused states are PM-deliberate via
   ``set_pm_mode``; the daemon doesn't override them. Mike's MCP
   activity during a paused stretch is not directly observable in
   ``pm_mode_log`` until M5-telegram-foundation routes Mike's
   reply-to-paused-question messages through Telegram. PM's
   continuous loop must use other freshness signals during paused
   states; see M5-continuous-pm-driver.

**Phase 1 transition-based approximation of the addendum's
sliding-window**: per-message freshness is NOT captured under
Reading B. PM's continuous loop computes the interactive window as
``now - most_recent_pm_mode_log_timestamp < 30min``, which captures
"30 min from stretch-start," not "30 min from last message." Self-
corrects via PM's flip-back-to-continuous logic (set_pm_mode at the
30-min mark resets the auto-flip writer's perception; Mike's next
message lands as a fresh transition). Phase 2 may refine to
per-message freshness — see ``BACKLOG.md``.
"""

from __future__ import annotations

import uuid
from datetime import UTC, datetime
from typing import TYPE_CHECKING

from meridian_orchestrator.audit.logger import write_audit
from meridian_orchestrator.bridge.dispatcher import _TERMINAL_STATES
from meridian_orchestrator.storage.interface import Storage
from meridian_orchestrator.tools.pm_mode import PAUSED_MODES

if TYPE_CHECKING:
    from meridian_orchestrator.auth import TokenRecord

# Build the IN-clause once at module load. Single source of truth: the
# dispatcher's _TERMINAL_STATES frozenset. Sorted so the SQL string is
# deterministic for diffability.
#
# Values are interpolated into the SQL string; values must never contain
# single quotes. Sourced from bridge.dispatcher._TERMINAL_STATES (frozenset
# of code-defined constants, never user input).
_TERMINAL_STATES_SQL_LIST = ",".join(f"'{s}'" for s in sorted(_TERMINAL_STATES))

# Timestamp is generated Python-side as a tz-aware ISO string rather
# than via SQL `datetime('now')` (which produces a naive string like
# '2026-05-01 09:53:34'). The supervisor watchdog parses this column
# with `datetime.fromisoformat` and compares against `datetime.now(tz=UTC)`;
# mixing naive + aware datetimes raises TypeError. Other dispatches.*
# timestamp columns (issued_at default, etc.) use SQL `datetime('now')`
# and are read by code that doesn't compare across that boundary, so
# they keep their existing format. The watchdog also has a defensive
# fallback (treats naive timestamps as UTC) — see supervisor_base.py
# `_check_and_handle_timeout`.
_UPDATE_SQL = (
    "UPDATE dispatches "
    "SET last_activity_at = ?, last_tool_called = ? "
    "WHERE token_id = ? "
    f"AND status NOT IN ({_TERMINAL_STATES_SQL_LIST})"
)


def _now_iso() -> str:
    """Tz-aware ISO 8601 timestamp with microsecond precision.

    Matches :func:`bridge.supervisor_base._now_iso`. Microsecond precision
    (rather than seconds) is required so the watchdog's age comparison
    resolves activity updates within the same wall-clock second.
    """
    return datetime.now(tz=UTC).isoformat(timespec="microseconds")


async def record_mcp_call(
    storage: Storage, token_id: str, tool_name: str
) -> None:
    """Single point of write for ``dispatches.last_activity_at`` and
    ``dispatches.last_tool_called``; do not write these columns elsewhere.

    Updates the dispatch row whose ``token_id`` matches, gated on the row
    being in a non-terminal state. Two skip cases produce zero updated rows
    silently — the caller doesn't need to distinguish:

    1. No dispatch row matches the ``token_id`` (operator OAuth tokens;
       static admin tokens not bound to a dispatch).
    2. Dispatch row is in a terminal state (final MCP call after
       termination shouldn't reset the activity clock).

    See ``11-engineer-bridge.md`` § `last_activity_at` and
    `last_tool_called` writer for the full rationale (current-state
    columns, not audit-log derivation).
    """
    await storage.execute(_UPDATE_SQL, (_now_iso(), tool_name, token_id))


async def record_oauth_pm_auto_flip(
    storage: Storage, token_record: TokenRecord
) -> None:
    """Write a ``pm_mode_log`` row when an OAuth-PM caller is observed
    and the current mode warrants a transition to ``interactive``.

    See module docstring § ``record_oauth_pm_auto_flip`` skip cases for
    the three skip surfaces (non-oauth-pm caller; already interactive;
    paused state). Only oauth-pm callers (``source='oauth' AND
    role='pm'``) can trigger; static-PM tokens (the pm-continuous
    supervisor) are not "Mike's presence" in the spec sense and are
    skipped silently.

    Atomicity: the SELECT-current-mode → conditional INSERT
    ``pm_mode_log`` → ``write_audit`` sequence wraps in a single
    ``storage.transaction()``. Nested-transaction semantics
    (``storage.transaction()`` is reentrant per ``storage/sqlite.py``):
    when called from inside ``server._observe_caller``'s outer
    transaction, this inner ``transaction()`` is a no-op and shares
    the outer COMMIT/ROLLBACK lifecycle. When called directly (tests
    or future direct-write paths), the inner transaction provides
    standalone atomicity.

    Audit shape: action ``pm_mode.auto_flip`` (distinct from
    ``pm_mode.transition`` which set_pm_mode emits). Actor is
    ``"system"`` per the daemon-internal-events convention in
    ``audit.logger.write_audit`` docstring; the triggering caller's
    agent_id is recorded in details for traceability. Target is the
    inserted ``pm_mode_log`` row id.
    """
    if token_record.source != "oauth" or token_record.role != "pm":
        return

    async with storage.transaction():
        # Read current mode = most recent pm_mode_log row by rowid.
        # Mirrors set_pm_mode's lookup pattern; see tools/pm_mode.py
        # for the rowid-vs-timestamp rationale.
        current_row = await storage.fetch_one(
            "SELECT mode FROM pm_mode_log ORDER BY rowid DESC LIMIT 1"
        )
        current_mode = (
            current_row["mode"] if current_row is not None else None
        )

        # Skip if already in interactive — Reading B (write only on
        # transition) per the M5-pm-mode-and-window-state commit 5
        # prep. Subsequent oauth-pm requests in the same stretch
        # produce no writes.
        if current_mode == "interactive":
            return

        # Skip if PM is in a deliberate paused state — Reading B on
        # Q2. The daemon doesn't override PM's state machine.
        # PAUSED_MODES is derived from tools.pm_mode.PmMode via
        # typing.get_args — single source of truth.
        if current_mode in PAUSED_MODES:
            return

        # Transition: write the auto-flip row + audit.
        pm_mode_log_id = uuid.uuid4().hex
        await storage.execute(
            "INSERT INTO pm_mode_log "
            "(id, mode, reason, triggered_by) "
            "VALUES (?, ?, ?, ?)",
            (pm_mode_log_id, "interactive", None, "mike_message"),
        )
        await write_audit(
            storage,
            actor="system",
            action="pm_mode.auto_flip",
            target=pm_mode_log_id,
            details={
                "old_mode": current_mode,
                "new_mode": "interactive",
                "triggering_agent_id": token_record.agent_id,
                "triggering_token_id": token_record.token_id,
                "pm_mode_log_id": pm_mode_log_id,
            },
        )
