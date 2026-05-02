"""Unit tests for server-level identity-resolution helpers.

Currently covers :func:`meridian_orchestrator.server._resolve_caller_token_id`
(M5-record-api-call commit 4). Per the
``feedback_contract_tests_fail_informatively`` pattern, the format-
contract test (``test_returns_short_hex_prefix_format_matching_dispatch_lookup``)
durably enforces that the token_id returned here matches what
``dispatches.token_id`` expects for the
``WHERE token_id = ? AND status NOT IN (terminal)`` lookup the
``record_api_call`` writer (and ``record_mcp_call``) use to find the
caller's active dispatch.

Coverage gap: ``_resolve_agent_id`` is the parallel helper but has no
direct unit coverage either — exercised only via integration tests
through the request context. BACKLOG entry: "Add direct unit tests
for _resolve_agent_id mirroring this file's pattern; identity-
resolution helpers are load-bearing and deserve unit-level coverage
not just integration-shape coverage."
"""

from __future__ import annotations

import pytest
from mcp.server.auth.provider import AccessToken

from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.errors import AuthError
from meridian_orchestrator.server import _resolve_caller_token_id


def test_resolves_token_id_from_valid_bearer_token(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Happy path: a valid bearer token in the access context resolves
    to its token_id (the short hex prefix). The resolved token_id is
    what the record_api_call handler passes to its dispatch lookup."""
    store = TokenStore(service="resolve-token-id-test-1")
    issued = store.issue("agent-x", "engineer", "test-engineer")

    access = AccessToken(
        token=issued.token,
        client_id=issued.record.agent_id,
        scopes=["role:engineer"],
    )
    monkeypatch.setattr(
        "meridian_orchestrator.server.get_access_token",
        lambda: access,
    )

    assert _resolve_caller_token_id() == issued.record.token_id


def test_raises_auth_error_on_missing_access_context(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """No authenticated context (no Bearer token on the request) raises
    AuthError. The helper does not silently return an empty string or
    None — wrapper-layer callers can't accidentally pass a falsy
    token_id into a SQL WHERE clause and get unexpected matches."""
    monkeypatch.setattr(
        "meridian_orchestrator.server.get_access_token",
        lambda: None,
    )

    with pytest.raises(AuthError, match="no authenticated context"):
        _resolve_caller_token_id()


def test_returns_short_hex_prefix_format_matching_dispatch_lookup(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Format contract test: the token_id returned by
    ``_resolve_caller_token_id`` round-trips through
    ``TokenStore.lookup_by_token_id`` cleanly. This is the durable
    enforcement that dispatches.token_id (the lookup key for
    record_api_call's atomic increment and record_mcp_call's activity
    update) and the wrapper-layer-resolved token_id share the same
    format.

    A future commit changing the token_id format somewhere
    (TokenStore mint, _parse_token output, dispatches.token_id column
    write) breaks this test before silent-fail in production. The
    failure path: increment-on-active-dispatch silently no-ops
    because WHERE token_id = ? doesn't match anything; existing tests
    don't exercise this because they construct dispatches with
    explicit token_ids that match what TokenStore returns at the
    time the test was written. This test asserts the round-trip is
    valid against the current TokenStore implementation.
    """
    store = TokenStore(service="resolve-token-id-test-3")
    issued = store.issue("agent-y", "implementer", "test-implementer")

    access = AccessToken(
        token=issued.token,
        client_id=issued.record.agent_id,
        scopes=["role:implementer"],
    )
    monkeypatch.setattr(
        "meridian_orchestrator.server.get_access_token",
        lambda: access,
    )

    resolved_token_id = _resolve_caller_token_id()

    # Round-trip: the resolved token_id must look up the original
    # record. If the format ever drifts (TokenStore changes the prefix
    # length, _parse_token changes its return shape, etc.), this fails.
    record = store.lookup_by_token_id(resolved_token_id)
    assert record is not None, (
        f"_resolve_caller_token_id returned {resolved_token_id!r} but "
        "TokenStore.lookup_by_token_id couldn't find a record. The "
        "token_id format used for dispatch lookups (dispatches.token_id "
        "in record_api_call's WHERE clause and record_mcp_call's "
        "activity update) is now broken — increments and activity "
        "writes will silently no-op in production."
    )
    assert record.agent_id == "agent-y"
    assert record.role == "implementer"
