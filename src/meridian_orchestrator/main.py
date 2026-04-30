"""Meridian Orchestrator daemon entry point.

M1: clean startup/shutdown skeleton with no MCP server yet. Connects to storage,
runs migrations, idles waiting for shutdown signal, exits cleanly. M2 adds the
MCP server inside the same lifespan.
"""

from __future__ import annotations

import asyncio
import logging
import signal
import sys
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import structlog

from meridian_orchestrator.config import Settings
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

    async with _lifespan(settings):
        logger.info("startup.complete")
        # M1: idle wait for shutdown. M2 adds MCP server.serve() here.
        await shutdown.wait()
        logger.info("shutdown.begin")

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
