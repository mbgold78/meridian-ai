"""Tests for the forward-only migration runner."""

from __future__ import annotations

from collections.abc import AsyncIterator
from pathlib import Path

import pytest
import pytest_asyncio

from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage

# Shipped migrations:
#   0001_initial.sql                          (M1)
#   0002_task_planning.sql                    (M4a)
#   0003_pm_mode.sql                          (M5-pm-mode-and-window-state)
#   0005_dispatches.sql                       (M4a)
#   0006_dispatches_last_tool_called.sql      (M4c-supervisor-base)
#   0007_dispatches_task_id_nullable.sql      (M4c-supervisor-base)
#   0008_dispatches_status_check.sql          (M4c-compaction)
#   0009_observability_cost_tier1.sql         (M5-subscription-routing)
#   0010_pm_current_focus.sql                 (M5-continuous-pm-driver commit 2)
#   0011_pm_spin_rationale_log.sql             (M5-continuous-pm-driver commit 3)
#   0012_audit_sample_log.sql                  (M5-continuous-pm-driver commit 4)
#   0013_tasks_task_type_and_status_check.sql  (M4d-task-management-surface commit 1)
#   0014_canonical_clones.sql                  (M5-canonical-clone-mgmt slice 1)
#   0015_transient_tokens.sql                  (M5-sqlite-transient-token-store slice 1a)
# Reservation gap remaining: 0004 reserved for inbound_messages +
# outbound_messages (Telegram notifications); milestone-attribution
# pending resolution between data-model.md (M6) and the M5 sub-milestone
# breakdown memory (M5-telegram-foundation).
EXPECTED_VERSIONS = [1, 2, 3, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15]


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "test.db")
    await s.connect()
    yield s
    await s.close()


async def test_run_migrations_creates_tables(storage: SqliteStorage) -> None:
    applied = await run_migrations(storage)
    assert applied == EXPECTED_VERSIONS

    rows = await storage.fetch_all(
        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
    )
    table_names = {row["name"] for row in rows}
    expected = {
        "companies",
        "projects",
        "agents",
        "tasks",
        "api_usage",
        "audit_log",
        "dispatches",
        "pm_mode_log",
        "pm_current_focus",
        "pm_spin_rationale_log",
        "audit_sample_log",
        "_schema_version",
    }
    missing = expected - table_names
    assert not missing, f"Missing tables: {missing}"


async def test_run_migrations_idempotent(storage: SqliteStorage) -> None:
    first = await run_migrations(storage)
    second = await run_migrations(storage)
    assert first == EXPECTED_VERSIONS
    assert second == []


async def test_schema_version_records_applied(storage: SqliteStorage) -> None:
    await run_migrations(storage)
    rows = await storage.fetch_all(
        "SELECT version FROM _schema_version ORDER BY version"
    )
    assert [r["version"] for r in rows] == EXPECTED_VERSIONS


async def test_indexes_created(storage: SqliteStorage) -> None:
    await run_migrations(storage)
    rows = await storage.fetch_all(
        "SELECT name FROM sqlite_master WHERE type='index' "
        "AND name NOT LIKE 'sqlite_%' ORDER BY name"
    )
    index_names = {row["name"] for row in rows}
    expected = {
        # 0001
        "idx_agents_company_role",
        "idx_tasks_status_priority",
        "idx_tasks_agent_role",
        "idx_tasks_parent",
        "idx_api_usage_window",
        "idx_api_usage_agent",
        "idx_audit_timestamp",
        "idx_audit_actor",
        # 0003 — pm_mode_log timestamp index
        "idx_pm_mode_timestamp",
        # 0005 — dispatches indexes
        "idx_dispatches_task",
        "idx_dispatches_status",
        "idx_dispatches_role_status",
        "idx_dispatches_role_completed",
        # 0009 — observability tier-1 cohort indexes on api_usage
        "idx_api_usage_workflow",
        "idx_api_usage_role_type",
        # 0010 — pm_current_focus single index on declared_at (Q5 Reading B
        # disposition; bound_workflow_id index deferred to BACKLOG)
        "idx_pm_focus_declared_at",
        # 0011 — pm_spin_rationale_log single index on iteration_timestamp
        # (mirrors commit 2's Q5 Reading B disposition; rationale_tag and
        # decision cohort indexes deferred to BACKLOG-when-needed)
        "idx_pm_spin_iteration_timestamp",
        # 0012 — audit_sample_log composite index on (re_eval_state,
        # sampled_at) supporting future async re-evaluator's pending-row
        # scan + operator-side time-window queries
        "idx_audit_sample_state_sampled_at",
    }
    missing = expected - index_names
    assert not missing, f"Missing indexes: {missing}"


async def test_tasks_has_token_planning_columns(storage: SqliteStorage) -> None:
    """0002_task_planning.sql adds estimated_tokens and actual_tokens to tasks."""
    await run_migrations(storage)
    cols = await storage.fetch_all("PRAGMA table_info(tasks)")
    col_names = {c["name"] for c in cols}
    assert "estimated_tokens" in col_names
    assert "actual_tokens" in col_names


async def test_dispatches_columns_match_spec(storage: SqliteStorage) -> None:
    """0005_dispatches.sql columns enumerated in 11-engineer-bridge.md § Storage shape."""
    await run_migrations(storage)
    cols = await storage.fetch_all("PRAGMA table_info(dispatches)")
    col_names = {c["name"] for c in cols}
    expected = {
        "id",
        "task_id",
        "role",
        "status",
        "working_directory",
        "spec_path",
        "commit_hash",
        "decline_reason",
        "estimated_tokens",
        "actual_tokens",
        "timeout_seconds",
        "timeout_override_seconds",
        "keep_worktree_on_failure",
        "token_id",
        "subprocess_pid",
        "engineer_decisions_json",
        "issued_at",
        "started_at",
        "last_activity_at",
        "completed_at",
        "timed_out_at",
        "merge_conflict_files_json",
        "actual_touched_files_json",
        "artifacts_json",
        "test_results_json",
        "queue_items_raised_json",
        "notes",
        # 0006 — added at M4c-supervisor-base for the activity writer
        "last_tool_called",
    }
    missing = expected - col_names
    assert not missing, f"Missing columns on dispatches: {missing}"


