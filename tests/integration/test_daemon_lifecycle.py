"""Integration tests exercising the daemon's startup/shutdown lifecycle.

The full ``main()`` adds OS signal-handler wiring on top of
``_run_lifespan_body``. Signal delivery on Windows requires
console-event mechanics that don't lend themselves to subprocess
testing, so the storage-only tests exercise ``_lifespan`` directly,
and the full-stack lifespan tests exercise ``_run_lifespan_body``
with a programmatic shutdown event (extracted at M5-pm-mode-and-
window-state commit 7 for exactly this reason). The signal-handler
glue is exercised manually by Mike (Ctrl+C in an interactive
``uv run python -m meridian_orchestrator.main``).

Coverage:

- Storage-only lifespan: migrations run; storage closes cleanly;
  idempotent on re-open.
- Full lifespan with no oauth token: poller skipped with the INFO
  log; daemon starts/serves/shuts down cleanly without any poller
  involvement.
- Full lifespan with oauth token (using monkeypatched
  ``_make_default_fetcher``): poller starts after MCP ready; shutdown
  ordering runs poller-first per Q3 disposition; no exceptions.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any, ClassVar

import pytest
import pytest_asyncio
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client

from meridian_orchestrator import main as main_module
from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.bridge.oauth_usage import CachedSnapshot
from meridian_orchestrator.config import Settings
from meridian_orchestrator.main import _lifespan, _run_lifespan_body
from meridian_orchestrator.server import serve as serve_mcp
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage


async def test_lifespan_runs_migrations_and_closes(tmp_path: Path) -> None:
    """The lifespan context manager opens storage, runs migrations, closes cleanly."""
    settings = Settings(data_dir=tmp_path)
    async with _lifespan(settings) as storage:
        # Inside the lifespan: storage is connected and migrations are applied.
        rows = await storage.fetch_all(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"
        )
        table_names = {row["name"] for row in rows}
        assert "companies" in table_names
        assert "_schema_version" in table_names

        version_rows = await storage.fetch_all("SELECT version FROM _schema_version")
        assert {r["version"] for r in version_rows} == {1, 2, 3, 5, 6, 7, 8, 9, 10, 11}

    # After the lifespan: storage is closed, no exception raised.
    assert storage._conn is None  # type: ignore[attr-defined]


async def test_lifespan_idempotent_on_existing_db(tmp_path: Path) -> None:
    """Running the lifespan twice on the same data dir does not re-apply migrations."""
    settings = Settings(data_dir=tmp_path)
    async with _lifespan(settings):
        pass
    async with _lifespan(settings) as storage:
        version_rows = await storage.fetch_all("SELECT version FROM _schema_version")
        # Still exactly one row per migration version — not duplicated.
        assert {r["version"] for r in version_rows} == {1, 2, 3, 5, 6, 7, 8, 9, 10, 11}


# ---------------------------------------------------------------------------
# Full lifespan tests (M5-pm-mode-and-window-state commit 7)
# ---------------------------------------------------------------------------


def _free_port() -> int:
    import socket as _socket

    with _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _make_settings_for_full_lifespan(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    *,
    oauth_token: str | None = None,
) -> Settings:
    """Settings configured for the full-lifespan tests. Loopback MCP
    on a free port; supervisors disabled (the lifespan tests don't
    exercise subprocess-launching paths).

    ``oauth_token`` is set via env var because the
    ``claude_code_oauth_token`` field uses ``validation_alias=
    "CLAUDE_CODE_OAUTH_TOKEN"``, which (with the default
    ``populate_by_name=False``) means the field can only be
    populated via the alias. Setting the env var pre-Settings()
    construction is the correct path; constructor kwargs by Python
    field name are silently ignored.

    Also unsets any inherited CLAUDE_CODE_OAUTH_TOKEN to avoid the
    test accidentally picking up the developer's real token from
    the environment when ``oauth_token`` is None.
    """
    if oauth_token is not None:
        monkeypatch.setenv("CLAUDE_CODE_OAUTH_TOKEN", oauth_token)
    else:
        monkeypatch.delenv("CLAUDE_CODE_OAUTH_TOKEN", raising=False)
    return Settings(
        data_dir=tmp_path,
        mcp_host="127.0.0.1",
        mcp_port=_free_port(),
    )


async def _run_lifespan_briefly(
    settings: Settings,
    *,
    wait_for_condition: Any = None,
    max_settle_seconds: float = 15.0,
    shutdown_timeout_seconds: float = 30.0,
) -> None:
    """Spawn ``_run_lifespan_body`` as a task, wait for the daemon
    to reach a signaled steady state, set shutdown, await clean
    exit.

    ``wait_for_condition`` is a zero-arg callable returning bool.
    The helper polls it at 50ms intervals up to
    ``max_settle_seconds``. If None (the no-token tests), falls
    back to a brief fixed wait — the no-token path doesn't have a
    natural readiness signal.

    Why poll a condition instead of a fixed sleep: uvicorn startup
    latency on Windows can exceed 1s; the post-mcp-ready block
    (where the poller is constructed) doesn't run until
    ``mcp_ready`` fires. A fixed sleep races the startup; condition
    polling waits exactly as long as needed.

    Shutdown timeout is generous enough for the poller-first-then-
    supervisors-then-MCP shutdown sequence to complete.
    """
    shutdown = asyncio.Event()
    task = asyncio.create_task(
        _run_lifespan_body(settings, shutdown), name="lifespan-test"
    )
    try:
        if wait_for_condition is not None:
            poll_interval = 0.05
            elapsed = 0.0
            while elapsed < max_settle_seconds:
                if wait_for_condition():
                    break
                if task.done():
                    # Daemon exited unexpectedly during settle —
                    # surface its exception by awaiting.
                    await task
                    return
                await asyncio.sleep(poll_interval)
                elapsed += poll_interval
        else:
            # Fallback: brief fixed wait for the no-token case (no
            # natural signal to poll for).
            await asyncio.sleep(0.5)
        shutdown.set()
        await asyncio.wait_for(task, timeout=shutdown_timeout_seconds)
    finally:
        if not task.done():
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task


async def test_full_lifespan_skips_poller_when_token_unset(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """When CLAUDE_CODE_OAUTH_TOKEN is unset, the daemon starts and
    shuts down cleanly without constructing the OAuth usage poller.
    Q1 disposition: skip-with-warning, daemon stays up.

    Failure mode this test prevents: a future commit that mistakenly
    treats the no-token case as a startup error (Reading A from the
    Q1 prep that we deliberately rejected) would block daemon
    startup in dev/test scenarios.
    """
    settings = _make_settings_for_full_lifespan(
        tmp_path, monkeypatch, oauth_token=None
    )

    # Simply running through the lifespan without exception is the
    # main assertion. The skipped-no-token INFO log fires inside
    # _run_lifespan_body; we don't capture/assert log content here
    # (structlog routing varies by config); the lifespan completing
    # cleanly is the load-bearing signal.
    await _run_lifespan_briefly(settings)


# Shared shutdown-order recording list for the ordering test
# (test_full_lifespan_shuts_down_poller_before_supervisor). Each
# tracking fake's shutdown() appends its label; the test asserts
# the resulting list against the Q3 Reading X disposition order.
# Reset by the _reset_tracking_poller fixture between tests.
_shutdown_order: list[str] = []


class _TrackingPoller:
    """Drop-in replacement for ``OAuthUsagePoller`` used by the
    lifespan tests. Records the constructor args + start/shutdown
    invocations so the test can verify wiring without polling
    real (or even monkeypatched) httpx.

    Why a tracking fake instead of monkeypatching
    ``_make_default_fetcher`` per the prep recommendation: the
    real ``OAuthUsagePoller.__init__`` sets
    ``poll_interval_seconds`` from a default arg evaluated at
    function-definition time, so monkeypatching the module-level
    ``DEFAULT_POLL_INTERVAL_SECONDS`` after import has no effect on
    new instances. To get a fast first poll, we'd need to either
    pass an explicit cadence at construction time (which production
    code doesn't do) or replace the class entirely. The tracking
    fake replaces the class — simpler and more directly verifies
    the load-bearing wiring (constructor called, start awaited,
    shutdown awaited).

    Production behavioral coverage (real poller polling, snapshot
    caching, ceiling audit) is exercised at the unit-test layer in
    ``tests/unit/bridge/test_oauth_usage.py``; this lifespan test
    layer focuses on wiring, not poller mechanics.
    """

    instances: ClassVar[list[_TrackingPoller]] = []

    def __init__(self, **kwargs: Any) -> None:
        self.kwargs = kwargs
        self.oauth_token = kwargs.get("oauth_token")
        self.start_called = False
        self.shutdown_called = False
        _TrackingPoller.instances.append(self)

    async def start(self) -> None:
        self.start_called = True

    async def shutdown(self) -> None:
        self.shutdown_called = True
        _shutdown_order.append("poller")

    def latest_snapshot(self) -> CachedSnapshot | None:
        return None


class _TrackingPMSupervisor:
    """Drop-in replacement for ``PMSupervisor`` used by the
    shutdown-ordering test. Records start/shutdown invocations and
    appends ``"pm"`` to ``_shutdown_order`` on shutdown so the test
    can assert ordering relative to the poller.

    Mimics the minimal interface main.py exercises:
    ``__init__(registry=, working_directory=, storage=, token_store=)``,
    ``async start()``, ``async shutdown()``, ``_current_dispatch_id``
    attribute (used in the startup INFO log only).

    PMSupervisor was chosen over EngineerSupervisor for the ordering
    test because PM doesn't require ``substrate_working_directory``
    in Settings (the model_validator
    ``_require_substrate_working_directory_when_supervisor_enabled``
    only triggers for engineer_supervisor), so enabling it via the
    env var avoids needing canonical-clone provisioning fixtures.
    """

    instances: ClassVar[list[_TrackingPMSupervisor]] = []

    def __init__(self, **kwargs: Any) -> None:
        self.kwargs = kwargs
        self.start_called = False
        self.shutdown_called = False
        self._current_dispatch_id = "fake-pm-dispatch-id"
        _TrackingPMSupervisor.instances.append(self)

    async def start(self) -> None:
        self.start_called = True

    async def shutdown(self) -> None:
        self.shutdown_called = True
        _shutdown_order.append("pm")


@pytest.fixture(autouse=False)
def _reset_tracking_poller() -> None:
    _TrackingPoller.instances.clear()
    _TrackingPMSupervisor.instances.clear()
    _shutdown_order.clear()


async def test_full_lifespan_starts_and_stops_poller_when_token_set(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    _reset_tracking_poller: None,
) -> None:
    """When CLAUDE_CODE_OAUTH_TOKEN is set, the daemon constructs the
    OAuth usage poller after MCP ready, awaits its start(), and
    awaits its shutdown() during the shutdown sequence. Verifies
    Q1 disposition (skip-with-warning OFF when token IS set) AND
    Q3 disposition (poller construction + start + shutdown all wire
    correctly through the lifespan).

    Uses a tracking-fake class via monkeypatch on the
    ``OAuthUsagePoller`` symbol IN ``main.py``'s namespace —
    monkeypatching the symbol where main.py reads it ensures the
    fake is used regardless of how the real class's defaults work.
    Localized monkeypatch keeps production code surface clean.

    Failure mode this test prevents: a future commit that breaks
    poller construction in the lifespan (e.g., reversing the
    token-presence check; reordering so poller starts BEFORE
    mcp_ready and races; breaking the shutdown sequence so the
    poller's shutdown() never gets awaited and the task leaks past
    lifespan exit) lands silently. With-token lifespan coverage
    gap closure per the commit 7 prep review.
    """
    monkeypatch.setattr(main_module, "OAuthUsagePoller", _TrackingPoller)

    settings = _make_settings_for_full_lifespan(
        tmp_path, monkeypatch, oauth_token="test-oauth-token"
    )
    # Sanity: confirm the env-var-via-alias actually populated the
    # Settings field. If this fails, the test setup is broken
    # (probably a Pydantic-settings populate_by_name regression).
    assert settings.claude_code_oauth_token == "test-oauth-token"

    # Wait for the poller to be constructed AND start() to be
    # awaited (covers the full Q3 Reading A start path).
    await _run_lifespan_briefly(
        settings,
        wait_for_condition=lambda: (
            len(_TrackingPoller.instances) >= 1
            and _TrackingPoller.instances[0].start_called
        ),
    )

    # The lifespan constructed exactly one OAuthUsagePoller.
    assert len(_TrackingPoller.instances) == 1, (
        f"expected exactly one OAuthUsagePoller construction during "
        f"the lifespan (got {len(_TrackingPoller.instances)}); the "
        "Q1 disposition's token-presence check is broken — either "
        "the poller wasn't constructed when the token was set, or "
        "the lifespan accidentally constructed multiple pollers."
    )
    poller = _TrackingPoller.instances[0]

    # Constructor received the configured token.
    assert poller.oauth_token == "test-oauth-token", (
        f"poller constructed with oauth_token={poller.oauth_token!r}, "
        "expected 'test-oauth-token'. The lifespan isn't passing "
        "settings.claude_code_oauth_token to the constructor."
    )

    # start() was awaited (Q3 Start disposition).
    assert poller.start_called, (
        "poller.start() was not awaited during the lifespan; the "
        "post-mcp-ready start block isn't invoking start() on the "
        "constructed poller."
    )

    # shutdown() was awaited (Q3 Shutdown disposition; poller-first
    # ordering).
    assert poller.shutdown_called, (
        "poller.shutdown() was not awaited during the shutdown "
        "sequence; the finally block's poller-first shutdown path "
        "isn't running. A future commit that removed the poller "
        "shutdown branch from the finally block would fail this "
        "test informatively."
    )


async def test_full_lifespan_with_token_shutdown_completes_within_timeout(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    _reset_tracking_poller: None,
) -> None:
    """Symmetric to the previous test, but with a shorter shutdown
    timeout to verify the poller-shutdown path completes promptly.
    A future bug where the poller-shutdown hangs (e.g.,
    OAuthUsagePoller.shutdown's CancelledError handling broken)
    would fail this test by exceeding the wait_for timeout.

    The load-bearing assertion is implicit: the test passes only
    if ``_run_lifespan_briefly`` returns within
    ``shutdown_timeout_seconds`` (10s here, well under the
    production POLLER_SHUTDOWN_TIMEOUT_SECONDS=30 cap).
    """
    monkeypatch.setattr(main_module, "OAuthUsagePoller", _TrackingPoller)

    settings = _make_settings_for_full_lifespan(
        tmp_path, monkeypatch, oauth_token="test-token"
    )

    await _run_lifespan_briefly(
        settings,
        wait_for_condition=lambda: (
            len(_TrackingPoller.instances) >= 1
            and _TrackingPoller.instances[0].start_called
        ),
        shutdown_timeout_seconds=10.0,
    )


async def test_full_lifespan_shuts_down_poller_before_pm_supervisor(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    _reset_tracking_poller: None,
) -> None:
    """Q3 Reading X contract test: when both poller and PM supervisor
    are running, the shutdown sequence stops the poller FIRST, then
    the PM supervisor. Encodes the disposition's ordering at the
    test layer rather than relying solely on the source-code
    structure of the finally block.

    Failure mode this test prevents: a future commit reordering the
    finally block (e.g., ``shutdown supervisors first, then poller``
    — accidentally swapping the order during an unrelated refactor)
    would land silently. The reviewer flagged this gap at commit 7
    review: source-code-structure enforcement alone is insufficient
    because the bug class is single-block-reordering, which is the
    kind of change that can pass code review if the reviewer
    doesn't notice the implicit ordering contract.

    Setup: enable PM supervisor (no substrate_working_directory
    required for PM; EngineerSupervisor would require canonical-
    clone fixtures), set CLAUDE_CODE_OAUTH_TOKEN, replace both
    OAuthUsagePoller and PMSupervisor with tracking fakes that
    append to a shared ``_shutdown_order`` list on shutdown. The
    tracking fakes capture order via append-time, not start-time,
    so the assertion is on shutdown order specifically (start order
    is also poller-first per the disposition but covered separately).

    EngineerSupervisor is not exercised here — the same code path
    runs for both supervisors, and adding Engineer would require
    substrate_working_directory + canonical-clone provisioning
    setup that doesn't add coverage for the ordering contract
    (Q3 Reading X says "poller before supervisors" — PM stands in
    for the supervisor class generally).
    """
    monkeypatch.setattr(main_module, "OAuthUsagePoller", _TrackingPoller)
    monkeypatch.setattr(main_module, "PMSupervisor", _TrackingPMSupervisor)
    monkeypatch.setenv("MERIDIAN_ORCH_ENABLE_PM_SUPERVISOR", "true")

    settings = _make_settings_for_full_lifespan(
        tmp_path, monkeypatch, oauth_token="test-token"
    )
    assert settings.enable_pm_supervisor is True, (
        "PM supervisor flag did not propagate to Settings; the env "
        "var monkeypatch may not be effective for the model_validator."
    )

    await _run_lifespan_briefly(
        settings,
        wait_for_condition=lambda: (
            len(_TrackingPoller.instances) >= 1
            and len(_TrackingPMSupervisor.instances) >= 1
            and _TrackingPoller.instances[0].start_called
            and _TrackingPMSupervisor.instances[0].start_called
        ),
    )

    # Both shut down.
    assert _TrackingPoller.instances[0].shutdown_called
    assert _TrackingPMSupervisor.instances[0].shutdown_called

    # Order: poller first, then PM supervisor.
    assert _shutdown_order == ["poller", "pm"], (
        f"shutdown order is {_shutdown_order!r}, expected "
        "['poller', 'pm']. Q3 Reading X disposition (poller stops "
        "FIRST before supervisors) is broken — the finally block in "
        "main.py was reordered. Causality rationale: poller is the "
        "upstream observer of Mike's MCP activity; stopping it first "
        "removes one source of writes (ceiling-reached audit rows) "
        "during the rest of shutdown. Symmetric with start order."
    )


# ---------------------------------------------------------------------------
# Agents-table seeding production gap (M5-subscription-routing commit 0.5)
# ---------------------------------------------------------------------------


async def test_full_lifespan_seeds_agents_rows_before_serving_mcp(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Booting the lifespan against a fresh DB results in agents rows
    populated before any MCP request can arrive. Closes Codex Finding 1
    durably: prior to commit 0.5, the registry's docstring claimed to
    seed the agents table but no production INSERT existed; record_api_call
    would FK-fail on first invocation.

    Production-shape test (per the integration-defects-need-production-
    shape-tests memory): the registry is constructed, YAMLs loaded,
    keyring tokens issued, and `seed_into_storage` runs through main.py's
    actual lifespan path — not via direct handler call. A future commit
    that drops the seed_into_storage call from main.py, or moves it
    after mcp_task spawn, fails this test with the regression mode in
    the failure message.

    Operator workflow: in production the keyring tokens come from
    `scripts/issue-token.py`. This test pre-issues them via TokenStore
    so the registry's initial_load resolves the four roles' UUIDs.
    Without those tokens, the registry logs unseeded warnings and the
    snapshot is empty; the seed runs but inserts zero rows. (That state
    is also valid — just means no agents are configured yet.)
    """
    from meridian_orchestrator.auth import TokenStore

    settings = _make_settings_for_full_lifespan(
        tmp_path, monkeypatch, oauth_token=None
    )

    # Pre-stage the canonical YAML configs at the runtime path.
    # AgentRegistry reads from settings.agents_dir.
    repo_config_dir = (
        Path(__file__).resolve().parents[2] / "config" / "agents"
    )
    settings.agents_dir.mkdir(parents=True, exist_ok=True)
    for role in ("pm", "engineer", "implementer", "tester"):
        (settings.agents_dir / f"{role}.yaml").write_text(
            (repo_config_dir / f"{role}.yaml").read_text(encoding="utf-8"),
            encoding="utf-8",
        )

    # Pre-issue keyring tokens for each role so the registry's
    # initial_load resolves them to agent UUIDs. Without this, the
    # snapshot is empty and the seed would no-op (still valid; just
    # not what this test verifies).
    store = TokenStore()
    issued_by_role = {
        role: store.issue(agent_id=None, role=role, label=f"{role}-lifecycle-test")
        for role in ("pm", "engineer", "implementer", "tester")
    }

    try:
        await _run_lifespan_briefly(settings)

        # Re-open the DB post-lifespan to inspect the agents rows.
        from meridian_orchestrator.storage.sqlite import SqliteStorage

        storage = SqliteStorage(settings.db_path)
        await storage.connect()
        try:
            rows = await storage.fetch_all(
                "SELECT id, role, reports_to, company_id "
                "FROM agents ORDER BY role"
            )
        finally:
            await storage.close()

        assert len(rows) == 4, (
            f"agents rows post-lifespan: {len(rows)}, expected 4. The "
            "seed_into_storage call in main.py's lifespan body is "
            "missing or runs after mcp_task spawn — Codex Finding 1 "
            "regression. record_api_call will FK-fail on first MCP "
            "invocation in production."
        )
        by_role = {r["role"]: r for r in rows}
        assert set(by_role.keys()) == {
            "pm", "engineer", "implementer", "tester"
        }
        # Every row got the seeded company_id (non-null) — confirms
        # seed_initial ran before seed_into_storage in the lifespan.
        for r in rows:
            assert r["company_id"], (
                f"agents row for role={r['role']!r} has empty "
                "company_id; seed_initial did not run before "
                "seed_into_storage in the lifespan. Ordering "
                "invariant broken."
            )
        # Each row's id matches the keyring-resolved UUID.
        for role, r in by_role.items():
            assert r["id"] == issued_by_role[role].record.agent_id

        # Hierarchy resolution: PM reports_to NULL; engineer reports_to
        # PM; impl/tester report_to engineer. Pass 2 of seed_into_storage
        # ran successfully end-to-end through the lifespan.
        pm_id = by_role["pm"]["id"]
        engineer_id = by_role["engineer"]["id"]
        assert by_role["pm"]["reports_to"] is None
        assert by_role["engineer"]["reports_to"] == pm_id
        assert by_role["implementer"]["reports_to"] == engineer_id
        assert by_role["tester"]["reports_to"] == engineer_id
    finally:
        # Clean up keyring tokens so subsequent test runs don't see
        # accumulated state. Each issued token is keyring-scoped; the
        # default TokenStore service is shared across tests in this
        # process.
        for issued in issued_by_role.values():
            store.revoke(issued.record.token_id)


# ---------------------------------------------------------------------------
# Cross-tool e2e for budget tools (M5-subscription-routing commit 6)
# ---------------------------------------------------------------------------
#
# These tests boot a real MCP server (via ``serve_mcp``) and roundtrip
# through the live wire to verify cross-tool composition that
# direct-handler / wrapper-per-tool tests can't reach. Coverage breadth
# is held by the existing layers; e2e sits on top to lock the
# wired-together behavior.
#
# Four focused tests per Q1 disposition (no exhaustive matrix):
#
# 1. Smoke: all four budget tools callable in one MCP session.
# 2. Cross-tool consistency: record_api_call → get_token_usage reads
#    back the recorded usage.
# 3. Snapshot threading: synthetic snapshot reaches check_budget_for_task
#    via the wrapper's get_oauth_usage_snapshot lambda.
# 4. Saturated subscription cross-tool: high subscription_5h +
#    extra_usage.is_enabled=True → check_budget_for_task routes to
#    api_key_overflow AND get_burn_rate reflects the rate. Parametrized
#    across PM and Engineer per Q1 sub-amendment to lock global-scope
#    contract end-to-end.


def _e2e_make_snapshot(
    *,
    five_hour_util: float = 30.0,
    seven_day_opus_util: float = 20.0,
    extra_usage_enabled: bool = False,
) -> CachedSnapshot:
    """Synthetic fully-parseable snapshot for the e2e tests. Q2
    disposition: stub via lambda — poller correctness locked at
    test_oauth_usage_poller.py."""
    polled_at = datetime.now(tz=UTC)
    return CachedSnapshot(
        payload={
            "five_hour": {
                "utilization": five_hour_util,
                "resets_at": (
                    polled_at + timedelta(hours=4)
                ).isoformat(timespec="microseconds"),
            },
            "seven_day_opus": {"utilization": seven_day_opus_util},
            "extra_usage": {"is_enabled": extra_usage_enabled},
        },
        polled_at=polled_at,
        null_counters=[],
    )


@asynccontextmanager
async def _e2e_running_server(
    settings: Settings,
    storage: SqliteStorage,
    snapshot: CachedSnapshot | None,
) -> AsyncIterator[None]:
    """Spawn the MCP server task and yield once it's ready. Mirrors
    the wrapper-test pattern; provides a control window for MCP
    roundtrips during the lifespan."""
    ready = asyncio.Event()
    task = asyncio.create_task(
        serve_mcp(
            settings,
            ready=ready,
            storage=storage,
            get_oauth_usage_snapshot=lambda: snapshot,
        ),
        name="mcp-e2e",
    )
    try:
        await asyncio.wait_for(ready.wait(), timeout=15)
        yield
    finally:
        task.cancel()
        with contextlib.suppress(TimeoutError, asyncio.CancelledError):
            await asyncio.wait_for(task, timeout=15)


async def _e2e_seed_storage(s: SqliteStorage) -> None:
    """Migrate + seed the canonical company. Used by tests that need
    api_usage rows attributed to a company-scoped agent."""
    await run_migrations(s)
    await s.execute(
        "INSERT INTO companies (id, name) VALUES ('co1', 'Meridian AI')"
    )


async def _e2e_seed_agent(
    s: SqliteStorage, agent_id: str, role: str
) -> None:
    await s.execute(
        "INSERT INTO agents (id, company_id, role, config_path) "
        "VALUES (?, 'co1', ?, '/dev/null')",
        (agent_id, role),
    )


async def _e2e_issue_role_token(
    storage: SqliteStorage, role: str, label: str
) -> Any:
    """Issue a static token for ``role`` and seed its agents row.
    Returns the IssuedToken."""
    store = TokenStore()
    issued = store.issue(agent_id=None, role=role, label=label)
    await _e2e_seed_agent(storage, issued.record.agent_id, role)
    return issued


async def _e2e_call_tool(
    *,
    base_url: str,
    token: str,
    tool_name: str,
    args: dict[str, Any],
) -> Any:
    """Invoke an MCP tool via the live Streamable-HTTP server."""
    headers = {"Authorization": f"Bearer {token}"}
    async with (
        streamablehttp_client(
            f"{base_url}/mcp", headers=headers
        ) as (read, write, _),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        return await session.call_tool(tool_name, args)


def _e2e_structured(result: Any) -> dict[str, Any]:
    """Extract structured output from an MCP tool result."""
    structured = getattr(result, "structuredContent", None)
    if structured is not None:
        return dict(structured)
    text = result.content[0].text
    return dict(json.loads(text))


@pytest_asyncio.fixture
async def _e2e_storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    """Migrated DB + seeded company. Each test gets a fresh DB."""
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await _e2e_seed_storage(s)
    yield s
    await s.close()


@pytest.fixture
def _e2e_settings(tmp_path: Path) -> Settings:
    return Settings(
        data_dir=tmp_path,
        mcp_host="127.0.0.1",
        mcp_port=_free_port(),
    )


# ---------------------------------------------------------------------------
# e2e test 1: smoke — all four budget tools callable in one MCP session
# ---------------------------------------------------------------------------


async def test_e2e_all_four_budget_tools_callable_in_one_session(
    _e2e_settings: Settings, _e2e_storage: SqliteStorage
) -> None:
    """PM token; one MCP session; calls record_api_call,
    get_token_usage, get_burn_rate, and check_budget_for_task in
    sequence; all return structured success.

    Locks: the four budget tools all register under
    ``_register_budget_tools`` and respond cleanly. A future
    refactor that breaks any single registration (e.g., dropping a
    decorator) fails this test informatively.
    """
    issued = await _e2e_issue_role_token(
        _e2e_storage, "pm", "e2e-smoke-pm"
    )
    base_url = f"http://{_e2e_settings.mcp_host}:{_e2e_settings.mcp_port}"
    snapshot = _e2e_make_snapshot()

    async with _e2e_running_server(_e2e_settings, _e2e_storage, snapshot):
        record_result = await _e2e_call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="record_api_call",
            args={
                "model": "claude-sonnet-4-6",
                "input_tokens": 1000,
                "output_tokens": 500,
            },
        )
        usage_result = await _e2e_call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="get_token_usage",
            args={"window": "last_24h"},
        )
        burn_result = await _e2e_call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="get_burn_rate",
            args={"window": "last_24h"},
        )
        budget_result = await _e2e_call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="check_budget_for_task",
            args={"complexity": 3, "priority": 3},
        )

    for name, result in (
        ("record_api_call", record_result),
        ("get_token_usage", usage_result),
        ("get_burn_rate", burn_result),
        ("check_budget_for_task", budget_result),
    ):
        assert getattr(result, "isError", False) is False, (
            f"e2e smoke test: {name} returned an MCP error: "
            f"{getattr(result, 'content', None)!r}. One of the four "
            "budget tools is not registering / responding cleanly. "
            "Check _register_budget_tools wiring in server.py."
        )


