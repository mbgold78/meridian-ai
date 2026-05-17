"""Unit tests for :class:`bridge.transient_token_probe.TransientTokenStartupProbe`.

Per M5-sqlite-transient-token-store slice 1b commit 6 spec
(``docs/specs/M5-sqlite-transient-token-store-slice-1b-implementer.md``
§ Commit 6): 8 unit tests covering the orphan / double-presence
predicates, the returned summary dict, the per-event audit-row
contract, and the pure-observability invariants (probe must not mutate
either backend).

Lifespan wiring is exercised separately at
``tests/integration/auth/test_transient_token_lifespan.py``.
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import AsyncIterator
from pathlib import Path

import keyring
import pytest_asyncio

from meridian_orchestrator.auth import (
    INDEX_USERNAME,
    TokenStore,
    _record_key,
)
from meridian_orchestrator.bridge.transient_token_probe import (
    DOUBLE_PRESENCE_DETECTED_AUDIT_ACTION,
    ORPHAN_DETECTED_AUDIT_ACTION,
    TransientTokenStartupProbe,
)
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.transient_tokens import TransientTokenStore

_TEST_SERVICE = "probe-test-service"


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    """Fresh SqliteStorage with all migrations applied (including the
    slice 1a transient_tokens table)."""
    s = SqliteStorage(tmp_path / "test.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


@pytest_asyncio.fixture
async def transient_store(storage: SqliteStorage) -> TransientTokenStore:
    return TransientTokenStore(storage)


@pytest_asyncio.fixture
async def token_store(
    transient_store: TransientTokenStore,
) -> TokenStore:
    """TokenStore wired with the transient backend and pointed at the
    test service name — the probe reads the service from
    ``token_store._service``."""
    return TokenStore(
        service=_TEST_SERVICE,
        transient_store=transient_store,
    )


@pytest_asyncio.fixture
async def probe(
    storage: SqliteStorage, token_store: TokenStore
) -> TransientTokenStartupProbe:
    return TransientTokenStartupProbe(
        storage=storage, token_store=token_store
    )


def _seed_keyring_orphan(token_id: str) -> None:
    """Write a raw ``token:<id>`` entry directly into the keyring,
    bypassing ``_index``. Simulates pre-slice-1a ``indexed=False``
    keyring entries that were never migrated to SQLite — the cohort the
    orphan probe is designed to detect."""
    keyring.set_password(
        _TEST_SERVICE,
        _record_key(token_id),
        json.dumps({"token_id": token_id, "fake_payload": True}),
    )


async def _insert_transient_row(
    storage: SqliteStorage, token_id: str
) -> None:
    """Insert a row directly into ``transient_tokens`` bypassing the
    issuance path. Used to seed double-presence scenarios (same
    token_id present in both backends) without triggering keyring
    writes."""
    await storage.execute(
        "INSERT INTO transient_tokens "
        "(token_id, agent_id, role, label, secret_hash, "
        "issued_at, revoked_at, ttl_seconds, expires_at) "
        "VALUES (?, 'agent-x', 'implementer', 'test', 'h', "
        "'2026-01-01T00:00:00+00:00', NULL, 3600, "
        "'2099-01-01T00:00:00+00:00')",
        (token_id,),
    )


async def _fetch_audit_rows(
    storage: SqliteStorage, action: str
) -> list[dict]:
    return await storage.fetch_all(
        "SELECT id, actor, action, target, details_json, success "
        "FROM audit_log WHERE action = ? ORDER BY id",
        (action,),
    )


# ---------------------------------------------------------------------------
# Orphan predicate
# ---------------------------------------------------------------------------


async def test_orphan_detected_for_unindexed_keyring_entry(
    storage: SqliteStorage, probe: TransientTokenStartupProbe
) -> None:
    """Keyring ``token:<id>`` entry NOT in ``_index`` AND NOT in SQLite
    ``transient_tokens`` is flagged as an orphan. Verifies the
    PM-ratified A1=(a) predicate verbatim:

        Orphan = keyring `token:<id>` entry
                  NOT in keyring `_index`
                  AND NOT in SQLite `transient_tokens`.

    Also pins the sha256-hex hashing discipline on the audit row's
    ``token_id_hash`` field — raw token_id must never leak into the
    audit details payload.
    """
    orphan_token_id = "deadbeefcafebabe"
    _seed_keyring_orphan(orphan_token_id)

    result = await probe.run()

    assert result["orphans_detected"] == 1
    assert result["double_presences_detected"] == 0

    audits = await _fetch_audit_rows(
        storage, ORPHAN_DETECTED_AUDIT_ACTION
    )
    assert len(audits) == 1
    audit = audits[0]
    details = json.loads(audit["details_json"])
    expected_hash = hashlib.sha256(
        orphan_token_id.encode("utf-8")
    ).hexdigest()
    assert details["token_id_hash"] == expected_hash
    # Raw token_id is NEVER emitted — defensive against log-grep
    # correlation attacks per spec § Commit 6 audit contracts.
    assert orphan_token_id not in audit["details_json"]


async def test_no_orphan_for_indexed_keyring_entry(
    storage: SqliteStorage,
    probe: TransientTokenStartupProbe,
    token_store: TokenStore,
) -> None:
    """Keyring entry that IS in ``_index`` (e.g., a static seed token
    issued via ``TokenStore.issue(indexed=True)``) must NOT be flagged
    as orphan. Encodes A1=(c) rejection from the spec — indexed
    keyring tokens are intended keyring-only, not orphans."""
    await token_store.issue(
        agent_id=None, role="pm", label="static-seed-pm"
    )

    result = await probe.run()

    assert result["orphans_detected"] == 0
    audits = await _fetch_audit_rows(
        storage, ORPHAN_DETECTED_AUDIT_ACTION
    )
    assert len(audits) == 0


async def test_no_orphan_for_sqlite_only_token(
    storage: SqliteStorage, probe: TransientTokenStartupProbe
) -> None:
    """Token present only in SQLite (no keyring `token:<id>` entry)
    must NOT be flagged as orphan. The orphan predicate is
    keyring-side state by definition — a SQLite-only row is the
    expected transient post-slice-1a shape, not an orphan."""
    sqlite_only_id = "0123456789abcdef"
    await _insert_transient_row(storage, sqlite_only_id)

    result = await probe.run()

    assert result["orphans_detected"] == 0
    audits = await _fetch_audit_rows(
        storage, ORPHAN_DETECTED_AUDIT_ACTION
    )
    assert len(audits) == 0


# ---------------------------------------------------------------------------
# Double-presence predicate
# ---------------------------------------------------------------------------


async def test_double_presence_detected_for_cross_backend_token(
    storage: SqliteStorage,
    probe: TransientTokenStartupProbe,
    token_store: TokenStore,
) -> None:
    """Same ``token_id`` present in BOTH keyring (via ``_index``) AND
    SQLite ``transient_tokens`` is flagged as double-presence. Models
    the anomalous slice-1a-routing-bleed scenario — a token that
    somehow ended up in both backends. Verifies sha256-hex hashing
    on the audit row."""
    issued = await token_store.issue(
        agent_id=None, role="pm", label="double-presence-source"
    )
    cross_token_id = issued.record.token_id
    await _insert_transient_row(storage, cross_token_id)

    result = await probe.run()

    assert result["double_presences_detected"] == 1
    # The keyring entry is in _index so it's NOT also an orphan — both
    # signals correctly classify the same token_id.
    assert result["orphans_detected"] == 0

    audits = await _fetch_audit_rows(
        storage, DOUBLE_PRESENCE_DETECTED_AUDIT_ACTION
    )
    assert len(audits) == 1
    audit = audits[0]
    details = json.loads(audit["details_json"])
    expected_hash = hashlib.sha256(
        cross_token_id.encode("utf-8")
    ).hexdigest()
    assert details["token_id_hash"] == expected_hash


# ---------------------------------------------------------------------------
# Return-value contract
# ---------------------------------------------------------------------------


async def test_probe_returns_counts(
    storage: SqliteStorage,
    probe: TransientTokenStartupProbe,
    token_store: TokenStore,
) -> None:
    """The probe returns the documented summary dict with both counts
    and the scan duration. Verifies the wire shape against the spec:

        {orphans_detected, double_presences_detected, scan_duration_ms}.
    """
    _seed_keyring_orphan("aaaaaaaaaaaaaaaa")
    issued = await token_store.issue(
        agent_id=None, role="pm", label="dual-presence"
    )
    await _insert_transient_row(storage, issued.record.token_id)

    result = await probe.run()

    assert set(result.keys()) == {
        "orphans_detected",
        "double_presences_detected",
        "scan_duration_ms",
    }
    assert isinstance(result["orphans_detected"], int)
    assert isinstance(result["double_presences_detected"], int)
    assert isinstance(result["scan_duration_ms"], int)
    assert result["orphans_detected"] == 1
    assert result["double_presences_detected"] == 1
    assert result["scan_duration_ms"] >= 0


# ---------------------------------------------------------------------------
# Audit event schema contracts (both event kinds)
# ---------------------------------------------------------------------------


async def test_probe_audit_event_schemas(
    storage: SqliteStorage,
    probe: TransientTokenStartupProbe,
    token_store: TokenStore,
) -> None:
    """Per-event-kind contract test for both ``auth.orphan.detected``
    and ``auth.double_presence.detected`` audit rows. Mirrors the
    contract-test discipline established at M5-record-api-call
    commit 4 and the slice 1b commit 5 GC contract test:

    - Event-kind string matches the spec constant.
    - Actor is ``"system"`` per daemon-internal-event convention.
    - Target is ``None`` (no per-resource binding).
    - Success is true.
    - Details payload field-set + types match the spec.

    A regression in field naming or type would break downstream
    audit-log consumers (operator queries, future probes); locking
    the wire shape at the test layer surfaces such drift immediately.
    """
    orphan_id = "1111111111111111"
    _seed_keyring_orphan(orphan_id)
    issued = await token_store.issue(
        agent_id=None, role="implementer", label="double-presence"
    )
    cross_id = issued.record.token_id
    await _insert_transient_row(storage, cross_id)

    await probe.run()

    orphan_audits = await _fetch_audit_rows(
        storage, ORPHAN_DETECTED_AUDIT_ACTION
    )
    assert len(orphan_audits) == 1
    orphan_audit = orphan_audits[0]
    assert orphan_audit["action"] == "auth.orphan.detected"
    assert orphan_audit["actor"] == "system"
    assert orphan_audit["target"] is None
    assert orphan_audit["success"] == 1
    orphan_details = json.loads(orphan_audit["details_json"])
    assert set(orphan_details.keys()) == {
        "source",
        "token_id_hash",
        "observation_only",
    }
    assert orphan_details["source"] == "keyring"
    assert isinstance(orphan_details["token_id_hash"], str)
    assert (
        len(orphan_details["token_id_hash"]) == 64
    )  # sha256 hex length
    assert orphan_details["observation_only"] is True

    dp_audits = await _fetch_audit_rows(
        storage, DOUBLE_PRESENCE_DETECTED_AUDIT_ACTION
    )
    assert len(dp_audits) == 1
    dp_audit = dp_audits[0]
    assert dp_audit["action"] == "auth.double_presence.detected"
    assert dp_audit["actor"] == "system"
    assert dp_audit["target"] is None
    assert dp_audit["success"] == 1
    dp_details = json.loads(dp_audit["details_json"])
    assert set(dp_details.keys()) == {
        "token_id_hash",
        "observation_only",
    }
    assert isinstance(dp_details["token_id_hash"], str)
    assert len(dp_details["token_id_hash"]) == 64
    assert dp_details["observation_only"] is True


# ---------------------------------------------------------------------------
# Pure-observability invariants
# ---------------------------------------------------------------------------


async def test_probe_does_not_mutate_keyring(
    probe: TransientTokenStartupProbe,
    token_store: TokenStore,
    keyring_memory: object,
) -> None:
    """The probe must not mutate any keyring state. Per M1=(c)
    ratification: pure observability, no destructive action. A future
    commit that flips this contract (e.g., accidentally writing to
    ``_index`` during the scan) would break the slice-1c boundary —
    destructive cleanup must require a separate, gated commit."""
    # Seed an indexed token and an orphan so both branches of the
    # probe exercise their read paths.
    issued = await token_store.issue(
        agent_id=None, role="pm", label="static-seed"
    )
    _seed_keyring_orphan("2222222222222222")

    # Snapshot the keyring memory backend's full state pre-probe.
    snapshot_before = dict(
        keyring_memory._store  # type: ignore[attr-defined]
    )

    await probe.run()

    snapshot_after = dict(
        keyring_memory._store  # type: ignore[attr-defined]
    )
    assert snapshot_after == snapshot_before, (
        "probe mutated keyring state — pure-observability contract "
        "violated. Slice 1c (gated) is the destructive-cleanup boundary; "
        "slice 1b commit 6's probe must be read-only."
    )

    # Sanity: the indexed token's ``_index`` blob is still parseable
    # and contains the issued token_id.
    raw = keyring.get_password(_TEST_SERVICE, INDEX_USERNAME)
    assert raw is not None
    index_data = json.loads(raw)
    assert issued.record.token_id in index_data["token_ids"]


async def test_probe_does_not_mutate_sqlite(
    storage: SqliteStorage, probe: TransientTokenStartupProbe
) -> None:
    """The probe must not mutate ``transient_tokens`` row state. Per
    M1=(c) ratification: pure observability. A future commit that
    flipped this contract (e.g., DELETE-ing detected double-presence
    rows) would conflict with the slice-1c gating + collapse two
    distinct slices' acceptance contracts."""
    # Seed two rows: one that will be a double-presence with a
    # keyring entry, and one that's SQLite-only.
    keyring.set_password(
        _TEST_SERVICE,
        _record_key("3333333333333333"),
        json.dumps({"token_id": "3333333333333333"}),
    )
    # Make the keyring entry indexed so it counts as double-presence
    # (orphan would NOT mutate either; double-presence is the more
    # rigorous case — the keyring side IS known and the SQLite side
    # is the candidate for accidental deletion).
    keyring.set_password(
        _TEST_SERVICE,
        INDEX_USERNAME,
        json.dumps({"token_ids": ["3333333333333333"]}),
    )
    await _insert_transient_row(storage, "3333333333333333")
    await _insert_transient_row(storage, "4444444444444444")

    rows_before = await storage.fetch_all(
        "SELECT token_id FROM transient_tokens ORDER BY token_id"
    )
    ids_before = {r["token_id"] for r in rows_before}
    assert ids_before == {"3333333333333333", "4444444444444444"}

    await probe.run()

    rows_after = await storage.fetch_all(
        "SELECT token_id FROM transient_tokens ORDER BY token_id"
    )
    ids_after = {r["token_id"] for r in rows_after}
    assert ids_after == ids_before, (
        "probe mutated transient_tokens — pure-observability contract "
        "violated. Probe must NOT DELETE or modify rows; slice 1c "
        "(gated) is the destructive-cleanup boundary."
    )