async def test_role_completed_index_is_partial(storage: SqliteStorage) -> None:
    """idx_dispatches_role_completed has a WHERE status='completed' predicate
    so Engineer's substrate-touch-map scans only walk completed rows."""
    await run_migrations(storage)
    row = await storage.fetch_one(
        "SELECT sql FROM sqlite_master "
        "WHERE type='index' AND name='idx_dispatches_role_completed'"
    )
    assert row is not None
    assert "WHERE status='completed'" in row["sql"]


async def test_pm_mode_log_columns_match_spec(storage: SqliteStorage) -> None:
    """0003_pm_mode.sql columns match 05-data-model.md § pm_mode_log:
    id (PK), timestamp (NOT NULL, default now), mode (NOT NULL with
    enum CHECK), reason (nullable), triggered_by (nullable with enum
    CHECK).
    """
    await run_migrations(storage)
    cols = await storage.fetch_all("PRAGMA table_info(pm_mode_log)")
    by_name = {c["name"]: c for c in cols}

    expected_names = {"id", "timestamp", "mode", "reason", "triggered_by"}
    assert set(by_name.keys()) == expected_names, (
        f"pm_mode_log columns drift: got {set(by_name.keys())!r}, "
        f"expected {expected_names!r}. Schema must match "
        "05-data-model.md § pm_mode_log."
    )

    # PRAGMA's notnull is 1 for NOT NULL, 0 for nullable.
    assert by_name["id"]["pk"] == 1
    assert by_name["timestamp"]["notnull"] == 1
    assert by_name["mode"]["notnull"] == 1
    assert by_name["reason"]["notnull"] == 0
    assert by_name["triggered_by"]["notnull"] == 0


async def test_pm_mode_check_constraint_rejects_invalid_mode(
    storage: SqliteStorage,
) -> None:
    """The mode column's CHECK constraint enforces the spec'd enum at
    write time. Defense-in-depth per 0008_dispatches_status_check.sql's
    precedent — Python literal types catch typos at edit time, the
    CHECK catches the same class of bug from manual SQL or future
    external integrations.
    """
    await run_migrations(storage)
    with pytest.raises(Exception, match=r"(?i)check"):
        await storage.execute(
            "INSERT INTO pm_mode_log (id, mode) VALUES (?, ?)",
            ("test-1", "not-a-real-mode"),
        )


async def test_pm_mode_check_constraint_accepts_all_spec_modes(
    storage: SqliteStorage,
) -> None:
    """Every mode value the spec enumerates must pass the CHECK.
    Failure here means the migration's CHECK list drifted from the
    spec; the writer-layer Python literal type would silently disagree
    with the DB constraint.
    """
    await run_migrations(storage)
    for i, mode in enumerate(
        ["interactive", "continuous", "paused_blocked", "paused_question"]
    ):
        await storage.execute(
            "INSERT INTO pm_mode_log (id, mode) VALUES (?, ?)",
            (f"test-mode-{i}", mode),
        )

    rows = await storage.fetch_all(
        "SELECT mode FROM pm_mode_log ORDER BY id"
    )
    assert {r["mode"] for r in rows} == {
        "interactive",
        "continuous",
        "paused_blocked",
        "paused_question",
    }


async def test_pm_mode_triggered_by_check_rejects_invalid_value(
    storage: SqliteStorage,
) -> None:
    """triggered_by's CHECK uses IS NULL OR IN (...) so NULL passes
    but unknown values reject. Symmetric to the mode-CHECK test."""
    await run_migrations(storage)
    with pytest.raises(Exception, match=r"(?i)check"):
        await storage.execute(
            "INSERT INTO pm_mode_log (id, mode, triggered_by) "
            "VALUES (?, ?, ?)",
            ("test-tb-1", "continuous", "not-a-real-trigger"),
        )


async def test_pm_mode_triggered_by_accepts_null_and_all_spec_values(
    storage: SqliteStorage,
) -> None:
    """NULL passes (default; reason-less daemon-internal flips don't
    need a trigger label) and every spec'd trigger value passes."""
    await run_migrations(storage)
    # NULL triggered_by.
    await storage.execute(
        "INSERT INTO pm_mode_log (id, mode) VALUES (?, ?)",
        ("test-tb-null", "continuous"),
    )
    # Each spec'd value.
    for i, trigger in enumerate(
        ["mike_message", "window_reset", "queue_empty", "rate_limit", "manual"]
    ):
        await storage.execute(
            "INSERT INTO pm_mode_log (id, mode, triggered_by) "
            "VALUES (?, ?, ?)",
            (f"test-tb-{i}", "continuous", trigger),
        )

    rows = await storage.fetch_all(
        "SELECT triggered_by FROM pm_mode_log "
        "WHERE id LIKE 'test-tb-%' ORDER BY id"
    )
    triggers = [r["triggered_by"] for r in rows]
    assert None in triggers
    for expected in [
        "mike_message",
        "window_reset",
        "queue_empty",
        "rate_limit",
        "manual",
    ]:
        assert expected in triggers


async def test_pm_mode_timestamp_default_populates_on_insert(
    storage: SqliteStorage,
) -> None:
    """The timestamp column has DEFAULT (datetime('now')) per the
    migration; INSERTs without an explicit timestamp must populate it
    rather than fail the NOT NULL constraint."""
    await run_migrations(storage)
    await storage.execute(
        "INSERT INTO pm_mode_log (id, mode) VALUES (?, ?)",
        ("test-ts-1", "continuous"),
    )
    row = await storage.fetch_one(
        "SELECT timestamp FROM pm_mode_log WHERE id = 'test-ts-1'"
    )
    assert row is not None
    assert row["timestamp"] is not None
    # SQLite datetime('now') format: 'YYYY-MM-DD HH:MM:SS'.
    assert len(row["timestamp"]) >= 19


