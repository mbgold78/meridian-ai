"""Best-effort daemon → Control Plane autonomy-intent read.

Operator Fix Window 2026-05-28. The :class:`AutonomyLivenessWatchdog`
runs daemon-side and has ZERO visibility into each project's autonomy
MODE (``Frozen``/``Manual``/``Continuous``/``Guided``/``Maintenance``) or
callback-arming state — both of which live ONLY in the Control Plane
backend (``controlplane.db``, not the daemon's ``orchestrator.db``). The
CP evaluates callback violations conditionally; the daemon's independent
watchdog historically could not, so it fired false-positive violations
(and spawned priority-0 PM tasks) for ``Frozen``/``Manual`` projects
where autonomous work is intentionally suppressed.

This module is the thin seam that lets the daemon respect CP intent
WITHOUT coupling to the CP's database:

* The CP exposes ``GET /api/daemon/intent`` returning
  ``{"projects": [{"projectId", "autonomy", "callbackArmed",
  "callbackMechanism"}, ...]}`` (the agreed intent seam).
* The daemon polls it once per watchdog tick (cached for the cache TTL),
  with a short timeout and **fully swallowed** failure modes.
* On ANY failure — CP unset, network error, non-2xx, malformed JSON —
  the fetch returns ``None`` and the watchdog **fails open** (runs every
  rule unconditionally, the conservative pre-existing behavior). The
  watchdog stays independent: it works even when the CP is down.

**Hard constraints** (subscription-only / never-block daemon build
rules, mirroring :mod:`bridge.control_plane_emitter`):

- **Best-effort.** This read MUST NEVER raise into the watchdog tick or
  the daemon lifecycle. Every failure is swallowed with a WARNING log.
- **No-op when unconfigured.** If ``Settings.control_plane_url`` is unset
  the fetch returns ``None`` immediately and spawns no HTTP request.
- **No new credentials.** Plain ``httpx`` (already a dependency); no
  ANTHROPIC_API_KEY, no extra-usage path.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any

import httpx
import structlog

logger = structlog.get_logger(__name__)

#: Path appended to ``Settings.control_plane_url`` for the intent read.
INTENT_PATH = "/api/daemon/intent"

#: Per-request timeout. Kept short — a slow CP must never back up the
#: watchdog tick. On timeout the fetch fails open to unconditional checks.
DEFAULT_HTTP_TIMEOUT_SECONDS = 5.0

#: How long a fetched snapshot is reused before a fresh fetch. Defaults to
#: one watchdog tick period so each tick does at most one CP round-trip.
DEFAULT_CACHE_TTL_SECONDS = 60.0

#: Autonomy modes for which the watchdog SUPPRESSES its rules. Per the
#: 2026-05-28 directive: skip detection entirely for ``Frozen`` / ``Manual``
#: projects (no autonomous work is expected there, so a "stalled" task is
#: not a violation). ``Continuous`` / ``Guided`` / ``Maintenance`` projects
#: remain checked. Conservative: we only suppress when the CP gives us an
#: explicit Frozen/Manual signal; absence of a signal fails open to checking.
SUPPRESSED_MODES: frozenset[str] = frozenset({"Frozen", "Manual"})


@dataclass(frozen=True)
class ProjectIntent:
    """A single project's autonomy intent as seen by the Control Plane."""

    project_id: str
    autonomy: str | None
    callback_armed: bool
    callback_mechanism: str | None = None
    # Per-project ULTRACODE toggle (op-ultracode-daemon). The CP stores
    # this flag per project and surfaces it on ``GET /api/daemon/intent``;
    # the dispatcher reads it (keyed by canonical) to decide whether to
    # append ``--settings '{"ultracode": true}'`` to the launched claude
    # argv. Default ``False`` so a CP that hasn't been extended yet (or a
    # row that omits the field) reads as ultracode-off — the fail-closed
    # posture for an expensive fan-out feature.
    ultracode: bool = False
    # The daemon canonical-clone name this project routes to (the same
    # literal threaded through ``dispatches.canonical_name``). Used to
    # build the snapshot's ``by_canonical`` index so the dispatcher — which
    # resolves work by canonical, not CP ``projectId`` — can look the flag
    # up. ``None`` when the CP row omits it (the project simply won't be
    # found by canonical, so the dispatcher fails closed to ultracode-off).
    canonical: str | None = None

    @property
    def rules_suppressed(self) -> bool:
        """True when the watchdog should skip ALL rules for this project.

        Suppressed when the CP reports the project in a mode where
        autonomous work is intentionally not running (``Frozen`` /
        ``Manual``). Any other / unknown mode is NOT suppressed (fail
        open to checking)."""
        return self.autonomy in SUPPRESSED_MODES


