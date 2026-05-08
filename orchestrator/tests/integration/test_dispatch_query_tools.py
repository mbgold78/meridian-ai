"""M4c-tooling integration tests for the dispatch query surface.

Five+ tests covering:

1. ``get_recent_dispatches`` Engineer-scoped: returns dispatches whose
   tasks were claimed by the Engineer caller; excludes other engineers'
   dispatches; excludes supervisor lifecycle rows.
2. ``get_recent_dispatches`` PM-scoped: returns all sub-agent dispatches
   regardless of claimed_by; supervisor rows still excluded.
3. ``get_recent_dispatches`` Implementer/Tester denied with PERMISSION_DENIED.
4. ``get_dispatch_events`` filters caller-supplied dispatch_ids through
   the same scoping rules; audit rows for excluded dispatch_ids are not
   returned.
5. ``get_dispatch_events`` since_timestamp filter narrows the audit-row
   window correctly.
6. BridgeDispatcher integration: when ``model`` is passed, the dispatcher
   reads ``tasks.estimated_tokens`` and uses ``calculate_timeout`` for
   the effective timeout instead of the legacy 300s default.

Tests use the same controllable subprocess fake pattern as
``test_dispatch_lifecycle.py``.
"""

from __future__ import annotations

import asyncio
import shutil
import subprocess
from collections.abc import AsyncIterator
from pathlib import Path

import pytest
import pytest_asyncio

from meridian_orchestrator.audit.logger import write_audit
from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.bridge.canonical import CanonicalClone
from meridian_orchestrator.bridge.dispatcher import (
    BridgeDispatcher,
    DispatchLaunchContext,
    SubprocessHandle,
)
from meridian_orchestrator.errors import PermissionError as OrchestratorPermissionError
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.tools.dispatch import (
    GetDispatchEventsInput,
    GetRecentDispatchesInput,
    get_dispatch_events,
    get_recent_dispatches,
)

pytestmark = pytest.mark.skipif(
    shutil.which("git") is None, reason="git is not on PATH"
)


def _git(*args: str, cwd: Path) -> None:
    subprocess.run(["git", *args], cwd=str(cwd), check=True, capture_output=True)


@pytest.fixture
def upstream_repo(tmp_path: Path) -> Path:
    upstream = tmp_path / "upstream"
    upstream.mkdir()
    _git("init", "-b", "main", cwd=upstream)
    _git("config", "user.email", "test@example.com", cwd=upstream)
    _git("config", "user.name", "Test", cwd=upstream)
    _git("config", "receive.denyCurrentBranch", "ignore", cwd=upstream)
    (upstream / "README.md").write_text("substrate\n", encoding="utf-8")
    _git("add", "README.md", cwd=upstream)
    _git("commit", "-m", "initial", cwd=upstream)
    return upstream


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    await s.execute("INSERT INTO companies (id, name) VALUES ('co1', 'T')")
    await s.execute(
        "INSERT INTO projects (id, company_id, name) VALUES ('p1', 'co1', 'P')"
    )
    yield s
    await s.close()


async def _seed_task(
    storage: SqliteStorage,
    *,
    task_id: str,
    claimed_by: str | None = None,
    agent_role: str = "implementer",
    estimated_tokens: int | None = None,
) -> None:
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description, claimed_by, estimated_tokens) "
        "VALUES (?, 'co1', 'p1', ?, 't', ?, ?)",
        (task_id, agent_role, claimed_by, estimated_tokens),
    )


async def _seed_dispatch(
    storage: SqliteStorage,
    *,
    dispatch_id: str,
    task_id: str | None,
    role: str,
    status: str = "completed",
    actual_touched_files_json: str | None = None,
    completed_at: str = "2026-05-01T10:00:00.000000+00:00",
) -> None:
    await storage.execute(
        "INSERT INTO dispatches "
        "(id, task_id, role, status, working_directory, timeout_seconds, "
        "actual_touched_files_json, completed_at) "
        "VALUES (?, ?, ?, ?, '/tmp/test', 300, ?, ?)",
        (
            dispatch_id,
            task_id,
            role,
            status,
            actual_touched_files_json,
            completed_at,
        ),
    )


# ----- get_recent_dispatches scoping -------------------------------------


