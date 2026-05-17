"""End-to-end tests that a tool wrapper observes caller activity.

The unit-level test ``test_record_mcp_call_*`` in
``test_supervisor_base.py`` proves the dispatches-update SQL works
in isolation; ``tests/unit/bridge/test_oauth_pm_auto_flip.py`` proves
the auto-flip writer in isolation. This file proves the **wiring** —
that the actual MCP tool wrapper in ``server.py`` calls
``_observe_caller`` on every authenticated call, threading through
the real auth context, the real token parser, the real
``record_mcp_call`` helper, AND the real ``record_oauth_pm_auto_flip``
helper. Both fan-outs land or neither (transactional atomicity).

Pattern: spin up the MCP server with a real storage handle, pre-seed
the relevant fixtures (dispatch row for activity update; current PM
mode for auto-flip), call ``ping`` via the real Streamable-HTTP
client, assert the expected writes landed.

This file catches the regression where a future tool wrapper is added
without the ``_observe_caller`` call (memory:
``project_mcp_tool_wrapper_activity_recording``). New wrappers extend
this file with a parallel test per new wrapper, or a parameterised
test covering all wrappers.
"""

from __future__ import annotations

import asyncio
import contextlib
import socket
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path

import pytest
import pytest_asyncio
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client

from meridian_orchestrator.auth import TokenStore
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
        serve_mcp(settings, ready=ready, storage=storage), name="mcp-test"
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
        "INSERT INTO companies (id, name) VALUES ('co1', 'Test Co')"
    )
    await s.execute(
        "INSERT INTO projects (id, company_id, name) VALUES "
        "('proj1', 'co1', 'Test Project')"
    )
    await s.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, description) "
        "VALUES ('task-1', 'co1', 'proj1', 'implementer', 'test')"
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


