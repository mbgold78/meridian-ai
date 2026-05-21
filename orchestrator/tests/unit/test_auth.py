"""Unit tests for the keyring-backed token store."""

from __future__ import annotations

import json

import pytest

from meridian_orchestrator.auth import (
    INDEX_USERNAME,
    OAUTH_CLIENTS_INDEX_USERNAME,
    TOKEN_PREFIX,
    OAuthClientRecord,
    TokenStore,
    _hash_secret,
    _parse_token,
    _record_key,
)


async def test_issue_returns_token_and_metadata() -> None:
    store = TokenStore(service="test-service")
    issued = await store.issue(agent_id=None, role="pm", label="mike-cd")

    assert issued.token.startswith(f"{TOKEN_PREFIX}_")
    assert issued.token.count("_") >= 2
    assert issued.record.role == "pm"
    assert issued.record.label == "mike-cd"
    assert issued.record.agent_id  # auto-generated UUID
    assert issued.record.revoked_at is None
    # Secret is hashed in storage; the plaintext appears only in the token string.
    parts = issued.token.split("_", 2)
    assert _hash_secret(parts[2]) == issued.record.secret_hash


async def test_lookup_round_trips_and_returns_record() -> None:
    store = TokenStore(service="test-service")
    issued = await store.issue(agent_id="agent-uuid-1", role="engineer", label="")
    rec = await store.lookup(issued.token)
    assert rec is not None
    assert rec.agent_id == "agent-uuid-1"
    assert rec.role == "engineer"


async def test_lookup_rejects_unknown_token() -> None:
    store = TokenStore(service="test-service")
    assert await store.lookup("mor_abcdef0123456789_notarealsecret") is None


async def test_lookup_rejects_malformed_tokens() -> None:
    store = TokenStore(service="test-service")
    for bad in [
        "",
        "not-a-token",
        "wrongprefix_abcdef0123456789_secret",
        "mor__secret",
        "mor_NONHEX_secret",
        "mor_abcdef0123456789_",
    ]:
        assert await store.lookup(bad) is None, f"should reject {bad!r}"


async def test_lookup_rejects_tampered_secret() -> None:
    store = TokenStore(service="test-service")
    issued = await store.issue(agent_id=None, role="pm", label="")
    parts = issued.token.split("_", 2)
    tampered = f"{parts[0]}_{parts[1]}_zzz{parts[2][3:]}"
    assert await store.lookup(tampered) is None


async def test_revoke_marks_record_and_blocks_lookup() -> None:
    store = TokenStore(service="test-service")
    issued = await store.issue(agent_id=None, role="pm", label="")
    assert await store.revoke(issued.record.token_id) is True
    assert await store.lookup(issued.token) is None
    # Second revoke on the same id is a no-op.
    assert await store.revoke(issued.record.token_id) is False


async def test_revoke_unknown_id_returns_false() -> None:
    store = TokenStore(service="test-service")
    assert await store.revoke("0000000000000000") is False


async def test_list_records_returns_active_and_revoked() -> None:
    store = TokenStore(service="test-service")
    a = await store.issue(agent_id=None, role="pm", label="a")
    await store.issue(agent_id=None, role="engineer", label="b")
    await store.revoke(a.record.token_id)

    records = store.list_records()
    assert len(records) == 2
    by_role = {r.role: r for r in records}
    assert by_role["pm"].revoked_at is not None
    assert by_role["engineer"].revoked_at is None


async def test_persisted_secret_is_only_hash(keyring_memory: object) -> None:
    """The plaintext secret never lives in keyring storage."""
    store = TokenStore(service="probe")
    issued = await store.issue(agent_id=None, role="pm", label="")
    parts = issued.token.split("_", 2)
    secret = parts[2]
    for stored in keyring_memory._store.values():  # type: ignore[attr-defined]
        assert secret not in stored


async def test_issue_requires_role() -> None:
    store = TokenStore(service="test-service")
    with pytest.raises(ValueError, match="role"):
        await store.issue(agent_id=None, role="", label="x")


