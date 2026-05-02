"""Direct-handler tests for ``tools.pm_mode.set_pm_mode``.

Exercises :func:`meridian_orchestrator.tools.pm_mode.set_pm_mode`
against a migrated SQLite DB. Covers:

- Happy path: a transition writes a pm_mode_log row + audit row.
- No-op: same-mode write skips the INSERT but still audits.
- Permission: non-PM callers rejected at the handler layer.
- Transactional rollback: an audit-write failure rolls back the
  preceding INSERT (same anti-drift discipline as
  ``test_record_api_call_rolls_back_insert_on_update_failure``).

Plus three durable contract tests encoding the commit 4 prep
dispositions:

- ``test_pm_initiated_set_pm_mode_writes_triggered_by_manual`` (Q1):
  PM-initiated rows have ``triggered_by='manual'``; never null.
- ``test_no_op_set_pm_mode_still_emits_audit_with_transition_logged_false``
  (Q2): no-op writes still audit; the diagnostic-visibility contract
  for PM's continuous-loop decision frequency is preserved.
- ``test_first_ever_set_pm_mode_always_logs_with_transition_logged_true``
  (Q3): empty pm_mode_log treated as "no current mode"; first-ever
  call always logs regardless of mode value.

End-to-end MCP wrapper tests live in
``tests/integration/test_pm_mode_mcp_wrapper.py``.
"""

from __future__ import annotations

import json
from collections.abc import AsyncIterator, Sequence
from pathlib import Path
from typing import Any

import pytest
import pytest_asyncio

from meridian_orchestrator.errors import (
    PermissionError as OrchestratorPermissionError,
)
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.tools.pm_mode import (
    SetPmModeInput,
    set_pm_mode,
)


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


# ---------------------------------------------------------------------------
# Happy path: transition writes pm_mode_log row
# ---------------------------------------------------------------------------


