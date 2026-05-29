"""Daemon → Control Plane event emitter.

The daemon's load-bearing lifecycle trail lands in ``audit_log`` via
:func:`audit.logger.write_audit`. The Control Plane (orchestrator-ui
backend) ingests the *same* events through
``POST /callbacks/orchestrator/events`` and projects them into snapshot
tables for the operator dashboard. This module is the missing link: it
mirrors audit rows to the CP ingest endpoint.

**Hard constraints** (per the daemon's subscription-only / never-block
build rules):

- **Best-effort, fire-and-forget.** Emission MUST NEVER block, slow, or
  raise into the daemon's primary lifecycle. Every failure mode — URL
  unset, network error, non-2xx response, malformed mapping — is
  swallowed with a WARNING log only.
- **No-op when unconfigured.** If ``Settings.control_plane_url`` is unset
  the emitter returns immediately and spawns no HTTP request.
- **No new credentials.** Uses plain ``httpx`` (already a dependency via
  :mod:`bridge.oauth_usage`); no ANTHROPIC_API_KEY, no extra-usage path.

**Mapping.** The daemon's audit ``action`` strings do not map 1:1 to the
CP's 26 ``event_type`` strings. :data:`_ACTION_CONTRACT` encodes the
authoritative ``daemon_audit_action → (cp_event_type, payload_field
extractor)`` table. Actions not present in the contract — and the
high-frequency observability actions explicitly skipped per the contract
notes (``dispatch.tool_activity``, ``dispatch.progress_signal``,
``daemon.healthy``) — are dropped before any HTTP work.

The audit row is the source of truth; this is a downstream mirror. A
dropped or failed emission loses an operator-dashboard signal, never a
forensic audit row.
"""

from __future__ import annotations

import asyncio
import uuid
from collections.abc import Callable
from datetime import UTC, datetime
from typing import Any

import httpx
import structlog

logger = structlog.get_logger(__name__)

#: Event source tag. The CP's ``OrchestratorEvent.source`` is free-form;
#: ``"daemon"`` distinguishes daemon-emitted events from any future
#: emitters (manual replays, test harnesses).
EVENT_SOURCE = "daemon"

#: Ingest endpoint path appended to ``Settings.control_plane_url``.
INGEST_PATH = "/callbacks/orchestrator/events"

#: Per-request timeout. Kept short — a slow CP must never back up the
#: daemon. The emission is fire-and-forget on a detached task, but the
#: timeout still bounds the detached task's lifetime + connection churn.
DEFAULT_HTTP_TIMEOUT_SECONDS = 5.0

#: Strong references to in-flight fire-and-forget POST tasks. ``create_task``
#: only holds a weak reference, so without this the task could be garbage-
#: collected mid-flight. Each task removes itself on completion via a
#: done-callback (the documented asyncio idiom).
_BACKGROUND_TASKS: set[asyncio.Task[None]] = set()

#: Actions deliberately NOT mirrored even though they flow through
#: ``write_audit``. High-frequency observability per the contract notes
#: (§4 "intentionally skipped — write-amplification concern") plus the
#: per-tick daemon health beacon. These would flood the CP with no
#: snapshot benefit.
HIGH_FREQUENCY_SKIP_ACTIONS: frozenset[str] = frozenset(
    {
        "dispatch.tool_activity",
        "dispatch.progress_signal",
        "daemon.healthy",
    }
)


# --- payload extractors ----------------------------------------------------
#
# Each extractor maps the daemon-side audit ``details`` dict to the CP
# ``payload`` shape named in the event contract. Extractors are total:
# missing keys resolve to ``None`` via ``details.get`` so a partial
# ``details`` never raises. The CP's Pydantic model accepts arbitrary
# ``payload`` dicts and silently drops unknown fields, so extra keys are
# harmless — but we keep extractors faithful to the contract's named
# fields for projector predictability.

PayloadExtractor = Callable[[dict[str, Any]], dict[str, Any]]


def _passthrough(details: dict[str, Any]) -> dict[str, Any]:
    """Forward the audit ``details`` verbatim.

    Used where the contract's named payload fields are a subset of the
    audit details and the CP drops the rest — faithful enough for the
    'missing projector' (skipped-projection) events whose payload is only
    recorded, not reduced.
    """
    return dict(details)


