"""AutonomyLivenessWatchdog spam-guard + autonomy-mode-gating unit tests.

Operator Fix Window 2026-05-28. The watchdog historically created ~2015
pending priority-0 PM tasks because PM-task creation had no system-wide
cap and no dedupe at the task layer. These tests pin the two fixes:

FIX #1 — Deduplication + capping (no spam):
  * same violation_key across many ticks -> exactly one PM task
  * a per-tick cap bounds the number of NEW tasks created in one tick
  * the outstanding cap bounds total OPEN watchdog tasks system-wide
  * a resolved violation stops regenerating tasks
  * a violation that recurs AFTER PM closed its task gets a fresh task

FIX #2 — Honor autonomy mode:
  * Frozen / Manual projects (per the CP intent read) are suppressed
  * Continuous projects are still checked
  * a CP-read failure fails OPEN (rules run unconditionally)

Tests seed storage directly (bypassing MCP wrappers) and drive the loop
via ``_tick_once()`` for determinism, mirroring the integration suite.
"""

from __future__ import annotations

import uuid
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta
from typing import Any

import pytest_asyncio

from meridian_orchestrator.bridge import autonomy_liveness_watchdog as wd_mod
from meridian_orchestrator.bridge.autonomy_intent import (
    AutonomyIntentSnapshot,
    ProjectIntent,
)
from meridian_orchestrator.bridge.autonomy_liveness_watchdog import (
    PM_TASK_SKIPPED_ACTION,
    WATCHDOG_ACTOR,
    AutonomyLivenessWatchdog,
    WatchdogThresholds,
)
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage

# --- Fixtures ---------------------------------------------------------------


@pytest_asyncio.fixture
async def storage(tmp_path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "test.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


@pytest_asyncio.fixture
async def seeded(storage: SqliteStorage) -> AsyncIterator[tuple[str, str]]:
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


def _watchdog(storage: SqliteStorage, **overrides: Any) -> AutonomyLivenessWatchdog:
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
        escalation_ticks=overrides.pop("escalation_ticks", 99),
        max_pm_tasks_per_tick=overrides.pop("max_pm_tasks_per_tick", 10),
        max_outstanding_pm_tasks=overrides.pop("max_outstanding_pm_tasks", 50),
        control_plane_url=overrides.pop("control_plane_url", None),
    )


def _past_sql(seconds_ago: int) -> str:
    return (datetime.now(tz=UTC) - timedelta(seconds=seconds_ago)).strftime(
        "%Y-%m-%d %H:%M:%S"
    )


async def _insert_stale_in_progress(
    storage: SqliteStorage,
    *,
    company_id: str,
    project_id: str,
    description: str = "stale task",
) -> str:
    task_id = uuid.uuid4().hex
    await storage.execute(
        "INSERT INTO tasks ("
        "id, company_id, project_id, agent_role, description, priority, "
        "complexity, status, submitted_by, claimed_by, claimed_at, created_at"
        ") VALUES (?, ?, ?, 'engineer', ?, 1, 3, 'in_progress', "
        "'human:mike', 'engineer-token', ?, ?)",
        (
            task_id,
            company_id,
            project_id,
            description,
            _past_sql(120),
            _past_sql(130),
        ),
    )
    return task_id


async def _pm_tasks(storage: SqliteStorage) -> list[dict]:
    return await storage.fetch_all(
        "SELECT * FROM tasks WHERE submitted_by = ? AND agent_role = 'pm' "
        "ORDER BY id",
        (WATCHDOG_ACTOR,),
    )


async def _skip_audits(storage: SqliteStorage) -> list[dict]:
    return await storage.fetch_all(
        "SELECT id, action, details_json FROM audit_log WHERE action = ? "
        "ORDER BY id",
        (PM_TASK_SKIPPED_ACTION,),
    )


# --- FIX #1: dedupe + cap ---------------------------------------------------


