"""Direct-handler tests for ``audit.sampler``.

Exercises :func:`audit.sampler._sample_decision` (pure function),
:class:`audit.sampler.Sampler` (writer + percentage validation), and
:func:`audit.sampler.record_sampleable_event` (handler + role gate)
against a migrated SQLite DB. Covers:

- ``_sample_decision`` boundary behavior at 0%, 100%, mid-range with
  fixed-seed Random for deterministic test (Q8 Reading A coin-flip
  algorithm).
- ``Sampler`` constructor range validation ([0.0, 1.0]).
- ``Sampler.maybe_sample`` skip vs write paths.
- ``record_sampleable_event`` end-to-end through each of the 5
  source_event_kinds (parametrized).
- Permission: non-PM callers rejected at the handler layer.
- Audit-log non-integration sentinel (Q6 Reading A invariant pin —
  pattern continuation from commit 3 :func:`tools.pm_spin`).
- Default percentage 0.0 invariant pin (Q9 Reading A — defensive
  sentinel against future "helpful" enable-by-default).

Permission tests at the MCP wrapper layer + Pydantic boundary tests
live in ``tests/integration/test_audit_sample_mcp_wrapper.py``.
"""

from __future__ import annotations

import random
from collections.abc import AsyncIterator
from pathlib import Path

import pytest
import pytest_asyncio

from meridian_orchestrator.audit.sampler import (
    RecordSampleableEventInput,
    Sampler,
    _sample_decision,
    record_sampleable_event,
)
from meridian_orchestrator.config import Settings
from meridian_orchestrator.errors import (
    PermissionError as OrchestratorPermissionError,
)
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


# ---------------------------------------------------------------------------
# _sample_decision pure function (Q8 Reading A coin-flip algorithm)
# ---------------------------------------------------------------------------


def test_sample_decision_zero_percent_returns_false() -> None:
    """0.0 percentage always returns False — fast-path; no rng call.
    The default-disabled-config case (Settings.audit_sample_percentage
    default per Q9 Reading A) hits this path on every
    record_sampleable_event invocation; skipping rng work eliminates
    measurable per-call cost at high event rates."""
    rng = random.Random(42)  # would advance state if hit
    assert _sample_decision(0.0, rng=rng) is False
    assert _sample_decision(0.0, rng=rng) is False
    assert _sample_decision(0.0, rng=rng) is False


def test_sample_decision_one_hundred_percent_returns_true() -> None:
    """1.0 percentage always returns True — fast-path; no rng call."""
    rng = random.Random(42)  # would advance state if hit
    assert _sample_decision(1.0, rng=rng) is True
    assert _sample_decision(1.0, rng=rng) is True


def test_sample_decision_with_seed_deterministic() -> None:
    """Fixed-seed Random produces reproducible coin-flip sequence at
    mid-range percentages. Statistical test: at 50% over 1000 samples
    with a known seed, the output sequence is deterministic and the
    sample fraction approximates 0.5 (within tolerance)."""
    rng = random.Random(42)
    samples = [_sample_decision(0.5, rng=rng) for _ in range(1000)]
    sampled_count = sum(1 for s in samples if s)
    # With seed=42, the sample should be near 50% — checking [400, 600]
    # gives a ~3-sigma tolerance for a binomial with p=0.5, n=1000.
    assert 400 <= sampled_count <= 600, (
        f"Unexpected sample count {sampled_count} at p=0.5 over n=1000 "
        "with seed=42. Either the underlying random implementation "
        "changed or the coin-flip algorithm regressed."
    )


# ---------------------------------------------------------------------------
# Sampler constructor range validation
# ---------------------------------------------------------------------------


def test_sampler_constructor_rejects_negative_percentage() -> None:
    """Sampler validates [0.0, 1.0] range. Defense-in-depth alongside
    Settings.audit_sample_percentage's pydantic ge=0.0 le=1.0
    constraint — direct callers (test fixtures) bypass Settings, so
    constructor-side validation closes the gap."""
    with pytest.raises(ValueError, match=r"sampling_percentage"):
        Sampler(-0.1)