def _pick(*keys: str) -> PayloadExtractor:
    """Build an extractor that selects exactly ``keys`` from details.

    Missing keys are simply absent from the result (rather than ``None``)
    so the emitted payload stays tight to the contract.
    """

    def extractor(details: dict[str, Any]) -> dict[str, Any]:
        return {k: details[k] for k in keys if k in details}

    return extractor


def _dispatch_issued(details: dict[str, Any]) -> dict[str, Any]:
    out: dict[str, Any] = {
        "dispatch_id": details.get("dispatch_id"),
        "task_id": details.get("task_id"),
        "role": details.get("role"),
    }
    # Contract names working_directory; the daemon details carry it under
    # working_directory (dispatcher) — keep both source spellings tolerant.
    wd = details.get("working_directory") or details.get("cwd")
    if wd is not None:
        out["working_directory"] = wd
    if "engineer_decisions" in details:
        out["engineer_decisions"] = details["engineer_decisions"]
    return out


def _dispatch_subprocess_started(details: dict[str, Any]) -> dict[str, Any]:
    out: dict[str, Any] = {"dispatch_id": details.get("dispatch_id")}
    pid = details.get("subprocess_pid", details.get("pid"))
    if pid is not None:
        out["subprocess_pid"] = pid
    wd = details.get("working_directory") or details.get("cwd")
    if wd is not None:
        out["working_directory"] = wd
    return out


def _task_submitted(details: dict[str, Any]) -> dict[str, Any]:
    # daemon ``task.submit`` details carry the full SubmitTaskRequest dump
    # plus task_id/submitted_by/caller_source. The contract names a subset.
    out = {
        "task_id": details.get("task_id"),
        "agent_role": details.get("agent_role"),
        "description": details.get("description"),
        "priority": details.get("priority"),
        "complexity": details.get("complexity"),
        "submitted_by": details.get("submitted_by"),
        "caller_source": details.get("caller_source"),
    }
    if details.get("task_type") is not None:
        out["task_type"] = details["task_type"]
    return out


def _task_claimed(details: dict[str, Any]) -> dict[str, Any]:
    return {
        "task_id": details.get("task_id"),
        "claimed_by": details.get("claimed_by"),
    }


def _task_status_change(details: dict[str, Any]) -> dict[str, Any]:
    out: dict[str, Any] = {
        "task_id": details.get("task_id"),
        "new_status": details.get("to_status"),
    }
    if details.get("notes") is not None:
        out["notes"] = details["notes"]
    return out


def _cold_start_hang(details: dict[str, Any]) -> dict[str, Any]:
    return {
        "dispatch_id": details.get("dispatch_id"),
        "stage": details.get("stage"),
        "elapsed_seconds": details.get("elapsed_seconds"),
        "fs_activity": details.get("fs_activity"),
        "session_activity": details.get("session_activity"),
        "progress_signal": details.get("progress_signal"),
    }


def _merge_conflict(details: dict[str, Any]) -> dict[str, Any]:
    return {
        "dispatch_id": details.get("dispatch_id"),
        # daemon details spell it conflicting_files; contract: conflicted_files.
        "conflicted_files": details.get(
            "conflicted_files", details.get("conflicting_files")
        ),
    }


def _liveness_violation_detected(details: dict[str, Any]) -> dict[str, Any]:
    return {
        "violation_key": details.get("violation_key"),
        "violation_type": details.get("violation_type"),
        "observed_state": details.get("observed_state"),
        "safe_next_action": details.get("safe_next_action"),
    }


def _liveness_violation_escalated(details: dict[str, Any]) -> dict[str, Any]:
    return {
        "violation_key": details.get("violation_key"),
        "escalation_tick_count": details.get(
            "escalation_tick_count", details.get("tick_count")
        ),
    }


def _liveness_human_action_required(details: dict[str, Any]) -> dict[str, Any]:
    return {
        "violation_key": details.get("violation_key"),
        "violation_type": details.get("violation_type"),
        "reason": details.get("reason", details.get("safe_next_action")),
    }


def _pm_mode_auto_flip(details: dict[str, Any]) -> dict[str, Any]:
    return {
        "pm_mode_log_id": details.get("pm_mode_log_id"),
        "mode": details.get("new_mode", "interactive"),
        "triggered_by_agent_id": details.get("triggering_agent_id"),
    }