async def test_api_usage_has_observability_tier1_columns(
    storage: SqliteStorage,
) -> None:
    """0009_observability_cost_tier1.sql adds notes, workflow_id,
    agent_role, task_type to api_usage. Tier-1 cost-interrogation
    cohort dimensions per BACKLOG § Observability roadmap.

    All four columns are nullable (no NOT NULL, no DEFAULT). Existing
    rows from before the migration remain NULL on agent_role until a
    future hygiene-commit backfill — not load-bearing for routing.
    """
    await run_migrations(storage)
    cols = await storage.fetch_all("PRAGMA table_info(api_usage)")
    by_name = {c["name"]: c for c in cols}

    for col in ("notes", "workflow_id", "agent_role", "task_type"):
        assert col in by_name, (
            f"api_usage column {col!r} missing after 0009 migration. "
            "BACKLOG § Observability roadmap tier-1 cost-interrogation "
            "scaffolding (cohort keys + agent self-annotation) needs "
            "this column to exist for downstream cohort aggregation."
        )
        assert by_name[col]["notnull"] == 0, (
            f"api_usage.{col} marked NOT NULL; the migration ships "
            "these columns nullable so pre-migration rows remain valid "
            "and so optional-input fields default to NULL."
        )


async def test_pm_current_focus_columns_match_spec(
    storage: SqliteStorage,
) -> None:
    """0010_pm_current_focus.sql columns match 05-data-model.md §
    pm_current_focus: id (PK), objective (NOT NULL), output_type (NOT
    NULL with enum CHECK), acceptance_criteria (NOT NULL),
    bound_workflow_id (nullable, FK to tasks(id)), declared_at (NOT
    NULL, default now), completed_at (nullable),
    contract_validated_bool (nullable INTEGER), contract_finding_text
    (nullable TEXT).
    """
    await run_migrations(storage)
    cols = await storage.fetch_all(
        "PRAGMA table_info(pm_current_focus)"
    )
    by_name = {c["name"]: c for c in cols}

    expected_names = {
        "id",
        "objective",
        "output_type",
        "acceptance_criteria",
        "bound_workflow_id",
        "declared_at",
        "completed_at",
        "contract_validated_bool",
        "contract_finding_text",
    }
    assert set(by_name.keys()) == expected_names, (
        f"pm_current_focus columns drift: got {set(by_name.keys())!r}, "
        f"expected {expected_names!r}. Schema must match "
        "05-data-model.md § pm_current_focus."
    )

    assert by_name["id"]["pk"] == 1
    assert by_name["objective"]["notnull"] == 1
    assert by_name["output_type"]["notnull"] == 1
    assert by_name["acceptance_criteria"]["notnull"] == 1
    assert by_name["declared_at"]["notnull"] == 1
    assert by_name["bound_workflow_id"]["notnull"] == 0
    assert by_name["completed_at"]["notnull"] == 0
    assert by_name["contract_validated_bool"]["notnull"] == 0
    assert by_name["contract_finding_text"]["notnull"] == 0


async def test_pm_current_focus_output_type_check_constraint(
    storage: SqliteStorage,
) -> None:
    """The output_type column's CHECK enforces the spec'd 5-value
    enum (Q1 Reading A, F3.6.A): document, code_change, decision,
    review, investigation. Defense-in-depth alongside the
    tools/pm_focus.py:OutputType Literal — Python types catch typos at
    edit time, the CHECK catches the same class of bug from manual
    SQL or future external integrations.

    Sentinel test: every spec'd value passes the CHECK; bogus values
    reject. Failure here means the migration's CHECK list drifted from
    the OutputType Literal.
    """
    await run_migrations(storage)
    # Each spec'd value passes.
    for i, ot in enumerate(
        ["document", "code_change", "decision", "review", "investigation"]
    ):
        await storage.execute(
            "INSERT INTO pm_current_focus "
            "(id, objective, output_type, acceptance_criteria) "
            "VALUES (?, ?, ?, ?)",
            (f"focus-ot-{i}", "obj", ot, "criteria"),
        )

    rows = await storage.fetch_all(
        "SELECT output_type FROM pm_current_focus "
        "WHERE id LIKE 'focus-ot-%' ORDER BY id"
    )
    assert {r["output_type"] for r in rows} == {
        "document",
        "code_change",
        "decision",
        "review",
        "investigation",
    }

    # Bogus value rejects.
    with pytest.raises(Exception, match=r"(?i)check"):
        await storage.execute(
            "INSERT INTO pm_current_focus "
            "(id, objective, output_type, acceptance_criteria) "
            "VALUES (?, ?, ?, ?)",
            ("focus-bogus", "obj", "not-a-real-output-type", "criteria"),
        )


async def test_pm_current_focus_bound_workflow_fk_constraint(
    storage: SqliteStorage,
) -> None:
    """The bound_workflow_id FK to tasks(id) blocks INSERTs with
    non-existent task ids and accepts NULL plus valid task ids.
    Q3 Reading A disposition (F3.6.B): NO ACTION on delete; FK at
    write catches the PM-references-nonexistent-task bug class.

    SQLite FK enforcement is enabled at SqliteStorage.connect via
    PRAGMA foreign_keys=ON — without that pragma this test would
    silently pass on bogus ids.
    """
    await run_migrations(storage)
    # Seed a real task to reference.
    await storage.execute(
        "INSERT INTO companies (id, name) VALUES ('co-fk', 'co')"
    )
    await storage.execute(
        "INSERT INTO projects (id, company_id, name) VALUES "
        "('proj-fk', 'co-fk', 'p')"
    )
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description) VALUES ('task-fk', 'co-fk', 'proj-fk', "
        "'implementer', 'no-op')"
    )

    # NULL passes.
    await storage.execute(
        "INSERT INTO pm_current_focus "
        "(id, objective, output_type, acceptance_criteria) "
        "VALUES ('focus-null-fk', 'obj', 'document', 'criteria')"
    )

    # Valid task_id passes.
    await storage.execute(
        "INSERT INTO pm_current_focus "
        "(id, objective, output_type, acceptance_criteria, "
        "bound_workflow_id) "
        "VALUES ('focus-valid-fk', 'obj', 'document', 'criteria', "
        "'task-fk')"
    )

    # Bogus task_id rejects.
    with pytest.raises(Exception, match=r"(?i)foreign key"):
        await storage.execute(
            "INSERT INTO pm_current_focus "
            "(id, objective, output_type, acceptance_criteria, "
            "bound_workflow_id) "
            "VALUES ('focus-bogus-fk', 'obj', 'document', 'criteria', "
            "'task-does-not-exist')"
        )


