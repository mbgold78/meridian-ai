"""OAuth Authorization Server implementation for the local MCP daemon.

Claude Desktop's MCP connector UI is OAuth-only — it cannot accept a static
bearer token. This module provides the minimal AS endpoints (RFC 8414 metadata
+ RFC 7591 dynamic client registration + RFC 6749 authorization-code flow with
PKCE) so Claude Desktop's connector can complete its discovery and approval
flow against the daemon.

The flow:

1. Claude Desktop POSTs to ``/register`` with redirect_uris (loopback only).
2. Claude Desktop opens the browser to ``/authorize?...``.
3. FastMCP's authorization handler invokes ``MeridianOAuthProvider.authorize``,
   which validates the redirect_uri is loopback, stores the request as a
   pending approval session, and returns a URL to ``/approve?session=<sid>``.
4. The browser GETs ``/approve``, sees an HTML form with the connector name,
   redirect URI, scopes, and the role being granted. The operator clicks
   Approve (or Deny).
5. POST ``/approve`` mints an authorization code, stores it (in-memory, TTL),
   and 302-redirects to the client's ``redirect_uri?code=<code>&state=<state>``.
6. Claude Desktop POSTs ``/token`` with the code + PKCE verifier. FastMCP's
   token handler validates the verifier against the stored ``code_challenge``
   and calls ``MeridianOAuthProvider.exchange_authorization_code``, which
   issues a token via :class:`auth.TokenStore` with ``source="oauth"``.

Tokens issued via this flow live in the same keyring index as static tokens.
``list-tokens.py`` shows them with ``source=oauth`` and the originating client.
"""

from __future__ import annotations

import html
import secrets
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from ipaddress import IPv4Address, IPv6Address, ip_address
from typing import Any, Literal, cast
from urllib.parse import urlencode, urlsplit

import structlog
from mcp.server.auth.handlers.authorize import AuthorizeError  # type: ignore[attr-defined]
from mcp.server.auth.handlers.register import RegistrationError  # type: ignore[attr-defined]
from mcp.server.auth.provider import (
    AccessToken,
    AuthorizationCode,
    AuthorizationParams,
    OAuthAuthorizationServerProvider,
    RefreshToken,
)
from mcp.shared.auth import OAuthClientInformationFull, OAuthToken
from pydantic import AnyUrl
from starlette.requests import Request
from starlette.responses import HTMLResponse, RedirectResponse, Response

from meridian_orchestrator.auth import (
    OAuthClientRecord,
    TokenRecord,
    TokenStore,
)

logger = structlog.get_logger(__name__)


APPROVAL_PATH = "/approve"
DEFAULT_OAUTH_ROLE = "pm"
DEFAULT_OAUTH_SCOPES = (f"role:{DEFAULT_OAUTH_ROLE}",)
AUTH_CODE_TTL_SECONDS = 600
APPROVAL_SESSION_TTL_SECONDS = 600
LOOPBACK_HOSTS = frozenset({"localhost", "meridian-orchestrator-local"})


def _is_loopback_redirect(uri: str) -> bool:
    """RFC 8252-style loopback check: scheme http(s), host is 127.0.0.1, ::1,
    or the literal hostname 'localhost'. Any non-loopback URI is rejected so a
    rogue client can't redirect approval-codes off the machine."""
    parts = urlsplit(uri)
    if parts.scheme not in {"http", "https"}:
        return False
    host = parts.hostname
    if not host:
        return False
    if host in LOOPBACK_HOSTS:
        return True
    try:
        addr = ip_address(host)
    except ValueError:
        return False
    if isinstance(addr, IPv4Address):
        return addr.is_loopback
    if isinstance(addr, IPv6Address):
        return addr.is_loopback
    return False


@dataclass
class _ApprovalSession:
    """Server-side state spanning /authorize → /approve → redirect.

    The ``session_id`` is a capability — anyone holding it can approve. Since
    /authorize generates the sid and returns its location via 302, only the
    redirected user-agent learns it. Single-use; cleared on consumption."""

    session_id: str
    client_id: str
    client_name: str
    redirect_uri: str
    redirect_uri_provided_explicitly: bool
    state: str | None
    scopes: list[str]
    code_challenge: str
    resource: str | None
    role: str
    expires_at: float
    csrf_token: str = field(default_factory=lambda: secrets.token_urlsafe(24))