def _pm_mode_transition(details: dict[str, Any]) -> dict[str, Any]:
    return {
        "pm_mode_log_id": details.get("pm_mode_log_id"),
        "old_mode": details.get("old_mode"),
        "new_mode": details.get("new_mode"),
    }


# --- contract table --------------------------------------------------------
#
# (cp_event_type, payload_extractor) keyed by daemon audit action.
# This is the single point of truth on the daemon side; it mirrors the
# authoritative event_contract handed down by the CP build. Actions absent
# from this table are not mirrored (the emitter no-ops on them).
_ACTION_CONTRACT: dict[str, tuple[str, PayloadExtractor]] = {
    # dispatcher.py
    "dispatch.issued": ("dispatch.issued", _dispatch_issued),
    "dispatch.token_created": (
        "dispatch.issued",
        _pick("dispatch_id", "token_id", "role"),
    ),
    "dispatch.subprocess_started": (
        "dispatch.subprocess_started",
        _dispatch_subprocess_started,
    ),
    "dispatch.complete_called": (
        "dispatch.complete_called",
        _pick(
            "dispatch_id",
            "status",
            "commit_hash",
            "test_results",
            "artifacts",
            "notes",
        ),
    ),
    "dispatch.merged": (
        "dispatch.merged",
        _pick("dispatch_id", "commit_hash"),
    ),
    "dispatch.failed": ("dispatch.failed", _pick("dispatch_id", "reason")),
    "dispatch.cold_start_hang_detected": (
        "dispatch.cold_start_hang_detected",
        _cold_start_hang,
    ),
    "dispatch.cold_start_hang_warning": (
        "dispatch.progress_signal",
        _cold_start_hang,
    ),
    "dispatch.token_revoked": (
        "dispatch.progress_signal",
        _pick("dispatch_id", "token_id"),
    ),
    "dispatch.subprocess_terminated": (
        "dispatch.progress_signal",
        _pick("dispatch_id", "exit_code"),
    ),
    "dispatch.canonical_diverged": (
        "dispatch.progress_signal",
        _pick("dispatch_id"),
    ),
    "dispatch.merge_conflict": ("dispatch.progress_signal", _merge_conflict),
    # supervisor_base.py
    "dispatch.supervisor_cycle_completed": (
        "dispatch.progress_signal",
        _pick("dispatch_id"),
    ),
    "dispatch.supervisor_cycle_spin_halt": (
        "dispatch.progress_signal",
        _pick("dispatch_id"),
    ),
    "dispatch.subprocess_unexpected_exit": (
        "dispatch.progress_signal",
        _pick("dispatch_id", "exit_code", "signal"),
    ),
    "dispatch.heartbeat_timeout": (
        "dispatch.progress_signal",
        _pick("dispatch_id", "last_activity_at"),
    ),
    "dispatch.heartbeat_timeout_cap": (
        "dispatch.progress_signal",
        _pick("dispatch_id"),
    ),
    # tools/tasks.py
    "task.submit": ("task.submitted", _task_submitted),
    "task.claim": ("task.claimed", _task_claimed),
    # task.status_change fans out to task.completed|failed|declined by
    # to_status; see _resolve_event_type. The placeholder event_type here is
    # never used directly (the resolver overrides it).
    "task.status_change": ("task.status_change", _task_status_change),
    "task.priority_change": (
        "dispatch.progress_signal",
        _pick("task_id", "from_priority", "to_priority"),
    ),
    # bridge/activity.py + tools/pm_mode.py
    "pm_mode.auto_flip": ("dispatch.progress_signal", _pm_mode_auto_flip),
    "pm_mode.transition": ("dispatch.progress_signal", _pm_mode_transition),
    # bridge/autonomy_liveness_watchdog.py
    "liveness.tick_completed": ("dispatch.progress_signal", _passthrough),
    "liveness.violation_detected": (
        "dispatch.progress_signal",
        _liveness_violation_detected,
    ),
    "liveness.violation_escalated": (
        "dispatch.progress_signal",
        _liveness_violation_escalated,
    ),
    "liveness.violation_resolved": (
        "dispatch.progress_signal",
        _pick("violation_key"),
    ),
    "liveness.human_action_required": (
        "human_action.required",
        _liveness_human_action_required,
    ),
    # tools/canonical.py
    "canonical_clone.registered": (
        "project.canonical_registered",
        _pick("name", "path"),
    ),
    "canonical_clone.unregistered": (
        "dispatch.progress_signal",
        _pick("name"),
    ),
    # tools/budget.py
    "api.record_call": (
        "dispatch.progress_signal",
        _pick(
            "model",
            "input_tokens",
            "output_tokens",
            "cache_creation_tokens",
            "cache_read_tokens",
        ),
    ),
    # bridge/oauth_usage.py
    "oauth_usage.polling_ceiling_reached": (
        "dispatch.progress_signal",
        _passthrough,
    ),
}

