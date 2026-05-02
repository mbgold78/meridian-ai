"""Unit tests for ``bridge.oauth_usage``.

Commit 1 of M5-pm-mode-and-window-state ships the basic poller
behavior; this file covers:

- :func:`_detect_null_counters` against the canonical Experiment 4
  payload from ``project_subscription_first_architecture`` memory plus
  all-populated and all-null edge cases.
- :meth:`CachedSnapshot.age_seconds` integer-truncation semantics.
- :class:`OAuthUsagePoller` lifecycle: ``start`` / ``shutdown``
  idempotence; ``shutdown`` cancels in-flight ``asyncio.sleep`` so the
  daemon doesn't wait for the next 30s tick.
- Happy-path polling: fake fetcher → cached snapshot populated;
  ``null_counters`` correctly detected.
- Error paths: non-2xx HTTP and fetcher exceptions log and skip the
  cycle; the loop continues to a successful poll.
- Constructor validation: requires either ``oauth_token`` or
  ``snapshot_fetcher``.

Commit 2 will add tests for the 429 backoff state machine
(60→120→240→480→600s) plus the
``oauth_usage.polling_ceiling_reached`` audit row at ceiling.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest
import pytest_asyncio

from meridian_orchestrator.bridge.oauth_usage import (
    CachedSnapshot,
    OAuthUsagePoller,
    SnapshotFetchResult,
    _detect_null_counters,
)
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage

# Verbatim from project_subscription_first_architecture memory § Experiment 4.
# Acts as a regression fixture: if Anthropic changes the response shape
# in a way that breaks our parser, the tests against this payload fail
# informatively.
_EXPERIMENT_4_PAYLOAD: dict[str, Any] = {
    "five_hour": {
        "utilization": 17.0,
        "resets_at": "2026-05-01T20:50:00.631001+00:00",
    },
    "seven_day": {
        "utilization": 15.0,
        "resets_at": "2026-05-04T12:00:00.631028+00:00",
    },
    "seven_day_oauth_apps": None,
    "seven_day_opus": None,
    "seven_day_sonnet": {"utilization": 0.0, "resets_at": None},
    "seven_day_cowork": None,
    "seven_day_omelette": {"utilization": 0.0, "resets_at": None},
    "tangelo": None,
    "iguana_necktie": None,
    "omelette_promotional": None,
    "extra_usage": {
        "is_enabled": False,
        "monthly_limit": None,
        "used_credits": None,
        "utilization": None,
        "currency": None,
    },
}


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


# ---------------------------------------------------------------------------
# _detect_null_counters
# ---------------------------------------------------------------------------


def test_detect_null_counters_against_experiment_4_payload() -> None:
    """The canonical Experiment 4 payload has ``seven_day_opus: null``
    and ``extra_usage.utilization: null``. The detector must return
    both bucket names; ``seven_day_sonnet`` (utilization 0.0) and
    ``seven_day`` (15.0) are populated and stay out of the list.

    Encodes B-1 reading B's null-detection contract per
    ``project_subscription_first_architecture`` memory: a counter is
    null when the field itself is None OR when the field's
    ``utilization`` value is None. The two cases must both be detected.
    """
    null_buckets = _detect_null_counters(_EXPERIMENT_4_PAYLOAD)
    assert set(null_buckets) == {
        "subscription_7d_opus",  # seven_day_opus is None
        "api_key_overflow",  # extra_usage.utilization is None
    }


def test_detect_null_counters_handles_all_populated() -> None:
    """When every counter has a numeric utilization, the null list is
    empty and the daemon does NOT emit the INFO log."""
    payload = {
        "five_hour": {"utilization": 17.0},
        "seven_day": {"utilization": 15.0},
        "seven_day_opus": {"utilization": 22.0},
        "seven_day_sonnet": {"utilization": 0.0},
        "extra_usage": {"is_enabled": True, "utilization": 5.0},
    }
    assert _detect_null_counters(payload) == []


def test_detect_null_counters_handles_all_null() -> None:
    """All five bucket fields null → all five bucket names returned."""
    payload = {
        "five_hour": None,
        "seven_day": None,
        "seven_day_opus": None,
        "seven_day_sonnet": None,
        "extra_usage": None,
    }
    assert set(_detect_null_counters(payload)) == {
        "subscription_5h",
        "subscription_7d",
        "subscription_7d_opus",
        "subscription_7d_sonnet",
        "api_key_overflow",
    }


def test_detect_null_counters_ignores_unmapped_codename_fields() -> None:
    """Anthropic-internal codename fields (``tangelo``,
    ``iguana_necktie``, etc.) are not in the canonical bucket map and
    must be ignored — null or otherwise."""
    payload = {
        "five_hour": {"utilization": 17.0},
        "tangelo": None,
        "iguana_necktie": {"utilization": 99.0},
        "omelette_promotional": None,
    }
    # Only the four canonical buckets that aren't in the payload are
    # missing → they appear as null. tangelo/iguana_necktie/omelette
    # are noise and are not in the result regardless of their values.
    null_buckets = _detect_null_counters(payload)
    assert "tangelo" not in null_buckets
    assert "iguana_necktie" not in null_buckets
    assert "omelette_promotional" not in null_buckets


# ---------------------------------------------------------------------------
# CachedSnapshot.age_seconds
# ---------------------------------------------------------------------------


def test_cached_snapshot_age_seconds_against_explicit_now() -> None:
    polled = datetime(2026, 5, 1, 12, 0, 0, tzinfo=UTC)
    later = polled + timedelta(seconds=45)
    snapshot = CachedSnapshot(
        payload={}, polled_at=polled, null_counters=[]
    )
    assert snapshot.age_seconds(now=later) == 45


def test_cached_snapshot_age_seconds_truncates_to_int() -> None:
    """The ``snapshot_age_seconds`` field on ``get_window_state``'s
    output is typed as ``int``; sub-second remainder must be dropped.
    A snapshot 119.999s old is age=119, not age=120."""
    polled = datetime(2026, 5, 1, 12, 0, 0, tzinfo=UTC)
    later = polled + timedelta(seconds=119, microseconds=999_999)
    snapshot = CachedSnapshot(
        payload={}, polled_at=polled, null_counters=[]
    )
    assert snapshot.age_seconds(now=later) == 119


# ---------------------------------------------------------------------------
# OAuthUsagePoller — happy path
# ---------------------------------------------------------------------------


async def test_poller_caches_snapshot_after_successful_poll(
    storage: SqliteStorage,
) -> None:
    """A poller with a fast cadence and a fake fetcher returning the
    Experiment 4 payload should populate ``latest_snapshot()`` within
    a few cycles. The cached snapshot's payload, polled_at, and
    null_counters are all populated correctly."""
    fetch_count = 0

    async def fake_fetcher() -> SnapshotFetchResult:
        nonlocal fetch_count
        fetch_count += 1
        return SnapshotFetchResult(
            status_code=200, payload=_EXPERIMENT_4_PAYLOAD
        )

    poller = OAuthUsagePoller(
        storage=storage,
        snapshot_fetcher=fake_fetcher,
        poll_interval_seconds=0.01,
    )
    assert poller.latest_snapshot() is None

    await poller.start()
    try:
        for _ in range(100):
            if poller.latest_snapshot() is not None:
                break
            await asyncio.sleep(0.01)
    finally:
        await poller.shutdown()

    snapshot = poller.latest_snapshot()
    assert snapshot is not None
    assert snapshot.payload == _EXPERIMENT_4_PAYLOAD
    assert snapshot.polled_at.tzinfo is UTC
    assert set(snapshot.null_counters) == {
        "subscription_7d_opus",
        "api_key_overflow",
    }
    assert fetch_count >= 1


# ---------------------------------------------------------------------------
# OAuthUsagePoller — lifecycle
# ---------------------------------------------------------------------------


async def test_poller_start_is_idempotent(
    storage: SqliteStorage,
) -> None:
    """Calling ``start`` twice must not spawn a second polling task —
    the second call returns immediately and the existing task remains
    the one driving the poll loop."""

    async def fake_fetcher() -> SnapshotFetchResult:
        return SnapshotFetchResult(status_code=200, payload={})

    poller = OAuthUsagePoller(
        storage=storage,
        snapshot_fetcher=fake_fetcher,
        poll_interval_seconds=10.0,
    )
    await poller.start()
    first_task = poller._task
    await poller.start()
    second_task = poller._task
    try:
        assert first_task is second_task, (
            "start() spawned a second task; idempotence is broken. "
            "Two polling tasks racing on _cached_snapshot would "
            "produce undefined behavior."
        )
    finally:
        await poller.shutdown()


async def test_poller_shutdown_is_idempotent(
    storage: SqliteStorage,
) -> None:
    """Calling ``shutdown`` twice must not raise — the daemon's
    shutdown sequence may invoke shutdown defensively even if a
    previous shutdown already landed."""

    async def fake_fetcher() -> SnapshotFetchResult:
        return SnapshotFetchResult(status_code=200, payload={})

    poller = OAuthUsagePoller(
        storage=storage,
        snapshot_fetcher=fake_fetcher,
        poll_interval_seconds=10.0,
    )
    await poller.start()
    await poller.shutdown()
    await poller.shutdown()  # must not raise


async def test_poller_shutdown_cancels_in_flight_sleep(
    storage: SqliteStorage,
) -> None:
    """The poller sleeps ``poll_interval_seconds`` between cycles;
    shutdown must cancel a sleeping task promptly even with a long
    interval. Validates the cancellation path through
    ``asyncio.sleep``.

    Without proper cancellation propagation, a 30s production interval
    would mean the daemon's shutdown sequence blocks for up to 30s
    waiting for the poller to wake up — unacceptable for clean
    shutdown.
    """

    async def fake_fetcher() -> SnapshotFetchResult:
        return SnapshotFetchResult(status_code=200, payload={})

    poller = OAuthUsagePoller(
        storage=storage,
        snapshot_fetcher=fake_fetcher,
        poll_interval_seconds=300.0,  # 5 minutes
    )
    await poller.start()
    # Let the first poll complete so the task is in the sleep state.
    for _ in range(50):
        if poller.latest_snapshot() is not None:
            break
        await asyncio.sleep(0.02)

    # Shutdown should return promptly, not wait for the 5-min sleep.
    await asyncio.wait_for(poller.shutdown(), timeout=2.0)


# ---------------------------------------------------------------------------
# OAuthUsagePoller — error handling
# ---------------------------------------------------------------------------


async def test_poller_skips_non_2xx_response_and_recovers(
    storage: SqliteStorage,
) -> None:
    """Non-2xx responses log a warning and skip the cycle without
    crashing the poller. The cached snapshot remains None until a
    successful poll lands; subsequent successful polls populate the
    cache normally — the loop is robust against transient upstream
    errors."""
    call_count = 0

    async def varying_fetcher() -> SnapshotFetchResult:
        nonlocal call_count
        call_count += 1
        if call_count <= 2:
            return SnapshotFetchResult(status_code=503)
        return SnapshotFetchResult(
            status_code=200, payload=_EXPERIMENT_4_PAYLOAD
        )

    poller = OAuthUsagePoller(
        storage=storage,
        snapshot_fetcher=varying_fetcher,
        poll_interval_seconds=0.01,
    )
    await poller.start()
    try:
        for _ in range(200):
            if poller.latest_snapshot() is not None:
                break
            await asyncio.sleep(0.01)
    finally:
        await poller.shutdown()

    assert call_count >= 3, (
        f"poller did not retry after 5xx responses (call_count={call_count}); "
        "the loop crashed on a non-2xx instead of skipping the cycle."
    )
    assert poller.latest_snapshot() is not None, (
        "poller never recovered to a successful poll despite the fetcher "
        "returning 200 after the third call; the cache write is gated "
        "incorrectly or the loop exited prematurely."
    )


async def test_poller_skips_on_fetcher_exception_and_recovers(
    storage: SqliteStorage,
) -> None:
    """An exception raised by the fetcher logs an error and skips the
    cycle; the loop continues. Network-layer exceptions in production
    (DNS failure, connection refused, TLS handshake error) hit this
    path — they must not crash the daemon's polling task."""
    call_count = 0

    async def flaky_fetcher() -> SnapshotFetchResult:
        nonlocal call_count
        call_count += 1
        if call_count <= 1:
            raise RuntimeError("simulated transient network error")
        return SnapshotFetchResult(
            status_code=200, payload=_EXPERIMENT_4_PAYLOAD
        )

    poller = OAuthUsagePoller(
        storage=storage,
        snapshot_fetcher=flaky_fetcher,
        poll_interval_seconds=0.01,
    )
    await poller.start()
    try:
        for _ in range(200):
            if poller.latest_snapshot() is not None:
                break
            await asyncio.sleep(0.01)
    finally:
        await poller.shutdown()

    assert call_count >= 2
    assert poller.latest_snapshot() is not None, (
        "poller did not recover after a fetcher exception; the loop "
        "exited or the exception path is propagating instead of being "
        "logged-and-skipped."
    )


