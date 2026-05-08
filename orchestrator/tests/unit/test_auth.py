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


def test_parse_token_returns_components() -> None:
    parsed = _parse_token("mor_abcdef0123456789_thesecret")
    assert parsed == ("abcdef0123456789", "thesecret")
