"""End-to-end PM compaction lifecycle test (M5-continuous-pm-driver
commit 8).

Exercises the full compaction-prep flow under F9.0 Reading Y
disposition (subprocess-side handoff via tools/pm_handoff.py +
pm.yaml; PMSupervisor unchanged):

1. PM-continuous subprocess running with active dispatch.
2. PM calls :func:`tools.pm_handoff.write_pm_handoff` to author
   handoff content (simulating the conserve-trigger compaction-prep
   sequence).
3. PM calls :func:`tools.supervisor.signal_compaction` to signal
   imminent clean exit (Q3-A widening: PM is allowed at the role
   gate).
4. Fake subprocess clean-exits.
5. PMSupervisor's watchdog observes the exit + the
   ``_compaction_pending`` flag, audits ``pm.compaction_completed``,
   marks the dispatch row ``status='compacted'``, respawns fresh
   without incrementing the respawn-cap counter.
6. The new (post-respawn) PM "subprocess" calls
   :func:`tools.pm_handoff.get_most_recent_handoff` to load the
   prior handoff content as resumed-context.

Asserts:

- File at ``<handoff_dir>/<iso-timestamp>.md`` exists with PM's
  authored content.
- Dispatch lifecycle: original → ``compacted``; new → ``running``.
- Audit chain captures all three events in PM namespace:
  ``pm.compaction.handoff_written``, ``pm.compaction_triggered``,
  ``pm.compaction_completed``.
- get_most_recent_handoff returns the content PM wrote, with
  matching written_at.
- Respawn-cap counter does NOT increment (compaction is healthy
  lifecycle).

This is the load-bearing integration test for F9.0 Reading Y's
end-to-end correctness. Per memory
`feedback_integration_defects_need_production_shape_tests`: the
component-isolated unit tests + MCP wrapper tests don't catch
defects that only surface when the full path executes against
real fixtures. This test exercises the full path.
"""

from __future__ import annotations

import asyncio
import json
from collections.abc import AsyncIterator, Callable
from pathlib import Path

import pytest
import pytest_asyncio

from meridian_orchestrator.agents.registry import AgentRegistry
from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.bridge.pm_supervisor import PMSupervisor
from meridian_orchestrator.bridge.supervisor_base import (
    SupervisorLaunchContext,
    SupervisorSubprocessHandle,
)
from meridian_orchestrator.config import Settings
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.tools.pm_handoff import (
    WritePmHandoffInput,
    get_most_recent_handoff,
    write_pm_handoff,
)
from meridian_orchestrator.tools.supervisor import (
    SignalCompactionInput,
    signal_compaction,
)

REPO_CONFIG_DIR = Path(__file__).resolve().parents[2] / "config" / "agents"


def _seed_repo_yamls(into_dir: Path) -> None:
    into_dir.mkdir(parents=True, exist_ok=True)
    for role in ("pm", "engineer", "implementer", "tester"):
        (into_dir / f"{role}.yaml").write_text(
            (REPO_CONFIG_DIR / f"{role}.yaml").read_text(encoding="utf-8"),
            encoding="utf-8",
        )


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(data_dir=tmp_path)


class _ControllableProc:
    def __init__(self) -> None:
        self._returncode: int | None = None
        self._exit = asyncio.Event()

    @property
    def pid(self) -> int | None:
        return 4242

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
        if self._returncode is None:
            self._returncode = -9
            self._exit.set()

    def signal_clean_exit(self) -> None:
        if self._returncode is None:
            self._returncode = 0
            self._exit.set()


async def _wait_until(
    predicate: Callable[[], bool],
    *,
    timeout: float = 5.0,
    interval: float = 0.05,
) -> None:
    deadline = asyncio.get_event_loop().time() + timeout
    while asyncio.get_event_loop().time() < deadline:
        if predicate():
            return
        await asyncio.sleep(interval)
    raise AssertionError("timed out waiting for predicate")


