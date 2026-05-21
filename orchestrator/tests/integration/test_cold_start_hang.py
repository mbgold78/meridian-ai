"""β-S11-17 cold-start hang detection — integration tests.

Pre-fix Implementer/Tester dispatches could sit for the full
``timeout_seconds`` (typically 1800s) with no useful activity before
being declared ``timed_out``. The Slice 5 Implementer dispatch
``84f98435`` (2026-05-21T12:58:41Z) is the motivating trigger:
subprocess_started present, ``last_tool_called=NULL`` for the full
1800s, no commit, no early failure signal.

Post-fix the dispatcher classifies clean rc=0-but-no-progress patterns
into three stages with early thresholds:

- Stage 1 ``pre_subprocess_started``: ``self._launcher(ctx)`` does not
  return within ``cold_start_no_subprocess_seconds``. ``asyncio.wait_for``
  raises ``TimeoutError`` inside ``dispatch()``. Dispatch marked
  ``failed`` with classification in ``notes``.
- Stage 2 ``pre_first_mcp_activity``: subprocess launched but
  ``last_tool_called=NULL`` for ``cold_start_no_mcp_activity_seconds``.
  Detected in ``_await_terminal`` polling loop. Dispatch marked
  ``failed`` with classification in ``notes``. Worktree force-preserved
  for forensic review regardless of the caller's
  ``keep_worktree_on_failure`` flag.
- Stage 3 ``pre_filesystem_activity``: MCP activity present but
  ``last_activity_at`` has not advanced within
  ``cold_start_no_useful_activity_seconds``. WARN-only —
  ``dispatch.cold_start_hang_warning`` audit fires once per dispatch;
  subprocess continues running and Engineer-side retry policy decides
  the next action.

All three stages emit deterministic audit events
(``dispatch.cold_start_hang_detected`` / ``cold_start_hang_warning``)
with structured details that PM/Engineer can correlate with their
β-S11-12 auto-resolve classification and β-S11-13 telemetry packets.
"""

from __future__ import annotations

import asyncio
import shutil
import subprocess
from collections.abc import AsyncIterator
from datetime import UTC, datetime
from pathlib import Path

import pytest
import pytest_asyncio

from meridian_orchestrator.bridge.canonical import CanonicalClone
from meridian_orchestrator.bridge.dispatcher import (
    BridgeDispatcher,
    ColdStartHangClassification,
    DispatchLaunchContext,
    SubprocessHandle,
    _ColdStartHangThresholds,
    _check_cold_start_hang_classification,
)
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.transient_tokens import TransientTokenStore
from meridian_orchestrator.auth import TokenStore

pytestmark = pytest.mark.skipif(
    shutil.which("git") is None,
    reason="git is not on PATH (canonical clone requires real git)",
)


def _git(*args: str, cwd: Path) -> None:
    subprocess.run(
        ["git", *args], cwd=str(cwd), check=True, capture_output=True
    )


@pytest.fixture
def upstream_repo(tmp_path: Path) -> Path:
    """Minimal git upstream for CanonicalClone."""
    upstream = tmp_path / "upstream"
    upstream.mkdir()
    _git("init", "-b", "main", cwd=upstream)
    _git("config", "user.email", "test@example.com", cwd=upstream)
    _git("config", "user.name", "Test", cwd=upstream)
    (upstream / "README.md").write_text("substrate\n", encoding="utf-8")
    _git("add", "README.md", cwd=upstream)
    _git("commit", "-m", "initial", cwd=upstream)
    return upstream


@pytest_asyncio.fixture
async def storage_with_task(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    """Minimal storage: schema migrated + one task row for the foreign-key
    reference. Worktrees / dispatches root prepared so the dispatcher's
    full lifecycle can run."""
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    await s.execute(
        "INSERT INTO companies (id, name) VALUES ('co1', 'Meridian AI')"
    )
    await s.execute(
        "INSERT INTO projects (id, company_id, name) VALUES "
        "('proj1', 'co1', 'Substrate Product')"
    )
    await s.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description) VALUES "
        "('task-1', 'co1', 'proj1', 'implementer', 'no-op')"
    )
    yield s
    await s.close()


