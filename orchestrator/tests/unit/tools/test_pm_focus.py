"""Direct-handler tests for ``tools.pm_focus``.

Exercises the three handlers + the ``read_current_focus`` helper
against a migrated SQLite DB. Covers:

- read_current_focus: empty + populated cases.
- declare_current_focus: basic, with bound_workflow_id, auto-supersede
  on prior-active (Q8 Reading A), full audit payload per Q4 Reading A.
- complete_current_focus: validated=True (one audit row),
  validated=False (two audit rows including contract_violation),
  no-active-focus error path.
- Audit-action namespace correctness (sentinel for the four-action
  closed set).

Permission tests + Pydantic boundary tests live in the integration
file ``tests/integration/test_pm_focus_mcp_wrapper.py`` since they
exercise the wrapper's role-resolve path.
"""

from __future__ import annotations

import json
from collections.abc import AsyncIterator
from pathlib import Path

import pytest
import pytest_asyncio

from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.tools.pm_focus import (
    _OBJECTIVE_SUMMARY_MAX_CHARS,
    _PM_FOCUS_AUDIT_ACTIONS,
    CompleteCurrentFocusInput,
    DeclareCurrentFocusInput,
    _truncate_objective,
    complete_current_focus,
    declare_current_focus,
    get_current_focus,
    read_current_focus,
)


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    # Seed a company+project+task so bound_workflow_id tests have a
    # real FK target (Q3 Reading A: FK enforced; bogus ids reject).
    await s.execute(
        "INSERT INTO companies (id, name) VALUES ('co1', 'Meridian AI')"
    )
    await s.execute(
        "INSERT INTO projects (id, company_id, name) VALUES "
        "('proj1', 'co1', 'Substrate Product')"
    )
    await s.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description) VALUES ('wf-root-1', 'co1', 'proj1', 'pm', "
        "'workflow root for tests')"
    )
    yield s
    await s.close()


# ---------------------------------------------------------------------------
# read_current_focus helper — empty + populated
# ---------------------------------------------------------------------------


async def test_read_current_focus_empty_returns_none(
    storage: SqliteStorage,
) -> None:
    """Empty pm_current_focus table returns None — the
    no-current-focus baseline. Mirrors read_current_pm_mode's empty
    behaviour."""
    assert await read_current_focus(storage) is None


async def test_read_current_focus_returns_most_recent_active(
    storage: SqliteStorage,
) -> None:
    """When multiple incomplete rows exist (defensive case — Q8 Reading
    A's at-most-one invariant prevents this in production, but the
    helper is deterministic against legacy data), rowid-DESC ordering
    returns the most recent.

    Also verifies completed rows are excluded (WHERE completed_at IS
    NULL).
    """
    # Insert a completed row (should be skipped).
    await storage.execute(
        "INSERT INTO pm_current_focus "
        "(id, objective, output_type, acceptance_criteria, completed_at) "
        "VALUES ('f-old', 'old objective', 'document', 'criteria', "
        "datetime('now'))"
    )
    # Insert two incomplete rows; the higher rowid (later insert) wins.
    await storage.execute(
        "INSERT INTO pm_current_focus "
        "(id, objective, output_type, acceptance_criteria) "
        "VALUES ('f-mid', 'mid objective', 'document', 'criteria')"
    )
    await storage.execute(
        "INSERT INTO pm_current_focus "
        "(id, objective, output_type, acceptance_criteria) "
        "VALUES ('f-new', 'new objective', 'code_change', 'criteria')"
    )

    current = await read_current_focus(storage)
    assert current is not None
    assert current.id == "f-new"
    assert current.output_type == "code_change"


# ---------------------------------------------------------------------------
# declare_current_focus — basic happy path
# ---------------------------------------------------------------------------


