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

Two-backend storage model (post-M5-sqlite-transient-token-store):
- Static seeds + OAuth tokens stored in keyring (via TokenStore methods).
- Transient per-dispatch / per-spawn tokens stored in SQLite via
  TransientTokenStore (injected at TokenStore construction; routed
  per ratified decision R1=(b) try-SQLite-first-then-keyring).
- Lifespan-managed GC reaps expired transient rows on the
  Settings.transient_token_gc_interval_seconds cadence (default 60s).
- Startup orphan + double-presence probe (M1=(c) ratified report-only)
  emits auth.orphan.detected + auth.double_presence.detected audit
  events for operator observability.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import secrets
import time
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import TYPE_CHECKING, Any, Literal

import keyring
import structlog
from keyring.errors import PasswordDeleteError

if TYPE_CHECKING:
    from meridian_orchestrator.transient_tokens import TransientTokenStore

KEYRING_SERVICE = "meridian-orchestrator-tokens"
INDEX_USERNAME = "_index"
OAUTH_CLIENTS_INDEX_USERNAME = "_oauth_clients_index"
TOKEN_PREFIX = "mor"

TokenSource = Literal["static", "oauth"]

_HEX = "0123456789abcdef"

logger = structlog.get_logger(__name__)


# β-S11-18: Windows CredWrite blob-overflow classifier ---------------------
#
# Windows Credential Manager rejects ``CredWrite`` calls whose blob exceeds
# ~2560 bytes with Windows error code 1783 ('The stub received bad data').
# When this surfaces through the ``keyring`` Python package on Windows the
# exception class depends on which Windows-bindings library is installed:
#
#   * ``pywin32-ctypes`` (the actual installed package per this repo's lockfile):
#     raises ``win32ctypes.pywin32.pywintypes.error`` — inherits from
#     ``Exception``, NOT from ``OSError``. Has ``winerror``, ``funcname``,
#     and ``strerror`` attributes; ``args`` is the raw 3-tuple
#     ``(winerror, funcname, strerror)``.
#   * The legacy ``pywin32`` package surfaces a similar
#     ``pywintypes.error`` class with comparable shape.
#   * Some non-Windows test paths construct the bare ``OSError(1783,
#     'CredWrite', '...')`` form to simulate the failure mode; on
#     Windows-Python that sets ``errno=1783`` with ``winerror=None``.
#
# The classifier below tolerates all three shapes by duck-typing on
# either:
#
#   - ``winerror``, ``errno``, or first-positional ``args[0]`` equal to
#     1783 (the code check); AND
#   - ``funcname == 'CredWrite'``, ``'CredWrite' in args``, or
#     ``'CredWrite' in str(exc)`` (the operation/source check).
#
# Both conditions are required: an unrelated exception happening to
# carry a 1783 code in some unrelated field, or a CredRead/CredEnumerate
# failure that doesn't match the blob-overflow shape, will NOT be
# suppressed. The β-S11-18 v1 hotfix relied on ``except OSError`` and
# silently failed in production because the actual pywintypes-shape
# exception isn't an OSError subclass; v2 broadens the catch and pins
# the narrowness here in the classifier.
def _is_credwrite_blob_overflow(exc: BaseException) -> bool:
    """Return True iff ``exc`` matches the Windows CredWrite blob-overflow
    failure shape (winerror=1783 + CredWrite source). False for any other
    exception; the caller will propagate non-matching exceptions.

    Tolerates pywin32-ctypes, legacy pywin32, and bare-OSError shapes.
    See module-level comment above for the classifier rationale.
    """
    # ----- code check -----
    code = getattr(exc, "winerror", None)
    if code is None:
        code = getattr(exc, "errno", None)
    if code is None:
        args = getattr(exc, "args", ()) or ()
        if args and isinstance(args[0], int):
            code = args[0]
    if code != 1783:
        return False

    # ----- source check (CredWrite / Windows keyring) -----
    funcname = getattr(exc, "funcname", None)
    if funcname == "CredWrite":
        return True
    args = getattr(exc, "args", ()) or ()
    if any(a == "CredWrite" for a in args if isinstance(a, str)):
        return True
    return "CredWrite" in str(exc)