# ``task.status_change`` maps to one of three terminal CP event types
# depending on the new status. The contract enumerates
# ``task.completed | task.failed | task.declined``. Resolved at emit time
# from the audit details' ``to_status``; an unrecognized status falls back
# to the generic progress signal rather than an invalid CP event_type.
_TASK_STATUS_TO_EVENT_TYPE: dict[str, str] = {
    "completed": "task.completed",
    "failed": "task.failed",
    "declined": "task.declined",
}


def _resolve_event_type(action: str, details: dict[str, Any]) -> str | None:
    """Resolve the CP event_type for an audit action.

    Returns ``None`` when the action is not in the contract — the caller
    treats that as 'do not mirror'. ``task.status_change`` is the one
    action that fans out across multiple event_types based on
    ``to_status``.
    """
    mapped = _ACTION_CONTRACT.get(action)
    if mapped is None:
        return None
    event_type, _ = mapped
    if action == "task.status_change":
        to_status = str(details.get("to_status") or "")
        # Only the three terminal statuses have CP task.* event types.
        # Non-terminal transitions (e.g. → in_progress) are not in the
        # CP's enum, so fall through to a generic progress signal.
        return _TASK_STATUS_TO_EVENT_TYPE.get(to_status, "dispatch.progress_signal")
    return event_type


def _resolve_settings_url() -> str | None:
    """Read ``control_plane_url`` from Settings, swallowing any error.

    Settings is constructed lazily per emission (cheap relative to an HTTP
    round-trip and avoids stale-config). Any construction failure — e.g. a
    misconfigured env that trips a validator — is swallowed so the emitter
    degrades to a no-op rather than affecting the caller.
    """
    try:
        from meridian_orchestrator.config import Settings

        return Settings().control_plane_url
    except Exception as exc:  # pragma: no cover - defensive
        logger.warning(
            "control_plane_emitter.settings_unavailable",
            error=str(exc),
            error_type=type(exc).__name__,
        )
        return None


def build_event(
    *,
    action: str,
    target: str | None,
    details: dict[str, Any] | None,
) -> dict[str, Any] | None:
    """Map a daemon audit (action, target, details) to a CP event dict.

    Returns the JSON-serializable event payload for the ingest endpoint, or
    ``None`` when the action should not be mirrored (not in the contract or
    in :data:`HIGH_FREQUENCY_SKIP_ACTIONS`).

    The CP server sets ``received_at`` itself and ignores any client value,
    so it is intentionally omitted. ``severity`` is free-form on the CP; the
    daemon emits ``"info"`` (the audit row's success/failure nuance is
    carried in ``payload`` where present).

    ``target`` is the audit row's per-resource subject (dispatch_id, task_id,
    canonical name, …); it is used here only as a correlation-id fallback
    when ``details`` omits the matching id, and is surfaced in payload under
    ``audit_target`` for traceability.
    """
    if action in HIGH_FREQUENCY_SKIP_ACTIONS:
        return None

    safe_details = details or {}
    event_type = _resolve_event_type(action, safe_details)
    if event_type is None:
        return None

    _, extractor = _ACTION_CONTRACT[action]
    try:
        payload = extractor(safe_details)
    except Exception as exc:  # pragma: no cover - extractors are total
        logger.warning(
            "control_plane_emitter.payload_extraction_failed",
            action=action,
            error=str(exc),
            error_type=type(exc).__name__,
        )
        payload = {}

    # Surface the originating daemon action (+ audit target) inside payload
    # for downstream disambiguation — several daemon actions collapse onto
    # dispatch.progress_signal, and target carries the resource subject.
    payload.setdefault("audit_action", action)
    if target is not None:
        payload.setdefault("audit_target", target)

    event: dict[str, Any] = {
        "event_id": str(uuid.uuid4()),
        "event_type": event_type,
        "timestamp": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        "source": EVENT_SOURCE,
        "severity": "info",
        "payload": payload,
    }

    # Promote correlation ids to the top-level fields the CP indexes on
    # (these are optional on the model). Prefer explicit details ids, then
    # extractor payload, then the audit target as a last-resort fallback.
    dispatch_id = safe_details.get("dispatch_id") or payload.get("dispatch_id")
    task_id = safe_details.get("task_id") or payload.get("task_id")
    if dispatch_id is None and action.startswith("dispatch."):
        dispatch_id = target
    if task_id is None and action.startswith("task."):
        task_id = target
    if dispatch_id is not None:
        event["dispatch_id"] = dispatch_id
    if task_id is not None:
        event["task_id"] = task_id
    if safe_details.get("role") is not None:
        event["role"] = safe_details["role"]

    return event


