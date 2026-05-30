"""Tests for the op-live-activity live tool-call mirror in
``bridge.activity.record_mcp_call``.

``record_mcp_call`` keeps its single-point-of-write contract for
``dispatches.last_activity_at`` / ``last_tool_called`` and additionally
mirrors each tool call to the Control Plane as a ``dispatch.tool_activity``
live event — best-effort, fire-and-forget, and ONLY when the call lands on a
non-terminal dispatch (same gate as the activity-clock UPDATE).

The mirror is captured by monkeypatching ``emit_tool_activity`` (the network
side is covered in ``test_control_plane_emitter.py``); these tests assert the
*decision* to mirror + the payload fields the writer resolves from the
dispatch row.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any

import pytest
import pytest_asyncio

from meridian_orchestrator.bridge import activity as activity_mod
from meridian_orchestrator.bridge.activity import record_mcp_call
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


@pytest.fixture
def captured_emits(monkeypatch: pytest.MonkeyPatch) -> list[dict[str, Any]]:
    """Capture emit_tool_activity(...) kwargs instead of emitting over HTTP."""
    calls: list[dict[str, Any]] = []

    def _capture(**kwargs: Any) -> None:
        calls.append(kwargs)

    monkeypatch.setattr(activity_mod, "emit_tool_activity", _capture)
    return calls


async def _insert_dispatch(
    storage: SqliteStorage,
    *,
    dispatch_id: str,
    token_id: str,
    role: str = "engineer",
    status: str = "running",
    canonical_name: str = "default",
) -> None:
    await storage.execute(
        "INSERT INTO dispatches "
        "(id, role, status, working_directory, timeout_seconds, token_id, "
        "canonical_name) "
        "VALUES (?, ?, ?, '/tmp/wt', 1800, ?, ?)",
        (dispatch_id, role, status, token_id, canonical_name),
    )


async def test_record_mcp_call_emits_tool_activity_for_active_dispatch(
    storage: SqliteStorage, captured_emits: list[dict[str, Any]]
) -> None:
    await _insert_dispatch(
        storage,
        dispatch_id="d-live",
        token_id="tok-live",
        role="implementer",
        canonical_name="curasync",
    )

    await record_mcp_call(storage, "tok-live", "Edit")

    # Activity clock advanced (single-point-of-write contract preserved).
    row = await storage.fetch_one(
        "SELECT last_tool_called FROM dispatches WHERE id = 'd-live'"
    )
    assert row is not None and row["last_tool_called"] == "Edit"

    # Live mirror fired with the dispatch-resolved fields.
    assert len(captured_emits) == 1
    emit = captured_emits[0]
    assert emit["dispatch_id"] == "d-live"
    assert emit["tool_name"] == "Edit"
    assert emit["role"] == "implementer"
    assert emit["canonical_name"] == "curasync"


async def test_record_mcp_call_no_emit_when_token_unbound(
    storage: SqliteStorage, captured_emits: list[dict[str, Any]]
) -> None:
    # No dispatch row for this token (operator OAuth / static admin token).
    await record_mcp_call(storage, "tok-unbound", "ping")
    assert captured_emits == []


async def test_record_mcp_call_no_emit_for_terminal_dispatch(
    storage: SqliteStorage, captured_emits: list[dict[str, Any]]
) -> None:
    # A final tool call after the dispatch terminated must not reset the
    # activity clock NOR emit a live signal (same non-terminal gate).
    await _insert_dispatch(
        storage,
        dispatch_id="d-done",
        token_id="tok-done",
        status="completed",
    )
    await record_mcp_call(storage, "tok-done", "Read")
    assert captured_emits == []
    # Activity clock untouched.
    row = await storage.fetch_one(
        "SELECT last_tool_called FROM dispatches WHERE id = 'd-done'"
    )
    assert row is not None and row["last_tool_called"] is None
