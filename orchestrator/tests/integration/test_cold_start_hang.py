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
    _has_worktree_activity_since,
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


# ===========================================================================
# β-S11-17.5 FS-activity probe + stage-2 recalibration
# ===========================================================================
#
# Diagnostic context (incident 2026-05-21): Slice 5 Implementer dispatches
# `5da62138` and `95ecbfc5` both ran with `last_tool_called=NULL` for the
# full 180s stage-2 window. Worktrees were preserved and showed zero file
# modifications — a true silent hang. Meanwhile the successful Slice 4
# dispatches `83aeb7d4` and `6dce0947` reached `complete_dispatch` 14-16
# minutes after subprocess_started with ZERO intermediate MCP activity —
# the dominant healthy Implementer shape. Had β-S11-17 stage 2 been
# active for those, it would have killed them at 180s as false positives.
#
# β-S11-17.5 closes that gap: stage 2's terminal classification requires
# BOTH no MCP activity AND no worktree filesystem activity. Healthy
# dispatches show file mtime updates from Read+Edit+Bash work; true hangs
# show neither signal.
# ---------------------------------------------------------------------------


def test_has_worktree_activity_since_detects_new_mtime(tmp_path: Path) -> None:
    """``_has_worktree_activity_since`` returns True iff a non-.git file
    has mtime strictly newer than the reference timestamp. Skips ``.git/``
    to avoid relying on git-internal housekeeping mtimes.
    """
    worktree = tmp_path / "wt"
    worktree.mkdir()
    (worktree / "preexisting.txt").write_text("from provisioning")
    # Simulated .git directory: anything inside is ignored by the probe.
    git_dir = worktree / ".git"
    git_dir.mkdir()
    (git_dir / "HEAD").write_text("ref: refs/heads/main")

    import time
    time.sleep(0.05)  # ensure subsequent mtime stamps land after `since`
    since = datetime.now(tz=UTC)
    time.sleep(0.05)

    # No new activity yet: only preexisting + .git internals.
    assert _has_worktree_activity_since(worktree, since) is False, (
        "preexisting (pre-since) files and .git internals must NOT register "
        "as activity"
    )

    # .git mutation alone: still no activity (skipped).
    (git_dir / "HEAD").write_text("ref: refs/heads/feature")
    assert _has_worktree_activity_since(worktree, since) is False, (
        ".git/* mutations must be skipped — git status/diff invocations "
        "would otherwise false-positive as subprocess activity"
    )

    # Subprocess writes a new file under the worktree.
    (worktree / "src").mkdir()
    (worktree / "src" / "newfile.py").write_text("# Implementer edit")
    assert _has_worktree_activity_since(worktree, since) is True, (
        "fresh non-.git file write inside the worktree must register as "
        "activity"
    )


def test_check_cold_start_hang_classification_stage_2_fs_activity_suppression() -> None:
    """β-S11-17.5 helper test: with ``has_worktree_activity=True``, stage 2
    elapsed-past-threshold no longer fires terminal — the classifier
    returns None and the dispatcher continues polling. With
    ``has_worktree_activity=False`` (default and the true-hang shape),
    stage 2 still fires terminal with ``fs_activity="none"``.
    """
    thresholds = _ColdStartHangThresholds(
        no_subprocess_seconds=10.0,
        no_mcp_activity_seconds=20.0,
        no_useful_activity_seconds=30.0,
    )
    issued_at = datetime(2026, 5, 21, 12, 0, 0, tzinfo=UTC)
    started_at_str = "2026-05-21T12:00:05+00:00"
    # Stage 2 elapsed-past-threshold row (no MCP, 25s after started_at).
    row_stage2 = {
        "started_at": started_at_str,
        "last_tool_called": None,
        "last_activity_at": None,
    }
    now_30s = datetime(2026, 5, 21, 12, 0, 30, tzinfo=UTC)

    # FS activity present → suppress.
    suppressed = _check_cold_start_hang_classification(
        row_stage2, issued_at, now_30s, thresholds,
        has_worktree_activity=True,
    )
    assert suppressed is None, (
        "FS activity present must suppress stage-2 terminal classification"
    )

    # FS activity absent → terminal with fs_activity=none.
    cls = _check_cold_start_hang_classification(
        row_stage2, issued_at, now_30s, thresholds,
        has_worktree_activity=False,
    )
    assert cls is not None
    assert cls.stage == "pre_first_mcp_activity"
    assert cls.is_terminal is True
    assert cls.fs_activity == "none", (
        "stage-2 terminal must record fs_activity='none' for forensic "
        "correlation in notes + audit"
    )


