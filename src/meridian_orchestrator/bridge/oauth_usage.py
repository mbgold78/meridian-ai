"""OAuth usage poller for ``/api/oauth/usage``.

Background asyncio task that polls Anthropic's subscription-utilization
endpoint at 30s cadence per ``project_subscription_first_architecture``
memory § Daemon-side polling. The cached snapshot is the single source
of truth for downstream bucket-utilization queries
(``get_window_state`` directly; ``get_token_usage`` / ``get_burn_rate``
/ ``check_budget_for_task`` join the cached snapshot against
``api_usage`` data at M5-subscription-routing).

**M5-pm-mode-and-window-state commit 1 scope** (this commit):

- Lifecycle: ``start()`` / ``shutdown()`` mirroring the supervisor
  pattern in :mod:`bridge.supervisor_base`.
- Happy-path snapshot caching with ``polled_at`` timestamp; consumers
  compute staleness via :meth:`CachedSnapshot.age_seconds`.
- Null counter detection (B-1 reading B per the project memory):
  every poll computes the list of buckets whose counter is null;
  INFO-logged when non-empty; exposed on the cached snapshot for
  ``get_window_state``'s ``null_counters`` field.
- Default fetcher uses ``httpx`` with the ``anthropic-beta:
  oauth-2025-04-20`` header.
- Non-2xx responses and fetcher exceptions log and skip the cycle —
  the loop continues with the previously-cached snapshot intact.

**Deferred to commit 2 of this sub-milestone**:

- 429 backoff state machine (60→120→240→480→600s ceiling, reset to
  30s on first successful poll).
- ``oauth_usage.polling_ceiling_reached`` audit row at ceiling.

The split is deliberate: commit 1 ships a working poller (sufficient
to populate ``get_window_state`` in normal operation); commit 2 adds
the operational hardening for the rate-limited path. Each commit is
coherent on its own.

**Production wiring** (deferred to a downstream commit in this chain):
the daemon's ``main.py`` lifespan constructs the poller after the
MCP server is ready, passing ``CLAUDE_CODE_OAUTH_TOKEN`` from the
operator's env. Shutdown ordering: poller stops before storage closes
(consistent with supervisor shutdown ordering).
"""

from __future__ import annotations

import asyncio
import contextlib
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any

import httpx
import structlog

from meridian_orchestrator.storage.interface import Storage

logger = structlog.get_logger(__name__)

OAUTH_USAGE_URL = "https://api.anthropic.com/api/oauth/usage"
ANTHROPIC_BETA_HEADER = "oauth-2025-04-20"
DEFAULT_POLL_INTERVAL_SECONDS = 30.0
DEFAULT_HTTP_TIMEOUT_SECONDS = 10.0

# Field-name → bucket-name mapping per
# ``project_subscription_first_architecture`` memory § Canonical bucket
# names. The poller reads raw field names from the
# ``/api/oauth/usage`` payload; downstream consumers
# (``get_window_state``) use canonical bucket names. This map is the
# single point of translation. Anthropic-internal codename fields
# (``tangelo``, ``iguana_necktie``, ``omelette*``, ``cowork``,
# ``oauth_apps``) are filtered as noise per the memory's Experiment 4
# findings.
_FIELD_TO_BUCKET: dict[str, str] = {
    "five_hour": "subscription_5h",
    "seven_day": "subscription_7d",
    "seven_day_opus": "subscription_7d_opus",
    "seven_day_sonnet": "subscription_7d_sonnet",
    "extra_usage": "api_key_overflow",
}


@dataclass
class SnapshotFetchResult:
    """Result of a single ``/api/oauth/usage`` fetch.

    ``status_code`` is the HTTP status; ``payload`` is the parsed JSON
    on 2xx success, ``None`` otherwise. Constructed by the production
    fetcher (httpx-based) or by tests passing a fake
    :data:`SnapshotFetcher` directly.
    """

    status_code: int
    payload: dict[str, Any] | None = None


