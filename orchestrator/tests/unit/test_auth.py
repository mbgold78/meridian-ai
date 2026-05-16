"""Unit tests for the keyring-backed token store."""

from __future__ import annotations

import json

import pytest

from meridian_orchestrator.auth import (
    INDEX_USERNAME,
    TOKEN_PREFIX,
    TokenStore,
    _hash_secret,
    _parse_token,
    _record_key,
)


def test_issue_returns_token_and_metadata() -> None:
    store = TokenStore(service="test-service")
    issued = store.issue(agent_id=None, role="pm", label="mike-cd")

    assert issued.token.startswith(f"{TOKEN_PREFIX}_")
    assert issued.token.count("_") >= 2
    assert issued.record.role == "pm"
    assert issued.record.label == "mike-cd"
    assert issued.record.agent_id  # auto-generated UUID
    assert issued.record.revoked_at is None
    # Secret is hashed in storage; the plaintext appears only in the token string.
    parts = issued.token.split("_", 2)
    assert _hash_secret(parts[2]) == issued.record.secret_hash


def test_lookup_round_trips_and_returns_record() -> None:
    store = TokenStore(service="test-service")
    issued = store.issue(agent_id="agent-uuid-1", role="engineer", label="")
    rec = store.lookup(issued.token)
    assert rec is not None
    assert rec.agent_id == "agent-uuid-1"
    assert rec.role == "engineer"


def test_lookup_rejects_unknown_token() -> None:
    store = TokenStore(service="test-service")
    assert store.lookup("mor_abcdef0123456789_notarealsecret") is None


def test_lookup_rejects_malformed_tokens() -> None:
    store = TokenStore(service="test-service")
    for bad in [
        "",
        "not-a-token",
        "wrongprefix_abcdef0123456789_secret",
        "mor__secret",
        "mor_NONHEX_secret",
        "mor_abcdef0123456789_",
    ]:
        assert store.lookup(bad) is None, f"should reject {bad!r}"


def test_lookup_rejects_tampered_secret() -> None:
    store = TokenStore(service="test-service")
    issued = store.issue(agent_id=None, role="pm", label="")
    parts = issued.token.split("_", 2)
    tampered = f"{parts[0]}_{parts[1]}_zzz{parts[2][3:]}"
    assert store.lookup(tampered) is None


def test_revoke_marks_record_and_blocks_lookup() -> None:
    store = TokenStore(service="test-service")
    issued = store.issue(agent_id=None, role="pm", label="")
    assert store.revoke(issued.record.token_id) is True
    assert store.lookup(issued.token) is None
    # Second revoke on the same id is a no-op.
    assert store.revoke(issued.record.token_id) is False


def test_revoke_unknown_id_returns_false() -> None:
    store = TokenStore(service="test-service")
    assert store.revoke("0000000000000000") is False


def test_list_records_returns_active_and_revoked() -> None:
    store = TokenStore(service="test-service")
    a = store.issue(agent_id=None, role="pm", label="a")
    store.issue(agent_id=None, role="engineer", label="b")
    store.revoke(a.record.token_id)

    records = store.list_records()
    assert len(records) == 2
    by_role = {r.role: r for r in records}
    assert by_role["pm"].revoked_at is not None
    assert by_role["engineer"].revoked_at is None


def test_persisted_secret_is_only_hash(keyring_memory: object) -> None:
    """The plaintext secret never lives in keyring storage."""
    store = TokenStore(service="probe")
    issued = store.issue(agent_id=None, role="pm", label="")
    parts = issued.token.split("_", 2)
    secret = parts[2]
    for stored in keyring_memory._store.values():  # type: ignore[attr-defined]
        assert secret not in stored


def test_issue_requires_role() -> None:
    store = TokenStore(service="test-service")
    with pytest.raises(ValueError, match="role"):
        store.issue(agent_id=None, role="", label="x")


