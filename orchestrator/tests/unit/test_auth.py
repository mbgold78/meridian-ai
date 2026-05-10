"""Unit tests for the keyring-backed token store."""

from __future__ import annotations

import pytest

from meridian_orchestrator.auth import (
    TOKEN_PREFIX,
    TokenStore,
    _hash_secret,
    _parse_token,
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
