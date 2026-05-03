"""Unit tests for the shared :mod:`storage.datetime_utils` helpers.

Extracted at M5-subscription-routing commit 2 from three inline call
sites. The helper is small but the bug class it prevents is severe
("invisible on UTC CI, catastrophic on non-UTC dev machine" per the
M5-pm-mode-and-window-state commit 6 fix), so the unit-level coverage
of both behaviors is durable.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta, timezone

from meridian_orchestrator.storage.datetime_utils import _coerce_naive_to_utc


def test_coerce_naive_treats_as_utc() -> None:
    """A naive datetime gets ``tzinfo=UTC`` attached. Wall-clock
    numbers preserved; the moment is now interpreted as UTC."""
    naive = datetime(2026, 5, 3, 14, 30, 0)
    coerced = _coerce_naive_to_utc(naive)

    assert coerced.tzinfo is UTC
    # Wall-clock numbers unchanged.
    assert coerced.year == 2026
    assert coerced.month == 5
    assert coerced.day == 3
    assert coerced.hour == 14
    assert coerced.minute == 30


def test_coerce_tz_aware_preserves_existing_offset() -> None:
    """A tz-aware datetime with explicit offset (e.g., +05:00) keeps
    that offset — the helper does NOT silently overwrite to UTC. This
    is the load-bearing invariant: unconditional ``.replace(tzinfo=UTC)``
    would shift the moment-in-time by the offset hours.

    Failure mode this test prevents: the bug fixed at M5-pm-mode-and-
    window-state commit 6 — "invisible on UTC CI, catastrophic on
    non-UTC dev machine."
    """
    plus_five = timezone(timedelta(hours=5))
    aware = datetime(2026, 5, 3, 14, 30, 0, tzinfo=plus_five)
    coerced = _coerce_naive_to_utc(aware)

    assert coerced is aware or coerced.tzinfo == plus_five, (
        "_coerce_naive_to_utc clobbered an explicit non-UTC offset; "
        "tz-aware inputs MUST preserve their offset. The bug class "
        "is: input '14:30 +05:00' (= 09:30 UTC) becomes '14:30 +00:00' "
        "(= 14:30 UTC) — five hours of real time silently lost. "
        "Invisible on UTC CI; catastrophic on non-UTC dev machine."
    )


def test_coerce_already_utc_aware_returns_unchanged() -> None:
    """A datetime already tagged ``UTC`` passes through unchanged.
    No double-replace, no surprises."""
    aware_utc = datetime(2026, 5, 3, 14, 30, 0, tzinfo=UTC)
    coerced = _coerce_naive_to_utc(aware_utc)
    assert coerced.tzinfo is UTC
    assert coerced == aware_utc