async def test_pm_current_focus_declared_at_default_populates(
    storage: SqliteStorage,
) -> None:
    """declared_at has DEFAULT (datetime('now')) per the migration;
    INSERTs without an explicit declared_at must populate it rather
    than fail the NOT NULL constraint. Mirrors
    test_pm_mode_timestamp_default_populates_on_insert."""
    await run_migrations(storage)
    await storage.execute(
        "INSERT INTO pm_current_focus "
        "(id, objective, output_type, acceptance_criteria) "
        "VALUES ('focus-default', 'obj', 'document', 'criteria')"
    )
    row = await storage.fetch_one(
        "SELECT declared_at FROM pm_current_focus "
        "WHERE id = 'focus-default'"
    )
    assert row is not None
    assert row["declared_at"] is not None
    # SQLite datetime('now') format: 'YYYY-MM-DD HH:MM:SS'.
    assert len(row["declared_at"]) >= 19


async def test_pm_spin_rationale_log_columns_match_spec(
    storage: SqliteStorage,
) -> None:
    """0011_pm_spin_rationale_log.sql columns match 05-data-model.md §
    pm_spin_rationale_log: id (PK), iteration_timestamp (NOT NULL,
    default now), decision (NOT NULL with closed enum CHECK),
    rationale_tag (NOT NULL with closed enum CHECK), rationale_free_text
    (nullable).
    """
    await run_migrations(storage)
    cols = await storage.fetch_all(
        "PRAGMA table_info(pm_spin_rationale_log)"
    )
    by_name = {c["name"]: c for c in cols}

    expected_names = {
        "id",
        "iteration_timestamp",
        "decision",
        "rationale_tag",
        "rationale_free_text",
    }
    assert set(by_name.keys()) == expected_names, (
        f"pm_spin_rationale_log columns drift: got {set(by_name.keys())!r}, "
        f"expected {expected_names!r}. Schema must match "
        "05-data-model.md § pm_spin_rationale_log."
    )

    assert by_name["id"]["pk"] == 1
    assert by_name["iteration_timestamp"]["notnull"] == 1
    assert by_name["decision"]["notnull"] == 1
    assert by_name["rationale_tag"]["notnull"] == 1
    assert by_name["rationale_free_text"]["notnull"] == 0


async def test_pm_spin_decision_check_constraint(
    storage: SqliteStorage,
) -> None:
    """The decision column's CHECK enforces the spec'd 2-value enum
    (Q2 Reading A, F4.2): kept_alive, shutdown_recommended.
    Defense-in-depth alongside the tools/pm_spin.py:Decision Literal —
    Python types catch typos at edit time, the CHECK catches the same
    class of bug from manual SQL or future external integrations.

    Sentinel test: every spec'd value passes; bogus values reject.
    Failure here means the migration's CHECK list drifted from the
    Decision Literal.
    """
    await run_migrations(storage)
    for i, decision in enumerate(["kept_alive", "shutdown_recommended"]):
        await storage.execute(
            "INSERT INTO pm_spin_rationale_log "
            "(id, decision, rationale_tag) VALUES (?, ?, ?)",
            (f"spin-dec-{i}", decision, "queued_work"),
        )

    rows = await storage.fetch_all(
        "SELECT decision FROM pm_spin_rationale_log "
        "WHERE id LIKE 'spin-dec-%' ORDER BY id"
    )
    assert {r["decision"] for r in rows} == {
        "kept_alive",
        "shutdown_recommended",
    }

    with pytest.raises(Exception, match=r"(?i)check"):
        await storage.execute(
            "INSERT INTO pm_spin_rationale_log "
            "(id, decision, rationale_tag) VALUES (?, ?, ?)",
            ("spin-bogus-decision", "not-a-real-decision", "queued_work"),
        )


async def test_pm_spin_rationale_tag_check_constraint(
    storage: SqliteStorage,
) -> None:
    """The rationale_tag column's CHECK enforces the spec'd 4-value
    enum (F3.6 Q3 Reading A from Followup-2): queued_work,
    mike_presence, heartbeat_cheap, forgot_to_shutdown.

    Sentinel test pins the enum; failure here means the migration's
    CHECK list drifted from the RationaleTag Literal in
    tools/pm_spin.py.
    """
    await run_migrations(storage)
    for i, tag in enumerate(
        [
            "queued_work",
            "mike_presence",
            "heartbeat_cheap",
            "forgot_to_shutdown",
        ]
    ):
        await storage.execute(
            "INSERT INTO pm_spin_rationale_log "
            "(id, decision, rationale_tag) VALUES (?, ?, ?)",
            (f"spin-tag-{i}", "kept_alive", tag),
        )

    rows = await storage.fetch_all(
        "SELECT rationale_tag FROM pm_spin_rationale_log "
        "WHERE id LIKE 'spin-tag-%' ORDER BY id"
    )
    assert {r["rationale_tag"] for r in rows} == {
        "queued_work",
        "mike_presence",
        "heartbeat_cheap",
        "forgot_to_shutdown",
    }

    with pytest.raises(Exception, match=r"(?i)check"):
        await storage.execute(
            "INSERT INTO pm_spin_rationale_log "
            "(id, decision, rationale_tag) VALUES (?, ?, ?)",
            ("spin-bogus-tag", "kept_alive", "not-a-real-tag"),
        )