# ---------------------------------------------------------------------------
# e2e test 2: record_api_call → get_token_usage cross-tool consistency
# ---------------------------------------------------------------------------


async def test_e2e_record_api_call_then_get_token_usage_reads_back(
    _e2e_settings: Settings, _e2e_storage: SqliteStorage
) -> None:
    """Record an api_usage row via MCP; immediately query via
    get_token_usage; verify the recorded tokens read back. Locks
    write→read cross-tool consistency end-to-end through the
    wrappers.

    Failure mode this prevents: a future refactor where
    get_token_usage's window resolution falls out of sync with
    record_api_call's timestamp column convention (e.g., one writes
    ISO-8601 with microseconds, other queries against a different
    format) would silently miss freshly-recorded usage. Wrapper
    tests don't catch this because each tool tests in isolation.
    """
    issued = await _e2e_issue_role_token(
        _e2e_storage, "pm", "e2e-cross-tool-pm"
    )
    base_url = f"http://{_e2e_settings.mcp_host}:{_e2e_settings.mcp_port}"

    async with _e2e_running_server(_e2e_settings, _e2e_storage, _e2e_make_snapshot()):
        await _e2e_call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="record_api_call",
            args={
                "model": "claude-sonnet-4-6",
                "input_tokens": 700,
                "output_tokens": 300,
            },
        )
        result = await _e2e_call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="get_token_usage",
            args={"window": "last_24h"},
        )

    assert getattr(result, "isError", False) is False
    out = _e2e_structured(result)
    assert out["input_tokens"] == 700, (
        f"get_token_usage read back input_tokens={out['input_tokens']}, "
        "expected 700. Cross-tool consistency broken: record_api_call "
        "wrote a row but get_token_usage's window resolution + SQL "
        "filter didn't surface it. Check that record_api_call's "
        "window_start column convention matches "
        "get_token_usage's filter format (both should be ISO-8601 "
        "microseconds; commit 2 wired this through _resolve_window)."
    )
    assert out["output_tokens"] == 300