@pytest_asyncio.fixture
async def canonical(tmp_path: Path, upstream_repo: Path) -> CanonicalClone:
    c = CanonicalClone(
        path=tmp_path / "canonical",
        upstream_url=str(upstream_repo),
    )
    await c.ensure_provisioned()
    return c


@pytest_asyncio.fixture
async def token_store_with_transient(
    storage_with_task: SqliteStorage,
) -> TokenStore:
    """TokenStore wired with TransientTokenStore (β-S11-15 invariant)."""
    return TokenStore(
        transient_store=TransientTokenStore(storage_with_task)
    )


# ---------------------------------------------------------------------------
# Helper-level unit test
# ---------------------------------------------------------------------------


def test_check_cold_start_hang_classification_stage_resolution() -> None:
    """Verify ``_check_cold_start_hang_classification`` returns the
    expected classification for each input shape. Pure helper test;
    doesn't exercise the dispatcher.
    """
    thresholds = _ColdStartHangThresholds(
        no_subprocess_seconds=10.0,
        no_mcp_activity_seconds=20.0,
        no_useful_activity_seconds=30.0,
    )
    issued_at = datetime(2026, 5, 21, 12, 0, 0, tzinfo=UTC)

    # Stage 1: no started_at, elapsed below threshold → None
    row_stage1_pending = {
        "started_at": None,
        "last_tool_called": None,
        "last_activity_at": None,
    }
    now_5s = datetime(2026, 5, 21, 12, 0, 5, tzinfo=UTC)
    assert (
        _check_cold_start_hang_classification(
            row_stage1_pending, issued_at, now_5s, thresholds
        )
        is None
    )

    # Stage 1: no started_at, elapsed >= threshold → stage 1 terminal
    now_15s = datetime(2026, 5, 21, 12, 0, 15, tzinfo=UTC)
    cls = _check_cold_start_hang_classification(
        row_stage1_pending, issued_at, now_15s, thresholds
    )
    assert cls is not None
    assert cls.stage == "pre_subprocess_started"
    assert cls.is_terminal is True
    assert cls.elapsed_seconds >= 10

    # Stage 2: started_at set, last_tool_called None, elapsed >= threshold
    started_at_str = "2026-05-21T12:00:05+00:00"
    row_stage2 = {
        "started_at": started_at_str,
        "last_tool_called": None,
        "last_activity_at": None,
    }
    now_30s = datetime(2026, 5, 21, 12, 0, 30, tzinfo=UTC)  # 25s after started
    cls = _check_cold_start_hang_classification(
        row_stage2, issued_at, now_30s, thresholds
    )
    assert cls is not None
    assert cls.stage == "pre_first_mcp_activity"
    assert cls.is_terminal is True
    assert cls.elapsed_seconds >= 20

    # Stage 3: last_tool_called set, last_activity_at stale → warn
    row_stage3 = {
        "started_at": started_at_str,
        "last_tool_called": "ping",
        "last_activity_at": "2026-05-21T12:00:10+00:00",
    }
    now_45s = datetime(2026, 5, 21, 12, 0, 45, tzinfo=UTC)  # 35s after activity
    cls = _check_cold_start_hang_classification(
        row_stage3, issued_at, now_45s, thresholds
    )
    assert cls is not None
    assert cls.stage == "pre_filesystem_activity"
    assert cls.is_terminal is False  # warn-only
    assert cls.elapsed_seconds >= 30

    # Stage 3 below threshold → None
    now_20s = datetime(2026, 5, 21, 12, 0, 20, tzinfo=UTC)  # 10s after activity
    assert (
        _check_cold_start_hang_classification(
            row_stage3, issued_at, now_20s, thresholds
        )
        is None
    )


# ---------------------------------------------------------------------------
# Stage 1: launcher hangs
# ---------------------------------------------------------------------------


