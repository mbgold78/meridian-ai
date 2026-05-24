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
from meridian_orchestrator.bridge.canonical_registry import CanonicalCloneRegistry
from meridian_orchestrator.bridge import dispatcher as dispatcher_module
from meridian_orchestrator.bridge.dispatcher import (
    BridgeDispatcher,
    ColdStartHangClassification,
    DispatchLaunchContext,
    SubprocessHandle,
    _ColdStartHangThresholds,
    _check_cold_start_hang_classification,
    _dispatcher_owned_canonicals,
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
async def canonical_registry(
    storage_with_task: SqliteStorage,
    canonical: CanonicalClone,
) -> AsyncIterator[CanonicalCloneRegistry]:
    """Slice 2-shape CanonicalCloneRegistry with the provisioned
    ``canonical`` registered as 'default'. Required because Slice 2's
    BridgeDispatcher.create() factory takes a ``canonical_registry``
    rather than a single ``CanonicalClone``. Teardown discards the
    canonical's path from the process-wide
    ``_dispatcher_owned_canonicals`` guard so sequential tests don't
    collide on the "another dispatcher already targets canonical"
    RuntimeError.
    """
    registry = CanonicalCloneRegistry(storage=storage_with_task)
    await registry.ensure_default(
        path=canonical.path, upstream_url=canonical.upstream_url
    )
    try:
        yield registry
    finally:
        _dispatcher_owned_canonicals.discard(canonical.path)


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
    canonical_registry: CanonicalCloneRegistry,
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

    dispatcher = await BridgeDispatcher.create(
        storage=storage_with_task,
        token_store=token_store_with_transient,
        canonical_registry=canonical_registry,
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
    canonical_registry: CanonicalCloneRegistry,
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

    dispatcher = await BridgeDispatcher.create(
        storage=storage_with_task,
        token_store=token_store_with_transient,
        canonical_registry=canonical_registry,
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
    canonical_registry: CanonicalCloneRegistry,
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

    dispatcher = await BridgeDispatcher.create(
        storage=storage_with_task,
        token_store=token_store_with_transient,
        canonical_registry=canonical_registry,
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
    canonical_registry: CanonicalCloneRegistry,
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

    dispatcher = await BridgeDispatcher.create(
        storage=storage_with_task,
        token_store=token_store_with_transient,
        canonical_registry=canonical_registry,
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
    canonical_registry: CanonicalCloneRegistry,
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
    dispatcher = await BridgeDispatcher.create(
        storage=storage_with_task,
        token_store=token_store_with_transient,
        canonical_registry=canonical_registry,
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
    canonical_registry: CanonicalCloneRegistry,
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

    dispatcher = await BridgeDispatcher.create(
        storage=storage_with_task,
        token_store=token_store_with_transient,
        canonical_registry=canonical_registry,
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
    # β-S11-17.5 session-signal-fix (2026-05-24): notes now carry BOTH
    # probe outcomes in stable order — fs_activity first (legacy),
    # session_activity second (new). The previous endswith assertion is
    # relaxed to a substring check so the legacy parser contract
    # (looking for ``:fs_activity=none``) still holds while the new
    # ``:session_activity=`` suffix lands behind it.
    assert ":fs_activity=none" in outcome.notes, (
        f"β-S11-17.5: terminal stage-2 notes must record the FS probe "
        f"result so post-mortem reviewers see the worktree was silent; "
        f"got {outcome.notes!r}"
    )
    assert ":session_activity=" in outcome.notes, (
        f"β-S11-17.5 session-signal-fix: terminal stage-2 notes must "
        f"also record the Claude-session-jsonl probe result; "
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
    assert "session_activity" in details, (
        f"β-S11-17.5 session-signal-fix: audit payload must carry "
        f"session_activity orthogonal to fs_activity; got {details}"
    )
    assert "progress_signal" in details, (
        f"β-S11-17.5 session-signal-fix: audit payload must carry the "
        f"computed progress_signal (worktree/session/both/none); "
        f"got {details}"
    )
    # For a true silent hang via _AliveButSilentHandle (no real claude
    # subprocess writes a jsonl), session_activity is "unavailable"
    # (no Claude project dir created) and progress_signal is "none"
    # (both probes effectively negative).
    assert details["session_activity"] in ("none", "unavailable")
    assert details["progress_signal"] == "none"

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


# ---------------------------------------------------------------------------
# β-S11-17.5 throttle: FS-activity probe must not walk the worktree every
# poll iteration. Without throttling the walk is O(N) per 0.1s — wasteful
# daemon load under concurrent cold-start-hangs. Required cap: ≤ 1 probe
# per second per dispatch (interval = _FS_ACTIVITY_PROBE_INTERVAL_SECONDS).
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_fs_activity_probe_is_throttled_to_at_most_once_per_second(
    storage_with_task: SqliteStorage,
    canonical_registry: CanonicalCloneRegistry,
    token_store_with_transient: TokenStore,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """β-S11-17.5 throttle invariant: across the FS-watching window
    between stage-2 threshold and activity confirmation, the worktree
    probe walks at most once per
    ``_FS_ACTIVITY_PROBE_INTERVAL_SECONDS`` (1.0s). Without the
    throttle, the probe would run at the dispatcher's poll cadence
    (default 0.1s = ~10 calls/sec) — wasteful daemon load especially
    risky under concurrent cold-start-hang dispatches.

    Mechanism: wrap the dispatcher's reference to
    :func:`_has_worktree_activity_since` with a counter + timestamp
    log. Run a healthy Implementer-shape dispatch where activity
    appears at ~T=1.5s; the dispatcher polls at 0.1s; stage-2
    threshold = 0.5s; grace = 2.0s (the recalibration default). The
    dispatcher's behavior under throttle:

        T=0.5s  → probe #1 fires; worktree empty; schedule next at
                  T=1.5s; classifier returns None (still inside grace,
                  no terminal yet).
        T=0.6-1.4s → ~9 poll iterations, NO probes (within throttle
                     window).
        T=1.5s  → probe #2 fires; worktree now has the helper's edit;
                  fs_activity_confirmed=True; suppression cache locks.
        T=1.6s+ → no more probes (cache short-circuits the gate).

    Without the throttle (or without the grace window) the probe
    would fire 10+ times during the ~1s no-activity window between
    threshold and activity. The cap is 3 probes (1 just in case of
    timing jitter on the first iteration + 1 confirmation probe + 1
    extra-just-in-case-of-a-very-slow-startup probe).
    """
    captured: dict[str, Path] = {}
    probe_calls: list[float] = []

    real_probe = dispatcher_module._has_worktree_activity_since

    def counting_probe(worktree: Path, since: datetime) -> bool:
        loop = asyncio.get_running_loop()
        probe_calls.append(loop.time())
        return real_probe(worktree, since)

    monkeypatch.setattr(
        dispatcher_module,
        "_has_worktree_activity_since",
        counting_probe,
    )

    async def _alive_silent_launcher(
        ctx: DispatchLaunchContext,
    ) -> SubprocessHandle:
        captured["worktree"] = ctx.working_directory
        return _AliveButSilentHandle()  # type: ignore[return-value]

    dispatcher = await BridgeDispatcher.create(
        storage=storage_with_task,
        token_store=token_store_with_transient,
        canonical_registry=canonical_registry,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=_alive_silent_launcher,
        cold_start_no_subprocess_seconds=10.0,
        cold_start_no_mcp_activity_seconds=0.5,
        cold_start_no_useful_activity_seconds=60.0,
        poll_interval_seconds=0.1,
    )

    async def _delayed_touch_then_complete() -> None:
        loop = asyncio.get_running_loop()
        deadline = loop.time() + 10.0
        dispatch_id: str | None = None
        while loop.time() < deadline:
            row = await storage_with_task.fetch_one(
                "SELECT id, status FROM dispatches "
                "WHERE task_id = 'task-1' LIMIT 1"
            )
            if row is not None and str(row["status"]) == "running":
                dispatch_id = str(row["id"])
                break
            await asyncio.sleep(0.02)
        assert dispatch_id is not None
        wt = captured["worktree"]
        # Hold off the write until ~1.5s after dispatch start so the
        # dispatcher spends ~1s inside the throttled no-activity
        # window (between first probe at ~T=0.5 and second probe at
        # ~T=1.5). During that window an un-throttled probe would
        # fire ~10 times; the throttle caps it at 0.
        await asyncio.sleep(1.5)
        (wt / "implementer-edit.txt").write_text("work\n", encoding="utf-8")
        # Let the second probe land (at ~T=1.5 by throttle schedule),
        # find the file, and confirm. Then a settle window before
        # terminal so we can also count any extra confirmation
        # iterations that might run before complete_dispatch.
        await asyncio.sleep(1.5)
        now_iso = datetime.now(tz=UTC).isoformat(timespec="seconds")
        await storage_with_task.execute(
            "UPDATE dispatches SET status = 'completed', "
            "last_tool_called = ?, last_activity_at = ?, "
            "completed_at = ? WHERE id = ?",
            ("complete_dispatch", now_iso, now_iso, dispatch_id),
        )

    activity_task = asyncio.create_task(_delayed_touch_then_complete())
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

    # Dispatch must complete (FS suppression worked — regression guard).
    assert outcome.status == "completed", (
        f"β-S11-17.5 FS suppression must let this dispatch complete; "
        f"got {outcome.status!r} notes={outcome.notes!r}"
    )

    # Throttle invariant: across the ~3s dispatch lifetime, probe must
    # have fired AT MOST 3 times. Un-throttled rate against the same
    # ~1s no-activity window + 0.1s poll cadence would be ~10+.
    assert len(probe_calls) <= 3, (
        f"β-S11-17.5 throttle: FS-activity probe must fire at most ~3 "
        f"times across the dispatch lifetime under 1s throttle; got "
        f"{len(probe_calls)} probes at offsets "
        f"{[round(t - probe_calls[0], 3) for t in probe_calls]}. "
        f"Un-throttled rate would be ~10+ for this window — regression."
    )

    # Sanity-check that the probe DID fire (otherwise the suppression
    # path didn't run and this test isn't proving anything). With the
    # grace window we expect at least 2 probes (one negative at
    # threshold, one positive after the helper writes the file).
    assert len(probe_calls) >= 2, (
        f"FS-activity probe ran only {len(probe_calls)} time(s) — "
        f"throttle or grace window broken (expected ≥2 to validate "
        f"multi-probe behavior)"
    )

    # Inter-probe interval invariant: consecutive probes must be
    # separated by ≥ ~1.0s (allow 50ms jitter for asyncio scheduling).
    for prev, curr in zip(probe_calls, probe_calls[1:]):
        gap = curr - prev
        assert gap >= 0.95, (
            f"β-S11-17.5 throttle: consecutive probes must be at least "
            f"~1s apart (jitter-tolerant); got gap={gap:.3f}s "
            f"between probes at {prev:.3f} and {curr:.3f}"
        )


# ===========================================================================
# β-S11-17.5 session-signal-fix (2026-05-24)
# ===========================================================================
#
# Failure shape diagnosed in dispatches 4f9c568d + c93b5170 (Phase 1 Slice
# 1 Implementer attempts, 2026-05-24): Claude was actively calling Read +
# Bash + Grep + ToolSearch for 142-174 seconds. None touch the worktree
# (Read is read-only; Bash find/grep don't mutate mtimes on NTFS; only
# Edit/Write would have moved the worktree probe). None touch the
# orchestrator MCP (those are CLI built-in tools, not orchestrator MCP
# tools). Both dispatches were killed at the stage-2 threshold despite
# being fully active. The session-jsonl mtime tracked every turn and
# would have caught the activity.
# ---------------------------------------------------------------------------


def test_claude_project_dir_encoding() -> None:
    """``_claude_project_dir_for`` mirrors Claude Code's cwd encoding:
    ``:`` ``\\`` ``.`` all become ``-``. Verified against existing
    project-dir names under ``~/.claude/projects/``."""
    wt = Path(r"C:\Users\mbgol\.meridian-orchestrator\dispatch-worktrees\xyz")
    encoded = dispatcher_module._claude_project_dir_for(wt)
    assert encoded is not None
    assert encoded.name.endswith(
        "C--Users-mbgol--meridian-orchestrator-dispatch-worktrees-xyz"
    ), f"unexpected encoding: {encoded.name!r}"
    assert encoded.parent == Path.home() / ".claude" / "projects"


def test_session_probe_unavailable_when_dir_missing(tmp_path: Path) -> None:
    """Project dir does not exist → ``(False, "unavailable")``. The
    caller treats this like ``"none"`` for terminal-decision purposes but
    can distinguish 'no probe data' from 'Claude was silent' in audit."""
    nonexistent_wt = tmp_path / "no-such-worktree"
    active, status = dispatcher_module._has_claude_session_activity_since(
        nonexistent_wt, datetime(2026, 5, 24, tzinfo=UTC)
    )
    assert active is False
    assert status == "unavailable"


def test_session_probe_present_when_jsonl_mtime_advances(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Fake Claude project dir with jsonl whose mtime is after
    ``since`` → ``(True, "present")``. Monkeypatches HOME so the
    resolver doesn't touch the operator's real ~/.claude/projects/."""
    monkeypatch.setenv("USERPROFILE", str(tmp_path))
    monkeypatch.setenv("HOME", str(tmp_path))
    wt = tmp_path / "fake-worktree"
    wt.mkdir()
    proj_dir = dispatcher_module._claude_project_dir_for(wt)
    assert proj_dir is not None
    proj_dir.mkdir(parents=True, exist_ok=True)
    jsonl = proj_dir / "session-uuid.jsonl"
    jsonl.write_text('{"type":"x"}\n', encoding="utf-8")
    import time as _time
    _time.sleep(0.05)
    since = datetime.now(tz=UTC)
    _time.sleep(0.05)
    active, status = dispatcher_module._has_claude_session_activity_since(
        wt, since
    )
    assert active is False
    assert status == "none"
    with jsonl.open("a", encoding="utf-8") as f:
        f.write('{"type":"user"}\n')
    active, status = dispatcher_module._has_claude_session_activity_since(
        wt, since
    )
    assert active is True
    assert status == "present"


def test_progress_signal_combinations() -> None:
    """``progress_signal`` maps the fs+session enum pair to the unified
    telemetry label."""
    def mk(fs, ss):
        return ColdStartHangClassification(
            stage="pre_first_mcp_activity", elapsed_seconds=200,
            is_terminal=True, fs_activity=fs, session_activity=ss,
        ).progress_signal

    assert mk("none", "none") == "none"
    assert mk("none", "unavailable") == "none"
    assert mk("present", "none") == "worktree"
    assert mk("none", "present") == "session"
    assert mk("present", "present") == "both"
    assert mk("not_probed", "not_probed") == "not_probed"


def test_check_classifier_suppresses_on_session_activity_only() -> None:
    """β-S11-17.5 session-signal-fix: stage 2 elapsed past threshold +
    worktree silent + session active → SUPPRESS. The Phase 1 failure
    shape — both failed Implementer dispatches were in this state for
    142-174s and got killed pre-fix."""
    thresholds = _ColdStartHangThresholds(
        no_subprocess_seconds=10.0, no_mcp_activity_seconds=20.0,
        no_useful_activity_seconds=30.0,
    )
    issued_at = datetime(2026, 5, 24, 12, 0, 0, tzinfo=UTC)
    row = {
        "started_at": "2026-05-24T12:00:05+00:00",
        "last_tool_called": None,
        "last_activity_at": None,
    }
    now_30s = datetime(2026, 5, 24, 12, 0, 30, tzinfo=UTC)

    cls = _check_cold_start_hang_classification(
        row, issued_at, now_30s, thresholds,
        has_worktree_activity=False, has_session_activity=True,
        session_probe_status="present",
    )
    assert cls is None, (
        "session-active-only must suppress stage 2 — the Phase 1 "
        "regression we just fixed"
    )

    cls = _check_cold_start_hang_classification(
        row, issued_at, now_30s, thresholds,
        has_worktree_activity=False, has_session_activity=False,
        session_probe_status="none",
    )
    assert cls is not None
    assert cls.is_terminal is True
    assert cls.fs_activity == "none"
    assert cls.session_activity == "none"
    assert cls.progress_signal == "none"

    cls = _check_cold_start_hang_classification(
        row, issued_at, now_30s, thresholds,
        has_worktree_activity=False, has_session_activity=False,
        session_probe_status="unavailable",
    )
    assert cls is not None
    assert cls.session_activity == "unavailable"

    # Worktree-only positive (pre-fix behavior preserved).
    cls = _check_cold_start_hang_classification(
        row, issued_at, now_30s, thresholds,
        has_worktree_activity=True, has_session_activity=False,
        session_probe_status="none",
    )
    assert cls is None


def test_check_classifier_backward_compat_worktree_only_kwarg() -> None:
    """Callers from the pre-2026-05-24 code path that pass only the
    ``has_worktree_activity`` kwarg continue to work unchanged. Session
    defaults to absent + not_probed → terminal stays terminal,
    suppression stays suppression-when-worktree-positive."""
    thresholds = _ColdStartHangThresholds(
        no_subprocess_seconds=10.0, no_mcp_activity_seconds=20.0,
        no_useful_activity_seconds=30.0,
    )
    issued_at = datetime(2026, 5, 24, 12, 0, 0, tzinfo=UTC)
    row = {
        "started_at": "2026-05-24T12:00:05+00:00",
        "last_tool_called": None, "last_activity_at": None,
    }
    now_30s = datetime(2026, 5, 24, 12, 0, 30, tzinfo=UTC)

    # Worktree positive → suppress (pre-fix behavior).
    cls = _check_cold_start_hang_classification(
        row, issued_at, now_30s, thresholds,
        has_worktree_activity=True,
    )
    assert cls is None

    # No kwargs at all → terminal (pre-fix shape).
    cls = _check_cold_start_hang_classification(
        row, issued_at, now_30s, thresholds,
    )
    assert cls is not None
    assert cls.is_terminal is True
    assert cls.fs_activity == "none"
    # Session not explicitly probed → "not_probed" recorded.
    assert cls.session_activity == "not_probed"


@pytest.mark.asyncio
async def test_stage_2_with_session_jsonl_activity_suppresses_kill(
    storage_with_task: SqliteStorage,
    canonical: CanonicalClone,
    canonical_registry: CanonicalCloneRegistry,
    token_store_with_transient: TokenStore,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """β-S11-17.5 session-signal-fix: dispatch whose subprocess does NOT
    touch the worktree but DOES advance the Claude-session jsonl (the
    failure shape of dispatches 4f9c568d + c93b5170 — 142-174s of
    Read/Bash/Grep work invisible to the worktree probe). Must NOT be
    killed at stage 2.

    Mechanism: monkeypatch ``_has_claude_session_activity_since`` to
    flip from ``(False, "none")`` to ``(True, "present")`` mid-grace
    window. Sidesteps the Windows MAX_PATH issue with the encoded-cwd
    project dir under pytest's deeply-nested ``tmp_path`` (encoded
    names approach 260 chars when rooted at
    ``AppData/Local/Temp/pytest-of-.../...``).
    """
    captured: dict[str, Path] = {}
    activity_state = {"present": False}

    def fake_session_probe(worktree: Path, since: datetime):
        if activity_state["present"]:
            return (True, "present")
        return (False, "none")

    monkeypatch.setattr(
        dispatcher_module,
        "_has_claude_session_activity_since",
        fake_session_probe,
    )

    async def _alive_silent_launcher(
        ctx: DispatchLaunchContext,
    ) -> SubprocessHandle:
        captured["worktree"] = ctx.working_directory
        return _AliveButSilentHandle()  # type: ignore[return-value]

    dispatcher = await BridgeDispatcher.create(
        storage=storage_with_task,
        token_store=token_store_with_transient,
        canonical_registry=canonical_registry,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=_alive_silent_launcher,
        cold_start_no_subprocess_seconds=10.0,
        cold_start_no_mcp_activity_seconds=2.5,
        cold_start_no_useful_activity_seconds=60.0,
    )

    async def _flip_session_then_complete() -> None:
        loop = asyncio.get_event_loop()
        deadline = loop.time() + 10.0
        dispatch_id: str | None = None
        while loop.time() < deadline:
            row = await storage_with_task.fetch_one(
                "SELECT id, status FROM dispatches "
                "WHERE task_id = 'task-1' LIMIT 1"
            )
            if row is not None and str(row["status"]) in (
                "running", "failed", "completed",
            ):
                dispatch_id = str(row["id"])
                if str(row["status"]) == "running":
                    break
                raise AssertionError(
                    f"transitioned to {str(row['status'])!r} before observe"
                )
            await asyncio.sleep(0.02)
        assert dispatch_id is not None
        # Hold off session-positive until just after the stage-2 threshold
        # so the dispatcher's gate fires once with session silent, then
        # flips. Exercises the throttle + grace window: second probe
        # catches the flipped state.
        await asyncio.sleep(0.4)
        activity_state["present"] = True
        # Wait past stage-2 threshold + grace so the dispatcher has time
        # to (a) hit the gate with session silent → schedule next probe,
        # (b) wait throttle interval, (c) probe again with session
        # positive → suppress.
        await asyncio.sleep(2.5)
        # Land terminal so the dispatch completes cleanly.
        now_iso = datetime.now(tz=UTC).isoformat(timespec="seconds")
        await storage_with_task.execute(
            "UPDATE dispatches SET status = 'completed', "
            "last_tool_called = ?, last_activity_at = ?, "
            "completed_at = ? WHERE id = ?",
            ("complete_dispatch", now_iso, now_iso, dispatch_id),
        )

    activity_task = asyncio.create_task(_flip_session_then_complete())
    try:
        outcome = await dispatcher.dispatch(
            task_id="task-1", role="implementer",
            role_agent_id="impl-agent-id", prompt="ignored",
            actor="eng-agent-id",
        )
    finally:
        await activity_task

    assert outcome.status == "completed", (
        f"session-jsonl-only activity must NOT be killed by stage 2; "
        f"got status={outcome.status!r} notes={outcome.notes!r}"
    )
    audit_rows = await storage_with_task.fetch_all(
        "SELECT action FROM audit_log "
        "WHERE action = 'dispatch.cold_start_hang_detected'"
    )
    assert len(audit_rows) == 0, (
        "β-S11-17.5 session-signal-fix: stage 2 must NOT fire terminal "
        "when session-jsonl activity is observed, even if worktree is "
        f"silent; got {len(audit_rows)} cold_start_hang_detected audits"
    )
