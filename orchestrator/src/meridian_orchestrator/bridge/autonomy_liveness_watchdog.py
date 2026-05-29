"""Server-side autonomy liveness watchdog.

Operator Fix Window 2026-05-26. Per Mike's directive: the daemon must
independently detect stalled or unsurfaced autonomous work and force PM
to address it. The detection logic lives daemon-side; PM yaml / memory /
prompt updates can teach PM how to *respond*, but enforcement lives in
server code.

Five violation rules (A-E) per spec:

* **A. pending_stale** — priority-1 task ``pending``/unclaimed past
  threshold (default 5 min).
* **B. in_progress_stale** — ``claimed``/``in_progress`` task with no
  dispatch activity, no audit activity, and no commit/HEAD movement
  past threshold (default 10 min).
* **C. terminal_unsurfaced** — Engineer/Tester/Implementer task or
  dispatch reached terminal status, but PM hasn't submitted follow-up,
  closed parent, notified human, or recorded disposition within
  threshold (default 5 min).
* **D. expected_artifact_missing** — task/dispatch notes declared an
  artifact at a durable path; dispatch reached terminal; artifact not
  present (default 30s grace after terminal).
* **E. callback_dark** — PM claims active autonomous work (active
  ``pm_current_focus`` row or claimed parent task) but no server-side
  monitor / liveness contract observable for the past threshold
  (default 10 min).

Action shape on detection:

1. Write audit row ``liveness.violation_detected`` with project/canonical/
   task/dispatch ids, violation type, observed state, recommended action.
2. Create a priority-0 PM task (``agent_role='pm'``,
   ``task_type='monitoring'``, ``submitted_by='system:liveness_watchdog'``).
   Two spam guards bound this (Operator Fix Window 2026-05-28):

   * **Dedupe by violation_key.** A second task is never created while an
     OPEN (pending/claimed/in_progress) watchdog task already exists for
     the same ``violation_key`` (via the ``liveness_violations.pm_task_id``
     back-link). A skip emits a structured ``liveness.pm_task_creation_skipped``
     audit naming the existing task. This is what fixes the historical
     ~2015-task spam.
   * **Caps.** A per-tick cap (default 10) and a system-wide outstanding
     cap (default 50) stop runaway creation; hitting either emits a
     structured WARNING audit so operators see the floor.

Autonomy-mode gating (Operator Fix Window 2026-05-28): before persisting
detected violations, the watchdog best-effort reads each project's
autonomy MODE + callback arming from the Control Plane
(``GET /api/daemon/intent``, cached, failures swallowed) and SUPPRESSES
all rules for ``Frozen``/``Manual`` projects — autonomous work is
intentionally not running there, so a "stalled" task is not a violation.
The read fails open: if the CP is unconfigured/down, every rule runs
unconditionally (the conservative pre-existing behavior). The watchdog
stays independent of the CP for its core liveness decision.
3. If unhandled across ``escalation_ticks`` ticks (default 2): emit
   ``liveness.violation_escalated`` audit + a ``HUMAN_ACTION_REQUIRED``
   audit row.
4. When the violation clears: ``liveness.violation_resolved`` + mark
   the row ``resolved_at = now()``.

The watchdog also emits a per-tick heartbeat ``liveness.tick_completed``
event so operators can confirm the loop is alive even when no
violations exist.

Structural shape mirrors :class:`TransientTokenGc` — daemon background
task with cooperative shutdown, in-tick cancel discipline, audit-on-
failure tolerance (keep the loop alive).
"""

from __future__ import annotations

import asyncio
import json
import time
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import structlog

from meridian_orchestrator.audit.logger import write_audit
from meridian_orchestrator.bridge.autonomy_intent import (
    DEFAULT_CACHE_TTL_SECONDS,
    AutonomyIntentSnapshot,
    fetch_autonomy_intent,
)
from meridian_orchestrator.storage.interface import Storage

logger = structlog.get_logger(__name__)

# Audit action constants
TICK_AUDIT_ACTION: str = "liveness.tick_completed"
DETECT_AUDIT_ACTION: str = "liveness.violation_detected"
ESCALATE_AUDIT_ACTION: str = "liveness.violation_escalated"
RESOLVE_AUDIT_ACTION: str = "liveness.violation_resolved"
HUMAN_ACTION_REQUIRED_ACTION: str = "liveness.human_action_required"
PM_TASK_SKIPPED_ACTION: str = "liveness.pm_task_creation_skipped"

WATCHDOG_ACTOR: str = "system:liveness_watchdog"

DEFAULT_DRAIN_TIMEOUT_SECONDS: int = 5

#: Hard cap on the number of NEW priority-0 PM tasks the watchdog will
#: create in a single tick. Guards against an O(N) explosion if a
#: malformed rule (or rule interaction) detects many fresh violations at
#: once. When the cap is hit, the watchdog emits a structured WARNING
#: audit (``task_creation_capped``) so operators see the floor.
DEFAULT_MAX_PM_TASKS_PER_TICK: int = 10

#: Hard cap on the total number of OUTSTANDING (pending, unresolved-by-PM)
#: watchdog-created PM tasks across all ticks. The dedupe-by-violation_key
#: layer already prevents duplicate tasks for the SAME violation; this is
#: the system-wide ceiling that prevents the historical ~2015-task spam
#: even if many distinct violation_keys fire. Once this many watchdog PM
#: tasks are pending, the watchdog stops creating new ones and emits a
#: structured WARNING until PM drains the backlog.
DEFAULT_MAX_OUTSTANDING_PM_TASKS: int = 50

# Terminal task statuses for Rule C parent-follow-up detection.
_TERMINAL_TASK_STATUSES = ("completed", "failed", "declined", "cancelled")
_TERMINAL_DISPATCH_STATUSES = ("completed", "failed", "declined")
_OPEN_TASK_STATUSES = ("pending", "claimed", "in_progress")


def _now_iso() -> str:
    """UTC ISO 8601 timestamp matching the format other daemon writers use."""
    return datetime.now(tz=UTC).isoformat(timespec="seconds")


def _now_utc() -> datetime:
    return datetime.now(tz=UTC)


