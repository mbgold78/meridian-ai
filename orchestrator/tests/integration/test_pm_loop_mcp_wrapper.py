"""End-to-end MCP wrapper tests for ``wait_for_next_tick`` +
``get_loop_state`` (M5-continuous-pm-driver commit 7).

Exercises the MCP wrappers against a real Streamable-HTTP MCP
server with a fake snapshot provider + monkeypatched asyncio.sleep
for cadence determinism.

- PM-token round-trip on both tools.
- Permission denied for non-PM tokens via structured MCP error.
- Cadence resolution closure produces expected sleep duration.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import socket
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

import pytest
import pytest_asyncio
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client

from meridian_orchestrator.auth import IssuedToken, TokenStore
from meridian_orchestrator.bridge.oauth_usage import CachedSnapshot
from meridian_orchestrator.config import Settings
from meridian_orchestrator.server import serve as serve_mcp
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _fake_snapshot_provider() -> CachedSnapshot:
    """Happy-path snapshot satisfying both subscription_5h and
    subscription_7d_opus get_window_state branches + get_burn_rate's
    finite-data path."""
    now = datetime.now(tz=UTC)
    payload: dict[str, Any] = {
        "five_hour": {
            "utilization": 30.0,
            "resets_at": (now + timedelta(hours=3)).isoformat(),
        },
        "seven_day_opus": {
            "utilization": 45.0,
            "resets_at": (now + timedelta(days=5)).isoformat(),
        },
        "extra_usage": {"is_enabled": False},
    }
    return CachedSnapshot(payload=payload, polled_at=now, null_counters=[])


def _get_tick_seconds_short() -> float:
    """Small tick value for tests; combined with monkeypatched
    asyncio.sleep this never actually waits."""
    return 0.01


@asynccontextmanager
async def _running_server(
    settings: Settings,
    storage: SqliteStorage,
) -> AsyncIterator[None]:
    ready = asyncio.Event()
    task = asyncio.create_task(
        serve_mcp(
            settings,
            ready=ready,
            storage=storage,
            get_oauth_usage_snapshot=_fake_snapshot_provider,
            get_pm_loop_tick_seconds=_get_tick_seconds_short,
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


async def _call_tool(
    *,
    base_url: str,
    token: str,
    tool_name: str,
    args: dict[str, object],
) -> object:
    url = f"{base_url}/mcp"
    headers = {"Authorization": f"Bearer {token}"}
    async with (
        streamablehttp_client(url, headers=headers) as (read, write, _),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        return await session.call_tool(tool_name, args)


def _structured(result: object) -> dict[str, object]:
    structured = getattr(result, "structuredContent", None)
    if structured is not None:
        return dict(structured)
    text = result.content[0].text  # type: ignore[union-attr]
    return dict(json.loads(text))


# ---------------------------------------------------------------------------
# wait_for_next_tick — PM round-trip + cadence assertion
# ---------------------------------------------------------------------------


async def test_pm_token_wait_for_next_tick_returns_slept_seconds(
    settings: Settings,
    storage: SqliteStorage,
) -> None:
    """PM token → wait_for_next_tick wrapper → handler. Uses a real
    10ms tick value (`_get_tick_seconds_short`) without monkeypatching
    asyncio.sleep — monkeypatching asyncio.sleep globally breaks the
    server's startup loop (server.py polls for `server.started` via
    asyncio.sleep(0.05); a fake_sleep that doesn't actually wait
    converts that into a busy loop). Unit tests in
    test_pm_loop.py cover the monkeypatched-sleep seam at the
    handler layer; integration test verifies the round-trip via real
    short cadence."""
    issued = await _issue_role_token(storage, "pm", "wait-tick-happy")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="wait_for_next_tick",
            args={},
        )

    assert getattr(result, "isError", False) is False
    out = _structured(result)
    assert out["slept_seconds"] == 0.01


async def test_engineer_token_wait_for_next_tick_allowed(
    settings: Settings,
    storage: SqliteStorage,
) -> None:
    """Engineer token → wait_for_next_tick wrapper → handler succeeds
    (M5-acceptance follow-on; BACKLOG Option 1 — Engineer-supervisor
    continuous-loop discipline). Production MCP path round-trip
    verification."""
    issued = await _issue_role_token(storage, "engineer", "engineer-tick")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="wait_for_next_tick",
            args={},
        )

    assert getattr(result, "isError", False) is False
    out = _structured(result)
    assert out["slept_seconds"] >= 0.0


@pytest.mark.parametrize(
    "rejected_role", ["implementer", "tester"]
)
async def test_non_pm_non_engineer_token_wait_for_next_tick_rejected(
    settings: Settings,
    storage: SqliteStorage,
    rejected_role: str,
) -> None:
    """Implementer/Tester tokens rejected at production MCP path.
    Engineer was widened to allowed at M5-acceptance follow-on per
    BACKLOG Option 1; remaining non-pm roles stay rejected (Pin 13
    discipline — no silent permission inheritance)."""
    issued = await _issue_role_token(
        storage, rejected_role, f"non-pm-{rejected_role}-tick"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="wait_for_next_tick",
            args={},
        )

    assert getattr(result, "isError", False) is True


# ---------------------------------------------------------------------------
# get_loop_state — PM round-trip + aggregation surface check
# ---------------------------------------------------------------------------


async def test_pm_token_get_loop_state_returns_aggregated_response(
    settings: Settings,
    storage: SqliteStorage,
) -> None:
    """PM token → get_loop_state wrapper → handler. Verifies the
    aggregator's response carries all four bundled reads."""
    issued = await _issue_role_token(storage, "pm", "loop-state-happy")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="get_loop_state",
            args={},
        )

    assert getattr(result, "isError", False) is False
    out = _structured(result)
    assert "subscription_5h_state" in out
    assert "subscription_7d_opus_state" in out
    assert "burn_rate" in out
    assert "current_focus" in out
    sub_5h = dict(out["subscription_5h_state"])  # type: ignore[arg-type]
    assert sub_5h["bucket"] == "subscription_5h"
    sub_7d_opus = dict(out["subscription_7d_opus_state"])  # type: ignore[arg-type]
    assert sub_7d_opus["bucket"] == "subscription_7d_opus"
    assert out["current_focus"] is None  # no focus declared in this test


async def test_engineer_token_get_loop_state_allowed(
    settings: Settings,
    storage: SqliteStorage,
) -> None:
    """Engineer token → get_loop_state wrapper → handler succeeds
    (M5-acceptance follow-on; BACKLOG Option 1). Engineer consumes
    subscription_5h_state for self-pacing per engineer.yaml
    § Continuous mode."""
    issued = await _issue_role_token(storage, "engineer", "engineer-loop")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="get_loop_state",
            args={},
        )

    assert getattr(result, "isError", False) is False
    out = _structured(result)
    assert "subscription_5h_state" in out


@pytest.mark.parametrize(
    "rejected_role", ["implementer", "tester"]
)
async def test_non_pm_non_engineer_token_get_loop_state_rejected(
    settings: Settings,
    storage: SqliteStorage,
    rejected_role: str,
) -> None:
    """Implementer/Tester tokens rejected at production MCP path.
    Engineer was widened to allowed at M5-acceptance follow-on per
    BACKLOG Option 1; remaining non-pm roles stay rejected (Pin 13
    discipline)."""
    issued = await _issue_role_token(
        storage, rejected_role, f"non-pm-{rejected_role}-loop"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="get_loop_state",
            args={},
        )

    assert getattr(result, "isError", False) is True
