"""Integration test for concurrent transaction safety under real MCP
handler load.

Reading C+ sentinel for M5-flag-flips F2 contextvars fix (2026-05-07):
the storage-core tests in
``tests/unit/storage/test_concurrent_transactions.py`` exercise the fix
at the SqliteStorage layer; this test exercises the fix at the
integration layer by running real MCP handler paths concurrently.

``record_api_call`` is the canonical multi-write transactional handler
(INSERT api_usage + conditional UPDATE dispatches.actual_tokens, both
inside a single ``storage.transaction()``). Running multiple concurrent
``record_api_call`` invocations validates that the F2 fix preserves
cross-handler isolation under real wrapper load — not just sentinel
storage-core writes.

Validates fix at integration level, not just storage core.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from pathlib import Path

import pytest_asyncio

from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.tools.budget import (
    RecordApiCallInput,
    record_api_call,
)


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    # Seed FK chain for api_usage rows. The OAuth-caller path
    # (caller_token_id with no matching dispatch) only requires
    # company + agent rows; no task or dispatch seeding needed.
    await s.execute(
        "INSERT INTO companies (id, name) VALUES ('co1', 'Meridian AI')"
    )
    for i in range(8):
        await s.execute(
            "INSERT INTO agents (id, company_id, role, config_path) "
            "VALUES (?, 'co1', 'implementer', '/dev/null')",
            (f"agent-{i}",),
        )
    yield s
    await s.close()


async def test_concurrent_transactions_under_real_handler_load(
    storage: SqliteStorage,
) -> None:
    """8 concurrent ``record_api_call`` invocations land 8 ``api_usage``
    rows cleanly without cross-contamination.

    Each call is its own transaction. With the F2 fix, calls serialize
    via ``_txn_lock`` and each lands its own row. Without the fix (M1
    shared boolean), concurrent calls would either trigger SQLite
    "cannot start transaction" errors OR silently leak writes across
    each other's envelopes.

    Caller token IDs (``token-{i}``) don't match any seeded dispatch,
    so this exercises the OAuth-caller path (INSERT api_usage only;
    the dispatch UPDATE no-ops). Sufficient sentinel for the F2 fix
    at handler layer; multi-handler concurrent (claim_task +
    update_task_status + record_api_call) is more elaborate fixturing
    for marginal coverage gain — same property, more handlers.

    Sentinel: row count == 8 AND no exceptions. Either failure
    indicates F2 fix did not hold at integration level.
    """

    async def call(i: int) -> None:
        await record_api_call(
            storage=storage,
            request=RecordApiCallInput(
                model="claude-sonnet-4-6",
                input_tokens=100 * (i + 1),
                output_tokens=50 * (i + 1),
            ),
            caller_agent_id=f"agent-{i}",
            caller_agent_role="implementer",
            caller_token_id=f"token-{i}",
        )

    await asyncio.gather(*[call(i) for i in range(8)])

    rows = await storage.fetch_all("SELECT * FROM api_usage")
    assert len(rows) == 8, f"Expected 8 api_usage rows, got {len(rows)}"