async def test_declare_basic_inserts_row_and_audit(
    storage: SqliteStorage,
) -> None:
    """A first-time declare with no prior active focus inserts one
    pm_current_focus row + emits exactly one audit row
    (pm.current_focus.declared). superseded_prior_focus_id is None."""
    out = await declare_current_focus(
        storage=storage,
        request=DeclareCurrentFocusInput(
            objective="ship the X feature",
            output_type="code_change",
            acceptance_criteria="green CI; review approved",
        ),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    assert out.focus_id is not None
    assert out.superseded_prior_focus_id is None

    # Exactly one row.
    rows = await storage.fetch_all(
        "SELECT id, objective, output_type, acceptance_criteria, "
        "bound_workflow_id, completed_at "
        "FROM pm_current_focus"
    )
    assert len(rows) == 1
    assert rows[0]["id"] == out.focus_id
    assert rows[0]["objective"] == "ship the X feature"
    assert rows[0]["output_type"] == "code_change"
    assert rows[0]["bound_workflow_id"] is None
    assert rows[0]["completed_at"] is None

    # Exactly one audit row.
    audits = await storage.fetch_all(
        "SELECT action, target FROM audit_log "
        "WHERE action LIKE 'pm.current_focus.%'"
    )
    assert len(audits) == 1
    assert audits[0]["action"] == "pm.current_focus.declared"
    assert audits[0]["target"] == out.focus_id


async def test_declare_with_bound_workflow_id(
    storage: SqliteStorage,
) -> None:
    """bound_workflow_id flows through to the pm_current_focus row and
    into the audit detail per Q4 Reading A. FK constraint (Q3 Reading
    A) accepts the seeded task_id."""
    out = await declare_current_focus(
        storage=storage,
        request=DeclareCurrentFocusInput(
            objective="finish workflow X",
            output_type="document",
            acceptance_criteria="doc landed; reviewer signed off",
            bound_workflow_id="wf-root-1",
        ),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    row = await storage.fetch_one(
        "SELECT bound_workflow_id FROM pm_current_focus "
        "WHERE id = ?",
        (out.focus_id,),
    )
    assert row is not None
    assert row["bound_workflow_id"] == "wf-root-1"

    audit = await storage.fetch_one(
        "SELECT details_json FROM audit_log "
        "WHERE action = 'pm.current_focus.declared' "
        "AND target = ?",
        (out.focus_id,),
    )
    assert audit is not None
    details = json.loads(audit["details_json"])
    assert details["bound_workflow_id"] == "wf-root-1"


# ---------------------------------------------------------------------------
# declare_current_focus — auto-supersede on prior active (Q8 Reading A)
# ---------------------------------------------------------------------------


async def test_declare_while_prior_active_auto_supersedes(
    storage: SqliteStorage,
) -> None:
    """When a prior incomplete focus exists, declare auto-supersedes
    it: prior gets completed_at + contract_finding_text='superseded';
    contract_validated_bool stays NULL. Two audit rows emit in the
    same transaction (superseded then declared). Q8 Reading A
    invariant preserved."""
    first = await declare_current_focus(
        storage=storage,
        request=DeclareCurrentFocusInput(
            objective="first focus",
            output_type="review",
            acceptance_criteria="initial criteria",
        ),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    second = await declare_current_focus(
        storage=storage,
        request=DeclareCurrentFocusInput(
            objective="second focus, mike pivoted",
            output_type="investigation",
            acceptance_criteria="new criteria",
        ),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    assert second.superseded_prior_focus_id == first.focus_id

    # Prior row: completed_at set; contract_finding_text='superseded';
    # contract_validated_bool NULL (supersede is not a validation
    # outcome).
    prior = await storage.fetch_one(
        "SELECT completed_at, contract_validated_bool, "
        "contract_finding_text FROM pm_current_focus WHERE id = ?",
        (first.focus_id,),
    )
    assert prior is not None
    assert prior["completed_at"] is not None
    assert prior["contract_validated_bool"] is None
    assert prior["contract_finding_text"] == "superseded"

    # Second row: declared; not yet completed.
    new_row = await storage.fetch_one(
        "SELECT completed_at FROM pm_current_focus WHERE id = ?",
        (second.focus_id,),
    )
    assert new_row is not None
    assert new_row["completed_at"] is None

    # Two audit rows from the second declare: superseded then declared.
    # (One audit row from the first declare, predating the supersede.)
    audits = await storage.fetch_all(
        "SELECT action, target FROM audit_log "
        "WHERE action LIKE 'pm.current_focus.%' "
        "ORDER BY rowid"
    )
    actions = [(a["action"], a["target"]) for a in audits]
    assert actions == [
        ("pm.current_focus.declared", first.focus_id),
        ("pm.current_focus.superseded", first.focus_id),
        ("pm.current_focus.declared", second.focus_id),
    ]


async def test_superseded_audit_payload_full_per_q4(
    storage: SqliteStorage,
) -> None:
    """Q4 Reading A specifies superseded payload as
    {focus_id, superseded_at, superseded_by_focus_id,
    prior_objective_summary}. Sentinel test pins the contract."""
    first = await declare_current_focus(
        storage=storage,
        request=DeclareCurrentFocusInput(
            objective=(
                "A long prior objective spanning multiple work-units "
                "of effort that PM had been pursuing before Mike "
                "interrupted with a reprioritization"
            ),
            output_type="document",
            acceptance_criteria="doc landed",
        ),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    second = await declare_current_focus(
        storage=storage,
        request=DeclareCurrentFocusInput(
            objective="urgent pivot",
            output_type="decision",
            acceptance_criteria="decision recorded",
        ),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    superseded_audit = await storage.fetch_one(
        "SELECT details_json FROM audit_log "
        "WHERE action = 'pm.current_focus.superseded' "
        "AND target = ?",
        (first.focus_id,),
    )
    assert superseded_audit is not None
    details = json.loads(superseded_audit["details_json"])

    # Q4 Reading A spec: four required keys.
    assert set(details.keys()) == {
        "focus_id",
        "superseded_at",
        "superseded_by_focus_id",
        "prior_objective_summary",
    }
    assert details["focus_id"] == first.focus_id
    assert details["superseded_by_focus_id"] == second.focus_id
    assert details["superseded_at"] is not None
    # prior_objective_summary is the truncated prior objective.
    assert details["prior_objective_summary"].startswith(
        "A long prior objective"
    )


# ---------------------------------------------------------------------------
# complete_current_focus
# ---------------------------------------------------------------------------


async def test_complete_basic_validated_true_audit_only_completed(
    storage: SqliteStorage,
) -> None:
    """contract_validated=True path: one audit row
    (pm.current_focus.completed). No contract_violation row. Row's
    contract_validated_bool=1, contract_finding_text=None."""
    declared = await declare_current_focus(
        storage=storage,
        request=DeclareCurrentFocusInput(
            objective="ship feature",
            output_type="code_change",
            acceptance_criteria="tests pass",
        ),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    out = await complete_current_focus(
        storage=storage,
        request=CompleteCurrentFocusInput(contract_validated=True),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    assert out.focus_id == declared.focus_id
    assert out.contract_validated is True

    row = await storage.fetch_one(
        "SELECT completed_at, contract_validated_bool, "
        "contract_finding_text FROM pm_current_focus WHERE id = ?",
        (declared.focus_id,),
    )
    assert row is not None
    assert row["completed_at"] is not None
    assert row["contract_validated_bool"] == 1
    assert row["contract_finding_text"] is None

    # Exactly one completed-related audit row (declared row exists from
    # the prior declare; no contract_violation row).
    audits = await storage.fetch_all(
        "SELECT action FROM audit_log "
        "WHERE action LIKE 'pm.current_focus.%' "
        "ORDER BY rowid"
    )
    actions = [a["action"] for a in audits]
    assert actions == [
        "pm.current_focus.declared",
        "pm.current_focus.completed",
    ]


async def test_complete_validated_false_emits_contract_violation_audit_too(
    storage: SqliteStorage,
) -> None:
    """contract_validated=False emits BOTH pm.current_focus.completed
    AND pm.current_focus.contract_violation in the same transaction
    per Q4 Reading A. Row records contract_validated_bool=0,
    contract_finding_text from the request."""
    await declare_current_focus(
        storage=storage,
        request=DeclareCurrentFocusInput(
            objective="ship feature Y",
            output_type="code_change",
            acceptance_criteria="all tests green; review approved",
        ),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    out = await complete_current_focus(
        storage=storage,
        request=CompleteCurrentFocusInput(
            contract_validated=False,
            finding_text="review still pending; partial coverage",
            actual_output_summary="3 of 5 acceptance criteria met",
        ),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    assert out.contract_validated is False

    row = await storage.fetch_one(
        "SELECT contract_validated_bool, contract_finding_text "
        "FROM pm_current_focus WHERE id = ?",
        (out.focus_id,),
    )
    assert row is not None
    assert row["contract_validated_bool"] == 0
    assert (
        row["contract_finding_text"]
        == "review still pending; partial coverage"
    )

    audits = await storage.fetch_all(
        "SELECT action, details_json FROM audit_log "
        "WHERE action LIKE 'pm.current_focus.%' "
        "ORDER BY rowid"
    )
    actions = [a["action"] for a in audits]
    assert actions == [
        "pm.current_focus.declared",
        "pm.current_focus.completed",
        "pm.current_focus.contract_violation",
    ]
    # Q4 Reading A: contract_violation payload includes
    # actual_output_summary.
    violation_details = json.loads(audits[2]["details_json"])
    assert violation_details["contract_validated"] is False
    assert (
        violation_details["actual_output_summary"]
        == "3 of 5 acceptance criteria met"
    )


async def test_complete_when_no_active_focus_raises(
    storage: SqliteStorage,
) -> None:
    """complete_current_focus with no active focus raises ValueError.
    PM's loop is expected to declare-then-complete; calling complete
    without a prior declare is a contract violation in the loop's
    logic, not a silent no-op."""
    with pytest.raises(ValueError, match="no active pm_current_focus"):
        await complete_current_focus(
            storage=storage,
            request=CompleteCurrentFocusInput(contract_validated=True),
            caller_agent_id="pm-agent",
            caller_role="pm",
        )


# ---------------------------------------------------------------------------
# get_current_focus handler (direct, bypasses MCP wrapper)
# ---------------------------------------------------------------------------


async def test_get_current_focus_returns_active(
    storage: SqliteStorage,
) -> None:
    """Direct-handler: returns GetCurrentFocusOutput with the active
    focus when one exists. Wrapper-layer permission tests live in the
    integration file."""
    declared = await declare_current_focus(
        storage=storage,
        request=DeclareCurrentFocusInput(
            objective="active focus",
            output_type="review",
            acceptance_criteria="review complete",
        ),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    out = await get_current_focus(storage=storage, caller_role="pm")
    assert out.current_focus is not None
    assert out.current_focus.id == declared.focus_id
    assert out.current_focus.objective == "active focus"
    assert out.current_focus.output_type == "review"
    assert out.current_focus.completed_at is None


async def test_get_current_focus_returns_none_when_empty(
    storage: SqliteStorage,
) -> None:
    """Empty table → GetCurrentFocusOutput(current_focus=None). The
    discriminated container shape lets the loop driver distinguish
    'no focus declared yet' from 'RPC failed' cleanly."""
    out = await get_current_focus(storage=storage, caller_role="pm")
    assert out.current_focus is None


# ---------------------------------------------------------------------------
# Audit-action namespace sentinel
# ---------------------------------------------------------------------------


def test_pm_focus_audit_actions_namespace_correct() -> None:
    """The four-action closed set per Q4 Reading A + Q8 Reading A.
    Sentinel test pins the namespace; failure here means the constants
    drifted from the spec amendment in 11-engineer-bridge.md § Audit
    log entries.

    Mirrors the sentinel pattern from M5-subscription-routing
    (_PRIORITY_TIER_DELTA / _TIER_HIERARCHY / _AGENT_ROLE_SUBTREE).
    """
    assert _PM_FOCUS_AUDIT_ACTIONS == {
        "declared": "pm.current_focus.declared",
        "completed": "pm.current_focus.completed",
        "contract_violation": "pm.current_focus.contract_violation",
        "superseded": "pm.current_focus.superseded",
    }


# ---------------------------------------------------------------------------
# Boundary-aware truncation (F3.6 commit-2 Item 2 pushback resolution)
# ---------------------------------------------------------------------------


def test_truncate_objective_under_budget_returns_unchanged() -> None:
    """Objectives within budget pass through unchanged. Pre-pushback
    pass-through baseline."""
    short = "Ship X feature."
    assert _truncate_objective(short) == short


def test_truncate_objective_prefers_sentence_boundary() -> None:
    """When a period+space boundary exists within the budget, truncate
    there. Cleanest at-a-glance read — truncation point coincides with
    a natural sentence pause."""
    # Construct an objective where the last ". " inside the budget is
    # well before the budget edge, demonstrating sentence-boundary
    # preference over whitespace-boundary fallback.
    head = "First sentence ends here. Second sentence starts here and continues with content. "
    tail = "x" * (_OBJECTIVE_SUMMARY_MAX_CHARS - len(head) + 50)
    objective = head + tail
    out = _truncate_objective(objective)
    # Should end with the second sentence's period (the last ". " inside
    # the budget) plus the ellipsis suffix.
    assert out.endswith("content....") or out.endswith("here....")
    assert "..." in out
    assert len(out) <= _OBJECTIVE_SUMMARY_MAX_CHARS


def test_truncate_objective_falls_back_to_whitespace_boundary() -> None:
    """When no period+space boundary exists within the budget but
    whitespace does, truncate at the last whitespace. Prevents
    mid-word truncation."""
    # Construct an objective with no periods but plenty of whitespace.
    objective = "ship " * 100  # 500 chars of "ship " repeated
    out = _truncate_objective(objective)
    assert out.endswith("...")
    # The truncated head (without the "..." suffix) must NOT end
    # mid-word; since the input is "ship " repeated, the head should
    # end after a "ship" token (with the trailing space stripped).
    head_no_ellipsis = out[:-3]
    assert head_no_ellipsis.endswith("ship") or head_no_ellipsis.endswith("ship ")
    assert len(out) <= _OBJECTIVE_SUMMARY_MAX_CHARS


def test_truncate_objective_hard_truncates_single_word_overflow() -> None:
    """When the objective has neither period nor whitespace within the
    budget (single long word), hard-truncate at budget. Rare in
    practice for natural-language objectives but the function should
    not infinite-loop or raise."""
    objective = "x" * (_OBJECTIVE_SUMMARY_MAX_CHARS * 2)
    out = _truncate_objective(objective)
    assert out.endswith("...")
    assert len(out) <= _OBJECTIVE_SUMMARY_MAX_CHARS
    # Hard-truncate produces budget-3 'x' characters + '...'.
    assert out == "x" * (_OBJECTIVE_SUMMARY_MAX_CHARS - 3) + "..."