# ---------------------------------------------------------------------------
# Required behavioral assertion 1:
#   healthy Implementer-like dispatch with filesystem activity but no
#   MCP call is NOT killed at 180s.
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_stage_2_with_worktree_fs_activity_suppresses_terminal_kill(
    storage_with_task: SqliteStorage,
    canonical: CanonicalClone,
    token_store_with_transient: TokenStore,
    tmp_path: Path,
) -> None:
    """β-S11-17.5 §1: an Implementer-shape dispatch whose subprocess
    writes files into the worktree but never makes an MCP call must
    NOT be killed when stage-2 ``no_mcp_activity_seconds`` elapses.

    This is the dominant healthy shape: ``complete_dispatch`` is
    typically the only MCP call in the dispatch lifecycle, fired
    minutes after Read+Edit+Bash work begins. Pre-β-S11-17.5 the 180s
    stage-2 threshold killed every such dispatch as a false positive.

    Mechanism: an async helper task waits for status='running', writes
    a scratch file in the worktree (registers FS activity), waits past
    the stage-2 threshold (proving stage 2 didn't fire), then lands
    terminal status. The dispatcher must observe FS activity, suppress
    stage 2, and complete cleanly.
    """
    captured: dict[str, Path] = {}

    async def _alive_silent_launcher(
        ctx: DispatchLaunchContext,
    ) -> SubprocessHandle:
        captured["worktree"] = ctx.working_directory
        return _AliveButSilentHandle()  # type: ignore[return-value]

    # Stage-2 threshold MUST be long enough that:
    #   (a) the test helper task has time to observe status='running'
    #       (dispatcher's setup — canonical refresh + worktree provision
    #       + token-create + several DB ops — serializes the helper's
    #       fetch_one behind it), and
    #   (b) the helper has time to write the scratch file BEFORE the
    #       dispatcher reaches stage-2's first FS probe.
    # 2.5s is conservative — the dispatcher's setup is typically <500ms
    # on the operator's reference machine, and the helper writes its
    # file at ~T=0.5s on the wall clock.
    dispatcher = BridgeDispatcher(
        storage=storage_with_task,
        token_store=token_store_with_transient,
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=_alive_silent_launcher,
        cold_start_no_subprocess_seconds=10.0,
        cold_start_no_mcp_activity_seconds=2.5,
        cold_start_no_useful_activity_seconds=60.0,
    )

    async def _touch_then_complete() -> None:
        # Wait for status in {running, failed}: in a regression where
        # FS suppression doesn't work, dispatcher would transition
        # straight through running → failed before we observe. Break on
        # either so the assertion error pinpoints the regression.
        loop = asyncio.get_event_loop()
        deadline = loop.time() + 10.0
        dispatch_id: str | None = None
        observed_status: str | None = None
        while loop.time() < deadline:
            row = await storage_with_task.fetch_one(
                "SELECT id, status FROM dispatches "
                "WHERE task_id = 'task-1' LIMIT 1"
            )
            if row is not None and str(row["status"]) in (
                "running",
                "failed",
                "completed",
            ):
                dispatch_id = str(row["id"])
                observed_status = str(row["status"])
                if observed_status == "running":
                    break
                # If the dispatcher already marked the row terminal
                # before we observed running, the FS-suppression path
                # is broken — surface that as a clear test failure
                # rather than racing further.
                raise AssertionError(
                    f"dispatcher transitioned to {observed_status!r} "
                    f"before test helper could observe 'running'. "
                    f"β-S11-17.5 FS suppression failed to fire before "
                    f"stage-2 terminal classification."
                )
            await asyncio.sleep(0.02)
        assert dispatch_id is not None, (
            "dispatcher never reached status='running' within 10s — "
            "fixture or runtime degradation; not a β-S11-17.5 regression"
        )
        wt = captured["worktree"]
        # Brief settle so the upcoming write's mtime is clearly after
        # the fs_probe_since snapshot taken at _await_terminal entry
        # (which is microsecond-precision and ≥ status='running').
        await asyncio.sleep(0.1)
        # Simulate Implementer write inside the worktree.
        (wt / "implementer-scratch.txt").write_text(
            "in-progress work\n", encoding="utf-8"
        )
        # Wait past the stage-2 threshold so the dispatcher has time to
        # (a) reach the threshold-elapsed gate, (b) run the FS probe,
        # (c) observe the file we just wrote, and (d) suppress stage 2.
        # If FS suppression DOESN'T work, the dispatcher kills the
        # dispatch during this sleep and the final assert catches it.
        await asyncio.sleep(2.5)
        # Land terminal: simulate complete_dispatch landing.
        now_iso = datetime.now(tz=UTC).isoformat(timespec="seconds")
        await storage_with_task.execute(
            "UPDATE dispatches SET status = 'completed', "
            "last_tool_called = ?, last_activity_at = ?, "
            "completed_at = ? WHERE id = ?",
            ("complete_dispatch", now_iso, now_iso, dispatch_id),
        )

    activity_task = asyncio.create_task(_touch_then_complete())
    try:
        outcome = await dispatcher.dispatch(
            task_id="task-1",
            role="implementer",
            role_agent_id="impl-agent-id",
            prompt="ignored",
            actor="eng-agent-id",
        )
    finally:
        await activity_task

    # Strongest behavioral assertion: dispatch reached `completed`,
    # NOT `failed` with cold-start-hang notes.
    assert outcome.status == "completed", (
        f"FS-activity-present dispatch must not be killed by stage 2; "
        f"got status={outcome.status!r} notes={outcome.notes!r}"
    )
    audit_rows = await storage_with_task.fetch_all(
        "SELECT action, details_json FROM audit_log "
        "WHERE action = 'dispatch.cold_start_hang_detected'"
    )
    assert len(audit_rows) == 0, (
        "β-S11-17.5: stage 2 must NOT fire terminal when FS activity is "
        "observed in the worktree, even if no MCP call has landed; "
        f"got {len(audit_rows)} cold_start_hang_detected audits"
    )