# ---------------------------------------------------------------------------
# e2e test 3: synthetic snapshot threads to check_budget_for_task
# ---------------------------------------------------------------------------


async def test_e2e_check_budget_for_task_uses_wired_snapshot(
    _e2e_settings: Settings, _e2e_storage: SqliteStorage
) -> None:
    """Wire a synthetic snapshot via ``get_oauth_usage_snapshot``;
    call ``check_budget_for_task``; verify the snapshot's
    five_hour utilization surfaces in the routing factors.

    Locks: the wrapper layer threads ``get_oauth_usage_snapshot``
    through to ``_compute_routing`` end-to-end. A future commit
    that breaks the threading (e.g., the wrapper accidentally calls
    ``get_oauth_usage_snapshot`` once at register time and caches
    None) would fail this test — the synthetic snapshot would not
    reach the routing function and the factor would be absent.
    """
    issued = await _e2e_issue_role_token(
        _e2e_storage, "pm", "e2e-snapshot-thread-pm"
    )
    base_url = f"http://{_e2e_settings.mcp_host}:{_e2e_settings.mcp_port}"

    # Distinct utilization value (45%) so we can grep for the exact
    # factor and rule out any default / cached snapshot.
    snapshot = _e2e_make_snapshot(five_hour_util=45.0)

    async with _e2e_running_server(_e2e_settings, _e2e_storage, snapshot):
        result = await _e2e_call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="check_budget_for_task",
            args={"complexity": 3, "priority": 3},
        )

    assert getattr(result, "isError", False) is False
    out = _e2e_structured(result)
    assert out["fallback_active"] is False, (
        "fallback_active=True under a fresh fully-parseable snapshot — "
        "the wrapper is not threading the snapshot through to "
        "_compute_routing. Check the _snapshot_provider closure in "
        "_register_budget_tools (server.py)."
    )
    factors = out["routing_factors"]
    assert isinstance(factors, list)
    assert "subscription_5h_at_45pct_normal" in factors, (
        f"Expected factor 'subscription_5h_at_45pct_normal' in routing "
        f"factors {factors!r}. The synthetic snapshot's 45% utilization "
        "did not reach the routing function via the wrapper layer's "
        "get_oauth_usage_snapshot threading."
    )


