"""Unit tests for TokenStore's R1=(b) try-SQLite-first-then-keyring
routing — slice 1a commit 2.

Coverage:
- ``TokenStore(transient_store=None)``: keyring-only behavior preserved
  across all five routed methods (regression guard for the ~800+ test
  sites and production call sites that haven't opted into the SQLite
  backend).
- ``TokenStore(transient_store=<real>)``: indexed=True still goes to
  keyring; indexed=False routes to SQLite. ``lookup``,
  ``lookup_by_token_id``, ``revoke`` resolve tokens from either
  backend. ``lookup_by_token_id`` preserves the revoked-record-returned
  contract for both backends. ``list_records()`` continues to surface
  keyring-stored only (transient invisibility per parent spec line 80).
- D2=(a) ``delete`` cross-backend semantics: static keyring delete works
  when ``transient_store`` is wired; transient delete does NOT touch
  keyring ``_index``.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from pathlib import Path

import keyring
import pytest_asyncio

from meridian_orchestrator.auth import (
    INDEX_USERNAME,
    TokenStore,
    _record_key,
)
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.transient_tokens import TransientTokenStore


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "test.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


@pytest_asyncio.fixture
async def transient_store(
    storage: SqliteStorage,
) -> TransientTokenStore:
    return TransientTokenStore(storage)


# ---------------------------------------------------------------------------
# transient_store=None — keyring-only regression guard
# ---------------------------------------------------------------------------


async def test_transient_store_none_keeps_keyring_only_issue_and_lookup() -> None:
    """``TokenStore(transient_store=None)``: ``issue(indexed=False)``
    falls through to the keyring path; ``lookup`` resolves via keyring.
    Regression guard for the 800+ test sites that construct
    ``TokenStore()`` without the new arg."""
    store = TokenStore(service="routing-none-issue")
    issued = await store.issue(
        agent_id=None,
        role="implementer",
        label="dispatch:no-transient",
        indexed=False,
    )
    # Keyring carries the per-token record.
    assert keyring.get_password(
        "routing-none-issue", _record_key(issued.record.token_id)
    ) is not None
    # lookup resolves via keyring fallback.
    rec = await store.lookup(issued.token)
    assert rec is not None
    assert rec.token_id == issued.record.token_id


async def test_transient_store_none_keeps_keyring_only_revoke_and_lookup_by_token_id(
) -> None:
    """Revoke + lookup_by_token_id resolve via keyring when no transient
    backend is wired. Preserves the revoked-record-returned contract."""
    store = TokenStore(service="routing-none-revoke")
    issued = await store.issue(
        agent_id=None, role="implementer", label="dispatch:revoke",
        indexed=False,
    )
    assert await store.revoke(issued.record.token_id) is True

    rec = await store.lookup_by_token_id(issued.record.token_id)
    assert rec is not None
    assert rec.revoked_at is not None  # revoked-record-returned contract


async def test_transient_store_none_keeps_keyring_only_delete() -> None:
    """``delete`` falls through to the keyring-only path when no
    transient backend is configured. Existing behavior preserved."""
    store = TokenStore(service="routing-none-delete")
    issued = await store.issue(
        agent_id=None, role="pm", label="static-pm"
    )
    assert await store.delete(issued.record.token_id) is True
    # Keyring entry gone.
    assert keyring.get_password(
        "routing-none-delete", _record_key(issued.record.token_id)
    ) is None


# ---------------------------------------------------------------------------
# transient_store wired — routing semantics
# ---------------------------------------------------------------------------


async def test_issue_indexed_true_writes_keyring_not_sqlite(
    storage: SqliteStorage, transient_store: TransientTokenStore
) -> None:
    """``issue(indexed=True)`` always uses keyring even when
    ``transient_store`` is wired. Verifies no SQLite row was created."""
    store = TokenStore(
        service="routing-indexed-true",
        transient_store=transient_store,
    )
    issued = await store.issue(
        agent_id=None, role="pm", label="static-pm", indexed=True,
    )
    # Keyring carries it.
    assert keyring.get_password(
        "routing-indexed-true", _record_key(issued.record.token_id)
    ) is not None
    # SQLite does NOT.
    row = await storage.fetch_one(
        "SELECT token_id FROM transient_tokens WHERE token_id = ?",
        (issued.record.token_id,),
    )
    assert row is None


async def test_issue_indexed_false_writes_sqlite_not_keyring(
    storage: SqliteStorage, transient_store: TransientTokenStore
) -> None:
    """``issue(indexed=False)`` routes to SQLite when ``transient_store``
    is wired. Verifies no keyring entry was created for the token_id."""
    store = TokenStore(
        service="routing-indexed-false",
        transient_store=transient_store,
    )
    issued = await store.issue(
        agent_id=None, role="implementer", label="dispatch:transient",
        indexed=False,
    )
    # Keyring does NOT carry the token_id.
    assert keyring.get_password(
        "routing-indexed-false", _record_key(issued.record.token_id)
    ) is None
    # SQLite carries it.
    row = await storage.fetch_one(
        "SELECT token_id, role, label FROM transient_tokens "
        "WHERE token_id = ?",
        (issued.record.token_id,),
    )
    assert row is not None
    assert row["role"] == "implementer"
    assert row["label"] == "dispatch:transient"


async def test_lookup_resolves_tokens_from_either_backend(
    transient_store: TransientTokenStore,
) -> None:
    """``lookup(token)`` finds tokens in either backend. Issue one
    indexed (keyring) and one transient (SQLite); lookup both."""
    store = TokenStore(
        service="routing-lookup-mixed",
        transient_store=transient_store,
    )
    indexed = await store.issue(
        agent_id=None, role="pm", label="static-pm",
    )
    transient = await store.issue(
        agent_id=None, role="implementer", label="dispatch:t",
        indexed=False,
    )
    assert (await store.lookup(indexed.token)) is not None
    assert (await store.lookup(transient.token)) is not None


async def test_lookup_by_token_id_resolves_from_either_backend(
    transient_store: TransientTokenStore,
) -> None:
    """``lookup_by_token_id`` resolves token_ids from either backend."""
    store = TokenStore(
        service="routing-lookup-by-id-mixed",
        transient_store=transient_store,
    )
    indexed = await store.issue(agent_id=None, role="pm", label="x")
    transient = await store.issue(
        agent_id=None, role="implementer", label="y", indexed=False,
    )
    assert (
        await store.lookup_by_token_id(indexed.record.token_id)
    ) is not None
    assert (
        await store.lookup_by_token_id(transient.record.token_id)
    ) is not None


async def test_lookup_by_token_id_returns_revoked_records_for_both_backends(
    transient_store: TransientTokenStore,
) -> None:
    """Load-bearing dispatch-self-access contract: revoked tokens still
    resolve via ``lookup_by_token_id`` regardless of which backend
    persisted them. Without this, Implementer's post-revocation
    ``get_dispatch_result`` self-call fails permission validation."""
    store = TokenStore(
        service="routing-revoked-mixed",
        transient_store=transient_store,
    )
    indexed = await store.issue(agent_id=None, role="pm", label="x")
    transient = await store.issue(
        agent_id=None, role="implementer", label="y", indexed=False,
    )
    assert await store.revoke(indexed.record.token_id) is True
    assert await store.revoke(transient.record.token_id) is True

    indexed_rec = await store.lookup_by_token_id(indexed.record.token_id)
    assert indexed_rec is not None
    assert indexed_rec.revoked_at is not None

    transient_rec = await store.lookup_by_token_id(
        transient.record.token_id
    )
    assert transient_rec is not None
    assert transient_rec.revoked_at is not None


async def test_revoke_succeeds_against_either_backend(
    transient_store: TransientTokenStore,
) -> None:
    """``revoke`` flips ``revoked_at`` whether the token lives in
    keyring (indexed=True) or SQLite (indexed=False)."""
    store = TokenStore(
        service="routing-revoke-mixed",
        transient_store=transient_store,
    )
    indexed = await store.issue(agent_id=None, role="pm", label="x")
    transient = await store.issue(
        agent_id=None, role="implementer", label="y", indexed=False,
    )
    assert await store.revoke(indexed.record.token_id) is True
    assert await store.revoke(transient.record.token_id) is True
    # Second revoke on either short-circuits.
    assert await store.revoke(indexed.record.token_id) is False
    assert await store.revoke(transient.record.token_id) is False


async def test_list_records_continues_to_return_keyring_stored_only(
    transient_store: TransientTokenStore,
) -> None:
    """``list_records()`` iterates the keyring ``_index`` — transient
    tokens are intentionally invisible per parent spec line 80. Routing
    layer does not alter this surface."""
    store = TokenStore(
        service="routing-list-records",
        transient_store=transient_store,
    )
    indexed = await store.issue(
        agent_id=None, role="pm", label="visible",
    )
    await store.issue(
        agent_id=None, role="implementer", label="hidden", indexed=False,
    )
    records = store.list_records()
    assert len(records) == 1
    assert records[0].token_id == indexed.record.token_id


# ---------------------------------------------------------------------------
# D2=(a) — delete cross-backend semantics
# ---------------------------------------------------------------------------


async def test_delete_static_keyring_token_works_with_transient_store_set(
    storage: SqliteStorage, transient_store: TransientTokenStore
) -> None:
    """D2=(a) test pin: static keyring delete still works when
    ``transient_store`` is wired. Keyring entry gone; no SQLite row
    ever existed for the token_id; ``delete`` returns True."""
    store = TokenStore(
        service="routing-delete-static",
        transient_store=transient_store,
    )
    issued = await store.issue(
        agent_id=None, role="pm", label="static-pm",
    )
    # Pre-state: keyring entry exists; no SQLite row.
    assert keyring.get_password(
        "routing-delete-static", _record_key(issued.record.token_id)
    ) is not None
    row = await storage.fetch_one(
        "SELECT token_id FROM transient_tokens WHERE token_id = ?",
        (issued.record.token_id,),
    )
    assert row is None

    assert await store.delete(issued.record.token_id) is True

    # Post-state: keyring entry gone; SQLite still has no row.
    assert keyring.get_password(
        "routing-delete-static", _record_key(issued.record.token_id)
    ) is None
    row = await storage.fetch_one(
        "SELECT token_id FROM transient_tokens WHERE token_id = ?",
        (issued.record.token_id,),
    )
    assert row is None


async def test_delete_transient_token_does_not_touch_keyring_index(
    storage: SqliteStorage,
    transient_store: TransientTokenStore,
    keyring_memory: object,
) -> None:
    """D2=(a) test pin: transient delete removes the SQLite row but
    leaves the keyring ``_index`` unchanged. Critical because
    ``list_records()`` iterates the index, and transient tokens were
    never recorded there in the first place — deletion must not touch
    the keyring surface."""
    store = TokenStore(
        service="routing-delete-transient",
        transient_store=transient_store,
    )
    # Seed one indexed token so _index is non-empty pre-test; verifies
    # the transient delete doesn't accidentally rewrite the index.
    indexed = await store.issue(
        agent_id=None, role="pm", label="static-pm",
    )
    transient = await store.issue(
        agent_id=None, role="implementer", label="dispatch:to-delete",
        indexed=False,
    )

    index_before = keyring_memory._store.get(  # type: ignore[attr-defined]
        ("routing-delete-transient", INDEX_USERNAME)
    )
    assert index_before is not None
    assert indexed.record.token_id in index_before

    assert await store.delete(transient.record.token_id) is True

    # SQLite row gone.
    row = await storage.fetch_one(
        "SELECT token_id FROM transient_tokens WHERE token_id = ?",
        (transient.record.token_id,),
    )
    assert row is None

    # Keyring _index unchanged — still has the static token, never had
    # the transient token to begin with.
    index_after = keyring_memory._store.get(  # type: ignore[attr-defined]
        ("routing-delete-transient", INDEX_USERNAME)
    )
    assert index_after == index_before

    # list_records() still surfaces only the static keyring token.
    records = store.list_records()
    assert len(records) == 1
    assert records[0].token_id == indexed.record.token_id


async def test_delete_unknown_token_id_returns_false(
    transient_store: TransientTokenStore,
) -> None:
    """``delete`` of an unknown ``token_id`` returns False without
    raising — neither backend has the row."""
    store = TokenStore(
        service="routing-delete-unknown",
        transient_store=transient_store,
    )
    assert await store.delete("0000000000000000") is False