@dataclass
class AutonomyIntentSnapshot:
    """A point-in-time view of every project's autonomy intent.

    ``by_project`` maps daemon ``project_id`` (== CP ``projectId``) to
    :class:`ProjectIntent`. A project absent from the map has NO known
    intent — callers fail open (treat it as checkable).

    ``by_canonical`` maps the daemon canonical-clone name to the same
    :class:`ProjectIntent`, built only for rows that carry a non-empty
    ``canonical``. The dispatcher resolves work by canonical (not CP
    ``projectId``), so :meth:`ultracode_for` uses this index. A canonical
    absent from the map has NO known ULTRACODE intent — :meth:`ultracode_for`
    fails CLOSED (returns ``False``)."""

    by_project: dict[str, ProjectIntent] = field(default_factory=dict)
    by_canonical: dict[str, ProjectIntent] = field(default_factory=dict)
    fetched_at_monotonic: float = field(default_factory=time.monotonic)

    def ultracode_for(self, canonical: str | None) -> bool:
        """Is ULTRACODE enabled for the project routing to ``canonical``?

        Fail-CLOSED semantics (the inverse of :meth:`is_suppressed`'s
        fail-open): a missing/empty ``canonical``, a canonical absent from
        the snapshot, or any project whose flag is off all read as
        ``False``. Callers that hold ``None`` for the whole snapshot (CP
        unconfigured / unreachable) also fail closed — there is no snapshot
        to consult, so ULTRACODE stays off. ULTRACODE is an expensive
        opt-in fan-out feature; the conservative default is OFF unless the
        CP explicitly says ON for this canonical."""
        if not canonical:
            return False
        intent = self.by_canonical.get(canonical)
        if intent is None:
            return False
        return intent.ultracode

    def is_suppressed(self, project_id: str | None) -> bool:
        """Should the watchdog suppress rules for ``project_id``?

        Fail-open semantics: a project with no project_id, or one absent
        from the snapshot, is NEVER suppressed (we lack a clear Frozen/
        Manual signal, so we keep the independent check running)."""
        if not project_id:
            return False
        intent = self.by_project.get(project_id)
        if intent is None:
            return False
        return intent.rules_suppressed


def _parse_snapshot(body: Any) -> AutonomyIntentSnapshot:
    """Parse the CP intent payload into a snapshot, tolerating partials.

    Accepts the agreed shape ``{"projects": [{...}, ...]}``. Rows missing
    a ``projectId`` are skipped; missing ``autonomy`` becomes ``None``
    (fail open); missing ``ultracode`` becomes ``False`` and missing
    ``canonical`` becomes ``None`` (fail CLOSED for the ULTRACODE read).
    A non-dict body or missing ``projects`` key yields an empty snapshot
    (fail open everywhere)."""
    snapshot = AutonomyIntentSnapshot()
    if not isinstance(body, dict):
        return snapshot
    projects = body.get("projects")
    if not isinstance(projects, list):
        return snapshot
    for raw in projects:
        if not isinstance(raw, dict):
            continue
        project_id = raw.get("projectId") or raw.get("project_id")
        if not project_id:
            continue
        autonomy = raw.get("autonomy")
        callback_armed = bool(
            raw.get("callbackArmed", raw.get("callback_armed", False))
        )
        mechanism = raw.get("callbackMechanism", raw.get("callback_mechanism"))
        # ULTRACODE flag — default False on absence (fail closed). bool()
        # coerces a JSON true/false; a stray non-bool (e.g. 1/"true") also
        # coerces sensibly without raising.
        ultracode = bool(raw.get("ultracode", False))
        # Canonical-clone name routing this project's daemon work. Read
        # tolerantly under either camelCase or snake_case; None when absent
        # so the project simply won't appear in the by_canonical index.
        canonical_raw = raw.get("canonical", raw.get("canonicalName"))
        canonical = str(canonical_raw) if canonical_raw else None
        intent = ProjectIntent(
            project_id=str(project_id),
            autonomy=str(autonomy) if autonomy is not None else None,
            callback_armed=callback_armed,
            callback_mechanism=str(mechanism) if mechanism is not None else None,
            ultracode=ultracode,
            canonical=canonical,
        )
        snapshot.by_project[str(project_id)] = intent
        if canonical:
            snapshot.by_canonical[canonical] = intent
    return snapshot


async def fetch_autonomy_intent(
    *,
    control_plane_url: str | None,
    timeout_seconds: float = DEFAULT_HTTP_TIMEOUT_SECONDS,
) -> AutonomyIntentSnapshot | None:
    """Fetch the autonomy-intent snapshot from the Control Plane.

    Returns the parsed :class:`AutonomyIntentSnapshot` on success, or
    ``None`` on ANY failure (CP unset, network error, non-2xx, malformed
    JSON). ``None`` is the watchdog's signal to fail open — run every
    rule unconditionally. NEVER raises.
    """
    if not control_plane_url:
        return None
    endpoint = control_plane_url.rstrip("/") + INTENT_PATH
    try:
        async with httpx.AsyncClient(timeout=timeout_seconds) as client:
            response = await client.get(endpoint)
        if response.status_code >= 400:
            logger.warning(
                "autonomy_intent.non_success_response",
                status_code=response.status_code,
            )
            return None
        body = response.json()
    except Exception as exc:
        logger.warning(
            "autonomy_intent.fetch_failed",
            error=str(exc),
            error_type=type(exc).__name__,
        )
        return None
    try:
        return _parse_snapshot(body)
    except Exception as exc:  # pragma: no cover - parser is total
        logger.warning(
            "autonomy_intent.parse_failed",
            error=str(exc),
            error_type=type(exc).__name__,
        )
        return None


__all__ = [
    "DEFAULT_CACHE_TTL_SECONDS",
    "DEFAULT_HTTP_TIMEOUT_SECONDS",
    "INTENT_PATH",
    "SUPPRESSED_MODES",
    "AutonomyIntentSnapshot",
    "ProjectIntent",
    "fetch_autonomy_intent",
]
