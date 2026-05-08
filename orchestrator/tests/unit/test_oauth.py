"""Unit tests for the OAuth provider's loopback enforcement and session/code logic.

Full HTTP-level OAuth flow is covered by ``tests/integration/test_oauth_flow.py``.
This file tests the provider class in isolation: redirect_uri validation,
approval session lifecycle, code generation, and CSRF/PKCE handling.
"""

from __future__ import annotations

import time
from typing import cast

import pytest
from mcp.server.auth.provider import AuthorizationParams
from mcp.shared.auth import OAuthClientInformationFull
from pydantic import AnyUrl

from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.oauth import (
    APPROVAL_PATH,
    MeridianOAuthProvider,
    _is_loopback_redirect,
)


def _client(redirect_uris: list[str]) -> OAuthClientInformationFull:
    return OAuthClientInformationFull(
        client_id="cid-1",
        client_name="claude-desktop-test",
        redirect_uris=[AnyUrl(u) for u in redirect_uris],
        grant_types=["authorization_code", "refresh_token"],
        response_types=["code"],
        token_endpoint_auth_method="none",
        scope="role:pm",
    )


def _params(redirect_uri: str) -> AuthorizationParams:
    return AuthorizationParams(
        state="xyz",
        scopes=["role:pm"],
        code_challenge="dGVzdC1jaGFsbGVuZ2UtdGVzdC1jaGFsbGVuZ2UtdGVzdA",
        redirect_uri=AnyUrl(redirect_uri),
        redirect_uri_provided_explicitly=True,
        resource=None,
    )


@pytest.mark.parametrize(
    "uri,expected",
    [
        ("http://127.0.0.1:54321/cb", True),
        ("http://localhost:54321/cb", True),
        ("http://[::1]:54321/cb", True),
        ("https://127.0.0.1/cb", True),
        ("http://10.0.0.5:54321/cb", False),
        ("http://example.com/cb", False),
        ("https://attacker.com/cb", False),
        ("ftp://127.0.0.1/cb", False),
        ("http://0.0.0.0/cb", False),
        ("http://127.0.0.1.evil.com/cb", False),
    ],
)
def test_is_loopback_redirect(uri: str, expected: bool) -> None:
    assert _is_loopback_redirect(uri) is expected


async def test_register_rejects_non_loopback() -> None:
    from mcp.server.auth.handlers.register import RegistrationError

    store = TokenStore(service="oauth-test")
    provider = MeridianOAuthProvider(store)
    bad = _client(["https://attacker.example.com/cb"])
    with pytest.raises(RegistrationError) as exc:
        await provider.register_client(bad)
    assert exc.value.error == "invalid_redirect_uri"


async def test_register_persists_loopback_client() -> None:
    store = TokenStore(service="oauth-test")
    provider = MeridianOAuthProvider(store)
    good = _client(["http://127.0.0.1:54321/cb"])
    await provider.register_client(good)
    fetched = await provider.get_client("cid-1")
    assert fetched is not None
    assert fetched.client_name == "claude-desktop-test"


async def test_authorize_returns_approval_url_and_stores_session() -> None:
    store = TokenStore(service="oauth-test")
    provider = MeridianOAuthProvider(store)
    client = _client(["http://127.0.0.1:54321/cb"])
    url = await provider.authorize(client, _params("http://127.0.0.1:54321/cb"))
    assert url.startswith(f"{APPROVAL_PATH}?session=")
    sid = url.split("session=", 1)[1]
    session = provider._consume_session(sid)
    assert session is not None
    assert session.client_id == "cid-1"
    assert session.scopes == ["role:pm"]


async def test_authorize_rejects_non_loopback_redirect() -> None:
    from mcp.server.auth.handlers.authorize import AuthorizeError

    store = TokenStore(service="oauth-test")
    provider = MeridianOAuthProvider(store)
    client = _client(["http://127.0.0.1:54321/cb"])
    with pytest.raises(AuthorizeError) as exc:
        await provider.authorize(client, _params("http://attacker.com/cb"))
    assert exc.value.error == "invalid_request"