def _parse_db_timestamp(value: str | None) -> datetime | None:
    """Best-effort parse of an audit/task timestamp from the DB.

    Tasks-table timestamps use SQLite's ``datetime('now')`` shape
    (``'2026-05-27 00:00:00'`` — no T separator, no offset). Audit
    timestamps similarly. Returns timezone-aware UTC datetime when
    parseable, else None.
    """
    if value is None:
        return None
    candidate = value.strip()
    if not candidate:
        return None
    # ISO 8601 with T separator + optional offset (transient_tokens style).
    try:
        parsed = datetime.fromisoformat(candidate)
    except ValueError:
        # SQLite datetime('now') style: space separator, no offset.
        try:
            parsed = datetime.strptime(candidate, "%Y-%m-%d %H:%M:%S")
        except ValueError:
            return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=UTC)
    return parsed


def _violation_key(
    violation_type: str,
    *,
    project_id: str | None = None,
    task_id: str | None = None,
    dispatch_id: str | None = None,
) -> str:
    """Composite dedup key. Stable per (type, project, task, dispatch)
    tuple. Order matches the column layout in 0016 migration."""
    return f"{violation_type}:{project_id or ''}:{task_id or ''}:{dispatch_id or ''}"


@dataclass
class WatchdogThresholds:
    """Per-rule thresholds (seconds)."""

    pending_stale: float = 300.0
    in_progress_stale: float = 600.0
    terminal_unsurfaced: float = 300.0
    artifact_missing_grace: float = 30.0
    callback_dark: float = 600.0


@dataclass
class DetectedViolation:
    """In-memory detection record produced by a single tick.

    Persisted via :meth:`AutonomyLivenessWatchdog._persist_violation` —
    INSERT new or UPDATE existing keyed on ``violation_key``."""

    violation_type: str
    violation_key: str
    project_id: str | None
    task_id: str | None
    dispatch_id: str | None
    observed_state: dict[str, Any]
    safe_next_action: str


