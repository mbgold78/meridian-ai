"""Background GC task for the SQLite-backed transient-token store.

Per ratified slice 1b spec (``docs/specs/M5-sqlite-transient-token-store-
slice-1b-implementer.md`` § Commit 5), the daemon runs a long-lived
cooperative loop that reaps expired ``transient_tokens`` rows on a
configurable interval. Without it, the SQLite table grows unbounded as
per-dispatch / per-spawn tokens accumulate past their TTL — the same
operational failure mode (β-S11-5 / β-S11-6) that motivated moving
transient tokens off the keyring at slice 1a.

Lifecycle (slice 1b commit 6 will wire this in; commit 5 ships the
module dormant):

- ``TransientTokenGc(storage, interval_seconds=…)`` constructed in the
  daemon's lifespan body after :class:`TransientTokenStore` is in
  place.
- ``await gc.run(shutdown_event)`` runs on the lifespan-scoped task
  group. The shutdown ``asyncio.Event`` is set during teardown; the
  loop exits cleanly on the next cycle (or immediately if it was
  sleeping).
- The caller enforces a drain timeout via :func:`asyncio.wait_for` —
  per ratified decision A5, 5s default. Inside the loop we cooperate
  with cancellation: a cancel during sleep exits immediately; a cancel
  during the in-flight DELETE is suppressed long enough to let the
  atomic statement finish (no half-state at the SQL layer), then we
  re-raise so the caller's drain timeout terminates the task.

Audit contract:

- Event kind: ``transient_tokens.gc_swept``
- Fields: ``{rows_deleted: int, scan_duration_ms: int, swept_at_iso: str}``
- Emitted every cycle, including zero-deletion cycles. The liveness
  signal is the point — an operator inspecting the audit log can tell
  the GC is running even when no expired rows exist.

The DELETE comparison passes a Python-computed "now" string as a
bind parameter rather than calling SQLite's ``datetime('now')`` or
``strftime`` builtins. The expires_at column is written by
:meth:`TransientTokenStore.issue` via :func:`auth._now_iso` +
``isoformat(timespec="seconds")`` — the resulting strings carry the
``+00:00`` UTC offset suffix (``'YYYY-MM-DDTHH:MM:SS+00:00'``).
SQLite's native ``datetime('now')`` returns ``'YYYY-MM-DD HH:MM:SS'``
(no T separator, no offset) and ``strftime('%Y-%m-%dT%H:%M:%S', 'now')``
strips the offset — neither aligns lexicographically with the stored
offset-suffixed strings. Computing "now" the same way :func:`_now_iso`
does and binding it as a parameter gives byte-identical format and
direct lexicographic comparison on the indexed ``expires_at`` column
(``transient_tokens_expires_at_idx``).
"""

from __future__ import annotations

import asyncio
import time
from datetime import UTC, datetime
from typing import Any

import structlog

from meridian_orchestrator.audit.logger import write_audit
from meridian_orchestrator.storage.interface import Storage

logger = structlog.get_logger(__name__)

GC_AUDIT_ACTION: str = "transient_tokens.gc_swept"
DEFAULT_DRAIN_TIMEOUT_SECONDS: int = 5