async def _post_event(url: str, event: dict[str, Any]) -> None:
    """POST a single event to the CP ingest endpoint, swallowing all errors.

    Runs on a detached task; nothing awaits its result. A non-2xx response,
    timeout, connection error, or any other exception is logged at WARNING
    and discarded — the daemon lifecycle is unaffected.
    """
    endpoint = url.rstrip("/") + INGEST_PATH
    try:
        async with httpx.AsyncClient(
            timeout=DEFAULT_HTTP_TIMEOUT_SECONDS
        ) as client:
            response = await client.post(endpoint, json=event)
        if response.status_code >= 400 and response.status_code != 409:
            # 409 is an expected dedupe outcome (duplicate event_id), not an
            # error worth WARNING on.
            logger.warning(
                "control_plane_emitter.non_success_response",
                status_code=response.status_code,
                event_type=event.get("event_type"),
            )
    except Exception as exc:
        logger.warning(
            "control_plane_emitter.post_failed",
            error=str(exc),
            error_type=type(exc).__name__,
            event_type=event.get("event_type"),
        )


def emit_event(
    *,
    action: str,
    target: str | None = None,
    details: dict[str, Any] | None = None,
    control_plane_url: str | None = None,
) -> None:
    """Fire-and-forget mirror of a daemon audit event to the Control Plane.

    Synchronous entry point intended to be called from inside
    :func:`audit.logger.write_audit` right after the audit row is written.
    It NEVER blocks (the HTTP work runs on a detached :func:`asyncio.create_task`)
    and NEVER raises into its caller — every failure mode is swallowed.

    No-op fast paths (no HTTP request spawned):

    - ``control_plane_url`` resolves to ``None`` (CP not configured).
    - ``action`` is not in the contract / is a high-frequency skip action.
    - No running event loop (e.g. called from sync test context) — logged
      and dropped rather than raising.
    """
    try:
        url = (
            control_plane_url
            if control_plane_url is not None
            else _resolve_settings_url()
        )
        if not url:
            return

        event = build_event(action=action, target=target, details=details)
        if event is None:
            return

        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            # No running loop — can't schedule the detached task. The audit
            # row is already persisted; the CP mirror is best-effort, so we
            # simply drop it with a WARNING rather than block on a fresh loop.
            logger.warning(
                "control_plane_emitter.no_event_loop",
                action=action,
            )
            return

        task = loop.create_task(_post_event(url, event))
        # Keep a strong reference until the task finishes so it isn't GC'd
        # mid-flight; the done-callback removes it.
        _BACKGROUND_TASKS.add(task)
        task.add_done_callback(_BACKGROUND_TASKS.discard)
    except Exception as exc:  # pragma: no cover - belt-and-suspenders
        # The whole point of this module is that it can never affect the
        # daemon lifecycle. Any unexpected failure is swallowed here.
        logger.warning(
            "control_plane_emitter.emit_failed",
            action=action,
            error=str(exc),
            error_type=type(exc).__name__,
        )


__all__ = [
    "DEFAULT_HTTP_TIMEOUT_SECONDS",
    "EVENT_SOURCE",
    "HIGH_FREQUENCY_SKIP_ACTIONS",
    "INGEST_PATH",
    "build_event",
    "emit_event",
]
