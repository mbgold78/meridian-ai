"""M4c-engineer-supervisor acceptance tests.

Exercises ``EngineerSupervisor`` end-to-end with a controllable subprocess
fake. The launcher receives the registry-loaded prompt, the per-spec
working directory (``<data_dir>/engineer/``), and the engineer agent_id
resolved from the keyring token — all wired through the M3 AgentRegistry
rather than hardcoded.

Three scenarios:

1. **Launcher context shape.** Verifies the prompt, cwd, and env vars
   match the registry's engineer config + Settings. Catches drift between
   the spec'd EngineerSupervisor hooks and what actually lands at the
   subprocess boundary.
2. **Activity keeps watchdog quiet.** A fake Engineer subprocess that
   periodically calls ``record_mcp_call`` (the same helper the production
   tool wrappers use) keeps ``dispatches.last_activity_at`` fresh; the
   watchdog never fires. Exercises the activity-writer ↔ watchdog
   integration in a production-shaped configuration.
3. **Silence triggers heartbeat timeout → respawn.** Inverse of (2): a
   fake subprocess that never makes MCP calls leaves activity NULL; the
   watchdog COALESCE-falls-back-to-started_at and fires the timeout
   sequence; respawn produces a fresh dispatch row.

The supervisor_base tests already cover the watchdog state machine
exhaustively; this file focuses on the engineer-specific wiring.
"""

from __future__ import annotations

import asyncio
import contextlib
from collections.abc import AsyncIterator, Callable
from pathlib import Path

import pytest
import pytest_asyncio

from meridian_orchestrator.agents.registry import AgentRegistry
from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.bridge.activity import record_mcp_call
from meridian_orchestrator.bridge.engineer_supervisor import EngineerSupervisor
from meridian_orchestrator.bridge.supervisor_base import (
    SupervisorLaunchContext,
    SupervisorSubprocessHandle,
)
from meridian_orchestrator.config import Settings
from meridian_orchestrator.errors import ValidationError
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage

REPO_CONFIG_DIR = Path(__file__).resolve().parents[2] / "config" / "agents"


def _seed_repo_yamls(into_dir: Path) -> None:
    """Copy the four canonical agent YAMLs from the repo into a test dir."""
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


# ----- Fake subprocess + capturing launcher -------------------------------


class _ControllableProc:
    def __init__(self, *, cooperative_terminate: bool = True) -> None:
        self._returncode: int | None = None
        self._exit = asyncio.Event()
        self._cooperative_terminate = cooperative_terminate
        self.terminate_called = False
        self.kill_called = False

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
        self.terminate_called = True
        if self._cooperative_terminate and self._returncode is None:
            self._returncode = 0
            self._exit.set()

    def kill(self) -> None:
        self.kill_called = True
        if self._returncode is None:
            self._returncode = -9
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


# ----- Common setup --------------------------------------------------------


async def _build_registry_with_engineer(
    tmp_path: Path, *, service: str
) -> tuple[AgentRegistry, TokenStore, str]:
    """Seed YAMLs + issue an engineer token + load the registry. Returns
    the registry, the token_store used to issue, and the resolved engineer
    agent_id."""
    agents_dir = tmp_path / "config" / "agents"
    _seed_repo_yamls(agents_dir)
    store = TokenStore(service=service)
    issued = await store.issue(agent_id=None, role="engineer", label="supervisor-test")
    registry = AgentRegistry(agents_dir, token_store=store)
    await registry.initial_load()
    engineer = registry.get("engineer")
    assert engineer is not None
    return registry, store, issued.record.agent_id


# ----- Tests ---------------------------------------------------------------


