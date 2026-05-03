"""End-to-end MCP wrapper tests for ``get_window_state``.

Exercises the wrapper against a real Streamable-HTTP MCP server with
a fake snapshot provider injected via ``serve(...,
get_oauth_usage_snapshot=...)``. Direct-handler tests (pace_recommendation
table, cold-start, stale, bucket-null, agent_id silent-ignore) live
in ``tests/unit/tools/test_get_window_state.py``.

Coverage:

- PM token over MCP → structured response with populated fields.
- Engineer token over MCP → structured response.
- Implementer/Tester rejected with structured MCP error
  (parametrized).
- Invalid bucket value → Pydantic validation error at the boundary.
- Wrapper fires ``_observe_caller`` (per
  ``project_mcp_tool_wrapper_activity_recording`` memory).
"""

from __future__ import annotations

import asyncio
import contextlib
import socket
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
import pytest_asyncio
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client

from meridian_orchestrator.auth import IssuedToken, TokenStore
from meridian_orchestrator.bridge.oauth_usage import (
    CachedSnapshot,
    OAuthUsagePoller,
    SnapshotFetchResult,
)
from meridian_orchestrator.config import Settings
from meridian_orchestrator.server import serve as serve_mcp
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _make_snapshot() -> CachedSnapshot:
    """Build a fresh snapshot for the tests. subscription_5h at 17%
    utilization, resets in 4 hours."""
    polled_at = datetime.now(tz=UTC)
    resets_at = (polled_at + timedelta(hours=4)).isoformat(
        timespec="microseconds"
    )
    return CachedSnapshot(
        payload={
            "five_hour": {"utilization": 17.0, "resets_at": resets_at},
            "seven_day": {
                "utilization": 15.0,
                "resets_at": (
                    polled_at + timedelta(days=6)
                ).isoformat(timespec="microseconds"),
            },
        },
        polled_at=polled_at,
        null_counters=[],
    )


@asynccontextmanager
async def _running_server(
    settings: Settings,
    storage: SqliteStorage,
    snapshot: CachedSnapshot | None,
) -> AsyncIterator[None]:
    ready = asyncio.Event()
    task = asyncio.create_task(
        serve_mcp(
            settings,
            ready=ready,
            storage=storage,
            get_oauth_usage_snapshot=lambda: snapshot,
        ),
        name="mcp-test",
    )
    try:
        await asyncio.wait_for(ready.wait(), timeout=15)
        yield
    finally:
        task.cancel()
        with contextlib.suppress(TimeoutError, asyncio.CancelledError):
            await asyncio.wait_for(task, timeout=15)


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    await s.execute(
        "INSERT INTO companies (id, name) VALUES ('co1', 'Meridian AI')"
    )
    await s.execute(
        "INSERT INTO projects (id, company_id, name) VALUES "
        "('proj1', 'co1', 'Substrate Product')"
    )
    await s.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, description) "
        "VALUES ('task-1', 'co1', 'proj1', 'pm', 'no-op')"
    )
    yield s
    await s.close()


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(
        data_dir=tmp_path,
        mcp_host="127.0.0.1",
        mcp_port=_free_port(),
    )


async def _seed_agent(s: SqliteStorage, agent_id: str, role: str) -> None:
    await s.execute(
        "INSERT INTO agents (id, company_id, role, config_path) "
        "VALUES (?, 'co1', ?, '/dev/null')",
        (agent_id, role),
    )


async def _issue_role_token(
    storage: SqliteStorage, role: str, label: str
) -> IssuedToken:
    token_store = TokenStore()
    issued = token_store.issue(agent_id=None, role=role, label=label)
    await _seed_agent(storage, issued.record.agent_id, role)
    return issued