async def test_pm_spin_iteration_timestamp_default_populates(
    storage: SqliteStorage,
) -> None:
    """iteration_timestamp has DEFAULT (datetime('now')) per the
    migration; INSERTs without an explicit timestamp must populate
    it rather than fail the NOT NULL constraint. Mirrors
    test_pm_current_focus_declared_at_default_populates."""
    await run_migrations(storage)
    await storage.execute(
        "INSERT INTO pm_spin_rationale_log "
        "(id, decision, rationale_tag) VALUES (?, ?, ?)",
        ("spin-default", "kept_alive", "queued_work"),
    )
    row = await storage.fetch_one(
        "SELECT iteration_timestamp FROM pm_spin_rationale_log "
        "WHERE id = 'spin-default'"
    )
    assert row is not None
    assert row["iteration_timestamp"] is not None
    assert len(row["iteration_timestamp"]) >= 19


async def test_audit_sample_log_columns_match_spec(
    storage: SqliteStorage,
) -> None:
    """0012_audit_sample_log.sql columns match 05-data-model.md §
    audit_sample_log: id (PK), sampled_at (NOT NULL, default now),
    source_event_kind (NOT NULL with closed enum CHECK),
    source_event_id (NOT NULL TEXT, polymorphic free TEXT no FK),
    re_eval_state (NOT NULL with closed enum CHECK, default 'pending'),
    re_eval_finding (nullable), re_eval_completed_at (nullable).
    """
    await run_migrations(storage)
    cols = await storage.fetch_all(
        "PRAGMA table_info(audit_sample_log)"
    )
    by_name = {c["name"]: c for c in cols}

    expected_names = {
        "id",
        "sampled_at",
        "source_event_kind",
        "source_event_id",
        "re_eval_state",
        "re_eval_finding",
        "re_eval_completed_at",
    }
    assert set(by_name.keys()) == expected_names, (
        f"audit_sample_log columns drift: got {set(by_name.keys())!r}, "
        f"expected {expected_names!r}. Schema must match "
        "05-data-model.md § audit_sample_log."
    )

    assert by_name["id"]["pk"] == 1
    assert by_name["sampled_at"]["notnull"] == 1
    assert by_name["source_event_kind"]["notnull"] == 1
    assert by_name["source_event_id"]["notnull"] == 1
    assert by_name["re_eval_state"]["notnull"] == 1
    assert by_name["re_eval_finding"]["notnull"] == 0
    assert by_name["re_eval_completed_at"]["notnull"] == 0


async def test_audit_sample_source_event_kind_check_constraint(
    storage: SqliteStorage,
) -> None:
    """The source_event_kind column's CHECK enforces the spec'd
    5-value closed enum (F5.6.B Q1 reading reversal): all five PM-
    emittable sampleable event kinds pass; bogus values reject.

    Sentinel test pins the enum; failure here means the migration's
    CHECK list drifted from the SourceEventKind Literal in
    audit/sampler.py. The 5-value scope is deliberate Phase 1 narrowing
    (PM observes pm.current_focus.* events directly and dispatch.complete_called
    via complete_dispatch outcome reads; Engineer-emittable additions
    land via migration when Engineer's continuous loop spec lands at
    M5-flag-flips per 0008_dispatches_status_check.sql precedent).

    Defensive sentinel: pins the closed-enum list against future
    "helpful" expansion or contraction without explicit migration +
    F5.6.B re-disposition.
    """
    await run_migrations(storage)
    for i, kind in enumerate(
        [
            "pm.current_focus.completed",
            "pm.current_focus.contract_violation",
            "pm.current_focus.superseded",
            "pm.spin_rationale.recorded",
            "dispatch.complete_called",
        ]
    ):
        await storage.execute(
            "INSERT INTO audit_sample_log "
            "(id, source_event_kind, source_event_id) "
            "VALUES (?, ?, ?)",
            (f"sample-kind-{i}", kind, f"event-{i}"),
        )

    rows = await storage.fetch_all(
        "SELECT source_event_kind FROM audit_sample_log "
        "WHERE id LIKE 'sample-kind-%' ORDER BY id"
    )
    assert {r["source_event_kind"] for r in rows} == {
        "pm.current_focus.completed",
        "pm.current_focus.contract_violation",
        "pm.current_focus.superseded",
        "pm.spin_rationale.recorded",
        "dispatch.complete_called",
    }

    with pytest.raises(Exception, match=r"(?i)check"):
        await storage.execute(
            "INSERT INTO audit_sample_log "
            "(id, source_event_kind, source_event_id) "
            "VALUES (?, ?, ?)",
            ("sample-bogus-kind", "not.a.real.kind", "event-bogus"),
        )


async def test_audit_sample_re_eval_state_check_constraint(
    storage: SqliteStorage,
) -> None:
    """The re_eval_state column's CHECK enforces the spec'd 3-value
    closed state-machine enum (F5.2 Q5 Reading A): pending,
    in_progress, complete.

    Sentinel test pins the enum; failure here means the migration's
    CHECK list drifted from the ReEvalState Literal in
    audit/sampler.py. Future state additions (e.g., 'failed',
    'abandoned') land via migration when the re-evaluator surfaces
    real terminal variants.
    """
    await run_migrations(storage)
    for i, state in enumerate(["pending", "in_progress", "complete"]):
        await storage.execute(
            "INSERT INTO audit_sample_log "
            "(id, source_event_kind, source_event_id, re_eval_state) "
            "VALUES (?, ?, ?, ?)",
            (
                f"sample-state-{i}",
                "pm.current_focus.completed",
                f"event-{i}",
                state,
            ),
        )

    rows = await storage.fetch_all(
        "SELECT re_eval_state FROM audit_sample_log "
        "WHERE id LIKE 'sample-state-%' ORDER BY id"
    )
    assert {r["re_eval_state"] for r in rows} == {
        "pending",
        "in_progress",
        "complete",
    }

    with pytest.raises(Exception, match=r"(?i)check"):
        await storage.execute(
            "INSERT INTO audit_sample_log "
            "(id, source_event_kind, source_event_id, re_eval_state) "
            "VALUES (?, ?, ?, ?)",
            (
                "sample-bogus-state",
                "pm.current_focus.completed",
                "event-bogus",
                "not-a-real-state",
            ),
        )


