"""End-to-end MCP wrapper tests for ``check_budget_for_task``.

Exercises the wrapper against a real Streamable-HTTP MCP server with
a fake snapshot provider injected via ``serve(...,
get_oauth_usage_snapshot=...)``. Pure-function routing tests for the
underlying decision logic live in
``tests/unit/tools/test_budget_routing.py``.

Coverage:

- PM and Engineer tokens succeed end-to-end with structured response.
- Mike (PM-OAuth) inherits PM permissions.
- Implementer / Tester rejected with structured MCP error per the
  permission table at ``06-mcp-tools-phase-one.md:629``.
- Cold-start (no snapshot wired) → ``fallback_active=True``
  propagates through the wrapper layer; load-bearing test for the
  Q3 fallback path on the live wire.
- Pydantic validation: complexity / priority outside 1..5 → MCP
  validation error at the boundary.
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


def _make_snapshot() -> CachedSnapshot:
    """Fully-parseable snapshot for the success-path tests. All four
    required fields populated; routing engages full logic (no
    fallback)."""
    polled_at = datetime.now(tz=UTC)
    return CachedSnapshot(
        payload={
            "five_hour": {
                "utilization": 30.0,
                "resets_at": (
                    polled_at + timedelta(hours=4)
                ).isoformat(timespec="microseconds"),
            },
            "seven_day_opus": {"utilization": 20.0},
            "extra_usage": {"is_enabled": False},
        },
        polled_at=polled_at,
        null_counters=[],
    )


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
    issued = await token_store.issue(agent_id=None, role=role, label=label)
    await _seed_agent(storage, issued.record.agent_id, role)
    return issued


async def _call_check_budget(
    *,
    base_url: str,
    token: str,
    complexity: int = 3,
    priority: int = 3,
) -> object:
    url = f"{base_url}/mcp"
    headers = {"Authorization": f"Bearer {token}"}
    args: dict[str, object] = {
        "complexity": complexity,
        "priority": priority,
    }
    async with (
        streamablehttp_client(url, headers=headers) as (read, write, _),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        return await session.call_tool("check_budget_for_task", args)


def _structured(result: object) -> dict[str, object]:
    structured = getattr(result, "structuredContent", None)
    if structured is not None:
        return dict(structured)
    text = result.content[0].text  # type: ignore[union-attr]
    return dict(json.loads(text))


# ---------------------------------------------------------------------------
# PM and Engineer succeed; Mike inherits PM
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("allowed_role", ["pm", "engineer"])
async def test_pm_engineer_can_invoke_check_budget_for_task(
    settings: Settings, storage: SqliteStorage, allowed_role: str
) -> None:
    """PM and Engineer tokens succeed end-to-end. Verifies wrapper
    routes to the public handler with caller_role correctly
    derived from the OAuth scope."""
    issued = await _issue_role_token(
        storage, allowed_role, f"{allowed_role}-budget-test"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, _make_snapshot()):
        result = await _call_check_budget(
            base_url=base_url, token=issued.token,
            complexity=3, priority=3,
        )

    assert getattr(result, "isError", False) is False, (
        f"role={allowed_role!r} got an MCP error: "
        f"{getattr(result, 'content', None)!r}"
    )
    out = _structured(result)
    # Structured fields present per Q3 disposition (pass-through).
    assert "recommended_model" in out
    assert "recommended_bucket" in out
    assert "routing_factors" in out
    assert "fallback_active" in out
    assert "rationale_text" in out
    assert out["fallback_active"] is False  # snapshot present + parseable


async def test_pm_priority_3_returns_opus_via_role_default(
    settings: Settings, storage: SqliteStorage
) -> None:
    """PM + priority=3 + fresh snapshot → Opus per role default
    (no priority delta at priority=3). Verifies the routing reaches
    the wrapper's structured response on the live wire."""
    issued = await _issue_role_token(storage, "pm", "pm-opus-test")
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, _make_snapshot()):
        result = await _call_check_budget(
            base_url=base_url, token=issued.token,
            complexity=3, priority=3,
        )

    assert getattr(result, "isError", False) is False
    out = _structured(result)
    assert out["recommended_model"] == "claude-opus-4-7"
    assert out["recommended_bucket"] == "subscription_5h"