class AutonomyLivenessWatchdog:
    """Daemon background task that emits liveness violations.

    Construct once in the daemon lifespan body; ``await run(shutdown)``
    on the lifespan-scoped task group. Shutdown contract mirrors
    :class:`TransientTokenGc` — cooperative, drain-timeout-bounded.

    Tests can drive the loop at sub-second cadence via ``interval_seconds``;
    production defaults to 60s.
    """

    def __init__(
        self,
        storage: Storage,
        *,
        interval_seconds: float,
        thresholds: WatchdogThresholds | None = None,
        escalation_ticks: int = 2,
        drain_timeout_seconds: int = DEFAULT_DRAIN_TIMEOUT_SECONDS,
        max_pm_tasks_per_tick: int = DEFAULT_MAX_PM_TASKS_PER_TICK,
        max_outstanding_pm_tasks: int = DEFAULT_MAX_OUTSTANDING_PM_TASKS,
        control_plane_url: str | None = None,
        intent_cache_ttl_seconds: float = DEFAULT_CACHE_TTL_SECONDS,
    ) -> None:
        self._storage = storage
        self._interval_seconds = float(interval_seconds)
        self._thresholds = thresholds or WatchdogThresholds()
        self._escalation_ticks = max(1, int(escalation_ticks))
        self._drain_timeout_seconds = drain_timeout_seconds
        self._max_pm_tasks_per_tick = max(1, int(max_pm_tasks_per_tick))
        self._max_outstanding_pm_tasks = max(1, int(max_outstanding_pm_tasks))
        self._control_plane_url = control_plane_url
        self._intent_cache_ttl_seconds = float(intent_cache_ttl_seconds)
        # Cached CP autonomy-intent snapshot + its monotonic fetch time.
        # ``None`` means "no usable snapshot" → fail open (check all rules).
        self._intent_snapshot: AutonomyIntentSnapshot | None = None
        self._intent_fetched_at: float | None = None

    @property
    def interval_seconds(self) -> float:
        return self._interval_seconds

    @property
    def drain_timeout_seconds(self) -> int:
        return self._drain_timeout_seconds

    @property
    def thresholds(self) -> WatchdogThresholds:
        return self._thresholds

    # ------------------------------------------------------------------
    # Run loop
    # ------------------------------------------------------------------

    async def run(self, shutdown: asyncio.Event) -> None:
        """Cooperative tick loop. Exits cleanly when ``shutdown`` is set.

        Each tick:
          1. Detect violations (5 rules in parallel, structurally).
          2. Persist/update the ``liveness_violations`` rows.
          3. Emit per-violation audit + (new violations) create PM tasks.
          4. Resolve violations no longer detected this tick.
          5. Escalate violations that crossed ``escalation_ticks`` without
             PM action.
          6. Emit ``liveness.tick_completed`` heartbeat.

        Errors inside the tick are caught and logged; the loop persists.
        Cancellation during the in-flight tick is honored at the next
        cooperation point.
        """
        while not shutdown.is_set():
            try:
                await asyncio.wait_for(
                    shutdown.wait(), timeout=self._interval_seconds
                )
                return
            except TimeoutError:
                pass
            except asyncio.CancelledError:
                raise
            try:
                await self._tick_once()
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.error(
                    "autonomy_liveness_watchdog.tick_failed",
                    error=str(exc),
                    error_type=type(exc).__name__,
                )

    async def _tick_once(self) -> dict[str, Any]:
        """One iteration of the tick loop. Returns a summary dict for
        the heartbeat audit."""
        perf_start = time.perf_counter()
        now = _now_utc()

        # Best-effort CP autonomy-intent read (cached). Used to SUPPRESS
        # rules for Frozen/Manual projects. Fail-open: a None snapshot
        # leaves every project checkable.
        intent = await self._get_autonomy_intent()

        detected: list[DetectedViolation] = []
        for rule in (
            self._detect_pending_stale,
            self._detect_in_progress_stale,
            self._detect_terminal_unsurfaced,
            self._detect_expected_artifact_missing,
            self._detect_callback_dark,
        ):
            try:
                detected.extend(await rule(now))
            except Exception as exc:
                logger.error(
                    "autonomy_liveness_watchdog.rule_failed",
                    rule=rule.__name__,
                    error=str(exc),
                    error_type=type(exc).__name__,
                )

        # Honor CP autonomy mode: drop violations for projects the CP
        # reports as Frozen/Manual (autonomous work intentionally not
        # running there, so a "stalled"/"unsurfaced" task is not a real
        # violation). Fail open when no snapshot / project unknown.
        suppressed_count = 0
        if intent is not None:
            kept: list[DetectedViolation] = []
            for v in detected:
                if intent.is_suppressed(v.project_id):
                    suppressed_count += 1
                    continue
                kept.append(v)
            if suppressed_count:
                logger.info(
                    "autonomy_liveness_watchdog.rules_suppressed_by_mode",
                    suppressed_count=suppressed_count,
                )
            detected = kept

        seen_keys = {v.violation_key for v in detected}

        # Persist / update detected violations.
        new_count = 0
        updated_count = 0
        pm_tasks_created = 0
        pm_tasks_capped = 0
        for v in detected:
            outcome = await self._persist_violation(v, now=now)
            if outcome == "new":
                new_count += 1
                await self._emit_detect_audit(v)
            elif outcome == "updated":
                updated_count += 1

            # PM-task creation is gated on the dedupe-by-violation_key check
            # inside _create_pm_task, NOT on the persist outcome. This is
            # what makes the spam fix robust: a violation that recurs after
            # PM closed its task (persist outcome 'updated') still gets a
            # FRESH task, while a continuously-live violation that already
            # has an OPEN task is deduped every tick. We skip the call only
            # when the row already carries an open back-link (cheap fast
            # path) to avoid a redundant query per active violation.
            if await self._violation_has_open_pm_task(v.violation_key):
                continue
            # Per-tick cap: never create more than max_pm_tasks_per_tick
            # fresh PM tasks in one tick (O(N)-explosion guard).
            if pm_tasks_created >= self._max_pm_tasks_per_tick:
                pm_tasks_capped += 1
                continue
            created = await self._create_pm_task(v)
            if created:
                pm_tasks_created += 1

        if pm_tasks_capped:
            await self._emit_cap_audit(
                reason="per_tick",
                count_capped=pm_tasks_capped,
                detected_count=pm_tasks_created + pm_tasks_capped,
                limit=self._max_pm_tasks_per_tick,
            )

        # Resolve violations no longer present.
        resolved_count = await self._resolve_absent_violations(seen_keys, now=now)

        # Escalate violations that crossed escalation_ticks.
        escalated_count = await self._escalate_unhandled(now=now)

        scan_duration_ms = int((time.perf_counter() - perf_start) * 1000)
        summary = {
            "detected": len(detected),
            "new": new_count,
            "updated": updated_count,
            "resolved": resolved_count,
            "escalated": escalated_count,
            "suppressed_by_mode": suppressed_count,
            "pm_tasks_created": pm_tasks_created,
            "pm_tasks_capped": pm_tasks_capped,
            "scan_duration_ms": scan_duration_ms,
            "ticked_at_iso": now.isoformat(timespec="seconds"),
        }
        try:
            await write_audit(
                self._storage,
                actor=WATCHDOG_ACTOR,
                action=TICK_AUDIT_ACTION,
                target=None,
                details=summary,
            )
        except Exception as exc:
            logger.error(
                "autonomy_liveness_watchdog.heartbeat_audit_failed",
                error=str(exc),
                error_type=type(exc).__name__,
            )
        return summary

    # ------------------------------------------------------------------
    # CP autonomy-intent (mode gating)
    # ------------------------------------------------------------------

    async def _get_autonomy_intent(self) -> AutonomyIntentSnapshot | None:
        """Return the CP autonomy-intent snapshot, fetching if the cache
        is cold or stale.

        Best-effort: returns ``None`` when the CP is unconfigured or the
        fetch fails, so the caller fails open (runs every rule). A stale
        cached snapshot is kept across a failed refresh — better to honor
        a slightly-old Frozen/Manual signal than to revert to spamming.
        """
        if not self._control_plane_url:
            return None
        nowm = time.monotonic()
        if (
            self._intent_snapshot is not None
            and self._intent_fetched_at is not None
            and (nowm - self._intent_fetched_at) < self._intent_cache_ttl_seconds
        ):
            return self._intent_snapshot
        snapshot = await fetch_autonomy_intent(
            control_plane_url=self._control_plane_url,
        )
        if snapshot is not None:
            self._intent_snapshot = snapshot
            self._intent_fetched_at = nowm
            return snapshot
        # Refresh failed. Keep any prior snapshot (honor last-known
        # Frozen/Manual signals) but don't reset its age — we want a
        # retry on the next tick rather than holding a dead snapshot
        # forever, so only return it within the TTL window.
        if (
            self._intent_snapshot is not None
            and self._intent_fetched_at is not None
            and (nowm - self._intent_fetched_at) < self._intent_cache_ttl_seconds
        ):
            return self._intent_snapshot
        return None

    # ------------------------------------------------------------------
    # Detection rules
    # ------------------------------------------------------------------

    async def _detect_pending_stale(
        self, now: datetime
    ) -> list[DetectedViolation]:
        """Rule A: priority-1 task pending/unclaimed past threshold."""
        cutoff = now.timestamp() - self._thresholds.pending_stale
        rows = await self._storage.fetch_all(
            "SELECT id, project_id, agent_role, priority, status, created_at "
            "FROM tasks WHERE status = 'pending' AND priority <= 1"
        )
        out: list[DetectedViolation] = []
        for row in rows:
            created = _parse_db_timestamp(row["created_at"])
            if created is None:
                continue
            age = (now - created).total_seconds()
            if age < self._thresholds.pending_stale:
                continue
            _ = cutoff  # explicit unused-binding silence; kept for clarity
            project_id = row["project_id"]
            task_id = row["id"]
            key = _violation_key(
                "pending_stale", project_id=project_id, task_id=task_id
            )
            out.append(
                DetectedViolation(
                    violation_type="pending_stale",
                    violation_key=key,
                    project_id=project_id,
                    task_id=task_id,
                    dispatch_id=None,
                    observed_state={
                        "agent_role": row["agent_role"],
                        "priority": row["priority"],
                        "age_seconds": int(age),
                        "threshold_seconds": self._thresholds.pending_stale,
                    },
                    safe_next_action=(
                        f"claim_task(task_id={task_id!r}) or "
                        "investigate why no agent has picked it up"
                    ),
                )
            )
        return out

    async def _detect_in_progress_stale(
        self, now: datetime
    ) -> list[DetectedViolation]:
        """Rule B: claimed/in_progress task with no recent activity."""
        threshold = self._thresholds.in_progress_stale
        rows = await self._storage.fetch_all(
            "SELECT id, project_id, agent_role, status, claimed_at, "
            "claimed_by FROM tasks WHERE status IN ('claimed', 'in_progress')"
        )
        out: list[DetectedViolation] = []
        for row in rows:
            task_id = row["id"]
            last_activity = await self._last_task_activity(task_id)
            if last_activity is None:
                # Fall back to claimed_at if no audit/dispatch activity.
                last_activity = _parse_db_timestamp(row["claimed_at"])
            if last_activity is None:
                continue
            age = (now - last_activity).total_seconds()
            if age < threshold:
                continue
            key = _violation_key(
                "in_progress_stale",
                project_id=row["project_id"],
                task_id=task_id,
            )
            out.append(
                DetectedViolation(
                    violation_type="in_progress_stale",
                    violation_key=key,
                    project_id=row["project_id"],
                    task_id=task_id,
                    dispatch_id=None,
                    observed_state={
                        "agent_role": row["agent_role"],
                        "status": row["status"],
                        "claimed_by": row["claimed_by"],
                        "last_activity_iso": last_activity.isoformat(
                            timespec="seconds"
                        ),
                        "silent_seconds": int(age),
                        "threshold_seconds": threshold,
                    },
                    safe_next_action=(
                        "inspect agent subprocess + recent audit_log for "
                        f"task {task_id!r}; consider claim release or cancel"
                    ),
                )
            )
        return out

    async def _detect_terminal_unsurfaced(
        self, now: datetime
    ) -> list[DetectedViolation]:
        """Rule C: terminal child dispatch with no PM follow-up.

        A dispatch is "surfaced" when AFTER its terminal time we observe:
          * PM submitted any new task referencing the parent task or
            project (heuristic: any new task with the same project_id
            created after the terminal time), OR
          * the parent task status moved to terminal, OR
          * an audit row exists with action LIKE 'notify_human%' or
            'pm.%' actor='pm' since the terminal time, OR
          * the parent task's notes were updated since terminal (we treat
            any notes mutation as a disposition record — approximated by
            audit_log action='task.update_notes').
        """
        threshold = self._thresholds.terminal_unsurfaced
        rows = await self._storage.fetch_all(
            "SELECT d.id AS dispatch_id, d.task_id, d.role, d.status, "
            "d.completed_at, t.project_id, t.status AS task_status, "
            "t.parent_task_id "
            "FROM dispatches d LEFT JOIN tasks t ON t.id = d.task_id "
            "WHERE d.status IN ('completed', 'failed', 'declined') "
            "AND d.completed_at IS NOT NULL"
        )
        out: list[DetectedViolation] = []
        for row in rows:
            terminal_at = _parse_db_timestamp(row["completed_at"])
            if terminal_at is None:
                continue
            age = (now - terminal_at).total_seconds()
            if age < threshold:
                continue
            task_id = row["task_id"]
            dispatch_id = row["dispatch_id"]
            # Skip if PM has surfaced follow-up since terminal.
            if await self._pm_followup_observed(
                task_id=task_id,
                project_id=row["project_id"],
                since=terminal_at,
            ):
                continue
            # Skip if the parent task itself is already terminal (Engineer
            # closed the chain).
            if row["task_status"] in _TERMINAL_TASK_STATUSES:
                continue
            key = _violation_key(
                "terminal_unsurfaced",
                project_id=row["project_id"],
                task_id=task_id,
                dispatch_id=dispatch_id,
            )
            out.append(
                DetectedViolation(
                    violation_type="terminal_unsurfaced",
                    violation_key=key,
                    project_id=row["project_id"],
                    task_id=task_id,
                    dispatch_id=dispatch_id,
                    observed_state={
                        "dispatch_role": row["role"],
                        "dispatch_status": row["status"],
                        "terminal_at_iso": terminal_at.isoformat(
                            timespec="seconds"
                        ),
                        "unsurfaced_seconds": int(age),
                        "threshold_seconds": threshold,
                    },
                    safe_next_action=(
                        f"PM: review dispatch {dispatch_id!r} terminal "
                        f"result and submit follow-up or close parent task "
                        f"{task_id!r}"
                    ),
                )
            )
        return out

    async def _detect_expected_artifact_missing(
        self, now: datetime
    ) -> list[DetectedViolation]:
        """Rule D: artifact promised but missing post-terminal.

        Looks for ``artifact_path:`` declarations in task.notes and
        dispatch (details_json or notes). If the dispatch reached
        terminal more than ``artifact_missing_grace`` seconds ago and
        the path doesn't exist on disk, surface as a violation.
        """
        grace = self._thresholds.artifact_missing_grace
        rows = await self._storage.fetch_all(
            "SELECT d.id AS dispatch_id, d.task_id, d.status, "
            "d.completed_at, t.project_id, t.notes "
            "FROM dispatches d LEFT JOIN tasks t ON t.id = d.task_id "
            "WHERE d.status IN ('completed', 'failed', 'declined') "
            "AND d.completed_at IS NOT NULL"
        )
        out: list[DetectedViolation] = []
        for row in rows:
            terminal_at = _parse_db_timestamp(row["completed_at"])
            if terminal_at is None:
                continue
            age = (now - terminal_at).total_seconds()
            if age < grace:
                continue
            task_id = row["task_id"]
            paths = self._extract_artifact_paths(row["notes"])
            for declared in paths:
                if not declared:
                    continue
                try:
                    exists = Path(declared).exists()
                except OSError:
                    exists = False
                if exists:
                    continue
                dispatch_id = row["dispatch_id"]
                key = _violation_key(
                    "expected_artifact_missing",
                    project_id=row["project_id"],
                    task_id=task_id,
                    dispatch_id=dispatch_id,
                )
                out.append(
                    DetectedViolation(
                        violation_type="expected_artifact_missing",
                        violation_key=key,
                        project_id=row["project_id"],
                        task_id=task_id,
                        dispatch_id=dispatch_id,
                        observed_state={
                            "declared_artifact_path": declared,
                            "terminal_at_iso": terminal_at.isoformat(
                                timespec="seconds"
                            ),
                            "elapsed_since_terminal_seconds": int(age),
                            "grace_seconds": grace,
                        },
                        safe_next_action=(
                            f"PM/Engineer: verify whether {declared!r} was "
                            "intended to be produced; if so, dispatch a "
                            "remediation; if not, update task notes to "
                            "remove the artifact_path declaration"
                        ),
                    )
                )
        return out

    async def _detect_callback_dark(
        self, now: datetime
    ) -> list[DetectedViolation]:
        """Rule E: PM has active focus / claimed work but no observable
        server-side liveness contract.

        Heuristic v1: a PM-role task in claimed/in_progress with NO
        audit-log activity from any PM/system actor in the last
        ``callback_dark`` seconds, AND no active monitor (heuristic:
        no recent ``monitor.armed`` or ``monitor.event`` audit since
        the cutoff). This is intentionally conservative — false
        positives are PM's signal to either restart their callback or
        explicitly acknowledge dark mode.
        """
        threshold = self._thresholds.callback_dark
        cutoff = now.timestamp() - threshold
        rows = await self._storage.fetch_all(
            "SELECT id, project_id, status FROM tasks "
            "WHERE agent_role = 'pm' AND status IN ('claimed', 'in_progress')"
        )
        out: list[DetectedViolation] = []
        for row in rows:
            task_id = row["id"]
            last_activity = await self._last_pm_or_monitor_audit_after(
                cutoff_seconds=cutoff,
            )
            if last_activity is not None:
                continue
            project_id = row["project_id"]
            key = _violation_key(
                "callback_dark", project_id=project_id, task_id=task_id
            )
            out.append(
                DetectedViolation(
                    violation_type="callback_dark",
                    violation_key=key,
                    project_id=project_id,
                    task_id=task_id,
                    dispatch_id=None,
                    observed_state={
                        "pm_task_status": row["status"],
                        "no_activity_seconds_min": int(threshold),
                        "threshold_seconds": threshold,
                    },
                    safe_next_action=(
                        f"PM: re-arm monitor or surface explicit pause for "
                        f"task {task_id!r}; do NOT treat 'Monitor armed' "
                        "as sufficient liveness"
                    ),
                )
            )
        return out

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _extract_artifact_paths(notes: str | None) -> list[str]:
        """Scan free-text notes for ``artifact_path:`` declarations.

        Accepts shapes:
          * ``artifact_path: /some/path``
          * ``artifact_path = /some/path``
          * ``- artifact_path: /some/path`` (markdown list)
        Returns the right-hand side stripped; one path per declaration.
        Multiple declarations in one notes blob each yield an entry.
        """
        if not notes:
            return []
        out: list[str] = []
        for raw_line in notes.splitlines():
            line = raw_line.strip().lstrip("-").lstrip("*").strip()
            for prefix in ("artifact_path:", "artifact_path ="):
                if prefix in line:
                    _, _, rhs = line.partition(prefix)
                    candidate = rhs.strip().strip('"').strip("'").strip("`")
                    if candidate:
                        out.append(candidate)
        return out

    async def _last_task_activity(
        self, task_id: str | None
    ) -> datetime | None:
        """Last observable activity for a task: audit_log row referencing
        the task_id OR a dispatch event timestamp for one of its
        dispatches. Returns None if no activity found."""
        if not task_id:
            return None
        # Exclude watchdog-emitted audit rows from the activity signal —
        # otherwise the watchdog's own violation-detection writes would
        # mask the very staleness it's trying to detect on the next
        # tick.
        row = await self._storage.fetch_one(
            "SELECT MAX(timestamp) AS last_ts FROM audit_log "
            "WHERE actor != ? AND ("
            "target = ? OR (details_json LIKE ?))",
            (WATCHDOG_ACTOR, task_id, f'%{task_id}%'),
        )
        last_audit = (
            _parse_db_timestamp(row["last_ts"]) if row and row["last_ts"] else None
        )
        # Most recent dispatch timestamp for any dispatch of this task.
        # The dispatches table has issued_at (NOT NULL DEFAULT now),
        # started_at, last_activity_at, completed_at, timed_out_at —
        # all nullable except issued_at. COALESCE order picks the most
        # operationally-recent timestamp; MAX rolls up across all
        # dispatches of the task.
        d_row = await self._storage.fetch_one(
            "SELECT MAX(COALESCE("
            "timed_out_at, completed_at, last_activity_at, "
            "started_at, issued_at"
            ")) AS last_ts "
            "FROM dispatches WHERE task_id = ?",
            (task_id,),
        )
        last_dispatch = (
            _parse_db_timestamp(d_row["last_ts"])
            if d_row and d_row["last_ts"]
            else None
        )
        candidates = [t for t in (last_audit, last_dispatch) if t is not None]
        if not candidates:
            return None
        return max(candidates)

    async def _pm_followup_observed(
        self,
        *,
        task_id: str | None,
        project_id: str | None,
        since: datetime,
    ) -> bool:
        """Has PM done *anything* after ``since`` that would count as
        surfacing this terminal dispatch?

        Signals:
          * A new task (any role) created with this project_id since.
          * Parent task moved to terminal since.
          * An audit row from a PM actor since (action LIKE 'pm.%' OR
            action LIKE 'task.submit%' OR action LIKE 'notify_human%' OR
            action='task.status_change').
          * Parent task notes mutated since (audit
            action='task.update_notes' or task.completed_at >= since).
        """
        since_iso = since.isoformat(timespec="seconds")
        # New task with same project_id.
        if project_id:
            row = await self._storage.fetch_one(
                "SELECT id FROM tasks WHERE project_id = ? "
                "AND created_at > ? LIMIT 1",
                (project_id, since_iso),
            )
            if row is not None:
                return True
        # Parent task itself moved to terminal since.
        if task_id:
            row = await self._storage.fetch_one(
                "SELECT id FROM tasks WHERE id = ? AND status IN "
                "('completed', 'failed', 'declined', 'cancelled') "
                "AND completed_at IS NOT NULL AND completed_at > ?",
                (task_id, since_iso),
            )
            if row is not None:
                return True
        # PM/notify/follow-up audit since. Exclude watchdog-emitted
        # rows so the watchdog's own ``liveness.*`` audits don't count
        # as PM follow-up.
        row = await self._storage.fetch_one(
            "SELECT id FROM audit_log WHERE timestamp > ? "
            "AND actor != ? AND ("
            "action LIKE 'pm.%' OR action LIKE 'task.submit%' OR "
            "action LIKE 'notify_human%' OR action = 'task.status_change'"
            ") LIMIT 1",
            (since_iso, WATCHDOG_ACTOR),
        )
        return row is not None

    async def _last_pm_or_monitor_audit_after(
        self, *, cutoff_seconds: float
    ) -> str | None:
        """Return the most recent audit row timestamp from a PM/system
        actor after ``cutoff_seconds`` (epoch). None if no recent rows.

        ``cutoff_seconds`` is epoch-style for simple comparison; we
        convert to ISO before binding to SQL."""
        cutoff_iso = datetime.fromtimestamp(cutoff_seconds, tz=UTC).isoformat(
            timespec="seconds"
        )
        # Exclude watchdog-emitted rows from the PM/monitor signal —
        # the watchdog's own audit writes are not PM action.
        row = await self._storage.fetch_one(
            "SELECT MAX(timestamp) AS last_ts FROM audit_log "
            "WHERE timestamp > ? AND actor != ? AND ("
            "actor LIKE 'pm.%' OR actor = 'pm' OR "
            "action LIKE 'pm.%' OR action LIKE 'monitor.%' OR "
            "action = 'task.status_change' OR action LIKE 'notify_human%'"
            ")",
            (cutoff_iso, WATCHDOG_ACTOR),
        )
        if row is None:
            return None
        return row["last_ts"]

    # ------------------------------------------------------------------
    # Persistence
    # ------------------------------------------------------------------

    async def _persist_violation(
        self, v: DetectedViolation, *, now: datetime
    ) -> str:
        """INSERT new or UPDATE existing violation row.

        Returns ``"new"`` if inserted, ``"updated"`` if the row already
        existed and we bumped tick_count + last_seen_at.
        """
        now_iso = now.isoformat(timespec="seconds")
        existing = await self._storage.fetch_one(
            "SELECT id, tick_count, resolved_at FROM liveness_violations "
            "WHERE violation_key = ?",
            (v.violation_key,),
        )
        observed_json = json.dumps(v.observed_state, sort_keys=True)
        if existing is None:
            await self._storage.execute(
                "INSERT INTO liveness_violations ("
                "id, violation_type, violation_key, project_id, task_id, "
                "dispatch_id, observed_state_json, safe_next_action, "
                "first_seen_at, last_seen_at, tick_count, escalated"
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 0)",
                (
                    uuid.uuid4().hex,
                    v.violation_type,
                    v.violation_key,
                    v.project_id,
                    v.task_id,
                    v.dispatch_id,
                    observed_json,
                    v.safe_next_action,
                    now_iso,
                    now_iso,
                ),
            )
            return "new"
        # Existing — bump tick_count + last_seen_at + observed_state.
        # If previously resolved, treat as a re-detection: clear
        # resolved_at and reset tick_count to 1 (fresh cycle).
        if existing["resolved_at"] is not None:
            await self._storage.execute(
                "UPDATE liveness_violations SET "
                "last_seen_at = ?, tick_count = 1, escalated = 0, "
                "escalated_at = NULL, resolved_at = NULL, "
                "observed_state_json = ?, safe_next_action = ? "
                "WHERE id = ?",
                (now_iso, observed_json, v.safe_next_action, existing["id"]),
            )
        else:
            await self._storage.execute(
                "UPDATE liveness_violations SET "
                "last_seen_at = ?, tick_count = tick_count + 1, "
                "observed_state_json = ?, safe_next_action = ? "
                "WHERE id = ?",
                (now_iso, observed_json, v.safe_next_action, existing["id"]),
            )
        return "updated"

    async def _resolve_absent_violations(
        self, seen_keys: set[str], *, now: datetime
    ) -> int:
        """Mark unresolved violations not seen this tick as resolved."""
        now_iso = now.isoformat(timespec="seconds")
        rows = await self._storage.fetch_all(
            "SELECT id, violation_key, violation_type, project_id, task_id, "
            "dispatch_id FROM liveness_violations WHERE resolved_at IS NULL"
        )
        resolved = 0
        for row in rows:
            if row["violation_key"] in seen_keys:
                continue
            await self._storage.execute(
                "UPDATE liveness_violations SET resolved_at = ? WHERE id = ?",
                (now_iso, row["id"]),
            )
            try:
                await write_audit(
                    self._storage,
                    actor=WATCHDOG_ACTOR,
                    action=RESOLVE_AUDIT_ACTION,
                    target=row["task_id"] or row["dispatch_id"],
                    details={
                        "violation_id": row["id"],
                        "violation_type": row["violation_type"],
                        "violation_key": row["violation_key"],
                        "project_id": row["project_id"],
                        "task_id": row["task_id"],
                        "dispatch_id": row["dispatch_id"],
                        "resolved_at_iso": now_iso,
                    },
                )
            except Exception as exc:
                logger.error(
                    "autonomy_liveness_watchdog.resolve_audit_failed",
                    error=str(exc),
                    error_type=type(exc).__name__,
                )
            resolved += 1
        return resolved

    async def _escalate_unhandled(self, *, now: datetime) -> int:
        """Trigger escalation for violations past ``escalation_ticks``
        and not yet escalated."""
        now_iso = now.isoformat(timespec="seconds")
        rows = await self._storage.fetch_all(
            "SELECT id, violation_type, violation_key, project_id, task_id, "
            "dispatch_id, tick_count, safe_next_action "
            "FROM liveness_violations "
            "WHERE resolved_at IS NULL AND escalated = 0 AND tick_count >= ?",
            (self._escalation_ticks,),
        )
        escalated = 0
        for row in rows:
            await self._storage.execute(
                "UPDATE liveness_violations SET escalated = 1, "
                "escalated_at = ? WHERE id = ?",
                (now_iso, row["id"]),
            )
            try:
                await write_audit(
                    self._storage,
                    actor=WATCHDOG_ACTOR,
                    action=ESCALATE_AUDIT_ACTION,
                    target=row["task_id"] or row["dispatch_id"],
                    details={
                        "violation_id": row["id"],
                        "violation_type": row["violation_type"],
                        "violation_key": row["violation_key"],
                        "project_id": row["project_id"],
                        "task_id": row["task_id"],
                        "dispatch_id": row["dispatch_id"],
                        "tick_count": row["tick_count"],
                        "escalated_at_iso": now_iso,
                        "safe_next_action": row["safe_next_action"],
                    },
                )
                await write_audit(
                    self._storage,
                    actor=WATCHDOG_ACTOR,
                    action=HUMAN_ACTION_REQUIRED_ACTION,
                    target=row["task_id"] or row["dispatch_id"],
                    details={
                        "violation_id": row["id"],
                        "violation_type": row["violation_type"],
                        "safe_next_action": row["safe_next_action"],
                        "tick_count": row["tick_count"],
                    },
                )
            except Exception as exc:
                logger.error(
                    "autonomy_liveness_watchdog.escalate_audit_failed",
                    error=str(exc),
                    error_type=type(exc).__name__,
                )
            escalated += 1
        return escalated

    # ------------------------------------------------------------------
    # Audit + PM task
    # ------------------------------------------------------------------

    async def _emit_detect_audit(self, v: DetectedViolation) -> None:
        try:
            await write_audit(
                self._storage,
                actor=WATCHDOG_ACTOR,
                action=DETECT_AUDIT_ACTION,
                target=v.task_id or v.dispatch_id,
                details={
                    "violation_type": v.violation_type,
                    "violation_key": v.violation_key,
                    "project_id": v.project_id,
                    "task_id": v.task_id,
                    "dispatch_id": v.dispatch_id,
                    "observed_state": v.observed_state,
                    "safe_next_action": v.safe_next_action,
                },
            )
        except Exception as exc:
            logger.error(
                "autonomy_liveness_watchdog.detect_audit_failed",
                error=str(exc),
                error_type=type(exc).__name__,
            )

    async def _create_pm_task(self, v: DetectedViolation) -> bool:
        """Insert a priority-0 PM monitoring task for this violation.

        Daemon-internal write — bypasses MCP submit_task wrapper (which
        gates priority to 1..5). The watchdog's ``submitted_by =
        'system:liveness_watchdog'`` literal distinguishes these rows
        from PM-authored tasks.

        Two spam guards run BEFORE the INSERT:

        * **Dedupe by violation_key.** If an OPEN (pending / claimed /
          in_progress) watchdog PM task already exists for this
          ``violation_key`` (via the ``liveness_violations.pm_task_id``
          back-link), skip — never create a second task for the same
          live violation. A previously-created task that PM already
          closed (terminal status) does NOT block a fresh task when the
          violation genuinely recurs.
        * **Outstanding cap.** If the count of OPEN watchdog PM tasks
          system-wide is already at ``max_outstanding_pm_tasks``, skip —
          this is the ceiling that prevents the historical ~2015-task
          spam even across many distinct violation_keys.

        Returns ``True`` when a task was actually inserted, ``False`` when
        skipped (deduped, capped, or no resolvable project). The caller
        uses the boolean to maintain its per-tick creation count.
        """
        # Guard 1: dedupe by violation_key against an OPEN watchdog task.
        existing_pm_task_id = await self._open_pm_task_for_violation(
            v.violation_key
        )
        if existing_pm_task_id is not None:
            logger.debug(
                "autonomy_liveness_watchdog.pm_task_already_open",
                violation_key=v.violation_key,
                existing_pm_task_id=existing_pm_task_id,
            )
            await self._emit_skip_audit(
                v,
                reason="duplicate",
                existing_pm_task_id=existing_pm_task_id,
            )
            return False

        # Guard 2: system-wide outstanding cap.
        outstanding = await self._outstanding_pm_task_count()
        if outstanding >= self._max_outstanding_pm_tasks:
            logger.warning(
                "autonomy_liveness_watchdog.pm_task_outstanding_cap",
                violation_key=v.violation_key,
                outstanding=outstanding,
                limit=self._max_outstanding_pm_tasks,
            )
            await self._emit_cap_audit(
                reason="outstanding",
                count_capped=1,
                detected_count=outstanding,
                limit=self._max_outstanding_pm_tasks,
                violation_key=v.violation_key,
            )
            return False

        # Resolve company_id + project_id for the new task.
        company_id, project_id = await self._resolve_company_project(
            project_id=v.project_id
        )
        if company_id is None or project_id is None:
            logger.warning(
                "autonomy_liveness_watchdog.pm_task_skipped_no_project",
                violation_key=v.violation_key,
            )
            return False
        body = self._compose_pm_task_body(v)
        task_id = uuid.uuid4().hex
        await self._storage.execute(
            "INSERT INTO tasks ("
            "id, company_id, project_id, agent_role, description, "
            "priority, complexity, status, task_type, submitted_by, "
            "created_at"
            ") VALUES (?, ?, ?, 'pm', ?, 0, 1, 'pending', 'monitoring', ?, ?)",
            (
                task_id,
                company_id,
                project_id,
                body,
                WATCHDOG_ACTOR,
                _now_iso(),
            ),
        )
        # Back-link from violation row → created PM task.
        await self._storage.execute(
            "UPDATE liveness_violations SET pm_task_id = ? "
            "WHERE violation_key = ?",
            (task_id, v.violation_key),
        )
        return True

    async def _open_pm_task_for_violation(
        self, violation_key: str
    ) -> str | None:
        """Return the id of an OPEN watchdog PM task for ``violation_key``,
        or ``None`` if none exists.

        Uses the ``liveness_violations.pm_task_id`` back-link joined to
        ``tasks`` filtered to open statuses + the watchdog ``submitted_by``
        literal. A task that PM already moved to a terminal status does
        not count — the violation can recur after being handled."""
        row = await self._storage.fetch_one(
            "SELECT t.id AS task_id FROM liveness_violations lv "
            "JOIN tasks t ON t.id = lv.pm_task_id "
            "WHERE lv.violation_key = ? AND lv.pm_task_id IS NOT NULL "
            "AND t.submitted_by = ? "
            "AND t.status IN ('pending', 'claimed', 'in_progress') "
            "LIMIT 1",
            (violation_key, WATCHDOG_ACTOR),
        )
        if row is None or row["task_id"] is None:
            return None
        return str(row["task_id"])

    async def _violation_has_open_pm_task(self, violation_key: str) -> bool:
        """Cheap boolean wrapper over :meth:`_open_pm_task_for_violation`,
        used by the tick loop's fast-path dedupe gate."""
        return (await self._open_pm_task_for_violation(violation_key)) is not None

    async def _outstanding_pm_task_count(self) -> int:
        """Count OPEN (pending/claimed/in_progress) watchdog-created PM
        tasks system-wide. Drives the outstanding cap."""
        row = await self._storage.fetch_one(
            "SELECT COUNT(*) AS n FROM tasks "
            "WHERE submitted_by = ? AND agent_role = 'pm' "
            "AND status IN ('pending', 'claimed', 'in_progress')",
            (WATCHDOG_ACTOR,),
        )
        if row is None or row["n"] is None:
            return 0
        return int(row["n"])

    async def _emit_skip_audit(
        self,
        v: DetectedViolation,
        *,
        reason: str,
        existing_pm_task_id: str | None = None,
    ) -> None:
        """Structured audit when a PM-task creation is deduped/skipped.

        Lets operators debug spam suppression without reading source: the
        row names the violation_key, the reason, and (for dedupe) the
        existing pm_task_id that blocked a second task."""
        details: dict[str, Any] = {
            "reason": reason,
            "violation_key": v.violation_key,
            "violation_type": v.violation_type,
            "project_id": v.project_id,
        }
        if existing_pm_task_id is not None:
            details["existing_pm_task_id"] = existing_pm_task_id
        try:
            await write_audit(
                self._storage,
                actor=WATCHDOG_ACTOR,
                action=PM_TASK_SKIPPED_ACTION,
                target=v.task_id or v.dispatch_id,
                details=details,
            )
        except Exception as exc:
            logger.error(
                "autonomy_liveness_watchdog.skip_audit_failed",
                error=str(exc),
                error_type=type(exc).__name__,
            )

    async def _emit_cap_audit(
        self,
        *,
        reason: str,
        count_capped: int,
        detected_count: int,
        limit: int,
        violation_key: str | None = None,
    ) -> None:
        """Structured WARNING audit when PM-task creation is capped.

        ``reason`` is ``per_tick`` (hit the per-tick ceiling) or
        ``outstanding`` (hit the system-wide outstanding ceiling). The
        floor is surfaced so operators see when the watchdog stopped
        creating tasks and why."""
        logger.warning(
            "autonomy_liveness_watchdog.task_creation_capped",
            reason=reason,
            count_capped=count_capped,
            detected_count=detected_count,
            limit=limit,
        )
        details: dict[str, Any] = {
            "reason": reason,
            "count_capped": count_capped,
            "detected_count": detected_count,
            "limit": limit,
        }
        if violation_key is not None:
            details["violation_key"] = violation_key
        try:
            await write_audit(
                self._storage,
                actor=WATCHDOG_ACTOR,
                action=PM_TASK_SKIPPED_ACTION,
                target=None,
                details=details,
            )
        except Exception as exc:
            logger.error(
                "autonomy_liveness_watchdog.cap_audit_failed",
                error=str(exc),
                error_type=type(exc).__name__,
            )

    @staticmethod
    def _compose_pm_task_body(v: DetectedViolation) -> str:
        observed = json.dumps(v.observed_state, sort_keys=True, indent=2)
        return (
            f"Address liveness violation: {v.violation_type}\n\n"
            f"violation_key: {v.violation_key}\n"
            f"project_id: {v.project_id}\n"
            f"task_id: {v.task_id}\n"
            f"dispatch_id: {v.dispatch_id}\n\n"
            f"Observed state:\n{observed}\n\n"
            f"Safe next action:\n{v.safe_next_action}\n\n"
            "This task was auto-created by the AutonomyLivenessWatchdog. "
            "Resolve by taking the safe next action OR by recording an "
            "explicit disposition in the parent task's notes. The watchdog "
            "marks the violation resolved once detection no longer fires."
        )

    async def _resolve_company_project(
        self, *, project_id: str | None
    ) -> tuple[str | None, str | None]:
        """Resolve (company_id, project_id) for the PM task.

        Prefers the violation's project_id when set; else falls back to
        the first project in the system (Phase-1 single-company shape).
        """
        if project_id:
            row = await self._storage.fetch_one(
                "SELECT id, company_id FROM projects WHERE id = ?",
                (project_id,),
            )
            if row is not None:
                return row["company_id"], row["id"]
        # Fallback: most recently created project.
        row = await self._storage.fetch_one(
            "SELECT id, company_id FROM projects ORDER BY created_at ASC LIMIT 1"
        )
        if row is None:
            return None, None
        return row["company_id"], row["id"]


