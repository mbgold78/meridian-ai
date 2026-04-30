"""Per-agent MCP auth tokens, persisted in the OS keychain via ``keyring``.

Token format: ``mor_<token_id>_<secret>``. ``token_id`` is a short UUID (hex, no
dashes); ``secret`` is 32 bytes of urlsafe base64. Lookup is O(1) — the token_id
indexes the keyring record, and the record stores ``sha256(secret)`` for constant-
time verification. The plaintext secret is never persisted.

Each token record carries an ``agent_id`` UUID generated at issuance time. M3
seeds the agents table using these UUIDs as primary keys (see
``project_meridian_m2_m3_token_contract.md`` in operator memory).
"""

from __future__ import annotations

import hashlib
import hmac
import json
import secrets
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime

import keyring
from keyring.errors import PasswordDeleteError

KEYRING_SERVICE = "meridian-orchestrator-tokens"
INDEX_USERNAME = "_index"
TOKEN_PREFIX = "mor"

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

    def issue(self, agent_id: str | None, role: str, label: str) -> IssuedToken:
        """Issue a new token for ``role`` and bind it to ``agent_id``.

        ``agent_id`` is generated if not provided — that UUID is stable and will
        be reused as the agents row PK when M3 seeds the agents table.
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
        revoked, or the secret hash does not match (constant-time comparison)."""
        parsed = _parse_token(token)
        if parsed is None:
            return None
        token_id, secret = parsed
        record = self._read_record(token_id)
        if record is None:
            return None
        if record.revoked_at is not None:
            return None
        if not hmac.compare_digest(record.secret_hash, _hash_secret(secret)):
            return None
        return record

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