async def test_same_violation_key_twice_yields_one_task(
    storage: SqliteStorage, seeded: tuple[str, str]
) -> None:
    """Driving five ticks on a single persistent violation must produce
    exactly one PM task — the dedupe-by-violation_key guard."""
    company_id, project_id = seeded
    await _insert_stale_in_progress(
        storage, company_id=company_id, project_id=project_id
    )
    watchdog = _watchdog(storage)
    for _ in range(5):
        await watchdog._tick_once()
    pm_tasks = await _pm_tasks(storage)
    assert len(pm_tasks) == 1
    assert pm_tasks[0]["priority"] == 0
    assert pm_tasks[0]["status"] == "pending"


async def test_resolution_stops_regeneration(
    storage: SqliteStorage, seeded: tuple[str, str]
) -> None:
    """Once PM moves the parent task terminal, the violation resolves and
    no further PM tasks are created on subsequent ticks."""
    company_id, project_id = seeded
    task_id = await _insert_stale_in_progress(
        storage, company_id=company_id, project_id=project_id
    )
    watchdog = _watchdog(storage)
    await watchdog._tick_once()
    assert len(await _pm_tasks(storage)) == 1

    # PM handles it: parent task -> completed.
    await storage.execute(
        "UPDATE tasks SET status = 'completed', completed_at = datetime('now') "
        "WHERE id = ?",
        (task_id,),
    )
    for _ in range(5):
        await watchdog._tick_once()
    # Still exactly one (the original); no regeneration.
    assert len(await _pm_tasks(storage)) == 1


async def test_recurrence_after_pm_closed_task_creates_fresh_task(
    storage: SqliteStorage, seeded: tuple[str, str]
) -> None:
    """If the violation resolves, the PM task is CLOSED, and then the same
    violation recurs, a fresh PM task is created (the old closed one must
    not suppress a genuine recurrence)."""
    company_id, project_id = seeded
    task_id = await _insert_stale_in_progress(
        storage, company_id=company_id, project_id=project_id
    )
    watchdog = _watchdog(storage)
    await watchdog._tick_once()
    pm_tasks = await _pm_tasks(storage)
    assert len(pm_tasks) == 1
    first_pm_task_id = pm_tasks[0]["id"]

    # Resolve the violation (parent terminal) AND close the watchdog PM task.
    await storage.execute(
        "UPDATE tasks SET status = 'completed', completed_at = datetime('now') "
        "WHERE id = ?",
        (task_id,),
    )
    await storage.execute(
        "UPDATE tasks SET status = 'completed' WHERE id = ?",
        (first_pm_task_id,),
    )
    await watchdog._tick_once()  # detects resolution

    # The same task goes stale again (re-open it, backdate claim).
    await storage.execute(
        "UPDATE tasks SET status = 'in_progress', completed_at = NULL, "
        "claimed_at = ? WHERE id = ?",
        (_past_sql(120), task_id),
    )
    await watchdog._tick_once()

    pm_tasks = await _pm_tasks(storage)
    # The original (closed) + a fresh one for the recurrence.
    assert len(pm_tasks) == 2
    fresh = [t for t in pm_tasks if t["status"] == "pending"]
    assert len(fresh) == 1


async def test_open_pm_task_blocks_duplicate_emits_skip_audit(
    storage: SqliteStorage, seeded: tuple[str, str]
) -> None:
    """The dedupe path emits a structured 'duplicate' skip audit naming
    the existing pm_task_id."""
    company_id, project_id = seeded
    await _insert_stale_in_progress(
        storage, company_id=company_id, project_id=project_id
    )
    watchdog = _watchdog(storage)
    await watchdog._tick_once()  # creates task
    # Force a fresh detection so _create_pm_task is exercised again: mark
    # the violation resolved at the DB level WITHOUT closing the PM task,
    # so re-detection on the next tick re-enters create with an OPEN task.
    await storage.execute(
        "UPDATE liveness_violations SET resolved_at = datetime('now')"
    )
    # Re-detection: _persist_violation returns 'updated' (clears resolved),
    # so _create_pm_task is NOT called via the 'new' path. To directly
    # exercise the dedupe guard, call _create_pm_task with the live
    # violation key.
    from meridian_orchestrator.bridge.autonomy_liveness_watchdog import (
        DetectedViolation,
    )

    row = await storage.fetch_one(
        "SELECT violation_key, violation_type, project_id, task_id "
        "FROM liveness_violations LIMIT 1"
    )
    v = DetectedViolation(
        violation_type=row["violation_type"],
        violation_key=row["violation_key"],
        project_id=row["project_id"],
        task_id=row["task_id"],
        dispatch_id=None,
        observed_state={},
        safe_next_action="noop",
    )
    created = await watchdog._create_pm_task(v)
    assert created is False
    skips = await _skip_audits(storage)
    assert len(skips) == 1
    assert '"reason": "duplicate"' in skips[0]["details_json"]
    assert "existing_pm_task_id" in skips[0]["details_json"]


