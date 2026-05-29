"""Real-size integration test for the supervisor-spawn WinError 206 fix
(Operator Fix Window 2026-05-27).

Exercises the FULL PMSupervisor / EngineerSupervisor lifecycle path with
the REAL ``config/agents/*.yaml`` (PM is ~37 KB, Engineer ~30 KB) and
the REAL ``_default_launcher`` — only the bottom-most
``asyncio.create_subprocess_exec`` call is monkeypatched (to avoid
spawning real ``claude`` processes that would consume subscription
tokens).

This is the test that would have caught the original WinError 206 had
it existed before yesterday's daemon restart attempt: pre-fix the
argv composition put the full 37 KB system_prompt as a single argv
element, which Windows CreateProcess rejects.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock, Mock

import pytest
import pytest_asyncio

from meridian_orchestrator.agents.registry import AgentRegistry
from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.bridge.engineer_supervisor import EngineerSupervisor
from meridian_orchestrator.bridge.pm_supervisor import PMSupervisor
from meridian_orchestrator.config import Settings
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage

REPO_CONFIG_DIR = Path(__file__).resolve().parents[2] / "config" / "agents"


def _seed_repo_yamls(into_dir: Path) -> None:
    """Copy the canonical agent YAMLs from the repo into a test dir."""
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


@pytest.fixture
def captured_subprocess_args(
    monkeypatch: pytest.MonkeyPatch,
) -> list[tuple[Any, ...]]:
    """Capture argv passed to asyncio.create_subprocess_exec; return a
    fake proc that pretends to be alive (returncode=None) so the
    supervisor's start() completes without trying to terminate a real
    subprocess."""
    captured: list[tuple[Any, ...]] = []

    async def fake_create(*args: Any, **kwargs: Any) -> AsyncMock:
        captured.append(args)
        proc = AsyncMock()
        proc.pid = 9999
        proc.returncode = None
        proc.stderr = None
        # terminate()/kill() are sync on the SupervisorSubprocessHandle
        # Protocol; without these overrides AsyncMock auto-creates async
        # children whose coroutines are never awaited in shutdown() →
        # noisy RuntimeWarning. wait() stays async (AsyncMock default).
        proc.terminate = Mock()
        proc.kill = Mock()
        return proc

    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_create)
    return captured


async def _build_registry(
    tmp_path: Path, *, service: str, role: str
) -> tuple[AgentRegistry, TokenStore, str]:
    """Seed real YAMLs, issue a token, return registry + store + agent_id."""
    agents_dir = tmp_path / "config" / "agents"
    _seed_repo_yamls(agents_dir)
    store = TokenStore(service=service)
    issued = await store.issue(agent_id=None, role=role, label="real-size-test")
    registry = AgentRegistry(agents_dir, token_store=store)
    await registry.initial_load()
    assert registry.get(role) is not None
    return registry, store, issued.record.agent_id


# --- The real-size argv test --------------------------------------------


async def test_pm_supervisor_start_with_real_37k_prompt_keeps_argv_small(
    captured_subprocess_args: list[tuple[Any, ...]],
    storage: SqliteStorage,
    settings: Settings,
    tmp_path: Path,
) -> None:
    """PMSupervisor.start() with the real pm.yaml (37 KB system_prompt)
    must compose an argv well under the Windows CreateProcess limit. This
    is the test that reproduces yesterday's WinError 206 condition pre-fix
    and verifies the file-and-pointer fix in place."""
    registry, token_store, _ = await _build_registry(
        tmp_path, service="real-size-pm", role="pm"
    )
    expected_prompt = registry.get("pm").config.system_prompt  # type: ignore[union-attr]
    # Sanity: PM prompt is large enough to reproduce the WinError 206
    # condition pre-fix. YAML-parsed body is ~28 KB (the 37 KB file size
    # includes YAML overhead). With the other argv elements (token via
    # env, but path strings + flags), the combined CreateProcess argv
    # crossed the Windows usable limit in the live restart that
    # triggered this fix.
    assert len(expected_prompt) >= 20 * 1024

    dispatches_root = tmp_path / "dispatches"
    supervisor = PMSupervisor(
        registry=registry,
        working_directory=settings.pm_continuous_dir,
        storage=storage,
        token_store=token_store,
        # NO subprocess_launcher override — exercise the real
        # _default_launcher path.
        check_interval_seconds=60.0,
        timeout_seconds=600,
        dispatches_root=dispatches_root,
        # No daemon_url → mcp_config.json not written (irrelevant here).
    )

    await supervisor.start()
    try:
        assert len(captured_subprocess_args) == 1
        argv = list(captured_subprocess_args[0])

        # 1. Argv stays well under the Windows CreateProcess limit.
        total = sum(len(str(a)) for a in argv)
        assert total < 4096, (
            f"argv total {total} bytes — regression vs Windows 32KB limit"
        )

        # 2. --append-system-prompt-file=<path> present (single-token form).
        sysprompt_tokens = [
            a for a in argv if a.startswith("--append-system-prompt-file=")
        ]
        assert len(sysprompt_tokens) == 1
        path_str = sysprompt_tokens[0].split("=", 1)[1]
        sysprompt_path = Path(path_str)
        assert sysprompt_path.name == "system_prompt.md"
        assert sysprompt_path.parent.parent == dispatches_root

        # 3. File contains the full 37 KB system_prompt verbatim.
        on_disk = sysprompt_path.read_text(encoding="utf-8")
        assert on_disk == expected_prompt

        # 4. -p argument is the small kickoff (not the playbook).
        p_value = argv[argv.index("-p") + 1]
        assert "Meridian PM supervisor" in p_value
        assert expected_prompt not in argv

        # 5. The dispatches row recorded the spawn ('running' status).
        dispatch_id = supervisor.current_dispatch_id
        assert dispatch_id is not None
        row = await storage.fetch_one(
            "SELECT status FROM dispatches WHERE id = ?", (dispatch_id,)
        )
        assert row is not None
        assert row["status"] == "running"
    finally:
        # supervisor.shutdown() calls proc.wait(), but our AsyncMock's wait
        # returns immediately. Suppress any leftover state.
        import contextlib

        with contextlib.suppress(Exception):
            await supervisor.shutdown()


async def test_engineer_supervisor_start_with_real_prompt_keeps_argv_small(
    captured_subprocess_args: list[tuple[Any, ...]],
    storage: SqliteStorage,
    settings: Settings,
    tmp_path: Path,
) -> None:
    """Symmetric test for EngineerSupervisor with the real engineer.yaml
    (~30 KB system_prompt)."""
    registry, token_store, _ = await _build_registry(
        tmp_path, service="real-size-engineer", role="engineer"
    )
    # Engineer needs a substrate_working_directory to construct, but we
    # construct the supervisor directly here (bypassing the lifespan
    # validator). The supervisor itself doesn't read substrate.
    expected_prompt = registry.get("engineer").config.system_prompt  # type: ignore[union-attr]
    assert len(expected_prompt) >= 20 * 1024

    dispatches_root = tmp_path / "dispatches"
    supervisor = EngineerSupervisor(
        registry=registry,
        working_directory=settings.engineer_supervisor_dir,
        storage=storage,
        token_store=token_store,
        check_interval_seconds=60.0,
        timeout_seconds=600,
        dispatches_root=dispatches_root,
    )

    await supervisor.start()
    try:
        argv = list(captured_subprocess_args[0])
        total = sum(len(str(a)) for a in argv)
        assert total < 4096, f"argv total {total} bytes"
        sysprompt_tokens = [
            a for a in argv if a.startswith("--append-system-prompt-file=")
        ]
        assert len(sysprompt_tokens) == 1
        path_str = sysprompt_tokens[0].split("=", 1)[1]
        assert Path(path_str).read_text(encoding="utf-8") == expected_prompt
        assert "Meridian Engineer supervisor" in argv[argv.index("-p") + 1]
    finally:
        import contextlib

        with contextlib.suppress(Exception):
            await supervisor.shutdown()