async def _call_ping(url: str, headers: dict[str, str], echo: str) -> object:
    async with (
        streamablehttp_client(url, headers=headers) as (read, write, _),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        return await session.call_tool("ping", {"echo": echo})


async def test_ping_wrapper_records_activity_end_to_end(
    storage: SqliteStorage, settings: Settings
) -> None:
    """Issue a token, seed a dispatch row whose token_id matches, call ping
    via the real MCP client, assert the dispatch row's last_activity_at and
    last_tool_called were updated by the helper invocation in the wrapper."""
    token_store = TokenStore()
    issued = await token_store.issue(
        agent_id="agent-test", role="implementer", label="e2e-activity"
    )

    # Pre-seed a dispatch row whose token_id matches the issued token.
    # Status='running' so the writer's terminal-state guard does NOT skip.
    await storage.execute(
        "INSERT INTO dispatches "
        "(id, task_id, role, status, working_directory, "
        "timeout_seconds, token_id) "
        "VALUES ('d-e2e', 'task-1', 'implementer', 'running', "
        "'/tmp/test', 300, ?)",
        (issued.record.token_id,),
    )

    # Pre-state: both activity columns NULL.
    pre = await storage.fetch_one(
        "SELECT last_activity_at, last_tool_called "
        "FROM dispatches WHERE id = ?",
        ("d-e2e",),
    )
    assert pre is not None
    assert pre["last_activity_at"] is None
    assert pre["last_tool_called"] is None

    async with _running_server(settings, storage):
        url = f"http://{settings.mcp_host}:{settings.mcp_port}/mcp"
        result = await _call_ping(
            url, {"Authorization": f"Bearer {issued.token}"}, "hello"
        )

    # Tool call succeeded.
    assert getattr(result, "isError", False) is False

    # Post-state: the wrapper's _observe_caller call landed.
    post = await storage.fetch_one(
        "SELECT last_activity_at, last_tool_called "
        "FROM dispatches WHERE id = ?",
        ("d-e2e",),
    )
    assert post is not None
    assert post["last_activity_at"] is not None, (
        "ping wrapper failed to update last_activity_at — "
        "_observe_caller is not wired correctly (rename pass at "
        "M5-pm-mode-and-window-state commit 5; the helper formerly "
        "named _record_caller_activity now fans out via _observe_caller)"
    )
    assert post["last_tool_called"] == "ping", (
        f"expected last_tool_called='ping', got {post['last_tool_called']!r} — "
        "the tool_name argument is wrong or the wrapper isn't calling the helper"
    )


async def test_observe_caller_rolls_back_both_writes_on_failure(
    storage: SqliteStorage,
    settings: Settings,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The activity update and the auto-flip both happen inside a
    single ``storage.transaction()`` in ``_observe_caller``. A failure
    in either rolls back the other — partial success would leave
    operator-visible state inconsistent.

    Setup: an oauth-pm token bound to a dispatches row (contrived
    case — Mike's OAuth token is NOT dispatch-bound in production,
    but constructing this case is the cleanest way to exercise both
    fan-out destinations in the same call). The production invariant
    "OAuth-PM tokens are not dispatch-bound" is asserted directly by
    ``tests/unit/bridge/test_oauth_pm_auto_flip.py::test_oauth_pm_tokens_are_not_dispatch_bound_in_production``;
    if that contract test fails in the future, this rollback test
    will silently become non-contrived and its meaning changes —
    revisit both tests together at that point.

    Force a failure on the audit_log INSERT inside
    ``record_oauth_pm_auto_flip``. Assert:

    - dispatches.last_activity_at remains None (record_mcp_call's
      UPDATE was rolled back)
    - pm_mode_log has no rows (record_oauth_pm_auto_flip's INSERT
      was rolled back)
    - audit_log has no pm_mode.auto_flip rows

    Failure mode this test prevents: a future commit removing the
    transactional wrapping in ``_observe_caller`` would let the
    activity update commit while the auto-flip's audit failure
    rolled back only the pm_mode_log INSERT — leaving
    dispatches.last_activity_at updated for an operation that
    didn't actually complete observably.
    """
    token_store = TokenStore()
    issued = await token_store.issue(
        agent_id=None,
        role="pm",
        label="oauth-pm-rollback-test",
        source="oauth",
    )

    # Pre-seed company/project/task/agent FK chain.
    await storage.execute(
        "INSERT INTO agents (id, company_id, role, config_path) "
        "VALUES (?, 'co1', 'pm', '/dev/null')",
        (issued.record.agent_id,),
    )
    # Pre-seed a dispatches row whose token_id matches the issued
    # oauth-pm token. Contrived (Mike isn't dispatch-bound in
    # production) but exercises both fan-out destinations.
    await storage.execute(
        "INSERT INTO dispatches "
        "(id, task_id, role, status, working_directory, "
        "timeout_seconds, token_id) "
        "VALUES ('d-rollback', 'task-1', 'pm-continuous', 'running', "
        "'/tmp/test', 300, ?)",
        (issued.record.token_id,),
    )

    # Pre-state assertions.
    pre_dispatch = await storage.fetch_one(
        "SELECT last_activity_at FROM dispatches WHERE id = ?",
        ("d-rollback",),
    )
    assert pre_dispatch is not None
    assert pre_dispatch["last_activity_at"] is None
    pre_pm_mode = await storage.fetch_all("SELECT id FROM pm_mode_log")
    assert pre_pm_mode == []

    # Force the audit_log INSERT inside record_oauth_pm_auto_flip to
    # fail. Patches the audit logger's storage execute via
    # monkeypatching write_audit at its import site in bridge.activity.
    from meridian_orchestrator.bridge import activity as activity_mod

    real_write_audit = activity_mod.write_audit

    async def failing_write_audit(*args: object, **kwargs: object) -> str:
        action = kwargs.get("action") if "action" in kwargs else (
            args[2] if len(args) > 2 else None
        )
        if action == "pm_mode.auto_flip":
            raise RuntimeError(
                "simulated audit write failure in auto-flip"
            )
        # Fallback for any other audit; should not be called in this
        # test but defensive.
        return await real_write_audit(*args, **kwargs)  # type: ignore[arg-type]

    monkeypatch.setattr(activity_mod, "write_audit", failing_write_audit)

    async with _running_server(settings, storage):
        url = f"http://{settings.mcp_host}:{settings.mcp_port}/mcp"
        # The ping call's _observe_caller will fail on the auto-flip
        # audit; the wrapper itself surfaces a structured error
        # because the failure propagates up through the transaction.
        async with (
            streamablehttp_client(
                url, headers={"Authorization": f"Bearer {issued.token}"}
            ) as (read, write, _),
            ClientSession(read, write) as session,
        ):
            await session.initialize()
            result = await session.call_tool("ping", {"echo": "hello"})

    # The MCP call should surface as an error (the transaction failed).
    assert getattr(result, "isError", False) is True, (
        "expected the MCP call to fail when _observe_caller's "
        "transaction failed; the failure was masked or the helper "
        "isn't propagating exceptions correctly."
    )

    # Post-state: BOTH writes rolled back.
    post_dispatch = await storage.fetch_one(
        "SELECT last_activity_at FROM dispatches WHERE id = ?",
        ("d-rollback",),
    )
    assert post_dispatch is not None
    assert post_dispatch["last_activity_at"] is None, (
        "dispatches.last_activity_at was updated despite the "
        "auto-flip's audit write failure; _observe_caller's "
        "transaction wrapping is broken — partial success leaves "
        "operator-visible state inconsistent (activity updated for "
        "an operation that didn't complete observably)."
    )

    post_pm_mode = await storage.fetch_all(
        "SELECT id FROM pm_mode_log"
    )
    assert post_pm_mode == [], (
        "pm_mode_log row persisted despite the audit write failure; "
        "record_oauth_pm_auto_flip's transaction or the outer "
        "_observe_caller transaction failed to roll back."
    )

    audits = await storage.fetch_all(
        "SELECT id FROM audit_log WHERE action = 'pm_mode.auto_flip'"
    )
    assert audits == []