async def test_per_tick_cap_limits_new_tasks(
    storage: SqliteStorage, seeded: tuple[str, str]
) -> None:
    """With many distinct fresh violations in one tick, only up to
    max_pm_tasks_per_tick tasks are created; a cap WARNING audit fires."""
    company_id, project_id = seeded
    # Seed 5 distinct stale tasks -> 5 distinct violation_keys.
    for i in range(5):
        await _insert_stale_in_progress(
            storage,
            company_id=company_id,
            project_id=project_id,
            description=f"stale-{i}",
        )
    watchdog = _watchdog(storage, max_pm_tasks_per_tick=3)
    summary = await watchdog._tick_once()
    assert summary["pm_tasks_created"] == 3
    assert summary["pm_tasks_capped"] == 2
    assert len(await _pm_tasks(storage)) == 3
    skips = await _skip_audits(storage)
    assert any('"reason": "per_tick"' in s["details_json"] for s in skips)


async def test_outstanding_cap_blocks_creation(
    storage: SqliteStorage, seeded: tuple[str, str]
) -> None:
    """Once outstanding open watchdog tasks reach the cap, no new tasks
    are created and an 'outstanding' cap audit fires."""
    company_id, project_id = seeded
    for i in range(4):
        await _insert_stale_in_progress(
            storage,
            company_id=company_id,
            project_id=project_id,
            description=f"stale-{i}",
        )
    # Cap at 2 outstanding; per-tick cap high so it isn't the limiter.
    watchdog = _watchdog(
        storage, max_outstanding_pm_tasks=2, max_pm_tasks_per_tick=100
    )
    await watchdog._tick_once()
    pm_tasks = await _pm_tasks(storage)
    assert len(pm_tasks) == 2
    skips = await _skip_audits(storage)
    assert any('"reason": "outstanding"' in s["details_json"] for s in skips)


# --- FIX #2: honor autonomy mode --------------------------------------------


async def test_frozen_project_suppresses_violations(
    storage: SqliteStorage, seeded: tuple[str, str], monkeypatch
) -> None:
    company_id, project_id = seeded
    await _insert_stale_in_progress(
        storage, company_id=company_id, project_id=project_id
    )
    snapshot = AutonomyIntentSnapshot(
        by_project={
            project_id: ProjectIntent(
                project_id=project_id,
                autonomy="Frozen",
                callback_armed=False,
            )
        }
    )

    async def _fake_fetch(**_kwargs: Any) -> AutonomyIntentSnapshot:
        return snapshot

    monkeypatch.setattr(wd_mod, "fetch_autonomy_intent", _fake_fetch)
    watchdog = _watchdog(storage, control_plane_url="http://cp.local")
    summary = await watchdog._tick_once()
    assert summary["suppressed_by_mode"] >= 1
    assert summary["new"] == 0
    assert await _pm_tasks(storage) == []
    violations = await storage.fetch_all("SELECT * FROM liveness_violations")
    assert violations == []


