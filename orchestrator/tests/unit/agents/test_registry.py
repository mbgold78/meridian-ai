"""Unit tests for agents.registry — load, lookup, hot reload, orphan/unseeded warnings."""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator, Sequence
from pathlib import Path
from typing import Any

import pytest
import pytest_asyncio

from meridian_orchestrator.agents.registry import AgentRegistry
from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.seed import seed_initial
from meridian_orchestrator.storage.sqlite import SqliteStorage

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
        role: await store.issue(agent_id=None, role=role, label=f"{role}-test")
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
    pm_token = await store.issue(agent_id=None, role="pm", label="x")
    await store.issue(agent_id=None, role="engineer", label="x")
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
    await store.issue(agent_id=None, role="pm", label="x")
    await store.issue(agent_id=None, role="engineer", label="orphan")
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
    await store.issue(agent_id=None, role="engineer", label="x")
    await store.issue(agent_id=None, role="implementer", label="x")
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
    await store.issue(agent_id=None, role="engineer", label="x")
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
    await store.issue(agent_id=None, role="engineer", label="x")
    registry = AgentRegistry(agents_dir, token_store=store)

    async with registry.lifespan():
        await asyncio.sleep(0.1)

    assert registry._watch_task is None


# ---------------------------------------------------------------------------
# seed_into_storage (M5-subscription-routing commit 0.5)
# ---------------------------------------------------------------------------


@pytest_asyncio.fixture
async def seeded_storage(tmp_path: Path) -> AsyncIterator[tuple[SqliteStorage, str]]:
    """SqliteStorage with migrations applied + seed_initial run.

    Yields (storage, company_id). The agents table is empty — that's
    the seed_into_storage tests' job to populate.
    """
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    company_id, _ = await seed_initial(s)
    yield s, company_id
    await s.close()


async def _registry_with_full_roster(
    agents_dir: Path, service: str
) -> AgentRegistry:
    """Helper: load all four canonical YAMLs + issue static tokens for
    every role. Returns a fully-loaded AgentRegistry whose snapshot has
    all four agents resolved.
    """
    _seed_repo_yamls(agents_dir)
    store = TokenStore(service=service)
    for role in ("pm", "engineer", "implementer", "tester"):
        await store.issue(agent_id=None, role=role, label=f"{role}-seed-test")
    registry = AgentRegistry(agents_dir, token_store=store)
    await registry.initial_load()
    return registry


async def test_seed_agents_into_storage_inserts_row_per_registered_agent(
    tmp_path: Path,
    seeded_storage: tuple[SqliteStorage, str],
) -> None:
    """Every agent in the in-memory snapshot becomes one row in the
    agents table. Closes Codex Finding 1's production gap: prior to
    this method, the registry's docstring claimed to seed the table
    but no production INSERT existed; only test fixtures wrote agents
    rows.
    """
    storage, company_id = seeded_storage
    agents_dir = tmp_path / "agents"
    registry = await _registry_with_full_roster(
        agents_dir, "registry-seed-insert"
    )

    await registry.seed_into_storage(storage, company_id)

    rows = await storage.fetch_all(
        "SELECT id, role, company_id, config_path FROM agents ORDER BY role"
    )
    assert len(rows) == 4
    by_role = {r["role"]: r for r in rows}
    assert set(by_role.keys()) == {"pm", "engineer", "implementer", "tester"}
    for role, row in by_role.items():
        assert row["company_id"] == company_id
        # config_path is the agents_dir + role.yaml; reconstructable at
        # read time so future maintainers see the convention.
        assert row["config_path"] == str(agents_dir / f"{role}.yaml")
        # agent_id matches the registry's resolved snapshot UUID.
        ra = registry.get(role)  # type: ignore[arg-type]
        assert ra is not None
        assert row["id"] == ra.agent_id


async def test_seed_agents_into_storage_resolves_reports_to_uuid(
    tmp_path: Path,
    seeded_storage: tuple[SqliteStorage, str],
) -> None:
    """reports_to is stored as the parent agent's UUID (FK to
    agents.id), not the parent's role name. Pass 2 of the seed
    resolves YAML role-strings to UUIDs against the in-memory snapshot.
    PM has reports_to=NULL; engineer reports to PM; impl/tester report
    to engineer per the canonical YAML hierarchy.
    """
    storage, company_id = seeded_storage
    agents_dir = tmp_path / "agents"
    registry = await _registry_with_full_roster(
        agents_dir, "registry-seed-reports-to"
    )

    await registry.seed_into_storage(storage, company_id)

    rows = await storage.fetch_all(
        "SELECT id, role, reports_to FROM agents"
    )
    by_role = {r["role"]: r for r in rows}
    pm_id = by_role["pm"]["id"]
    engineer_id = by_role["engineer"]["id"]

    # PM reports to no one (Mike is human, not an agent).
    assert by_role["pm"]["reports_to"] is None
    # Engineer reports to PM.
    assert by_role["engineer"]["reports_to"] == pm_id
    # Implementer + Tester both report to Engineer.
    assert by_role["implementer"]["reports_to"] == engineer_id
    assert by_role["tester"]["reports_to"] == engineer_id


