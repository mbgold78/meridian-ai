"""End-to-end MCP wrapper tests for ``record_sampleable_event``.

Exercises the MCP wrapper against a real Streamable-HTTP MCP server
with a Sampler at 100% sampling (so every call writes a row,
deterministic for testing).

- PM-token writes: full request path → handler → audit_sample_log INSERT.
- Permission denied for non-PM tokens via structured MCP error.
- Closed-enum validation rejects bogus source_event_kind at the
  Pydantic boundary.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import socket
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path

import pytest
import pytest_asyncio
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client

from meridian_orchestrator.audit.sampler import Sampler
from meridian_orchestrator.auth import IssuedToken, TokenStore
from meridian_orchestrator.config import Settings
from meridian_orchestrator.server import serve as serve_mcp
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage

# ---------------------------------------------------------------------------
# Fixtures (mirror test_pm_spin_mcp_wrapper.py shape)
# ---------------------------------------------------------------------------


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


@asynccontextmanager
async def _running_server(
    settings: Settings,
    storage: SqliteStorage,
    sampler: Sampler,
) -> AsyncIterator[None]:
    ready = asyncio.Event()
    task = asyncio.create_task(
        serve_mcp(
            settings, ready=ready, storage=storage, sampler=sampler
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


@pytest.fixture
def sampler() -> Sampler:
    """100% sampling for deterministic test behavior — every call
    writes a row."""
    return Sampler(1.0)


async def _seed_agent(s: SqliteStorage, agent_id: str, role: str) -> None:
    await s.execute(
        "INSERT INTO agents (id, company_id, role, config_path) "
        "VALUES (?, 'co1', ?, '/dev/null')",
        (agent_id, role),
    )


async def _issue_role_token(
    storage: SqliteStorage, role: str, label: str
) -> IssuedToken:
    """Issue a token for ``role`` and seed the matching agents row."""
    token_store = TokenStore()
    issued = token_store.issue(agent_id=None, role=role, label=label)
    await _seed_agent(storage, issued.record.agent_id, role)
    return issued


# ---------------------------------------------------------------------------
# MCP client helpers
# ---------------------------------------------------------------------------


async def _call_record_sampleable_event(
    *,
    base_url: str,
    token: str,
    source_event_kind: str,
    source_event_id: str,
) -> object:
    url = f"{base_url}/mcp"
    args: dict[str, object] = {
        "source_event_kind": source_event_kind,
        "source_event_id": source_event_id,
    }
    headers = {"Authorization": f"Bearer {token}"}
    async with (
        streamablehttp_client(url, headers=headers) as (read, write, _),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        return await session.call_tool("record_sampleable_event", args)


def _structured(result: object) -> dict[str, object]:
    structured = getattr(result, "structuredContent", None)
    if structured is not None:
        return dict(structured)
    text = result.content[0].text  # type: ignore[union-attr]
    return dict(json.loads(text))


# ---------------------------------------------------------------------------
# Test 1: PM token writes via MCP at 100% sampling
# ---------------------------------------------------------------------------


async def test_pm_token_record_sampleable_event_writes_row(
    settings: Settings, storage: SqliteStorage, sampler: Sampler
) -> None:
    """Full request path: PM token → record_sampleable_event wrapper →
    handler → Sampler.maybe_sample → audit_sample_log INSERT. With
    sampler at 100%, every call writes a row."""
    issued = await _issue_role_token(storage, "pm", "audit-sample-happy")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, sampler):
        result = await _call_record_sampleable_event(
            base_url=base_url,
            token=issued.token,
            source_event_kind="pm.current_focus.completed",
            source_event_id="audit-row-test-1",
        )

    assert getattr(result, "isError", False) is False, (
        f"record_sampleable_event wrapper returned error: "
        f"{getattr(result, 'content', None)!r}"
    )
    out = _structured(result)
    assert out["sample_id"] is not None

    rows = await storage.fetch_all(
        "SELECT id, source_event_kind, source_event_id, re_eval_state "
        "FROM audit_sample_log"
    )
    assert len(rows) == 1
    assert rows[0]["source_event_kind"] == "pm.current_focus.completed"
    assert rows[0]["source_event_id"] == "audit-row-test-1"
    assert rows[0]["re_eval_state"] == "pending"


# ---------------------------------------------------------------------------
# Test 2: non-PM tokens get permission-denied
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "non_pm_role", ["engineer", "implementer", "tester"]
)
async def test_non_pm_token_cannot_record_sampleable_event_over_mcp(
    settings: Settings,
    storage: SqliteStorage,
    sampler: Sampler,
    non_pm_role: str,
) -> None:
    """Engineer, Implementer, and Tester tokens get permission-denied
    at the handler's _require_role check. The MCP layer surfaces the
    error structurally, not as a 500. No audit_sample_log row is
    written — rejection happens before Sampler.maybe_sample."""
    issued = await _issue_role_token(
        storage, non_pm_role, f"non-pm-{non_pm_role}-sample"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, sampler):
        result = await _call_record_sampleable_event(
            base_url=base_url,
            token=issued.token,
            source_event_kind="pm.current_focus.completed",
            source_event_id="audit-row-test-rejected",
        )

    assert getattr(result, "isError", False) is True, (
        f"role={non_pm_role!r} successfully invoked "
        "record_sampleable_event; the PM-only role gate at the handler "
        "layer has been removed or bypassed."
    )

    rows = await storage.fetch_all("SELECT id FROM audit_sample_log")
    assert rows == []
