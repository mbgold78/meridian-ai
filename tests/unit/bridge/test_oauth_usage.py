"""Unit tests for ``bridge.oauth_usage``.

Commits 1-2 of M5-pm-mode-and-window-state ship the poller; this file
covers:

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
- :class:`BackoffStateMachine` (commit 2): pure-state transitions
  through the 6-step ladder, ceiling clamp on consecutive 429s,
  reset on successful poll, ceiling-audit-emission flag tracking.
- Poller integration with backoff (commit 2):
  ``oauth_usage.polling_ceiling_reached`` audit row emitted exactly
  once per ceiling stretch; subsequent ceiling stretches emit fresh
  rows after a successful-poll reset.
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest
import pytest_asyncio

from meridian_orchestrator.bridge.oauth_usage import (
    BackoffStateMachine,
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


# ---------------------------------------------------------------------------
# BackoffStateMachine — pure state transitions
# ---------------------------------------------------------------------------


# Test intervals: 6-step ladder distinguishable by value (0.0/0.1/0.2/0.3/
# 0.4/0.5). Tests assert against these values, not against the production
# canonical (30/60/120/240/480/600); the state machine is generic over
# the interval ladder.
_TEST_INTERVALS: tuple[float, ...] = (0.0, 0.1, 0.2, 0.3, 0.4, 0.5)


def _success_result() -> SnapshotFetchResult:
    return SnapshotFetchResult(
        status_code=200, payload=_EXPERIMENT_4_PAYLOAD
    )


def _rate_limited_result() -> SnapshotFetchResult:
    return SnapshotFetchResult(status_code=429)


def _server_error_result() -> SnapshotFetchResult:
    return SnapshotFetchResult(status_code=503)


def test_backoff_starts_at_default_step() -> None:
    """A fresh state machine reports the default cadence and is not
    at the ceiling."""
    sm = BackoffStateMachine(intervals_seconds=_TEST_INTERVALS)
    assert sm.current_interval_seconds == 0.0
    assert sm.at_ceiling is False


def test_backoff_429_escalates_one_step() -> None:
    """A single 429 transitions step 0 → step 1."""
    sm = BackoffStateMachine(intervals_seconds=_TEST_INTERVALS)
    update = sm.update(_rate_limited_result())
    assert update.previous_interval_seconds == 0.0
    assert update.current_interval_seconds == 0.1
    assert update.should_emit_ceiling_audit is False
    assert sm.at_ceiling is False


def test_backoff_429_chain_escalates_through_full_ladder() -> None:
    """Five consecutive 429s escalate through 0.0→0.1→0.2→0.3→0.4→0.5
    with the 5th transition into the ceiling step. Per spec the
    canonical sequence is 30→60→120→240→480→600s; this test uses the
    test intervals to verify the ladder structure independent of the
    canonical values."""
    sm = BackoffStateMachine(intervals_seconds=_TEST_INTERVALS)
    expected_intervals = [0.1, 0.2, 0.3, 0.4, 0.5]
    for expected in expected_intervals:
        update = sm.update(_rate_limited_result())
        assert update.current_interval_seconds == pytest.approx(expected)
    assert sm.at_ceiling is True


def test_backoff_429_at_ceiling_clamps() -> None:
    """A 429 received while already at the ceiling step does not
    escalate further — the state machine clamps. Per Issue #31021 the
    ceiling is 600s; the daemon does not back off beyond this even
    under sustained rate limiting."""
    sm = BackoffStateMachine(intervals_seconds=_TEST_INTERVALS)
    # Drive to ceiling.
    for _ in range(5):
        sm.update(_rate_limited_result())
    assert sm.at_ceiling is True

    # Two more 429s — must clamp at the ceiling, not escalate or wrap.
    for _ in range(2):
        update = sm.update(_rate_limited_result())
        assert update.current_interval_seconds == 0.5
        assert update.should_emit_ceiling_audit is False, (
            "ceiling audit re-emitted on subsequent 429 at ceiling; "
            "the _ceiling_audit_emitted flag is not blocking re-emit "
            "within the same ceiling stretch."
        )


def test_backoff_ceiling_audit_emits_exactly_once_at_transition() -> None:
    """The audit signal fires only on the transition INTO the ceiling
    step (step 4 → step 5), not on earlier escalations and not on
    repeated 429s while already at the ceiling."""
    sm = BackoffStateMachine(intervals_seconds=_TEST_INTERVALS)
    # First four 429s: no ceiling audit signal.
    for _ in range(4):
        update = sm.update(_rate_limited_result())
        assert update.should_emit_ceiling_audit is False
    # Fifth 429: transition into ceiling — audit signal fires.
    update = sm.update(_rate_limited_result())
    assert update.current_interval_seconds == 0.5
    assert update.should_emit_ceiling_audit is True


def test_backoff_success_resets_to_step_zero() -> None:
    """A successful poll resets the state machine regardless of the
    current step. Encodes the 'reset to default on first successful
    poll' rule from project_subscription_first_architecture memory
    § B-4."""
    sm = BackoffStateMachine(intervals_seconds=_TEST_INTERVALS)
    # Drive to ceiling.
    for _ in range(5):
        sm.update(_rate_limited_result())
    assert sm.at_ceiling is True

    update = sm.update(_success_result())
    assert update.previous_interval_seconds == 0.5
    assert update.current_interval_seconds == 0.0
    assert update.should_emit_ceiling_audit is False
    assert sm.at_ceiling is False


def test_backoff_success_after_reset_re_arms_ceiling_audit() -> None:
    """After a successful-poll reset, a subsequent ceiling stretch
    emits a fresh audit signal — the flag clears on reset so the next
    stretch is observable as a distinct operator-visible event.

    Failure mode this test prevents: a future commit that forgets to
    clear the _ceiling_audit_emitted flag on reset would silently
    suppress all subsequent ceiling-reached audits, leaving the
    operator blind to repeated rate-limit stretches.
    """
    sm = BackoffStateMachine(intervals_seconds=_TEST_INTERVALS)
    # First ceiling stretch.
    for _ in range(4):
        sm.update(_rate_limited_result())
    first_ceiling = sm.update(_rate_limited_result())
    assert first_ceiling.should_emit_ceiling_audit is True

    # Successful poll resets.
    sm.update(_success_result())

    # Second ceiling stretch — fresh audit must fire.
    for _ in range(4):
        sm.update(_rate_limited_result())
    second_ceiling = sm.update(_rate_limited_result())
    assert second_ceiling.should_emit_ceiling_audit is True, (
        "second ceiling stretch did not emit a fresh audit signal; "
        "the _ceiling_audit_emitted flag was not cleared on the "
        "intermediate successful-poll reset."
    )


def test_backoff_non_429_non_success_holds_state() -> None:
    """Non-2xx non-429 responses (5xx, etc.) do not change state.
    A 5xx during normal operation does not trigger backoff; a 5xx
    during backoff does not escalate further or reset.

    Rationale per BackoffStateMachine docstring: 5xx is a transient
    outage signal, not a rate-limit signal. The 60s+ backoff is for
    rate limits specifically; treating 5xx as a rate limit would
    over-back-off during transient endpoint outages.
    """
    sm = BackoffStateMachine(intervals_seconds=_TEST_INTERVALS)
    # 5xx during normal: no escalation.
    update = sm.update(_server_error_result())
    assert update.current_interval_seconds == 0.0
    assert sm.at_ceiling is False

    # Drive to step 2 via 429s.
    sm.update(_rate_limited_result())
    sm.update(_rate_limited_result())
    assert sm.current_interval_seconds == 0.2

    # 5xx during backoff: stay at step 2, don't escalate or reset.
    update = sm.update(_server_error_result())
    assert update.previous_interval_seconds == 0.2
    assert update.current_interval_seconds == 0.2


def test_backoff_exception_path_holds_state() -> None:
    """A None result (the poll loop's exception path) does not change
    state, same as non-429 non-success. A network exception is not a
    rate-limit signal."""
    sm = BackoffStateMachine(intervals_seconds=_TEST_INTERVALS)
    sm.update(_rate_limited_result())
    sm.update(_rate_limited_result())
    sm.update(_rate_limited_result())
    assert sm.current_interval_seconds == 0.3

    update = sm.update(None)
    assert update.previous_interval_seconds == 0.3
    assert update.current_interval_seconds == 0.3


def test_backoff_constructor_rejects_wrong_ladder_length() -> None:
    """The state machine is not generic over arbitrary ladder depths
    — the spec defines a 6-step ladder. Passing a shorter or longer
    ladder is a programming error and must surface immediately."""
    with pytest.raises(ValueError, match=r"6 intervals"):
        BackoffStateMachine(intervals_seconds=(0.0, 0.1, 0.2))
    with pytest.raises(ValueError, match=r"6 intervals"):
        BackoffStateMachine(
            intervals_seconds=(0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6)
        )


# ---------------------------------------------------------------------------
# OAuthUsagePoller — ceiling-reached audit emission
# ---------------------------------------------------------------------------


async def test_poller_emits_ceiling_audit_exactly_once_per_stretch(
    storage: SqliteStorage,
) -> None:
    """End-to-end through the poll loop: five consecutive 429s drive
    the state machine to ceiling on the fifth poll;
    ``oauth_usage.polling_ceiling_reached`` audit row is written
    exactly once. Subsequent 429s at ceiling do not write additional
    rows.

    The test uses scaled-down backoff intervals
    (sub-millisecond) so the loop completes within the test's wait
    window. State-machine correctness is unit-tested separately;
    this test verifies the poller's integration with it.
    """
    fetch_count = 0
    target_429s = 7  # 5 → ceiling, then 2 more at ceiling

    async def always_429() -> SnapshotFetchResult:
        nonlocal fetch_count
        fetch_count += 1
        return _rate_limited_result()

    sm = BackoffStateMachine(intervals_seconds=_TEST_INTERVALS)
    poller = OAuthUsagePoller(
        storage=storage,
        snapshot_fetcher=always_429,
        poll_interval_seconds=0.0,
        backoff_state=sm,
    )
    await poller.start()
    try:
        # Wait until we've observed enough fetches to traverse the
        # ladder and stay at ceiling.
        for _ in range(500):
            if fetch_count >= target_429s:
                break
            await asyncio.sleep(0.01)
    finally:
        await poller.shutdown()

    audits = await storage.fetch_all(
        "SELECT id, actor, action, target, details_json "
        "FROM audit_log WHERE action = 'oauth_usage.polling_ceiling_reached'"
    )
    assert len(audits) == 1, (
        f"expected exactly one ceiling-reached audit row across "
        f"{fetch_count} consecutive 429s, got {len(audits)}. "
        "The poller is either re-emitting on subsequent 429s at "
        "ceiling or failing to emit on the transition into ceiling."
    )
    audit = audits[0]
    assert audit["actor"] == "system"
    assert audit["target"] is None
    details = json.loads(audit["details_json"])
    # Ceiling interval is the last entry in the test ladder.
    assert details["interval_seconds"] == pytest.approx(0.5)
    assert details["from_interval_seconds"] == pytest.approx(0.4)


async def test_poller_emits_fresh_ceiling_audit_after_reset(
    storage: SqliteStorage,
) -> None:
    """Two ceiling stretches separated by a successful poll produce
    two audit rows. Encodes the 'one stretch = one audit' contract
    per project memory § B-4 — the operator sees each ceiling event
    as a distinct row.

    Failure mode this test prevents: a future commit that fails to
    clear the ceiling-audit-emitted flag on reset would suppress all
    audits beyond the first stretch, leaving the operator blind to
    sustained or repeated rate-limit episodes.
    """
    call_count = 0

    async def staged_fetcher() -> SnapshotFetchResult:
        nonlocal call_count
        call_count += 1
        # First 5 calls: 429 (stretch 1, reaches ceiling on 5th)
        # 6th call: 200 success (resets state machine)
        # Calls 7-11: 429 (stretch 2, reaches ceiling on 11th)
        if call_count <= 5:
            return _rate_limited_result()
        if call_count == 6:
            return _success_result()
        return _rate_limited_result()

    sm = BackoffStateMachine(intervals_seconds=_TEST_INTERVALS)
    poller = OAuthUsagePoller(
        storage=storage,
        snapshot_fetcher=staged_fetcher,
        poll_interval_seconds=0.0,
        backoff_state=sm,
    )
    await poller.start()
    try:
        for _ in range(500):
            if call_count >= 12:
                break
            await asyncio.sleep(0.01)
    finally:
        await poller.shutdown()

    audits = await storage.fetch_all(
        "SELECT details_json FROM audit_log "
        "WHERE action = 'oauth_usage.polling_ceiling_reached' "
        "ORDER BY rowid"
    )
    assert len(audits) == 2, (
        f"expected two ceiling-reached audit rows (one per stretch), "
        f"got {len(audits)}. The successful-poll reset between "
        "stretches did not re-arm the ceiling audit."
    )