async def test_seed_agents_into_storage_idempotent_on_repeated_call(
    tmp_path: Path,
    seeded_storage: tuple[SqliteStorage, str],
) -> None:
    """Calling seed_into_storage twice produces the same final row
    set — no duplicates, no exceptions. UPSERT semantics: ON CONFLICT
    DO UPDATE on the (id) PRIMARY KEY. Critical for daemon restart
    cycles where the same registry snapshot re-runs the seed.
    """
    storage, company_id = seeded_storage
    agents_dir = tmp_path / "agents"
    registry = await _registry_with_full_roster(
        agents_dir, "registry-seed-idempotent"
    )

    await registry.seed_into_storage(storage, company_id)
    rows_first = await storage.fetch_all(
        "SELECT id, role, reports_to FROM agents ORDER BY role"
    )

    await registry.seed_into_storage(storage, company_id)
    rows_second = await storage.fetch_all(
        "SELECT id, role, reports_to FROM agents ORDER BY role"
    )

    assert len(rows_first) == 4
    assert len(rows_second) == 4
    assert rows_first == rows_second


async def test_seed_agents_into_storage_updates_mutable_fields_on_yaml_change(
    tmp_path: Path,
    seeded_storage: tuple[SqliteStorage, str],
) -> None:
    """When the registry's snapshot reflects a YAML edit (e.g.,
    operator relocated config_path or restructured reports_to), the
    next seed propagates those mutable fields via UPSERT. Identity-
    stable fields (id, role, company_id) preserved.

    Simulated by relocating the agents_dir between two registry loads
    against the same storage. The keyring tokens (and therefore the
    resolved agent UUIDs) stay the same; only the on-disk path
    changes. Verifies config_path UPDATE without a row recreate.
    """
    storage, company_id = seeded_storage
    agents_dir_1 = tmp_path / "agents-original"
    registry_1 = await _registry_with_full_roster(
        agents_dir_1, "registry-seed-update"
    )

    await registry_1.seed_into_storage(storage, company_id)
    rows_v1 = await storage.fetch_all(
        "SELECT id, role, config_path FROM agents ORDER BY role"
    )
    pm_v1 = next(r for r in rows_v1 if r["role"] == "pm")

    # Same keyring service → same token UUIDs → same agent_ids. New
    # agents_dir relocates the YAMLs.
    agents_dir_2 = tmp_path / "agents-relocated"
    _seed_repo_yamls(agents_dir_2)
    store = TokenStore(service="registry-seed-update")
    registry_2 = AgentRegistry(agents_dir_2, token_store=store)
    await registry_2.initial_load()

    await registry_2.seed_into_storage(storage, company_id)
    rows_v2 = await storage.fetch_all(
        "SELECT id, role, config_path FROM agents ORDER BY role"
    )
    pm_v2 = next(r for r in rows_v2 if r["role"] == "pm")

    # PM's UUID is identity-stable across the two seeds (same keyring
    # token resolved to the same agent_id).
    assert pm_v1["id"] == pm_v2["id"]
    # config_path mutated to the relocated path.
    assert pm_v1["config_path"] == str(agents_dir_1 / "pm.yaml")
    assert pm_v2["config_path"] == str(agents_dir_2 / "pm.yaml")
    # Same number of rows; UPSERT did not duplicate.
    assert len(rows_v1) == 4
    assert len(rows_v2) == 4