def test_sampler_constructor_rejects_greater_than_one() -> None:
    with pytest.raises(ValueError, match=r"sampling_percentage"):
        Sampler(1.5)


def test_sampler_constructor_accepts_boundaries() -> None:
    """0.0 and 1.0 are valid (inclusive boundaries)."""
    Sampler(0.0)
    Sampler(1.0)


# ---------------------------------------------------------------------------
# Sampler.maybe_sample writer
# ---------------------------------------------------------------------------


async def test_sampler_maybe_sample_at_zero_returns_none(
    storage: SqliteStorage,
) -> None:
    """At 0% sampling, maybe_sample returns None and writes no row.
    This is the default-disabled-config behavior."""
    sampler = Sampler(0.0)
    result = await sampler.maybe_sample(
        storage,
        source_event_kind="pm.current_focus.completed",
        source_event_id="audit-log-id-1",
    )
    assert result is None
    rows = await storage.fetch_all("SELECT id FROM audit_sample_log")
    assert rows == []


async def test_sampler_maybe_sample_at_one_writes_row(
    storage: SqliteStorage,
) -> None:
    """At 100% sampling, maybe_sample writes a row and returns the
    sample_id. Row has re_eval_state='pending' (default) and the
    correct source_event_kind + source_event_id."""
    sampler = Sampler(1.0)
    result = await sampler.maybe_sample(
        storage,
        source_event_kind="pm.current_focus.contract_violation",
        source_event_id="audit-log-id-42",
    )
    assert result is not None

    rows = await storage.fetch_all(
        "SELECT id, source_event_kind, source_event_id, re_eval_state, "
        "re_eval_finding, re_eval_completed_at FROM audit_sample_log"
    )
    assert len(rows) == 1
    assert rows[0]["id"] == result
    assert rows[0]["source_event_kind"] == "pm.current_focus.contract_violation"
    assert rows[0]["source_event_id"] == "audit-log-id-42"
    assert rows[0]["re_eval_state"] == "pending"
    assert rows[0]["re_eval_finding"] is None
    assert rows[0]["re_eval_completed_at"] is None


@pytest.mark.parametrize(
    "event_kind",
    [
        "pm.current_focus.completed",
        "pm.current_focus.contract_violation",
        "pm.current_focus.superseded",
        "pm.spin_rationale.recorded",
        "dispatch.complete_called",
    ],
)
async def test_sampler_maybe_sample_writes_each_event_kind(
    storage: SqliteStorage,
    event_kind: str,
) -> None:
    """Every spec'd source_event_kind value passes the writer's CHECK
    constraint. Parametrized over all 5 values to verify each flows
    through the full INSERT path. Closed-enum sentinel test
    test_audit_sample_source_event_kind_check_constraint covers the
    DDL-level CHECK; this test covers the writer-level happy path."""
    sampler = Sampler(1.0)
    result = await sampler.maybe_sample(
        storage,
        source_event_kind=event_kind,  # type: ignore[arg-type]
        source_event_id=f"event-{event_kind}",
    )
    assert result is not None

    row = await storage.fetch_one(
        "SELECT source_event_kind FROM audit_sample_log WHERE id = ?",
        (result,),
    )
    assert row is not None
    assert row["source_event_kind"] == event_kind


# ---------------------------------------------------------------------------
# record_sampleable_event handler — permission + invariants
# ---------------------------------------------------------------------------


async def test_record_sampleable_event_writes_via_handler(
    storage: SqliteStorage,
) -> None:
    """End-to-end through the handler: PM caller, sampler at 100%,
    happy-path write."""
    sampler = Sampler(1.0)
    out = await record_sampleable_event(
        storage=storage,
        sampler=sampler,
        request=RecordSampleableEventInput(
            source_event_kind="pm.current_focus.completed",
            source_event_id="audit-row-1",
        ),
        caller_role="pm",
    )
    assert out.sample_id is not None