class _HangingHandle:
    """Test-fake SubprocessHandle that satisfies the Protocol but never
    actually starts. Used only as a return type from the never-returning
    launcher; in stage-1 hang tests the launcher coroutine never reaches
    the return statement so this never gets constructed."""

    pid = 0
    returncode: int | None = None

    async def wait(self) -> int:
        return 0

    def terminate(self) -> None:
        return None

    def kill(self) -> None:
        return None


@pytest.mark.asyncio
async def test_stage_1_launcher_timeout_classifies_pre_subprocess_started(
    storage_with_task: SqliteStorage,
    canonical: CanonicalClone,
    token_store_with_transient: TokenStore,
    tmp_path: Path,
) -> None:
    """β-S11-17 stage 1: launcher coroutine never returns; dispatcher
    classifies as ``pre_subprocess_started``, marks dispatch ``failed``
    with notes prefixed ``cold-start-hang:pre_subprocess_started:``,
    emits ``dispatch.cold_start_hang_detected`` audit event."""

    async def _hanging_launcher(
        ctx: DispatchLaunchContext,
    ) -> SubprocessHandle:
        await asyncio.sleep(60)  # never returns within stage-1 threshold
        return _HangingHandle()  # type: ignore[return-value]

    dispatcher = BridgeDispatcher(
        storage=storage_with_task,
        token_store=token_store_with_transient,
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=_hanging_launcher,
        cold_start_no_subprocess_seconds=0.2,
        cold_start_no_mcp_activity_seconds=10.0,
        cold_start_no_useful_activity_seconds=20.0,
    )

    outcome = await dispatcher.dispatch(
        task_id="task-1",
        role="implementer",
        role_agent_id="impl-agent-id",
        prompt="ignored",
        actor="eng-agent-id",
    )

    assert outcome.status == "failed"
    assert outcome.notes is not None
    assert outcome.notes.startswith(
        "cold-start-hang:pre_subprocess_started:"
    )

    audit_rows = await storage_with_task.fetch_all(
        "SELECT details_json FROM audit_log "
        "WHERE action = 'dispatch.cold_start_hang_detected'"
    )
    assert len(audit_rows) == 1
    import json
    details = json.loads(audit_rows[0]["details_json"])
    assert details["stage"] == "pre_subprocess_started"
    assert details["elapsed_seconds"] >= 0


# ---------------------------------------------------------------------------
# Stage 2: subprocess started, no MCP activity
# ---------------------------------------------------------------------------


class _AliveButSilentHandle:
    """Returns proc.returncode=None forever so the dispatcher polls
    without ever observing a subprocess exit. No MCP activity will be
    recorded — production this would manifest as claude-code launched
    but never authenticating."""

    pid = 99999
    returncode: int | None = None

    async def wait(self) -> int:
        await asyncio.sleep(60)
        return 0

    def terminate(self) -> None:
        self.returncode = -15

    def kill(self) -> None:
        self.returncode = -9


