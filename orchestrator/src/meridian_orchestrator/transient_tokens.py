"""SQLite-backed store for transient (high-churn) auth tokens.

Per ratified decision R1=(b) at M5-sqlite-transient-token-store (parent spec
``docs/specs/M5-sqlite-transient-token-store.md``), high-churn auth tokens
(per-dispatch Implementer/Tester + per-spawn supervisor) move off Windows
Credential Manager into a SQLite-backed table to bound keyring entry growth
(β-S11-5 / β-S11-6 next failure mode). The keyring remains the storage for
``indexed=True`` long-lived credentials (static seeds + OAuth-issued PM
tokens).

This module provides the SQLite-backed store. The composing routing layer in
``auth.TokenStore`` lands at slice 1a commit 2; this commit ships the store
in isolation alongside its schema migration.

Per ratified decision D1=(a), transient records hardcode ``source="static"``
on the returned :class:`TokenRecord`: static + transient are both daemon-
issued (distinct only by churn rate); the synthetic value preserves the
Phase 1 ``TokenSource`` enum surface without adding a third literal.
"""

from __future__ import annotations

import hmac
import uuid
from datetime import datetime, timedelta

from meridian_orchestrator.auth import (
    TOKEN_PREFIX,
    IssuedToken,
    TokenRecord,
    _hash_secret,
    _new_secret,
    _new_token_id,
    _now_iso,
    _parse_token,
)
from meridian_orchestrator.storage.interface import Storage


def _compute_expires_at(issued_at: str, ttl_seconds: int) -> str:
    """Materialize ``expires_at`` at insert time. The schema column has no
    default — column-default-only would not generalize across SQLite versions
    consistently per the migration's header comment. Mirrors ``auth._now_iso``
    formatting (``isoformat(timespec="seconds")``) so :meth:`TokenRecord.is_expired`
    can round-trip the value through ``datetime.fromisoformat``."""
    return (
        datetime.fromisoformat(issued_at) + timedelta(seconds=ttl_seconds)
    ).isoformat(timespec="seconds")


class TransientTokenStore:
    """SQLite-backed store for transient (``indexed=False``) tokens.

    Routed behind :class:`auth.TokenStore` per ratified decision R1=(b) in a
    later slice-1a commit. Methods are async because the underlying storage
    backend is ``aiosqlite``-via-:class:`Storage`.
    """

    def __init__(
        self,
        storage: Storage,
        default_ttl_seconds: int = 86400,
    ) -> None:
        self._storage = storage
        self._default_ttl_seconds = default_ttl_seconds

    async def issue(
        self,
        agent_id: str | None,
        role: str,
        label: str,
        *,
        ttl_seconds: int | None = None,
    ) -> IssuedToken:
        """Mint + persist a transient token.

        Hardcodes ``source="static"`` on the returned :class:`TokenRecord` per
        ratified decision D1=(a) — see module docstring.
        """
        if not role:
            raise ValueError("role must be non-empty")
        ttl = self._default_ttl_seconds if ttl_seconds is None else ttl_seconds
        token_id = _new_token_id()
        secret = _new_secret()
        secret_hash = _hash_secret(secret)
        issued_at = _now_iso()
        expires_at = _compute_expires_at(issued_at, ttl)
        agent_id_resolved = agent_id or str(uuid.uuid4())
        async with self._storage.transaction():
            await self._storage.execute(
                "INSERT INTO transient_tokens "
                "(token_id, agent_id, role, label, secret_hash, "
                "issued_at, revoked_at, ttl_seconds, expires_at) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    token_id,
                    agent_id_resolved,
                    role,
                    label,
                    secret_hash,
                    issued_at,
                    None,
                    ttl,
                    expires_at,
                ),
            )
        record = TokenRecord(
            token_id=token_id,
            agent_id=agent_id_resolved,
            role=role,
            label=label,
            secret_hash=secret_hash,
            issued_at=issued_at,
            revoked_at=None,
            source="static",  # D1=(a) — see module docstring.
            client_id=None,
            expires_at=expires_at,
        )
        token = f"{TOKEN_PREFIX}_{token_id}_{secret}"
        return IssuedToken(token=token, record=record)

    async def lookup(self, token: str) -> TokenRecord | None:
        """Active-record lookup. Mirrors :meth:`auth.TokenStore.lookup`
        semantics: returns ``None`` if not found, revoked, expired, or
        secret_hash mismatch. Constant-time secret comparison via
        :func:`hmac.compare_digest`."""
        parsed = _parse_token(token)
        if parsed is None:
            return None
        token_id, secret = parsed
        record = await self._fetch_record(token_id)
        if record is None:
            return None
        if record.revoked_at is not None:
            return None
        if record.is_expired():
            return None
        if not hmac.compare_digest(record.secret_hash, _hash_secret(secret)):
            return None
        return record

    async def lookup_by_token_id(self, token_id: str) -> TokenRecord | None:
        """Returns the record EVEN WHEN REVOKED. Preserves the dispatch
        self-access contract (Implementer reads its own dispatch result via
        ``get_dispatch_result`` after the dispatcher has already revoked the
        per-dispatch token in its terminal-state cleanup). Returns ``None``
        only when the ``token_id`` is unknown."""
        return await self._fetch_record(token_id)

    async def revoke(self, token_id: str) -> bool:
        """Mark ``revoked_at`` on the row. Returns ``True`` if flipped,
        ``False`` if the record is missing or already revoked. Mirrors
        :meth:`auth.TokenStore.revoke`'s short-circuit shape."""
        async with self._storage.transaction():
            row = await self._storage.fetch_one(
                "SELECT revoked_at FROM transient_tokens "
                "WHERE token_id = ?",
                (token_id,),
            )
            if row is None or row["revoked_at"] is not None:
                return False
            await self._storage.execute(
                "UPDATE transient_tokens SET revoked_at = ? "
                "WHERE token_id = ?",
                (_now_iso(), token_id),
            )
            return True

    async def delete(self, token_id: str) -> bool:
        """Hard-delete a row by ``token_id``. Returns ``True`` if a row was
        removed, ``False`` if the ``token_id`` was unknown. Test-cleanup-only
        at this slice (mirrors :meth:`auth.TokenStore.delete` docstring
        intent)."""
        async with self._storage.transaction():
            row = await self._storage.fetch_one(
                "SELECT token_id FROM transient_tokens "
                "WHERE token_id = ?",
                (token_id,),
            )
            if row is None:
                return False
            await self._storage.execute(
                "DELETE FROM transient_tokens WHERE token_id = ?",
                (token_id,),
            )
            return True

    async def _fetch_record(self, token_id: str) -> TokenRecord | None:
        row = await self._storage.fetch_one(
            "SELECT token_id, agent_id, role, label, secret_hash, "
            "issued_at, revoked_at, expires_at "
            "FROM transient_tokens WHERE token_id = ?",
            (token_id,),
        )
        if row is None:
            return None
        return TokenRecord(
            token_id=row["token_id"],
            agent_id=row["agent_id"],
            role=row["role"],
            label=row["label"],
            secret_hash=row["secret_hash"],
            issued_at=row["issued_at"],
            revoked_at=row["revoked_at"],
            source="static",  # D1=(a) — see module docstring.
            client_id=None,
            expires_at=row["expires_at"],
        )