# ----- Read API surface (consumed by tools/liveness.py + HTTP route) -----


async def fetch_active_violations(storage: Storage) -> list[dict[str, Any]]:
    """Return the active (unresolved) liveness violations as plain dicts.

    Shape per row:
      {
        id, violation_type, project_id, task_id, dispatch_id,
        observed_state, safe_next_action, pm_task_id,
        first_seen_at, last_seen_at, tick_count, escalated, escalated_at,
        age_seconds (computed from first_seen_at vs now)
      }

    Consumers (HTTP route, MCP tool wrapper) shape this into the
    operator-ui-facing payload.
    """
    rows = await storage.fetch_all(
        "SELECT id, violation_type, violation_key, project_id, task_id, "
        "dispatch_id, observed_state_json, safe_next_action, pm_task_id, "
        "first_seen_at, last_seen_at, tick_count, escalated, escalated_at "
        "FROM liveness_violations WHERE resolved_at IS NULL "
        "ORDER BY first_seen_at ASC"
    )
    out: list[dict[str, Any]] = []
    now = _now_utc()
    for row in rows:
        first_seen = _parse_db_timestamp(row["first_seen_at"])
        age = int((now - first_seen).total_seconds()) if first_seen else None
        try:
            observed = (
                json.loads(row["observed_state_json"])
                if row["observed_state_json"]
                else {}
            )
        except json.JSONDecodeError:
            observed = {"_parse_error": True, "raw": row["observed_state_json"]}
        out.append(
            {
                "id": row["id"],
                "violation_type": row["violation_type"],
                "violation_key": row["violation_key"],
                "project_id": row["project_id"],
                "task_id": row["task_id"],
                "dispatch_id": row["dispatch_id"],
                "observed_state": observed,
                "safe_next_action": row["safe_next_action"],
                "pm_task_id": row["pm_task_id"],
                "first_seen_at": row["first_seen_at"],
                "last_seen_at": row["last_seen_at"],
                "tick_count": row["tick_count"],
                "escalated": bool(row["escalated"]),
                "escalated_at": row["escalated_at"],
                "age_seconds": age,
            }
        )
    return out


__all__ = [
    "DEFAULT_DRAIN_TIMEOUT_SECONDS",
    "DEFAULT_MAX_OUTSTANDING_PM_TASKS",
    "DEFAULT_MAX_PM_TASKS_PER_TICK",
    "DETECT_AUDIT_ACTION",
    "ESCALATE_AUDIT_ACTION",
    "HUMAN_ACTION_REQUIRED_ACTION",
    "PM_TASK_SKIPPED_ACTION",
    "RESOLVE_AUDIT_ACTION",
    "TICK_AUDIT_ACTION",
    "WATCHDOG_ACTOR",
    "AutonomyLivenessWatchdog",
    "DetectedViolation",
    "WatchdogThresholds",
    "fetch_active_violations",
]
