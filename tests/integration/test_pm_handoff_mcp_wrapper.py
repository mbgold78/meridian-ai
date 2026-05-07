"""End-to-end MCP wrapper tests for ``write_pm_handoff`` +
``get_most_recent_handoff`` (M5-continuous-pm-driver commit 8).

Exercises the MCP wrappers against a real Streamable-HTTP MCP server
with a tmp_path-rooted handoff directory. Verifies:

- PM-token round-trip on both tools (write succeeds; read returns
  the most-recent content + written-at).
- Permission denied for non-PM tokens via structured MCP error
  (parametrized x 3 non-PM roles x 2 tools = 6 collected).
- Cold-start: get_most_recent_handoff returns None fields when no
  prior handoff exists.

Direct-handler tests for the underlying behavior live in
``tests/unit/tools/test_pm_handoff.py``. End-to-end compaction-write-
then-respawn-read flow is in ``tests/integration/test_pm_compaction_e2e.py``.
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
    settings: Settings,
    storage: SqliteStorage,
    handoff_dir: Path,
) -> AsyncIterator[None]:
    def _get_pm_handoff_dir() -> Path:
        return handoff_dir

    ready = asyncio.Event()
    task = asyncio.create_task(
        serve_mcp(
            settings,
            ready=ready,
            storage=storage,
            get_pm_handoff_dir=_get_pm_handoff_dir,
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
def handoff_dir(tmp_path: Path) -> Path:
    return tmp_path / "pm-handoffs"


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
# write_pm_handoff — PM round-trip + non-PM rejection
# ---------------------------------------------------------------------------


async def test_pm_token_write_pm_handoff_writes_file_and_audits(
    settings: Settings,
    storage: SqliteStorage,
    handoff_dir: Path,
) -> None:
    """PM token → write_pm_handoff wrapper → handler. Verifies the
    file lands at the closure-resolved handoff_dir and the audit row
    records the path."""
    issued = await _issue_role_token(storage, "pm", "handoff-write-happy")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    content = "# Handoff\n\n## current_focus\nshipping commit 8\n"

    async with _running_server(settings, storage, handoff_dir):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="write_pm_handoff",
            args={"content": content},
        )

    assert getattr(result, "isError", False) is False
    out = _structured(result)
    assert out["content_size"] == len(content)
    written_path = Path(str(out["path"]))
    assert written_path.exists()
    assert written_path.read_text(encoding="utf-8") == content
    assert written_path.parent == handoff_dir

    rows = await storage.fetch_all(
        "SELECT actor, action FROM audit_log "
        "WHERE action = 'pm.compaction.handoff_written'"
    )
    assert len(rows) == 1
    assert rows[0]["actor"] == issued.record.agent_id


@pytest.mark.parametrize(
    "non_pm_role", ["engineer", "implementer", "tester"]
)
async def test_non_pm_token_write_pm_handoff_rejected(
    settings: Settings,
    storage: SqliteStorage,
    handoff_dir: Path,
    non_pm_role: str,
) -> None:
    issued = await _issue_role_token(
        storage, non_pm_role, f"non-pm-{non_pm_role}-handoff-write"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, handoff_dir):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="write_pm_handoff",
            args={"content": "rejected content"},
        )

    assert getattr(result, "isError", False) is True


# ---------------------------------------------------------------------------
# get_most_recent_handoff — PM round-trip + cold-start + non-PM rejection
# ---------------------------------------------------------------------------


async def test_pm_token_get_most_recent_handoff_returns_prior_write(
    settings: Settings,
    storage: SqliteStorage,
    handoff_dir: Path,
) -> None:
    """PM writes a handoff; PM reads it back via get_most_recent_handoff.
    Round-trip exercises the closure plumbing for both tools sharing
    the same handoff_dir."""
    issued = await _issue_role_token(storage, "pm", "handoff-rt")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    written_content = "# Resumed context\n\nplanned_next: ship commit 9\n"

    async with _running_server(settings, storage, handoff_dir):
        write_result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="write_pm_handoff",
            args={"content": written_content},
        )
        assert getattr(write_result, "isError", False) is False

        read_result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="get_most_recent_handoff",
            args={},
        )

    assert getattr(read_result, "isError", False) is False
    out = _structured(read_result)
    assert out["content"] == written_content
    assert out["written_at"] is not None


async def test_pm_token_get_most_recent_handoff_cold_start_returns_none(
    settings: Settings,
    storage: SqliteStorage,
    handoff_dir: Path,
) -> None:
    """No prior write → content + written_at are both None."""
    issued = await _issue_role_token(storage, "pm", "handoff-cold-start")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, handoff_dir):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="get_most_recent_handoff",
            args={},
        )

    assert getattr(result, "isError", False) is False
    out = _structured(result)
    assert out["content"] is None
    assert out["written_at"] is None


@pytest.mark.parametrize(
    "non_pm_role", ["engineer", "implementer", "tester"]
)
async def test_non_pm_token_get_most_recent_handoff_rejected(
    settings: Settings,
    storage: SqliteStorage,
    handoff_dir: Path,
    non_pm_role: str,
) -> None:
    issued = await _issue_role_token(
        storage, non_pm_role, f"non-pm-{non_pm_role}-handoff-read"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, handoff_dir):
        result = await _call_tool(
            base_url=base_url,
            token=issued.token,
            tool_name="get_most_recent_handoff",
            args={},
        )

    assert getattr(result, "isError", False) is True