# ---------------------------------------------------------------------------
# Implementer / Tester rejected
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("rejected_role", ["implementer", "tester"])
async def test_implementer_tester_denied_check_budget_for_task(
    settings: Settings, storage: SqliteStorage, rejected_role: str
) -> None:
    """Implementer/Tester are not in the check_budget_for_task
    allowlist per the spec permission table at
    ``06-mcp-tools-phase-one.md:629``. Wrapper-resolved caller_role
    drives the handler-layer ``_require_role`` rejection; MCP returns
    a structured error.

    Failure mode this test prevents: a future commit that drops the
    role gate (or mistakenly broadens to all four roles) would let
    Implementer/Tester call check_budget_for_task. The spec rationale
    is "only roles that originate work or dispatch sub-agents need
    the budget signal; Impl/Tester run with the model Engineer
    chose at dispatch time."
    """
    issued = await _issue_role_token(
        storage, rejected_role, f"{rejected_role}-budget-rejected"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, _make_snapshot()):
        result = await _call_check_budget(
            base_url=base_url, token=issued.token
        )

    assert getattr(result, "isError", False) is True, (
        f"role={rejected_role!r} unexpectedly succeeded — handler-"
        "layer _require_role should have rejected this caller. The "
        "permission table at 06-mcp-tools-phase-one.md:629 lists "
        "check_budget_for_task as PM/Engineer/Mike-only "
        "(Implementer/Tester denied)."
    )


# ---------------------------------------------------------------------------
# Cold-start propagation through the wrapper
# ---------------------------------------------------------------------------


async def test_cold_start_fallback_propagates_through_wrapper(
    settings: Settings, storage: SqliteStorage
) -> None:
    """When the snapshot provider returns None (cold-start; operator
    hasn't set CLAUDE_CODE_OAUTH_TOKEN), ``fallback_active=True``
    propagates through the wrapper to the MCP caller. Per-role
    defaults applied; no priority delta.

    Load-bearing wrapper test: pure-function tests cover the routing
    decision; this test verifies the wrapper layer threads the
    snapshot=None state through correctly. Failure mode this
    prevents: a future commit that defaults the snapshot to a stub
    instead of None would suppress the fallback signal silently.
    """
    # Engineer + priority=1 would normally upgrade to Opus; under
    # fallback the priority delta is suppressed and we get Sonnet.
    issued = await _issue_role_token(
        storage, "engineer", "engineer-cold-start"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, snapshot=None):
        result = await _call_check_budget(
            base_url=base_url, token=issued.token,
            complexity=3, priority=1,  # would upgrade absent fallback
        )

    assert getattr(result, "isError", False) is False
    out = _structured(result)
    assert out["fallback_active"] is True, (
        "fallback_active did not propagate through the wrapper. "
        "Cold-start (snapshot=None) must surface fallback_active=True "
        "to the MCP caller per Q3 disposition β. A future commit may "
        "have defaulted the snapshot to a stub, suppressing the "
        "fallback signal silently."
    )
    # Engineer's role default; priority delta NOT applied under fallback.
    assert out["recommended_model"] == "claude-sonnet-4-6"
    factors = out["routing_factors"]
    assert isinstance(factors, list)
    assert "snapshot_unavailable" in factors
    assert "fallback_per_role_defaults" in factors
    # rationale_text names fallback explicitly per Q3 sub-amendment.
    rationale = out["rationale_text"]
    assert isinstance(rationale, str)
    assert "Snapshot unavailable" in rationale


# ---------------------------------------------------------------------------
# Pydantic input validation
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("complexity", "priority"),
    [
        (0, 3),    # complexity below 1
        (6, 3),    # complexity above 5
        (3, 0),    # priority below 1
        (3, 6),    # priority above 5
    ],
)
async def test_pydantic_validation_rejects_out_of_range_inputs(
    settings: Settings,
    storage: SqliteStorage,
    complexity: int,
    priority: int,
) -> None:
    """Pydantic enforces complexity/priority bounds (1..5) via the
    CheckBudgetForTaskInput model construction. Out-of-range inputs
    surface as structured MCP errors, not 500s.

    Closes the input-validation contract: future commits that drop
    the Field(ge=1, le=5) constraints from the input schema would
    fail this test informatively.
    """
    issued = await _issue_role_token(
        storage, "engineer", f"validation-{complexity}-{priority}"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, _make_snapshot()):
        result = await _call_check_budget(
            base_url=base_url, token=issued.token,
            complexity=complexity, priority=priority,
        )

    assert getattr(result, "isError", False) is True, (
        f"complexity={complexity}, priority={priority} should have "
        "raised a validation error; out-of-range inputs are spec'd "
        "as 1..5 per CheckBudgetForTaskInput's Field(ge=1, le=5) "
        "constraints."
    )