class TransientTokenGc:
    """Daemon-side background task that reaps expired transient tokens.

    Construct once at lifespan-start; await :meth:`run` on the
    lifespan-scoped task group. ``shutdown`` is an ``asyncio.Event``
    that the lifespan teardown sets to signal a clean exit; the loop
    exits at the next cycle boundary (or immediately if it was
    sleeping). Caller enforces an outer drain timeout via
    :func:`asyncio.wait_for` per ratified decision A5.

    The class only owns the cooperative-cancellation contract; it does
    not enforce its own timeout. ``drain_timeout_seconds`` is held on
    the instance so the wiring layer can read the documented default
    without re-importing the module constant.
    """

    def __init__(
        self,
        storage: Storage,
        interval_seconds: float,
        drain_timeout_seconds: int = DEFAULT_DRAIN_TIMEOUT_SECONDS,
    ) -> None:
        """Construct the GC task.

        ``interval_seconds`` is typed ``float`` rather than the spec
        sample's ``int`` so the slice 1b commit 5 spec
        ``test_loop_respects_interval`` (interval=0.1s) can drive the
        loop at sub-second cadence. The :class:`Settings` field
        ``transient_token_gc_interval_seconds`` remains ``int`` per
        operator-tunable-with-integer-defaults convention (see
        :mod:`config`); an int upcasts to float silently when used as a
        constructor argument.
        """
        self._storage = storage
        self._interval_seconds = float(interval_seconds)
        self._drain_timeout_seconds = drain_timeout_seconds

    @property
    def interval_seconds(self) -> float:
        return self._interval_seconds

    @property
    def drain_timeout_seconds(self) -> int:
        return self._drain_timeout_seconds

    async def run(self, shutdown: asyncio.Event) -> None:
        """Run the cooperative GC loop until ``shutdown`` is set.

        Cycle: sleep(interval) → :meth:`_sweep_once` → audit emit →
        repeat. The ``shutdown.wait()`` interleaves with the sleep so a
        teardown signal mid-sleep exits the loop immediately. An
        ``asyncio.CancelledError`` (caller invoked ``task.cancel()``
        rather than ``shutdown.set()``) raised mid-sleep also exits
        the loop; raised during the in-flight DELETE, we let the
        atomic statement complete and the audit fire so the audit log
        carries the final cycle's row, then re-raise to surrender to
        the caller's drain timeout.
        """
        while not shutdown.is_set():
            try:
                await asyncio.wait_for(
                    shutdown.wait(), timeout=self._interval_seconds
                )
                # shutdown.set() resolved the wait → clean exit before sweep.
                return
            except TimeoutError:
                # Interval elapsed without a shutdown signal → run a sweep.
                pass
            except asyncio.CancelledError:
                # task.cancel() during sleep → exit immediately, no sweep.
                raise

            try:
                result = await self._sweep_once()
            except asyncio.CancelledError:
                # Cancel during the in-flight DELETE: aiosqlite's
                # cancellation behavior on a single statement is
                # implementation-defined, but the DELETE is atomic at
                # the SQL layer regardless. Re-raise to surrender to
                # the caller's drain timeout — we do not attempt a
                # post-cancel audit emit because the loop's contract is
                # "one audit per completed sweep". A cancel during
                # sweep is not a completed sweep.
                raise

            await self._emit_audit(result)

    async def _sweep_once(self) -> dict[str, Any]:
        """Execute one DELETE-where-expired cycle.

        Computes the comparison threshold the same way
        :func:`auth._now_iso` produces ``expires_at`` strings so the
        comparison is byte-aligned (see module docstring for the
        format-alignment rationale). The comparison runs against the
        ``transient_tokens_expires_at_idx`` index added at the slice 1a
        migration so the scan stays cheap as the table grows.

        Deliberately runs *without* a wrapping transaction. SqliteStorage
        opens its aiosqlite connection with ``isolation_level=None``
        (autocommit), so each statement is its own atomic write at the
        SQL layer. Wrapping the COUNT + DELETE in
        ``self._storage.transaction()`` would mean a mid-sweep
        :class:`asyncio.CancelledError` propagates through the context
        manager's ``__aexit__``, which catches the exception and runs
        ROLLBACK — undoing the DELETE and contradicting the
        "atomic at SQL layer" / "let the DELETE complete" guarantee in
        the slice 1b spec § Commit 5 cancel-aware contract. With
        autocommit, the DELETE's effect persists past cancel
        regardless of Python-side timing because aiosqlite dispatches
        the C-API call to an executor thread that runs to completion.

        Returns a dict matching the ``transient_tokens.gc_swept`` event
        contract: ``{rows_deleted, scan_duration_ms, swept_at_iso}``.
        ``swept_at_iso`` is the wall-clock UTC moment we started the
        sweep (not the moment we finished) so operators correlating
        audit rows against expiration timestamps see "what was expired
        as of this moment" rather than "what was expired by the time
        we got here".

        COUNT-vs-DELETE consistency: GC is the only deleter of
        ``transient_tokens`` rows; writers (:meth:`TransientTokenStore.issue`)
        only insert rows with ``expires_at > now()`` (no malformed
        already-expired inserts). So the COUNT result is byte-identical
        to the rowcount the DELETE would report — even without
        transactional isolation between the two statements.
        """
        sweep_start = datetime.now(tz=UTC)
        swept_at_iso = sweep_start.isoformat(timespec="seconds")
        perf_start = time.perf_counter()

        # COUNT first — aiosqlite does not expose rowcount through the
        # Storage protocol, so we count separately. See method docstring
        # for the consistency rationale (GC is the only deleter; writers
        # only insert future-expires rows).
        count_row = await self._storage.fetch_one(
            "SELECT COUNT(*) AS rows_to_delete "
            "FROM transient_tokens WHERE expires_at < ?",
            (swept_at_iso,),
        )
        rows_deleted = int(count_row["rows_to_delete"]) if count_row else 0
        await self._storage.execute(
            "DELETE FROM transient_tokens WHERE expires_at < ?",
            (swept_at_iso,),
        )

        scan_duration_ms = int((time.perf_counter() - perf_start) * 1000)
        return {
            "rows_deleted": rows_deleted,
            "scan_duration_ms": scan_duration_ms,
            "swept_at_iso": swept_at_iso,
        }

    async def _emit_audit(self, result: dict[str, Any]) -> None:
        """Emit the ``transient_tokens.gc_swept`` audit row.

        Failure to write the audit row logs an error but does not
        crash the GC loop — symmetric with
        :meth:`OAuthUsagePoller._emit_ceiling_reached_audit`'s tradeoff:
        keeping the long-running task alive matters more than a
        single missed audit row. Actor is ``"system"`` per the
        daemon-internal-event convention documented on
        :func:`audit.logger.write_audit`.
        """
        try:
            await write_audit(
                self._storage,
                actor="system",
                action=GC_AUDIT_ACTION,
                target=None,
                details=result,
            )
        except Exception as exc:
            logger.error(
                "transient_token_gc.audit_emit_failed",
                error=str(exc),
                error_type=type(exc).__name__,
            )


__all__ = [
    "DEFAULT_DRAIN_TIMEOUT_SECONDS",
    "GC_AUDIT_ACTION",
    "TransientTokenGc",
]
