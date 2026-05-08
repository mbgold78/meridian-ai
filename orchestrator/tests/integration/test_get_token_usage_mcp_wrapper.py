"""End-to-end MCP wrapper tests for ``get_token_usage``.

Exercises the wrapper against a real Streamable-HTTP MCP server with a
fake snapshot provider injected via ``serve(...,
get_oauth_usage_snapshot=...)``. Direct-handler tests (windows,
hierarchy, breakdowns, NULL cost) live in
``tests/integration/test_get_token_usage.py``.

Coverage:

- PM / Engineer tokens over MCP → structured response.
- Implementer / Tester get their OWN usage (allowed scope per spec).
- Wrapper-resolved caller_role drives subtree filtering on the live
  wire — Engineer cannot see PM's usage even when the data exists.
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
from meridian_orchestrator.bridge.oauth_usage import CachedSnapshot
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


async def _seed_usage(
    s: SqliteStorage,
    *,
    usage_id: str,
    agent_id: str,
    agent_role: str,
    input_tokens: int,
    output_tokens: int,
) -> None:
    await s.execute(
        "INSERT INTO api_usage "
        "(id, agent_id, agent_role, model, input_tokens, output_tokens, "
        "cost_usd, window_start) "
        "VALUES (?, ?, ?, 'claude-sonnet-4-6', ?, ?, 0.001, ?)",
        (
            usage_id,
            agent_id,
            agent_role,
            input_tokens,
            output_tokens,
            (datetime.now(tz=UTC) - timedelta(hours=1)).isoformat(
                timespec="microseconds"
            ),
        ),
    )


async def _call_get_token_usage(
    *,
    base_url: str,
    token: str,
    agent_id: str | None = None,
    window: str = "last_24h",
) -> object:
    url = f"{base_url}/mcp"
    args: dict[str, object] = {"window": window}
    if agent_id is not None:
        args["agent_id"] = agent_id
    headers = {"Authorization": f"Bearer {token}"}
    async with (
        streamablehttp_client(url, headers=headers) as (read, write, _),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        return await session.call_tool("get_token_usage", args)


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
async def test_pm_engineer_get_token_usage_over_mcp(
    settings: Settings,
    storage: SqliteStorage,
    allowed_role: str,
) -> None:
    """PM and Engineer tokens succeed end-to-end. Verifies wrapper
    routes to the handler with caller_role correctly derived from
    OAuth scope.
    """
    issued = await _issue_role_token(
        storage, allowed_role, f"{allowed_role}-tokens-test"
    )
    # Seed one engineer-role usage row that's in scope for both.
    await _seed_agent(storage, "other-engineer", "engineer")
    await _seed_usage(
        storage,
        usage_id="u-1",
        agent_id="other-engineer",
        agent_role="engineer",
        input_tokens=100,
        output_tokens=50,
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, snapshot=None):
        result = await _call_get_token_usage(
            base_url=base_url, token=issued.token, window="last_24h"
        )

    assert getattr(result, "isError", False) is False, (
        f"role={allowed_role!r} got an MCP error: "
        f"{getattr(result, 'content', None)!r}"
    )
    out = _structured(result)
    assert out["input_tokens"] == 100
    assert out["output_tokens"] == 50


async def test_engineer_cannot_see_pm_usage_via_subtree_filter(
    settings: Settings, storage: SqliteStorage
) -> None:
    """Engineer's wrapper-resolved subtree excludes pm-role rows.
    Verifies the wrapper's caller_role propagation drives the subtree
    filter on the live wire.

    Failure mode this prevents: a future commit that bypasses
    _resolve_caller_role_via_token and defaults caller_role to 'pm'
    (or any superset) would let Engineer see PM's usage in
    production. This test makes the subtree-via-wrapper-resolution
    contract durably enforced.
    """
    issued = await _issue_role_token(
        storage, "engineer", "engineer-cant-see-pm"
    )
    # Seed a PM-role usage row that should NOT appear in engineer's view.
    await _seed_agent(storage, "the-pm", "pm")
    await _seed_usage(
        storage,
        usage_id="u-pm",
        agent_id="the-pm",
        agent_role="pm",
        input_tokens=999,
        output_tokens=999,
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, snapshot=None):
        result = await _call_get_token_usage(
            base_url=base_url, token=issued.token, window="last_24h"
        )

    assert getattr(result, "isError", False) is False
    out = _structured(result)
    assert out["input_tokens"] == 0, (
        "Engineer saw PM's usage rows — wrapper-layer subtree filter "
        "is not propagating caller_role to the handler. The "
        "_AGENT_ROLE_SUBTREE['engineer'] frozenset should exclude "
        "'pm', and the handler's role-IN filter should drop pm rows."
    )


@pytest.mark.parametrize("scope_role", ["implementer", "tester"])
async def test_implementer_tester_see_only_self_via_mcp(
    settings: Settings,
    storage: SqliteStorage,
    scope_role: str,
) -> None:
    """Implementer/Tester invoking get_token_usage with no agent_id
    implicitly scope to their own agent_id. Sibling rows excluded.
    """
    issued = await _issue_role_token(
        storage, scope_role, f"{scope_role}-self-only"
    )
    # Seed one row for the caller and one row for a sibling agent.
    await _seed_usage(
        storage,
        usage_id="u-self",
        agent_id=issued.record.agent_id,
        agent_role=scope_role,
        input_tokens=100,
        output_tokens=50,
    )
    sibling_role = "tester" if scope_role == "implementer" else "implementer"
    await _seed_agent(storage, "sibling-agent", sibling_role)
    await _seed_usage(
        storage,
        usage_id="u-sibling",
        agent_id="sibling-agent",
        agent_role=sibling_role,
        input_tokens=999,
        output_tokens=999,
    )

    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, snapshot=None):
        result = await _call_get_token_usage(
            base_url=base_url, token=issued.token, window="last_24h"
        )

    assert getattr(result, "isError", False) is False
    out = _structured(result)
    # Only self's row contributes.
    assert out["input_tokens"] == 100
    assert out["output_tokens"] == 50