def test_find_oauth_caller_by_client_id_returns_none_when_no_match() -> None:
    """No prior OAuth records for (client_id, role) → returns None;
    caller mints fresh agent_id at issuance time."""
    store = TokenStore(service="test-find-oauth-empty")
    assert store.find_oauth_caller_by_client_id("missing-cid", "pm") is None

    # Static-only record exists but doesn't match — alpha persistence is
    # OAuth-only by design (static tokens have stable agent_ids via
    # different M2/M3 contract path).
    store.issue(agent_id=None, role="pm", label="static-pm")
    assert store.find_oauth_caller_by_client_id("missing-cid", "pm") is None


def test_find_oauth_caller_by_client_id_returns_agent_id_for_match() -> None:
    """One OAuth record for (client_id, role) → returns its agent_id."""
    store = TokenStore(service="test-find-oauth-match")
    issued = store.issue(
        agent_id=None,
        role="pm",
        label="oauth:claude",
        source="oauth",
        client_id="cid-mike-laptop",
    )
    found = store.find_oauth_caller_by_client_id("cid-mike-laptop", "pm")
    assert found == issued.record.agent_id


def test_find_oauth_caller_by_client_id_returns_most_recent_for_alpha_persistence(
) -> None:
    """Multiple OAuth records for same (client_id, role) → returns the
    most-recent agent_id (by issued_at). Alpha-persistence reuse path:
    re-authorization flows return the same agent_id minted at first
    issuance via ``store.issue(agent_id=existing_match)``.

    Active vs. revoked is not the discriminator — revoked tokens are
    still keyed by their original agent_id, and alpha persistence treats
    revocation as orthogonal to identity continuity. Most-recent
    issuance is the discriminator.
    """
    store = TokenStore(service="test-find-oauth-recent")
    first = store.issue(
        agent_id=None,
        role="pm",
        label="oauth:first",
        source="oauth",
        client_id="cid-stable",
    )
    # Re-issue with same agent_id (simulating alpha persistence reuse
    # by the OAuth provider's exchange_authorization_code path).
    second = store.issue(
        agent_id=first.record.agent_id,
        role="pm",
        label="oauth:second",
        source="oauth",
        client_id="cid-stable",
    )
    assert second.record.agent_id == first.record.agent_id

    # Helper returns the agent_id (same in both records since alpha
    # persistence preserved it).
    found = store.find_oauth_caller_by_client_id("cid-stable", "pm")
    assert found == first.record.agent_id

    # Different role for same client_id → no match (role-scoped).
    assert (
        store.find_oauth_caller_by_client_id("cid-stable", "engineer") is None
    )


def test_parse_token_returns_components() -> None:
    parsed = _parse_token("mor_abcdef0123456789_thesecret")
    assert parsed == ("abcdef0123456789", "thesecret")


# ---- β-S11-5 / β-S11-6 fix: indexed kwarg + unindexed transient tokens ----
#
# Root cause of the boot-blocking bug: TokenStore._write_index serialized the
# full list of every token_id ever issued (active + revoked) to a single
# keyring entry on every issue() call. Windows Credential Manager's per-
# credential blob limit (~2560 bytes) is exceeded once a few hundred token
# cycles accumulate, after which CredWrite returns WinError 1783 and the
# daemon cannot boot because pm_supervisor.start() → _spawn() → token_issue
# → _write_index fails.
#
# Fix (per operator disposition): add `indexed: bool = True` to issue(); pass
# `indexed=False` for high-churn transient credentials (per-dispatch +
# per-spawn supervisor tokens) so the index doesn't grow unbounded. lookup
# and revoke continue to work because they read the per-token entry at
# token:<id> directly without consulting _index. list_records intentionally
# returns only indexed tokens (visibility tradeoff documented in
# scripts/list-tokens.py prose).
#
# Rollback semantics: when indexed=True and the index-update sequence fails
# AFTER token:<id> was written, the orphan token record is deleted before
# re-raising so list_records remains the truthful enumeration of indexed
# tokens.