# ---------------------------------------------------------------------------
# Required behavioral assertion 2:
#   true Slice-5-like hang with no MCP and no filesystem activity is
#   detected early with `fs_activity=none` recorded in notes + audit.
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_stage_2_true_hang_no_fs_no_mcp_terminates_with_fs_activity_none(
    storage_with_task: SqliteStorage,
    canonical: CanonicalClone,
    token_store_with_transient: TokenStore,
    tmp_path: Path,
) -> None:
    """β-S11-17.5 §2: the Slice 5 reproducer shape — subprocess alive,
    no MCP activity, no worktree file writes — MUST still be detected
    and terminated within stage-2 threshold. The new ``fs_activity``
    field must record ``"none"`` in both the dispatches.notes suffix
    and the dispatch.cold_start_hang_detected audit payload.

    This guards against the FS-probe regressing into a "never kills"
    fallback. Without FS activity, stage 2 still fires terminal — the
    fix is targeted suppression, not full disablement.
    """

    async def _alive_silent_launcher(
        ctx: DispatchLaunchContext,
    ) -> SubprocessHandle:
        # Crucially does NOT touch the worktree — leaves it pristine
        # after provisioning, exactly like the Slice 5 reproducer.
        return _AliveButSilentHandle()  # type: ignore[return-value]

    dispatcher = BridgeDispatcher(
        storage=storage_with_task,
        token_store=token_store_with_transient,
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=_alive_silent_launcher,
        cold_start_no_subprocess_seconds=10.0,
        cold_start_no_mcp_activity_seconds=0.5,
        cold_start_no_useful_activity_seconds=60.0,
    )

    outcome = await dispatcher.dispatch(
        task_id="task-1",
        role="implementer",
        role_agent_id="impl-agent-id",
        prompt="ignored",
        actor="eng-agent-id",
        keep_worktree_on_failure=False,  # force-keep should override
    )

    assert outcome.status == "failed"
    assert outcome.notes is not None
    assert outcome.notes.startswith(
        "cold-start-hang:pre_first_mcp_activity:"
    ), (
        f"notes prefix must remain stable for downstream parsing "
        f"(worktree force-keep gate); got {outcome.notes!r}"
    )
    assert outcome.notes.endswith(":fs_activity=none"), (
        f"β-S11-17.5: terminal stage-2 notes must record the FS probe "
        f"result so post-mortem reviewers see both signals were absent; "
        f"got {outcome.notes!r}"
    )

    import json
    audit_rows = await storage_with_task.fetch_all(
        "SELECT details_json FROM audit_log "
        "WHERE action = 'dispatch.cold_start_hang_detected'"
    )
    assert len(audit_rows) == 1
    details = json.loads(audit_rows[0]["details_json"])
    assert details["stage"] == "pre_first_mcp_activity"
    assert details["fs_activity"] == "none", (
        f"audit payload must carry fs_activity for telemetry "
        f"correlation; got {details}"
    )

    # Worktree force-preserved (regression check for the existing
    # β-S11-17 behavior — must survive the β-S11-17.5 notes-suffix change).
    sub_term_rows = await storage_with_task.fetch_all(
        "SELECT details_json FROM audit_log "
        "WHERE action = 'dispatch.subprocess_terminated'"
    )
    assert len(sub_term_rows) == 1
    assert (
        json.loads(sub_term_rows[0]["details_json"])["worktree_retained"]
        is True
    )


# ---------------------------------------------------------------------------
# Required behavioral assertion 3 (stdout log created + content captured)
# and 4 (stderr behavior remains intact) — covered in the dedicated
# launcher test module at
# tests/integration/test_dispatch_launcher_output_drain.py.
# ---------------------------------------------------------------------------