@dataclass
class _StoredAuthorizationCode:
    """In-memory authorization code with PKCE challenge for /token exchange."""

    code: AuthorizationCode
    role: str


class MeridianOAuthProvider(
    OAuthAuthorizationServerProvider[
        AuthorizationCode, RefreshToken, AccessToken
    ]
):
    """OAuth AS provider backed by :class:`auth.TokenStore`.

    Authorization codes and approval sessions live in process memory with
    short TTLs — they're transient state. Clients and access tokens live in
    keyring (the existing single source of truth for credentials)."""

    def __init__(
        self,
        store: TokenStore,
        default_role: str = DEFAULT_OAUTH_ROLE,
        on_oauth_caller_registered: (
            Callable[[str, str], Awaitable[None]] | None
        ) = None,
    ) -> None:
        """Construct the OAuth AS provider.

        ``on_oauth_caller_registered`` is invoked from
        :meth:`exchange_authorization_code` after a successful token issue.
        Signature ``async (agent_id: str, role: str) -> None``. Production
        wiring (main.py) passes a closure that registers the OAuth caller
        in the agent registry + UPSERTs the agents row in storage so
        FK-bound handlers (submit_task, etc.) accept the OAuth caller's
        ``agent_id``. ``None`` default keeps the OAuth flow standalone for
        tests that don't exercise the registry/storage integration.
        """
        self._store = store
        self._default_role = default_role
        self._on_oauth_caller_registered = on_oauth_caller_registered
        self._approval_sessions: dict[str, _ApprovalSession] = {}
        self._auth_codes: dict[str, _StoredAuthorizationCode] = {}

    # ----- Client registry --------------------------------------------------

    async def get_client(self, client_id: str) -> OAuthClientInformationFull | None:
        record = self._store.get_oauth_client(client_id)
        if record is None:
            return None
        return _record_to_client_info(record)

    async def register_client(self, client_info: OAuthClientInformationFull) -> None:
        for uri in client_info.redirect_uris or []:
            if not _is_loopback_redirect(str(uri)):
                logger.warning(
                    "oauth.register.non_loopback_rejected",
                    client_id=client_info.client_id,
                    redirect_uri=str(uri),
                )
                raise RegistrationError(
                    error="invalid_redirect_uri",
                    error_description=(
                        "Phase 1 only accepts loopback redirect URIs "
                        "(127.0.0.1, ::1, or localhost)."
                    ),
                )
        record = OAuthClientRecord(
            client_id=client_info.client_id or secrets.token_urlsafe(16),
            client_secret=client_info.client_secret,
            client_name=client_info.client_name or "unnamed-mcp-client",
            redirect_uris=[str(u) for u in (client_info.redirect_uris or [])],
            scope=client_info.scope,
            grant_types=list(client_info.grant_types or []),
            response_types=list(client_info.response_types or []),
            token_endpoint_auth_method=client_info.token_endpoint_auth_method or "none",
            registered_at=_now_iso(),
        )
        self._store.register_oauth_client(record)
        logger.info(
            "oauth.client_registered",
            client_id=record.client_id,
            client_name=record.client_name,
            redirect_uris=record.redirect_uris,
        )

    # ----- Authorization flow ----------------------------------------------

    async def authorize(
        self, client: OAuthClientInformationFull, params: AuthorizationParams
    ) -> str:
        redirect_uri_str = str(params.redirect_uri)
        if not _is_loopback_redirect(redirect_uri_str):
            logger.warning(
                "oauth.authorize.non_loopback_rejected",
                client_id=client.client_id,
                redirect_uri=redirect_uri_str,
            )
            raise AuthorizeError(
                error="invalid_request",
                error_description=(
                    "Phase 1 only accepts loopback redirect URIs "
                    "(127.0.0.1, ::1, or localhost)."
                ),
            )

        sid = secrets.token_urlsafe(24)
        scopes = params.scopes or list(DEFAULT_OAUTH_SCOPES)
        session = _ApprovalSession(
            session_id=sid,
            client_id=client.client_id or "",
            client_name=client.client_name or "unnamed-mcp-client",
            redirect_uri=redirect_uri_str,
            redirect_uri_provided_explicitly=params.redirect_uri_provided_explicitly,
            state=params.state,
            scopes=list(scopes),
            code_challenge=params.code_challenge,
            resource=params.resource,
            role=self._default_role,
            expires_at=time.monotonic() + APPROVAL_SESSION_TTL_SECONDS,
        )
        self._approval_sessions[sid] = session
        self._cleanup_expired_sessions()
        return f"{APPROVAL_PATH}?session={sid}"

    def _consume_session(self, sid: str) -> _ApprovalSession | None:
        session = self._approval_sessions.get(sid)
        if session is None:
            return None
        if session.expires_at < time.monotonic():
            self._approval_sessions.pop(sid, None)
            return None
        return session

    def _finalize_session(self, sid: str) -> _ApprovalSession | None:
        session = self._consume_session(sid)
        if session is not None:
            self._approval_sessions.pop(sid, None)
        return session

    def _cleanup_expired_sessions(self) -> None:
        now = time.monotonic()
        for sid, session in list(self._approval_sessions.items()):
            if session.expires_at < now:
                self._approval_sessions.pop(sid, None)
        for code, stored in list(self._auth_codes.items()):
            if stored.code.expires_at < time.time():
                self._auth_codes.pop(code, None)

    def render_approval_page(self, session: _ApprovalSession) -> str:
        scope_html = "".join(
            f"<li><code>{html.escape(s)}</code></li>" for s in session.scopes
        )
        return _APPROVAL_TEMPLATE.format(
            client_name=html.escape(session.client_name),
            client_id=html.escape(session.client_id),
            redirect_uri=html.escape(session.redirect_uri),
            scopes=scope_html or "<li><em>none requested</em></li>",
            role=html.escape(session.role),
            session_id=html.escape(session.session_id),
            csrf_token=html.escape(session.csrf_token),
            approve_path=APPROVAL_PATH,
        )

    def complete_approval(self, sid: str, csrf_token: str) -> str | None:
        """Generate an authorization code and return the redirect URL the
        approval form should 302 to. Returns None on bad/missing session or
        CSRF mismatch."""
        session = self._approval_sessions.get(sid)
        if session is None:
            return None
        if not secrets.compare_digest(session.csrf_token, csrf_token):
            logger.warning(
                "oauth.approve.csrf_mismatch", session_id=sid, client_id=session.client_id
            )
            return None
        session = self._finalize_session(sid)
        if session is None:
            return None

        code_value = secrets.token_urlsafe(32)
        auth_code = AuthorizationCode(
            code=code_value,
            scopes=session.scopes,
            expires_at=time.time() + AUTH_CODE_TTL_SECONDS,
            client_id=session.client_id,
            code_challenge=session.code_challenge,
            redirect_uri=AnyUrl(session.redirect_uri),
            redirect_uri_provided_explicitly=session.redirect_uri_provided_explicitly,
            resource=session.resource,
        )
        self._auth_codes[code_value] = _StoredAuthorizationCode(
            code=auth_code, role=session.role
        )

        params: dict[str, str] = {"code": code_value}
        if session.state is not None:
            params["state"] = session.state
        return f"{session.redirect_uri}?{urlencode(params)}"

    def deny_approval(self, sid: str, csrf_token: str) -> str | None:
        """Operator-clicked Deny. Redirects back to the client with an
        ``access_denied`` error per RFC 6749 § 4.1.2.1."""
        session = self._approval_sessions.get(sid)
        if session is None:
            return None
        if not secrets.compare_digest(session.csrf_token, csrf_token):
            return None
        session = self._finalize_session(sid)
        if session is None:
            return None
        params = {"error": "access_denied", "error_description": "user denied the request"}
        if session.state is not None:
            params["state"] = session.state
        return f"{session.redirect_uri}?{urlencode(params)}"

    # ----- Code/token exchange ---------------------------------------------

    async def load_authorization_code(
        self, client: OAuthClientInformationFull, authorization_code: str
    ) -> AuthorizationCode | None:
        stored = self._auth_codes.get(authorization_code)
        if stored is None:
            return None
        if stored.code.client_id != (client.client_id or ""):
            return None
        if stored.code.expires_at < time.time():
            self._auth_codes.pop(authorization_code, None)
            return None
        return stored.code

    async def exchange_authorization_code(
        self,
        client: OAuthClientInformationFull,
        authorization_code: AuthorizationCode,
    ) -> OAuthToken:
        stored = self._auth_codes.pop(authorization_code.code, None)
        role = stored.role if stored is not None else self._default_role
        ttl_seconds = 60 * 60 * 24 * 30
        expires_at_iso = (
            datetime.now(tz=UTC) + timedelta(seconds=ttl_seconds)
        ).isoformat(timespec="seconds")
        # alpha persistence: reuse same agent_id across re-authorizations
        # for the same (client_id, role). Same operator's OAuth
        # re-authorization flow -> same agent_id -> audit trail consistency
        # across operator session re-opens. None client_id (defensive;
        # OAuth dynamic registration always sets it but the SDK type
        # is Optional) skips the lookup; TokenStore.issue then mints
        # fresh agent_id. None return from the lookup likewise mints
        # fresh.
        existing_agent_id: str | None = None
        if client.client_id:
            existing_agent_id = self._store.find_oauth_caller_by_client_id(
                client.client_id, role
            )
        issued = await self._store.issue(
            agent_id=existing_agent_id,
            role=role,
            label=f"oauth:{client.client_name or client.client_id or 'unknown'}",
            source="oauth",
            client_id=client.client_id,
            expires_at=expires_at_iso,
        )
        logger.info(
            "oauth.token_issued",
            client_id=client.client_id,
            agent_id=issued.record.agent_id,
            role=role,
            token_id=issued.record.token_id,
            agent_id_reused=existing_agent_id is not None,
        )
        if self._on_oauth_caller_registered is not None:
            try:
                await self._on_oauth_caller_registered(
                    issued.record.agent_id, role
                )
            except Exception as exc:
                # Callback failure does NOT block token issuance — operator
                # already has the bearer; FK validation at handler time
                # would surface a downstream error, but the OAuth flow
                # itself completes. Log at error level for operator
                # diagnostic.
                logger.error(
                    "oauth.caller_registered_callback_failed",
                    agent_id=issued.record.agent_id,
                    role=role,
                    error=str(exc),
                )
        return OAuthToken(
            access_token=issued.token,
            token_type="Bearer",
            expires_in=ttl_seconds,
            scope=" ".join(authorization_code.scopes),
            refresh_token=None,
        )

    async def load_refresh_token(
        self,
        client: OAuthClientInformationFull,  # noqa: ARG002
        refresh_token: str,  # noqa: ARG002
    ) -> RefreshToken | None:
        return None

    async def exchange_refresh_token(
        self,
        client: OAuthClientInformationFull,
        refresh_token: RefreshToken,
        scopes: list[str],
    ) -> OAuthToken:
        raise NotImplementedError("refresh tokens not issued in Phase 1")

    async def load_access_token(self, token: str) -> AccessToken | None:
        record = await self._store.lookup(token)
        if record is None:
            return None
        return _record_to_access_token(token, record)

    async def revoke_token(
        self, token: AccessToken | RefreshToken
    ) -> None:
        record = (
            await self._store.lookup(token.token)
            if isinstance(token, AccessToken)
            else None
        )
        if record is None:
            return
        await self._store.revoke(record.token_id)
        logger.info("oauth.token_revoked", token_id=record.token_id)