def test_issue_indexed_true_writes_both_record_and_index(
    keyring_memory: object,
) -> None:
    """Default behavior (indexed=True): writes the per-token record AND
    appends the token_id to _index. Preserves prior shape for static/OAuth
    long-lived credentials."""
    store = TokenStore(service="test-indexed-true")
    issued = store.issue(agent_id=None, role="pm", label="static-pm")

    # Per-token record present
    record_raw = keyring_memory._store.get(  # type: ignore[attr-defined]
        ("test-indexed-true", _record_key(issued.record.token_id))
    )
    assert record_raw is not None

    # Index contains the token_id
    index_raw = keyring_memory._store.get(  # type: ignore[attr-defined]
        ("test-indexed-true", INDEX_USERNAME)
    )
    assert index_raw is not None
    assert issued.record.token_id in json.loads(index_raw)["token_ids"]


def test_issue_indexed_false_writes_record_but_not_index(
    keyring_memory: object,
) -> None:
    """indexed=False writes the per-token record but does NOT touch _index.
    This is the high-churn-transient-token path used by per-dispatch and
    per-spawn supervisor tokens to prevent unbounded keyring blob growth."""
    store = TokenStore(service="test-indexed-false")
    issued = store.issue(
        agent_id=None,
        role="implementer",
        label="dispatch:abc123",
        indexed=False,
    )

    # Per-token record present (token still works for auth)
    record_raw = keyring_memory._store.get(  # type: ignore[attr-defined]
        ("test-indexed-false", _record_key(issued.record.token_id))
    )
    assert record_raw is not None

    # Index entry was never created — verifies _write_index never fired
    # for this issuance, which is the load-bearing property: even at
    # arbitrary issuance rates the index blob stays at zero bytes for
    # services that only issue unindexed tokens.
    assert (
        keyring_memory._store.get(  # type: ignore[attr-defined]
            ("test-indexed-false", INDEX_USERNAME)
        )
        is None
    )


def test_lookup_works_for_unindexed_tokens() -> None:
    """lookup() reads token:<id> directly and verifies the secret hash
    constant-time. It does NOT consult _index, so unindexed tokens
    authenticate identically to indexed ones."""
    store = TokenStore(service="test-lookup-unindexed")
    issued = store.issue(
        agent_id=None,
        role="implementer",
        label="dispatch:xyz789",
        indexed=False,
    )

    rec = store.lookup(issued.token)
    assert rec is not None
    assert rec.role == "implementer"
    assert rec.label == "dispatch:xyz789"
    assert rec.token_id == issued.record.token_id


def test_revoke_works_for_unindexed_tokens() -> None:
    """revoke() reads/writes token:<id> directly. Works for unindexed
    tokens because the per-token record carries the revoked_at field;
    _index is not consulted on the revocation path."""
    store = TokenStore(service="test-revoke-unindexed")
    issued = store.issue(
        agent_id=None,
        role="tester",
        label="dispatch:def456",
        indexed=False,
    )

    # First revoke succeeds (record exists + was not previously revoked)
    assert store.revoke(issued.record.token_id) is True
    # lookup now returns None (revoked_at is set)
    assert store.lookup(issued.token) is None
    # Idempotency: second revoke is a no-op
    assert store.revoke(issued.record.token_id) is False


def test_list_records_excludes_unindexed_tokens(
    keyring_memory: object,
) -> None:
    """list_records iterates _index and reads each token:<id>. Unindexed
    tokens are intentionally absent from _index so they don't appear here.

    Visibility tradeoff: per-dispatch + per-spawn supervisor tokens
    authenticate normally but are NOT operator-listable via the CLI.
    Their lifecycle is recorded in audit_log (dispatch.token_created /
    dispatch.token_revoked) and the dispatches table token_id column."""
    store = TokenStore(service="test-list-mixed")
    indexed_token = store.issue(
        agent_id=None, role="pm", label="static-pm"
    )
    unindexed_token = store.issue(
        agent_id=None,
        role="implementer",
        label="dispatch:hidden",
        indexed=False,
    )

    records = store.list_records()
    assert len(records) == 1
    assert records[0].token_id == indexed_token.record.token_id

    # The unindexed token still EXISTS in keyring at token:<id>...
    assert (
        keyring_memory._store.get(  # type: ignore[attr-defined]
            ("test-list-mixed", _record_key(unindexed_token.record.token_id))
        )
        is not None
    )
    # ...and still authenticates via lookup...
    assert store.lookup(unindexed_token.token) is not None
    # ...but list_records intentionally does not surface it.


