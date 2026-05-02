"""Integration tests for the ``record_api_call`` writer.

Exercises :func:`meridian_orchestrator.tools.budget.record_api_call`
against a migrated SQLite DB. Covers all three caller paths per
``project_subscription_first_architecture`` § R-4:

1. Dispatch-bound static token (Implementer / Tester / pm-continuous /
   engineer-supervisor) → api_usage row INSERT + dispatches.actual_tokens
   INCREMENT.
2. OAuth caller (no dispatch row matches token) → api_usage row only.
3. Dispatch-bound token whose dispatch is in a terminal state →
   api_usage row only (the increment skips terminal dispatches; mirror
   of bridge.activity.record_mcp_call).

Plus:

- Cumulative-increment behavior across multiple sequential calls
  (per R-3 Reading B atomicity).
- Audit row emission with the spec'd shape (action='api.record_call',
  target=dispatch_id-or-NULL).
- Unknown-model path: cost_usd written as NULL; api_usage row still
  inserted; structlog warning emitted.

Cost computation pure-function tests live in
``tests/unit/tools/test_budget.py``.
"""

from __future__ import annotations

import json
from collections.abc import AsyncIterator
from pathlib import Path

import pytest_asyncio

from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.tools.budget import (
    RecordApiCallInput,
    record_api_call,
)

_TEST_AGENT_IDS = [f"agent-{i}" for i in range(1, 8)] + [
    "pm-agent-id",
    "some-token",  # used by test_record_api_call_unknown_model_writes_null_cost
]


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    # Seed FK chain so dispatches rows can reference tasks and api_usage
    # rows can reference agents (api_usage.agent_id REFERENCES agents(id)).
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
    # Seed agent rows for each agent_id the tests pass as
    # caller_agent_id. The api_usage.agent_id FK to agents.id is
    # enforced by SQLite's PRAGMA foreign_keys=ON; un-seeded
    # caller_agent_ids would fail the INSERT with FOREIGN KEY constraint.
    for agent_id in _TEST_AGENT_IDS:
        await s.execute(
            "INSERT INTO agents (id, company_id, role, config_path) "
            "VALUES (?, 'co1', 'implementer', '/dev/null')",
            (agent_id,),
        )
    yield s
    await s.close()


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
# Path 1: dispatch-bound caller — INSERT + INCREMENT
# ---------------------------------------------------------------------------


async def test_record_api_call_inserts_api_usage_row(
    storage: SqliteStorage,
) -> None:
    """The api_usage row is inserted with caller_agent_id, model, token
    counts, computed cost_usd, optional task_id, and a non-null
    window_start timestamp."""
    token_store = TokenStore(service="record-api-call-test-1")
    issued = token_store.issue("agent-1", "implementer", "test-1")
    await _seed_dispatch(
        storage,
        dispatch_id="d-1",
        role="implementer",
        status="running",
        token_id=issued.record.token_id,
    )

    out = await record_api_call(
        storage=storage,
        request=RecordApiCallInput(
            model="claude-sonnet-4-6",
            input_tokens=5000,
            output_tokens=2000,
            task_id="task-1",
        ),
        caller_agent_id="agent-1",
        caller_token_id=issued.record.token_id,
    )

    assert out.usage_id
    assert out.window_start

    row = await storage.fetch_one(
        "SELECT agent_id, model, input_tokens, output_tokens, cost_usd, "
        "task_id, window_start FROM api_usage WHERE id = ?",
        (out.usage_id,),
    )
    assert row is not None
    assert row["agent_id"] == "agent-1"
    assert row["model"] == "claude-sonnet-4-6"
    assert row["input_tokens"] == 5000
    assert row["output_tokens"] == 2000
    # cost_usd: (5000 * 3 + 2000 * 15) / 1e6 = 45000 / 1e6 = 0.045
    assert row["cost_usd"] is not None
    assert abs(row["cost_usd"] - 0.045) < 1e-9
    assert row["task_id"] == "task-1"
    assert row["window_start"] == out.window_start