LOOKUP_LATENCY_METRIC_KIND: str = "auth.lookup.duration_us"


def _now_iso() -> str:
    return datetime.now(tz=UTC).isoformat(timespec="seconds")


def _hash_secret(secret: str) -> str:
    return hashlib.sha256(secret.encode("utf-8")).hexdigest()


def _hash_token_id(token_id: str) -> str:
    """sha256(token_id) hex.

    Used by the lookup-latency observability surface to emit a stable,
    one-way reference to the looked-up id without exposing the raw
    ``token_id`` to log readers. The id alone is not the credential
    (the secret-half of ``mor_<id>_<secret>`` is what authenticates),
    but the unhashed id can still be correlated against other auth-log
    surfaces; hashing is defensive against that correlation.
    """
    return hashlib.sha256(token_id.encode("utf-8")).hexdigest()


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

    def __init__(
        self,
        service: str = KEYRING_SERVICE,
        *,
        transient_store: TransientTokenStore | None = None,
    ) -> None:
        self._service = service
        self._transient_store = transient_store

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

    async def issue(
        self,
        agent_id: str | None,
        role: str,
        label: str,
        *,
        source: TokenSource = "static",
        client_id: str | None = None,
        expires_at: str | None = None,
        indexed: bool = True,
    ) -> IssuedToken:
        """Issue a new token for ``role`` and bind it to ``agent_id``.

        ``agent_id`` is generated if not provided — that UUID is stable and will
        be reused as the agents row PK when M3 seeds the agents table.

        ``source`` distinguishes static (CLI-issued) from OAuth-issued tokens.
        ``client_id`` and ``expires_at`` are only meaningful for OAuth tokens.

        ``indexed`` controls whether the issued ``token_id`` is appended to the
        keyring ``_index`` aggregator. Default ``True`` preserves prior
        behavior for static/OAuth long-lived credentials and keeps them
        visible via :meth:`list_records` / ``scripts/list-tokens.py``. Pass
        ``False`` for high-churn transient credentials (per-dispatch
        Implementer/Tester tokens; per-spawn PM/Engineer supervisor
        tokens). Unindexed tokens still authenticate normally via
        :meth:`lookup` because the per-token record at
        ``token:<token_id>`` is always written, and they revoke normally
        via :meth:`revoke` for the same reason — both code paths read
        the per-token entry directly without going through ``_index``.
        Unindexed tokens are intentionally excluded from
        :meth:`list_records` so the keyring ``_index`` blob does not grow
        unbounded and exceed the underlying keyring backend's
        per-credential size limit (Windows Credential Manager:
        ~2560 bytes; β-S11-5 / β-S11-6 root cause — once the limit was
        reached, ``CredWrite`` returned ``WinError 1783`` and the daemon
        could no longer boot because ``pm_supervisor.start()`` failed in
        ``_write_index`` on every spawn).

        **Rollback semantics** (``indexed=True`` only): if any step in
        the index-update sequence fails after the ``token:<token_id>``
        record has been written, the just-written record is deleted
        before re-raising. Without rollback, the keyring would be left
        with an orphan record that :meth:`list_records` would never
        surface (since the failed index write means the id never entered
        the index) but whose secret-hash would still pass
        :meth:`lookup` for the in-process ``IssuedToken`` returned to
        the caller — and any subsequent re-issue with the same
        ``token_id`` (vanishingly unlikely but not impossible) would
        return a record with mismatched metadata. The cleanup delete is
        best-effort: if it itself raises, the original error still
        propagates so the caller is never silently masked.
        """
        if not role:
            raise ValueError("role must be non-empty")
        # R1=(b) routing: indexed=False high-churn tokens go to the SQLite-
        # backed TransientTokenStore when one is configured. indexed=True
        # always stays on keyring (static seeds + OAuth-issued PM tokens).
        # transient_store=None preserves keyring-only behavior for callers
        # (tests; scripts) that haven't opted into the SQLite backend.
        if not indexed and self._transient_store is not None:
            return await self._transient_store.issue(
                agent_id=agent_id,
                role=role,
                label=label,
            )
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
        if indexed:
            try:
                index = self._read_index()
                if token_id not in index:
                    index.append(token_id)
                    self._write_index(index)
            except Exception:
                # Rollback the just-written per-token record so we don't
                # leave an orphan that list_records() would never surface
                # (the failed index write means the id never entered the
                # index). Best-effort: suppress cleanup errors so the
                # original write failure is what propagates to the caller.
                try:
                    keyring.delete_password(
                        self._service, _record_key(token_id)
                    )
                except Exception:
                    pass
                raise
        token = f"{TOKEN_PREFIX}_{token_id}_{secret}"
        return IssuedToken(token=token, record=record)

    async def lookup(self, token: str) -> TokenRecord | None:
        """Resolve a token string to its record. Returns ``None`` if not found,
        revoked, expired, or the secret hash does not match (constant-time).

        R1=(b) routing: when a TransientTokenStore is configured, try SQLite
        first; on miss, fall back to keyring. Active-record lookup short-
        circuits on the first hit (returning a real record OR None per the
        SQLite-side semantics — revoked/expired tokens return None from the
        transient backend just as they do from the keyring path).

        Emits one structlog INFO row per call with ``metric_kind=
        "auth.lookup.duration_us"`` and ``backend`` in ``{"sqlite",
        "keyring", "miss"}`` (slice 1b commit 7 latency observability;
        A3=(b) ratified — structlog, not audit log, to avoid
        per-MCP-call audit bloat). ``token_id_hash`` is sha256-hex; the
        raw ``token_id`` never leaves the function.
        """
        start_ns = time.perf_counter_ns()
        backend: str = "miss"
        token_id_for_hash: str = ""
        try:
            if self._transient_store is not None:
                record = await self._transient_store.lookup(token)
                if record is not None:
                    backend = "sqlite"
                    token_id_for_hash = record.token_id
                    return record
            parsed = _parse_token(token)
            if parsed is None:
                return None
            token_id, secret = parsed
            token_id_for_hash = token_id
            record = self._read_record(token_id)
            if record is None:
                return None
            if record.revoked_at is not None:
                return None
            if record.is_expired():
                return None
            if not hmac.compare_digest(
                record.secret_hash, _hash_secret(secret)
            ):
                return None
            backend = "keyring"
            return record
        finally:
            duration_us = (time.perf_counter_ns() - start_ns) // 1000
            logger.info(
                "auth.lookup.duration",
                metric_kind=LOOKUP_LATENCY_METRIC_KIND,
                backend=backend,
                duration_us=int(duration_us),
                token_id_hash=_hash_token_id(token_id_for_hash),
            )

    async def lookup_by_token_id(self, token_id: str) -> TokenRecord | None:
        """Look up a token record by id alone. Used by the OAuth provider when
        the framework gives it the access_token string to revoke; we strip the
        id and call this for the lookup. Does not validate the secret.

        Returns the record even when revoked. Load-bearing for the dispatch
        self-access pattern (Implementer reads its own dispatch result via
        ``get_dispatch_result`` after the dispatcher has already revoked the
        per-dispatch token in its terminal-state cleanup). Authorization checks
        at the call site are responsible for validating ``revoked_at``; do
        not filter revoked records here without auditing every caller.

        R1=(b) routing: try transient first; if hit, return — the SQLite
        backend honors the revoked-record-returned contract too. Else fall
        back to keyring.

        Emits the same structlog INFO latency row as :meth:`lookup`
        (slice 1b commit 7).
        """
        start_ns = time.perf_counter_ns()
        backend: str = "miss"
        try:
            if self._transient_store is not None:
                record = await self._transient_store.lookup_by_token_id(
                    token_id
                )
                if record is not None:
                    backend = "sqlite"
                    return record
            record = self._read_record(token_id)
            if record is not None:
                backend = "keyring"
            return record
        finally:
            duration_us = (time.perf_counter_ns() - start_ns) // 1000
            logger.info(
                "auth.lookup.duration",
                metric_kind=LOOKUP_LATENCY_METRIC_KIND,
                backend=backend,
                duration_us=int(duration_us),
                token_id_hash=_hash_token_id(token_id),
            )

    def find_oauth_caller_by_client_id(
        self, client_id: str, role: str
    ) -> str | None:
        """Look up the most-recent OAuth-issued agent_id for (client_id, role).

        OAuth caller's ``client_id`` is stable across re-authorizations for
        that operator-on-that-machine — inherits M2/M3 contract semantics
        ("token UUIDs become agents row primary keys; stable across
        reissues"). Per alpha persistence: same operator's OAuth re-authorization
        flow reuses the same agent_id; audit trail consistency preserved
        across operator session re-opens.

        Iterates ``list_records()`` filtered to ``source='oauth'`` AND
        matching ``client_id`` AND matching ``role``; returns the most-recent
        matching record's ``agent_id`` (active or revoked — revoked tokens
        carry the same agent_id; alpha persistence reuses regardless of revoke
        state). Returns ``None`` if no prior OAuth caller exists for the
        (client_id, role) pair — caller (typically
        :meth:`oauth.MeridianOAuthProvider.exchange_authorization_code`)
        mints a fresh ``agent_id`` via :meth:`issue` with ``agent_id=None``.

        Most-recent by ``issued_at`` ISO string; ties broken by record
        order in keyring index (effectively first-issued in case of
        microsecond tie). Multi-device concern (different ``client_id``
        per Claude Code install): per-device persistence; multi-device
        operator identity unification is post-alpha scope per BACKLOG entry.
        """
        matching: list[TokenRecord] = []
        for record in self.list_records():
            if (
                record.source == "oauth"
                and record.client_id == client_id
                and record.role == role
            ):
                matching.append(record)
        if not matching:
            return None
        matching.sort(key=lambda r: r.issued_at, reverse=True)
        return matching[0].agent_id

    async def revoke(self, token_id: str) -> bool:
        """Mark a token revoked. Returns True if the record existed and was
        flipped, False if the token_id was unknown or already revoked.

        Forwards every metadata field on the original record. Earlier versions
        defaulted ``source``/``client_id``/``expires_at`` on the rebuilt
        record, which silently flipped a revoked OAuth token's source to
        ``static`` — corrupting audit context for any downstream call that
        inspected revoked records (the dispatch self-access path is one).

        R1=(b) routing: try transient first; if it flips, return True. Else
        fall back to the keyring path.
        """
        if self._transient_store is not None:
            if await self._transient_store.revoke(token_id):
                return True
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
            source=record.source,
            client_id=record.client_id,
            expires_at=record.expires_at,
        )
        self._write_record(revoked)
        return True

    async def delete(self, token_id: str) -> bool:
        """Hard-delete a token record from keyring and the index. Returns True
        if anything was removed. Used by tests for cleanup; not exposed via CLI.

        D2=(a) — cross-backend symmetric routing. Try the transient backend
        first (if configured); always also try the keyring path, since the
        ``token_id`` may live in keyring even when ``transient_store`` is
        wired (e.g., a static seed deleted by a test fixture that also has
        the transient backend wired). Returns True if either path removed
        something.
        """
        removed = False
        if self._transient_store is not None:
            if await self._transient_store.delete(token_id):
                removed = True
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
        """Persist the OAuth-clients enumeration index in keyring.

        β-S11-18 (2026-05-21): Windows Credential Manager rejects
        ``CredWrite`` calls whose blob exceeds ~2560 bytes with the
        Windows error code 1783 ('The stub received bad data'). Same
        architectural shape as β-S11-5 transient-tokens index-bleed:
        a monolithic aggregator keyring blob grows unbounded across
        daemon lifetimes until it overflows the backend's
        per-credential limit. Once over, every new ``register_client``
        call from a fresh MCP dynamic-registration request fails at
        this exact line, surfacing as HTTP 500 at ``/register`` and
        breaking the OAuth handshake.

        Tactical suppression (β-S11-18 hotfix v2): the per-client
        record at ``oauth_client:<client_id>`` was already written by
        :meth:`register_oauth_client` BEFORE this call, so
        ``get_oauth_client(client_id)`` — the only call site OAuth
        token validation depends on — still resolves the new client.
        The cost of suppressing this specific failure is that
        :meth:`list_oauth_clients` will miss enumerating clients added
        after the overflow; no production code path uses
        ``list_oauth_clients`` for live OAuth flow, only for operator
        introspection.

        Durable follow-up tracked in BACKLOG as β-S11-18: SQLite-backed
        OAuth client registry, mirroring β-S11-15's transient-tokens
        migration. That removes the keyring blob entirely and recovers
        ``list_oauth_clients`` correctness.

        **v2 catch-classifier rationale:** the v1 hotfix used
        ``except OSError``, which failed in production because the
        actual exception arriving from
        ``keyring.backends.Windows.set_password`` is
        ``win32ctypes.pywin32.pywintypes.error`` — a bare
        ``Exception`` subclass, NOT ``OSError``. The ``pywin32error``
        context manager catches the underlying ``WindowsError`` and
        re-raises as ``pywintypes.error`` with ``winerror`` set as an
        attribute. v2 broadens the catch to ``except Exception`` and
        applies an inline duck-typing check that requires BOTH:

          1. Windows error code 1783 surfaced via ANY of
             ``winerror`` (pywin32-shape), ``errno`` (bare-OSError
             shape used in tests / non-pywin32 backends), or
             ``args[0]`` (rawest pywintypes shape with the int code
             as first positional).
          2. Operation/source indicator points at CredWrite or the
             Windows keyring path: ``funcname == 'CredWrite'``
             (pywintypes-shape), ``'CredWrite' in args``
             (multi-positional shape), or ``'CredWrite' in str(exc)``
             (string-fallback shape).

        Both conditions must hold; otherwise the exception propagates
        verbatim. This keeps the broadened try-block from broadening
        the silent-failure surface — any unrelated exception (e.g.,
        ``RuntimeError`` from a misconfigured backend, OSError with
        ``errno=5`` access-denied, ValueError from a corrupt blob)
        still bubbles up loudly.
        """
        try:
            keyring.set_password(
                self._service,
                OAUTH_CLIENTS_INDEX_USERNAME,
                json.dumps({"client_ids": client_ids}),
            )
        except Exception as exc:
            if not _is_credwrite_blob_overflow(exc):
                raise
            logger.warning(
                "auth.oauth_clients_index_write_overflow",
                service=self._service,
                client_count=len(client_ids),
                error=str(exc),
                error_type=f"{type(exc).__module__}.{type(exc).__name__}",
                hint=(
                    # β-S11-18 v3 (2026-05-21): runtime-emitted strings
                    # MUST be ASCII-safe. Windows console stderr defaults
                    # to cp1252 which has no codepoint for Greek beta
                    # (U+03B2). The v2 hotfix's hint contained the
                    # literal "β-S11-18" prefix; when structlog rendered
                    # the warning to stderr, cp1252 raised
                    # UnicodeEncodeError, which propagated up through
                    # the OAuth /register handler as HTTP 500.
                    # Paired with the daemon-startup
                    # sys.stderr.reconfigure(errors='replace') in
                    # main.py — belt-and-suspenders against future
                    # Unicode-in-stderr regressions.
                    "beta-S11-18: keyring CredWrite blob >~2560 bytes; "
                    "per-client `oauth_client:<id>` records remain "
                    "written and resolvable via get_oauth_client(). "
                    "list_oauth_clients() enumeration is now stale "
                    "until BACKLOG beta-S11-18 SQLite-backed migration "
                    "lands."
                ),
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