def test_indexed_write_failure_rolls_back_token_record(
    monkeypatch: pytest.MonkeyPatch, keyring_memory: object
) -> None:
    """If _write_index fails AFTER token:<id> has been written, the
    just-written per-token record is deleted before re-raising. Without
    rollback, the keyring would be left with an orphan record that
    list_records can never surface (since _write_index failed, the id
    never entered the index) — which would mean every list_records call
    permanently undercounts and re-issues against the same token_id
    could return mismatched metadata.

    This simulates the exact β-S11-5 / β-S11-6 production failure shape:
    Windows CredentialBlob size limit causing CredWrite to return
    WinError 1783 on _write_index even though _write_record succeeded
    for the smaller per-token blob."""
    store = TokenStore(service="test-rollback")

    def failing_write_index(token_ids: list[str]) -> None:
        # Simulates the boot-blocking failure mode observed in production:
        # token:<id> blob writes succeed (smaller), index blob exceeds
        # limit and CredWrite raises WinError 1783.
        raise OSError(1783, "CredWrite", "The stub received bad data")

    monkeypatch.setattr(store, "_write_index", failing_write_index)

    with pytest.raises(OSError):
        store.issue(agent_id=None, role="pm", label="static-pm")

    # After rollback, no token:* record should remain for this service.
    # We don't know the failed token_id (issue raised before returning),
    # so verify the absence by scanning the in-memory keyring for any
    # entry whose username starts with "token:".
    orphan_entries = [
        (svc, user)
        for (svc, user) in keyring_memory._store  # type: ignore[attr-defined]
        if svc == "test-rollback" and user.startswith("token:")
    ]
    assert orphan_entries == [], (
        f"rollback should have deleted orphaned token record(s); "
        f"found: {orphan_entries}"
    )


def test_indexed_write_failure_does_not_mask_original_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """If the cleanup delete itself raises, the original _write_index
    failure must still propagate to the caller. Cleanup is best-effort;
    silently masking the real error would hide root cause."""
    import keyring as keyring_module

    store = TokenStore(service="test-cleanup-also-fails")

    def failing_write_index(token_ids: list[str]) -> None:
        raise OSError(1783, "CredWrite", "The stub received bad data")

    def failing_delete(service: str, username: str) -> None:
        raise RuntimeError("simulated cleanup-delete failure")

    monkeypatch.setattr(store, "_write_index", failing_write_index)
    monkeypatch.setattr(keyring_module, "delete_password", failing_delete)

    # The OSError from _write_index propagates, NOT the RuntimeError
    # from the failing cleanup. Root cause is preserved.
    with pytest.raises(OSError, match="The stub received bad data"):
        store.issue(agent_id=None, role="pm", label="static-pm")


def test_indexed_false_skips_rollback_path_entirely(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """indexed=False never calls _write_index, so even if _write_index
    would fail, an indexed=False issuance succeeds. Verifies the
    high-churn-transient-token path is fully isolated from index
    failures — load-bearing for daemon boot reliability when the index
    is already at/over the CredentialBlob limit from prior cycles."""
    store = TokenStore(service="test-unindexed-skips-rollback")

    def always_failing_write_index(token_ids: list[str]) -> None:
        # Would always fail if called — but indexed=False shouldn't call it.
        raise OSError(1783, "CredWrite", "The stub received bad data")

    monkeypatch.setattr(
        store, "_write_index", always_failing_write_index
    )

    # No exception — _write_index is never invoked for indexed=False.
    issued = store.issue(
        agent_id=None,
        role="implementer",
        label="dispatch:safe",
        indexed=False,
    )
    assert issued.record.role == "implementer"
    # Token still authenticates via lookup.
    assert store.lookup(issued.token) is not None