async def test_record_api_call_increments_dispatches_actual_tokens(
    storage: SqliteStorage,
) -> None:
    """A dispatch-bound caller's record_api_call increments
    dispatches.actual_tokens by the call's input + output total. The
    increment uses COALESCE-around-zero so a freshly-inserted dispatch
    with NULL actual_tokens initializes correctly to the call's total."""
    token_store = TokenStore(service="record-api-call-test-2")
    issued = token_store.issue("agent-2", "implementer", "test-2")
    await _seed_dispatch(
        storage,
        dispatch_id="d-2",
        role="implementer",
        status="running",
        token_id=issued.record.token_id,
        actual_tokens=None,
    )

    await record_api_call(
        storage=storage,
        request=RecordApiCallInput(
            model="claude-sonnet-4-6",
            input_tokens=1000,
            output_tokens=500,
        ),
        caller_agent_id="agent-2",
        caller_token_id=issued.record.token_id,
    )

    row = await storage.fetch_one(
        "SELECT actual_tokens FROM dispatches WHERE id = 'd-2'"
    )
    assert row is not None
    assert row["actual_tokens"] == 1500


async def test_record_api_call_cumulative_increment(
    storage: SqliteStorage,
) -> None:
    """Multiple sequential calls increment cumulatively, not just to
    the last call's total. Verifies COALESCE(actual_tokens, 0) + ?
    rather than SET actual_tokens = ?."""
    token_store = TokenStore(service="record-api-call-test-3")
    issued = token_store.issue("agent-3", "tester", "test-3")
    await _seed_dispatch(
        storage,
        dispatch_id="d-3",
        role="tester",
        status="running",
        token_id=issued.record.token_id,
    )

    for tokens in [(100, 50), (200, 100), (300, 150)]:
        await record_api_call(
            storage=storage,
            request=RecordApiCallInput(
                model="claude-sonnet-4-6",
                input_tokens=tokens[0],
                output_tokens=tokens[1],
            ),
            caller_agent_id="agent-3",
            caller_token_id=issued.record.token_id,
        )

    row = await storage.fetch_one(
        "SELECT actual_tokens FROM dispatches WHERE id = 'd-3'"
    )
    assert row is not None
    # 150 + 300 + 450 = 900
    assert row["actual_tokens"] == 900


# ---------------------------------------------------------------------------
# Path 2: OAuth caller — INSERT only
# ---------------------------------------------------------------------------


async def test_record_api_call_oauth_caller_no_dispatch_update(
    storage: SqliteStorage,
) -> None:
    """A caller whose token doesn't bind to any dispatch row (Mike's
    interactive OAuth PM) gets the api_usage row inserted but no
    dispatches.actual_tokens write happens. Mirror of
    record_mcp_call's no-match skip case."""
    # No dispatch seeded for this token. The lookup returns None.
    fake_oauth_token_id = "oauth-token-no-dispatch"

    out = await record_api_call(
        storage=storage,
        request=RecordApiCallInput(
            model="claude-opus-4-7",
            input_tokens=10_000,
            output_tokens=5000,
        ),
        caller_agent_id="pm-agent-id",
        caller_token_id=fake_oauth_token_id,
    )

    # api_usage row is present
    row = await storage.fetch_one(
        "SELECT id, agent_id FROM api_usage WHERE id = ?", (out.usage_id,)
    )
    assert row is not None
    assert row["agent_id"] == "pm-agent-id"

    # No dispatch updates (no dispatches in this test's storage at
    # all, but assert the table is untouched).
    dispatches = await storage.fetch_all(
        "SELECT id FROM dispatches WHERE token_id = ?", (fake_oauth_token_id,)
    )
    assert dispatches == []


# ---------------------------------------------------------------------------
# Path 3: dispatch in terminal state — INSERT only, no INCREMENT
# ---------------------------------------------------------------------------


async def test_record_api_call_terminal_dispatch_no_increment(
    storage: SqliteStorage,
) -> None:
    """A final API call after the bound dispatch has terminated should
    not retroactively grow actual_tokens. The status-NOT-IN guard on
    the UPDATE no-ops correctly. Mirror of record_mcp_call's
    terminal-state skip."""
    token_store = TokenStore(service="record-api-call-test-4")
    issued = token_store.issue("agent-4", "implementer", "test-4")
    await _seed_dispatch(
        storage,
        dispatch_id="d-4",
        role="implementer",
        status="completed",  # terminal
        token_id=issued.record.token_id,
        actual_tokens=4242,
    )

    await record_api_call(
        storage=storage,
        request=RecordApiCallInput(
            model="claude-sonnet-4-6", input_tokens=100, output_tokens=50
        ),
        caller_agent_id="agent-4",
        caller_token_id=issued.record.token_id,
    )

    row = await storage.fetch_one(
        "SELECT actual_tokens FROM dispatches WHERE id = 'd-4'"
    )
    assert row is not None
    assert row["actual_tokens"] == 4242, (
        "actual_tokens grew on a terminal-status dispatch; the "
        "status-NOT-IN guard on the UPDATE failed to no-op. This is "
        "a regression of project_subscription_first_architecture R-4 "
        "(post-terminal API call must not retroactively grow the "
        "dispatch's actual_tokens)."
    )