async def test_poller_skips_2xx_with_unparseable_payload(
    storage: SqliteStorage,
) -> None:
    """Defensive: a 2xx response whose payload is None (JSON parse
    failure or non-dict body) must not be cached. The previous
    snapshot stays intact; the next valid poll replaces it."""

    async def malformed_then_good() -> SnapshotFetchResult:
        # First call: 2xx but no payload (simulates JSON parse fail).
        if not hasattr(malformed_then_good, "_called"):
            malformed_then_good._called = True  # type: ignore[attr-defined]
            return SnapshotFetchResult(status_code=200, payload=None)
        return SnapshotFetchResult(
            status_code=200, payload=_EXPERIMENT_4_PAYLOAD
        )

    poller = OAuthUsagePoller(
        storage=storage,
        snapshot_fetcher=malformed_then_good,
        poll_interval_seconds=0.01,
    )
    await poller.start()
    try:
        for _ in range(200):
            snap = poller.latest_snapshot()
            if snap is not None and snap.payload == _EXPERIMENT_4_PAYLOAD:
                break
            await asyncio.sleep(0.01)
    finally:
        await poller.shutdown()

    snap = poller.latest_snapshot()
    assert snap is not None
    assert snap.payload == _EXPERIMENT_4_PAYLOAD


# ---------------------------------------------------------------------------
# OAuthUsagePoller — constructor validation
# ---------------------------------------------------------------------------


def test_constructor_requires_token_or_fetcher(
    storage: SqliteStorage,
) -> None:
    """Production passes ``oauth_token``; tests pass
    ``snapshot_fetcher``. Passing neither is a programming error and
    must surface as ``ValueError`` rather than silently constructing
    a non-functional poller."""
    with pytest.raises(
        ValueError, match=r"oauth_token.*snapshot_fetcher"
    ):
        OAuthUsagePoller(storage=storage)
