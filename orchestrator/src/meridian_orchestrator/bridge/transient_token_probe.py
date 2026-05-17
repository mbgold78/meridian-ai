"""Startup-time observability probe for the keyring/SQLite two-backend split.

Per ratified slice 1b spec (``docs/specs/M5-sqlite-transient-token-store-
slice-1b-implementer.md`` § Commit 6), the daemon's lifespan body
invokes a one-shot probe at startup that:

- Detects **orphan** keyring entries — ``token:<id>`` records that are
  NOT in the keyring ``_index`` AND NOT in SQLite ``transient_tokens``.
  These are pre-slice-1a entries created with ``indexed=False`` that
  never migrated to the SQLite backend (β-S11-5 / β-S11-6 residuals).
  Predicate per PM-ratified A1=(a) — held verbatim in :meth:`run`'s
  set arithmetic.

- Detects **double-presence** token_ids — the same ``token_id`` present
  in both keyring and SQLite ``transient_tokens``. Indicates either a
  slice-1a routing-bleed (a token was somehow written to both backends)
  or operator-induced cross-backend duplication. Anomalous and worth
  flagging.

Pure observability (M1=(c) ratified report-only): NO mutation. NO
destructive action. The probe is read-only inspection across both
backends. Slice 1c (gated) ships the destructive cleanup variant.

Probe failure semantics — internal exceptions are caught at the call
site (:func:`main._run_lifespan_body`) and logged as WARNING; the
daemon reaches ``startup.complete`` regardless. Observability is not
load-bearing for daemon liveness (per spec § Lifespan ordering;
probe failure mode).

Audit event contracts (two new event kinds; emitted per occurrence):

- ``auth.orphan.detected`` — fields ``{source: "keyring",
  token_id_hash: str, observation_only: bool}``. ``token_id_hash`` is
  sha256 hex of the raw token_id; never emit raw token_id even in
  observability-only events (defensive against log-grep correlation
  attacks per spec § Commit 6).

- ``auth.double_presence.detected`` — fields ``{token_id_hash: str,
  observation_only: bool}``. Same sha256 hashing discipline.

Keyring enumeration — the production Windows keyring backend
(:class:`keyring.backends.Windows.WinVaultKeyring`) does not expose
a public ``list_credentials`` / enumeration API; the public keyring
interface is read-by-key only. The probe enumerates via the
backend's ``_store`` attribute when present — the in-memory backend
used in tests is the principal consumer; some debug backends also
expose it. When the active backend doesn't expose enumeration, the
probe logs a WARN and reports zero orphans. The double-presence
check still works on production Windows because it intersects the
keyring ``_index`` (enumerable via the public API) with the SQLite
``transient_tokens`` SELECT (also enumerable). Per spec
§ Predictions: a per-platform Windows shim (e.g., win32cred-based
enumeration) is welcome if/when operator-side observability of
orphans on production Windows becomes a concrete operational
concern; deferred at this commit because the immediate failure mode
(orphan leakage) was already addressed at slice 1a's routing layer.
"""

from __future__ import annotations

import hashlib
import json
import time

import keyring
import structlog

from meridian_orchestrator.audit.logger import write_audit
from meridian_orchestrator.auth import (
    INDEX_USERNAME,
    KEYRING_SERVICE,
    TokenStore,
)
from meridian_orchestrator.storage.interface import Storage

logger = structlog.get_logger(__name__)

ORPHAN_DETECTED_AUDIT_ACTION: str = "auth.orphan.detected"
DOUBLE_PRESENCE_DETECTED_AUDIT_ACTION: str = "auth.double_presence.detected"
_TOKEN_KEY_PREFIX: str = "token:"


def _hash_token_id(token_id: str) -> str:
    """sha256(token_id) hex.

    Defensive against log-grep correlation attacks per spec § Commit 6
    audit-event contracts. Even in observability-only events the raw
    ``token_id`` is never emitted — a still-active non-orphan token
    might share a ``token_id`` with an orphan in some operational
    scenarios (e.g., transient → keyring routing flipped mid-flight)
    and exposing the raw id would let a reader correlate against
    other auth-log surfaces.
    """
    return hashlib.sha256(token_id.encode("utf-8")).hexdigest()


def _enumerate_keyring_token_ids(service: str) -> set[str]:
    """Best-effort enumeration of ``token:<id>`` entries in the active
    keyring backend.

    Backends that expose a ``_store`` dict (the in-memory backend used
    in tests, plus some debug backends) are iterated directly. The
    production Windows backend does not expose this surface; absent
    enumeration, this function logs a WARN and returns ``set()`` so
    the probe gracefully degrades to "double-presence detection only"
    rather than failing the call site.
    """
    backend = keyring.get_keyring()
    store = getattr(backend, "_store", None)
    if isinstance(store, dict):
        out: set[str] = set()
        for key in store:
            if not isinstance(key, tuple) or len(key) != 2:
                continue
            svc, username = key
            if (
                svc == service
                and isinstance(username, str)
                and username.startswith(_TOKEN_KEY_PREFIX)
            ):
                out.add(username[len(_TOKEN_KEY_PREFIX):])
        return out
    logger.warning(
        "transient_token_probe.keyring_enumeration_unavailable",
        backend_class=type(backend).__name__,
        message=(
            "active keyring backend does not expose _store enumeration; "
            "orphan probe will report zero orphans. Double-presence "
            "detection still functions via _index intersection with "
            "SQLite transient_tokens."
        ),
    )
    return set()