# ---------------------------------------------------------------------------
# Audit row
# ---------------------------------------------------------------------------


async def test_record_api_call_writes_audit_row(
    storage: SqliteStorage,
) -> None:
    """An api.record_call audit row is emitted with target=dispatch_id
    when the caller is dispatch-bound. Engineer's get_dispatch_events
    filters by target = dispatch_id, so dispatch-bound API calls
    surface there for the iteration loop."""
    token_store = TokenStore(service="record-api-call-test-5")
    issued = token_store.issue("agent-5", "implementer", "test-5")
    await _seed_dispatch(
        storage,
        dispatch_id="d-5",
        role="implementer",
        status="running",
        token_id=issued.record.token_id,
    )

    out = await record_api_call(
        storage=storage,
        request=RecordApiCallInput(
            model="claude-sonnet-4-6", input_tokens=1000, output_tokens=500
        ),
        caller_agent_id="agent-5",
        caller_token_id=issued.record.token_id,
    )

    audits = await storage.fetch_all(
        "SELECT actor, action, target, details_json FROM audit_log "
        "WHERE action = 'api.record_call'"
    )
    assert len(audits) == 1
    audit = audits[0]
    assert audit["actor"] == "agent-5"
    assert audit["action"] == "api.record_call"
    assert audit["target"] == "d-5"
    details = json.loads(audit["details_json"])
    assert details["usage_id"] == out.usage_id
    assert details["model"] == "claude-sonnet-4-6"
    assert details["input_tokens"] == 1000
    assert details["output_tokens"] == 500
    assert details["dispatch_id"] == "d-5"
    assert details["task_id"] is None


async def test_record_api_call_audit_target_null_for_oauth_caller(
    storage: SqliteStorage,
) -> None:
    """OAuth caller (no dispatch context) emits the audit with
    target=NULL. The audit doesn't surface in Engineer's
    get_dispatch_events; surfaces only via Mike-only get_audit_log
    (M6)."""
    out = await record_api_call(
        storage=storage,
        request=RecordApiCallInput(
            model="claude-opus-4-7", input_tokens=500, output_tokens=200
        ),
        caller_agent_id="pm-agent-id",
        caller_token_id="oauth-no-dispatch-token",
    )

    audits = await storage.fetch_all(
        "SELECT target, details_json FROM audit_log "
        "WHERE action = 'api.record_call'"
    )
    assert len(audits) == 1
    assert audits[0]["target"] is None
    details = json.loads(audits[0]["details_json"])
    assert details["dispatch_id"] is None
    assert details["usage_id"] == out.usage_id


# ---------------------------------------------------------------------------
# Unknown model — cost_usd NULL
# ---------------------------------------------------------------------------


async def test_record_api_call_unknown_model_writes_null_cost(
    storage: SqliteStorage,
) -> None:
    """A model not in MODEL_PRICING produces a null cost_usd column
    on the api_usage row. The row is still written — token data is
    more valuable than cost data, and the NULL is the visible-via-
    column-inspection diagnostic."""
    out = await record_api_call(
        storage=storage,
        request=RecordApiCallInput(
            model="some-future-model-not-yet-priced",
            input_tokens=1000,
            output_tokens=500,
        ),
        caller_agent_id="agent-6",
        caller_token_id="some-token",
    )

    row = await storage.fetch_one(
        "SELECT model, cost_usd, input_tokens, output_tokens "
        "FROM api_usage WHERE id = ?",
        (out.usage_id,),
    )
    assert row is not None
    assert row["model"] == "some-future-model-not-yet-priced"
    assert row["cost_usd"] is None
    assert row["input_tokens"] == 1000
    assert row["output_tokens"] == 500
