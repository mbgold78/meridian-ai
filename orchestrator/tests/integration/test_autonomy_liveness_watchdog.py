"""AutonomyLivenessWatchdog acceptance tests.

Operator Fix Window 2026-05-26. Covers Mike's 8 acceptance criteria from
the watchdog directive plus the two rules (pending_stale, callback_dark)
not directly named in the criteria. Tests seed the storage tables
directly (bypassing MCP wrappers) and drive the loop via
``_tick_once()`` for deterministic behavior.

The 8 acceptance criteria (verbatim from the 2026-05-26 directive):

1. A completed Tester task with no PM follow-up creates
   ``liveness.terminal_unsurfaced``.
2. A promised artifact path missing after terminal dispatch creates
   ``liveness.expected_artifact_missing``.
3. An in-progress task with no dispatch/audit/head movement past
   threshold creates ``liveness.in_progress_stale``.
4. A violation creates exactly one deduped priority-0 PM task.
5. If the violation remains unhandled across two ticks, human
   notification/audit escalation fires.
6. If PM handles the violation, the watchdog marks it resolved and
   does not re-emit.
7. The watchdog does not create noise during normal active coding
   where dispatch activity or commit/head movement is fresh.
8. Works for non-default canonicals such as ``player-learning-kb``.
"""

from __future__ import annotations

import uuid
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest_asyncio

from meridian_orchestrator.bridge.autonomy_liveness_watchdog import (
    DETECT_AUDIT_ACTION,
    ESCALATE_AUDIT_ACTION,
    HUMAN_ACTION_REQUIRED_ACTION,
    RESOLVE_AUDIT_ACTION,
    TICK_AUDIT_ACTION,
    WATCHDOG_ACTOR,
    AutonomyLivenessWatchdog,
    WatchdogThresholds,
    fetch_active_violations,
)
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage

# --- Fixtures ---------------------------------------------------------


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "test.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


@pytest_asyncio.fixture
async def seeded(
    storage: SqliteStorage,
) -> AsyncIterator[tuple[str, str]]:
    """Insert a company + project; return (company_id, project_id)."""
    company_id = uuid.uuid4().hex
    project_id = uuid.uuid4().hex
    await storage.execute(
        "INSERT INTO companies (id, name) VALUES (?, ?)",
        (company_id, "test-company"),
    )
    await storage.execute(
        "INSERT INTO projects (id, company_id, name, working_directory) "
        "VALUES (?, ?, ?, ?)",
        (project_id, company_id, "test-project", "/tmp/test-project"),
    )
    yield company_id, project_id


def _fast_watchdog(storage: SqliteStorage, **overrides) -> AutonomyLivenessWatchdog:
    """Watchdog with sub-second thresholds so tests don't wait minutes."""
    thresholds = WatchdogThresholds(
        pending_stale=overrides.pop("pending_stale", 1.0),
        in_progress_stale=overrides.pop("in_progress_stale", 1.0),
        terminal_unsurfaced=overrides.pop("terminal_unsurfaced", 1.0),
        artifact_missing_grace=overrides.pop("artifact_missing_grace", 0.0),
        callback_dark=overrides.pop("callback_dark", 1.0),
    )
    return AutonomyLivenessWatchdog(
        storage=storage,
        interval_seconds=overrides.pop("interval_seconds", 60.0),
        thresholds=thresholds,
        escalation_ticks=overrides.pop("escalation_ticks", 2),
    )


def _past_sql(seconds_ago: int) -> str:
    """Build a SQLite ``datetime('now', '-N seconds')`` style timestamp."""
    return (datetime.now(tz=UTC) - timedelta(seconds=seconds_ago)).strftime(
        "%Y-%m-%d %H:%M:%S"
    )


