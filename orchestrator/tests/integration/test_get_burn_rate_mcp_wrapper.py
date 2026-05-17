"""End-to-end MCP wrapper tests for ``get_burn_rate``.

Exercises the wrapper against a real Streamable-HTTP MCP server.
Direct-handler tests (math, projection, scope) live in
``tests/integration/test_get_burn_rate.py``.

Coverage:

- PM / Engineer tokens over MCP → structured response.
- Implementer / Tester rejected with structured MCP error
  (parametrized).
- Burn rate is global scope on the live wire — Engineer's view
  matches PM's view (Q8 contract on the wrapper path).
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

import pytest
import pytest_asyncio
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client

from meridian_orchestrator.auth import IssuedToken, TokenStore
from meridian_orchestrator.config import Settings
from meridian_orchestrator.server import serve as serve_mcp
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@asynccontextmanager
async def _running_server(
    settings: Settings, storage: SqliteStorage
) -> AsyncIterator[None]:
    ready = asyncio.Event()
    task = asyncio.create_task(
        serve_mcp(
            settings,
            ready=ready,
            storage=storage,
            get_oauth_usage_snapshot=lambda: None,
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
    issued = await token_store.issue(agent_id=None, role=role, label=label)
    await _seed_agent(storage, issued.record.agent_id, role)
    return issued


async def _seed_usage(
    s: SqliteStorage, agent_id: str, agent_role: str, tokens: int
) -> None:
    await s.execute(
        "INSERT INTO api_usage "
        "(id, agent_id, agent_role, model, input_tokens, output_tokens, "
        "cost_usd, window_start) "
        "VALUES (?, ?, ?, 'claude-sonnet-4-6', ?, 0, 0.001, ?)",
        (
            f"u-{agent_id}",
            agent_id,
            agent_role,
            tokens,
            (datetime.now(tz=UTC) - timedelta(hours=12)).isoformat(
                timespec="microseconds"
            ),
        ),
    )


async def _call_get_burn_rate(
    *, base_url: str, token: str, window: str = "last_24h"
) -> object:
    url = f"{base_url}/mcp"
    headers = {"Authorization": f"Bearer {token}"}
    async with (
        streamablehttp_client(url, headers=headers) as (read, write, _),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        return await session.call_tool("get_burn_rate", {"window": window})


def _structured(result: object) -> dict[str, object]:
    structured = getattr(result, "structuredContent", None)
    if structured is not None:
        return dict(structured)
    text = result.content[0].text  # type: ignore[union-attr]
    return dict(json.loads(text))


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("allowed_role", ["pm", "engineer"])
async def test_pm_engineer_can_invoke_get_burn_rate(
    settings: Settings, storage: SqliteStorage, allowed_role: str
) -> None:
    """PM and Engineer tokens get a structured response."""
    issued = await _issue_role_token(
        storage, allowed_role, f"{allowed_role}-burn-test"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"
    async with _running_server(settings, storage):
        result = await _call_get_burn_rate(
            base_url=base_url, token=issued.token
        )
    assert getattr(result, "isError", False) is False, (
        f"role={allowed_role!r} got an MCP error: "
        f"{getattr(result, 'content', None)!r}"
    )


@pytest.mark.parametrize("rejected_role", ["implementer", "tester"])
async def test_implementer_tester_rejected_get_burn_rate(
    settings: Settings, storage: SqliteStorage, rejected_role: str
) -> None:
    """Implementer/Tester are not in the get_burn_rate allowlist; the
    handler's _require_role rejects them and the wrapper surfaces a
    structured error."""
    issued = await _issue_role_token(
        storage, rejected_role, f"{rejected_role}-burn-rejected"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"
    async with _running_server(settings, storage):
        result = await _call_get_burn_rate(
            base_url=base_url, token=issued.token
        )
    assert getattr(result, "isError", False) is True, (
        f"role={rejected_role!r} unexpectedly succeeded — handler-layer "
        "_require_role should have rejected this caller. The "
        "permission table at 06-mcp-tools-phase-one.md lists "
        "get_burn_rate as PM/Engineer/Mike-only."
    )


async def test_burn_rate_global_scope_engineer_sees_pm_tokens(
    settings: Settings, storage: SqliteStorage
) -> None:
    """Engineer's burn rate aggregates PM's tokens too — Q8 global-
    scope contract on the live wire. Verifies the scope contract
    propagates through the wrapper layer (not just direct-handler).
    """
    issued = await _issue_role_token(
        storage, "engineer", "engineer-global-scope"
    )
    # Seed a PM-role usage row that engineer must aggregate.
    await _seed_agent(storage, "the-pm", "pm")
    await _seed_usage(storage, "the-pm", "pm", tokens=2400)

    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"
    async with _running_server(settings, storage):
        result = await _call_get_burn_rate(
            base_url=base_url, token=issued.token, window="last_24h"
        )

    assert getattr(result, "isError", False) is False
    out = _structured(result)
    # 2400 tokens / 24h = 100 tok/h. Engineer must see this.
    assert abs(float(out["tokens_per_hour"]) - 100.0) < 1e-9, (
        "Engineer's burn rate did not include PM's tokens — the "
        "global-scope contract per Q8 disposition is broken on the "
        "wrapper path. Engineer and PM should observe identical burn "
        "rates because the rate reflects the whole subscription."
    )