# ---------------------------------------------------------------------------
# e2e test 4: saturated cross-tool — overflow routing + burn rate reflection
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("caller_role", ["pm", "engineer"])
async def test_e2e_saturated_subscription_routes_to_overflow_and_burn_rate(
    _e2e_settings: Settings,
    _e2e_storage: SqliteStorage,
    caller_role: str,
) -> None:
    """Saturated subscription scenario end-to-end: snapshot has
    ``five_hour.utilization=100.0`` + ``extra_usage.is_enabled=True``.

    Two cross-tool assertions:

    1. ``check_budget_for_task`` routes to ``api_key_overflow``
       (rule 6). Routing factors include ``subscription_5h_rate_limited``
       and ``routed_to_api_key_overflow``.
    2. ``get_burn_rate`` reflects accumulated usage. Seed an
       api_usage row before the test so the rate is non-zero;
       verify both PM and Engineer callers see the same global-
       scope rate (Q8 contract from M5-subscription-routing commit 2).

    Parametrized across PM and Engineer per Q1 sub-amendment —
    Engineer's compaction-at-80% decision relative to subscription
    state is e2e-locked alongside PM's.
    """
    issued = await _e2e_issue_role_token(
        _e2e_storage, caller_role, f"e2e-saturated-{caller_role}"
    )
    # Seed an api_usage row for burn-rate accumulation. Different agent
    # role ("pm") on the row so the global-scope contract test verifies
    # Engineer sees PM's tokens.
    await _e2e_seed_agent(_e2e_storage, "seed-pm-agent", "pm")
    await _e2e_storage.execute(
        "INSERT INTO api_usage "
        "(id, agent_id, agent_role, model, input_tokens, output_tokens, "
        "cost_usd, window_start) "
        "VALUES ('u-seed', 'seed-pm-agent', 'pm', 'claude-sonnet-4-6', "
        "1200, 0, 0.0036, ?)",
        (
            (datetime.now(tz=UTC) - timedelta(hours=12)).isoformat(
                timespec="microseconds"
            ),
        ),
    )

    base_url = f"http://{_e2e_settings.mcp_host}:{_e2e_settings.mcp_port}"
    snapshot = _e2e_make_snapshot(
        five_hour_util=100.0,  # rate_limited
        extra_usage_enabled=True,  # overflow available
    )

    async with _e2e_running_server(_e2e_settings, _e2e_storage, snapshot):
        budget_result = await _e2e_call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="check_budget_for_task",
            args={"complexity": 3, "priority": 3},
        )
        burn_result = await _e2e_call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="get_burn_rate",
            args={"window": "last_24h"},
        )

    # Assertion 1: check_budget_for_task routes to overflow.
    assert getattr(budget_result, "isError", False) is False
    budget = _e2e_structured(budget_result)
    assert budget["recommended_bucket"] == "api_key_overflow", (
        f"caller_role={caller_role!r}: check_budget_for_task did not "
        f"route to api_key_overflow under saturated + extra_usage."
        f"is_enabled=True. Got bucket={budget['recommended_bucket']!r}. "
        "Rule 6 (saturated + overflow enabled → api_key_overflow) is "
        "broken end-to-end."
    )
    factors = budget["routing_factors"]
    assert "subscription_5h_rate_limited" in factors
    assert "routed_to_api_key_overflow" in factors

    # Assertion 2: get_burn_rate reflects the seeded api_usage row.
    # Global scope contract: both PM and Engineer see the same rate
    # because subscription burn is global, not role-scoped (Q8 from
    # M5-subscription-routing commit 2).
    assert getattr(burn_result, "isError", False) is False
    burn = _e2e_structured(burn_result)
    # 1200 tokens / 24h = 50 tokens/h. Tolerate slop for parametrize
    # ordering (the seeded row's window_start is "12h ago" relative to
    # each test's now, which can drift between parametrize variants).
    assert float(burn["tokens_per_hour"]) > 0.0, (
        f"caller_role={caller_role!r}: get_burn_rate returned zero "
        "tokens/hour despite 1200 seeded tokens. Cross-tool e2e: "
        "either the seed insert didn't land or get_burn_rate's window "
        "filter excluded the row. Check window resolution in "
        "_resolve_window."
    )
    assert abs(float(burn["tokens_per_hour"]) - 50.0) < 5.0, (
        f"caller_role={caller_role!r}: tokens_per_hour="
        f"{burn['tokens_per_hour']!r}, expected ~50.0 "
        "(1200 tokens / 24h). Either window resolution drifted or "
        "the global-scope contract broke (Engineer not seeing PM's "
        "tokens — the Q8 contract from M5-subscription-routing "
        "commit 2)."
    )