async def test_launcher_receives_registry_prompt_and_supervisor_dir(
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """The launcher is invoked with a SupervisorLaunchContext whose prompt
    is the registry's engineer.yaml system_prompt and whose
    working_directory is settings.engineer_supervisor_dir. The dispatch
    token's role is 'engineer' with label 'engineer-supervisor', bound
    to the engineer agent_id resolved from the keyring."""
    registry, token_store, expected_agent_id = await _build_registry_with_engineer(
        tmp_path, service="engineer-sup-launcher"
    )
    expected_prompt = registry.get("engineer").config.system_prompt  # type: ignore[union-attr]

    captured: list[SupervisorLaunchContext] = []

    async def capturing_launcher(
        ctx: SupervisorLaunchContext,
    ) -> SupervisorSubprocessHandle:
        captured.append(ctx)
        return _ControllableProc(cooperative_terminate=True)

    supervisor = EngineerSupervisor(
        registry=registry,
        working_directory=settings.engineer_supervisor_dir,
        storage=storage,
        token_store=token_store,
        subprocess_launcher=capturing_launcher,
        # Long timeout — we shut down before the watchdog fires.
        check_interval_seconds=10.0,
        timeout_seconds=600,
    )

    await supervisor.start()
    try:
        assert len(captured) == 1
        ctx = captured[0]

        # Prompt comes from the registry, not a hardcoded string or file path
        assert ctx.prompt == expected_prompt
        assert ctx.role == "engineer-supervisor"  # role-with-label per dispatches.role
        assert ctx.working_directory == settings.engineer_supervisor_dir

        # Token bound to the engineer agent_id from the keyring lookup
        token_record = await token_store.lookup_by_token_id(
            supervisor._current_token.record.token_id  # type: ignore[union-attr]
        )
        assert token_record is not None
        assert token_record.role == "engineer"
        assert token_record.label == "engineer-supervisor"
        assert token_record.agent_id == expected_agent_id

        # Token surfaces in env so the subprocess can authenticate
        assert ctx.token == supervisor._current_token.token  # type: ignore[union-attr]
    finally:
        await supervisor.shutdown()


async def test_supervisor_start_inserts_engineer_supervisor_dispatch_row(
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """M5-flag-flips commit 5 flag-flip smoke test: a started
    EngineerSupervisor inserts exactly one ``dispatches`` row with
    ``role='engineer-supervisor'`` and ``status='running'``. Existing
    tests (launcher context, watchdog activity, heartbeat respawn)
    implicitly rely on this row but don't assert it directly. This
    smoke test pins the lifecycle-row contract explicitly so accidental
    drift in the supervisor's spawn path surfaces in CI rather than
    only manifesting through downstream-test failure cascades."""
    registry, token_store, _ = await _build_registry_with_engineer(
        tmp_path, service="engineer-sup-flag-flip-smoke"
    )

    async def launcher(
        ctx: SupervisorLaunchContext,
    ) -> SupervisorSubprocessHandle:
        return _ControllableProc(cooperative_terminate=True)

    supervisor = EngineerSupervisor(
        registry=registry,
        working_directory=settings.engineer_supervisor_dir,
        storage=storage,
        token_store=token_store,
        subprocess_launcher=launcher,
        # Long timeout — we shut down before the watchdog fires.
        check_interval_seconds=10.0,
        timeout_seconds=600,
    )

    await supervisor.start()
    try:
        rows = await storage.fetch_all(
            "SELECT id, role, status FROM dispatches "
            "WHERE role = 'engineer-supervisor'"
        )
        assert len(rows) == 1
        assert rows[0]["role"] == "engineer-supervisor"
        assert rows[0]["status"] == "running"
        assert rows[0]["id"] == supervisor._current_dispatch_id
    finally:
        await supervisor.shutdown()


async def test_construction_rejects_unregistered_engineer(
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """If no engineer agent resolves from the registry (no keyring token
    issued), construction raises ValidationError. The wiring layer must
    not enable the supervisor flag without first seeding the token."""
    agents_dir = tmp_path / "config" / "agents"
    _seed_repo_yamls(agents_dir)
    store = TokenStore(service="engineer-sup-no-token")
    # No token issued — registry.get("engineer") returns None
    registry = AgentRegistry(agents_dir, token_store=store)
    await registry.initial_load()
    assert registry.get("engineer") is None

    with pytest.raises(ValidationError, match="engineer"):
        EngineerSupervisor(
            registry=registry,
            working_directory=settings.engineer_supervisor_dir,
            storage=storage,
            token_store=store,
        )


async def test_activity_updates_keep_watchdog_quiet(
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """A fake Engineer subprocess that periodically calls record_mcp_call
    keeps dispatches.last_activity_at fresh; the watchdog never fires
    because COALESCE(last_activity_at, started_at) stays under the
    timeout. Verifies the activity-writer ↔ watchdog integration."""
    registry, token_store, _ = await _build_registry_with_engineer(
        tmp_path, service="engineer-sup-quiet"
    )

    captured_proc: list[_ControllableProc] = []

    async def launcher(
        ctx: SupervisorLaunchContext,
    ) -> SupervisorSubprocessHandle:
        proc = _ControllableProc(cooperative_terminate=True)
        captured_proc.append(proc)
        return proc

    supervisor = EngineerSupervisor(
        registry=registry,
        working_directory=settings.engineer_supervisor_dir,
        storage=storage,
        token_store=token_store,
        subprocess_launcher=launcher,
        timeout_seconds=1,
        check_interval_seconds=0.05,
    )

    # Fake "Engineer iteration" task: every 0.3s, record an MCP call.
    # 0.3s << 1s timeout, so the watchdog never observes a stale row.
    activity_task: asyncio.Task[None] | None = None

    async def fake_engineer_loop() -> None:
        while True:
            await asyncio.sleep(0.3)
            token = supervisor._current_token
            if token is None:
                continue
            await record_mcp_call(
                storage, token.record.token_id, "get_window_state"
            )

    await supervisor.start()
    initial_dispatch_id = supervisor._current_dispatch_id

    try:
        activity_task = asyncio.create_task(fake_engineer_loop())
        # Run for ~2.5s — well past the 1s timeout. If the watchdog were
        # firing, dispatch_id would change.
        await asyncio.sleep(2.5)

        assert supervisor._current_dispatch_id == initial_dispatch_id
        assert not captured_proc[0].terminate_called
        assert not supervisor.is_halted

        # last_tool_called reflects the most recent MCP call
        row = await storage.fetch_one(
            "SELECT last_tool_called, last_activity_at FROM dispatches "
            "WHERE id = ?",
            (initial_dispatch_id,),
        )
        assert row is not None
        assert row["last_tool_called"] == "get_window_state"
        assert row["last_activity_at"] is not None
    finally:
        if activity_task is not None:
            activity_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await activity_task
        await supervisor.shutdown()


async def test_silence_triggers_heartbeat_timeout_and_respawn(
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """Inverse of the above. A fake Engineer subprocess that never makes
    MCP calls leaves last_activity_at NULL; the watchdog COALESCE-falls-
    back to started_at and fires the timeout sequence after timeout_seconds.
    Respawn produces a fresh dispatch row."""
    registry, token_store, _ = await _build_registry_with_engineer(
        tmp_path, service="engineer-sup-silent"
    )

    async def launcher(
        ctx: SupervisorLaunchContext,
    ) -> SupervisorSubprocessHandle:
        return _ControllableProc(cooperative_terminate=True)

    supervisor = EngineerSupervisor(
        registry=registry,
        working_directory=settings.engineer_supervisor_dir,
        storage=storage,
        token_store=token_store,
        subprocess_launcher=launcher,
        timeout_seconds=1,
        check_interval_seconds=0.05,
    )

    await supervisor.start()
    initial_dispatch_id = supervisor._current_dispatch_id
    assert initial_dispatch_id is not None

    try:
        await _wait_until(
            lambda: supervisor._current_dispatch_id is not None
            and supervisor._current_dispatch_id != initial_dispatch_id,
            timeout=4.0,
        )

        # The original row is timed_out; the new row is running with a
        # fresh token; both rows have task_id IS NULL (Decision Q).
        rows = await storage.fetch_all(
            "SELECT id, status, task_id FROM dispatches WHERE role = ? "
            "ORDER BY issued_at",
            ("engineer-supervisor",),
        )
        assert len(rows) >= 2
        assert rows[0]["id"] == initial_dispatch_id
        assert rows[0]["status"] == "timed_out"
        assert rows[0]["task_id"] is None
        # Find the new running row
        running_rows = [r for r in rows if r["status"] == "running"]
        assert len(running_rows) == 1
        assert running_rows[0]["id"] == supervisor._current_dispatch_id
        assert running_rows[0]["task_id"] is None
    finally:
        await supervisor.shutdown()
