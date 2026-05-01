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

import structlog

from meridian_orchestrator.agents.registry import AgentRegistry
from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.bridge.canonical import (
    CanonicalClone,
    discover_upstream_url,
)
from meridian_orchestrator.bridge.dispatcher import BridgeDispatcher
from meridian_orchestrator.bridge.engineer_supervisor import EngineerSupervisor
from meridian_orchestrator.config import Settings
from meridian_orchestrator.errors import ValidationError
from meridian_orchestrator.server import serve as serve_mcp
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage

logger = structlog.get_logger(__name__)


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
    """Async main: open lifespan, wait for shutdown signal, exit cleanly."""
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

    async with _lifespan(settings) as storage:
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

            mcp_task = asyncio.create_task(
                serve_mcp(
                    settings,
                    ready=mcp_ready,
                    registry=registry,
                    storage=storage,
                    get_engineer_supervisor=get_engineer_supervisor,
                    get_dispatcher=get_dispatcher,
                ),
                name="mcp-server",
            )
            logger.info(
                "startup.complete",
                mcp_url=f"http://{settings.mcp_host}:{settings.mcp_port}/mcp",
                agents_loaded=sorted(
                    ra.config.role for ra in registry.all_agents()
                ),
                engineer_supervisor_enabled=settings.enable_engineer_supervisor,
                dispatcher_constructed=dispatcher is not None,
            )

            try:
                # Start the engineer-supervisor only after the MCP server
                # is accepting connections — its own MCP calls back to the
                # daemon need the server up. Gated on the
                # MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR flag (default
                # off at M4c; flipped on at M5 alongside PMSupervisor —
                # see project_m5_engineer_supervisor_flag_flip memory).
                if settings.enable_engineer_supervisor:
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
                        try:
                            settings.engineer_supervisor_dir.mkdir(
                                parents=True, exist_ok=True
                            )
                            engineer_supervisor = EngineerSupervisor(
                                registry=registry,
                                working_directory=settings.engineer_supervisor_dir,
                                storage=storage,
                                token_store=TokenStore(),
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
                # Shutdown order: supervisor first (its in-flight MCP
                # calls land while the server still accepts them), then
                # MCP server.
                if engineer_supervisor is not None:
                    with contextlib.suppress(Exception):
                        await engineer_supervisor.shutdown()
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
