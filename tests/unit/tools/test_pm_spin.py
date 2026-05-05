"""Direct-handler tests for ``tools.pm_spin``.

Exercises :func:`tools.pm_spin.record_spin_rationale` against a
migrated SQLite DB. Covers:

- Happy path: a single emit writes one pm_spin_rationale_log row.
- rationale_free_text optional + nullable: omitted defaults to NULL.
- Per-tick no-dedup: identical (decision, rationale_tag) tuples emit
  separate rows on each call (Q1 Reading A invariant — durable
  timeline, no coalescing).
- Permission: non-PM callers rejected at the handler layer.
- Audit-log non-integration: confirms record_spin_rationale does NOT
  write audit_log per Q6 Reading A (audit log scope = cross-cutting
  events; pm_spin is self-contained durable record).

Permission tests at the MCP wrapper layer + Pydantic boundary tests
live in ``tests/integration/test_pm_spin_mcp_wrapper.py``.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from pathlib import Path

import pytest
import pytest_asyncio

from meridian_orchestrator.errors import (
    PermissionError as OrchestratorPermissionError,
)
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.tools.pm_spin import (
    RecordSpinRationaleInput,
    record_spin_rationale,
)


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


# ---------------------------------------------------------------------------
# Happy path: writes a row
# ---------------------------------------------------------------------------


async def test_record_spin_rationale_writes_row(
    storage: SqliteStorage,
) -> None:
    """A first-time emit writes one pm_spin_rationale_log row with
    the correct decision, rationale_tag, and (NULL) free_text."""
    out = await record_spin_rationale(
        storage=storage,
        request=RecordSpinRationaleInput(
            decision="kept_alive",
            rationale_tag="queued_work",
        ),
        caller_role="pm",
    )

    assert out.spin_rationale_id is not None

    rows = await storage.fetch_all(
        "SELECT id, decision, rationale_tag, rationale_free_text, "
        "iteration_timestamp FROM pm_spin_rationale_log"
    )
    assert len(rows) == 1
    assert rows[0]["id"] == out.spin_rationale_id
    assert rows[0]["decision"] == "kept_alive"
    assert rows[0]["rationale_tag"] == "queued_work"
    assert rows[0]["rationale_free_text"] is None
    assert rows[0]["iteration_timestamp"] is not None


async def test_record_spin_rationale_with_free_text(
    storage: SqliteStorage,
) -> None:
    """rationale_free_text flows through to the row when supplied.
    Used for diagnostic context PM may attach (e.g., 'queue depth=7,
    expected drain ~10min')."""
    out = await record_spin_rationale(
        storage=storage,
        request=RecordSpinRationaleInput(
            decision="kept_alive",
            rationale_tag="queued_work",
            rationale_free_text="queue depth=7; expected drain ~10min",
        ),
        caller_role="pm",
    )

    row = await storage.fetch_one(
        "SELECT rationale_free_text FROM pm_spin_rationale_log "
        "WHERE id = ?",
        (out.spin_rationale_id,),
    )
    assert row is not None
    assert (
        row["rationale_free_text"]
        == "queue depth=7; expected drain ~10min"
    )


# ---------------------------------------------------------------------------
# Per-tick no-dedup invariant (Q1 Reading A)
# ---------------------------------------------------------------------------


async def test_record_spin_rationale_no_dedup_emits_per_tick(
    storage: SqliteStorage,
) -> None:
    """Three identical-tuple calls produce three rows. Q1 Reading A
    invariant: per-tick emission, no dedup. Faithful timeline preserves
    operator-relevant oscillation signals that dedup would hide.
    Future-Claude reading this test should NOT introduce server-side
    dedup without revisiting Q1 disposition first."""
    for _ in range(3):
        await record_spin_rationale(
            storage=storage,
            request=RecordSpinRationaleInput(
                decision="kept_alive",
                rationale_tag="queued_work",
            ),
            caller_role="pm",
        )

    rows = await storage.fetch_all(
        "SELECT id FROM pm_spin_rationale_log"
    )
    assert len(rows) == 3, (
        "record_spin_rationale collapsed identical tuples — Q1 "
        "Reading A disposition (per-tick, no dedup) was reverted "
        "without surfacing. Three identical (decision, rationale_tag) "
        "calls must produce three rows; durable timeline preserves "
        "operator-relevant oscillation signals."
    )


# ---------------------------------------------------------------------------
# Permission: non-PM callers rejected
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "non_pm_role", ["engineer", "implementer", "tester"]
)
async def test_record_spin_rationale_non_pm_role_rejected(
    storage: SqliteStorage,
    non_pm_role: str,
) -> None:
    """Engineer, Implementer, and Tester callers are rejected at the
    handler's _require_role check. No row is written — the rejection
    happens before the INSERT."""
    with pytest.raises(OrchestratorPermissionError, match=non_pm_role):
        await record_spin_rationale(
            storage=storage,
            request=RecordSpinRationaleInput(
                decision="kept_alive",
                rationale_tag="queued_work",
            ),
            caller_role=non_pm_role,
        )

    rows = await storage.fetch_all(
        "SELECT id FROM pm_spin_rationale_log"
    )
    assert rows == []


# ---------------------------------------------------------------------------
# Audit-log non-integration (Q6 Reading A invariant)
# ---------------------------------------------------------------------------


async def test_record_spin_rationale_does_not_write_audit_log(
    storage: SqliteStorage,
) -> None:
    """record_spin_rationale must NOT write audit_log rows per Q6
    Reading A (F4.5.3 reasoning): pm_spin_rationale_log IS itself the
    durable record; cross-recording would create two-write-paths with
    divergence risk and operator confusion about canonical source.

    Sentinel test pins the contract — future-Claude introducing audit
    integration without revisiting the Q6 disposition would fail this
    test loudly. The test name and rationale explain why the
    no-audit-write is deliberate, not omission."""
    await record_spin_rationale(
        storage=storage,
        request=RecordSpinRationaleInput(
            decision="shutdown_recommended",
            rationale_tag="forgot_to_shutdown",
        ),
        caller_role="pm",
    )

    audit_rows = await storage.fetch_all(
        "SELECT id, action FROM audit_log "
        "WHERE action LIKE 'pm.spin_rationale.%' "
        "OR action LIKE 'pm_spin.%'"
    )
    assert audit_rows == [], (
        "record_spin_rationale wrote audit_log rows; Q6 Reading A "
        "disposition (F4.5.3 reasoning: pm_spin_rationale_log IS the "
        "durable record; cross-recording creates divergence risk + "
        "operator confusion about canonical source) was reverted "
        "without surfacing."
    )