async def test_set_pm_mode_writes_pm_mode_log_row(
    storage: SqliteStorage,
) -> None:
    """A first-time PM-initiated transition writes a pm_mode_log row
    with the correct mode, reason, and triggered_by='manual'."""
    out = await set_pm_mode(
        storage=storage,
        request=SetPmModeInput(
            mode="paused_question", reason="surfacing blocker to mike"
        ),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    assert out.mode == "paused_question"
    assert out.transition_logged is True

    rows = await storage.fetch_all(
        "SELECT mode, reason, triggered_by FROM pm_mode_log"
    )
    assert len(rows) == 1
    assert rows[0]["mode"] == "paused_question"
    assert rows[0]["reason"] == "surfacing blocker to mike"
    assert rows[0]["triggered_by"] == "manual"


async def test_set_pm_mode_returns_transition_logged_true_on_change(
    storage: SqliteStorage,
) -> None:
    """A second call with a different mode writes another row and
    returns transition_logged=True."""
    await set_pm_mode(
        storage=storage,
        request=SetPmModeInput(mode="continuous"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )
    out = await set_pm_mode(
        storage=storage,
        request=SetPmModeInput(mode="interactive"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    assert out.transition_logged is True
    rows = await storage.fetch_all(
        "SELECT mode FROM pm_mode_log ORDER BY rowid"
    )
    assert [r["mode"] for r in rows] == ["continuous", "interactive"]


# ---------------------------------------------------------------------------
# No-op: same-mode write
# ---------------------------------------------------------------------------


async def test_set_pm_mode_no_op_when_already_in_mode(
    storage: SqliteStorage,
) -> None:
    """A second call with the same mode skips the INSERT and returns
    transition_logged=False. The pm_mode_log row count stays at 1."""
    await set_pm_mode(
        storage=storage,
        request=SetPmModeInput(mode="continuous"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )
    out = await set_pm_mode(
        storage=storage,
        request=SetPmModeInput(mode="continuous"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    assert out.transition_logged is False
    rows = await storage.fetch_all("SELECT id FROM pm_mode_log")
    assert len(rows) == 1


# ---------------------------------------------------------------------------
# Audit row shape
# ---------------------------------------------------------------------------


async def test_set_pm_mode_writes_audit_row_with_transition_action(
    storage: SqliteStorage,
) -> None:
    """The handler emits a pm_mode.transition audit row with the
    actor=caller_agent_id, target=pm_mode_log_id (or null on no-op),
    and details={old_mode, new_mode, reason, transition_logged,
    pm_mode_log_id}."""
    out = await set_pm_mode(
        storage=storage,
        request=SetPmModeInput(
            mode="paused_blocked", reason="waiting on Mike's reply"
        ),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    audits = await storage.fetch_all(
        "SELECT actor, action, target, details_json "
        "FROM audit_log WHERE action = 'pm_mode.transition'"
    )
    assert len(audits) == 1
    audit = audits[0]
    assert audit["actor"] == "pm-agent"
    assert audit["action"] == "pm_mode.transition"
    # target is the pm_mode_log row id (transition was logged).
    assert audit["target"] is not None

    details = json.loads(audit["details_json"])
    assert details["old_mode"] is None  # empty pm_mode_log → None
    assert details["new_mode"] == "paused_blocked"
    assert details["reason"] == "waiting on Mike's reply"
    assert details["transition_logged"] is True
    # pm_mode_log_id round-trips through the audit details.
    assert details["pm_mode_log_id"] == audit["target"]
    # And separately verify against the actual pm_mode_log row.
    row = await storage.fetch_one("SELECT id FROM pm_mode_log")
    assert row is not None
    assert details["pm_mode_log_id"] == row["id"]
    assert out.transition_logged is True


# ---------------------------------------------------------------------------
# Permission: non-PM callers rejected
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "non_pm_role", ["engineer", "implementer", "tester"]
)
async def test_set_pm_mode_rejects_non_pm_callers(
    storage: SqliteStorage, non_pm_role: str
) -> None:
    """The handler enforces PM-only via _require_role. Engineer,
    Implementer, and Tester all get OrchestratorPermissionError;
    no pm_mode_log or audit rows are written."""
    with pytest.raises(
        OrchestratorPermissionError, match=non_pm_role
    ):
        await set_pm_mode(
            storage=storage,
            request=SetPmModeInput(mode="continuous"),
            caller_agent_id=f"{non_pm_role}-agent",
            caller_role=non_pm_role,
        )

    # No state changes on rejected calls.
    rows = await storage.fetch_all("SELECT id FROM pm_mode_log")
    assert rows == []
    audits = await storage.fetch_all(
        "SELECT id FROM audit_log WHERE action = 'pm_mode.transition'"
    )
    assert audits == []


# ---------------------------------------------------------------------------
# Transactional rollback regression
# ---------------------------------------------------------------------------


async def test_set_pm_mode_rolls_back_insert_on_audit_failure(
    storage: SqliteStorage, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Forcing an audit-write failure mid-handler rolls back the
    preceding pm_mode_log INSERT. Without transaction wrapping, the
    pm_mode_log row would persist while audit_log disagreed —
    operator-visible state lying about what was logged.

    Same anti-drift discipline as
    ``test_record_api_call_rolls_back_insert_on_update_failure`` —
    a future commit that drops the ``async with storage.transaction()``
    wrapper from set_pm_mode fails this test informatively.
    """
    real_execute = storage.execute

    async def failing_execute(
        query: str, params: Sequence[Any] | None = None
    ) -> None:
        if query.lstrip().upper().startswith("INSERT INTO AUDIT_LOG"):
            raise RuntimeError(
                "simulated audit write failure for rollback regression"
            )
        await real_execute(query, params)

    monkeypatch.setattr(storage, "execute", failing_execute)

    with pytest.raises(RuntimeError, match="simulated audit write failure"):
        await set_pm_mode(
            storage=storage,
            request=SetPmModeInput(mode="paused_question"),
            caller_agent_id="pm-agent",
            caller_role="pm",
        )

    # pm_mode_log row must NOT exist — the INSERT was rolled back when
    # the audit write failed inside the same transaction.
    rows = await storage.fetch_all("SELECT id FROM pm_mode_log")
    assert rows == [], (
        "pm_mode_log row persisted despite audit write failure; the "
        "storage.transaction() wrapper around INSERT+audit in "
        "set_pm_mode has been removed or is not rolling back. "
        "Without rollback: pm_mode_log claims a transition that the "
        "audit_log doesn't reflect, breaking operator-visible state "
        "consistency."
    )


# ---------------------------------------------------------------------------
# Q1 contract: triggered_by='manual' for PM-initiated writes
# ---------------------------------------------------------------------------


async def test_pm_initiated_set_pm_mode_writes_triggered_by_manual(
    storage: SqliteStorage,
) -> None:
    """PM-initiated transitions via set_pm_mode write pm_mode_log rows
    with triggered_by='manual'. Encodes the Q1 disposition from
    commit 4 prep: 'manual' is the deliberate enum value for
    set_pm_mode-initiated writes; null would leave 'manual'
    permanently dead-letter and break the operator query
    ``WHERE triggered_by = 'manual'`` for filtering PM-deliberate
    writes from daemon-initiated writes.
    """
    await set_pm_mode(
        storage=storage,
        request=SetPmModeInput(mode="continuous"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )
    await set_pm_mode(
        storage=storage,
        request=SetPmModeInput(mode="paused_blocked"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    rows = await storage.fetch_all(
        "SELECT triggered_by FROM pm_mode_log ORDER BY rowid"
    )
    assert len(rows) == 2
    for row in rows:
        assert row["triggered_by"] == "manual", (
            f"PM-initiated set_pm_mode wrote triggered_by="
            f"{row['triggered_by']!r} instead of 'manual'. "
            "Operators querying WHERE triggered_by = 'manual' will "
            "see empty results, defeating the auto-vs-deliberate "
            "filter the Q1 disposition encoded into the schema."
        )


# ---------------------------------------------------------------------------
# Q2 contract: audit fires even on no-op
# ---------------------------------------------------------------------------


async def test_no_op_set_pm_mode_still_emits_audit_with_transition_logged_false(
    storage: SqliteStorage,
) -> None:
    """A no-op set_pm_mode call (mode == current mode) still emits a
    pm_mode.transition audit row, with details.transition_logged
    = false and details.pm_mode_log_id = null. Encodes the Q2
    disposition: PM's continuous-loop logic will make speculative
    set_pm_mode calls; audit-on-no-op gives operators visibility
    into PM's decision frequency, not just transition events.
    The transition_logged flag preserves the distinction at query
    time."""
    # Establish current mode = continuous.
    await set_pm_mode(
        storage=storage,
        request=SetPmModeInput(mode="continuous"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )
    # No-op: request continuous again.
    out = await set_pm_mode(
        storage=storage,
        request=SetPmModeInput(mode="continuous", reason="loop tick"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    assert out.transition_logged is False

    audits = await storage.fetch_all(
        "SELECT target, details_json FROM audit_log "
        "WHERE action = 'pm_mode.transition' ORDER BY rowid"
    )
    # Two audit rows: one for the first transition, one for the no-op.
    assert len(audits) == 2, (
        f"expected exactly two pm_mode.transition audit rows (one for "
        f"the initial transition, one for the no-op), got "
        f"{len(audits)}. The Q2 contract is broken: no-op set_pm_mode "
        "calls must still emit an audit row so operators tracking "
        "PM's set_pm_mode decision frequency see all calls, not just "
        "transitions."
    )
    no_op_audit = audits[1]
    assert no_op_audit["target"] is None, (
        "no-op audit row's target is non-null; target should be null "
        "when no pm_mode_log row was inserted. The handler is "
        "incorrectly populating target on the no-op path."
    )
    details = json.loads(no_op_audit["details_json"])
    assert details["transition_logged"] is False, (
        "no-op audit row's details.transition_logged is True; the "
        "diagnostic flag distinguishing transitions from no-ops at "
        "query time has drifted from its contract."
    )
    assert details["pm_mode_log_id"] is None
    assert details["new_mode"] == "continuous"
    assert details["old_mode"] == "continuous"


# ---------------------------------------------------------------------------
# Q3 contract: empty pm_mode_log → first call always logs
# ---------------------------------------------------------------------------


async def test_first_ever_set_pm_mode_always_logs_with_transition_logged_true(
    storage: SqliteStorage,
) -> None:
    """When pm_mode_log is empty (fresh DB or new operator), the
    first-ever set_pm_mode call always produces transition_logged=True
    regardless of which mode is requested. Encodes the Q3
    disposition: empty pm_mode_log means 'no report yet' — explicit
    semantics encoded in the schema, not tribal knowledge about
    implicit continuous-default. Treating current as None means any
    requested mode != None → log.

    Failure mode this test prevents: a future commit that introduces
    an implicit-default-is-continuous baseline (e.g.,
    ``current_mode = current_row['mode'] if current_row else 'continuous'``)
    would silently no-op the first-ever continuous request, leaving
    the operator without a startup audit trail. The implicit-default
    pattern is fragile because it requires every reader of pm_mode_log
    to know about the implicit default; explicit None is safer.
    """
    pre_rows = await storage.fetch_all("SELECT id FROM pm_mode_log")
    assert pre_rows == []  # confirm starting empty

    # First-ever call to 'continuous' (the spec's natural default).
    out = await set_pm_mode(
        storage=storage,
        request=SetPmModeInput(mode="continuous"),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )
    assert out.transition_logged is True, (
        "first-ever set_pm_mode call with mode='continuous' returned "
        "transition_logged=False; the handler is treating 'continuous' "
        "as an implicit default rather than the empty pm_mode_log as "
        "'no current mode'. The Q3 disposition specifies None baseline "
        "— explicit no-current-mode semantics, not implicit-default-"
        "is-continuous. An implicit default would require every reader "
        "of pm_mode_log to share the convention."
    )

    rows = await storage.fetch_all("SELECT mode FROM pm_mode_log")
    assert len(rows) == 1
    assert rows[0]["mode"] == "continuous"
