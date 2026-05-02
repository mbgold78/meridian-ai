"""End-to-end MCP wrapper tests for ``record_api_call``.

Exercises the ``record_api_call`` MCP wrapper against a real
Streamable-HTTP MCP server. Verifies wiring that direct-handler tests
in ``test_record_api_call.py`` cannot reach:

- Wrapper-resolved ``agent_id`` (from ``access.client_id`` on the live
  request context) lands on the ``api_usage`` row.
- Wrapper-resolved ``token_id`` (from ``_resolve_caller_token_id``
  parsing the live Bearer header) drives the dispatch lookup that
  increments ``dispatches.actual_tokens``.
- Activity writer (``_record_caller_activity``) fires for the
  wrapper, mirror of the pattern in ``test_tool_activity_writer.py``.
- All four agent roles (PM/Engineer/Implementer/Tester) can invoke
  the tool — spec lists no role gate.
- Pydantic validation rejects negative ``input_tokens`` over the
  wire as a structured MCP error, not a 500.
- OAuth-caller path (token_id doesn't match any dispatch row)
  produces the INSERT-only flow through the wrapper.

Direct-handler unit/integration tests live in
``tests/integration/test_record_api_call.py`` and
``tests/unit/tools/test_budget.py``.
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

# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


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
        "INSERT INTO companies (id, name) VALUES ('co1', 'Meridian AI')"
    )
    await s.execute(
        "INSERT INTO projects (id, company_id, name) VALUES "
        "('proj1', 'co1', 'Substrate Product')"
    )
    await s.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, description) "
        "VALUES ('task-1', 'co1', 'proj1', 'implementer', 'no-op')"
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
    """Seed an agents row matching the issued token's agent_id so the
    api_usage.agent_id FK satisfies."""
    await s.execute(
        "INSERT INTO agents (id, company_id, role, config_path) "
        "VALUES (?, 'co1', ?, '/dev/null')",
        (agent_id, role),
    )


async def _seed_dispatch(
    s: SqliteStorage,
    *,
    dispatch_id: str,
    role: str,
    status: str,
    token_id: str,
    actual_tokens: int | None = None,
) -> None:
    await s.execute(
        "INSERT INTO dispatches "
        "(id, task_id, role, status, working_directory, timeout_seconds, "
        "token_id, actual_tokens) "
        "VALUES (?, 'task-1', ?, ?, '/tmp/wt', 300, ?, ?)",
        (dispatch_id, role, status, token_id, actual_tokens),
    )


# ---------------------------------------------------------------------------
# MCP client helpers
# ---------------------------------------------------------------------------


async def _call_record_api_call(
    *,
    base_url: str,
    token: str,
    model: str,
    input_tokens: int,
    output_tokens: int,
    task_id: str | None = None,
) -> object:
    url = f"{base_url}/mcp"
    args: dict[str, object] = {
        "model": model,
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
    }
    if task_id is not None:
        args["task_id"] = task_id
    headers = {"Authorization": f"Bearer {token}"}
    async with (
        streamablehttp_client(url, headers=headers) as (read, write, _),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        return await session.call_tool("record_api_call", args)


def _structured(result: object) -> dict[str, object]:
    structured = getattr(result, "structuredContent", None)
    if structured is not None:
        return dict(structured)
    text = result.content[0].text  # type: ignore[union-attr]
    return dict(json.loads(text))


async def _issue_role_token(
    storage: SqliteStorage, role: str, label: str
) -> IssuedToken:
    """Issue a token for ``role`` and seed the matching agents row.

    The returned IssuedToken's record.agent_id is the auto-generated UUID
    that becomes ``access.client_id`` at the wrapper layer; seeding the
    agents row ensures the api_usage.agent_id FK satisfies."""
    token_store = TokenStore()
    issued = token_store.issue(agent_id=None, role=role, label=label)
    await _seed_agent(storage, issued.record.agent_id, role)
    return issued


# ---------------------------------------------------------------------------
# Test 1: wrapper-resolved agent_id lands on the api_usage row
# ---------------------------------------------------------------------------


async def test_wrapper_resolved_agent_id_lands_on_api_usage_row(
    settings: Settings, storage: SqliteStorage
) -> None:
    """The agent_id resolved from access.client_id at the wrapper layer
    matches what gets written to api_usage.agent_id. Direct-handler
    tests pass caller_agent_id explicitly; this test exercises the
    live request-context resolution path that production uses."""
    issued = await _issue_role_token(storage, "pm", "wrapper-agent-id-test")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage):
        result = await _call_record_api_call(
            base_url=base_url,
            token=issued.token,
            model="claude-sonnet-4-6",
            input_tokens=1000,
            output_tokens=500,
        )

    assert getattr(result, "isError", False) is False, (
        f"record_api_call wrapper returned an error: "
        f"{getattr(result, 'content', None)!r}"
    )
    out = _structured(result)
    usage_id = out["usage_id"]

    row = await storage.fetch_one(
        "SELECT agent_id FROM api_usage WHERE id = ?", (usage_id,)
    )
    assert row is not None
    assert row["agent_id"] == issued.record.agent_id, (
        "api_usage.agent_id does not match the issued token's agent_id; "
        "_resolve_agent_id is not threading access.client_id correctly "
        "from the live request context to the handler's caller_agent_id."
    )


# ---------------------------------------------------------------------------
# Test 2: wrapper-resolved token_id drives dispatch-actual-tokens increment
# ---------------------------------------------------------------------------


async def test_wrapper_resolved_token_id_increments_dispatch_actual_tokens(
    settings: Settings, storage: SqliteStorage
) -> None:
    """The token_id resolved from the live Bearer header at the wrapper
    layer matches what gets passed to the dispatch-lookup query, driving
    the actual_tokens increment. Format-contract round-trip is unit-
    covered in test_server_helpers.py; this asserts the integration
    shape: pre-seed a dispatch with the token's token_id, call over MCP,
    observe the increment."""
    issued = await _issue_role_token(
        storage, "implementer", "wrapper-token-id-test"
    )
    await _seed_dispatch(
        storage,
        dispatch_id="d-mcp-1",
        role="implementer",
        status="running",
        token_id=issued.record.token_id,
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage):
        result = await _call_record_api_call(
            base_url=base_url,
            token=issued.token,
            model="claude-sonnet-4-6",
            input_tokens=2000,
            output_tokens=1000,
        )

    assert getattr(result, "isError", False) is False
    row = await storage.fetch_one(
        "SELECT actual_tokens FROM dispatches WHERE id = 'd-mcp-1'"
    )
    assert row is not None
    assert row["actual_tokens"] == 3000, (
        "dispatches.actual_tokens did not increment by input+output total; "
        "_resolve_caller_token_id parsed the Bearer header into a token_id "
        "that did not match dispatches.token_id, or the wrapper failed to "
        "thread it to record_api_call's caller_token_id parameter."
    )


# ---------------------------------------------------------------------------
# Test 3: _record_caller_activity fires for the record_api_call wrapper
# ---------------------------------------------------------------------------


async def test_record_api_call_wrapper_fires_activity_writer(
    settings: Settings, storage: SqliteStorage
) -> None:
    """Verify the wrapper calls _record_caller_activity per
    project_mcp_tool_wrapper_activity_recording memory: every MCP
    tool wrapper must update last_activity_at and last_tool_called.
    Mirror of test_tool_activity_writer.py's ping-wrapper test, for
    record_api_call.

    Activity writing is independent of actual_tokens incrementing —
    different writers, different columns. Both fire on the same call
    when the dispatch row matches; this test isolates the activity
    side."""
    issued = await _issue_role_token(
        storage, "implementer", "wrapper-activity-test"
    )
    await _seed_dispatch(
        storage,
        dispatch_id="d-mcp-act",
        role="implementer",
        status="running",
        token_id=issued.record.token_id,
    )

    pre = await storage.fetch_one(
        "SELECT last_activity_at, last_tool_called FROM dispatches "
        "WHERE id = 'd-mcp-act'"
    )
    assert pre is not None
    assert pre["last_activity_at"] is None
    assert pre["last_tool_called"] is None

    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"
    async with _running_server(settings, storage):
        result = await _call_record_api_call(
            base_url=base_url,
            token=issued.token,
            model="claude-sonnet-4-6",
            input_tokens=100,
            output_tokens=50,
        )

    assert getattr(result, "isError", False) is False

    post = await storage.fetch_one(
        "SELECT last_activity_at, last_tool_called FROM dispatches "
        "WHERE id = 'd-mcp-act'"
    )
    assert post is not None
    assert post["last_activity_at"] is not None, (
        "record_api_call wrapper failed to update last_activity_at — "
        "_record_caller_activity is not wired into the wrapper. See "
        "project_mcp_tool_wrapper_activity_recording memory: every "
        "MCP tool wrapper must call _record_caller_activity right "
        "after _resolve_agent_id."
    )
    assert post["last_tool_called"] == "record_api_call", (
        f"expected last_tool_called='record_api_call', got "
        f"{post['last_tool_called']!r} — the tool_name argument to "
        "_record_caller_activity is wrong or the wrapper is recording "
        "activity for a different tool."
    )


# ---------------------------------------------------------------------------
# Test 4: all four roles can invoke record_api_call
# ---------------------------------------------------------------------------


async def test_all_four_roles_can_call_record_api_call(
    settings: Settings, storage: SqliteStorage
) -> None:
    """Per 06-mcp-tools-phase-one.md § Permission summary, record_api_call
    is allowed for PM, Engineer, Implementer, Tester (all four agents).
    No role gate exists at handler or wrapper layer; this test asserts
    that absence by exercising each role over the wire and observing a
    successful api_usage row insert.

    Failure mode this test prevents: a future commit adding a defensive
    role check (e.g., "only Implementer/Tester can record" because
    someone confused this with dispatch-only tools) would break PM and
    Engineer's ability to record their own API usage."""
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    role_tokens: dict[str, IssuedToken] = {}
    for role in ("pm", "engineer", "implementer", "tester"):
        role_tokens[role] = await _issue_role_token(
            storage, role, f"all-roles-{role}"
        )

    async with _running_server(settings, storage):
        for role, issued in role_tokens.items():
            result = await _call_record_api_call(
                base_url=base_url,
                token=issued.token,
                model="claude-sonnet-4-6",
                input_tokens=10,
                output_tokens=5,
            )
            assert getattr(result, "isError", False) is False, (
                f"role={role!r} got an error invoking record_api_call; "
                "the spec lists all four roles as permitted, but a role "
                "gate has been added at the wrapper or handler layer."
            )

    # Verify the four api_usage rows landed, one per role.
    rows = await storage.fetch_all(
        "SELECT agent_id FROM api_usage ORDER BY rowid"
    )
    assert len(rows) == 4
    written_agent_ids = {r["agent_id"] for r in rows}
    expected_agent_ids = {t.record.agent_id for t in role_tokens.values()}
    assert written_agent_ids == expected_agent_ids