_AuthMethod = Literal["none", "client_secret_post", "client_secret_basic", "private_key_jwt"]


def _record_to_client_info(record: OAuthClientRecord) -> OAuthClientInformationFull:
    return OAuthClientInformationFull(
        client_id=record.client_id,
        client_secret=record.client_secret,
        client_name=record.client_name,
        redirect_uris=[AnyUrl(u) for u in record.redirect_uris],
        scope=record.scope,
        grant_types=record.grant_types,
        response_types=record.response_types,
        token_endpoint_auth_method=cast(
            "_AuthMethod", record.token_endpoint_auth_method
        ),
    )


def _record_to_access_token(token_str: str, record: TokenRecord) -> AccessToken:
    expires_epoch: int | None
    if record.expires_at is None:
        expires_epoch = None
    else:
        try:
            expires_epoch = int(datetime.fromisoformat(record.expires_at).timestamp())
        except ValueError:
            expires_epoch = None
    return AccessToken(
        token=token_str,
        client_id=record.agent_id,
        scopes=[f"role:{record.role}"],
        expires_at=expires_epoch,
    )


def _now_iso() -> str:
    return datetime.now(tz=UTC).isoformat(timespec="seconds")


# ----- Custom routes -----------------------------------------------------

def make_approval_routes(provider: MeridianOAuthProvider) -> list[
    tuple[str, list[str], Any]
]:
    """Return ``(path, methods, handler)`` tuples to register via
    ``FastMCP.custom_route``. Two routes share the path: GET renders the form,
    POST consumes it and redirects."""

    async def get_approval(request: Request) -> Response:
        sid = request.query_params.get("session", "")
        session = provider._consume_session(sid)
        if session is None:
            return HTMLResponse(
                _ERROR_TEMPLATE.format(
                    message="Approval session is invalid or expired."
                ),
                status_code=400,
            )
        return HTMLResponse(provider.render_approval_page(session))

    async def post_approval(request: Request) -> Response:
        form = await request.form()
        sid = str(form.get("session", ""))
        csrf = str(form.get("csrf", ""))
        decision = str(form.get("decision", ""))
        if decision == "approve":
            redirect = provider.complete_approval(sid, csrf)
        elif decision == "deny":
            redirect = provider.deny_approval(sid, csrf)
        else:
            redirect = None
        if redirect is None:
            return HTMLResponse(
                _ERROR_TEMPLATE.format(
                    message="Approval session is invalid, expired, or CSRF check failed."
                ),
                status_code=400,
            )
        return RedirectResponse(redirect, status_code=302)

    return [
        (APPROVAL_PATH, ["GET"], get_approval),
        (APPROVAL_PATH, ["POST"], post_approval),
    ]