async def test_get_recent_dispatches_engineer_sees_only_own_subagent_dispatches(
    storage: SqliteStorage,
) -> None:
    """Engineer's view: dispatches whose tasks were claimed by Engineer.
    Excludes other engineers' dispatches AND supervisor lifecycle rows."""
    # Two engineers' worth of work + one supervisor row.
    await _seed_task(storage, task_id="t-eng1-a", claimed_by="eng1")
    await _seed_task(storage, task_id="t-eng1-b", claimed_by="eng1")
    await _seed_task(storage, task_id="t-eng2", claimed_by="eng2")

    await _seed_dispatch(
        storage, dispatch_id="d-eng1-a", task_id="t-eng1-a", role="implementer"
    )
    await _seed_dispatch(
        storage, dispatch_id="d-eng1-b", task_id="t-eng1-b", role="tester"
    )
    await _seed_dispatch(
        storage, dispatch_id="d-eng2", task_id="t-eng2", role="implementer"
    )
    # Engineer-supervisor row (NULL task_id per Decision Q)
    await _seed_dispatch(
        storage,
        dispatch_id="d-supervisor",
        task_id=None,
        role="engineer-supervisor",
        status="running",
        completed_at=None,  # type: ignore[arg-type]
    )

    out = await get_recent_dispatches(
        storage=storage,
        request=GetRecentDispatchesInput(),
        caller_agent_id="eng1",
        caller_role="engineer",
    )

    ids = sorted(d.id for d in out.dispatches)
    assert ids == ["d-eng1-a", "d-eng1-b"]


async def test_get_recent_dispatches_pm_sees_all_subagent_dispatches(
    storage: SqliteStorage,
) -> None:
    """PM's view: all sub-agent dispatches regardless of claimed_by.
    Supervisor rows still excluded."""
    await _seed_task(storage, task_id="t-eng1", claimed_by="eng1")
    await _seed_task(storage, task_id="t-eng2", claimed_by="eng2")

    await _seed_dispatch(
        storage, dispatch_id="d-eng1", task_id="t-eng1", role="implementer"
    )
    await _seed_dispatch(
        storage, dispatch_id="d-eng2", task_id="t-eng2", role="tester"
    )
    await _seed_dispatch(
        storage,
        dispatch_id="d-pm-cont",
        task_id=None,
        role="pm-continuous",
        status="running",
        completed_at=None,  # type: ignore[arg-type]
    )

    out = await get_recent_dispatches(
        storage=storage,
        request=GetRecentDispatchesInput(),
        caller_agent_id="pm-agent",
        caller_role="pm",
    )

    ids = sorted(d.id for d in out.dispatches)
    assert ids == ["d-eng1", "d-eng2"]


async def test_get_recent_dispatches_implementer_tester_denied(
    storage: SqliteStorage,
) -> None:
    """Sub-agent roles use get_dispatch_status / get_dispatch_result, not
    these query tools."""
    for role in ("implementer", "tester"):
        with pytest.raises(OrchestratorPermissionError):
            await get_recent_dispatches(
                storage=storage,
                request=GetRecentDispatchesInput(),
                caller_agent_id="some-agent",
                caller_role=role,
            )


async def test_get_recent_dispatches_actual_touched_files_parses(
    storage: SqliteStorage,
) -> None:
    """JSON column → list[str]; NULL → empty list."""
    await _seed_task(storage, task_id="t1", claimed_by="eng1")
    await _seed_task(storage, task_id="t2", claimed_by="eng1")
    await _seed_dispatch(
        storage,
        dispatch_id="d-with",
        task_id="t1",
        role="implementer",
        actual_touched_files_json='["foo.py","bar.py"]',
    )
    await _seed_dispatch(
        storage,
        dispatch_id="d-null",
        task_id="t2",
        role="implementer",
        actual_touched_files_json=None,
    )

    out = await get_recent_dispatches(
        storage=storage,
        request=GetRecentDispatchesInput(),
        caller_agent_id="eng1",
        caller_role="engineer",
    )
    by_id = {d.id: d for d in out.dispatches}
    assert sorted(by_id["d-with"].actual_touched_files) == ["bar.py", "foo.py"]
    assert by_id["d-null"].actual_touched_files == []


# ----- get_dispatch_events scoping ---------------------------------------