# ---------------------------------------------------------------------------
# Test 5: negative input_tokens rejected at Pydantic boundary
# ---------------------------------------------------------------------------


async def test_negative_input_tokens_rejected_as_structured_error(
    settings: Settings, storage: SqliteStorage
) -> None:
    """RecordApiCallInput.input_tokens has Field(ge=0); negative values
    must be rejected at the Pydantic validation boundary as a structured
    MCP error, not a 500 server crash. Same for output_tokens.

    This guards the input contract: the wrapper takes int parameters,
    Pydantic enforces ge=0, MCP returns the validation failure
    structurally. A future regression that drops Field(ge=0) or that
    adds a wrapper-layer cast that masks the constraint would surface
    here as the test going green when it should still fail validation."""
    issued = await _issue_role_token(
        storage, "implementer", "validation-error-test"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage):
        result = await _call_record_api_call(
            base_url=base_url,
            token=issued.token,
            model="claude-sonnet-4-6",
            input_tokens=-100,
            output_tokens=50,
        )

    assert getattr(result, "isError", False) is True, (
        "negative input_tokens did not surface as a structured MCP error; "
        "Pydantic Field(ge=0) constraint may have been dropped, or the "
        "wrapper is masking validation errors."
    )

    # No api_usage row should have been written.
    rows = await storage.fetch_all("SELECT id FROM api_usage")
    assert rows == [], (
        "api_usage row was written despite validation failure; the "
        "wrapper invoked the handler before the Pydantic validation "
        "rejected the input."
    )