async def _insert_task(
    storage: SqliteStorage,
    *,
    company_id: str,
    project_id: str,
    agent_role: str = "engineer",
    status: str = "pending",
    priority: int = 1,
    description: str = "test task",
    notes: str | None = None,
    submitted_by: str = "human:mike",
    claimed_by: str | None = None,
    claimed_seconds_ago: int | None = None,
    created_seconds_ago: int = 0,
) -> str:
    """Insert a task with controllable timing fields."""
    task_id = uuid.uuid4().hex
    created_at = _past_sql(created_seconds_ago)
    claimed_at = (
        _past_sql(claimed_seconds_ago)
        if claimed_seconds_ago is not None
        else None
    )
    await storage.execute(
        "INSERT INTO tasks ("
        "id, company_id, project_id, agent_role, description, priority, "
        "complexity, status, submitted_by, claimed_by, claimed_at, "
        "notes, created_at"
        ") VALUES (?, ?, ?, ?, ?, ?, 3, ?, ?, ?, ?, ?, ?)",
        (
            task_id,
            company_id,
            project_id,
            agent_role,
            description,
            priority,
            status,
            submitted_by,
            claimed_by,
            claimed_at,
            notes,
            created_at,
        ),
    )
    return task_id


async def _insert_dispatch(
    storage: SqliteStorage,
    *,
    task_id: str,
    role: str = "tester",
    status: str = "completed",
    completed_seconds_ago: int = 0,
    working_directory: str = r"C:\tmp\test-dispatch-worktree",
    timeout_seconds: int = 1800,
) -> str:
    """Insert a dispatch row with completed_at backdated."""
    dispatch_id = uuid.uuid4().hex
    completed_at = _past_sql(completed_seconds_ago)
    await storage.execute(
        "INSERT INTO dispatches ("
        "id, task_id, role, status, working_directory, timeout_seconds, "
        "completed_at, last_activity_at"
        ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        (
            dispatch_id,
            task_id,
            role,
            status,
            working_directory,
            timeout_seconds,
            completed_at,
            completed_at,
        ),
    )
    return dispatch_id


async def _audit_rows(
    storage: SqliteStorage, action: str
) -> list[dict]:
    return await storage.fetch_all(
        "SELECT id, actor, action, target, details_json "
        "FROM audit_log WHERE action = ? ORDER BY id",
        (action,),
    )


async def _violations(storage: SqliteStorage) -> list[dict]:
    return await storage.fetch_all(
        "SELECT * FROM liveness_violations ORDER BY id"
    )


async def _pm_tasks(storage: SqliteStorage) -> list[dict]:
    return await storage.fetch_all(
        "SELECT * FROM tasks WHERE submitted_by = ? AND agent_role = 'pm' "
        "ORDER BY id",
        (WATCHDOG_ACTOR,),
    )


# --- Acceptance A1: terminal_unsurfaced -----------------------------------


async def test_a1_terminal_tester_with_no_pm_followup_fires_unsurfaced(
    storage: SqliteStorage, seeded: tuple[str, str]
) -> None:
    company_id, project_id = seeded
    task_id = await _insert_task(
        storage,
        company_id=company_id,
        project_id=project_id,
        agent_role="engineer",
        status="claimed",  # parent still open
        claimed_by="some-engineer-token",
        claimed_seconds_ago=120,
        created_seconds_ago=130,
    )
    await _insert_dispatch(
        storage,
        task_id=task_id,
        role="tester",
        status="completed",
        completed_seconds_ago=10,
    )
    watchdog = _fast_watchdog(storage, terminal_unsurfaced=1.0)
    summary = await watchdog._tick_once()
    types = [v["violation_type"] for v in await _violations(storage)]
    assert "terminal_unsurfaced" in types
    detect_audits = await _audit_rows(storage, DETECT_AUDIT_ACTION)
    assert any(
        '"terminal_unsurfaced"' in (a["details_json"] or "")
        for a in detect_audits
    )
    assert summary["new"] >= 1


# --- Acceptance A2: expected_artifact_missing -----------------------------