async def test_get_dispatch_events_filters_through_scoping(
    storage: SqliteStorage,
) -> None:
    """Audit rows for dispatch_ids the caller can't see are not returned —
    even when the caller asks for them by id."""
    await _seed_task(storage, task_id="t-eng1", claimed_by="eng1")
    await _seed_task(storage, task_id="t-eng2", claimed_by="eng2")
    await _seed_dispatch(
        storage, dispatch_id="d-eng1", task_id="t-eng1", role="implementer"
    )
    await _seed_dispatch(
        storage, dispatch_id="d-eng2", task_id="t-eng2", role="implementer"
    )
    await write_audit(
        storage,
        actor="system",
        action="dispatch.issued",
        target="d-eng1",
        details={"dispatch_id": "d-eng1"},
    )
    await write_audit(
        storage,
        actor="system",
        action="dispatch.issued",
        target="d-eng2",
        details={"dispatch_id": "d-eng2"},
    )

    # Engineer eng1 asks for both — should only get its own
    out = await get_dispatch_events(
        storage=storage,
        request=GetDispatchEventsInput(dispatch_ids=["d-eng1", "d-eng2"]),
        caller_agent_id="eng1",
        caller_role="engineer",
    )
    targets = sorted({e.target for e in out.events})
    assert targets == ["d-eng1"]


async def test_get_dispatch_events_since_timestamp_narrows_window(
    storage: SqliteStorage,
) -> None:
    """The since_timestamp filter trims old audit rows."""
    await _seed_task(storage, task_id="t1", claimed_by="eng1")
    await _seed_dispatch(
        storage, dispatch_id="d1", task_id="t1", role="implementer"
    )
    # Two audit rows close together — use direct SQL to set timestamps.
    await storage.execute(
        "INSERT INTO audit_log (id, timestamp, actor, action, target) "
        "VALUES ('a-old', '2026-04-01T00:00:00.000000+00:00', 'system', "
        "'dispatch.issued', 'd1')"
    )
    await storage.execute(
        "INSERT INTO audit_log (id, timestamp, actor, action, target) "
        "VALUES ('a-new', '2026-05-01T00:00:00.000000+00:00', 'system', "
        "'dispatch.subprocess_started', 'd1')"
    )

    # No filter: both
    out_all = await get_dispatch_events(
        storage=storage,
        request=GetDispatchEventsInput(dispatch_ids=["d1"]),
        caller_agent_id="eng1",
        caller_role="engineer",
    )
    assert {e.id for e in out_all.events} == {"a-old", "a-new"}

    # since_timestamp 2026-04-15: only the new one
    out_filtered = await get_dispatch_events(
        storage=storage,
        request=GetDispatchEventsInput(
            dispatch_ids=["d1"],
            since_timestamp="2026-04-15T00:00:00.000000+00:00",
        ),
        caller_agent_id="eng1",
        caller_role="engineer",
    )
    assert {e.id for e in out_filtered.events} == {"a-new"}


async def test_get_dispatch_events_implementer_tester_denied(
    storage: SqliteStorage,
) -> None:
    for role in ("implementer", "tester"):
        with pytest.raises(OrchestratorPermissionError):
            await get_dispatch_events(
                storage=storage,
                request=GetDispatchEventsInput(dispatch_ids=["d1"]),
                caller_agent_id="some-agent",
                caller_role=role,
            )


# ----- BridgeDispatcher uses calculate_timeout ---------------------------


class _FakeProc:
    def __init__(self) -> None:
        self._returncode: int | None = None
        self._exit = asyncio.Event()

    @property
    def pid(self) -> int | None:
        return 99999

    @property
    def returncode(self) -> int | None:
        return self._returncode

    async def wait(self) -> int:
        await self._exit.wait()
        assert self._returncode is not None
        return self._returncode

    def terminate(self) -> None:
        if self._returncode is None:
            self._returncode = 0
            self._exit.set()

    def kill(self) -> None:
        self._returncode = -9
        self._exit.set()