async def test_manual_project_suppresses_violations(
    storage: SqliteStorage, seeded: tuple[str, str], monkeypatch
) -> None:
    company_id, project_id = seeded
    await _insert_stale_in_progress(
        storage, company_id=company_id, project_id=project_id
    )
    snapshot = AutonomyIntentSnapshot(
        by_project={
            project_id: ProjectIntent(
                project_id=project_id, autonomy="Manual", callback_armed=False
            )
        }
    )

    async def _fake_fetch(**_kwargs: Any) -> AutonomyIntentSnapshot:
        return snapshot

    monkeypatch.setattr(wd_mod, "fetch_autonomy_intent", _fake_fetch)
    watchdog = _watchdog(storage, control_plane_url="http://cp.local")
    summary = await watchdog._tick_once()
    assert summary["new"] == 0
    assert await _pm_tasks(storage) == []


async def test_continuous_project_still_checked(
    storage: SqliteStorage, seeded: tuple[str, str], monkeypatch
) -> None:
    company_id, project_id = seeded
    await _insert_stale_in_progress(
        storage, company_id=company_id, project_id=project_id
    )
    snapshot = AutonomyIntentSnapshot(
        by_project={
            project_id: ProjectIntent(
                project_id=project_id,
                autonomy="Continuous",
                callback_armed=True,
            )
        }
    )

    async def _fake_fetch(**_kwargs: Any) -> AutonomyIntentSnapshot:
        return snapshot

    monkeypatch.setattr(wd_mod, "fetch_autonomy_intent", _fake_fetch)
    watchdog = _watchdog(storage, control_plane_url="http://cp.local")
    summary = await watchdog._tick_once()
    assert summary["suppressed_by_mode"] == 0
    assert summary["new"] >= 1
    assert len(await _pm_tasks(storage)) == 1


async def test_cp_read_failure_fails_open(
    storage: SqliteStorage, seeded: tuple[str, str], monkeypatch
) -> None:
    """When the CP intent read returns None (CP down/slow/malformed), the
    watchdog runs every rule unconditionally — conservative fail-open."""
    company_id, project_id = seeded
    await _insert_stale_in_progress(
        storage, company_id=company_id, project_id=project_id
    )

    async def _fake_fetch(**_kwargs: Any) -> None:
        return None

    monkeypatch.setattr(wd_mod, "fetch_autonomy_intent", _fake_fetch)
    watchdog = _watchdog(storage, control_plane_url="http://cp.local")
    summary = await watchdog._tick_once()
    assert summary["suppressed_by_mode"] == 0
    assert summary["new"] >= 1
    assert len(await _pm_tasks(storage)) == 1


async def test_unconfigured_cp_skips_read_and_checks(
    storage: SqliteStorage, seeded: tuple[str, str], monkeypatch
) -> None:
    """With no control_plane_url, the watchdog never calls fetch and runs
    rules unconditionally."""
    company_id, project_id = seeded
    await _insert_stale_in_progress(
        storage, company_id=company_id, project_id=project_id
    )
    calls = {"n": 0}

    async def _fake_fetch(**_kwargs: Any) -> None:
        calls["n"] += 1
        return None

    monkeypatch.setattr(wd_mod, "fetch_autonomy_intent", _fake_fetch)
    watchdog = _watchdog(storage, control_plane_url=None)
    summary = await watchdog._tick_once()
    assert calls["n"] == 0
    assert summary["new"] >= 1


async def test_intent_snapshot_cached_within_ttl(
    storage: SqliteStorage, seeded: tuple[str, str], monkeypatch
) -> None:
    """Two ticks within the cache TTL trigger only one CP fetch."""
    company_id, project_id = seeded
    await _insert_stale_in_progress(
        storage, company_id=company_id, project_id=project_id
    )
    calls = {"n": 0}
    snapshot = AutonomyIntentSnapshot(by_project={})

    async def _fake_fetch(**_kwargs: Any) -> AutonomyIntentSnapshot:
        calls["n"] += 1
        return snapshot

    monkeypatch.setattr(wd_mod, "fetch_autonomy_intent", _fake_fetch)
    watchdog = _watchdog(storage, control_plane_url="http://cp.local")
    await watchdog._tick_once()
    await watchdog._tick_once()
    assert calls["n"] == 1
