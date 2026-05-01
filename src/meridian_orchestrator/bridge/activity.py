"""Activity-writer helper for ``dispatches.last_activity_at`` and
``dispatches.last_tool_called``.

Per ``docs/kickoff/11-engineer-bridge.md`` § `last_activity_at` and
`last_tool_called` writer: this module is the **single point of write** for
those columns. Tool wrappers in ``server.py`` call :func:`record_mcp_call`
immediately after ``_resolve_agent_id`` resolves the caller's token. Any
future code that bypasses this helper to UPDATE those columns directly is
incorrect by spec; the discipline makes drift catchable at code review by
grepping for the column names.

Two columns, one UPDATE, two skip cases (both produce zero updated rows):

1. Tokens with no associated dispatch row (operator OAuth tokens; static
   admin tokens not bound to a dispatch). Resolved by the ``token_id = ?``
   clause matching nothing.
2. Tokens whose dispatch row is in a terminal state. A subprocess making a
   final MCP call after termination must not reset the activity clock for a
   row whose lifecycle is over. Resolved by ``status NOT IN (...)``.

The terminal-states list is imported from :mod:`bridge.dispatcher` so the
two stay in sync — adding a new terminal state in the dispatcher
automatically extends this guard.
"""

from __future__ import annotations

from datetime import UTC, datetime

from meridian_orchestrator.bridge.dispatcher import _TERMINAL_STATES
from meridian_orchestrator.storage.interface import Storage

# Build the IN-clause once at module load. Single source of truth: the
# dispatcher's _TERMINAL_STATES frozenset. Sorted so the SQL string is
# deterministic for diffability.
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
