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
# Reservation gap remaining: 0004 reserved for inbound_messages +
# outbound_messages (Telegram notifications); milestone-attribution
# pending resolution between data-model.md (M6) and the M5 sub-milestone
# breakdown memory (M5-telegram-foundation).
EXPECTED_VERSIONS = [1, 2, 3, 5, 6, 7, 8, 9]


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
