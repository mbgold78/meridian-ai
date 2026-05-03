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
from pathlib import Path
from typing import Any, ClassVar

import pytest

from meridian_orchestrator import main as main_module
from meridian_orchestrator.bridge.oauth_usage import CachedSnapshot
from meridian_orchestrator.config import Settings
from meridian_orchestrator.main import _lifespan, _run_lifespan_body


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
        assert {r["version"] for r in version_rows} == {1, 2, 3, 5, 6, 7, 8}

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
        assert {r["version"] for r in version_rows} == {1, 2, 3, 5, 6, 7, 8}


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