@pytest.mark.asyncio
async def test_stage_2_no_mcp_activity_classifies_pre_first_mcp_activity(
    storage_with_task: SqliteStorage,
    canonical: CanonicalClone,
    token_store_with_transient: TokenStore,
    tmp_path: Path,
) -> None:
    """β-S11-17 stage 2: subprocess launches but never writes a
    ``last_tool_called`` value; dispatcher detects in poll loop after
    ``cold_start_no_mcp_activity_seconds``, marks dispatch ``failed``
    with notes prefixed ``cold-start-hang:pre_first_mcp_activity:``,
    emits ``dispatch.cold_start_hang_detected`` audit.
    """

    async def _alive_but_silent_launcher(
        ctx: DispatchLaunchContext,
    ) -> SubprocessHandle:
        return _AliveButSilentHandle()  # type: ignore[return-value]

    dispatcher = BridgeDispatcher(
        storage=storage_with_task,
        token_store=token_store_with_transient,
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=_alive_but_silent_launcher,
        cold_start_no_subprocess_seconds=10.0,
        cold_start_no_mcp_activity_seconds=0.5,
        cold_start_no_useful_activity_seconds=20.0,
    )

    outcome = await dispatcher.dispatch(
        task_id="task-1",
        role="implementer",
        role_agent_id="impl-agent-id",
        prompt="ignored",
        actor="eng-agent-id",
        keep_worktree_on_failure=False,  # explicit: should be overridden by force-keep
    )

    assert outcome.status == "failed"
    assert outcome.notes is not None
    assert outcome.notes.startswith(
        "cold-start-hang:pre_first_mcp_activity:"
    )

    audit_rows = await storage_with_task.fetch_all(
        "SELECT details_json FROM audit_log "
        "WHERE action = 'dispatch.cold_start_hang_detected'"
    )
    assert len(audit_rows) == 1
    import json
    details = json.loads(audit_rows[0]["details_json"])
    assert details["stage"] == "pre_first_mcp_activity"

    # Worktree force-preserved despite keep_worktree_on_failure=False.
    subprocess_terminated_rows = await storage_with_task.fetch_all(
        "SELECT details_json FROM audit_log "
        "WHERE action = 'dispatch.subprocess_terminated'"
    )
    assert len(subprocess_terminated_rows) == 1
    details = json.loads(subprocess_terminated_rows[0]["details_json"])
    assert details["worktree_retained"] is True, (
        "stage 2 cold-start hang must force-preserve worktree even when "
        "keep_worktree_on_failure=False; β-S11-17 § Preserve enough "
        "diagnostics for forensic review."
    )


# ---------------------------------------------------------------------------
# Stage 3: MCP activity present but last_activity_at stale (warn-only)
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_stage_3_stale_activity_emits_warning_does_not_terminate(
    storage_with_task: SqliteStorage,
    canonical: CanonicalClone,
    token_store_with_transient: TokenStore,
    tmp_path: Path,
) -> None:
    """β-S11-17 stage 3: subprocess has MCP activity but
    ``last_activity_at`` stale beyond ``no_useful_activity_seconds``;
    dispatcher emits one ``dispatch.cold_start_hang_warning`` audit per
    dispatch and continues polling. The subprocess eventually completes
    via background row mutation to terminal status; final outcome is
    ``completed`` (not ``failed``).

    Mechanism: a launcher returns a handle that stays alive; a separate
    asyncio task simulates pre-populated ``last_tool_called`` +
    backwards-aged ``last_activity_at``, then later writes terminal
    status. The dispatcher observes both the warning condition and the
    terminal transition in the same dispatch.
    """

    async def _silent_alive_launcher(
        ctx: DispatchLaunchContext,
    ) -> SubprocessHandle:
        return _AliveButSilentHandle()  # type: ignore[return-value]

    dispatcher = BridgeDispatcher(
        storage=storage_with_task,
        token_store=token_store_with_transient,
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=_silent_alive_launcher,
        # Stage 2 disabled (very large threshold) so stage 3 is the
        # only firing condition. Stage 3 short so the test runs fast.
        cold_start_no_subprocess_seconds=10.0,
        cold_start_no_mcp_activity_seconds=30.0,
        cold_start_no_useful_activity_seconds=0.3,
    )

    async def _simulate_stale_then_complete() -> None:
        # Wait briefly for the dispatcher to insert + start the
        # dispatch, then plant stale activity state.
        await asyncio.sleep(0.1)
        row = await storage_with_task.fetch_one(
            "SELECT id FROM dispatches WHERE task_id = 'task-1' LIMIT 1"
        )
        assert row is not None
        dispatch_id = str(row["id"])
        # Backdate last_activity_at so the threshold-3 check fires
        # immediately. last_tool_called set so stages 1+2 don't fire.
        await storage_with_task.execute(
            "UPDATE dispatches SET last_tool_called = ?, "
            "last_activity_at = ? WHERE id = ?",
            (
                "ping",
                "2020-01-01T00:00:00+00:00",
                dispatch_id,
            ),
        )
        # Let the dispatcher poll cycle observe the stale state.
        await asyncio.sleep(0.5)
        # Now land terminal status so dispatcher returns cleanly.
        await storage_with_task.execute(
            "UPDATE dispatches SET status = 'completed', "
            "completed_at = ? WHERE id = ?",
            (datetime.now(tz=UTC).isoformat(timespec="seconds"), dispatch_id),
        )

    stale_task = asyncio.create_task(_simulate_stale_then_complete())
    try:
        outcome = await dispatcher.dispatch(
            task_id="task-1",
            role="implementer",
            role_agent_id="impl-agent-id",
            prompt="ignored",
            actor="eng-agent-id",
        )
    finally:
        await stale_task

    assert outcome.status == "completed"
    audit_rows = await storage_with_task.fetch_all(
        "SELECT details_json FROM audit_log "
        "WHERE action = 'dispatch.cold_start_hang_warning'"
    )
    assert len(audit_rows) == 1, (
        "stage 3 must emit exactly one cold_start_hang_warning per "
        "dispatch (warn-once gate); got "
        + str(len(audit_rows))
    )
    import json
    details = json.loads(audit_rows[0]["details_json"])
    assert details["stage"] == "pre_filesystem_activity"