class TransientTokenStartupProbe:
    """One-shot startup probe over the keyring + SQLite two-backend
    split.

    Constructed at lifespan-start; one call to :meth:`run` drives a
    single combined scan (A4 ratified — both signals share one keyring
    enumeration so the cost of iterating the namespace is paid once).
    Emits one audit row per detected orphan and one per detected
    double-presence; returns counts for a startup log summary.

    Pure observability (M1=(c) ratified report-only): NO mutation. NO
    destructive action. ``token_store`` is held so the constructor
    surface is symmetric with the slice 1c destructive variant; the
    read-only probe at slice 1b reaches the keyring through the same
    module-level :mod:`keyring` interface :class:`TokenStore` uses,
    plus the ``KEYRING_SERVICE`` / ``INDEX_USERNAME`` constants. The
    service name is read from ``token_store._service`` so test
    fixtures that pass custom service names via ``TokenStore(service=…)``
    are honored without parallel construction.
    """

    def __init__(
        self,
        storage: Storage,
        token_store: TokenStore,
    ) -> None:
        self._storage = storage
        self._token_store = token_store

    async def run(self) -> dict[str, int]:
        """Execute the combined scan.

        Returns counts for the startup log summary:
        ``{orphans_detected, double_presences_detected, scan_duration_ms}``.
        Side effects: per-orphan ``auth.orphan.detected`` audit rows
        and per-occurrence ``auth.double_presence.detected`` audit rows.

        Orphan predicate (PM-ratified A1=(a), verbatim from spec):

            keyring `token:<id>` entry NOT in keyring `_index`
            AND NOT in SQLite `transient_tokens`.

        Double-presence predicate: same ``token_id`` present in BOTH
        SQLite ``transient_tokens`` AND keyring (regardless of
        ``_index`` membership). The keyring side of the intersection
        uses the union of enumerated keyring entries and ``_index``
        entries so production-Windows paths (where enumeration is
        unavailable) still detect double-presence via the ``_index``
        ∩ SQLite intersection.
        """
        perf_start = time.perf_counter()

        # The keyring service name. TokenStore stores it as ``_service``;
        # accessing the private attribute is fine here — both modules
        # are part of the same internal codebase and the constant is
        # otherwise unreachable for the rare test that overrides it.
        service = getattr(
            self._token_store, "_service", KEYRING_SERVICE
        )

        # Indexed token_ids — the keyring ``_index`` aggregator. Static
        # seeds + OAuth-issued long-lived credentials.
        indexed_ids = _read_index_ids(service)

        # Keyring-side ``token:*`` entries. Best-effort; production
        # Windows returns an empty set with a WARN log.
        keyring_ids = _enumerate_keyring_token_ids(service)

        # SQLite ``transient_tokens`` row token_ids.
        sqlite_rows = await self._storage.fetch_all(
            "SELECT token_id FROM transient_tokens"
        )
        sqlite_ids: set[str] = {row["token_id"] for row in sqlite_rows}

        # Orphan: keyring entry NOT in _index AND NOT in SQLite.
        orphan_ids = keyring_ids - indexed_ids - sqlite_ids

        # Double-presence: same token_id in BOTH SQLite AND keyring.
        # Use the union of enumerated entries and _index entries on the
        # keyring side so the production-Windows path (enumeration
        # unavailable; keyring_ids empty) still detects via _index.
        keyring_known_ids = keyring_ids | indexed_ids
        double_presence_ids = keyring_known_ids & sqlite_ids

        # Deterministic emit order helps audit-log diffing across boots.
        for token_id in sorted(orphan_ids):
            await self._emit_orphan_audit(token_id)
        for token_id in sorted(double_presence_ids):
            await self._emit_double_presence_audit(token_id)

        scan_duration_ms = int((time.perf_counter() - perf_start) * 1000)
        return {
            "orphans_detected": len(orphan_ids),
            "double_presences_detected": len(double_presence_ids),
            "scan_duration_ms": scan_duration_ms,
        }

    async def _emit_orphan_audit(self, token_id: str) -> None:
        await write_audit(
            self._storage,
            actor="system",
            action=ORPHAN_DETECTED_AUDIT_ACTION,
            target=None,
            details={
                "source": "keyring",
                "token_id_hash": _hash_token_id(token_id),
                "observation_only": True,
            },
        )

    async def _emit_double_presence_audit(self, token_id: str) -> None:
        await write_audit(
            self._storage,
            actor="system",
            action=DOUBLE_PRESENCE_DETECTED_AUDIT_ACTION,
            target=None,
            details={
                "token_id_hash": _hash_token_id(token_id),
                "observation_only": True,
            },
        )


def _read_index_ids(service: str) -> set[str]:
    """Read the keyring ``_index`` aggregator and return the set of
    indexed token_ids. Mirrors :meth:`TokenStore._read_index` but
    returns a set for fast set arithmetic in :meth:`run`."""
    raw = keyring.get_password(service, INDEX_USERNAME)
    if raw is None:
        return set()
    try:
        data = json.loads(raw)
    except (json.JSONDecodeError, AttributeError):
        return set()
    return {
        tid
        for tid in data.get("token_ids", [])
        if isinstance(tid, str)
    }


__all__ = [
    "DOUBLE_PRESENCE_DETECTED_AUDIT_ACTION",
    "ORPHAN_DETECTED_AUDIT_ACTION",
    "TransientTokenStartupProbe",
]
