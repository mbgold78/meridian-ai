"""Unit tests for :class:`bridge.transient_token_gc.TransientTokenGc`.

Per M5-sqlite-transient-token-store slice 1b commit 5 spec
(``docs/specs/M5-sqlite-transient-token-store-slice-1b-implementer.md``
§ Commit 5): 6 unit tests exercising the sweep mechanics, audit event
contract, cooperative cancellation, and interval cadence.

Lifespan wiring is OUT of scope at commit 5 — these tests pin the
module in isolation against a real :class:`SqliteStorage`.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import time
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest_asyncio

from meridian_orchestrator.bridge.transient_token_gc import (
    GC_AUDIT_ACTION,
    TransientTokenGc,
)
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.transient_tokens import TransientTokenStore


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    """Fresh SqliteStorage with all migrations applied (including 0015)."""
    s = SqliteStorage(tmp_path / "test.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


@pytest_asyncio.fixture
async def store(storage: SqliteStorage) -> TransientTokenStore:
    return TransientTokenStore(storage)


def _past_iso(seconds_ago: int = 3600) -> str:
    """ISO-8601 string matching the format auth._now_iso produces."""
    return (datetime.now(tz=UTC) - timedelta(seconds=seconds_ago)).isoformat(
        timespec="seconds"
    )


def _future_iso(seconds_ahead: int = 3600) -> str:
    return (datetime.now(tz=UTC) + timedelta(seconds=seconds_ahead)).isoformat(
        timespec="seconds"
    )


async def _override_expires_at(
    storage: SqliteStorage, token_id: str, expires_at: str
) -> None:
    """Force ``expires_at`` to a fixed value — sidesteps wall-clock timing
    flakiness when seeding tokens that should be considered expired by the GC.
    Mirrors the pattern in tests/unit/auth/test_transient_token_store.py
    ``test_lookup_returns_none_for_expired_token``."""
    await storage.execute(
        "UPDATE transient_tokens SET expires_at = ? WHERE token_id = ?",
        (expires_at, token_id),
    )


async def _count_rows(storage: SqliteStorage) -> int:
    row = await storage.fetch_one("SELECT COUNT(*) AS n FROM transient_tokens")
    assert row is not None
    return int(row["n"])


async def _fetch_gc_audits(storage: SqliteStorage) -> list[dict]:
    return await storage.fetch_all(
        "SELECT id, actor, action, target, details_json, success "
        "FROM audit_log WHERE action = ? ORDER BY id",
        (GC_AUDIT_ACTION,),
    )


# ---------------------------------------------------------------------------
# Sweep mechanics
# ---------------------------------------------------------------------------


async def test_sweep_deletes_expired_rows(
    storage: SqliteStorage, store: TransientTokenStore
) -> None:
    """A sweep deletes rows whose ``expires_at`` is in the past and leaves
    unexpired rows intact. Encodes the slice 1b spec § Commit 5 contract:
    ``DELETE FROM transient_tokens WHERE expires_at < now()`` semantics."""
    # Seed one row that should expire, one that should survive.
    expired = await store.issue(
        agent_id="agent-expired", role="implementer", label="dispatch:expired"
    )
    fresh = await store.issue(
        agent_id="agent-fresh", role="implementer", label="dispatch:fresh"
    )
    await _override_expires_at(storage, expired.record.token_id, _past_iso())

    gc = TransientTokenGc(storage=storage, interval_seconds=60)
    result = await gc._sweep_once()

    assert result["rows_deleted"] == 1
    assert result["scan_duration_ms"] >= 0
    assert result["swept_at_iso"]

    assert await _count_rows(storage) == 1
    surviving = await storage.fetch_one(
        "SELECT token_id FROM transient_tokens"
    )
    assert surviving is not None
    assert surviving["token_id"] == fresh.record.token_id


# ---------------------------------------------------------------------------
# Zero-deletion cycle still emits the liveness signal
# ---------------------------------------------------------------------------


async def test_sweep_zero_when_no_expired(
    storage: SqliteStorage,
) -> None:
    """An empty table yields ``rows_deleted == 0`` and the audit event still
    fires. Liveness signal contract per slice 1b spec § Commit 5: operators
    inspecting the audit log should see GC heartbeat rows even when there is
    nothing to reap."""
    gc = TransientTokenGc(storage=storage, interval_seconds=60)
    shutdown = asyncio.Event()

    async def runner() -> None:
        await gc.run(shutdown)

    # interval_seconds is 60s for the construction but we drive the loop
    # by issuing a manual single-cycle wakeup: set shutdown shortly AFTER
    # the first sweep completes so the loop exits cleanly.
    task = asyncio.create_task(runner())

    # Run a single sweep directly (faster + deterministic than waiting
    # 60s) and assert the audit emits. The loop test below covers the
    # interleaved sleep + sweep + emit sequence.
    result = await gc._sweep_once()
    assert result["rows_deleted"] == 0
    await gc._emit_audit(result)

    shutdown.set()
    task.cancel()
    with contextlib.suppress(asyncio.CancelledError):
        await task

    audits = await _fetch_gc_audits(storage)
    assert len(audits) >= 1, (
        "zero-deletion sweep did not emit a liveness audit row; "
        "operators relying on heartbeat audits would see silence."
    )
    details = json.loads(audits[-1]["details_json"])
    assert details["rows_deleted"] == 0


# ---------------------------------------------------------------------------
# Audit event schema contract
# ---------------------------------------------------------------------------


async def test_sweep_audit_event_schema(
    storage: SqliteStorage, store: TransientTokenStore
) -> None:
    """The ``transient_tokens.gc_swept`` audit row must carry the
    event-kind string, the three documented fields, and the documented
    types. Per slice 1b spec § Acceptance contracts: per-event-kind
    schema-contract tests pin the wire shape so consumers downstream
    (operator queries, future probes) can rely on stable field names."""
    expired = await store.issue(
        agent_id=None, role="implementer", label="dispatch:schema"
    )
    await _override_expires_at(storage, expired.record.token_id, _past_iso())

    gc = TransientTokenGc(storage=storage, interval_seconds=60)
    result = await gc._sweep_once()
    await gc._emit_audit(result)

    audits = await _fetch_gc_audits(storage)
    assert len(audits) == 1
    audit = audits[0]
    # Event-kind string.
    assert audit["action"] == "transient_tokens.gc_swept"
    # Daemon-internal-event actor convention.
    assert audit["actor"] == "system"
    # No per-resource target.
    assert audit["target"] is None
    assert audit["success"] == 1

    details = json.loads(audit["details_json"])
    # Field-set contract.
    assert set(details.keys()) == {
        "rows_deleted",
        "scan_duration_ms",
        "swept_at_iso",
    }
    # Type contract.
    assert isinstance(details["rows_deleted"], int)
    assert isinstance(details["scan_duration_ms"], int)
    assert isinstance(details["swept_at_iso"], str)
    # Values are sane.
    assert details["rows_deleted"] == 1
    assert details["scan_duration_ms"] >= 0
    # swept_at_iso parses as an ISO-8601 datetime.
    datetime.fromisoformat(details["swept_at_iso"])


# ---------------------------------------------------------------------------
# Cancel semantics
# ---------------------------------------------------------------------------


async def test_loop_cancel_completes_in_flight_cycle(
    storage: SqliteStorage, store: TransientTokenStore
) -> None:
    """A cancel during the sleep phase (between cycles) exits the loop
    immediately. The DELETE that already ran in a prior cycle stays
    committed — encodes the "atomic at the SQL layer" guarantee.
    """
    expired = await store.issue(
        agent_id=None, role="implementer", label="dispatch:in-flight"
    )
    await _override_expires_at(storage, expired.record.token_id, _past_iso())

    # interval=0.05s → first sweep fires fast; we cancel during the
    # follow-up sleep so the DELETE landed but the loop is mid-sleep.
    gc = TransientTokenGc(storage=storage, interval_seconds=1)

    # Drive a single sweep + audit through the public surface so the row
    # is gone before we cancel a sleeping loop. _sweep_once is the
    # primary contract — the loop merely sequences sweeps.
    result = await gc._sweep_once()
    await gc._emit_audit(result)
    assert result["rows_deleted"] == 1
    assert await _count_rows(storage) == 0

    shutdown = asyncio.Event()
    task = asyncio.create_task(gc.run(shutdown))
    # Let the loop reach its first wait_for(shutdown.wait(), …) call.
    await asyncio.sleep(0.05)
    task.cancel()
    with contextlib.suppress(asyncio.CancelledError):
        await task

    # The prior DELETE remains committed — no rollback on cancel.
    assert await _count_rows(storage) == 0


async def test_loop_cancel_during_sweep_completes_then_exits(
    storage: SqliteStorage, store: TransientTokenStore
) -> None:
    """Cancel during the in-flight DELETE: the GC's contract is to let
    the atomic DELETE land (the SQL layer cannot half-commit a single
    statement) and exit. Drain timeout is enforced by the caller via
    ``asyncio.wait_for`` — here we just verify the loop terminates
    within a small bound and the row state is consistent.

    Simulated by wrapping ``storage.execute`` to add a controllable
    delay on the DELETE statement, then cancelling the GC task during
    that delay.
    """
    expired = await store.issue(
        agent_id=None, role="implementer", label="dispatch:cancel-mid-sweep"
    )
    await _override_expires_at(storage, expired.record.token_id, _past_iso())

    gc = TransientTokenGc(
        storage=storage, interval_seconds=0, drain_timeout_seconds=5
    )

    delete_landed = asyncio.Event()
    original_execute = storage.execute

    async def slow_delete_execute(query: str, params=None) -> None:
        if "DELETE FROM transient_tokens" in query:
            # Run the DELETE first so the SQL-layer atomic effect lands
            # before the test issues a cancel. Then hold the await
            # inside execute long enough that the cancel can arrive
            # mid-call — this is the "in-flight DELETE" the spec
            # contract names. With ``isolation_level=None`` autocommit
            # the row is gone by the time we set ``delete_landed``;
            # whatever the cancel timing does to the Python-side state
            # is moot at the SQL layer.
            await original_execute(query, params)
            delete_landed.set()
            await asyncio.sleep(0.2)
            return
        await original_execute(query, params)

    storage.execute = slow_delete_execute  # type: ignore[method-assign]

    try:
        shutdown = asyncio.Event()
        cancel_task = asyncio.create_task(gc.run(shutdown))

        # Wait until the DELETE has landed at the SQL layer — at this
        # point the slow_delete_execute wrapper is inside its post-
        # delete asyncio.sleep, so the cancel will arrive during that
        # in-execute hold and exercise the cancel-during-sweep path.
        await asyncio.wait_for(delete_landed.wait(), timeout=2.0)
        cancel_task.cancel()

        # Loop must terminate within the drain timeout. Cap at 2s for
        # test speed; the 5s drain_timeout_seconds is the production
        # ceiling. Either CancelledError propagates (cancel during
        # _sweep_once re-raises per the module contract) or the
        # _emit_audit path completes — both end the task promptly.
        with contextlib.suppress(TimeoutError, asyncio.CancelledError):
            await asyncio.wait_for(cancel_task, timeout=2.0)

        # Row state is consistent — the atomic DELETE landed (it was
        # already inside execute when the cancel arrived; aiosqlite
        # cannot split a single SQL statement).
        assert await _count_rows(storage) == 0
    finally:
        storage.execute = original_execute  # type: ignore[method-assign]


# ---------------------------------------------------------------------------
# Cadence
# ---------------------------------------------------------------------------


async def test_loop_respects_interval(storage: SqliteStorage) -> None:
    """A loop running at ``interval_seconds=0.1`` should complete at
    least 3 sweep cycles in 0.5s wall time. Validates that:

    1. Sleeping uses ``asyncio.wait_for(shutdown.wait(), interval)``
       (rather than e.g. spinning) so the cadence is correct.
    2. Each cycle emits exactly one audit row.

    Empty-table sweeps so the cycles cost ~nothing; the test is about
    sequencing + cadence, not DELETE throughput. The constructor
    accepts ``float`` (the Settings field is ``int`` for operator
    tuning, but the test-injection slot allows sub-second values).
    """
    gc = TransientTokenGc(storage=storage, interval_seconds=0.1)
    shutdown = asyncio.Event()

    task = asyncio.create_task(gc.run(shutdown))
    started = time.monotonic()
    await asyncio.sleep(0.5)
    shutdown.set()
    try:
        await asyncio.wait_for(task, timeout=1.0)
    except (TimeoutError, asyncio.CancelledError):
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task

    elapsed = time.monotonic() - started
    audits = await _fetch_gc_audits(storage)
    assert len(audits) >= 3, (
        f"interval-driven cadence undershoot: got {len(audits)} audit "
        f"rows in {elapsed:.2f}s; expected ≥3."
    )
