"""Per-agent MCP auth tokens, persisted in the OS keychain via ``keyring``.

Token format: ``mor_<token_id>_<secret>``. ``token_id`` is a short UUID (hex, no
dashes); ``secret`` is 32 bytes of urlsafe base64. Lookup is O(1) — the token_id
indexes the keyring record, and the record stores ``sha256(secret)`` for constant-
time verification. The plaintext secret is never persisted.

Each token record carries an ``agent_id`` UUID generated at issuance time. M3
seeds the agents table using these UUIDs as primary keys (see
``project_meridian_m2_m3_token_contract.md`` in operator memory).

Two issuance sources, both stored in the same table and looked up via the same
constant-time path:

- ``static`` — issued by ``scripts/issue-token.py``. Used by M3+ daemon-launched
  agents (Engineer, Implementer, Tester) which can't go through a browser.
- ``oauth`` — issued by the OAuth Authorization Server endpoints (Claude
  Desktop's connector flow). The token's ``client_id`` is the OAuth client_id;
  ``expires_at`` may be set if the OAuth flow chose a finite TTL.

The CLI (``list-tokens.py``) surfaces the ``source`` so the operator can audit
which tokens came from where.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import secrets
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any, Literal

import keyring
from keyring.errors import PasswordDeleteError

KEYRING_SERVICE = "meridian-orchestrator-tokens"
INDEX_USERNAME = "_index"
OAUTH_CLIENTS_INDEX_USERNAME = "_oauth_clients_index"
TOKEN_PREFIX = "mor"

TokenSource = Literal["static", "oauth"]

_HEX = "0123456789abcdef"


def _now_iso() -> str:
    return datetime.now(tz=UTC).isoformat(timespec="seconds")


def _hash_secret(secret: str) -> str:
    return hashlib.sha256(secret.encode("utf-8")).hexdigest()


def _new_token_id() -> str:
    return uuid.uuid4().hex[:16]


def _new_secret() -> str:
    return secrets.token_urlsafe(32)


def _record_key(token_id: str) -> str:
    return f"token:{token_id}"


@dataclass(frozen=True, slots=True)
class TokenRecord:
    """A single issued auth token's metadata.

    ``secret_hash`` is sha256 of the plaintext secret. ``agent_id`` is the UUID
    that M3 will use as the primary key when seeding the agents table.
    """

    token_id: str
    agent_id: str
    role: str
    label: str
    secret_hash: str
    issued_at: str
    revoked_at: str | None
    source: TokenSource = "static"
    client_id: str | None = None
    expires_at: str | None = None

    def to_json(self) -> str:
        return json.dumps(
            {
                "token_id": self.token_id,
                "agent_id": self.agent_id,
                "role": self.role,
                "label": self.label,
                "secret_hash": self.secret_hash,
                "issued_at": self.issued_at,
                "revoked_at": self.revoked_at,
                "source": self.source,
                "client_id": self.client_id,
                "expires_at": self.expires_at,
            }
        )

    @classmethod
    def from_json(cls, raw: str) -> TokenRecord:
        d = json.loads(raw)
        return cls(
            token_id=d["token_id"],
            agent_id=d["agent_id"],
            role=d["role"],
            label=d["label"],
            secret_hash=d["secret_hash"],
            issued_at=d["issued_at"],
            revoked_at=d.get("revoked_at"),
            source=d.get("source", "static"),
            client_id=d.get("client_id"),
            expires_at=d.get("expires_at"),
        )

    def is_expired(self, now: datetime | None = None) -> bool:
        if self.expires_at is None:
            return False
        moment = now or datetime.now(tz=UTC)
        try:
            return datetime.fromisoformat(self.expires_at) <= moment
        except ValueError:
            return True


@dataclass(frozen=True, slots=True)
class OAuthClientRecord:
    """A registered OAuth client (typically Claude Desktop's connector).

    Persisted in the same keyring service as token records. Used by the OAuth
    Authorization Server endpoints implemented in ``oauth.py``.
    """

    client_id: str
    client_secret: str | None
    client_name: str
    redirect_uris: list[str]
    scope: str | None
    grant_types: list[str]
    response_types: list[str]
    token_endpoint_auth_method: str
    registered_at: str
    extra: dict[str, Any] = field(default_factory=dict)

    def to_json(self) -> str:
        return json.dumps(
            {
                "client_id": self.client_id,
                "client_secret": self.client_secret,
                "client_name": self.client_name,
                "redirect_uris": list(self.redirect_uris),
                "scope": self.scope,
                "grant_types": list(self.grant_types),
                "response_types": list(self.response_types),
                "token_endpoint_auth_method": self.token_endpoint_auth_method,
                "registered_at": self.registered_at,
                "extra": dict(self.extra),
            }
        )

    @classmethod
    def from_json(cls, raw: str) -> OAuthClientRecord:
        d = json.loads(raw)
        return cls(
            client_id=d["client_id"],
            client_secret=d.get("client_secret"),
            client_name=d.get("client_name", ""),
            redirect_uris=list(d.get("redirect_uris", [])),
            scope=d.get("scope"),
            grant_types=list(d.get("grant_types", [])),
            response_types=list(d.get("response_types", [])),
            token_endpoint_auth_method=d.get("token_endpoint_auth_method", "none"),
            registered_at=d.get("registered_at", _now_iso()),
            extra=dict(d.get("extra", {})),
        )


@dataclass(frozen=True, slots=True)
class IssuedToken:
    """Returned exactly once at issuance — the full token string is shown to the
    operator and never reconstructed from storage afterward."""

    token: str
    record: TokenRecord


class TokenStore:
    """Keyring-backed token store.

    The default uses the system keyring (Windows Credential Manager on Mike's
    machine). Tests inject an alternate ``keyring.backend.KeyringBackend`` via
    ``keyring.set_keyring`` and rely on the same module surface.
    """

    def __init__(self, service: str = KEYRING_SERVICE) -> None:
        self._service = service

    def _read_index(self) -> list[str]:
        raw = keyring.get_password(self._service, INDEX_USERNAME)
        if raw is None:
            return []
        try:
            data = json.loads(raw)
            ids = data.get("token_ids", [])
            return [tid for tid in ids if isinstance(tid, str)]
        except (json.JSONDecodeError, AttributeError):
            return []

    def _write_index(self, token_ids: list[str]) -> None:
        keyring.set_password(
            self._service, INDEX_USERNAME, json.dumps({"token_ids": token_ids})
        )

    def _read_record(self, token_id: str) -> TokenRecord | None:
        raw = keyring.get_password(self._service, _record_key(token_id))
        if raw is None:
            return None
        return TokenRecord.from_json(raw)

    def _write_record(self, record: TokenRecord) -> None:
        keyring.set_password(
            self._service, _record_key(record.token_id), record.to_json()
        )

    def issue(
        self,
        agent_id: str | None,
        role: str,
        label: str,
        *,
        source: TokenSource = "static",
        client_id: str | None = None,
        expires_at: str | None = None,
    ) -> IssuedToken:
        """Issue a new token for ``role`` and bind it to ``agent_id``.

        ``agent_id`` is generated if not provided — that UUID is stable and will
        be reused as the agents row PK when M3 seeds the agents table.

        ``source`` distinguishes static (CLI-issued) from OAuth-issued tokens.
        ``client_id`` and ``expires_at`` are only meaningful for OAuth tokens.
        """
        if not role:
            raise ValueError("role must be non-empty")
        token_id = _new_token_id()
        secret = _new_secret()
        record = TokenRecord(
            token_id=token_id,
            agent_id=agent_id or str(uuid.uuid4()),
            role=role,
            label=label,
            secret_hash=_hash_secret(secret),
            issued_at=_now_iso(),
            revoked_at=None,
            source=source,
            client_id=client_id,
            expires_at=expires_at,
        )
        self._write_record(record)
        index = self._read_index()
        if token_id not in index:
            index.append(token_id)
            self._write_index(index)
        token = f"{TOKEN_PREFIX}_{token_id}_{secret}"
        return IssuedToken(token=token, record=record)

    def lookup(self, token: str) -> TokenRecord | None:
        """Resolve a token string to its record. Returns ``None`` if not found,
        revoked, expired, or the secret hash does not match (constant-time)."""
        parsed = _parse_token(token)
        if parsed is None:
            return None
        token_id, secret = parsed
        record = self._read_record(token_id)
        if record is None:
            return None
        if record.revoked_at is not None:
            return None
        if record.is_expired():
            return None
        if not hmac.compare_digest(record.secret_hash, _hash_secret(secret)):
            return None
        return record

    def lookup_by_token_id(self, token_id: str) -> TokenRecord | None:
        """Look up a token record by id alone. Used by the OAuth provider when
        the framework gives it the access_token string to revoke; we strip the
        id and call this for the lookup. Does not validate the secret."""
        return self._read_record(token_id)

    def revoke(self, token_id: str) -> bool:
        """Mark a token revoked. Returns True if the record existed and was
        flipped, False if the token_id was unknown or already revoked."""
        record = self._read_record(token_id)
        if record is None or record.revoked_at is not None:
            return False
        revoked = TokenRecord(
            token_id=record.token_id,
            agent_id=record.agent_id,
            role=record.role,
            label=record.label,
            secret_hash=record.secret_hash,
            issued_at=record.issued_at,
            revoked_at=_now_iso(),
        )
        self._write_record(revoked)
        return True

    def delete(self, token_id: str) -> bool:
        """Hard-delete a token record from keyring and the index. Returns True
        if anything was removed. Used by tests for cleanup; not exposed via CLI."""
        removed = False
        try:
            keyring.delete_password(self._service, _record_key(token_id))
            removed = True
        except PasswordDeleteError:
            pass
        index = self._read_index()
        if token_id in index:
            index.remove(token_id)
            self._write_index(index)
            removed = True
        return removed

    def list_records(self) -> list[TokenRecord]:
        """Return all known token records (active and revoked), oldest first."""
        out: list[TokenRecord] = []
        for tid in self._read_index():
            rec = self._read_record(tid)
            if rec is not None:
                out.append(rec)
        out.sort(key=lambda r: r.issued_at)
        return out

    # --- OAuth client registry --------------------------------------------------
    #
    # OAuth clients (e.g., Claude Desktop's connector) are persisted alongside
    # token records in the same keyring service. They share storage because the
    # operator audit story is unified — both are creds.

    def _oauth_client_key(self, client_id: str) -> str:
        return f"oauth_client:{client_id}"

    def _read_oauth_clients_index(self) -> list[str]:
        raw = keyring.get_password(self._service, OAUTH_CLIENTS_INDEX_USERNAME)
        if raw is None:
            return []
        try:
            data = json.loads(raw)
            ids = data.get("client_ids", [])
            return [cid for cid in ids if isinstance(cid, str)]
        except (json.JSONDecodeError, AttributeError):
            return []

    def _write_oauth_clients_index(self, client_ids: list[str]) -> None:
        keyring.set_password(
            self._service,
            OAUTH_CLIENTS_INDEX_USERNAME,
            json.dumps({"client_ids": client_ids}),
        )

    def register_oauth_client(self, record: OAuthClientRecord) -> None:
        """Persist a registered OAuth client. Idempotent on client_id."""
        keyring.set_password(
            self._service, self._oauth_client_key(record.client_id), record.to_json()
        )
        index = self._read_oauth_clients_index()
        if record.client_id not in index:
            index.append(record.client_id)
            self._write_oauth_clients_index(index)

    def get_oauth_client(self, client_id: str) -> OAuthClientRecord | None:
        raw = keyring.get_password(
            self._service, self._oauth_client_key(client_id)
        )
        if raw is None:
            return None
        return OAuthClientRecord.from_json(raw)

    def list_oauth_clients(self) -> list[OAuthClientRecord]:
        out: list[OAuthClientRecord] = []
        for cid in self._read_oauth_clients_index():
            rec = self.get_oauth_client(cid)
            if rec is not None:
                out.append(rec)
        out.sort(key=lambda r: r.registered_at)
        return out


def _parse_token(token: str) -> tuple[str, str] | None:
    """Split ``mor_<id>_<secret>`` → (id, secret). Validates the prefix and the
    id charset; returns None on any deviation."""
    if not isinstance(token, str):
        return None
    parts = token.split("_", 2)
    if len(parts) != 3:
        return None
    prefix, token_id, secret = parts
    if prefix != TOKEN_PREFIX or not token_id or not secret:
        return None
    if any(c not in _HEX for c in token_id):
        return None
    return token_id, secret