@dataclass
class CachedSnapshot:
    """A cached ``/api/oauth/usage`` response with metadata.

    ``payload`` is the raw parsed JSON. ``polled_at`` is the wall-clock
    UTC timestamp at the moment the successful response was received.
    ``null_counters`` is the canonical-bucket-name list of counters
    that returned null on this poll (B-1 reading B per the project
    memory).
    """

    payload: dict[str, Any]
    polled_at: datetime
    null_counters: list[str]

    def age_seconds(self, now: datetime | None = None) -> int:
        """Wall-clock age of the snapshot in seconds (integer-truncated).

        ``now`` defaults to ``datetime.now(tz=UTC)``; tests pass an
        explicit time for deterministic age computation. Truncation
        (``int(...)``) is integer floor — sub-second remainder is
        dropped, mirroring ``snapshot_age_seconds: int`` on the
        ``get_window_state`` output schema.
        """
        moment = now if now is not None else datetime.now(tz=UTC)
        return int((moment - self.polled_at).total_seconds())


SnapshotFetcher = Callable[[], Awaitable[SnapshotFetchResult]]


def _detect_null_counters(payload: dict[str, Any]) -> list[str]:
    """Compute the canonical bucket names whose counter is null in this
    snapshot.

    A counter is "null" when the payload field is either:

    1. The literal value ``None`` (e.g., ``"seven_day_opus": null``), or
    2. A dict whose ``"utilization"`` key maps to ``None``
       (e.g., ``extra_usage`` with ``is_enabled: false``).

    Returned bucket names use the canonical subscription-first
    vocabulary; raw payload field names are translated through
    :data:`_FIELD_TO_BUCKET`. Codename fields not in the translation
    map are ignored (Anthropic-internal A/B / pre-launch features per
    the memory's Experiment 4 findings).
    """
    null_buckets: list[str] = []
    for field_name, bucket_name in _FIELD_TO_BUCKET.items():
        value = payload.get(field_name)
        if value is None:
            null_buckets.append(bucket_name)
            continue
        if isinstance(value, dict) and value.get("utilization") is None:
            null_buckets.append(bucket_name)
    return null_buckets


def _make_default_fetcher(oauth_token: str) -> SnapshotFetcher:
    """Build the production httpx-based snapshot fetcher.

    Each call constructs a fresh :class:`httpx.AsyncClient` via async
    context manager — cleanest lifecycle, and at 30s cadence the
    per-poll construction cost is negligible. The fetcher returns
    parsed JSON on 2xx; non-2xx responses return
    :class:`SnapshotFetchResult` with ``payload=None``. JSON parse
    failures on a 2xx response also return ``payload=None``
    (defensive: malformed body should not crash the loop).
    """
    headers = {
        "Authorization": f"Bearer {oauth_token}",
        "anthropic-beta": ANTHROPIC_BETA_HEADER,
    }

    async def fetch() -> SnapshotFetchResult:
        async with httpx.AsyncClient(
            timeout=DEFAULT_HTTP_TIMEOUT_SECONDS
        ) as client:
            response = await client.get(OAUTH_USAGE_URL, headers=headers)
            payload: dict[str, Any] | None = None
            if response.is_success:
                try:
                    parsed = response.json()
                except ValueError:
                    parsed = None
                if isinstance(parsed, dict):
                    payload = parsed
            return SnapshotFetchResult(
                status_code=response.status_code,
                payload=payload,
            )

    return fetch