async def test_pm_compaction_e2e_handoff_write_signal_respawn_read(
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """Full lifecycle: PM writes handoff → signals compaction →
    subprocess exits → supervisor respawns → new PM reads handoff
    content as resumed-context."""
    handoff_dir = tmp_path / "pm-handoffs"

    def get_handoff_dir() -> Path:
        return handoff_dir

    # --- Setup PM supervisor with controllable proc queue --------------
    agents_dir = tmp_path / "config" / "agents"
    _seed_repo_yamls(agents_dir)
    token_store = TokenStore(service="pm-compaction-e2e")
    issued = await token_store.issue(
        agent_id=None, role="pm", label="pm-supervisor-test"
    )
    pm_agent_id = issued.record.agent_id
    registry = AgentRegistry(agents_dir, token_store=token_store)
    await registry.initial_load()

    initial_proc = _ControllableProc()
    next_proc = _ControllableProc()
    proc_queue: list[_ControllableProc] = [initial_proc, next_proc]

    async def launcher(
        ctx: SupervisorLaunchContext,
    ) -> SupervisorSubprocessHandle:
        return proc_queue.pop(0)

    supervisor = PMSupervisor(
        registry=registry,
        working_directory=settings.pm_continuous_dir,
        storage=storage,
        token_store=token_store,
        subprocess_launcher=launcher,
        timeout_seconds=10,
        check_interval_seconds=0.05,
        respawn_cap=3,
        respawn_window_seconds=60,
    )

    await supervisor.start()
    initial_dispatch_id = supervisor.current_dispatch_id
    assert initial_dispatch_id is not None

    try:
        # --- Phase 1: PM authors and writes the handoff doc -----------
        handoff_content = (
            "# PM Compaction Handoff\n\n"
            "## current_focus\n"
            "Shipping M5-continuous-pm-driver commit 8.\n\n"
            "## planned_next\n"
            "Commit 9: e2e tests + closeout.\n\n"
            "## open_questions\n"
            "None at this checkpoint.\n\n"
            "## recent_decisions_pointer\n"
            "F9.0 Reading Y disposition; tools/pm_handoff.py.\n\n"
            "## cohort_summary\n"
            "Workflow: M5-continuous-pm-driver chain.\n"
        )
        write_out = await write_pm_handoff(
            storage=storage,
            get_handoff_dir=get_handoff_dir,
            request=WritePmHandoffInput(content=handoff_content),
            caller_agent_id=pm_agent_id,
            caller_role="pm",
        )

        # File exists at the closure-resolved location
        assert Path(write_out.path).exists()
        assert Path(write_out.path).parent == handoff_dir
        assert Path(write_out.path).read_text(encoding="utf-8") == handoff_content

        # --- Phase 2: PM signals compaction --------------------------
        signal_out = await signal_compaction(
            storage=storage,
            supervisor=supervisor,
            request=SignalCompactionInput(
                iteration_marker="e2e-conserve-trigger",
                proxy_value=0.85,
            ),
            caller_agent_id=pm_agent_id,
            caller_role="pm",
        )
        assert signal_out.accepted is True
        assert supervisor._compaction_pending is True

        # --- Phase 3: Subprocess clean-exits -------------------------
        initial_proc.signal_clean_exit()

        # --- Phase 4: Wait for watchdog respawn ----------------------
        await _wait_until(
            lambda: supervisor.current_dispatch_id is not None
            and supervisor.current_dispatch_id != initial_dispatch_id,
            timeout=4.0,
        )

        # Original row → 'compacted'; new row → 'running'
        old_row = await storage.fetch_one(
            "SELECT status FROM dispatches WHERE id = ?",
            (initial_dispatch_id,),
        )
        assert old_row is not None
        assert old_row["status"] == "compacted"

        new_row = await storage.fetch_one(
            "SELECT status FROM dispatches WHERE id = ?",
            (supervisor.current_dispatch_id,),
        )
        assert new_row is not None
        assert new_row["status"] == "running"

        # Compaction-respawn does not increment the cap counter
        assert len(supervisor._respawn_history) == 0
        # _compaction_pending reset on the fresh spawn
        assert supervisor._compaction_pending is False

        # --- Phase 5: Audit chain captures all three events ----------
        # handoff_written audits target=path (filesystem path);
        # compaction events target the dispatch_id.
        path_actions = [
            r["action"]
            for r in await storage.fetch_all(
                "SELECT action FROM audit_log WHERE target = ?",
                (write_out.path,),
            )
        ]
        assert "pm.compaction.handoff_written" in path_actions

        dispatch_actions = [
            r["action"]
            for r in await storage.fetch_all(
                "SELECT action FROM audit_log WHERE target = ? "
                "ORDER BY timestamp, rowid",
                (initial_dispatch_id,),
            )
        ]
        assert "pm.compaction_triggered" in dispatch_actions
        assert "pm.compaction_completed" in dispatch_actions

        # The handoff_written audit's details preserve the path/size
        handoff_audit = await storage.fetch_one(
            "SELECT details_json FROM audit_log "
            "WHERE action = 'pm.compaction.handoff_written' AND target = ?",
            (write_out.path,),
        )
        assert handoff_audit is not None
        details = json.loads(handoff_audit["details_json"])
        assert details["path"] == write_out.path
        assert details["content_size"] == len(handoff_content)

        # --- Phase 6: New PM (post-respawn) reads the handoff --------
        # Simulates the new PM subprocess's startup get_most_recent_handoff
        # call. Same handler, same closure — no daemon-side
        # _reconstruct_state involvement (per F9.0 Reading Y).
        read_out = await get_most_recent_handoff(
            get_handoff_dir=get_handoff_dir,
            caller_role="pm",
        )
        assert read_out.content == handoff_content
        assert read_out.written_at == write_out.written_at
    finally:
        await supervisor.shutdown()