# ---------------------------------------------------------------------------
# Test 6: OAuth-caller path (token_id matches no dispatch) — INSERT only
# ---------------------------------------------------------------------------


async def test_oauth_caller_no_dispatch_match_inserts_only(
    settings: Settings, storage: SqliteStorage
) -> None:
    """A caller whose token_id doesn't bind to any dispatch row (Mike's
    interactive OAuth PM, or a static admin token) gets the api_usage
    row inserted with no actual_tokens increment. Mirror of the direct-
    handler test test_record_api_call_oauth_caller_no_dispatch_update,
    but exercised over the live wire so the wrapper-resolved token_id
    is the one used in the dispatch lookup.

    Failure mode this test prevents: a wrapper-layer change that
    accidentally bound record_api_call to a dispatch (e.g., requiring
    the caller to be in an active dispatch context) would break PM's
    ability to record OAuth-driven calls."""
    issued = await _issue_role_token(storage, "pm", "oauth-no-dispatch-test")
    # Deliberately no dispatch seeded. The wrapper-resolved token_id
    # will not match any dispatches.token_id; the dispatch lookup
    # returns None and the increment is skipped.

    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"
    async with _running_server(settings, storage):
        result = await _call_record_api_call(
            base_url=base_url,
            token=issued.token,
            model="claude-opus-4-7",
            input_tokens=10_000,
            output_tokens=5000,
        )

    assert getattr(result, "isError", False) is False
    out = _structured(result)
    usage_id = out["usage_id"]

    # api_usage row landed.
    row = await storage.fetch_one(
        "SELECT agent_id FROM api_usage WHERE id = ?", (usage_id,)
    )
    assert row is not None
    assert row["agent_id"] == issued.record.agent_id

    # No dispatches in this scenario — the table is empty.
    dispatches = await storage.fetch_all("SELECT id FROM dispatches")
    assert dispatches == []

    # Audit row landed with target=NULL (per audit-target semantics: no
    # dispatch context to associate the call with).
    audits = await storage.fetch_all(
        "SELECT target FROM audit_log WHERE action = 'api.record_call'"
    )
    assert len(audits) == 1
    assert audits[0]["target"] is None
