"""M5-pm-supervisor-base acceptance tests.

Exercises ``PMSupervisor`` end-to-end with a controllable subprocess
fake, mirroring ``test_engineer_supervisor.py``'s structure. The
launcher receives the registry-loaded prompt, the per-spec working
directory (``<data_dir>/pm-continuous/``), and the pm agent_id resolved
from the keyring token — all wired through the M3 AgentRegistry rather
than hardcoded.

Four scenarios:

1. **Launcher context shape.** Verifies the prompt, cwd, role+label,
   and token agent_id match the registry's pm config + Settings.
2. **Activity keeps watchdog quiet.** A fake PM subprocess that
   periodically calls ``record_mcp_call`` keeps
   ``dispatches.last_activity_at`` fresh; the watchdog never fires.
3. **Silence triggers heartbeat timeout → respawn.** Inverse of (2):
   a fake subprocess that never makes MCP calls leaves activity NULL;
   the watchdog COALESCE-falls-back-to-started_at and fires the
   timeout sequence; respawn produces a fresh dispatch row.
4. **`_reconstruct_state` is no-op.** Enforces the architectural
   invariant from M5-pm-supervisor-base prep checkpoint P-2 Reading B:
   PMSupervisor's ``_reconstruct_state`` is no-op. Handoff-doc reading
   is a subprocess-prompt concern landed at M5-continuous-pm-driver.
   A future commit that silently adds daemon-side state-read behavior
   to ``_reconstruct_state`` will fail this test, prompting deliberate
   reconsideration of the layer separation.

The supervisor_base tests already cover the watchdog state machine
exhaustively; this file focuses on the pm-specific wiring plus the
no-op invariant.
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
from meridian_orchestrator.bridge.pm_supervisor import PMSupervisor
from meridian_orchestrator.bridge.supervisor_base import (
    LongRunningSupervisor,
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


# ----- Fake subprocess -----------------------------------------------------


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


async def _build_registry_with_pm(
    tmp_path: Path, *, service: str
) -> tuple[AgentRegistry, TokenStore, str]:
    """Seed YAMLs + issue a pm token + load the registry. Returns the
    registry, the token_store used to issue, and the resolved pm
    agent_id."""
    agents_dir = tmp_path / "config" / "agents"
    _seed_repo_yamls(agents_dir)
    store = TokenStore(service=service)
    issued = store.issue(agent_id=None, role="pm", label="supervisor-test")
    registry = AgentRegistry(agents_dir, token_store=store)
    await registry.initial_load()
    pm = registry.get("pm")
    assert pm is not None
    return registry, store, issued.record.agent_id


# ----- Tests ---------------------------------------------------------------


async def test_launcher_receives_registry_loaded_pm_prompt_and_pm_continuous_cwd(
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """The launcher is invoked with a SupervisorLaunchContext whose prompt
    is the registry's pm.yaml system_prompt and whose working_directory
    is settings.pm_continuous_dir. The dispatch token's role is 'pm'
    with label 'pm-continuous', bound to the pm agent_id resolved from
    the keyring."""
    registry, token_store, expected_agent_id = await _build_registry_with_pm(
        tmp_path, service="pm-sup-launcher"
    )
    expected_prompt = registry.get("pm").config.system_prompt  # type: ignore[union-attr]

    captured: list[SupervisorLaunchContext] = []

    async def capturing_launcher(
        ctx: SupervisorLaunchContext,
    ) -> SupervisorSubprocessHandle:
        captured.append(ctx)
        return _ControllableProc(cooperative_terminate=True)

    supervisor = PMSupervisor(
        registry=registry,
        working_directory=settings.pm_continuous_dir,
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
        # role-with-label per dispatches.role enum (pm-continuous, not pm)
        assert ctx.role == "pm-continuous"
        assert ctx.working_directory == settings.pm_continuous_dir

        # Token bound to the pm agent_id from the keyring lookup
        token_record = token_store.lookup_by_token_id(
            supervisor._current_token.record.token_id  # type: ignore[union-attr]
        )
        assert token_record is not None
        assert token_record.role == "pm"
        assert token_record.label == "pm-continuous"
        assert token_record.agent_id == expected_agent_id

        # Token surfaces in env so the subprocess can authenticate
        assert ctx.token == supervisor._current_token.token  # type: ignore[union-attr]
    finally:
        await supervisor.shutdown()


async def test_supervisor_start_inserts_pm_continuous_dispatch_row(
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """M5-flag-flips commit 6 flag-flip smoke test (mirror of
    EngineerSupervisor's commit 5 smoke test): a started PMSupervisor
    inserts exactly one ``dispatches`` row with ``role='pm-continuous'``
    and ``status='running'``. Existing tests (launcher context, watchdog
    activity, heartbeat respawn, _reconstruct_state no-op) implicitly
    rely on this row but don't assert it directly. This smoke test pins
    the lifecycle-row contract explicitly so accidental drift in the
    supervisor's spawn path surfaces in CI rather than only manifesting
    through downstream-test failure cascades.

    Note: ``role='pm-continuous'`` (not ``'pm-supervisor'``) per
    ``pm_supervisor.py:_token_label()`` returning ``'pm-continuous'``;
    ``supervisor_base.py:_token_role_with_label()`` writes the label to
    the dispatches.role column. Verified empirically at commit 6 prep."""
    registry, token_store, _ = await _build_registry_with_pm(
        tmp_path, service="pm-sup-flag-flip-smoke"
    )

    async def launcher(
        ctx: SupervisorLaunchContext,
    ) -> SupervisorSubprocessHandle:
        return _ControllableProc(cooperative_terminate=True)

    supervisor = PMSupervisor(
        registry=registry,
        working_directory=settings.pm_continuous_dir,
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
            "WHERE role = 'pm-continuous'"
        )
        assert len(rows) == 1
        assert rows[0]["role"] == "pm-continuous"
        assert rows[0]["status"] == "running"
        assert rows[0]["id"] == supervisor._current_dispatch_id
    finally:
        await supervisor.shutdown()


async def test_construction_rejects_unregistered_pm(
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """If no pm agent resolves from the registry (no keyring seed token
    issued), construction raises ValidationError. The wiring layer in
    main.py must not enable enable_pm_supervisor without first seeding
    the token via scripts/issue-token.py."""
    agents_dir = tmp_path / "config" / "agents"
    _seed_repo_yamls(agents_dir)
    store = TokenStore(service="pm-sup-no-token")
    # No token issued — registry.get("pm") returns None
    registry = AgentRegistry(agents_dir, token_store=store)
    await registry.initial_load()
    assert registry.get("pm") is None

    with pytest.raises(ValidationError, match="pm"):
        PMSupervisor(
            registry=registry,
            working_directory=settings.pm_continuous_dir,
            storage=storage,
            token_store=store,
        )


async def test_activity_updates_keep_watchdog_quiet(
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """A fake PM subprocess that periodically calls record_mcp_call keeps
    dispatches.last_activity_at fresh; the watchdog never fires because
    COALESCE(last_activity_at, started_at) stays under the timeout.
    Mirrors the engineer-supervisor activity test."""
    registry, token_store, _ = await _build_registry_with_pm(
        tmp_path, service="pm-sup-quiet"
    )

    captured_proc: list[_ControllableProc] = []

    async def launcher(
        ctx: SupervisorLaunchContext,
    ) -> SupervisorSubprocessHandle:
        proc = _ControllableProc(cooperative_terminate=True)
        captured_proc.append(proc)
        return proc

    supervisor = PMSupervisor(
        registry=registry,
        working_directory=settings.pm_continuous_dir,
        storage=storage,
        token_store=token_store,
        subprocess_launcher=launcher,
        timeout_seconds=1,
        check_interval_seconds=0.05,
    )

    activity_task: asyncio.Task[None] | None = None

    async def fake_pm_loop() -> None:
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
        activity_task = asyncio.create_task(fake_pm_loop())
        # Run for ~2.5s — well past the 1s timeout. If the watchdog were
        # firing, dispatch_id would change.
        await asyncio.sleep(2.5)

        assert supervisor._current_dispatch_id == initial_dispatch_id
        assert not captured_proc[0].terminate_called
        assert not supervisor.is_halted

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
    """A fake PM subprocess that never makes MCP calls leaves
    last_activity_at NULL; the watchdog COALESCE-falls-back to
    started_at and fires the timeout sequence after timeout_seconds.
    Respawn produces a fresh dispatch row."""
    registry, token_store, _ = await _build_registry_with_pm(
        tmp_path, service="pm-sup-silent"
    )

    async def launcher(
        ctx: SupervisorLaunchContext,
    ) -> SupervisorSubprocessHandle:
        return _ControllableProc(cooperative_terminate=True)

    supervisor = PMSupervisor(
        registry=registry,
        working_directory=settings.pm_continuous_dir,
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
        # fresh token and a fresh dispatch_id.
        original_row = await storage.fetch_one(
            "SELECT status FROM dispatches WHERE id = ?",
            (initial_dispatch_id,),
        )
        assert original_row is not None
        assert original_row["status"] == "timed_out"

        new_row = await storage.fetch_one(
            "SELECT status, role FROM dispatches WHERE id = ?",
            (supervisor._current_dispatch_id,),
        )
        assert new_row is not None
        assert new_row["status"] == "running"
        assert new_row["role"] == "pm-continuous"
    finally:
        await supervisor.shutdown()


async def test_reconstruct_state_is_noop_at_initial_spawn_and_respawn(
    storage: SqliteStorage, settings: Settings, tmp_path: Path
) -> None:
    """Enforces the M5-pm-supervisor-base prep checkpoint P-2 Reading B
    invariant: PMSupervisor._reconstruct_state is no-op. Handoff-doc
    reading is a subprocess-prompt concern landed at
    M5-continuous-pm-driver, not daemon-side bookkeeping.

    Three checks:

    1. PMSupervisor explicitly defines _reconstruct_state in its own
       __dict__ (not inherited from base). The override is deliberate —
       a future commit that removes the override would inherit base's
       no-op and silently lose the architectural decision's
       documentation.
    2. Direct invocation returns None.
    3. Invoking _reconstruct_state writes no audit_log rows. Captures
       audit row count before/after; future commits that add daemon-
       side state-read behavior (audit writes, dispatch row updates,
       etc.) will diverge the counts and fail the test informatively.

    A future commit that wants daemon-side handoff-doc reading should
    explicitly remove this test, write a new spec, and land the new
    behavior with explicit operator-facing docs — not silently accrete
    behavior in _reconstruct_state.
    """
    registry, token_store, _ = await _build_registry_with_pm(
        tmp_path, service="pm-sup-noop"
    )

    # Check 1: PMSupervisor explicitly defines _reconstruct_state.
    # A future inherit-from-base shape would lose the no-op
    # documentation; the explicit override is part of the contract.
    assert "_reconstruct_state" in PMSupervisor.__dict__, (
        "PMSupervisor must explicitly override _reconstruct_state to "
        "preserve the no-op-with-future-proofing-docstring contract. "
        "Inheriting base's no-op would functionally work but lose the "
        "P-2 Reading B documentation that downstream readers need."
    )
    # Sanity: verify the base has the same method shape (so the
    # override-vs-inherit check is meaningful).
    assert hasattr(LongRunningSupervisor, "_reconstruct_state")

    async def launcher(
        ctx: SupervisorLaunchContext,
    ) -> SupervisorSubprocessHandle:
        return _ControllableProc(cooperative_terminate=True)

    supervisor = PMSupervisor(
        registry=registry,
        working_directory=settings.pm_continuous_dir,
        storage=storage,
        token_store=token_store,
        subprocess_launcher=launcher,
        # Short timeout so a respawn fires during the test window.
        timeout_seconds=1,
        check_interval_seconds=0.05,
    )

    # Check 2: direct invocation returns None.
    assert await supervisor._reconstruct_state() is None

    await supervisor.start()
    initial_dispatch_id = supervisor._current_dispatch_id
    assert initial_dispatch_id is not None

    try:
        # Wait for a respawn so we exercise the post-respawn branch
        # of _reconstruct_state's invocation path.
        await _wait_until(
            lambda: supervisor._current_dispatch_id is not None
            and supervisor._current_dispatch_id != initial_dispatch_id,
            timeout=4.0,
        )

        # Check 3: a fresh _reconstruct_state invocation writes no
        # audit rows. Snapshot before/after; equal counts confirm
        # no-op. (The supervisor lifecycle itself writes audit rows
        # during spawn/respawn — this check isolates the
        # _reconstruct_state call by counting only the delta of one
        # direct invocation.)
        before = await storage.fetch_one(
            "SELECT COUNT(*) AS n FROM audit_log"
        )
        before_count = int(before["n"]) if before is not None else 0

        result = await supervisor._reconstruct_state()
        assert result is None

        after = await storage.fetch_one(
            "SELECT COUNT(*) AS n FROM audit_log"
        )
        after_count = int(after["n"]) if after is not None else 0
        assert after_count == before_count, (
            f"_reconstruct_state wrote {after_count - before_count} audit "
            "row(s); expected zero. A future commit has added daemon-side "
            "behavior to PMSupervisor._reconstruct_state — this contradicts "
            "the M5-pm-supervisor-base prep checkpoint P-2 Reading B "
            "architectural decision (handoff-doc reading is a subprocess-"
            "prompt concern at M5-continuous-pm-driver, not daemon "
            "bookkeeping). Remove this test deliberately and re-spec the "
            "new behavior, or revert the offending commit."
        )
    finally:
        await supervisor.shutdown()
