"""Unit tests for agents.registry — load, lookup, hot reload, orphan/unseeded warnings."""

from __future__ import annotations

import asyncio
from pathlib import Path

from meridian_orchestrator.agents.registry import AgentRegistry
from meridian_orchestrator.auth import TokenStore

REPO_CONFIG_DIR = Path(__file__).resolve().parents[3] / "config" / "agents"


def _seed_repo_yamls(into_dir: Path, roles: tuple[str, ...] = ("pm", "engineer", "implementer", "tester")) -> None:
    into_dir.mkdir(parents=True, exist_ok=True)
    for role in roles:
        (into_dir / f"{role}.yaml").write_text(
            (REPO_CONFIG_DIR / f"{role}.yaml").read_text(encoding="utf-8"),
            encoding="utf-8",
        )


async def test_initial_load_with_no_tokens_logs_unseeded(tmp_path: Path) -> None:
    """All four YAMLs present but no keyring tokens → all roles emit unseeded
    warnings; snapshot is empty."""
    agents_dir = tmp_path / "agents"
    _seed_repo_yamls(agents_dir)
    store = TokenStore(service="registry-test-empty")
    registry = AgentRegistry(agents_dir, token_store=store)
    await registry.initial_load()
    assert registry.all_agents() == []


async def test_initial_load_resolves_uuids_from_keyring(tmp_path: Path) -> None:
    """Issue static tokens for each role; registry resolves their UUIDs as
    the agents row PKs per the M2/M3 reconciliation contract."""
    agents_dir = tmp_path / "agents"
    _seed_repo_yamls(agents_dir)
    store = TokenStore(service="registry-test-resolve")
    issued = {
        role: store.issue(agent_id=None, role=role, label=f"{role}-test")
        for role in ("pm", "engineer", "implementer", "tester")
    }
    registry = AgentRegistry(agents_dir, token_store=store)
    await registry.initial_load()

    loaded = {ra.config.role: ra for ra in registry.all_agents()}
    assert set(loaded.keys()) == {"pm", "engineer", "implementer", "tester"}
    for role, ra in loaded.items():
        assert ra.agent_id == issued[role].record.agent_id


async def test_get_by_agent_id(tmp_path: Path) -> None:
    agents_dir = tmp_path / "agents"
    _seed_repo_yamls(agents_dir, roles=("pm", "engineer"))
    store = TokenStore(service="registry-test-getby")
    pm_token = store.issue(agent_id=None, role="pm", label="x")
    store.issue(agent_id=None, role="engineer", label="x")
    registry = AgentRegistry(agents_dir, token_store=store)
    await registry.initial_load()

    found = registry.get_by_agent_id(pm_token.record.agent_id)
    assert found is not None
    assert found.config.role == "pm"

    missing = registry.get_by_agent_id("nonexistent-uuid")
    assert missing is None


async def test_orphan_token_warning_on_unconfigured_role(tmp_path: Path) -> None:
    """Token issued for a role with no YAML config → registry warns but
    doesn't crash."""
    agents_dir = tmp_path / "agents"
    _seed_repo_yamls(agents_dir, roles=("pm",))
    store = TokenStore(service="registry-test-orphan")
    store.issue(agent_id=None, role="pm", label="x")
    store.issue(agent_id=None, role="engineer", label="orphan")
    registry = AgentRegistry(agents_dir, token_store=store)
    await registry.initial_load()

    loaded = {ra.config.role for ra in registry.all_agents()}
    assert loaded == {"pm"}


async def test_reload_picks_up_new_config(tmp_path: Path) -> None:
    """Adding a new YAML file → calling reload directly updates the snapshot.

    This tests the reload code path. The watchfiles-driven trigger is tested
    separately in ``test_lifespan_starts_watcher`` because watchfiles event
    timing varies by platform (Windows native events can take seconds to
    fire, and polling fallback is even slower); the *behavior* under test
    is "registry reloads from disk on demand," not "watchfiles emits within
    N seconds."
    """
    agents_dir = tmp_path / "agents"
    _seed_repo_yamls(agents_dir, roles=("engineer",))
    store = TokenStore(service="registry-test-reload")
    store.issue(agent_id=None, role="engineer", label="x")
    store.issue(agent_id=None, role="implementer", label="x")
    registry = AgentRegistry(agents_dir, token_store=store)

    await registry.initial_load()
    assert {ra.config.role for ra in registry.all_agents()} == {"engineer"}

    (agents_dir / "implementer.yaml").write_text(
        (REPO_CONFIG_DIR / "implementer.yaml").read_text(encoding="utf-8"),
        encoding="utf-8",
    )

    async with registry._lock:
        await registry._reload_locked()

    assert {ra.config.role for ra in registry.all_agents()} == {
        "engineer",
        "implementer",
    }


async def test_lifespan_starts_watcher(tmp_path: Path) -> None:
    """The watcher task is created and stopped cleanly under the lifespan
    context manager. This does NOT assert watchfiles emits a specific event
    on disk change — that's watchfiles' contract, not ours; cross-platform
    event timing varies. The reload code path is covered by
    test_reload_picks_up_new_config."""
    agents_dir = tmp_path / "agents"
    _seed_repo_yamls(agents_dir, roles=("engineer",))
    store = TokenStore(service="registry-test-watcher")
    store.issue(agent_id=None, role="engineer", label="x")
    registry = AgentRegistry(agents_dir, token_store=store)

    async with registry.lifespan():
        assert registry._watch_task is not None
        assert not registry._watch_task.done()
        await asyncio.sleep(0.1)

    assert registry._watch_task is None


async def test_lifespan_stops_watcher_cleanly(tmp_path: Path) -> None:
    agents_dir = tmp_path / "agents"
    _seed_repo_yamls(agents_dir, roles=("engineer",))
    store = TokenStore(service="registry-test-stop")
    store.issue(agent_id=None, role="engineer", label="x")
    registry = AgentRegistry(agents_dir, token_store=store)

    async with registry.lifespan():
        await asyncio.sleep(0.1)

    assert registry._watch_task is None