# ---------------------------------------------------------------------------
# Happy path: no cold-start hang on normal completion
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_no_cold_start_hang_audits_on_normal_completion(
    storage_with_task: SqliteStorage,
    canonical: CanonicalClone,
    token_store_with_transient: TokenStore,
    tmp_path: Path,
) -> None:
    """β-S11-17 happy path: dispatch completes quickly with MCP activity
    + terminal status; no cold-start hang audit events fire."""

    async def _quick_complete_launcher(
        ctx: DispatchLaunchContext,
    ) -> SubprocessHandle:
        handle = _AliveButSilentHandle()
        return handle  # type: ignore[return-value]

    dispatcher = BridgeDispatcher(
        storage=storage_with_task,
        token_store=token_store_with_transient,
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=_quick_complete_launcher,
        cold_start_no_subprocess_seconds=5.0,
        cold_start_no_mcp_activity_seconds=5.0,
        cold_start_no_useful_activity_seconds=5.0,
    )

    async def _land_terminal() -> None:
        # Poll for dispatcher to advance status='running' (which it
        # writes immediately after the launcher returns + the
        # subprocess_started audit fires). Setting terminal BEFORE that
        # racing UPDATE would let the dispatcher overwrite status back
        # to 'running' and leave last_tool_called/last_activity_at as
        # the planted values — which then ages into a stage-3 stale-
        # activity warning. Poll-then-update is the deterministic shape.
        loop = asyncio.get_event_loop()
        deadline = loop.time() + 5.0
        dispatch_id: str | None = None
        while loop.time() < deadline:
            row = await storage_with_task.fetch_one(
                "SELECT id, status FROM dispatches "
                "WHERE task_id = 'task-1' LIMIT 1"
            )
            if row is not None and str(row["status"]) == "running":
                dispatch_id = str(row["id"])
                break
            await asyncio.sleep(0.05)
        assert dispatch_id is not None, (
            "dispatcher never reached status='running' within poll window"
        )
        now_iso = datetime.now(tz=UTC).isoformat(timespec="seconds")
        await storage_with_task.execute(
            "UPDATE dispatches SET status = 'completed', "
            "last_tool_called = ?, last_activity_at = ?, "
            "completed_at = ? WHERE id = ?",
            ("ping", now_iso, now_iso, dispatch_id),
        )

    land_task = asyncio.create_task(_land_terminal())
    try:
        outcome = await dispatcher.dispatch(
            task_id="task-1",
            role="implementer",
            role_agent_id="impl-agent-id",
            prompt="ignored",
            actor="eng-agent-id",
        )
    finally:
        await land_task

    assert outcome.status == "completed"
    audit_rows = await storage_with_task.fetch_all(
        "SELECT action FROM audit_log "
        "WHERE action LIKE 'dispatch.cold_start_hang%'"
    )
    assert len(audit_rows) == 0, (
        "happy-path completion must not emit any cold_start_hang audit "
        "events; got " + str([dict(r) for r in audit_rows])
    )
