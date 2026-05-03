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
- Transaction rollback: an UPDATE failure mid-handler rolls back the
  INSERT api_usage that preceded it, leaving no orphan usage row that
  claims tokens were consumed without the dispatches.actual_tokens
  reflecting it.
- Audit-target-on-no-op-update contract: when the dispatch transitions
  to terminal between SELECT and UPDATE, the UPDATE no-ops via its
  status-NOT-IN-terminal guard, but the audit row still fires with
  target=dispatch_id. Encodes the documented forensic semantics
  ("target=dispatch_id means call landed during this dispatch, not
  increment occurred") as a durable contract.

Cost computation pure-function tests live in
``tests/unit/tools/test_budget.py``.
"""

from __future__ import annotations

import json
from collections.abc import AsyncIterator, Sequence
from pathlib import Path
from typing import Any

import pytest
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
        caller_agent_role="implementer",
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
        caller_agent_role="implementer",
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
            caller_agent_role="tester",
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
        caller_agent_role="pm",
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
        caller_agent_role="implementer",
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
        caller_agent_role="implementer",
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
        caller_agent_role="pm",
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
        caller_agent_role="implementer",
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

    # Audit details serialization contract: cost_usd is None in the audit
    # row's details_json when the model isn't priced. Encodes the audit-
    # consumer contract durably so future audit-format changes don't break
    # M6 get_audit_log readers silently. Mirror of the cost_usd column NULL
    # assertion above — the in-memory dict serializes through json.dumps
    # to JSON null and round-trips back to Python None.
    audits = await storage.fetch_all(
        "SELECT details_json FROM audit_log WHERE action = 'api.record_call'"
    )
    assert len(audits) == 1
    details = json.loads(audits[0]["details_json"])
    assert details["cost_usd"] is None, (
        "audit details serialized cost_usd as something other than None for "
        "an unknown model; downstream get_audit_log consumers (M6) reading "
        "cost_usd will see corrupted shape."
    )


# ---------------------------------------------------------------------------
# Transaction rollback regression
# ---------------------------------------------------------------------------


async def test_record_api_call_rolls_back_insert_on_update_failure(
    storage: SqliteStorage, monkeypatch: pytest.MonkeyPatch
) -> None:
    """An UPDATE failure mid-handler must roll back the INSERT api_usage
    that preceded it; storage.transaction() is the wrapping mechanism.

    Without transaction wrapping, INSERT api_usage succeeds but UPDATE
    dispatches fails; the api_usage row claims the call consumed tokens
    but dispatches.actual_tokens disagrees, silently breaking budget
    reasoning. This test forces the failure mode by monkeypatching
    storage.execute to raise specifically on the UPDATE call, then
    asserts both the INSERT and the audit_log write were rolled back to
    a clean pre-call state.

    Same anti-drift discipline as the env-sanitization wiring tests in
    test_launcher_env_sanitization.py — a future commit that drops the
    `async with storage.transaction():` wrapper from record_api_call
    fails this test informatively.
    """
    token_store = TokenStore(service="record-api-call-rollback")
    issued = token_store.issue("agent-7", "implementer", "rollback-test")
    await _seed_dispatch(
        storage,
        dispatch_id="d-rollback",
        role="implementer",
        status="running",
        token_id=issued.record.token_id,
        actual_tokens=42,
    )

    real_execute = storage.execute

    async def failing_execute(
        query: str, params: Sequence[Any] | None = None
    ) -> None:
        if query.lstrip().upper().startswith("UPDATE DISPATCHES"):
            raise RuntimeError(
                "simulated UPDATE failure for rollback regression test"
            )
        await real_execute(query, params)

    monkeypatch.setattr(storage, "execute", failing_execute)

    with pytest.raises(RuntimeError, match="simulated UPDATE failure"):
        await record_api_call(
            storage=storage,
            request=RecordApiCallInput(
                model="claude-sonnet-4-6",
                input_tokens=1000,
                output_tokens=500,
            ),
            caller_agent_id="agent-7",
            caller_agent_role="implementer",
            caller_token_id=issued.record.token_id,
        )

    # api_usage row must NOT exist — the INSERT was rolled back when the
    # UPDATE failed inside the same transaction.
    rows = await storage.fetch_all("SELECT id FROM api_usage")
    assert rows == [], (
        "api_usage row persisted despite UPDATE failure; the "
        "storage.transaction() wrapper around INSERT+UPDATE+audit in "
        "record_api_call has been removed or is not rolling back. "
        "Without rollback: api_usage claims tokens were consumed while "
        "dispatches.actual_tokens disagrees, silently breaking budget "
        "reasoning."
    )

    # audit_log row must NOT exist either — the audit write was inside
    # the same transaction.
    audits = await storage.fetch_all(
        "SELECT id FROM audit_log WHERE action = 'api.record_call'"
    )
    assert audits == [], (
        "audit_log row persisted despite UPDATE failure; the audit write "
        "is outside the transaction or the rollback isn't reaching it."
    )

    # dispatches.actual_tokens must remain at the pre-call value of 42 —
    # the UPDATE never landed, the rollback didn't accidentally clobber
    # the row.
    dispatch_row = await storage.fetch_one(
        "SELECT actual_tokens FROM dispatches WHERE id = 'd-rollback'"
    )
    assert dispatch_row is not None
    assert dispatch_row["actual_tokens"] == 42, (
        f"dispatches.actual_tokens is {dispatch_row['actual_tokens']!r}, "
        "expected 42 (the pre-call value). The UPDATE either landed "
        "before the simulated failure or the rollback affected the row."
    )


# ---------------------------------------------------------------------------
# Audit-target-on-no-op-update contract
# ---------------------------------------------------------------------------


async def test_audit_target_dispatch_id_emitted_for_no_op_update(
    storage: SqliteStorage, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Race-window contract: when the dispatch transitions to terminal
    between SELECT and UPDATE, the UPDATE no-ops via its
    status-NOT-IN-terminal guard, but the audit row still fires with
    target=dispatch_id (the dispatch_id resolved at SELECT time).

    Forces the race by monkeypatching ``storage.fetch_one`` to fire a
    status-transition UPDATE as a side effect right before returning
    the dispatch row. SQLite sees the side-effect inside the active
    transaction, so the subsequent UPDATE in record_api_call observes
    status='completed' and no-ops. The audit then emits with the
    dispatch_id captured at SELECT time.

    Failure message: target=dispatch_id means "call landed during this
    dispatch", not "increment occurred". Documentation alone is drift-
    prone for a contract with alternative-interpretation risk; this
    test makes the contract durably enforced.
    """
    token_store = TokenStore(service="record-api-call-norace")
    issued = token_store.issue("agent-7", "implementer", "no-op-update-test")
    await _seed_dispatch(
        storage,
        dispatch_id="d-norace",
        role="implementer",
        status="running",  # non-terminal at SELECT time
        token_id=issued.record.token_id,
        actual_tokens=100,
    )

    real_fetch_one = storage.fetch_one
    real_execute = storage.execute

    async def transitioning_fetch_one(
        query: str, params: Sequence[Any] | None = None
    ) -> dict[str, Any] | None:
        result = await real_fetch_one(query, params)
        # Identify the dispatch-resolution SELECT specifically (not the
        # post-call assertion fetches in this test). The handler's query
        # selects from dispatches with a token_id filter.
        if (
            "FROM dispatches" in query
            and "token_id" in query
            and result is not None
        ):
            # Race-window simulation: transition the dispatch to terminal
            # after the SELECT but before the UPDATE. The write lands in
            # the same transaction record_api_call opened, so the
            # subsequent UPDATE sees status='completed' and no-ops via
            # its status-NOT-IN guard.
            await real_execute(
                "UPDATE dispatches SET status = 'completed' WHERE id = ?",
                (result["id"],),
            )
        return result

    monkeypatch.setattr(storage, "fetch_one", transitioning_fetch_one)

    out = await record_api_call(
        storage=storage,
        request=RecordApiCallInput(
            model="claude-sonnet-4-6",
            input_tokens=500,
            output_tokens=250,
        ),
        caller_agent_id="agent-7",
        caller_agent_role="implementer",
        caller_token_id=issued.record.token_id,
    )

    # Restore for post-call assertion fetches.
    monkeypatch.setattr(storage, "fetch_one", real_fetch_one)

    # api_usage row landed.
    usage_row = await storage.fetch_one(
        "SELECT id FROM api_usage WHERE id = ?", (out.usage_id,)
    )
    assert usage_row is not None

    # actual_tokens unchanged — UPDATE no-opped via the status guard.
    dispatch_row = await storage.fetch_one(
        "SELECT actual_tokens, status FROM dispatches WHERE id = 'd-norace'"
    )
    assert dispatch_row is not None
    assert dispatch_row["status"] == "completed", (
        "race-window simulation failed: dispatch did not transition to "
        "terminal between SELECT and UPDATE. The test's monkeypatch is "
        "not firing the side-effect UPDATE."
    )
    assert dispatch_row["actual_tokens"] == 100, (
        f"actual_tokens={dispatch_row['actual_tokens']!r}, expected 100 "
        "(pre-call value). The UPDATE in record_api_call landed despite "
        "the dispatch transitioning to terminal — the status-NOT-IN "
        "guard on the UPDATE has been removed or weakened."
    )

    # Audit row exists with target=<dispatch_id> even though the
    # increment no-opped — this is the contract under test.
    audits = await storage.fetch_all(
        "SELECT target, details_json FROM audit_log "
        "WHERE action = 'api.record_call'"
    )
    assert len(audits) == 1, (
        f"expected exactly one api.record_call audit, got {len(audits)}; "
        "transaction may not have committed cleanly."
    )
    assert audits[0]["target"] == "d-norace", (
        f"audit target is {audits[0]['target']!r}, expected 'd-norace'. "
        "Contract violated: target=dispatch_id means 'call landed during "
        "this dispatch', not 'increment occurred'. A future commit may "
        "have changed the audit emit site to skip target when the UPDATE "
        "no-ops, breaking the documented forensic semantics. Documentation "
        "alone is drift-prone for a contract with alternative-interpretation "
        "risk; this test makes the contract durably enforced."
    )
    details = json.loads(audits[0]["details_json"])
    assert details["dispatch_id"] == "d-norace", (
        "audit details_json.dispatch_id should match target; both are "
        "captured from the SELECT-time resolution."
    )


# ---------------------------------------------------------------------------
# Observability tier-1 columns (M5-subscription-routing commit 1)
# ---------------------------------------------------------------------------


async def test_record_api_call_auto_populates_agent_role(
    storage: SqliteStorage,
) -> None:
    """``agent_role`` is auto-populated server-side from the wrapper-
    resolved caller role. Every api_usage row has the cohort dimension
    from day 1 — no NULL agent_role rows accumulate, no agent burden
    on the writer side.
    """
    out = await record_api_call(
        storage=storage,
        request=RecordApiCallInput(
            model="claude-sonnet-4-6", input_tokens=100, output_tokens=50
        ),
        caller_agent_id="agent-1",
        caller_agent_role="engineer",
        caller_token_id="some-engineer-token",
    )
    row = await storage.fetch_one(
        "SELECT agent_role FROM api_usage WHERE id = ?", (out.usage_id,)
    )
    assert row is not None
    assert row["agent_role"] == "engineer", (
        f"agent_role={row['agent_role']!r}, expected 'engineer'. "
        "agent_role auto-population from caller_agent_role parameter "
        "regressed; query-side cohort aggregation by role will see "
        "NULL or stale values."
    )


async def test_record_api_call_observability_columns_default_null(
    storage: SqliteStorage,
) -> None:
    """When the caller doesn't supply ``notes`` / ``workflow_id`` /
    ``task_type``, those columns persist as NULL. Optional fields stay
    optional — no implicit defaulting that would hide missing-tag rows
    at query time.
    """
    out = await record_api_call(
        storage=storage,
        request=RecordApiCallInput(
            model="claude-sonnet-4-6", input_tokens=100, output_tokens=50
        ),
        caller_agent_id="agent-1",
        caller_agent_role="implementer",
        caller_token_id="some-token",
    )
    row = await storage.fetch_one(
        "SELECT notes, workflow_id, task_type FROM api_usage WHERE id = ?",
        (out.usage_id,),
    )
    assert row is not None
    assert row["notes"] is None
    assert row["workflow_id"] is None
    assert row["task_type"] is None

    # Audit details_json round-trip for the NULL-default case: all four
    # observability keys appear in the audit dict regardless of whether
    # the caller supplied them. notes/workflow_id/task_type serialize as
    # JSON null and round-trip to Python None; agent_role is always
    # populated from caller_agent_role (wrapper-resolved). Without this
    # assertion, a future refactor that conditionally omits the keys
    # would leave M6 get_audit_log readers seeing an inconsistent shape
    # depending on whether the caller tagged the call or not.
    audits = await storage.fetch_all(
        "SELECT details_json FROM audit_log WHERE action = 'api.record_call'"
    )
    assert len(audits) == 1
    details = json.loads(audits[0]["details_json"])
    assert details["notes"] is None
    assert details["workflow_id"] is None
    assert details["task_type"] is None
    assert details["agent_role"] == "implementer"


async def test_record_api_call_writes_observability_columns_when_provided(
    storage: SqliteStorage,
) -> None:
    """All three optional cohort fields round-trip from
    RecordApiCallInput to the api_usage row when the caller supplies
    them. Verifies the wiring through the INSERT statement; absence of
    this test would let a future commit drop one of the columns from
    the VALUES list silently.
    """
    out = await record_api_call(
        storage=storage,
        request=RecordApiCallInput(
            model="claude-sonnet-4-6",
            input_tokens=100,
            output_tokens=50,
            notes="exploring substrate sub-task decomposition",
            workflow_id="wf-substrate-pivot-2026-05",
            task_type="planning",
        ),
        caller_agent_id="agent-2",
        caller_agent_role="pm",
        caller_token_id="some-pm-token",
    )
    row = await storage.fetch_one(
        "SELECT notes, workflow_id, agent_role, task_type "
        "FROM api_usage WHERE id = ?",
        (out.usage_id,),
    )
    assert row is not None
    assert row["notes"] == "exploring substrate sub-task decomposition"
    assert row["workflow_id"] == "wf-substrate-pivot-2026-05"
    assert row["agent_role"] == "pm"
    assert row["task_type"] == "planning"


async def test_record_api_call_audit_details_include_observability_fields(
    storage: SqliteStorage,
) -> None:
    """audit_log details_json carries the four observability columns so
    M6 ``get_audit_log`` consumers (and the post-hoc cost-interrogation
    queries described in BACKLOG § Observability roadmap) can join
    audit history with cohort context. Without this assertion, a future
    refactor that drops the fields from the audit dict would silently
    break that downstream join.
    """
    out = await record_api_call(
        storage=storage,
        request=RecordApiCallInput(
            model="claude-opus-4-7",
            input_tokens=200,
            output_tokens=80,
            notes="audit-test annotation",
            workflow_id="wf-audit-test",
            task_type="research",
        ),
        caller_agent_id="agent-3",
        caller_agent_role="engineer",
        caller_token_id="some-engineer-token",
    )
    audits = await storage.fetch_all(
        "SELECT details_json FROM audit_log WHERE action = 'api.record_call'"
    )
    assert len(audits) == 1
    details = json.loads(audits[0]["details_json"])
    assert details["usage_id"] == out.usage_id
    assert details["notes"] == "audit-test annotation"
    assert details["workflow_id"] == "wf-audit-test"
    assert details["agent_role"] == "engineer"
    assert details["task_type"] == "research"