async def test_audit_sample_re_eval_state_default_pending(
    storage: SqliteStorage,
) -> None:
    """re_eval_state has DEFAULT 'pending' per the migration; INSERTs
    without an explicit re_eval_state must populate it rather than
    fail the NOT NULL constraint. The default matches the writer
    contract — :meth:`audit.sampler.Sampler.maybe_sample` writes rows
    with re_eval_state='pending' for the future async re-evaluator
    to advance."""
    await run_migrations(storage)
    await storage.execute(
        "INSERT INTO audit_sample_log "
        "(id, source_event_kind, source_event_id) "
        "VALUES (?, ?, ?)",
        (
            "sample-default-state",
            "pm.current_focus.completed",
            "event-default",
        ),
    )
    row = await storage.fetch_one(
        "SELECT re_eval_state FROM audit_sample_log "
        "WHERE id = 'sample-default-state'"
    )
    assert row is not None
    assert row["re_eval_state"] == "pending"


async def test_audit_sample_sampled_at_default_populates(
    storage: SqliteStorage,
) -> None:
    """sampled_at has DEFAULT (datetime('now')) per the migration;
    INSERTs without an explicit sampled_at must populate it rather
    than fail the NOT NULL constraint. Mirrors
    test_pm_spin_iteration_timestamp_default_populates."""
    await run_migrations(storage)
    await storage.execute(
        "INSERT INTO audit_sample_log "
        "(id, source_event_kind, source_event_id) "
        "VALUES (?, ?, ?)",
        (
            "sample-default-ts",
            "pm.current_focus.completed",
            "event-default-ts",
        ),
    )
    row = await storage.fetch_one(
        "SELECT sampled_at FROM audit_sample_log "
        "WHERE id = 'sample-default-ts'"
    )
    assert row is not None
    assert row["sampled_at"] is not None
    assert len(row["sampled_at"]) >= 19


async def test_tasks_columns_match_spec_after_0013(
    storage: SqliteStorage,
) -> None:
    """0013_tasks_task_type_and_status_check.sql rewrites the tasks
    table preserving all columns from 0001 + 0002 and adding
    task_type. Column inventory must match 05-data-model.md § tasks
    DDL; rewrite preserves indexes via post-RENAME CREATE INDEX.
    """
    await run_migrations(storage)
    cols = await storage.fetch_all("PRAGMA table_info(tasks)")
    by_name = {c["name"]: c for c in cols}

    expected_names = {
        "id",
        "company_id",
        "project_id",
        "parent_task_id",
        "agent_role",
        "description",
        "priority",
        "complexity",
        "status",
        "task_type",
        "blocking_human",
        "estimated_tokens",
        "actual_tokens",
        "submitted_by",
        "claimed_by",
        "claimed_at",
        "completed_at",
        "notes",
        "created_at",
    }
    assert set(by_name.keys()) == expected_names, (
        f"tasks columns drift: got {set(by_name.keys())!r}, "
        f"expected {expected_names!r}. Schema must match "
        "05-data-model.md § tasks (post-0013)."
    )

    # task_type is nullable per the migration (NULL = unknown / not yet
    # tagged); status is NOT NULL with default 'pending' per 0001.
    assert by_name["task_type"]["notnull"] == 0
    assert by_name["status"]["notnull"] == 1
    assert by_name["status"]["dflt_value"] == "'pending'"

    # Indexes recreated post-RENAME.
    indexes = await storage.fetch_all(
        "SELECT name FROM sqlite_master "
        "WHERE type='index' AND tbl_name='tasks' "
        "AND name NOT LIKE 'sqlite_autoindex%'"
    )
    index_names = {r["name"] for r in indexes}
    assert {"idx_tasks_status_priority", "idx_tasks_agent_role",
            "idx_tasks_parent"}.issubset(index_names), (
        f"tasks indexes drift after 0013 rewrite: got {index_names!r}"
    )


async def test_tasks_task_type_check_constraint_accepts_all_spec_values(
    storage: SqliteStorage,
) -> None:
    """The task_type column's CHECK enforces the spec'd 9-value
    closed-enum work-shape taxonomy (Q1 disposition at
    M4d-task-management-surface, 2026-05-07): four coordination-shape
    values + four production-shape values + uncategorized fallback.
    Defense-in-depth alongside the storage.models.TaskType Literal —
    Python types catch typos at edit time, the CHECK catches the same
    class of bug from manual SQL or future external integrations.

    Sentinel test: every spec'd value passes the CHECK; bogus values
    reject (covered by the negative-path companion test). Failure here
    means the migration's CHECK list drifted from the TaskType Literal.
    """
    await run_migrations(storage)
    # Seed a real company + project to satisfy NOT NULL FKs.
    await storage.execute(
        "INSERT INTO companies (id, name) VALUES ('co-tt', 'co')"
    )
    await storage.execute(
        "INSERT INTO projects (id, company_id, name) "
        "VALUES ('proj-tt', 'co-tt', 'p')"
    )

    spec_values = [
        # Coordination shape.
        "planning", "dispatching", "monitoring", "review",
        # Production shape.
        "investigation", "synthesis", "execution", "revision",
        # Fallback.
        "uncategorized",
    ]
    for i, tt in enumerate(spec_values):
        await storage.execute(
            "INSERT INTO tasks "
            "(id, company_id, project_id, agent_role, description, "
            "task_type) VALUES (?, ?, ?, ?, ?, ?)",
            (f"task-tt-{i}", "co-tt", "proj-tt", "engineer", "desc", tt),
        )

    rows = await storage.fetch_all(
        "SELECT task_type FROM tasks WHERE id LIKE 'task-tt-%' ORDER BY id"
    )
    assert {r["task_type"] for r in rows} == set(spec_values)


async def test_tasks_task_type_check_rejects_invalid_value(
    storage: SqliteStorage,
) -> None:
    """A task_type value not in the spec'd closed enum must fail the
    CHECK constraint. Symmetric to the positive-walk companion test."""
    await run_migrations(storage)
    await storage.execute(
        "INSERT INTO companies (id, name) VALUES ('co-tt-bogus', 'co')"
    )
    await storage.execute(
        "INSERT INTO projects (id, company_id, name) "
        "VALUES ('proj-tt-bogus', 'co-tt-bogus', 'p')"
    )

    with pytest.raises(Exception, match=r"(?i)check"):
        await storage.execute(
            "INSERT INTO tasks "
            "(id, company_id, project_id, agent_role, description, "
            "task_type) VALUES (?, ?, ?, ?, ?, ?)",
            (
                "task-tt-bogus",
                "co-tt-bogus",
                "proj-tt-bogus",
                "engineer",
                "desc",
                "not-a-real-work-shape",
            ),
        )