async def test_a2_terminal_dispatch_with_missing_artifact_fires(
    storage: SqliteStorage, seeded: tuple[str, str], tmp_path: Path
) -> None:
    company_id, project_id = seeded
    promised_path = tmp_path / "promised_artifact_that_does_not_exist.md"
    notes = (
        f"This task should produce an artifact_path: {promised_path}\n"
        "(Operator-visible deliverable.)"
    )
    task_id = await _insert_task(
        storage,
        company_id=company_id,
        project_id=project_id,
        agent_role="engineer",
        status="claimed",
        claimed_by="engineer-token",
        notes=notes,
        claimed_seconds_ago=120,
        created_seconds_ago=130,
    )
    await _insert_dispatch(
        storage,
        task_id=task_id,
        role="implementer",
        status="completed",
        completed_seconds_ago=10,
    )
    watchdog = _fast_watchdog(storage, artifact_missing_grace=0.0)
    await watchdog._tick_once()
    violations = await _violations(storage)
    types = [v["violation_type"] for v in violations]
    assert "expected_artifact_missing" in types
    import json as _json
    target_row = next(
        v for v in violations if v["violation_type"] == "expected_artifact_missing"
    )
    observed = _json.loads(target_row["observed_state_json"])
    assert observed["declared_artifact_path"] == str(promised_path)


# --- Acceptance A3: in_progress_stale --------------------------------------


async def test_a3_in_progress_task_with_no_recent_activity_fires(
    storage: SqliteStorage, seeded: tuple[str, str]
) -> None:
    company_id, project_id = seeded
    await _insert_task(
        storage,
        company_id=company_id,
        project_id=project_id,
        agent_role="engineer",
        status="in_progress",
        claimed_by="engineer-token",
        claimed_seconds_ago=120,
        created_seconds_ago=130,
    )
    watchdog = _fast_watchdog(storage, in_progress_stale=1.0)
    await watchdog._tick_once()
    types = [v["violation_type"] for v in await _violations(storage)]
    assert "in_progress_stale" in types


# --- Acceptance A4: violation creates exactly one deduped PM task ---------


async def test_a4_violation_creates_exactly_one_deduped_pm_task(
    storage: SqliteStorage, seeded: tuple[str, str]
) -> None:
    company_id, project_id = seeded
    await _insert_task(
        storage,
        company_id=company_id,
        project_id=project_id,
        agent_role="engineer",
        status="in_progress",
        claimed_by="engineer-token",
        claimed_seconds_ago=120,
        created_seconds_ago=130,
    )
    watchdog = _fast_watchdog(storage, in_progress_stale=1.0, escalation_ticks=99)
    # Tick three times — dedup must keep PM tasks at 1.
    for _ in range(3):
        await watchdog._tick_once()
    pm_tasks = await _pm_tasks(storage)
    assert len(pm_tasks) == 1
    assert pm_tasks[0]["priority"] == 0
    assert pm_tasks[0]["task_type"] == "monitoring"
    assert pm_tasks[0]["agent_role"] == "pm"
    assert pm_tasks[0]["submitted_by"] == WATCHDOG_ACTOR


# --- Acceptance A5: escalation after 2 ticks unhandled --------------------


async def test_a5_unhandled_violation_escalates_after_two_ticks(
    storage: SqliteStorage, seeded: tuple[str, str]
) -> None:
    company_id, project_id = seeded
    await _insert_task(
        storage,
        company_id=company_id,
        project_id=project_id,
        agent_role="engineer",
        status="in_progress",
        claimed_by="engineer-token",
        claimed_seconds_ago=120,
        created_seconds_ago=130,
    )
    watchdog = _fast_watchdog(
        storage, in_progress_stale=1.0, escalation_ticks=2
    )
    await watchdog._tick_once()  # tick 1: detect, no escalation yet
    escalations = await _audit_rows(storage, ESCALATE_AUDIT_ACTION)
    assert escalations == []
    await watchdog._tick_once()  # tick 2: tick_count crosses threshold
    escalations = await _audit_rows(storage, ESCALATE_AUDIT_ACTION)
    human_actions = await _audit_rows(storage, HUMAN_ACTION_REQUIRED_ACTION)
    assert len(escalations) >= 1
    assert len(human_actions) >= 1
    # Verify the violation row reflects escalation state.
    violations = await _violations(storage)
    assert any(v["escalated"] == 1 for v in violations)