async def test_find_oauth_caller_by_client_id_returns_none_when_no_match() -> None:
    """No prior OAuth records for (client_id, role) → returns None;
    caller mints fresh agent_id at issuance time."""
    store = TokenStore(service="test-find-oauth-empty")
    assert store.find_oauth_caller_by_client_id("missing-cid", "pm") is None

    # Static-only record exists but doesn't match — alpha persistence is
    # OAuth-only by design (static tokens have stable agent_ids via
    # different M2/M3 contract path).
    await store.issue(agent_id=None, role="pm", label="static-pm")
    assert store.find_oauth_caller_by_client_id("missing-cid", "pm") is None


async def test_find_oauth_caller_by_client_id_returns_agent_id_for_match() -> None:
    """One OAuth record for (client_id, role) → returns its agent_id."""
    store = TokenStore(service="test-find-oauth-match")
    issued = await store.issue(
        agent_id=None,
        role="pm",
        label="oauth:claude",
        source="oauth",
        client_id="cid-mike-laptop",
    )
    found = store.find_oauth_caller_by_client_id("cid-mike-laptop", "pm")
    assert found == issued.record.agent_id


async def test_find_oauth_caller_by_client_id_returns_most_recent_for_alpha_persistence(
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
    first = await store.issue(
        agent_id=None,
        role="pm",
        label="oauth:first",
        source="oauth",
        client_id="cid-stable",
    )
    # Re-issue with same agent_id (simulating alpha persistence reuse
    # by the OAuth provider's exchange_authorization_code path).
    second = await store.issue(
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


async def test_issue_indexed_true_writes_both_record_and_index(
    keyring_memory: object,
) -> None:
    """Default behavior (indexed=True): writes the per-token record AND
    appends the token_id to _index. Preserves prior shape for static/OAuth
    long-lived credentials."""
    store = TokenStore(service="test-indexed-true")
    issued = await store.issue(agent_id=None, role="pm", label="static-pm")

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


async def test_issue_indexed_false_writes_record_but_not_index(
    keyring_memory: object,
) -> None:
    """indexed=False writes the per-token record but does NOT touch _index.
    This is the high-churn-transient-token path used by per-dispatch and
    per-spawn supervisor tokens to prevent unbounded keyring blob growth."""
    store = TokenStore(service="test-indexed-false")
    issued = await store.issue(
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


async def test_lookup_works_for_unindexed_tokens() -> None:
    """lookup() reads token:<id> directly and verifies the secret hash
    constant-time. It does NOT consult _index, so unindexed tokens
    authenticate identically to indexed ones."""
    store = TokenStore(service="test-lookup-unindexed")
    issued = await store.issue(
        agent_id=None,
        role="implementer",
        label="dispatch:xyz789",
        indexed=False,
    )

    rec = await store.lookup(issued.token)
    assert rec is not None
    assert rec.role == "implementer"
    assert rec.label == "dispatch:xyz789"
    assert rec.token_id == issued.record.token_id


async def test_revoke_works_for_unindexed_tokens() -> None:
    """revoke() reads/writes token:<id> directly. Works for unindexed
    tokens because the per-token record carries the revoked_at field;
    _index is not consulted on the revocation path."""
    store = TokenStore(service="test-revoke-unindexed")
    issued = await store.issue(
        agent_id=None,
        role="tester",
        label="dispatch:def456",
        indexed=False,
    )

    # First revoke succeeds (record exists + was not previously revoked)
    assert await store.revoke(issued.record.token_id) is True
    # lookup now returns None (revoked_at is set)
    assert await store.lookup(issued.token) is None
    # Idempotency: second revoke is a no-op
    assert await store.revoke(issued.record.token_id) is False


async def test_list_records_excludes_unindexed_tokens(
    keyring_memory: object,
) -> None:
    """list_records iterates _index and reads each token:<id>. Unindexed
    tokens are intentionally absent from _index so they don't appear here.

    Visibility tradeoff: per-dispatch + per-spawn supervisor tokens
    authenticate normally but are NOT operator-listable via the CLI.
    Their lifecycle is recorded in audit_log (dispatch.token_created /
    dispatch.token_revoked) and the dispatches table token_id column."""
    store = TokenStore(service="test-list-mixed")
    indexed_token = await store.issue(
        agent_id=None, role="pm", label="static-pm"
    )
    unindexed_token = await store.issue(
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
    assert await store.lookup(unindexed_token.token) is not None
    # ...but list_records intentionally does not surface it.


async def test_indexed_write_failure_rolls_back_token_record(
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
        await store.issue(agent_id=None, role="pm", label="static-pm")

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


async def test_indexed_write_failure_does_not_mask_original_error(
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
        await store.issue(agent_id=None, role="pm", label="static-pm")


async def test_indexed_false_skips_rollback_path_entirely(
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
    issued = await store.issue(
        agent_id=None,
        role="implementer",
        label="dispatch:safe",
        indexed=False,
    )
    assert issued.record.role == "implementer"
    # Token still authenticates via lookup.
    assert await store.lookup(issued.token) is not None


# ===========================================================================
# β-S11-18: OAuth client index keyring blob overflow — tactical suppression
# ===========================================================================
#
# Production incident 2026-05-21: PM Claude Code MCP /register endpoint
# returned HTTP 500 after daemon restart. Root cause:
# _write_oauth_clients_index() persists the entire JSON list of accumulated
# OAuth client_ids under one keyring credential
# (OAUTH_CLIENTS_INDEX_USERNAME). Every dynamic-client-registration grows
# that blob. Once it exceeds Windows Credential Manager's ~2560-byte
# CredWrite limit, OSError(winerror=1783, 'The stub received bad data')
# propagates through the OAuth /register handler.
#
# Same shape as β-S11-5 transient-tokens index bleed; durable fix is
# tracked in BACKLOG as β-S11-18 SQLite-backed OAuth client registry.
# Hotfix below suppresses the specific overflow error so OAuth
# registration succeeds: the per-client `oauth_client:<id>` keyring
# entry was already written BEFORE the index update, so
# get_oauth_client(client_id) — the only call OAuth token validation
# depends on — still resolves correctly.
# ---------------------------------------------------------------------------


def _oauth_record(client_id: str) -> OAuthClientRecord:
    """Build an OAuthClientRecord for these tests with the minimum
    fields the dataclass / persistence path requires.
    """
    return OAuthClientRecord(
        client_id=client_id,
        client_secret="test-secret",
        client_name="test-client",
        redirect_uris=["http://127.0.0.1:8765/callback"],
        scope="mcp:read mcp:write",
        grant_types=["authorization_code"],
        response_types=["code"],
        token_endpoint_auth_method="client_secret_post",
        registered_at="2026-05-21T00:00:00+00:00",
    )


async def test_register_oauth_client_succeeds_when_index_write_overflows(
    monkeypatch: pytest.MonkeyPatch,
    keyring_memory: object,
) -> None:
    """β-S11-18 hotfix: when _write_oauth_clients_index hits
    OSError(winerror=1783) (Windows Credential Manager blob-overflow),
    register_oauth_client must STILL return successfully so the
    MCP /register handler returns 200 to Claude Code. The per-client
    record at oauth_client:<id> was already written before the index
    write, so get_oauth_client(client_id) MUST resolve the new client.
    """
    import keyring as keyring_module

    store = TokenStore(service="test-oauth-overflow")

    real_set_password = keyring_module.set_password

    def selective_failing_set_password(
        service: str, username: str, password: str
    ) -> None:
        # Fail ONLY on the index write; per-client `oauth_client:<id>`
        # writes still go through to the in-memory keyring.
        if username == OAUTH_CLIENTS_INDEX_USERNAME:
            raise OSError(1783, "CredWrite", "The stub received bad data")
        real_set_password(service, username, password)

    monkeypatch.setattr(
        keyring_module, "set_password", selective_failing_set_password
    )

    record = _oauth_record("test-client-001")
    # Must NOT raise — β-S11-18 suppresses the specific overflow shape.
    store.register_oauth_client(record)

    # The per-client record was written and resolves via get_oauth_client.
    fetched = store.get_oauth_client("test-client-001")
    assert fetched is not None, (
        "β-S11-18: per-client `oauth_client:<id>` record must remain "
        "written and resolvable even when the index write overflows. "
        "OAuth token validation depends on this lookup path."
    )
    assert fetched.client_id == "test-client-001"


async def test_register_oauth_client_propagates_unknown_oserror(
    monkeypatch: pytest.MonkeyPatch,
    keyring_memory: object,
) -> None:
    """β-S11-18 narrow-suppression invariant: an OSError that is NOT
    WinError 1783 (e.g., winerror=5 access-denied; or any non-Windows
    OSError without winerror) MUST propagate. The hotfix only masks
    the specific known-shape blob-overflow failure; broader keyring
    regressions must remain visible.
    """
    import keyring as keyring_module

    store = TokenStore(service="test-oauth-other-oserror")

    real_set_password = keyring_module.set_password

    def index_write_access_denied(
        service: str, username: str, password: str
    ) -> None:
        if username == OAUTH_CLIENTS_INDEX_USERNAME:
            # winerror=5 = ERROR_ACCESS_DENIED. Not the overflow shape.
            raise OSError(5, "CredWrite", "Access is denied")
        real_set_password(service, username, password)

    monkeypatch.setattr(
        keyring_module, "set_password", index_write_access_denied
    )

    record = _oauth_record("test-client-002")
    with pytest.raises(OSError) as exc_info:
        store.register_oauth_client(record)
    # Python's bare OSError(5, ...) sets errno=5 with winerror=None.
    # The suppression invariant is "match 1783 only on either field"; an
    # errno=5 OSError MUST propagate. Pin the errno rather than
    # winerror so this test passes on both production-shape (winerror
    # set by pywin32) AND synthetic-shape (errno set by Python).
    assert exc_info.value.errno == 5, (
        "non-1783 OSError must propagate verbatim — β-S11-18 hotfix "
        "must not mask other auth-store regressions"
    )


async def test_register_oauth_client_per_client_write_failure_not_muted(
    monkeypatch: pytest.MonkeyPatch,
    keyring_memory: object,
) -> None:
    """β-S11-18 narrow-suppression invariant: the per-client
    `oauth_client:<id>` write (which is the OAuth lookup primary key)
    is NOT inside the suppress-on-1783 try/except. If keyring rejects
    the per-client write itself, the OAuth flow MUST fail loudly —
    silently dropping the per-client record would leave a registered
    client that token validation can never resolve.
    """
    import keyring as keyring_module

    store = TokenStore(service="test-oauth-per-client-fail")

    def fail_per_client_writes(
        service: str, username: str, password: str
    ) -> None:
        # Fail on the per-client key. Index writes would also fail
        # but we never reach them.
        if username.startswith("oauth_client:"):
            raise OSError(1783, "CredWrite", "The stub received bad data")

    monkeypatch.setattr(
        keyring_module, "set_password", fail_per_client_writes
    )

    record = _oauth_record("test-client-003")
    with pytest.raises(OSError):
        store.register_oauth_client(record)


async def test_register_oauth_client_logs_overflow_warning(
    monkeypatch: pytest.MonkeyPatch,
    keyring_memory: object,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """β-S11-18: the suppression path emits a structured WARNING with
    the documented event name so operator log-grep + telemetry
    pipelines can detect the degraded enumeration state. structlog is
    configured at module load to write to stderr (not stdlib logging),
    so capsys is the appropriate capture surface here.
    """
    import keyring as keyring_module

    store = TokenStore(service="test-oauth-overflow-warn")

    def index_overflow(
        service: str, username: str, password: str
    ) -> None:
        if username == OAUTH_CLIENTS_INDEX_USERNAME:
            raise OSError(1783, "CredWrite", "The stub received bad data")

    monkeypatch.setattr(
        keyring_module, "set_password", index_overflow
    )

    record = _oauth_record("test-client-004")
    store.register_oauth_client(record)

    captured = capsys.readouterr()
    combined = captured.out + captured.err
    assert "auth.oauth_clients_index_write_overflow" in combined, (
        f"β-S11-18: register_oauth_client must emit a structured "
        f"WARNING `auth.oauth_clients_index_write_overflow` when "
        f"the index write overflows; captured:\n"
        f"--- stdout ---\n{captured.out}\n--- stderr ---\n{captured.err}"
    )
    # Pin two structural fields so the suppression path's diagnostics
    # remain actionable: hint mentions β-S11-18 + client_count is
    # reported so operators see the size growth.
    assert "β-S11-18" in combined or "SQLite-backed" in combined, (
        "warning hint must reference β-S11-18 / SQLite-backed "
        "migration so post-mortem reviewers can find the durable fix"
    )


async def test_list_oauth_clients_misses_post_overflow_clients(
    monkeypatch: pytest.MonkeyPatch,
    keyring_memory: object,
) -> None:
    """β-S11-18 contained degradation: list_oauth_clients() is the
    enumeration path that reads the index, so clients registered
    AFTER the index overflowed will be missing from list output.
    The per-client records remain on disk + resolvable via direct
    get_oauth_client(client_id) — only the enumeration is degraded.
    This test pins the expected behavior so operators can rely on
    `get_oauth_client` as the source of truth for individual client
    lookup until β-S11-18 SQLite migration lands.
    """
    import keyring as keyring_module

    store = TokenStore(service="test-oauth-list-degrades")

    # First client registers with healthy index.
    store.register_oauth_client(_oauth_record("client-A"))

    # Subsequent registrations hit the overflow.
    real_set_password = keyring_module.set_password

    def index_overflow_after_first(
        service: str, username: str, password: str
    ) -> None:
        if username == OAUTH_CLIENTS_INDEX_USERNAME:
            raise OSError(1783, "CredWrite", "The stub received bad data")
        real_set_password(service, username, password)

    monkeypatch.setattr(
        keyring_module, "set_password", index_overflow_after_first
    )

    store.register_oauth_client(_oauth_record("client-B"))
    store.register_oauth_client(_oauth_record("client-C"))

    # list_oauth_clients misses B + C (they never reached the index).
    listed_ids = {rec.client_id for rec in store.list_oauth_clients()}
    assert "client-A" in listed_ids
    assert "client-B" not in listed_ids
    assert "client-C" not in listed_ids

    # But direct lookups for B + C STILL succeed — OAuth flow works.
    assert store.get_oauth_client("client-B") is not None, (
        "β-S11-18: direct lookup MUST resolve clients that the index "
        "overflow caused list_oauth_clients to miss"
    )
    assert store.get_oauth_client("client-C") is not None


# ===========================================================================
# β-S11-18 v2: pywintypes.error catch — production shape coverage
# ===========================================================================
#
# v1 incident 2026-05-21: the v1 hotfix used `except OSError` and silently
# failed in production. Root cause: keyring's Windows backend re-raises
# the underlying WindowsError as `win32ctypes.pywin32.pywintypes.error`,
# which inherits from `Exception` (NOT `OSError`). The v1 tests passed
# because they raised bare `OSError(1783, ...)` — a fixture-fidelity gap.
#
# v2 broadens the catch to `except Exception` and gates suppression on
# the duck-typed CredWrite-blob-overflow shape (winerror/errno/args[0]
# == 1783 AND CredWrite source). Tests below pin production-shape
# exceptions to prevent this regression class.
# ---------------------------------------------------------------------------


async def test_register_oauth_client_succeeds_when_pywintypes_overflow_raised(
    monkeypatch: pytest.MonkeyPatch,
    keyring_memory: object,
) -> None:
    """β-S11-18 v2: production exception class
    ``win32ctypes.pywin32.pywintypes.error`` is NOT an ``OSError``
    subclass. v1 hotfix's ``except OSError`` did not catch it; v2's
    ``except Exception`` + CredWrite-shape classifier MUST catch it.

    This is the test the v1 hotfix needed but lacked. Without it,
    the v1 hotfix's existing OSError-shaped tests all passed but
    production still HTTP-500'd on /register.
    """
    from win32ctypes.pywin32.pywintypes import error as pywintypes_error
    import keyring as keyring_module

    store = TokenStore(service="test-oauth-pywintypes-overflow")

    real_set_password = keyring_module.set_password

    def selective_failing_set_password(
        service: str, username: str, password: str
    ) -> None:
        if username == OAUTH_CLIENTS_INDEX_USERNAME:
            # Production exception shape: from the daemon-stderr
            # diagnostic 2026-05-21. winerror=1783, funcname='CredWrite',
            # strerror='The stub received bad data'. Inherits from
            # Exception, NOT OSError.
            raise pywintypes_error(
                1783, "CredWrite", "The stub received bad data"
            )
        real_set_password(service, username, password)

    monkeypatch.setattr(
        keyring_module, "set_password", selective_failing_set_password
    )

    record = _oauth_record("test-client-v2-001")
    # Must NOT raise — v2 hotfix suppresses the pywintypes-shape overflow.
    store.register_oauth_client(record)

    # Per-client record persists and resolves — OAuth token validation
    # path is unaffected.
    fetched = store.get_oauth_client("test-client-v2-001")
    assert fetched is not None, (
        "β-S11-18 v2: pywintypes-shape overflow must suppress cleanly "
        "AND the per-client record must remain resolvable via direct "
        "get_oauth_client lookup. v1 hotfix failed BOTH parts in "
        "production."
    )
    assert fetched.client_id == "test-client-v2-001"


async def test_register_oauth_client_propagates_pywintypes_with_other_winerror(
    monkeypatch: pytest.MonkeyPatch,
    keyring_memory: object,
) -> None:
    """β-S11-18 v2 narrow-suppression invariant: a pywintypes.error
    with the SAME class but a DIFFERENT winerror code (e.g., 5 =
    access denied) MUST propagate. Suppression is keyed to
    winerror==1783 specifically, not to the exception class.
    """
    from win32ctypes.pywin32.pywintypes import error as pywintypes_error
    import keyring as keyring_module

    store = TokenStore(service="test-oauth-pywintypes-access-denied")

    real_set_password = keyring_module.set_password

    def index_write_access_denied(
        service: str, username: str, password: str
    ) -> None:
        if username == OAUTH_CLIENTS_INDEX_USERNAME:
            raise pywintypes_error(5, "CredWrite", "Access is denied")
        real_set_password(service, username, password)

    monkeypatch.setattr(
        keyring_module, "set_password", index_write_access_denied
    )

    record = _oauth_record("test-client-v2-002")
    with pytest.raises(pywintypes_error) as exc_info:
        store.register_oauth_client(record)
    assert exc_info.value.winerror == 5, (
        "non-1783 pywintypes.error must propagate verbatim — "
        "β-S11-18 v2 suppression is keyed to winerror==1783 only"
    )


async def test_register_oauth_client_propagates_unrelated_exception(
    monkeypatch: pytest.MonkeyPatch,
    keyring_memory: object,
) -> None:
    """β-S11-18 v2 broadened-catch invariant: ``except Exception``
    around _write_oauth_clients_index does NOT widen the
    silent-failure surface. An unrelated exception (e.g., RuntimeError
    from a misconfigured backend, ValueError, KeyError) MUST
    propagate. The inner duck-typing check on (1783 + CredWrite)
    enforces the narrowness.
    """
    import keyring as keyring_module

    store = TokenStore(service="test-oauth-unrelated-exception")

    real_set_password = keyring_module.set_password

    def index_write_runtime_error(
        service: str, username: str, password: str
    ) -> None:
        if username == OAUTH_CLIENTS_INDEX_USERNAME:
            raise RuntimeError(
                "simulated backend misconfiguration — not a CredWrite "
                "blob-overflow failure"
            )
        real_set_password(service, username, password)

    monkeypatch.setattr(
        keyring_module, "set_password", index_write_runtime_error
    )

    record = _oauth_record("test-client-v2-003")
    with pytest.raises(RuntimeError, match="misconfiguration"):
        store.register_oauth_client(record)


async def test_register_oauth_client_propagates_pywintypes_credread_with_overflow_code(
    monkeypatch: pytest.MonkeyPatch,
    keyring_memory: object,
) -> None:
    """β-S11-18 v2 source-check invariant: even if winerror==1783
    surfaces, suppression requires the source/operation to indicate
    CredWrite. A pywintypes.error from CredRead/CredEnumerate or any
    other Windows call that happens to carry code 1783 MUST propagate
    — those are not the blob-overflow failure mode this hotfix
    targets.
    """
    from win32ctypes.pywin32.pywintypes import error as pywintypes_error
    import keyring as keyring_module

    store = TokenStore(service="test-oauth-credread-1783")

    real_set_password = keyring_module.set_password

    def index_write_credread_overflow(
        service: str, username: str, password: str
    ) -> None:
        if username == OAUTH_CLIENTS_INDEX_USERNAME:
            # winerror=1783 but funcname=CredRead — implausible in
            # practice but pins the source-check invariant.
            raise pywintypes_error(1783, "CredRead", "some-other-error")
        real_set_password(service, username, password)

    monkeypatch.setattr(
        keyring_module, "set_password", index_write_credread_overflow
    )

    record = _oauth_record("test-client-v2-004")
    with pytest.raises(pywintypes_error):
        store.register_oauth_client(record)


async def test_register_oauth_client_warning_ascii_safe_under_cp1252_stderr(
    monkeypatch: pytest.MonkeyPatch,
    keyring_memory: object,
) -> None:
    """β-S11-18 v3: the overflow warning's hint string MUST be
    ASCII-safe so structlog's render-to-stderr path does not crash
    under Windows-default cp1252 console encoding. v2 hotfix shipped a
    hint with the literal Greek beta (U+03B2) prefix; cp1252 raised
    UnicodeEncodeError on render, the error propagated out of the
    OAuth /register handler, and PM Claude Code re-auth returned
    HTTP 500.

    Mechanism: explicitly bind a structlog PrintLogger to a
    cp1252-encoded stream with errors='strict' (the Windows production
    shape). Trigger the overflow suppression path against a TokenStore
    that uses that logger. The warning emission MUST complete without
    raising; the per-client record MUST remain resolvable.
    """
    import io
    import structlog
    from win32ctypes.pywin32.pywintypes import error as pywintypes_error
    import keyring as keyring_module
    from meridian_orchestrator import auth as auth_module

    # Capture via cp1252-encoded buffer with errors='strict' to mirror
    # the Windows console redirect shape. If the hint string contains a
    # non-cp1252-encodable character, the write will raise exactly as
    # it did in production.
    raw_buf = io.BytesIO()
    cp1252_strict_stderr = io.TextIOWrapper(
        raw_buf, encoding="cp1252", errors="strict", write_through=True
    )
    # Bind a structlog PrintLogger directly to our cp1252-strict stream
    # and patch it into the auth module so the suppression path uses
    # this logger. Bypasses any default-stderr cache that
    # structlog.get_logger may have established at module load.
    test_logger = structlog.PrintLogger(file=cp1252_strict_stderr)
    test_bound = structlog.wrap_logger(
        test_logger,
        processors=[
            structlog.processors.add_log_level,
            structlog.dev.ConsoleRenderer(colors=False),
        ],
    )
    monkeypatch.setattr(auth_module, "logger", test_bound)

    store = TokenStore(service="test-oauth-cp1252-stderr")

    real_set_password = keyring_module.set_password

    def overflow_on_index(
        service: str, username: str, password: str
    ) -> None:
        if username == OAUTH_CLIENTS_INDEX_USERNAME:
            raise pywintypes_error(
                1783, "CredWrite", "The stub received bad data"
            )
        real_set_password(service, username, password)

    monkeypatch.setattr(keyring_module, "set_password", overflow_on_index)

    record = _oauth_record("test-client-cp1252-001")
    # The v2 hotfix would raise UnicodeEncodeError here when structlog
    # rendered the beta-containing hint string to the cp1252-strict
    # stderr. The v3 hotfix's ASCII-safe hint MUST avoid that.
    store.register_oauth_client(record)

    cp1252_strict_stderr.flush()
    rendered = raw_buf.getvalue().decode("cp1252", errors="strict")
    # The warning event name must appear in the rendered output (proves
    # we actually traversed the suppression path, not some short-circuit).
    assert "auth.oauth_clients_index_write_overflow" in rendered, (
        f"β-S11-18 v3: warning must render cleanly under cp1252-strict "
        f"stderr; rendered output: {rendered!r}"
    )
    # And the per-client record must still resolve (no propagation).
    assert store.get_oauth_client("test-client-cp1252-001") is not None


def test_configure_stderr_encoding_sets_replace_error_policy(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """β-S11-18 v3 Change 2: daemon-startup
    `_configure_stderr_encoding()` calls `sys.stderr.reconfigure(
    errors='replace')` so that ANY future unencodable character in a
    log message degrades gracefully (renders as `?`) instead of
    crashing the daemon's response path.

    Mechanism: install a fake stderr that records reconfigure() calls.
    Verify the helper invokes reconfigure with errors='replace'.
    Verify a stderr without reconfigure (e.g., a raw BytesIO redirect)
    degrades gracefully — no exception, no boot-blocker.
    """
    import io
    import sys
    from meridian_orchestrator.main import _configure_stderr_encoding

    # Case 1: stderr.reconfigure exists and accepts errors kwarg.
    captured_calls: list[dict[str, object]] = []

    class _FakeReconfigurableStderr:
        encoding = "cp1252"

        def reconfigure(self, **kwargs: object) -> None:
            captured_calls.append(dict(kwargs))

    monkeypatch.setattr(sys, "stderr", _FakeReconfigurableStderr())
    _configure_stderr_encoding()
    assert captured_calls == [{"errors": "replace"}], (
        f"β-S11-18 v3: daemon startup must call "
        f"sys.stderr.reconfigure(errors='replace'); got {captured_calls}"
    )

    # Case 2: stderr has no reconfigure attribute (e.g., a closed
    # stream, a non-TextIOWrapper redirect). Helper must NOT raise.
    class _NoReconfigureStderr:
        encoding = "cp1252"

    monkeypatch.setattr(sys, "stderr", _NoReconfigureStderr())
    _configure_stderr_encoding()  # must complete without raising

    # Case 3: stderr.reconfigure raises OSError (e.g., already
    # configured to a non-reconfigurable shape). Helper must NOT
    # propagate — best-effort by design.
    class _RaisingReconfigureStderr:
        encoding = "cp1252"

        def reconfigure(self, **kwargs: object) -> None:
            raise OSError("simulated non-reconfigurable stream")

    monkeypatch.setattr(sys, "stderr", _RaisingReconfigureStderr())
    _configure_stderr_encoding()  # must complete without raising


def test_is_credwrite_blob_overflow_classifier_matrix() -> None:
    """β-S11-18 v2 classifier unit test: matrix of (exception shape,
    expected suppression) pinning the duck-typing rules.
    """
    from win32ctypes.pywin32.pywintypes import error as pywintypes_error
    from meridian_orchestrator.auth import _is_credwrite_blob_overflow

    # ----- positive cases (should suppress) -----
    assert _is_credwrite_blob_overflow(
        pywintypes_error(1783, "CredWrite", "The stub received bad data")
    ), "production pywintypes shape with code 1783 + CredWrite MUST match"

    assert _is_credwrite_blob_overflow(
        OSError(1783, "CredWrite", "The stub received bad data")
    ), "bare OSError(1783, 'CredWrite', ...) MUST match for fallback shapes"

    # ----- negative cases (must NOT suppress) -----
    assert not _is_credwrite_blob_overflow(
        pywintypes_error(5, "CredWrite", "Access denied")
    ), "non-1783 code must NOT match"

    assert not _is_credwrite_blob_overflow(
        pywintypes_error(1783, "CredRead", "anything")
    ), "non-CredWrite source must NOT match"

    assert not _is_credwrite_blob_overflow(
        OSError(5, "CredWrite", "Access denied")
    ), "OSError with errno=5 must NOT match"

    assert not _is_credwrite_blob_overflow(
        RuntimeError("unrelated failure")
    ), "unrelated exception must NOT match"

    assert not _is_credwrite_blob_overflow(
        ValueError("some value error")
    ), "ValueError must NOT match"

    assert not _is_credwrite_blob_overflow(
        Exception()
    ), "bare Exception with no args must NOT match"