_APPROVAL_TEMPLATE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Approve MCP connector</title>
<style>
body {{ font-family: -apple-system, Segoe UI, sans-serif; max-width: 560px; margin: 4em auto; padding: 0 1em; color: #222; }}
h1 {{ font-size: 1.4em; margin-bottom: 0.5em; }}
dl {{ display: grid; grid-template-columns: max-content 1fr; gap: 0.4em 1em; margin: 1.5em 0; }}
dt {{ font-weight: 600; color: #555; }}
dd {{ margin: 0; word-break: break-all; }}
ul {{ margin: 0.3em 0; padding-left: 1.4em; }}
code {{ background: #f3f3f3; padding: 0.1em 0.35em; border-radius: 3px; font-size: 0.95em; }}
.actions {{ display: flex; gap: 1em; margin-top: 2em; }}
button {{ font-size: 1em; padding: 0.6em 1.4em; border-radius: 4px; border: 1px solid #999; cursor: pointer; }}
button.approve {{ background: #2563eb; color: white; border-color: #1d4ed8; }}
button.deny {{ background: #f3f3f3; color: #222; }}
.warn {{ font-size: 0.9em; color: #777; margin-top: 1.5em; }}
</style>
</head>
<body>
<h1>Approve MCP connector?</h1>
<p>An MCP client is requesting authorization to call tools on the local Meridian Orchestrator. Approving will issue an access token bound to the role below.</p>
<dl>
<dt>Connector</dt><dd>{client_name}</dd>
<dt>Client ID</dt><dd><code>{client_id}</code></dd>
<dt>Redirect URI</dt><dd><code>{redirect_uri}</code></dd>
<dt>Scopes requested</dt><dd><ul>{scopes}</ul></dd>
<dt>Granting role</dt><dd><code>{role}</code> (Phase 1: only Mike's PM agent uses OAuth; subagents use static keyring tokens)</dd>
</dl>
<form method="POST" action="{approve_path}">
<input type="hidden" name="session" value="{session_id}">
<input type="hidden" name="csrf" value="{csrf_token}">
<div class="actions">
<button class="approve" type="submit" name="decision" value="approve">Approve</button>
<button class="deny" type="submit" name="decision" value="deny">Deny</button>
</div>
</form>
<p class="warn">Only approve if you initiated this from a connector setup flow you trust. The redirect URI above is where the authorization code will be sent.</p>
</body>
</html>
"""


_ERROR_TEMPLATE = """<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8"><title>Approval failed</title></head>
<body style="font-family: sans-serif; max-width: 560px; margin: 4em auto; padding: 0 1em;">
<h1>Approval failed</h1>
<p>{message}</p>
<p>Return to the connector setup and start over.</p>
</body></html>
"""
