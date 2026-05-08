"""Unit tests for complete_dispatch / get_dispatch_status / get_dispatch_result.

These tests exercise the pure handler functions against a migrated SQLite DB
and an in-memory keyring (via the autouse ``keyring_memory`` fixture in
``tests/conftest.py``). The dispatcher's subprocess and worktree machinery is
not involved — the tests pre-seed dispatches rows in the state the handlers
expect to find them in. The dispatcher's full lifecycle is exercised in
``tests/integration/test_dispatch_lifecycle.py``.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from pathlib import Path

import pytest
import pytest_asyncio

from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.errors import ConflictError, NotFoundError
from meridian_orchestrator.errors import PermissionError as OrchestratorPermissionError
from meridian_orchestrator.errors import ValidationError as OrchestratorValidationError
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.tools.dispatch import (
    CompleteDispatchInput,
    GetDispatchResultInput,
    GetDispatchStatusInput,
    complete_dispatch,
    get_dispatch_result,
    get_dispatch_status,
)


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "test.db")
    await s.connect()
    await run_migrations(s)
    # Seed FK chain for dispatches.task_id → tasks.id → projects.id → companies.id.
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
def token_store() -> TokenStore:
    return TokenStore()


async def _seed_dispatch(
    s: SqliteStorage,
    *,
    dispatch_id: str,
    role: str,
    status: str,
    token_id: str | None,
    task_id: str = "task-1",
) -> None:
    await s.execute(
        "INSERT INTO dispatches "
        "(id, task_id, role, status, working_directory, timeout_seconds, token_id) "
        "VALUES (?, ?, ?, ?, '/tmp/wt', 300, ?)",
        (dispatch_id, task_id, role, status, token_id),
    )


# ----- complete_dispatch ---------------------------------------------------


async def test_complete_dispatch_happy_path(
    storage: SqliteStorage, token_store: TokenStore
) -> None:
    issued = token_store.issue("impl-agent-id", "implementer", "dispatch:d1")
    await _seed_dispatch(
        storage,
        dispatch_id="d1",
        role="implementer",
        status="running",
        token_id=issued.record.token_id,
    )

    out = await complete_dispatch(
        storage=storage,
        token_store=token_store,
        request=CompleteDispatchInput(
            dispatch_id="d1",
            status="completed",
            commit_hash="abc1234",
            artifacts=["src/foo.py"],
            queue_items_raised=["follow-up: rename bar"],
            notes="landed cleanly",
        ),
        caller_agent_id="impl-agent-id",
        caller_role="implementer",
    )
    assert out.accepted is True
    assert out.next_action == "terminate"

    row = await storage.fetch_one(
        "SELECT status, commit_hash, notes, completed_at FROM dispatches WHERE id = 'd1'"
    )
    assert row is not None
    assert row["status"] == "completed"
    assert row["commit_hash"] == "abc1234"
    assert row["notes"] == "landed cleanly"
    assert row["completed_at"] is not None

    audit = await storage.fetch_all(
        "SELECT actor, action, target FROM audit_log WHERE target = 'd1'"
    )
    actions = {a["action"] for a in audit}
    assert "dispatch.complete_called" in actions
    cc = next(a for a in audit if a["action"] == "dispatch.complete_called")
    assert cc["actor"] == "impl-agent-id"


async def test_complete_dispatch_decline_path(
    storage: SqliteStorage, token_store: TokenStore
) -> None:
    issued = token_store.issue("impl-agent-id", "implementer", "dispatch:d2")
    await _seed_dispatch(
        storage,
        dispatch_id="d2",
        role="implementer",
        status="running",
        token_id=issued.record.token_id,
    )

    out = await complete_dispatch(
        storage=storage,
        token_store=token_store,
        request=CompleteDispatchInput(
            dispatch_id="d2",
            status="declined",
            decline_reason="spec contradicts foundation §UI-7",
        ),
        caller_agent_id="impl-agent-id",
        caller_role="implementer",
    )
    assert out.accepted is True

    row = await storage.fetch_one(
        "SELECT status, decline_reason FROM dispatches WHERE id = 'd2'"
    )
    assert row is not None
    assert row["status"] == "declined"
    assert row["decline_reason"] == "spec contradicts foundation §UI-7"


async def test_complete_dispatch_decline_requires_reason(
    storage: SqliteStorage, token_store: TokenStore
) -> None:
    issued = token_store.issue("impl-agent-id", "implementer", "dispatch:d3")
    await _seed_dispatch(
        storage,
        dispatch_id="d3",
        role="implementer",
        status="running",
        token_id=issued.record.token_id,
    )
    with pytest.raises(OrchestratorValidationError):
        await complete_dispatch(
            storage=storage,
            token_store=token_store,
            request=CompleteDispatchInput(dispatch_id="d3", status="declined"),
            caller_agent_id="impl-agent-id",
            caller_role="implementer",
        )


async def test_complete_dispatch_conflict_already_terminal(
    storage: SqliteStorage, token_store: TokenStore
) -> None:
    issued = token_store.issue("impl-agent-id", "implementer", "dispatch:d4")
    await _seed_dispatch(
        storage,
        dispatch_id="d4",
        role="implementer",
        status="completed",
        token_id=issued.record.token_id,
    )
    with pytest.raises(ConflictError):
        await complete_dispatch(
            storage=storage,
            token_store=token_store,
            request=CompleteDispatchInput(dispatch_id="d4", status="completed"),
            caller_agent_id="impl-agent-id",
            caller_role="implementer",
        )


async def test_complete_dispatch_role_mismatch(
    storage: SqliteStorage, token_store: TokenStore
) -> None:
    """A tester-role caller cannot complete an implementer dispatch."""
    issued = token_store.issue("impl-agent-id", "implementer", "dispatch:d5")
    await _seed_dispatch(
        storage,
        dispatch_id="d5",
        role="implementer",
        status="running",
        token_id=issued.record.token_id,
    )
    with pytest.raises(OrchestratorPermissionError):
        await complete_dispatch(
            storage=storage,
            token_store=token_store,
            request=CompleteDispatchInput(dispatch_id="d5", status="completed"),
            caller_agent_id="impl-agent-id",
            caller_role="tester",
        )


async def test_complete_dispatch_caller_not_token_owner(
    storage: SqliteStorage, token_store: TokenStore
) -> None:
    """Even with the correct role, only the agent the token was issued to
    can complete the dispatch."""
    issued = token_store.issue("impl-agent-id", "implementer", "dispatch:d6")
    await _seed_dispatch(
        storage,
        dispatch_id="d6",
        role="implementer",
        status="running",
        token_id=issued.record.token_id,
    )
    with pytest.raises(OrchestratorPermissionError):
        await complete_dispatch(
            storage=storage,
            token_store=token_store,
            request=CompleteDispatchInput(dispatch_id="d6", status="completed"),
            caller_agent_id="some-other-impl-agent",
            caller_role="implementer",
        )


async def test_complete_dispatch_unknown_id(
    storage: SqliteStorage, token_store: TokenStore
) -> None:
    with pytest.raises(NotFoundError):
        await complete_dispatch(
            storage=storage,
            token_store=token_store,
            request=CompleteDispatchInput(dispatch_id="ghost", status="completed"),
            caller_agent_id="impl-agent-id",
            caller_role="implementer",
        )


# ----- F5: tester commit_hash pass-through + COALESCE preservation --------


async def test_complete_dispatch_tester_preserves_commit_hash_on_none(
    storage: SqliteStorage, token_store: TokenStore
) -> None:
    """F5 CQ-D: Tester's complete_dispatch with commit_hash=None must NOT
    clobber the up-front-recorded value. The COALESCE shape on the UPDATE
    delivers the docstring contract that the verified commit is recorded
    up front and visible end-to-end on the dispatches row."""
    issued = token_store.issue("tester-agent-id", "tester", "dispatch:dt1")
    seeded_sha = "deadbeef" + "0" * 32
    await storage.execute(
        "INSERT INTO dispatches "
        "(id, task_id, role, status, working_directory, timeout_seconds, "
        "token_id, commit_hash) "
        "VALUES (?, 'task-1', 'tester', 'running', '/tmp/wt', 300, ?, ?)",
        ("dt1", issued.record.token_id, seeded_sha),
    )

    await complete_dispatch(
        storage=storage,
        token_store=token_store,
        request=CompleteDispatchInput(
            dispatch_id="dt1",
            status="completed",
            notes="verified",
        ),
        caller_agent_id="tester-agent-id",
        caller_role="tester",
    )

    row = await storage.fetch_one(
        "SELECT status, commit_hash FROM dispatches WHERE id = 'dt1'"
    )
    assert row is not None
    assert row["status"] == "completed"
    # COALESCE preserved the up-front sha through terminal.
    assert row["commit_hash"] == seeded_sha


async def test_complete_dispatch_implementer_overwrites_commit_hash(
    storage: SqliteStorage, token_store: TokenStore
) -> None:
    """F5 CQ-D symmetry: Implementer success path still overwrites the
    column when a new commit_hash is provided. COALESCE only no-ops when
    the caller passes None."""
    issued = token_store.issue("impl-agent-id", "implementer", "dispatch:di1")
    await _seed_dispatch(
        storage,
        dispatch_id="di1",
        role="implementer",
        status="running",
        token_id=issued.record.token_id,
    )

    await complete_dispatch(
        storage=storage,
        token_store=token_store,
        request=CompleteDispatchInput(
            dispatch_id="di1",
            status="completed",
            commit_hash="cafebabe" + "1" * 32,
        ),
        caller_agent_id="impl-agent-id",
        caller_role="implementer",
    )

    row = await storage.fetch_one(
        "SELECT commit_hash FROM dispatches WHERE id = 'di1'"
    )
    assert row is not None
    assert row["commit_hash"] == "cafebabe" + "1" * 32


async def test_complete_dispatch_implementer_decline_leaves_commit_hash_null(
    storage: SqliteStorage, token_store: TokenStore
) -> None:
    """F5 CQ-D symmetry: Implementer decline/fail path leaves commit_hash
    NULL (no clobber semantics; the row's INSERT defaulted to NULL and
    COALESCE(None, NULL) = NULL preserves the absence)."""
    issued = token_store.issue("impl-agent-id", "implementer", "dispatch:di2")
    await _seed_dispatch(
        storage,
        dispatch_id="di2",
        role="implementer",
        status="running",
        token_id=issued.record.token_id,
    )

    await complete_dispatch(
        storage=storage,
        token_store=token_store,
        request=CompleteDispatchInput(
            dispatch_id="di2",
            status="declined",
            decline_reason="spec contradicts §UI-7",
        ),
        caller_agent_id="impl-agent-id",
        caller_role="implementer",
    )

    row = await storage.fetch_one(
        "SELECT status, commit_hash, decline_reason FROM dispatches "
        "WHERE id = 'di2'"
    )
    assert row is not None
    assert row["status"] == "declined"
    assert row["commit_hash"] is None
    assert row["decline_reason"] == "spec contradicts §UI-7"


def test_bridge_dispatcher_dispatch_commit_hash_is_keyword_only() -> None:
    """F5 CQ-B contract: ``commit_hash`` on ``BridgeDispatcher.dispatch`` is
    keyword-only with a None default. Encodes the convention so a future
    refactor can't silently flip the parameter to positional and break
    callers that omit it."""
    import inspect

    from meridian_orchestrator.bridge.dispatcher import BridgeDispatcher

    sig = inspect.signature(BridgeDispatcher.dispatch)
    param = sig.parameters.get("commit_hash")
    assert param is not None, (
        "BridgeDispatcher.dispatch must accept commit_hash kwarg per F5"
    )
    assert param.kind is inspect.Parameter.KEYWORD_ONLY, (
        "commit_hash must be KEYWORD_ONLY (consistent with all other "
        "BridgeDispatcher.dispatch parameters after the * separator)"
    )
    assert param.default is None, (
        "commit_hash must default to None — non-tester callers "
        "(dispatch_implementer, supervisor lifecycle) omit the kwarg"
    )


# ----- get_dispatch_status / result ---------------------------------------


async def test_get_dispatch_status_engineer_can_read(
    storage: SqliteStorage, token_store: TokenStore
) -> None:
    issued = token_store.issue("impl-agent-id", "implementer", "dispatch:d7")
    await _seed_dispatch(
        storage,
        dispatch_id="d7",
        role="implementer",
        status="running",
        token_id=issued.record.token_id,
    )
    out = await get_dispatch_status(
        storage=storage,
        token_store=token_store,
        request=GetDispatchStatusInput(dispatch_id="d7"),
        caller_agent_id="eng-agent-id",
        caller_role="engineer",
    )
    assert out.dispatch_id == "d7"
    assert out.status == "running"
    assert out.role == "implementer"


async def test_get_dispatch_result_implementer_self_only(
    storage: SqliteStorage, token_store: TokenStore
) -> None:
    issued = token_store.issue("impl-agent-id", "implementer", "dispatch:d8")
    await _seed_dispatch(
        storage,
        dispatch_id="d8",
        role="implementer",
        status="completed",
        token_id=issued.record.token_id,
    )
    # Owning implementer can read.
    out = await get_dispatch_result(
        storage=storage,
        token_store=token_store,
        request=GetDispatchResultInput(dispatch_id="d8"),
        caller_agent_id="impl-agent-id",
        caller_role="implementer",
    )
    assert out.dispatch_id == "d8"

    # A different implementer cannot read.
    with pytest.raises(OrchestratorPermissionError):
        await get_dispatch_result(
            storage=storage,
            token_store=token_store,
            request=GetDispatchResultInput(dispatch_id="d8"),
            caller_agent_id="some-other-impl",
            caller_role="implementer",
        )


# ---------------------------------------------------------------------------
# _resolve_allowed_tools_for_role (M5-acceptance commit 3)
#
# Convention 17 sub-type (a) Contract enforcement: helper accepts None
# registry without crashing; returns []; preserves the test-fixture
# argv shape per ``feedback_optional_dependency_in_helpers`` precedent.
#
# Convention 17 sub-type (c) Invariant enforcement: orchestrator MCP
# tool prefix transformation produces the canonical
# ``mcp__meridian-orchestrator__<tool>`` shape (hyphen preserved per
# empirical verification 2026-05-08; convention seen with claude.ai
# HTTP MCPs in the deferred-tools list normalizes spaces/dots to
# underscore but preserves hyphens as valid identifier chars).
# ---------------------------------------------------------------------------


def test_resolve_allowed_tools_for_role_returns_empty_when_registry_none() -> None:
    """The helper handles ``registry=None`` by returning ``[]`` —
    matches the fallback pattern of :func:`_resolve_system_prompt_for_role`,
    :func:`_resolve_effort_for_role`, :func:`_resolve_model_for_role`.
    Empty result causes the dispatcher launcher to omit ``--allowedTools``
    entirely, preserving the test-fixture argv shape.
    """
    from meridian_orchestrator.tools.dispatch import _resolve_allowed_tools_for_role

    assert _resolve_allowed_tools_for_role(None, "tester") == []
    assert _resolve_allowed_tools_for_role(None, "implementer") == []


async def test_resolve_allowed_tools_for_role_prefixes_orchestrator_tools_with_canonical_naming(
    tmp_path: Path,
) -> None:
    """The helper prefixes orchestrator MCP tools (from
    ``AgentConfig.mcp_tools_allowed``) with the canonical
    ``mcp__meridian-orchestrator__`` prefix and concatenates with
    ``AgentConfig.allowed_tools`` (built-in + non-orchestrator MCP).

    Empirical anchor (2026-05-08): subprocess permission-denial messages
    name the canonical orchestrator MCP tool as
    ``mcp__meridian-orchestrator__list_agents`` — hyphen preserved
    (NOT normalized to ``mcp__meridian_orchestrator__...``). This test
    locks the prefix literal against future drift; if the
    orchestrator's FastMCP server name at ``server.py:368`` changes,
    update both sites in lockstep.

    Output ordering invariant: built-in/external first (yaml literal
    order), orchestrator tools second (yaml literal order with prefix).
    Stable across runs so test fixtures asserting on the exact list
    don't flap.
    """
    from meridian_orchestrator.agents.registry import AgentRegistry
    from meridian_orchestrator.tools.dispatch import (
        _ORCHESTRATOR_MCP_TOOL_PREFIX,
        _resolve_allowed_tools_for_role,
    )

    # Lock the prefix literal at the test layer too — if the constant
    # is changed without the empirical re-verification (e.g., to
    # mcp__meridian_orchestrator__ via a misremembered convention),
    # this assertion fires. Brittle by design per Convention 9
    # code-inspection sentinel observability.
    assert _ORCHESTRATOR_MCP_TOOL_PREFIX == "mcp__meridian-orchestrator__", (
        "orchestrator MCP tool prefix drifted from the empirical canonical "
        "form. Verified 2026-05-08: subprocess permission-denial named "
        "`mcp__meridian-orchestrator__list_agents` (hyphen preserved). "
        "If FastMCP server name at server.py:368 changes, update both "
        "the constant and this assertion in lockstep."
    )

    # Construct a real AgentRegistry from the repo's tester.yaml +
    # implementer.yaml — exercises the full helper code path including
    # AgentConfig.allowed_tools + AgentConfig.mcp_tools_allowed access.
    # Tokens issued via a per-test TokenStore service so initial_load()
    # resolves agent_ids and the snapshot includes all four roles
    # (mirrors test_registry.py's pattern at
    # test_initial_load_resolves_uuids_from_keyring).
    repo_config_dir = (
        Path(__file__).resolve().parents[3] / "config" / "agents"
    )
    agents_dir = tmp_path / "agents"
    agents_dir.mkdir()
    for role in ("pm", "engineer", "implementer", "tester"):
        (agents_dir / f"{role}.yaml").write_text(
            (repo_config_dir / f"{role}.yaml").read_text(encoding="utf-8"),
            encoding="utf-8",
        )
    store = TokenStore(service="resolve-allowed-tools-test")
    for role in ("pm", "engineer", "implementer", "tester"):
        store.issue(agent_id=None, role=role, label=f"{role}-test")
    registry = AgentRegistry(agents_dir, token_store=store)
    await registry.initial_load()

    tester_tools = _resolve_allowed_tools_for_role(registry, "tester")
    # Built-in/external tools come first per ordering invariant
    assert tester_tools[0] == "Read"
    # Orchestrator MCP tools come last with canonical prefix
    assert "mcp__meridian-orchestrator__update_task_status" in tester_tools
    assert "mcp__meridian-orchestrator__complete_dispatch" in tester_tools
    # Playwright tools (already prefixed in yaml) flow through as-is
    assert "mcp__playwright__browser_navigate" in tester_tools
    # No bare orchestrator tool names in the final list — proves the
    # prefix transformation ran, not just yaml pass-through
    assert "update_task_status" not in tester_tools, (
        "bare 'update_task_status' appeared in resolved allowed_tools — "
        "the orchestrator prefix transformation failed to apply"
    )

    implementer_tools = _resolve_allowed_tools_for_role(registry, "implementer")
    # Implementer also gets prefixed orchestrator tools
    assert "mcp__meridian-orchestrator__complete_dispatch" in implementer_tools
    # No Playwright entries (role-asymmetry preserved)
    assert not any(
        t.startswith("mcp__playwright__") for t in implementer_tools
    ), "implementer resolved allowed_tools contains Playwright entries"
