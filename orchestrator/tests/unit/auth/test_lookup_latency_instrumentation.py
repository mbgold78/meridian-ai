"""Unit tests for TokenStore.lookup / lookup_by_token_id latency
instrumentation — slice 1b commit 7.

Per spec § Commit 7 (PM-ratified A3=(b) structlog INFO):

- Every ``lookup`` and ``lookup_by_token_id`` call emits one
  structlog INFO row with fields:
  ``{metric_kind, backend, duration_us, token_id_hash}``.
- ``metric_kind == "auth.lookup.duration_us"``.
- ``backend`` in ``{"sqlite", "keyring", "miss"}``.
- ``token_id_hash`` is ``sha256(token_id)`` hex; the raw ``token_id``
  is never present in any log field (defensive against log-grep
  correlation; see :func:`auth._hash_token_id` docstring).

These tests pin the field schema + privacy contract. Latency-value
assertions are floor-only (``duration_us >= 0``) since real wall-clock
runtimes are non-deterministic.
"""

from __future__ import annotations

import hashlib
from collections.abc import AsyncIterator
from pathlib import Path

import pytest_asyncio
import structlog

from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.transient_tokens import TransientTokenStore


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "test.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


@pytest_asyncio.fixture
async def transient_store(
    storage: SqliteStorage,
) -> TransientTokenStore:
    return TransientTokenStore(storage)


def _latency_rows(captured: list[dict[str, object]]) -> list[dict[str, object]]:
    """Filter ``structlog.testing.capture_logs()`` output to the
    ``auth.lookup.duration`` event rows."""
    return [c for c in captured if c.get("event") == "auth.lookup.duration"]


# ---------------------------------------------------------------------------
# lookup() — three backend classifications
# ---------------------------------------------------------------------------


async def test_lookup_emits_duration_log_on_sqlite_hit(
    transient_store: TransientTokenStore,
) -> None:
    """``indexed=False`` issuance routes through SQLite; ``lookup``
    of the resulting token emits one INFO row with
    ``backend="sqlite"`` and a non-negative ``duration_us``."""
    store = TokenStore(
        service="latency-sqlite-hit",
        transient_store=transient_store,
    )
    issued = await store.issue(
        agent_id=None,
        role="implementer",
        label="dispatch:latency-sqlite",
        indexed=False,
    )
    with structlog.testing.capture_logs() as captured:
        rec = await store.lookup(issued.token)
    assert rec is not None
    rows = _latency_rows(captured)
    assert len(rows) == 1
    row = rows[0]
    assert row["log_level"] == "info"
    assert row["metric_kind"] == "auth.lookup.duration_us"
    assert row["backend"] == "sqlite"
    assert isinstance(row["duration_us"], int)
    assert row["duration_us"] >= 0
    assert row["token_id_hash"] == hashlib.sha256(
        issued.record.token_id.encode("utf-8")
    ).hexdigest()


async def test_lookup_emits_duration_log_on_keyring_hit(
    transient_store: TransientTokenStore,
) -> None:
    """``indexed=True`` issuance routes to keyring; ``lookup`` of
    the resulting token emits one INFO row with
    ``backend="keyring"``."""
    store = TokenStore(
        service="latency-keyring-hit",
        transient_store=transient_store,
    )
    issued = await store.issue(
        agent_id=None,
        role="pm",
        label="static-pm",
        indexed=True,
    )
    with structlog.testing.capture_logs() as captured:
        rec = await store.lookup(issued.token)
    assert rec is not None
    rows = _latency_rows(captured)
    assert len(rows) == 1
    row = rows[0]
    assert row["log_level"] == "info"
    assert row["metric_kind"] == "auth.lookup.duration_us"
    assert row["backend"] == "keyring"
    assert isinstance(row["duration_us"], int)
    assert row["duration_us"] >= 0
    assert row["token_id_hash"] == hashlib.sha256(
        issued.record.token_id.encode("utf-8")
    ).hexdigest()


async def test_lookup_emits_duration_log_on_miss(
    transient_store: TransientTokenStore,
) -> None:
    """``lookup`` of a never-issued (but syntactically valid) token
    string returns ``None`` and emits one INFO row with
    ``backend="miss"``."""
    store = TokenStore(
        service="latency-miss",
        transient_store=transient_store,
    )
    unknown_token_id = "0123456789abcdef"
    unknown_token = f"mor_{unknown_token_id}_unused-secret-value"
    with structlog.testing.capture_logs() as captured:
        rec = await store.lookup(unknown_token)
    assert rec is None
    rows = _latency_rows(captured)
    assert len(rows) == 1
    row = rows[0]
    assert row["log_level"] == "info"
    assert row["metric_kind"] == "auth.lookup.duration_us"
    assert row["backend"] == "miss"
    assert isinstance(row["duration_us"], int)
    assert row["duration_us"] >= 0
    assert row["token_id_hash"] == hashlib.sha256(
        unknown_token_id.encode("utf-8")
    ).hexdigest()