@pytest.mark.parametrize(
    "non_pm_role", ["engineer", "implementer", "tester"]
)
async def test_record_sampleable_event_non_pm_role_rejected(
    storage: SqliteStorage,
    non_pm_role: str,
) -> None:
    """Engineer, Implementer, Tester callers are rejected at the
    handler's _require_role check. No row is written — rejection
    happens before sampler.maybe_sample is invoked."""
    sampler = Sampler(1.0)
    with pytest.raises(OrchestratorPermissionError, match=non_pm_role):
        await record_sampleable_event(
            storage=storage,
            sampler=sampler,
            request=RecordSampleableEventInput(
                source_event_kind="pm.current_focus.completed",
                source_event_id="audit-row-1",
            ),
            caller_role=non_pm_role,
        )

    rows = await storage.fetch_all("SELECT id FROM audit_sample_log")
    assert rows == []


# ---------------------------------------------------------------------------
# Q6 invariant pin — does NOT write audit_log (pattern from commit 3)
# ---------------------------------------------------------------------------


async def test_record_sampleable_event_does_not_write_audit_log(
    storage: SqliteStorage,
) -> None:
    """record_sampleable_event must NOT write audit_log rows per F5.2
    Q6 Reading A (F4.5.3 reasoning continued from commit 3):
    audit_sample_log IS itself the durable record of what was
    sampled; cross-recording would create two-write-paths with
    divergence risk and operator confusion about canonical source.

    Sentinel test pins the contract — future-Claude introducing audit
    integration without revisiting the F5.2 Q6 disposition would fail
    this test loudly. Mirrors commit 3's
    test_record_spin_rationale_does_not_write_audit_log invariant pin."""
    sampler = Sampler(1.0)
    await record_sampleable_event(
        storage=storage,
        sampler=sampler,
        request=RecordSampleableEventInput(
            source_event_kind="pm.current_focus.completed",
            source_event_id="audit-row-1",
        ),
        caller_role="pm",
    )

    audit_rows = await storage.fetch_all(
        "SELECT id, action FROM audit_log "
        "WHERE action LIKE 'audit_sample.%' "
        "OR action LIKE 'audit.sample.%'"
    )
    assert audit_rows == [], (
        "record_sampleable_event wrote audit_log rows; F5.2 Q6 Reading A "
        "disposition (F4.5.3 reasoning: audit_sample_log IS the "
        "durable record; cross-recording creates divergence risk + "
        "operator confusion about canonical source) was reverted "
        "without surfacing."
    )


# ---------------------------------------------------------------------------
# Q9 invariant pin — Settings default audit_sample_percentage = 0.0
# ---------------------------------------------------------------------------


def test_settings_audit_sample_percentage_default_is_zero(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Settings.audit_sample_percentage defaults to 0.0 per F5.2 Q9
    Reading A — opt-in disabled-by-default shape mirroring
    enable_engineer_supervisor / enable_pm_supervisor flag conventions.

    Defensive sentinel: pins the default against future "helpful"
    auto-enable. Failure here means the default was changed without
    surfacing F5.2 Q9 re-disposition.

    Uses monkeypatch.delenv to ensure no env var override is in
    effect for this test process."""
    monkeypatch.delenv("MERIDIAN_ORCH_AUDIT_SAMPLE_PERCENTAGE", raising=False)
    settings = Settings()
    assert settings.audit_sample_percentage == 0.0, (
        "Settings.audit_sample_percentage default changed from 0.0 "
        "without surfacing F5.2 Q9 Reading A re-disposition. "
        "Disabled-by-default opt-in shape is the established Phase 1 "
        "flag convention (enable_engineer_supervisor=False, "
        "enable_pm_supervisor=False); auto-enable would ship "
        "behavior the operator didn't ask for."
    )


def test_settings_audit_sample_percentage_rejects_out_of_range(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Settings.audit_sample_percentage has ge=0.0 le=1.0 constraint;
    out-of-range values are rejected at Settings construction time."""
    monkeypatch.setenv("MERIDIAN_ORCH_AUDIT_SAMPLE_PERCENTAGE", "1.5")
    with pytest.raises(Exception, match=r"(?i)less than|le=1"):
        Settings()
    monkeypatch.setenv("MERIDIAN_ORCH_AUDIT_SAMPLE_PERCENTAGE", "-0.1")
    with pytest.raises(Exception, match=r"(?i)greater than|ge=0"):
        Settings()