async def test_dispatcher_uses_calculate_timeout_when_model_passed(
    storage: SqliteStorage, upstream_repo: Path, tmp_path: Path
) -> None:
    """When BridgeDispatcher.dispatch is called with model + tasks.estimated_tokens
    set, the row's timeout_seconds reflects calculate_timeout(model, tokens),
    not the legacy 300s default."""
    canonical = CanonicalClone(
        path=tmp_path / "data" / "substrate-canonical",
        upstream_url=str(upstream_repo),
        default_branch="main",
    )
    await canonical.ensure_provisioned()
    token_store = TokenStore()

    # Task with 60000 estimated_tokens; sonnet at 60 tps → 1000s timeout
    await _seed_task(
        storage,
        task_id="task-sonnet-long",
        claimed_by="eng1",
        estimated_tokens=60_000,
    )

    proc = _FakeProc()
    background: list[asyncio.Task[None]] = []

    async def launcher(ctx: DispatchLaunchContext) -> SubprocessHandle:
        async def simulate() -> None:
            await asyncio.sleep(0)
            # Mark the dispatch completed so dispatch() returns
            await storage.execute(
                "UPDATE dispatches SET status = 'completed', "
                "completed_at = datetime('now') WHERE id = ?",
                (ctx.dispatch_id,),
            )
            await write_audit(
                storage,
                actor="impl",
                action="dispatch.complete_called",
                target=ctx.dispatch_id,
                details={},
            )
            proc._returncode = 0
            proc._exit.set()

        background.append(asyncio.create_task(simulate()))
        return proc

    dispatcher = BridgeDispatcher(
        storage=storage,
        token_store=token_store,
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=launcher,
        poll_interval_seconds=0.02,
    )

    spec = tmp_path / "spec.md"
    spec.write_text("test\n", encoding="utf-8")

    outcome = await dispatcher.dispatch(
        task_id="task-sonnet-long",
        role="implementer",
        role_agent_id="impl-agent",
        prompt=spec.read_text(encoding="utf-8"),
        spec_path_descriptor=spec,
        model="claude-sonnet-4-6",
        actor="eng-agent",
    )

    # Verify the dispatch row's timeout_seconds reflects calculate_timeout
    row = await storage.fetch_one(
        "SELECT timeout_seconds FROM dispatches WHERE id = ?",
        (outcome.dispatch_id,),
    )
    assert row is not None
    # 60000 tokens / 60 tps = 1000s, above the 300s floor
    assert row["timeout_seconds"] == 1000


async def test_dispatcher_legacy_default_when_no_model_passed(
    storage: SqliteStorage, upstream_repo: Path, tmp_path: Path
) -> None:
    """Backwards-compat: when model is None, the dispatcher uses the
    legacy timeout_seconds default (300). Verifies M4a/M4b tests still
    work with the new optional parameter."""
    canonical = CanonicalClone(
        path=tmp_path / "data" / "substrate-canonical",
        upstream_url=str(upstream_repo),
        default_branch="main",
    )
    await canonical.ensure_provisioned()
    token_store = TokenStore()
    await _seed_task(
        storage, task_id="task-default", claimed_by="eng1", estimated_tokens=100_000
    )

    proc = _FakeProc()
    background: list[asyncio.Task[None]] = []

    async def launcher(ctx: DispatchLaunchContext) -> SubprocessHandle:
        async def simulate() -> None:
            await asyncio.sleep(0)
            await storage.execute(
                "UPDATE dispatches SET status = 'completed', "
                "completed_at = datetime('now') WHERE id = ?",
                (ctx.dispatch_id,),
            )
            await write_audit(
                storage,
                actor="impl",
                action="dispatch.complete_called",
                target=ctx.dispatch_id,
                details={},
            )
            proc._returncode = 0
            proc._exit.set()

        background.append(asyncio.create_task(simulate()))
        return proc

    dispatcher = BridgeDispatcher(
        storage=storage,
        token_store=token_store,
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=launcher,
        poll_interval_seconds=0.02,
    )

    spec = tmp_path / "spec.md"
    spec.write_text("test\n", encoding="utf-8")

    outcome = await dispatcher.dispatch(
        task_id="task-default",
        role="implementer",
        role_agent_id="impl-agent",
        prompt=spec.read_text(encoding="utf-8"),
        spec_path_descriptor=spec,
        # NO model parameter — legacy default
        actor="eng-agent",
    )

    row = await storage.fetch_one(
        "SELECT timeout_seconds FROM dispatches WHERE id = ?",
        (outcome.dispatch_id,),
    )
    assert row is not None
    assert row["timeout_seconds"] == 300