# ---------------------------------------------------------------------------
# lookup_by_token_id() — same shape, different method
# ---------------------------------------------------------------------------


async def test_lookup_by_token_id_emits_duration_log(
    transient_store: TransientTokenStore,
) -> None:
    """``lookup_by_token_id`` emits the same field schema as ``lookup``
    across all three backend classifications: sqlite-hit, keyring-hit,
    miss."""
    store = TokenStore(
        service="latency-by-id",
        transient_store=transient_store,
    )
    sqlite_issued = await store.issue(
        agent_id=None,
        role="implementer",
        label="dispatch:by-id-sqlite",
        indexed=False,
    )
    keyring_issued = await store.issue(
        agent_id=None,
        role="pm",
        label="static-pm-by-id",
        indexed=True,
    )

    # sqlite-hit
    with structlog.testing.capture_logs() as captured:
        rec = await store.lookup_by_token_id(sqlite_issued.record.token_id)
    assert rec is not None
    sqlite_rows = _latency_rows(captured)
    assert len(sqlite_rows) == 1
    assert sqlite_rows[0]["log_level"] == "info"
    assert sqlite_rows[0]["metric_kind"] == "auth.lookup.duration_us"
    assert sqlite_rows[0]["backend"] == "sqlite"
    assert isinstance(sqlite_rows[0]["duration_us"], int)
    assert sqlite_rows[0]["duration_us"] >= 0
    assert sqlite_rows[0]["token_id_hash"] == hashlib.sha256(
        sqlite_issued.record.token_id.encode("utf-8")
    ).hexdigest()

    # keyring-hit
    with structlog.testing.capture_logs() as captured:
        rec = await store.lookup_by_token_id(keyring_issued.record.token_id)
    assert rec is not None
    keyring_rows = _latency_rows(captured)
    assert len(keyring_rows) == 1
    assert keyring_rows[0]["backend"] == "keyring"
    assert keyring_rows[0]["token_id_hash"] == hashlib.sha256(
        keyring_issued.record.token_id.encode("utf-8")
    ).hexdigest()

    # miss
    unknown_token_id = "fedcba9876543210"
    with structlog.testing.capture_logs() as captured:
        rec = await store.lookup_by_token_id(unknown_token_id)
    assert rec is None
    miss_rows = _latency_rows(captured)
    assert len(miss_rows) == 1
    assert miss_rows[0]["backend"] == "miss"
    assert miss_rows[0]["token_id_hash"] == hashlib.sha256(
        unknown_token_id.encode("utf-8")
    ).hexdigest()


# ---------------------------------------------------------------------------
# Privacy / no-leak contract
# ---------------------------------------------------------------------------


async def test_duration_log_no_token_id_leak(
    transient_store: TransientTokenStore,
) -> None:
    """The latency row's ``token_id_hash`` is a 64-char sha256-hex
    string; the raw ``token_id`` and the raw token string never appear
    in any log field value across both lookup methods + all three
    backend classifications."""
    store = TokenStore(
        service="latency-no-leak",
        transient_store=transient_store,
    )
    sqlite_issued = await store.issue(
        agent_id=None,
        role="implementer",
        label="dispatch:leak-sqlite",
        indexed=False,
    )
    keyring_issued = await store.issue(
        agent_id=None,
        role="pm",
        label="static-pm-leak",
        indexed=True,
    )
    unknown_token_id = "abcdef0123456789"
    unknown_token = f"mor_{unknown_token_id}_unused-secret-value"
    sensitive_strings = [
        sqlite_issued.token,
        sqlite_issued.record.token_id,
        keyring_issued.token,
        keyring_issued.record.token_id,
        unknown_token,
        unknown_token_id,
    ]

    with structlog.testing.capture_logs() as captured:
        await store.lookup(sqlite_issued.token)
        await store.lookup(keyring_issued.token)
        await store.lookup(unknown_token)
        await store.lookup_by_token_id(sqlite_issued.record.token_id)
        await store.lookup_by_token_id(keyring_issued.record.token_id)
        await store.lookup_by_token_id(unknown_token_id)

    rows = _latency_rows(captured)
    assert len(rows) == 6
    for row in rows:
        # token_id_hash is a 64-char lowercase-hex sha256 digest.
        token_hash = row["token_id_hash"]
        assert isinstance(token_hash, str)
        assert len(token_hash) == 64
        assert all(c in "0123456789abcdef" for c in token_hash)
        # No raw token-derived string appears in any other field's
        # value. ``token_id_hash`` is intentionally derived from the
        # raw ``token_id`` (via one-way sha256) and excluded from this
        # substring sweep — the hash IS the privacy-preserving carrier
        # the spec mandates.
        for field_name, field_value in row.items():
            if field_name == "token_id_hash":
                continue
            if not isinstance(field_value, str):
                continue
            for leak in sensitive_strings:
                assert leak not in field_value, (
                    f"raw token material {leak!r} leaked into log field "
                    f"{field_name!r}: {field_value!r}"
                )