async def test_tasks_task_type_accepts_null(
    storage: SqliteStorage,
) -> None:
    """task_type is nullable; INSERTs without the column (or with
    explicit NULL) must satisfy the CHECK. SQLite's CHECK semantics
    treat NULL as TRUE for the column-IN-(...) form, so NULL passes
    without an explicit IS NULL OR clause."""
    await run_migrations(storage)
    await storage.execute(
        "INSERT INTO companies (id, name) VALUES ('co-tt-null', 'co')"
    )
    await storage.execute(
        "INSERT INTO projects (id, company_id, name) "
        "VALUES ('proj-tt-null', 'co-tt-null', 'p')"
    )

    # Implicit NULL (column omitted from INSERT).
    await storage.execute(
        "INSERT INTO tasks "
        "(id, company_id, project_id, agent_role, description) "
        "VALUES ('task-tt-null', 'co-tt-null', 'proj-tt-null', "
        "'engineer', 'desc')"
    )
    # Explicit NULL.
    await storage.execute(
        "INSERT INTO tasks "
        "(id, company_id, project_id, agent_role, description, "
        "task_type) VALUES ('task-tt-explicit-null', 'co-tt-null', "
        "'proj-tt-null', 'engineer', 'desc', NULL)"
    )

    rows = await storage.fetch_all(
        "SELECT task_type FROM tasks WHERE id LIKE 'task-tt-%null%'"
    )
    assert all(r["task_type"] is None for r in rows)


async def test_tasks_status_check_constraint_accepts_all_spec_values(
    storage: SqliteStorage,
) -> None:
    """The status column's CHECK enforces the seven-value state-machine
    enum that the application layer (storage.models.TaskStatus Literal)
    has enforced since M1. Migration 0013 adds DB-layer enforcement
    via table rewrite per 0008_dispatches_status_check.sql precedent.
    Defense-in-depth — Python types catch typos at edit time, the
    CHECK catches the same class of bug from manual SQL.

    Sentinel test: every spec'd value passes the CHECK; bogus values
    reject (covered by the negative-path companion test). Failure here
    means the migration's CHECK list drifted from the TaskStatus
    Literal.
    """
    await run_migrations(storage)
    await storage.execute(
        "INSERT INTO companies (id, name) VALUES ('co-st', 'co')"
    )
    await storage.execute(
        "INSERT INTO projects (id, company_id, name) "
        "VALUES ('proj-st', 'co-st', 'p')"
    )

    spec_values = [
        "pending", "claimed", "in_progress",
        "completed", "failed", "declined", "cancelled",
    ]
    for i, st in enumerate(spec_values):
        await storage.execute(
            "INSERT INTO tasks "
            "(id, company_id, project_id, agent_role, description, "
            "status) VALUES (?, ?, ?, ?, ?, ?)",
            (f"task-st-{i}", "co-st", "proj-st", "engineer", "desc", st),
        )

    rows = await storage.fetch_all(
        "SELECT status FROM tasks WHERE id LIKE 'task-st-%' ORDER BY id"
    )
    assert {r["status"] for r in rows} == set(spec_values)


async def test_tasks_status_check_rejects_invalid_value(
    storage: SqliteStorage,
) -> None:
    """A status value not in the spec'd state-machine enum must fail
    the CHECK constraint. Symmetric to the positive-walk companion
    test."""
    await run_migrations(storage)
    await storage.execute(
        "INSERT INTO companies (id, name) VALUES ('co-st-bogus', 'co')"
    )
    await storage.execute(
        "INSERT INTO projects (id, company_id, name) "
        "VALUES ('proj-st-bogus', 'co-st-bogus', 'p')"
    )

    with pytest.raises(Exception, match=r"(?i)check"):
        await storage.execute(
            "INSERT INTO tasks "
            "(id, company_id, project_id, agent_role, description, "
            "status) VALUES (?, ?, ?, ?, ?, ?)",
            (
                "task-st-bogus",
                "co-st-bogus",
                "proj-st-bogus",
                "engineer",
                "desc",
                "not-a-real-status",
            ),
        )


async def test_canonical_clones_columns_match_spec(
    storage: SqliteStorage,
) -> None:
    """0014_canonical_clones.sql ships the canonical_clones registry
    table per M5-canonical-clone-mgmt slice 1. Column inventory must
    match the migration DDL: id (PK), name (NOT NULL UNIQUE),
    path (NOT NULL), upstream_url (NOT NULL), default_branch (NOT NULL
    DEFAULT 'main'), created_at (NOT NULL DEFAULT (datetime('now'))).
    """
    await run_migrations(storage)
    cols = await storage.fetch_all("PRAGMA table_info(canonical_clones)")
    by_name = {c["name"]: c for c in cols}

    expected_names = {
        "id",
        "name",
        "path",
        "upstream_url",
        "default_branch",
        "created_at",
    }
    assert set(by_name.keys()) == expected_names, (
        f"canonical_clones columns drift: got {set(by_name.keys())!r}, "
        f"expected {expected_names!r}."
    )

    assert by_name["id"]["pk"] == 1
    assert by_name["name"]["notnull"] == 1
    assert by_name["path"]["notnull"] == 1
    assert by_name["upstream_url"]["notnull"] == 1
    assert by_name["default_branch"]["notnull"] == 1
    assert by_name["created_at"]["notnull"] == 1
    assert by_name["default_branch"]["dflt_value"] == "'main'"
    # SQLite stores datetime('now') as the raw expression text; assert
    # on a substring rather than the exact form so future cosmetic
    # rewrites (e.g., parentheses placement) don't trigger spurious
    # failures.
    assert "datetime" in (by_name["created_at"]["dflt_value"] or "")