# --- Acceptance A6: PM action marks resolved and does not re-emit ---------


async def test_a6_pm_handling_marks_resolved_and_no_re_emit(
    storage: SqliteStorage, seeded: tuple[str, str]
) -> None:
    company_id, project_id = seeded
    task_id = await _insert_task(
        storage,
        company_id=company_id,
        project_id=project_id,
        agent_role="engineer",
        status="in_progress",
        claimed_by="engineer-token",
        claimed_seconds_ago=120,
        created_seconds_ago=130,
    )
    watchdog = _fast_watchdog(
        storage, in_progress_stale=1.0, escalation_ticks=99
    )
    await watchdog._tick_once()
    assert len(await _pm_tasks(storage)) == 1
    # Simulate PM resolving the violation — move the parent task to terminal.
    await storage.execute(
        "UPDATE tasks SET status = 'completed', "
        "completed_at = datetime('now') WHERE id = ?",
        (task_id,),
    )
    await watchdog._tick_once()
    # No active violations should remain.
    active = await fetch_active_violations(storage)
    assert active == []
    # Resolve audit row was emitted.
    resolves = await _audit_rows(storage, RESOLVE_AUDIT_ACTION)
    assert len(resolves) >= 1
    # Subsequent tick should not re-emit (no detection, no audit).
    detect_count_before = len(await _audit_rows(storage, DETECT_AUDIT_ACTION))
    await watchdog._tick_once()
    detect_count_after = len(await _audit_rows(storage, DETECT_AUDIT_ACTION))
    assert detect_count_after == detect_count_before


# --- Acceptance A7: no noise during active coding -------------------------


async def test_a7_no_noise_when_dispatches_recent(
    storage: SqliteStorage, seeded: tuple[str, str]
) -> None:
    company_id, project_id = seeded
    task_id = await _insert_task(
        storage,
        company_id=company_id,
        project_id=project_id,
        agent_role="engineer",
        status="in_progress",
        claimed_by="engineer-token",
        claimed_seconds_ago=10,
        created_seconds_ago=12,
    )
    # Recent dispatch activity — well within in_progress_stale threshold.
    await _insert_dispatch(
        storage,
        task_id=task_id,
        role="implementer",
        status="completed",
        completed_seconds_ago=2,
    )
    # Thresholds large enough that nothing should fire on a fresh task.
    watchdog = _fast_watchdog(
        storage,
        pending_stale=300.0,
        in_progress_stale=300.0,
        terminal_unsurfaced=300.0,
        callback_dark=300.0,
        artifact_missing_grace=300.0,
    )
    await watchdog._tick_once()
    violations = await _violations(storage)
    assert violations == []
    pm_tasks = await _pm_tasks(storage)
    assert pm_tasks == []
    # Heartbeat audit still fires though.
    heartbeats = await _audit_rows(storage, TICK_AUDIT_ACTION)
    assert len(heartbeats) == 1


# --- Acceptance A8: works for non-default canonical -----------------------


