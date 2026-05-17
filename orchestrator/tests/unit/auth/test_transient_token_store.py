"""Unit tests for the SQLite-backed TransientTokenStore.

Storage-isolation tests per M5-sqlite-transient-token-store slice 1a
commit 1. The routing-layer composition with :class:`auth.TokenStore`
lands at commit 2; these tests exercise the store in isolation against
a real :class:`SqliteStorage` instance.

Test surface mirrors the spec § "Unit tests
``tests/unit/auth/test_transient_token_store.py``" inventory at
``docs/specs/M5-sqlite-transient-token-store-slice-1a-implementer.md``
lines 121-132.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from pathlib import Path

import pytest_asyncio

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


async def test_migration_0015_applies_cleanly_to_fresh_db(
    tmp_path: Path,
) -> None:
    """Migration 0015 ships the transient_tokens table + two indexes.
    Greenfield: run all migrations on a fresh DB and assert post-state.
    Mirrors the 0014 migration test pattern in
    tests/integration/test_canonical_clones_migration.py."""
    s = SqliteStorage(tmp_path / "fresh.db")
    await s.connect()
    try:
        await run_migrations(s)
        # Table exists.
        row = await s.fetch_one(
            "SELECT name FROM sqlite_master "
            "WHERE type='table' AND name='transient_tokens'"
        )
        assert row is not None
        assert row["name"] == "transient_tokens"

        # Column inventory matches the migration DDL.
        cols = await s.fetch_all("PRAGMA table_info(transient_tokens)")
        by_name = {c["name"]: c for c in cols}
        expected_names = {
            "token_id",
            "agent_id",
            "role",
            "label",
            "secret_hash",
            "issued_at",
            "revoked_at",
            "ttl_seconds",
            "expires_at",
        }
        assert set(by_name.keys()) == expected_names, (
            f"transient_tokens columns drift: got {set(by_name.keys())!r}, "
            f"expected {expected_names!r}."
        )

        assert by_name["token_id"]["pk"] == 1
        assert by_name["agent_id"]["notnull"] == 1
        assert by_name["role"]["notnull"] == 1
        assert by_name["label"]["notnull"] == 1
        assert by_name["secret_hash"]["notnull"] == 1
        assert by_name["issued_at"]["notnull"] == 1
        assert by_name["revoked_at"]["notnull"] == 0
        assert by_name["ttl_seconds"]["notnull"] == 1
        assert by_name["expires_at"]["notnull"] == 1

        # Both indexes from the migration are present.
        index_rows = await s.fetch_all(
            "SELECT name FROM sqlite_master "
            "WHERE type='index' AND tbl_name='transient_tokens' "
            "AND name NOT LIKE 'sqlite_autoindex%'"
        )
        index_names = {r["name"] for r in index_rows}
        assert index_names == {
            "transient_tokens_expires_at_idx",
            "transient_tokens_agent_id_idx",
        }

        # _schema_version contains version 15.
        versions = await s.fetch_all(
            "SELECT version FROM _schema_version ORDER BY version"
        )
        assert 15 in {r["version"] for r in versions}
    finally:
        await s.close()


async def test_issue_writes_row_to_sqlite_and_hardcodes_static_source(
    storage: SqliteStorage,
    store: TransientTokenStore,
) -> None:
    """``issue`` writes the row to SQLite (verify via direct
    ``SELECT * FROM transient_tokens``); returned ``IssuedToken.record.source ==
    "static"`` (D1=(a) verification — see transient_tokens module docstring)."""
    issued = await store.issue(
        agent_id="agent-1",
        role="implementer",
        label="dispatch:abc123",
    )

    # Returned record carries the hardcoded 'static' source.
    assert issued.record.source == "static"
    assert issued.record.client_id is None
    assert issued.record.agent_id == "agent-1"
    assert issued.record.role == "implementer"
    assert issued.record.label == "dispatch:abc123"
    assert issued.record.revoked_at is None
    assert issued.token.startswith("mor_")

    # Row exists in SQLite with the matching token_id.
    row = await storage.fetch_one(
        "SELECT * FROM transient_tokens WHERE token_id = ?",
        (issued.record.token_id,),
    )
    assert row is not None
    assert row["agent_id"] == "agent-1"
    assert row["role"] == "implementer"
    assert row["label"] == "dispatch:abc123"
    assert row["secret_hash"] == issued.record.secret_hash
    assert row["revoked_at"] is None
    assert row["ttl_seconds"] == 86400
    assert row["expires_at"] == issued.record.expires_at


async def test_lookup_by_token_id_returns_record_for_active_token(
    store: TransientTokenStore,
) -> None:
    """``lookup_by_token_id`` returns the record verbatim for active tokens."""
    issued = await store.issue(
        agent_id="agent-2", role="tester", label="dispatch:xyz"
    )
    record = await store.lookup_by_token_id(issued.record.token_id)
    assert record is not None
    assert record.token_id == issued.record.token_id
    assert record.agent_id == "agent-2"
    assert record.role == "tester"
    assert record.label == "dispatch:xyz"
    assert record.revoked_at is None
    assert record.source == "static"


async def test_lookup_by_token_id_returns_record_even_when_revoked(
    store: TransientTokenStore,
) -> None:
    """``lookup_by_token_id`` returns the record EVEN WHEN REVOKED.
    Load-bearing contract: the dispatch self-access pattern
    (Implementer reads its own ``get_dispatch_result`` after the
    dispatcher's terminal-state cleanup has revoked the per-dispatch
    token) depends on revoked records being returned, not filtered."""
    issued = await store.issue(
        agent_id=None, role="implementer", label="dispatch:revoked"
    )
    assert await store.revoke(issued.record.token_id) is True

    record = await store.lookup_by_token_id(issued.record.token_id)
    assert record is not None
    assert record.token_id == issued.record.token_id
    assert record.revoked_at is not None


async def test_lookup_by_token_id_returns_none_for_unknown_token_id(
    store: TransientTokenStore,
) -> None:
    """``lookup_by_token_id`` returns ``None`` for unknown ``token_id``."""
    assert await store.lookup_by_token_id("0000000000000000") is None


async def test_lookup_returns_record_for_active_token(
    store: TransientTokenStore,
) -> None:
    """``lookup(token)`` returns the record for active tokens with a valid
    secret. Constant-time secret comparison via ``hmac.compare_digest``."""
    issued = await store.issue(
        agent_id="agent-lookup", role="implementer", label="dispatch:active"
    )
    record = await store.lookup(issued.token)
    assert record is not None
    assert record.token_id == issued.record.token_id
    assert record.agent_id == "agent-lookup"


async def test_lookup_returns_none_for_revoked_token(
    store: TransientTokenStore,
) -> None:
    """``lookup(token)`` returns ``None`` when the record has
    ``revoked_at`` set. Distinct from ``lookup_by_token_id`` which returns
    revoked records (load-bearing for self-access)."""
    issued = await store.issue(
        agent_id=None, role="implementer", label="dispatch:revoked-lookup"
    )
    assert await store.revoke(issued.record.token_id) is True
    assert await store.lookup(issued.token) is None


async def test_lookup_returns_none_for_bad_secret(
    store: TransientTokenStore,
) -> None:
    """``lookup(token)`` returns ``None`` when the secret in the token
    string does not match the stored ``secret_hash``. Pins the
    constant-time comparison branch."""
    issued = await store.issue(
        agent_id=None, role="implementer", label="dispatch:bad-secret"
    )
    # Tamper the secret portion of mor_<id>_<secret>.
    parts = issued.token.split("_", 2)
    tampered = f"{parts[0]}_{parts[1]}_zzz{parts[2][3:]}"
    assert await store.lookup(tampered) is None


async def test_lookup_returns_none_for_expired_token(
    storage: SqliteStorage,
    store: TransientTokenStore,
) -> None:
    """``lookup(token)`` returns ``None`` once ``expires_at < now()``.
    Pins the TTL/expiry time-comparison branch. Forces expiry by directly
    overwriting ``expires_at`` to a past timestamp — sidesteps wall-clock
    dependency and timing flakiness."""
    issued = await store.issue(
        agent_id=None, role="implementer", label="dispatch:will-expire"
    )
    # Active first.
    assert await store.lookup(issued.token) is not None

    # Overwrite expires_at to a deep-past timestamp.
    past = "2020-01-01T00:00:00+00:00"
    await storage.execute(
        "UPDATE transient_tokens SET expires_at = ? WHERE token_id = ?",
        (past, issued.record.token_id),
    )

    assert await store.lookup(issued.token) is None


async def test_revoke_flips_revoked_at_and_short_circuits_on_second_call(
    storage: SqliteStorage,
    store: TransientTokenStore,
) -> None:
    """``revoke`` flips ``revoked_at`` from NULL → ISO timestamp; the
    second ``revoke`` on the same id returns ``False`` (matches the
    ``TokenStore.revoke`` short-circuit at ``auth.py:404``)."""
    issued = await store.issue(
        agent_id=None, role="implementer", label="dispatch:revoke"
    )

    # Pre-state: revoked_at is NULL.
    row = await storage.fetch_one(
        "SELECT revoked_at FROM transient_tokens WHERE token_id = ?",
        (issued.record.token_id,),
    )
    assert row is not None
    assert row["revoked_at"] is None

    # First revoke flips it.
    assert await store.revoke(issued.record.token_id) is True
    row = await storage.fetch_one(
        "SELECT revoked_at FROM transient_tokens WHERE token_id = ?",
        (issued.record.token_id,),
    )
    assert row is not None
    assert row["revoked_at"] is not None

    # Second revoke is a no-op short-circuit.
    assert await store.revoke(issued.record.token_id) is False


async def test_revoke_returns_false_for_unknown_token_id(
    store: TransientTokenStore,
) -> None:
    """``revoke`` returns ``False`` for an unknown ``token_id`` without
    raising."""
    assert await store.revoke("0000000000000000") is False


async def test_delete_hard_removes_row_and_lookup_by_token_id_returns_none(
    storage: SqliteStorage,
    store: TransientTokenStore,
) -> None:
    """``delete`` hard-removes the row; subsequent ``lookup_by_token_id``
    returns ``None``. Test-cleanup-only semantics."""
    issued = await store.issue(
        agent_id=None, role="implementer", label="dispatch:delete"
    )
    assert await store.lookup_by_token_id(issued.record.token_id) is not None

    assert await store.delete(issued.record.token_id) is True

    # Row gone from SQLite.
    row = await storage.fetch_one(
        "SELECT token_id FROM transient_tokens WHERE token_id = ?",
        (issued.record.token_id,),
    )
    assert row is None

    # lookup_by_token_id reports None for the now-removed id.
    assert await store.lookup_by_token_id(issued.record.token_id) is None

    # A second delete reports False (nothing to remove).
    assert await store.delete(issued.record.token_id) is False