async def _call_get_window_state(
    *,
    base_url: str,
    token: str,
    bucket: str = "subscription_5h",
    agent_id: str | None = None,
) -> object:
    url = f"{base_url}/mcp"
    args: dict[str, object] = {"bucket": bucket}
    if agent_id is not None:
        args["agent_id"] = agent_id
    headers = {"Authorization": f"Bearer {token}"}
    async with (
        streamablehttp_client(url, headers=headers) as (read, write, _),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        return await session.call_tool("get_window_state", args)


# ---------------------------------------------------------------------------
# PM and Engineer can invoke
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("allowed_role", ["pm", "engineer"])
async def test_allowed_role_can_invoke_get_window_state_over_mcp(
    settings: Settings, storage: SqliteStorage, allowed_role: str
) -> None:
    """PM and Engineer tokens get a structured response."""
    issued = await _issue_role_token(
        storage, allowed_role, f"{allowed_role}-window-state"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, _make_snapshot()):
        result = await _call_get_window_state(
            base_url=base_url, token=issued.token
        )

    assert getattr(result, "isError", False) is False, (
        f"role={allowed_role!r} got an MCP error: "
        f"{getattr(result, 'content', None)!r}"
    )


# ---------------------------------------------------------------------------
# Implementer / Tester rejected
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "rejected_role", ["implementer", "tester"]
)
async def test_implementer_tester_rejected_over_mcp(
    settings: Settings, storage: SqliteStorage, rejected_role: str
) -> None:
    """Implementer and Tester are not in the get_window_state
    allowlist per spec; the handler's _require_role rejects them and
    the wrapper surfaces the error structurally (not a 500).

    Implementer/Tester read their own usage via get_token_usage at
    M5-subscription-routing, not subscription-level state."""
    issued = await _issue_role_token(
        storage, rejected_role, f"{rejected_role}-window-state"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, _make_snapshot()):
        result = await _call_get_window_state(
            base_url=base_url, token=issued.token
        )

    assert getattr(result, "isError", False) is True


# ---------------------------------------------------------------------------
# Invalid bucket rejected at Pydantic boundary
# ---------------------------------------------------------------------------


async def test_invalid_bucket_rejected_as_structured_error(
    settings: Settings, storage: SqliteStorage
) -> None:
    """The bucket Literal restricts values to the 5-value enum; passing
    an unknown value must be rejected at the Pydantic validation
    boundary as a structured error, not a 500."""
    issued = await _issue_role_token(
        storage, "pm", "pm-invalid-bucket"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, _make_snapshot()):
        result = await _call_get_window_state(
            base_url=base_url,
            token=issued.token,
            bucket="not_a_real_bucket",
        )

    assert getattr(result, "isError", False) is True


# ---------------------------------------------------------------------------
# Wrapper fires _observe_caller
# ---------------------------------------------------------------------------


async def test_get_window_state_wrapper_fires_observe_caller(
    settings: Settings, storage: SqliteStorage
) -> None:
    """Per project_mcp_tool_wrapper_activity_recording memory, every
    MCP tool wrapper must call _observe_caller right after
    _resolve_agent_id. Pre-seed a dispatch row whose token_id matches
    the issued token, call get_window_state over MCP, observe that
    dispatches.last_activity_at + last_tool_called were updated.

    Mirror of test_record_api_call_wrapper_fires_activity_writer in
    test_record_api_call_mcp_wrapper.py — same shape, this wrapper.
    """
    issued = await _issue_role_token(
        storage, "engineer", "engineer-observe-caller"
    )
    await storage.execute(
        "INSERT INTO dispatches "
        "(id, task_id, role, status, working_directory, "
        "timeout_seconds, token_id) "
        "VALUES ('d-window-state', 'task-1', 'engineer-supervisor', "
        "'running', '/tmp/wt', 300, ?)",
        (issued.record.token_id,),
    )

    pre = await storage.fetch_one(
        "SELECT last_activity_at, last_tool_called FROM dispatches "
        "WHERE id = 'd-window-state'"
    )
    assert pre is not None
    assert pre["last_activity_at"] is None
    assert pre["last_tool_called"] is None

    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"
    async with _running_server(settings, storage, _make_snapshot()):
        result = await _call_get_window_state(
            base_url=base_url, token=issued.token
        )

    assert getattr(result, "isError", False) is False

    post = await storage.fetch_one(
        "SELECT last_activity_at, last_tool_called FROM dispatches "
        "WHERE id = 'd-window-state'"
    )
    assert post is not None
    assert post["last_activity_at"] is not None, (
        "get_window_state wrapper failed to update last_activity_at "
        "— _observe_caller is not wired into the wrapper."
    )
    assert post["last_tool_called"] == "get_window_state"


# ---------------------------------------------------------------------------
# Cold-start path through the wire
# ---------------------------------------------------------------------------


async def test_cold_start_returns_unknown_over_mcp(
    settings: Settings, storage: SqliteStorage
) -> None:
    """When the server's get_oauth_usage_snapshot returns None (cold
    start at production startup, before the first poll), the wrapper
    surfaces the unknown placeholder via MCP. Verifies the closure
    pattern threads None correctly through to the handler."""
    issued = await _issue_role_token(storage, "pm", "pm-cold-start")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, snapshot=None):
        result = await _call_get_window_state(
            base_url=base_url, token=issued.token
        )

    assert getattr(result, "isError", False) is False
    structured = getattr(result, "structuredContent", None)
    assert structured is not None
    assert structured["pace_recommendation"] == "unknown"
    assert structured["snapshot_age_seconds"] is None


