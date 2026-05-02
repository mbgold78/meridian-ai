"""OAuth usage poller for ``/api/oauth/usage``.

Background asyncio task that polls Anthropic's subscription-utilization
endpoint at 30s cadence per ``project_subscription_first_architecture``
memory § Daemon-side polling. The cached snapshot is the single source
of truth for downstream bucket-utilization queries
(``get_window_state`` directly; ``get_token_usage`` / ``get_burn_rate``
/ ``check_budget_for_task`` join the cached snapshot against
``api_usage`` data at M5-subscription-routing).

**Behaviors shipped across commits 1-2 of M5-pm-mode-and-window-state**:

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
- Non-2xx (non-429) responses and fetcher exceptions log and skip the
  cycle — the loop continues with the previously-cached snapshot
  intact and without changing the backoff state.
- 429 backoff state machine (commit 2): 30s default → 60 → 120 → 240
  → 480 → 600s ceiling on consecutive 429s. Reset to 30s on first
  successful poll. Per ``project_subscription_first_architecture``
  memory § Daemon-side polling.
- ``oauth_usage.polling_ceiling_reached`` audit row at ceiling
  (commit 2): emitted exactly once per ceiling stretch; cleared on
  successful-poll reset so a subsequent ceiling stretch emits a
  fresh audit.

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

from meridian_orchestrator.audit.logger import write_audit
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


# Canonical backoff intervals per Issue #31021 + project memory § B-4.
# Step 0 is the default cadence; steps 1-5 are the 429 backoff
# escalation. Step 5 is the ceiling — further 429s clamp here.
#
# The default-step value (DEFAULT_POLL_INTERVAL_SECONDS) is
# parameterizable via the BackoffStateMachine constructor so tests can
# substitute fast-cadence values without monkeypatching this constant.
_DEFAULT_BACKOFF_INTERVALS_SECONDS: tuple[float, ...] = (
    DEFAULT_POLL_INTERVAL_SECONDS,  # step 0 — normal cadence
    60.0,                           # step 1
    120.0,                          # step 2
    240.0,                          # step 3
    480.0,                          # step 4
    600.0,                          # step 5 — ceiling
)


@dataclass
class BackoffUpdateResult:
    """Result of a single :meth:`BackoffStateMachine.update` call.

    Returned from the state machine to inform the poller about what
    changed during this transition. The poller uses
    ``current_interval_seconds`` to drive the next sleep duration and
    ``should_emit_ceiling_audit`` to decide whether to emit the
    ``oauth_usage.polling_ceiling_reached`` audit row.

    ``previous_interval_seconds`` is exposed for diagnostics — log
    messages that name the transition direction ("escalated 240s →
    480s") read more clearly with both endpoints than with just the
    new value.
    """

    previous_interval_seconds: float
    current_interval_seconds: float
    should_emit_ceiling_audit: bool


class BackoffStateMachine:
    """Exponential backoff state machine for the OAuth usage poller.

    Tracks the current backoff step (0 = default cadence; 5 = ceiling)
    and whether the ceiling audit has been emitted for this stretch.

    **Transitions** (per ``project_subscription_first_architecture``
    memory § B-4):

    - Successful poll (2xx + parseable payload): reset to step 0,
      clear ceiling-audit-emitted flag.
    - 429 response: escalate step by 1, clamped at the ceiling.
    - Any other non-success (non-2xx non-429, JSON parse failure,
      fetcher exception/None): no state change. Conservative middle
      ground — a 5xx during normal operation doesn't trigger 60s+
      backoff (those are transient outages, not rate limits); a 5xx
      during backoff doesn't escalate further (the 429s are the
      thing being rate-limited) but doesn't reset either (the
      endpoint is in trouble, stay defensive).

    **Ceiling audit emission**: ``oauth_usage.polling_ceiling_reached``
    fires exactly once when transitioning INTO the ceiling step from
    below. Subsequent 429s at the ceiling do not emit. Returning to
    step 0 clears the flag so the next ceiling stretch emits a fresh
    audit row (each stretch is one operator-visible event).

    **Constructor parameter**: ``intervals_seconds`` accepts a tuple
    of 6 floats (step 0 through step 5). Tests pass scaled-down
    values for fast iteration; production uses
    :data:`_DEFAULT_BACKOFF_INTERVALS_SECONDS`. The class enforces
    exactly 6 entries — the spec defines a 6-step ladder and the
    state machine is not generic over arbitrary depths.
    """

    _CEILING_STEP: int = len(_DEFAULT_BACKOFF_INTERVALS_SECONDS) - 1

    def __init__(
        self,
        intervals_seconds: tuple[float, ...] = _DEFAULT_BACKOFF_INTERVALS_SECONDS,
    ) -> None:
        if len(intervals_seconds) != len(_DEFAULT_BACKOFF_INTERVALS_SECONDS):
            raise ValueError(
                f"BackoffStateMachine requires exactly "
                f"{len(_DEFAULT_BACKOFF_INTERVALS_SECONDS)} intervals "
                f"(default + 5 backoff steps); got {len(intervals_seconds)}"
            )
        self._intervals = intervals_seconds
        self._step = 0
        self._ceiling_audit_emitted = False

    @property
    def current_interval_seconds(self) -> float:
        """The interval to sleep before the next poll, given the
        current step."""
        return self._intervals[self._step]

    @property
    def at_ceiling(self) -> bool:
        """True when the state machine is at the ceiling step."""
        return self._step == self._CEILING_STEP

    def update(
        self, result: SnapshotFetchResult | None
    ) -> BackoffUpdateResult:
        """Process a poll outcome and transition state.

        ``result`` is the fetcher's :class:`SnapshotFetchResult`, or
        ``None`` when the fetcher raised an exception (the poll loop
        passes None for the exception path so the state machine sees
        a uniform "no useful response" signal).

        Returns a :class:`BackoffUpdateResult` describing the
        transition; the poll loop reads ``current_interval_seconds``
        for the next sleep and ``should_emit_ceiling_audit`` to decide
        whether to write the ``oauth_usage.polling_ceiling_reached``
        audit row.
        """
        previous_step = self._step
        previous_interval = self._intervals[previous_step]

        if (
            result is not None
            and 200 <= result.status_code < 300
            and result.payload is not None
        ):
            # Successful poll: reset to default cadence and clear the
            # ceiling-audit-emitted flag so the next ceiling stretch
            # gets a fresh audit row.
            self._step = 0
            self._ceiling_audit_emitted = False
        elif result is not None and result.status_code == 429:
            # Rate-limited: escalate one step, clamped at the ceiling.
            self._step = min(self._step + 1, self._CEILING_STEP)
        # else: non-2xx non-429, malformed payload, or exception — no
        # state change. See class docstring for the rationale.

        should_emit = (
            self._step == self._CEILING_STEP
            and previous_step != self._CEILING_STEP
            and not self._ceiling_audit_emitted
        )
        if should_emit:
            self._ceiling_audit_emitted = True

        return BackoffUpdateResult(
            previous_interval_seconds=previous_interval,
            current_interval_seconds=self._intervals[self._step],
            should_emit_ceiling_audit=should_emit,
        )


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
        backoff_state: BackoffStateMachine | None = None,
    ) -> None:
        """Construct a poller.

        ``oauth_token`` (production) or ``snapshot_fetcher`` (tests)
        must be provided; passing neither raises :class:`ValueError`.
        When both are provided, ``snapshot_fetcher`` takes precedence
        (test-injection path).

        ``backoff_state`` is the 429-backoff state machine. Defaults to
        a fresh :class:`BackoffStateMachine` constructed with the
        canonical intervals (30, 60, 120, 240, 480, 600). Tests pass
        scaled-down state machines so backoff transitions can be
        observed within seconds rather than minutes.

        ``storage`` is used by commit 2 of this sub-milestone to write
        the ``oauth_usage.polling_ceiling_reached`` audit row when the
        backoff state machine reaches the ceiling.
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

        if backoff_state is None:
            # Deliberate test-cadence support — do not collapse to
            # BackoffStateMachine() default. Derive step 0 from
            # poll_interval_seconds + canonical 60→120→240→480→600s
            # backoff ladder (steps 1-5).
            #
            # When poll_interval_seconds == DEFAULT_POLL_INTERVAL_SECONDS
            # (production), the derived tuple equals
            # _DEFAULT_BACKOFF_INTERVALS_SECONDS — same behavior as
            # BackoffStateMachine() with no args. When tests pass a
            # smaller poll_interval_seconds for fast cadence, step 0
            # picks it up while backoff ladder steps stay canonical.
            # Tests that need scaled-down ladders entirely pass an
            # explicit BackoffStateMachine via the backoff_state param.
            #
            # This looks like redundant derivation but is load-bearing:
            # without it, three commit-1 tests (test_poller_skips_*)
            # would block on 30s sleeps instead of test-fast cadence.
            derived_intervals = (
                poll_interval_seconds,
                *_DEFAULT_BACKOFF_INTERVALS_SECONDS[1:],
            )
            self._backoff = BackoffStateMachine(
                intervals_seconds=derived_intervals
            )
        else:
            self._backoff = backoff_state

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
            poll_interval_seconds=self._backoff.current_interval_seconds,
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

        Each cycle:

        1. Fetch ``/api/oauth/usage`` (or capture the exception path).
        2. Update the backoff state machine — drives the next sleep
           interval and signals when to emit the ceiling audit.
        3. Emit the ``oauth_usage.polling_ceiling_reached`` audit row
           if the state machine just transitioned into the ceiling
           step (exactly once per ceiling stretch).
        4. Cache the snapshot if the response was successful.
        5. Sleep ``backoff.current_interval_seconds`` until the next
           cycle. Cancellation propagates through ``asyncio.sleep`` so
           shutdown is prompt regardless of the current interval.

        State-machine ordering note: ``update`` is called *before*
        ``_handle_result`` so the audit emission and snapshot caching
        observe the same transition. The order doesn't matter for
        correctness (the two operations are independent) but the
        symmetric "result observed → state updated → side effects"
        flow reads clearly.
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

            update = self._backoff.update(result)

            if update.should_emit_ceiling_audit:
                await self._emit_ceiling_reached_audit(
                    interval_seconds=update.current_interval_seconds,
                    from_interval_seconds=update.previous_interval_seconds,
                )

            if result is not None:
                self._handle_result(result)

            await asyncio.sleep(update.current_interval_seconds)

    async def _emit_ceiling_reached_audit(
        self, *, interval_seconds: float, from_interval_seconds: float
    ) -> None:
        """Emit the ``oauth_usage.polling_ceiling_reached`` audit row.

        Called by the poll loop when the backoff state machine
        transitions into the ceiling step from below. Fires exactly
        once per ceiling stretch — the state machine's
        ``_ceiling_audit_emitted`` flag tracks whether the current
        stretch has already emitted; flag clears on successful-poll
        reset so a subsequent stretch emits a fresh row.

        Actor convention: ``"system"`` for daemon-internal events with
        no MCP-authenticated caller, per :func:`audit.logger.write_audit`
        docstring. Target is ``None`` — the audit is a daemon-level
        event without a per-resource target.

        Audit-write failures are logged but do not crash the poll
        loop. The next ceiling event in this same stretch won't
        re-emit (the flag was already set), so a transient storage
        failure here loses the operator-visible signal — accepted
        tradeoff: keeping the loop alive matters more than a single
        missed audit row.
        """
        try:
            await write_audit(
                self._storage,
                actor="system",
                action="oauth_usage.polling_ceiling_reached",
                target=None,
                details={
                    "interval_seconds": interval_seconds,
                    "from_interval_seconds": from_interval_seconds,
                    "default_interval_seconds": self._poll_interval_seconds,
                },
            )
        except Exception as exc:
            logger.error(
                "oauth_usage_poller.ceiling_audit_emit_failed",
                error=str(exc),
                error_type=type(exc).__name__,
            )

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
    "BackoffStateMachine",
    "BackoffUpdateResult",
    "CachedSnapshot",
    "OAuthUsagePoller",
    "SnapshotFetchResult",
    "SnapshotFetcher",
]
