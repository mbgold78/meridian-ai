"""Meridian Orchestrator daemon entry point.

Connects to storage, runs migrations, starts the MCP server, idles until a
shutdown signal arrives, then drains cleanly.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import signal
import sys
import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path

import structlog

from meridian_orchestrator.agents.registry import AgentRegistry
from meridian_orchestrator.audit.sampler import Sampler
from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.bridge.canonical import (
    CanonicalClone,
    discover_upstream_url,
)
from meridian_orchestrator.bridge.dispatcher import BridgeDispatcher
from meridian_orchestrator.bridge.engineer_supervisor import EngineerSupervisor
from meridian_orchestrator.bridge.oauth_usage import (
    CachedSnapshot,
    OAuthUsagePoller,
)
from meridian_orchestrator.bridge.pm_supervisor import PMSupervisor
from meridian_orchestrator.config import Settings
from meridian_orchestrator.errors import ValidationError
from meridian_orchestrator.server import serve as serve_mcp
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.seed import seed_initial
from meridian_orchestrator.storage.sqlite import SqliteStorage

logger = structlog.get_logger(__name__)

# Wall-clock timeout for each supervisor's shutdown() coroutine. Worst-
# case terminate/grace/SIGKILL ~20s + headroom for audit log writes,
# token revocation, dispatch-row update, straggling subprocess output
# capture. A wedged supervisor (e.g., audit-log write blocked on a
# stalled SQLite connection) won't block daemon shutdown beyond this;
# force-close after timeout. The subprocess gets SIGKILL via the OS
# when the daemon process exits.
SUPERVISOR_SHUTDOWN_TIMEOUT_SECONDS = 90

# Wall-clock timeout for the OAuth usage poller's shutdown() coroutine.
# The poller's shutdown is lightweight (cancel one asyncio task, await
# its clean exit) and shouldn't take more than a few seconds even under
# pathological asyncio.sleep cancellation paths. Generous 30s headroom.
POLLER_SHUTDOWN_TIMEOUT_SECONDS = 30


def _configure_logging(settings: Settings) -> None:
    """Configure structlog with JSON output to stderr."""
    level = getattr(logging, settings.log_level.upper(), logging.INFO)
    logging.basicConfig(level=level, format="%(message)s", stream=sys.stderr)
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(level),
        cache_logger_on_first_use=True,
    )


@asynccontextmanager
async def _lifespan(settings: Settings) -> AsyncIterator[SqliteStorage]:
    """Open storage, run migrations, yield, then close."""
    storage = SqliteStorage(settings.db_path)
    await storage.connect()
    try:
        applied = await run_migrations(storage)
        if applied:
            logger.info("startup.migrations_applied", versions=applied)
        else:
            logger.info("startup.migrations_up_to_date")
        yield storage
    finally:
        await storage.close()


async def _run() -> None:
    """Async main: read settings, configure logging + signal handlers,
    open lifespan, wait for shutdown signal, exit cleanly.

    Thin wrapper around :func:`_run_lifespan_body` — extraction at
    M5-pm-mode-and-window-state commit 7 makes the lifespan body
    testable. Production builds the shutdown ``asyncio.Event``
    internally and wires SIGINT/SIGTERM (POSIX) or SIGINT/SIGBREAK
    (Windows) handlers to set it. Tests pass an external event they
    can set programmatically to trigger clean shutdown without
    sending real OS signals.
    """
    settings = Settings()
    _configure_logging(settings)
    logger.info("startup.begin", data_dir=str(settings.data_dir))

    shutdown = asyncio.Event()
    loop = asyncio.get_running_loop()

    def _on_signal(*_: object) -> None:
        logger.info("shutdown.signal_received")
        shutdown.set()

    if sys.platform != "win32":
        for sig in (signal.SIGTERM, signal.SIGINT):
            loop.add_signal_handler(sig, _on_signal)
    else:
        # Windows: signal.signal handlers run in the main thread.
        signal.signal(signal.SIGINT, lambda *_: _on_signal())
        if hasattr(signal, "SIGBREAK"):
            signal.signal(signal.SIGBREAK, lambda *_: _on_signal())

    await _run_lifespan_body(settings, shutdown)


async def _run_lifespan_body(
    settings: Settings, shutdown: asyncio.Event
) -> None:
    """The actual lifespan body — opened, runs until ``shutdown`` is
    set, drains cleanly. Extracted from :func:`_run` at M5-pm-mode-
    and-window-state commit 7 for testability: tests pass an
    external shutdown event they can set programmatically without
    sending OS signals.

    Production callers go through :func:`_run`; tests can call this
    directly with a custom shutdown event and a Settings instance
    pointing at a temp data dir.
    """
    async with _lifespan(settings) as storage:
        # M5-subscription-routing commit 0.5: seed companies + projects
        # automatically on every boot. Idempotent per seed_initial's
        # contract — re-runs are no-ops when rows already exist. Folded
        # into the lifespan to remove the fragile manual scripts/init-db.py
        # pre-step; init-db.py stays valid for explicit operator
        # initialization but is no longer required for first boot. The
        # returned company_id is required by the agents-table seed below.
        company_id, _project_id = await seed_initial(storage)
        registry = AgentRegistry(settings.agents_dir)
        async with registry.lifespan():
            # M5-wrapper-closure: provision the canonical clone and
            # construct the BridgeDispatcher synchronously before the MCP
            # server starts accepting connections. Synchronous-in-lifespan
            # rather than async-after-ready avoids the
            # dispatcher-not-ready window during which dispatch_*
            # wrappers would return ConflictError; first-boot is slow but
            # predictable. Skipped when substrate_working_directory is
            # unset (the M5-wrapper-closure default state for tests that
            # don't exercise dispatch).
            dispatcher: BridgeDispatcher | None = None
            if settings.substrate_working_directory is not None:
                logger.info(
                    "canonical.provisioning_begin",
                    canonical_path=str(settings.substrate_canonical_dir),
                    substrate_working_directory=str(
                        settings.substrate_working_directory
                    ),
                )
                provision_start = time.monotonic()
                upstream_url = await discover_upstream_url(
                    settings.substrate_working_directory
                )
                canonical = CanonicalClone(
                    path=settings.substrate_canonical_dir,
                    upstream_url=upstream_url,
                )
                await canonical.ensure_provisioned()
                settings.dispatch_worktrees_root.mkdir(
                    parents=True, exist_ok=True
                )
                dispatcher = BridgeDispatcher(
                    storage=storage,
                    token_store=TokenStore(),
                    canonical=canonical,
                    worktrees_root=settings.dispatch_worktrees_root,
                    # M5-flag-flips F4: per-dispatch stderr log root.
                    # The default launcher writes per-dispatch stderr
                    # to <dispatches_root>/<dispatch_id>/stderr.log via
                    # bridge.stderr_drain. Persists beyond worktree
                    # cleanup for post-mortem.
                    dispatches_root=settings.dispatches_root,
                    # Orchestrator daemon URL embedded into per-dispatch
                    # mcp_config.json's meridian-orchestrator HTTP MCP
                    # entry; the dispatcher uses it to compose the
                    # ``Authorization: Bearer`` header per per-dispatch
                    # token. See :mod:`bridge.mcp_config`.
                    daemon_url=(
                        f"http://{settings.mcp_host}:{settings.mcp_port}/mcp"
                    ),
                )
                logger.info(
                    "canonical.provisioning_complete",
                    canonical_path=str(settings.substrate_canonical_dir),
                    upstream_url=upstream_url,
                    elapsed_seconds=round(
                        time.monotonic() - provision_start, 2
                    ),
                )

            mcp_ready = asyncio.Event()
            engineer_supervisor: EngineerSupervisor | None = None
            pm_supervisor: PMSupervisor | None = None
            # M5-pm-mode-and-window-state commit 7: OAuth usage poller
            # is constructed AFTER mcp_ready (parallelized with
            # supervisor starts; see post-ready start block below). The
            # variable is created once at lifespan scope and either
            # stays None (no token configured) or holds the live
            # poller; the get_oauth_usage_snapshot closure dispatches
            # off the current value at call time.
            oauth_usage_poller: OAuthUsagePoller | None = None

            # Lazy lookup callable for the signal_compaction tool wrapper.
            # The supervisor is constructed AFTER the MCP server starts
            # (its MCP calls back to the daemon need the server up); a
            # closure-captured reference would be None at build_server
            # time. Python closures resolve names at call time, so the
            # lambda picks up the variable's current value when invoked.
            def get_engineer_supervisor() -> EngineerSupervisor | None:
                return engineer_supervisor

            # Symmetric lazy lookup for dispatch_implementer / dispatch_tester
            # wrappers. The dispatcher is constructed before mcp serve
            # starts (above), so the closure resolves immediately to a
            # populated value in production paths; the late-binding shape
            # is symmetric with get_engineer_supervisor for consistency.
            def get_dispatcher() -> BridgeDispatcher | None:
                return dispatcher

            # Lazy lookup for the get_window_state tool wrapper.
            # OAuthUsagePoller is constructed after mcp_ready (so its
            # eventual ceiling-reached audit writes have a live MCP
            # context — though the audit goes through storage, not MCP,
            # so this is more about start-order symmetry than a hard
            # dependency). When the operator hasn't set
            # CLAUDE_CODE_OAUTH_TOKEN, the poller stays None and this
            # closure returns None unconditionally — the
            # get_window_state handler treats that as the cold-start
            # case (snapshot=None → pace_recommendation='unknown'),
            # which collapses "no poller running" with "poller
            # exists but hasn't polled yet" into a single user-visible
            # state per Q3 disposition at commit 6 prep.
            def get_oauth_usage_snapshot() -> CachedSnapshot | None:
                return (
                    oauth_usage_poller.latest_snapshot()
                    if oauth_usage_poller is not None
                    else None
                )

            # M5-continuous-pm-driver commit 8 (F9.0 Reading Y): PM
            # supervisor closure ships at compaction-trigger commit
            # because signal_compaction permission widens from
            # engineer-only to engineer + pm (per F9.2 Q3 Reading A);
            # the wrapper resolves to the PM supervisor instance when
            # the caller is PM. Symmetric with get_engineer_supervisor
            # — closure resolves at call time so PM supervisor's
            # post-mcp-ready construction order doesn't break the
            # build_server-time wiring. PM supervisor stays None when
            # operator hasn't enabled it via enable_pm_supervisor flag.
            def get_pm_supervisor() -> PMSupervisor | None:
                return pm_supervisor

            # M5-subscription-routing commit 0.5: seed the agents table
            # from the registry's in-memory snapshot. MUST run before
            # mcp_task spawns — otherwise the first MCP request that
            # writes api_usage (or any row referencing agents.id) hits
            # FOREIGN KEY CONSTRAINT FAILED. Closes Codex Finding 1: the
            # registry's startup docstring claimed it seeded the agents
            # table but no INSERT ever happened in production code; only
            # test fixtures inserted agents rows. Failure here propagates
            # and the daemon fails to start — load-bearing for record_api_call.
            await registry.seed_into_storage(storage, company_id)

            # M5-continuous-pm-driver commit 4: random-audit-sampling
            # Sampler. Constructed-once-and-immutable from
            # settings.audit_sample_percentage; passed directly to
            # build_server (not via closure pattern — Sampler doesn't
            # have late-binding state like OAuthUsagePoller's snapshot).
            # Default 0.0 = sampling disabled per F5.2 Q9 Reading A
            # opt-in shape; operator sets non-zero via env var to enable.
            sampler = Sampler(settings.audit_sample_percentage)

            # M5-continuous-pm-driver commit 7: PM loop tick cadence.
            # Lazy-callable closure resolves to settings.pm_loop_tick_seconds
            # at each wait_for_next_tick MCP invocation. Effectively
            # static across daemon lifetime (Settings constructed once
            # at startup), but the callable shape mirrors
            # get_oauth_usage_snapshot precedent for consistency and
            # admits future hot-reload paths if Settings ever grows
            # one. Per F8.6.A Reading D disposition: cadence config
            # flows through main.py lifespan via closure to
            # tools/pm_loop.py, NOT through pm_supervisor.py
            # (preserving symmetric-supervisor-shape with
            # EngineerSupervisor + single-responsibility separation).
            def get_pm_loop_tick_seconds() -> float:
                return settings.pm_loop_tick_seconds

            # M5-continuous-pm-driver commit 8: PM compaction handoff-doc
            # directory. Settings.pm_handoff_dir is `Path | None`; the
            # closure resolves the default `<data_dir>/pm-handoffs/`
            # when None. Operator override via
            # MERIDIAN_ORCH_PM_HANDOFF_DIR env var sets the field.
            #
            # Per F9.0 Reading Y disposition (2026-05-07): handoff-doc
            # reading lives subprocess-side via tools/pm_handoff.py +
            # pm.yaml, NOT daemon-side _reconstruct_state override.
            # This closure plumbs the directory path through main.py
            # lifespan to the MCP wrappers — symmetric with
            # get_pm_loop_tick_seconds + get_oauth_usage_snapshot
            # precedents; zero new bridge → tools imports.
            def get_pm_handoff_dir() -> Path:
                return (
                    settings.pm_handoff_dir
                    if settings.pm_handoff_dir is not None
                    else settings.data_dir / "pm-handoffs"
                )

            mcp_task = asyncio.create_task(
                serve_mcp(
                    settings,
                    ready=mcp_ready,
                    registry=registry,
                    storage=storage,
                    get_engineer_supervisor=get_engineer_supervisor,
                    get_pm_supervisor=get_pm_supervisor,
                    get_dispatcher=get_dispatcher,
                    get_oauth_usage_snapshot=get_oauth_usage_snapshot,
                    sampler=sampler,
                    get_pm_loop_tick_seconds=get_pm_loop_tick_seconds,
                    get_pm_handoff_dir=get_pm_handoff_dir,
                ),
                name="mcp-server",
            )

            # OAuth usage poller skip-with-warning at startup if the
            # operator hasn't configured the token. Logs once at
            # startup so the operator sees the intent and consequence
            # before discovering at query time that get_window_state
            # always returns cold-start.
            #
            # Body tightened at M5-subscription-routing commit 5 now
            # that the unknown-pace fallback semantics are pinned at
            # the routing layer (Q3 disposition β; commit 4's
            # _is_snapshot_fully_parseable + fallback_per_role_defaults
            # factor + fallback_active=True). The previous hand-wavy
            # framing ("may include conservative defaults, halting, or
            # operating without window awareness") is replaced with the
            # exact behavior. Structured `fallback_behavior` field
            # alongside the message lets structlog-grep find this state
            # without parsing the message string.
            if settings.claude_code_oauth_token is None:
                logger.info(
                    "oauth_usage_poller.skipped_no_token",
                    fallback_behavior="per_role_defaults",
                    message=(
                        "CLAUDE_CODE_OAUTH_TOKEN not set; OAuth usage "
                        "poller will not run. Routing falls back to "
                        "per-role defaults (PM=Opus, "
                        "Engineer/Implementer/Tester=Sonnet, no "
                        "priority-driven upgrade or Opus-downgrade) "
                        "until the token is set and a snapshot is "
                        "available. fallback_active=True will surface "
                        "in routing_factors on every "
                        "check_budget_for_task call; "
                        "get_window_state will return "
                        "pace_recommendation='unknown' for all "
                        "queries. Set CLAUDE_CODE_OAUTH_TOKEN and "
                        "restart the daemon to enable budget-aware "
                        "routing."
                    ),
                )
            logger.info(
                "startup.complete",
                mcp_url=f"http://{settings.mcp_host}:{settings.mcp_port}/mcp",
                agents_loaded=sorted(
                    ra.config.role for ra in registry.all_agents()
                ),
                engineer_supervisor_enabled=settings.enable_engineer_supervisor,
                pm_supervisor_enabled=settings.enable_pm_supervisor,
                dispatcher_constructed=dispatcher is not None,
            )

            try:
                # Start supervisors AND the OAuth usage poller only
                # after the MCP server is accepting connections — their
                # own MCP calls back to the daemon need the server up.
                # Gated:
                # - Supervisors gated on per-supervisor enable flags
                #   (defaults off; flipped on at M5-flag-flips after
                #   the verification checklist passes per
                #   project_m5_engineer_supervisor_flag_flip memory).
                # - OAuth poller gated on
                #   ``settings.claude_code_oauth_token is not None``
                #   per Q1 disposition at commit 7 prep (skip with
                #   warning when token unset).
                #
                # Start order: poller → PM → Engineer. Per Q3 Reading A
                # (poller starts after MCP ready, parallelizable with
                # supervisor starts because OAuthUsagePoller.start()
                # spawns an asyncio task and returns immediately
                # without awaiting first poll completion). Symmetric
                # with the shutdown order below where poller stops
                # first per Q3 Reading X.
                needs_post_ready_start = (
                    settings.claude_code_oauth_token is not None
                    or settings.enable_engineer_supervisor
                    or settings.enable_pm_supervisor
                )
                if needs_post_ready_start:
                    ready_or_crash = asyncio.create_task(
                        mcp_ready.wait(), name="mcp-ready-wait"
                    )
                    await asyncio.wait(
                        {mcp_task, ready_or_crash},
                        return_when=asyncio.FIRST_COMPLETED,
                    )
                    if mcp_task.done():
                        ready_or_crash.cancel()
                        with contextlib.suppress(asyncio.CancelledError):
                            await ready_or_crash
                        mcp_exc = mcp_task.exception()
                        if mcp_exc is not None:
                            raise mcp_exc
                    else:
                        # Poller first per Q3 Start = Reading A.
                        # ``start()`` is non-blocking (task spawn +
                        # INFO log; no await on first poll). Failures
                        # in the polling loop are caught inside the
                        # loop and logged; they don't propagate to
                        # ``start()``. So this can't fail in a way
                        # that blocks supervisor starts.
                        if settings.claude_code_oauth_token is not None:
                            oauth_usage_poller = OAuthUsagePoller(
                                storage=storage,
                                oauth_token=settings.claude_code_oauth_token,
                            )
                            await oauth_usage_poller.start()
                            logger.info(
                                "oauth_usage_poller.lifespan_started"
                            )

                        if settings.enable_pm_supervisor:
                            try:
                                settings.pm_continuous_dir.mkdir(
                                    parents=True, exist_ok=True
                                )
                                pm_supervisor = PMSupervisor(
                                    registry=registry,
                                    working_directory=settings.pm_continuous_dir,
                                    storage=storage,
                                    token_store=TokenStore(),
                                    # M5-flag-flips F4: stderr drain
                                    # destination for the PM-continuous
                                    # supervisor subprocess.
                                    dispatches_root=settings.dispatches_root,
                                )
                                await pm_supervisor.start()
                                logger.info(
                                    "pm_supervisor.started",
                                    dispatch_id=pm_supervisor._current_dispatch_id,
                                )
                            except ValidationError as start_exc:
                                logger.error(
                                    "pm_supervisor.start_failed",
                                    error=str(start_exc),
                                )
                                raise

                        if settings.enable_engineer_supervisor:
                            try:
                                settings.engineer_supervisor_dir.mkdir(
                                    parents=True, exist_ok=True
                                )
                                engineer_supervisor = EngineerSupervisor(
                                    registry=registry,
                                    working_directory=settings.engineer_supervisor_dir,
                                    storage=storage,
                                    token_store=TokenStore(),
                                    # M5-flag-flips F4: stderr drain
                                    # destination for the Engineer
                                    # supervisor subprocess.
                                    dispatches_root=settings.dispatches_root,
                                )
                                await engineer_supervisor.start()
                                logger.info(
                                    "engineer_supervisor.started",
                                    dispatch_id=engineer_supervisor._current_dispatch_id,
                                )
                            except ValidationError as start_exc:
                                logger.error(
                                    "engineer_supervisor.start_failed",
                                    error=str(start_exc),
                                )
                                raise

                done, _ = await asyncio.wait(
                    {
                        mcp_task,
                        asyncio.create_task(shutdown.wait(), name="shutdown-wait"),
                    },
                    return_when=asyncio.FIRST_COMPLETED,
                )
                for t in done:
                    if t.get_name() == "mcp-server":
                        crash_exc = t.exception()
                        if crash_exc is not None:
                            logger.error("mcp.crashed", error=str(crash_exc))
                            raise crash_exc
                logger.info("shutdown.begin")
            finally:
                # Shutdown order: poller → PM → Engineer → MCP. Per Q3
                # Shutdown = Reading X disposition at commit 7 prep:
                # poller is the lightest-weight component (cancel one
                # asyncio task; no cascade) and the upstream observer
                # of Mike's MCP activity. Stopping it first removes
                # one source of writes (ceiling-reached audit rows)
                # during the rest of shutdown. Symmetric with start
                # order (poller-first there too).
                #
                # Each shutdown() gets a wall-clock timeout — a
                # wedged component (poller stuck on a slow asyncio.sleep
                # cancellation; supervisor stalled on a SQLite write)
                # doesn't block daemon shutdown; force-close after
                # timeout, log the failure, continue the sequence.
                if oauth_usage_poller is not None:
                    try:
                        await asyncio.wait_for(
                            oauth_usage_poller.shutdown(),
                            timeout=POLLER_SHUTDOWN_TIMEOUT_SECONDS,
                        )
                    except Exception as exc:
                        logger.error(
                            "oauth_usage_poller.shutdown_force_close",
                            error=str(exc),
                        )
                if pm_supervisor is not None:
                    try:
                        await asyncio.wait_for(
                            pm_supervisor.shutdown(),
                            timeout=SUPERVISOR_SHUTDOWN_TIMEOUT_SECONDS,
                        )
                    except Exception as exc:
                        logger.error(
                            "pm_supervisor.shutdown_force_close",
                            error=str(exc),
                        )
                if engineer_supervisor is not None:
                    try:
                        await asyncio.wait_for(
                            engineer_supervisor.shutdown(),
                            timeout=SUPERVISOR_SHUTDOWN_TIMEOUT_SECONDS,
                        )
                    except Exception as exc:
                        logger.error(
                            "engineer_supervisor.shutdown_force_close",
                            error=str(exc),
                        )
                if not mcp_task.done():
                    mcp_task.cancel()
                    with contextlib.suppress(asyncio.CancelledError, Exception):
                        await mcp_task

    logger.info("shutdown.complete")


def main() -> int:
    """Synchronous entry point. Returns exit code."""
    try:
        asyncio.run(_run())
    except KeyboardInterrupt:
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