# ---------------------------------------------------------------------------
# Full-stack with real OAuthUsagePoller + injected fake fetcher
# (M5-pm-mode-and-window-state commit 7 production-shape integration)
# ---------------------------------------------------------------------------


@asynccontextmanager
async def _running_server_with_live_poller(
    settings: Settings,
    storage: SqliteStorage,
    poller: OAuthUsagePoller,
) -> AsyncIterator[None]:
    """Spin the MCP server with a live OAuthUsagePoller's
    ``latest_snapshot`` as the closure target. Mirrors
    :func:`_running_server` but passes a poller-backed callable
    instead of a static snapshot — exercises the
    ``main.py``-shape integration where ``get_oauth_usage_snapshot``
    delegates to a running poller.
    """
    ready = asyncio.Event()
    task = asyncio.create_task(
        serve_mcp(
            settings,
            ready=ready,
            storage=storage,
            get_oauth_usage_snapshot=poller.latest_snapshot,
        ),
        name="mcp-test-with-poller",
    )
    try:
        await asyncio.wait_for(ready.wait(), timeout=15)
        yield
    finally:
        task.cancel()
        with contextlib.suppress(TimeoutError, asyncio.CancelledError):
            await asyncio.wait_for(task, timeout=15)


async def test_get_window_state_returns_populated_state_after_real_poll(
    settings: Settings, storage: SqliteStorage
) -> None:
    """Production-shape integration: a real OAuthUsagePoller is
    constructed with an injected fake fetcher returning the
    Experiment-4 payload; the poller's first snapshot lands; the
    MCP server's get_window_state wrapper closure dispatches to
    ``poller.latest_snapshot()`` and returns populated window data
    (NOT cold-start).

    Closes the with-poller end-to-end coverage gap from the commit
    7 prep review. Existing tests cover:
    - Static-snapshot injection (this file's other tests)
    - Direct-handler logic (test_get_window_state.py)
    - Lifespan wiring (test_daemon_lifecycle.py with TrackingPoller)

    What this test adds: the production code path where the closure
    threads through the running poller's ``latest_snapshot()``
    method, picks up a snapshot the poller wrote during normal
    operation, and surfaces it to a live MCP caller — verifying the
    full-stack wiring is intact.
    """
    polled_at = datetime.now(tz=UTC)
    resets_at = (polled_at + timedelta(hours=4)).isoformat(
        timespec="microseconds"
    )

    async def fake_fetch() -> SnapshotFetchResult:
        return SnapshotFetchResult(
            status_code=200,
            payload={
                "five_hour": {
                    "utilization": 17.0,
                    "resets_at": resets_at,
                },
            },
        )

    issued = await _issue_role_token(storage, "pm", "pm-real-poller")

    # Construct a real poller with a fast cadence and the fake
    # fetcher injected. The cadence override is at construction
    # time (not via the module-level default) so it actually
    # applies.
    poller = OAuthUsagePoller(
        storage=storage,
        snapshot_fetcher=fake_fetch,
        poll_interval_seconds=0.02,
    )
    await poller.start()
    try:
        # Wait for the poller's first snapshot to land. Fast cadence
        # means this should happen within a few hundred ms.
        for _ in range(100):
            if poller.latest_snapshot() is not None:
                break
            await asyncio.sleep(0.02)
        assert poller.latest_snapshot() is not None, (
            "real poller did not publish a snapshot within ~2s; "
            "polling/fetcher injection is broken in the test harness."
        )

        base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"
        async with _running_server_with_live_poller(
            settings, storage, poller
        ):
            result = await _call_get_window_state(
                base_url=base_url, token=issued.token
            )

        assert getattr(result, "isError", False) is False, (
            f"get_window_state returned an error: "
            f"{getattr(result, 'content', None)!r}"
        )
        structured = getattr(result, "structuredContent", None)
        assert structured is not None

        # Populated, NOT cold-start. This is the load-bearing
        # assertion: pace_recommendation is concrete (not 'unknown'),
        # snapshot_age_seconds is a real int, utilization matches
        # the fake payload.
        assert structured["pace_recommendation"] != "unknown", (
            "get_window_state returned 'unknown' despite the poller "
            "having a live snapshot; the closure is not threading "
            "through poller.latest_snapshot() correctly."
        )
        assert structured["snapshot_age_seconds"] is not None
        assert structured["snapshot_age_seconds"] >= 0
        assert structured["utilization"] == 17.0
        assert structured["bucket"] == "subscription_5h"
        assert structured["window_end"]  # populated, not empty string
    finally:
        await poller.shutdown()