async def test_complete_approval_emits_redirect_and_persists_code() -> None:
    store = TokenStore(service="oauth-test")
    provider = MeridianOAuthProvider(store)
    client = _client(["http://127.0.0.1:54321/cb"])
    url = await provider.authorize(client, _params("http://127.0.0.1:54321/cb"))
    sid = url.split("session=", 1)[1]
    session = provider._approval_sessions[sid]
    csrf = session.csrf_token

    redirect = provider.complete_approval(sid, csrf)
    assert redirect is not None
    assert redirect.startswith("http://127.0.0.1:54321/cb?")
    assert "code=" in redirect
    assert "state=xyz" in redirect

    code_value = redirect.split("code=", 1)[1].split("&", 1)[0]
    loaded = await provider.load_authorization_code(client, code_value)
    assert loaded is not None
    assert loaded.code_challenge == _params("http://127.0.0.1:54321/cb").code_challenge


async def test_complete_approval_rejects_csrf_mismatch() -> None:
    store = TokenStore(service="oauth-test")
    provider = MeridianOAuthProvider(store)
    client = _client(["http://127.0.0.1:54321/cb"])
    url = await provider.authorize(client, _params("http://127.0.0.1:54321/cb"))
    sid = url.split("session=", 1)[1]
    assert provider.complete_approval(sid, "wrong-csrf") is None
    assert sid in provider._approval_sessions


async def test_deny_approval_redirects_with_access_denied() -> None:
    store = TokenStore(service="oauth-test")
    provider = MeridianOAuthProvider(store)
    client = _client(["http://127.0.0.1:54321/cb"])
    url = await provider.authorize(client, _params("http://127.0.0.1:54321/cb"))
    sid = url.split("session=", 1)[1]
    session = provider._approval_sessions[sid]
    redirect = provider.deny_approval(sid, session.csrf_token)
    assert redirect is not None
    assert "error=access_denied" in redirect
    assert "state=xyz" in redirect


async def test_exchange_authorization_code_issues_oauth_sourced_token() -> None:
    store = TokenStore(service="oauth-test")
    provider = MeridianOAuthProvider(store)
    client = _client(["http://127.0.0.1:54321/cb"])
    url = await provider.authorize(client, _params("http://127.0.0.1:54321/cb"))
    sid = url.split("session=", 1)[1]
    session = provider._approval_sessions[sid]
    redirect = provider.complete_approval(sid, session.csrf_token)
    assert redirect is not None
    code_value = redirect.split("code=", 1)[1].split("&", 1)[0]
    auth_code = await provider.load_authorization_code(client, code_value)
    assert auth_code is not None

    oauth_token = await provider.exchange_authorization_code(client, auth_code)
    assert oauth_token.token_type == "Bearer"
    assert oauth_token.access_token.startswith("mor_")

    # The issued token resolves through the unified TokenStore, marked oauth.
    record = store.lookup(oauth_token.access_token)
    assert record is not None
    assert record.source == "oauth"
    assert record.client_id == "cid-1"
    assert record.role == "pm"


async def test_load_authorization_code_rejects_after_expiry() -> None:
    store = TokenStore(service="oauth-test")
    provider = MeridianOAuthProvider(store)
    client = _client(["http://127.0.0.1:54321/cb"])
    url = await provider.authorize(client, _params("http://127.0.0.1:54321/cb"))
    sid = url.split("session=", 1)[1]
    session = provider._approval_sessions[sid]
    redirect = provider.complete_approval(sid, session.csrf_token)
    assert redirect is not None
    code_value = redirect.split("code=", 1)[1].split("&", 1)[0]
    stored = provider._auth_codes[code_value]
    object.__setattr__(stored.code, "expires_at", time.time() - 5)
    assert await provider.load_authorization_code(client, code_value) is None


async def test_revoke_token_marks_record_revoked() -> None:
    store = TokenStore(service="oauth-test")
    provider = MeridianOAuthProvider(store)
    issued = store.issue(
        agent_id=None, role="pm", label="oauth:test",
        source="oauth", client_id="cid-1",
    )
    access = await provider.load_access_token(issued.token)
    assert access is not None
    await provider.revoke_token(cast("object", access))  # type: ignore[arg-type]
    assert await provider.load_access_token(issued.token) is None