async def test_canonical_clones_name_unique(
    storage: SqliteStorage,
) -> None:
    """name carries a UNIQUE constraint per the migration; a second
    INSERT with the same name must raise. The registry layer
    (bridge.canonical_registry.CanonicalCloneRegistry.register) maps
    this raise to CanonicalAlreadyRegisteredError; the DB-layer
    constraint is the defense-in-depth backstop."""
    await run_migrations(storage)
    await storage.execute(
        "INSERT INTO canonical_clones "
        "(id, name, path, upstream_url) "
        "VALUES (?, ?, ?, ?)",
        ("clone-1", "default", "/tmp/c1", "https://example.com/r.git"),
    )
    with pytest.raises(Exception, match=r"(?i)unique"):
        await storage.execute(
            "INSERT INTO canonical_clones "
            "(id, name, path, upstream_url) "
            "VALUES (?, ?, ?, ?)",
            ("clone-2", "default", "/tmp/c2", "https://example.com/r.git"),
        )


async def test_dispatches_canonical_name_column_added(
    storage: SqliteStorage,
) -> None:
    """0014 rewrites dispatches via CREATE-INSERT-DROP-RENAME and adds
    canonical_name (TEXT NOT NULL DEFAULT 'default'). New writers don't
    yet thread canonical_name (Slice 1 ships the column for Slices 2+
    to consume); the default makes existing writers correct."""
    await run_migrations(storage)
    cols = await storage.fetch_all("PRAGMA table_info(dispatches)")
    by_name = {c["name"]: c for c in cols}

    assert "canonical_name" in by_name
    assert by_name["canonical_name"]["notnull"] == 1
    assert by_name["canonical_name"]["dflt_value"] == "'default'"


async def test_dispatches_indexes_recreated_after_0014(
    storage: SqliteStorage,
) -> None:
    """The dispatches table rewrite drops + recreates the table, which
    would silently drop the four indexes from 0005_dispatches.sql
    unless the migration explicitly recreates them post-RENAME.
    Sentinel against the rewrite losing the indexes."""
    await run_migrations(storage)
    rows = await storage.fetch_all(
        "SELECT name FROM sqlite_master "
        "WHERE type='index' AND tbl_name='dispatches' "
        "AND name NOT LIKE 'sqlite_autoindex%'"
    )
    index_names = {r["name"] for r in rows}
    expected = {
        "idx_dispatches_task",
        "idx_dispatches_status",
        "idx_dispatches_role_status",
        "idx_dispatches_role_completed",
    }
    missing = expected - index_names
    assert not missing, (
        f"dispatches indexes lost after 0014 rewrite: {missing}"
    )


async def test_dispatches_existing_rows_inherit_default_canonical_name(
    storage: SqliteStorage,
) -> None:
    """A dispatch row INSERTed without canonical_name must inherit
    'default' from the column default. Pins the greenfield-safety
    invariant the migration depends on: pre-migration dispatches
    rows (and Slice 1-era writers that don't yet thread
    canonical_name) get the only-canonical-that-ever-existed name
    automatically."""
    await run_migrations(storage)
    # Seed company + project + task to satisfy FK + NOT NULL chain.
    await storage.execute(
        "INSERT INTO companies (id, name) VALUES ('co-cn', 'co')"
    )
    await storage.execute(
        "INSERT INTO projects (id, company_id, name) "
        "VALUES ('proj-cn', 'co-cn', 'p')"
    )
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description) VALUES ('task-cn', 'co-cn', 'proj-cn', "
        "'implementer', 'desc')"
    )
    await storage.execute(
        "INSERT INTO dispatches "
        "(id, task_id, role, working_directory, timeout_seconds) "
        "VALUES (?, ?, ?, ?, ?)",
        ("disp-cn", "task-cn", "implementer", "/tmp/wd", 600),
    )
    row = await storage.fetch_one(
        "SELECT canonical_name FROM dispatches WHERE id = 'disp-cn'"
    )
    assert row is not None
    assert row["canonical_name"] == "default"


async def test_dispatches_status_check_preserved_after_0014(
    storage: SqliteStorage,
) -> None:
    """0008_dispatches_status_check.sql added the 8-value status enum
    CHECK; the 0014 rewrite must preserve it. Regression-guard against
    a future rewrite that silently drops the constraint by forgetting
    to copy it into the new table DDL."""
    await run_migrations(storage)
    await storage.execute(
        "INSERT INTO companies (id, name) VALUES ('co-sc', 'co')"
    )
    await storage.execute(
        "INSERT INTO projects (id, company_id, name) "
        "VALUES ('proj-sc', 'co-sc', 'p')"
    )
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description) VALUES ('task-sc', 'co-sc', 'proj-sc', "
        "'implementer', 'desc')"
    )
    with pytest.raises(Exception, match=r"(?i)check"):
        await storage.execute(
            "INSERT INTO dispatches "
            "(id, task_id, role, status, working_directory, "
            "timeout_seconds) VALUES (?, ?, ?, ?, ?, ?)",
            (
                "disp-sc-bogus",
                "task-sc",
                "implementer",
                "not-a-real-status",
                "/tmp/wd",
                600,
            ),
        )


async def test_dispatches_task_id_remains_nullable_after_0014(
    storage: SqliteStorage,
) -> None:
    """0007_dispatches_task_id_nullable.sql relaxed task_id to NULL-
    able (supervisor rows carry no task_id); the 0014 rewrite must
    preserve that nullability. Regression-guard against a future
    rewrite that silently reintroduces NOT NULL."""
    await run_migrations(storage)
    await storage.execute(
        "INSERT INTO dispatches "
        "(id, task_id, role, working_directory, timeout_seconds) "
        "VALUES (?, ?, ?, ?, ?)",
        ("disp-null-task", None, "engineer-supervisor", "/tmp/wd", 600),
    )
    row = await storage.fetch_one(
        "SELECT task_id FROM dispatches WHERE id = 'disp-null-task'"
    )
    assert row is not None
    assert row["task_id"] is None