class OAuthUsagePoller:
    """Background poller for ``/api/oauth/usage``.

    Lifecycle: :meth:`start` spawns the polling task;
    :meth:`shutdown` cancels and awaits cleanly.
    :meth:`latest_snapshot` returns the current cached snapshot or
    ``None`` if no successful poll has landed yet.

    Thread/concurrency model: the poller owns a single asyncio task;
    snapshot reads are race-free because Python's GIL makes the
    attribute read atomic and the writer (``_handle_result``) only
    runs on the same event loop. Multi-loop usage is not supported.
    """

    def __init__(
        self,
        *,
        storage: Storage,
        oauth_token: str | None = None,
        poll_interval_seconds: float = DEFAULT_POLL_INTERVAL_SECONDS,
        snapshot_fetcher: SnapshotFetcher | None = None,
    ) -> None:
        """Construct a poller.

        ``oauth_token`` (production) or ``snapshot_fetcher`` (tests)
        must be provided; passing neither raises :class:`ValueError`.
        When both are provided, ``snapshot_fetcher`` takes precedence
        (test-injection path).

        ``storage`` is held for the audit emission landing in commit 2
        of this sub-milestone (``oauth_usage.polling_ceiling_reached``);
        commit 1 does not write to storage but the dependency is
        threaded through now to avoid churning the constructor
        signature when commit 2 lands.
        """
        if snapshot_fetcher is None:
            if oauth_token is None:
                raise ValueError(
                    "OAuthUsagePoller requires either oauth_token "
                    "(production wiring) or snapshot_fetcher "
                    "(test injection)"
                )
            snapshot_fetcher = _make_default_fetcher(oauth_token)

        self._storage = storage
        self._poll_interval_seconds = poll_interval_seconds
        self._fetcher = snapshot_fetcher
        self._cached_snapshot: CachedSnapshot | None = None
        self._task: asyncio.Task[None] | None = None

    async def start(self) -> None:
        """Spawn the polling task. Idempotent — calling :meth:`start`
        twice is a no-op after the first call."""
        if self._task is not None:
            return
        self._task = asyncio.create_task(
            self._poll_loop(), name="oauth-usage-poller"
        )
        logger.info(
            "oauth_usage_poller.started",
            poll_interval_seconds=self._poll_interval_seconds,
        )

    async def shutdown(self) -> None:
        """Cancel the polling task and await its clean exit. Idempotent.

        Cancellation propagates through ``asyncio.sleep`` in the poll
        loop, so a poller mid-sleep on a 30s interval shuts down
        promptly without waiting for the next cycle.
        """
        if self._task is None:
            return
        task = self._task
        self._task = None
        task.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await task
        logger.info("oauth_usage_poller.shutdown")

    def latest_snapshot(self) -> CachedSnapshot | None:
        """Return the latest cached snapshot, or ``None`` if no
        successful poll has landed yet.

        Callers compute snapshot age via
        :meth:`CachedSnapshot.age_seconds`; the poller does not
        auto-expire stale snapshots — staleness decisions live with
        the consumer (``get_window_state``'s 120s threshold per
        ``project_subscription_first_architecture`` memory § Snapshot
        freshness).
        """
        return self._cached_snapshot

    async def _poll_loop(self) -> None:
        """Cancellation-safe polling loop.

        Each cycle: fetch → on 2xx-with-payload, cache snapshot; on any
        other outcome (non-2xx, exception), log and skip. Then sleep
        until the next poll. The 429 backoff state machine ships in
        commit 2; commit 1 treats all non-2xx responses uniformly as
        transient errors that skip a cycle without backoff.
        """
        while True:
            try:
                result: SnapshotFetchResult | None = await self._fetcher()
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.error(
                    "oauth_usage_poller.fetch_exception",
                    error=str(exc),
                    error_type=type(exc).__name__,
                )
                result = None

            if result is not None:
                self._handle_result(result)

            await asyncio.sleep(self._poll_interval_seconds)

    def _handle_result(self, result: SnapshotFetchResult) -> None:
        """Process a single poll result: cache on success; log on
        non-success.

        Non-success surfaces:

        - HTTP non-2xx: log warning with status code, leave cached
          snapshot intact.
        - 2xx with no parseable payload (defensive): log warning, leave
          cached snapshot intact.

        Commit 2's backoff state machine also dispatches off the status
        code at this layer.
        """
        if not (200 <= result.status_code < 300) or result.payload is None:
            logger.warning(
                "oauth_usage_poller.non_success_response",
                status_code=result.status_code,
                has_payload=result.payload is not None,
            )
            return

        polled_at = datetime.now(tz=UTC)
        null_counters = _detect_null_counters(result.payload)

        if null_counters:
            # B-1 reading B per project_subscription_first_architecture
            # memory: INFO log every poll cycle when any per-model
            # counter is null. Surfaces fallback-state buckets in
            # operator logs without requiring dashboard plumbing. Per
            # the memory: revisit after a week of operational data.
            logger.info(
                "oauth_usage_poller.null_counters",
                null_counters=null_counters,
            )

        self._cached_snapshot = CachedSnapshot(
            payload=result.payload,
            polled_at=polled_at,
            null_counters=null_counters,
        )


__all__ = [
    "ANTHROPIC_BETA_HEADER",
    "DEFAULT_POLL_INTERVAL_SECONDS",
    "OAUTH_USAGE_URL",
    "CachedSnapshot",
    "OAuthUsagePoller",
    "SnapshotFetchResult",
    "SnapshotFetcher",
]