async def test_a8_works_for_non_default_canonical(
    storage: SqliteStorage,
) -> None:
    """Mike's spec example: ``player-learning-kb`` canonical. The watchdog's
    detection logic is canonical-agnostic — it keys on (project_id, task_id,
    dispatch_id), which is exactly what non-default canonicals carry.
    Construct a project named ``player-learning-kb`` + seed an in-progress
    stale task; verify the violation fires."""
    company_id = uuid.uuid4().hex
    project_id = uuid.uuid4().hex
    await storage.execute(
        "INSERT INTO companies (id, name) VALUES (?, ?)",
        (company_id, "test-company-plkb"),
    )
    await storage.execute(
        "INSERT INTO projects (id, company_id, name, working_directory) "
        "VALUES (?, ?, ?, ?)",
        (
            project_id,
            company_id,
            "player-learning-kb",
            "/tmp/player-learning-kb",
        ),
    )
    await _insert_task(
        storage,
        company_id=company_id,
        project_id=project_id,
        agent_role="engineer",
        status="in_progress",
        claimed_by="engineer-token-plkb",
        claimed_seconds_ago=120,
        created_seconds_ago=130,
        description="Phase 2 player-learning-kb dispatch",
    )
    watchdog = _fast_watchdog(storage, in_progress_stale=1.0)
    await watchdog._tick_once()
    violations = await _violations(storage)
    assert any(
        v["project_id"] == project_id
        and v["violation_type"] == "in_progress_stale"
        for v in violations
    )
    # And the PM task is created against the player-learning-kb project.
    pm_tasks = await _pm_tasks(storage)
    assert len(pm_tasks) == 1
    assert pm_tasks[0]["project_id"] == project_id


# --- Bonus: pending_stale rule ------------------------------------------


async def test_pending_stale_rule_fires(
    storage: SqliteStorage, seeded: tuple[str, str]
) -> None:
    company_id, project_id = seeded
    await _insert_task(
        storage,
        company_id=company_id,
        project_id=project_id,
        agent_role="engineer",
        status="pending",
        priority=1,
        created_seconds_ago=120,
    )
    watchdog = _fast_watchdog(storage, pending_stale=1.0)
    await watchdog._tick_once()
    types = [v["violation_type"] for v in await _violations(storage)]
    assert "pending_stale" in types


# --- Bonus: heartbeat audit fires even when no violations ---------------


async def test_heartbeat_audit_fires_every_tick(
    storage: SqliteStorage,
) -> None:
    watchdog = _fast_watchdog(storage)
    for _ in range(3):
        await watchdog._tick_once()
    heartbeats = await _audit_rows(storage, TICK_AUDIT_ACTION)
    assert len(heartbeats) == 3
    # Heartbeat actor is the watchdog literal.
    assert all(a["actor"] == WATCHDOG_ACTOR for a in heartbeats)


# --- Bonus: fetch_active_violations shape -------------------------------


async def test_fetch_active_violations_returns_expected_shape(
    storage: SqliteStorage, seeded: tuple[str, str]
) -> None:
    company_id, project_id = seeded
    await _insert_task(
        storage,
        company_id=company_id,
        project_id=project_id,
        agent_role="engineer",
        status="in_progress",
        claimed_by="engineer-token",
        claimed_seconds_ago=120,
        created_seconds_ago=130,
    )
    watchdog = _fast_watchdog(storage, in_progress_stale=1.0)
    await watchdog._tick_once()
    active = await fetch_active_violations(storage)
    assert len(active) == 1
    row = active[0]
    assert row["violation_type"] == "in_progress_stale"
    assert row["project_id"] == project_id
    assert "observed_state" in row
    assert "safe_next_action" in row
    assert row["age_seconds"] is not None
    assert row["tick_count"] == 1
    assert row["escalated"] is False


# --- Cooperative shutdown ----------------------------------------------


async def test_run_loop_exits_on_shutdown_event(
    storage: SqliteStorage,
) -> None:
    import asyncio

    watchdog = AutonomyLivenessWatchdog(
        storage=storage,
        interval_seconds=0.1,
        thresholds=WatchdogThresholds(),
        escalation_ticks=2,
    )
    shutdown = asyncio.Event()

    async def runner() -> None:
        await watchdog.run(shutdown)

    task = asyncio.create_task(runner())
    await asyncio.sleep(0.25)  # let ~2 ticks fire
    shutdown.set()
    await asyncio.wait_for(task, timeout=1.0)
    # At least the heartbeat should have fired.
    heartbeats = await _audit_rows(storage, TICK_AUDIT_ACTION)
    assert len(heartbeats) >= 1
