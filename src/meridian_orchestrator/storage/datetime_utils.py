"""Datetime utilities shared across storage-adjacent modules.

Extracted at M5-subscription-routing commit 2 when the inline UTC-
coercion pattern crossed the rule-of-three threshold (third instance
in :mod:`meridian_orchestrator.tools.budget`'s ``_resolve_window``,
following the existing two in
:mod:`meridian_orchestrator.bridge.supervisor_base` and
:mod:`meridian_orchestrator.tools.budget`'s
``_read_most_recent_pm_mode_log_timestamp``).

Lives under ``storage/`` per the BACKLOG watch-for-pattern entry's
suggested location: both ``bridge/`` and ``tools/`` consumers import
from ``storage/`` without cross-layer concerns. The pattern's
underlying motivation is "SQLite ``datetime('now')`` produces naive
strings that round-trip through Python's ``datetime.fromisoformat``
as naive datetimes; treat naive as UTC, preserve any explicit
offset" — purely a storage-format-bridge concern, hence the storage
home.
"""

from __future__ import annotations

from datetime import UTC, datetime


def _coerce_naive_to_utc(dt: datetime) -> datetime:
    """If ``dt`` is naive (``tzinfo is None``), return a UTC-aware
    copy; if already tz-aware, return as-is.

    **Naive-as-UTC convention.** SQLite ``datetime('now')`` produces
    'YYYY-MM-DD HH:MM:SS' strings; ``datetime.fromisoformat`` parses
    these as naive. The orchestrator stores all timestamps in UTC
    by convention, so naive values are interpreted as UTC.

    **tz-aware preservation.** Defensive: a future writer might use
    a different format and produce tz-aware datetimes (e.g., with
    explicit ``+05:00`` offset). Unconditional ``.replace(tzinfo=UTC)``
    would silently corrupt tz-aware inputs by overwriting their
    offset — wall-clock numbers preserved but the moment-in-time
    shifts by the offset's hours. Per the bug fix at M5-pm-mode-and-
    window-state commit 6 (which surfaced "invisible on UTC CI,
    catastrophic on non-UTC dev machine" as the failure class), the
    guard on ``tzinfo is None`` is load-bearing.

    The helper does not validate that an explicit offset is
    actually UTC — caller-supplied offsets stay as the caller chose
    them. Callers wanting strict UTC normalization should call
    ``.astimezone(UTC)`` after this helper.
    """
    if dt.tzinfo is None:
        return dt.replace(tzinfo=UTC)
    return dt


__all__ = ["_coerce_naive_to_utc"]