async def test_seed_agents_into_storage_atomic_on_pass_two_failure(
    tmp_path: Path,
    seeded_storage: tuple[SqliteStorage, str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """If pass 2 (the reports_to UPDATE pass) raises, pass 1's
    UPSERTs roll back. The agents table returns to its pre-call
    state — no partial-seed rows persist.

    Critical invariant per seed_into_storage's docstring; future
    refactors that move the transaction boundary or split the passes
    into separate transactions would silently break atomicity. This
    test makes the contract durably enforced; failure message names
    the regression mode.
    """
    storage, company_id = seeded_storage
    agents_dir = tmp_path / "agents"
    registry = await _registry_with_full_roster(
        agents_dir, "registry-seed-atomic"
    )

    real_execute = storage.execute

    async def failing_execute(
        query: str, params: Sequence[Any] | None = None
    ) -> None:
        # Fail specifically on the reports_to UPDATE — pass 2 of the
        # two-pass seed. Pass 1's INSERT...ON CONFLICT goes through
        # normally; the UPDATE-reports_to is what raises.
        if (
            query.lstrip().upper().startswith("UPDATE AGENTS")
            and "reports_to" in query
        ):
            raise RuntimeError(
                "simulated pass-2 UPDATE failure for atomicity test"
            )
        await real_execute(query, params)

    monkeypatch.setattr(storage, "execute", failing_execute)

    with pytest.raises(RuntimeError, match="simulated pass-2 UPDATE failure"):
        await registry.seed_into_storage(storage, company_id)

    # Restore real execute for the post-call assertion.
    monkeypatch.setattr(storage, "execute", real_execute)

    # No agents rows persist — pass 1's UPSERTs rolled back when
    # pass 2 raised inside the same transaction.
    rows = await storage.fetch_all("SELECT id FROM agents")
    assert rows == [], (
        "agents rows persisted despite pass-2 UPDATE failure; the "
        "single-transaction wrapping around pass 1 + pass 2 in "
        "seed_into_storage has been removed or the rollback isn't "
        "reaching pass 1's INSERTs. Without atomic rollback: a "
        "half-seeded agents table is visible to the next daemon boot, "
        "with rows whose reports_to is NULL when YAML expects a parent."
    )


async def test_seed_agents_into_storage_raises_on_missing_company_id(
    tmp_path: Path,
    seeded_storage: tuple[SqliteStorage, str],
) -> None:
    """Empty-string company_id raises ValueError before touching SQL.
    Documents the failure mode that arises if a future refactor
    breaks the lifespan ordering (seed_initial returning a falsy
    value, or main.py calling seed_into_storage before seed_initial).

    With main.py's current ordering this never happens in production —
    seed_initial returns a non-empty UUID by construction. The sentinel
    test guards against future refactors that reorder lifespan steps.
    """
    storage, _company_id = seeded_storage
    agents_dir = tmp_path / "agents"
    registry = await _registry_with_full_roster(
        agents_dir, "registry-seed-missing-company"
    )

    with pytest.raises(ValueError, match="company_id"):
        await registry.seed_into_storage(storage, "")

    # No rows written — the guard fires before any INSERT runs.
    rows = await storage.fetch_all("SELECT id FROM agents")
    assert rows == []


# ---------------------------------------------------------------------------
# OAuth-PM registry binding (Component 1 post-β architectural remediation):
# OAuth callers join the agent_id-keyed snapshot via _reload_locked picking
# up active OAuth tokens + via register_oauth_caller for explicit
# registration-on-issuance paths. registry.get(role) preserves existing
# semantics (returns static-source supervisor); registry.get_by_agent_id
# resolves both static and OAuth identities.
# ---------------------------------------------------------------------------


async def test_reload_locked_includes_oauth_tokens_in_snapshot(
    tmp_path: Path,
) -> None:
    """Active OAuth tokens for known roles → registry snapshot includes
    them keyed by agent_id with source='oauth'. Multiple agents per
    role coexist (static-source supervisor + OAuth-source operator)."""
    agents_dir = tmp_path / "agents"
    _seed_repo_yamls(agents_dir, roles=("pm",))
    store = TokenStore(service="registry-test-oauth-reload")

    # Static PM (supervisor identity)
    static_pm = await store.issue(agent_id=None, role="pm", label="static-pm")
    # OAuth PM (operator identity)
    oauth_pm = await store.issue(
        agent_id=None,
        role="pm",
        label="oauth:test",
        source="oauth",
        client_id="cid-mike",
    )

    registry = AgentRegistry(agents_dir, token_store=store)
    await registry.initial_load()

    # Snapshot includes both agents keyed by agent_id
    all_agents = registry.all_agents()
    agent_ids = {ra.agent_id for ra in all_agents}
    assert static_pm.record.agent_id in agent_ids
    assert oauth_pm.record.agent_id in agent_ids

    # source field distinguishes them
    sources_by_agent_id = {ra.agent_id: ra.source for ra in all_agents}
    assert sources_by_agent_id[static_pm.record.agent_id] == "static"
    assert sources_by_agent_id[oauth_pm.record.agent_id] == "oauth"


async def test_get_role_returns_static_when_oauth_agent_exists_for_same_role(
    tmp_path: Path,
) -> None:
    """get(role) returns the static-source supervisor agent even when
    an OAuth-source agent exists for the same role. Existing call sites
    (engineer_supervisor, pm_supervisor, dispatch resolver helpers) all
    expect supervisor semantics; OAuth callers are only reachable via
    get_by_agent_id."""
    agents_dir = tmp_path / "agents"
    _seed_repo_yamls(agents_dir, roles=("pm",))
    store = TokenStore(service="registry-test-get-role-static")

    static_pm = await store.issue(agent_id=None, role="pm", label="static-pm")
    oauth_pm = await store.issue(
        agent_id=None,
        role="pm",
        label="oauth:operator",
        source="oauth",
        client_id="cid-op",
    )

    registry = AgentRegistry(agents_dir, token_store=store)
    await registry.initial_load()

    found = registry.get("pm")
    assert found is not None
    assert found.source == "static"
    assert found.agent_id == static_pm.record.agent_id
    assert found.agent_id != oauth_pm.record.agent_id


async def test_get_by_agent_id_resolves_oauth_caller(tmp_path: Path) -> None:
    """get_by_agent_id resolves both static and OAuth agent_ids — direct
    dict lookup against the agent_id-keyed snapshot."""
    agents_dir = tmp_path / "agents"
    _seed_repo_yamls(agents_dir, roles=("pm",))
    store = TokenStore(service="registry-test-getby-oauth")
    static_pm = await store.issue(agent_id=None, role="pm", label="static-pm")
    oauth_pm = await store.issue(
        agent_id=None,
        role="pm",
        label="oauth:op",
        source="oauth",
        client_id="cid-op",
    )

    registry = AgentRegistry(agents_dir, token_store=store)
    await registry.initial_load()

    static_resolved = registry.get_by_agent_id(static_pm.record.agent_id)
    assert static_resolved is not None
    assert static_resolved.source == "static"

    oauth_resolved = registry.get_by_agent_id(oauth_pm.record.agent_id)
    assert oauth_resolved is not None
    assert oauth_resolved.source == "oauth"
    # Same yaml config (pm.yaml) regardless of source
    assert oauth_resolved.config.role == "pm"


async def test_register_oauth_caller_adds_snapshot_entry_and_agents_row(
    tmp_path: Path,
) -> None:
    """register_oauth_caller adds an oauth-source RegisteredAgent to the
    snapshot keyed by agent_id AND UPSERTs the agents row in storage.
    Idempotent on repeat calls (alpha persistence reuse pattern)."""
    storage = SqliteStorage(tmp_path / "test.db")
    await storage.connect()
    try:
        await run_migrations(storage)
        company_id, _project_id = await seed_initial(storage)

        agents_dir = tmp_path / "agents"
        _seed_repo_yamls(agents_dir, roles=("pm",))
        store = TokenStore(service="registry-test-register-oauth")
        # Static PM exists so registry's reload picks up at least one
        # role; needed for register_oauth_caller's role validation path.
        await store.issue(agent_id=None, role="pm", label="static-pm")
        registry = AgentRegistry(agents_dir, token_store=store)
        await registry.initial_load()

        # Simulate OAuth issuance path: caller has an agent_id minted
        # via TokenStore.issue (or reused via alpha persistence); now
        # register it.
        oauth_agent_id = "oauth-caller-uuid-xyz"
        await registry.register_oauth_caller(
            storage=storage,
            company_id=company_id,
            agent_id=oauth_agent_id,
            role="pm",
        )

        # Snapshot entry present with source='oauth'
        resolved = registry.get_by_agent_id(oauth_agent_id)
        assert resolved is not None
        assert resolved.source == "oauth"
        assert resolved.config.role == "pm"

        # agents table row present with reports_to=NULL (operator
        # identities aren't org-chart entities)
        rows = await storage.fetch_all(
            "SELECT id, role, reports_to FROM agents WHERE id = ?",
            (oauth_agent_id,),
        )
        assert len(rows) == 1
        assert rows[0]["role"] == "pm"
        assert rows[0]["reports_to"] is None

        # Idempotent: re-registering same agent_id with same role
        # doesn't duplicate or fail; snapshot + storage stable.
        await registry.register_oauth_caller(
            storage=storage,
            company_id=company_id,
            agent_id=oauth_agent_id,
            role="pm",
        )
        rows_after = await storage.fetch_all(
            "SELECT id FROM agents WHERE id = ?", (oauth_agent_id,)
        )
        assert len(rows_after) == 1

        # registry.get('pm') still returns static (preserves supervisor
        # semantics under existing call sites)
        static_found = registry.get("pm")
        assert static_found is not None
        assert static_found.source == "static"
    finally:
        await storage.close()
