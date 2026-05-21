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
    DispatchImplementerInput,
    DispatchTesterInput,
    GetDispatchResultInput,
    GetDispatchStatusInput,
    complete_dispatch,
    dispatch_implementer,
    dispatch_tester,
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
    issued = await token_store.issue("impl-agent-id", "implementer", "dispatch:d1")
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
    issued = await token_store.issue("impl-agent-id", "implementer", "dispatch:d2")
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
    issued = await token_store.issue("impl-agent-id", "implementer", "dispatch:d3")
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
    issued = await token_store.issue("impl-agent-id", "implementer", "dispatch:d4")
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
    issued = await token_store.issue("impl-agent-id", "implementer", "dispatch:d5")
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
    issued = await token_store.issue("impl-agent-id", "implementer", "dispatch:d6")
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
    issued = await token_store.issue("tester-agent-id", "tester", "dispatch:dt1")
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
    issued = await token_store.issue("impl-agent-id", "implementer", "dispatch:di1")
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
    issued = await token_store.issue("impl-agent-id", "implementer", "dispatch:di2")
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
    issued = await token_store.issue("impl-agent-id", "implementer", "dispatch:d7")
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
    issued = await token_store.issue("impl-agent-id", "implementer", "dispatch:d8")
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


# ----- M5-canonical-clone-mgmt slice 4 — canonical_name parameter ---------


# U1 — Pydantic input model validation (DispatchImplementerInput +
# DispatchTesterInput). Slice 4 adds an optional ``canonical_name`` field
# constrained by the same regex/length bounds as Slice 3's
# ``register_canonical_clone`` validation surface, so a name that passes
# registration also passes dispatch. ``None`` is the default (preserves
# pre-Slice-4 behavior). ``'default'`` is callable on dispatch even
# though reserved on register/unregister (Ratification #5).


_VALID_CANONICAL_NAMES = ["substrate", "orchestrator", "a", "default", "x-y-z"]
_INVALID_CANONICAL_NAMES = [
    "",            # length < 1
    "A-NAME",      # uppercase
    "1bad",        # must start with letter
    "-leading",    # must start with letter
    "x" * 64,      # length > 63
    "with_underscore",  # underscores not allowed
    "with space",  # spaces not allowed
]


@pytest.mark.parametrize("name", _VALID_CANONICAL_NAMES + [None])
def test_dispatch_implementer_input_accepts_valid_canonical_name(
    name: str | None,
) -> None:
    """``DispatchImplementerInput.canonical_name`` accepts ``None`` (default)
    and every name passing the Slice 3 registration regex (``^[a-z][a-z0-9-]{0,62}$``).
    Slice 4 Ratification #5: ``'default'`` is callable on dispatch."""
    req = DispatchImplementerInput(
        task_id="task-1",
        spec_path="/tmp/spec.md",
        working_directory="/tmp/wd",
        canonical_name=name,
    )
    assert req.canonical_name == name


@pytest.mark.parametrize("name", _INVALID_CANONICAL_NAMES)
def test_dispatch_implementer_input_rejects_invalid_canonical_name(
    name: str,
) -> None:
    """Pydantic rejects names violating the Slice 3 regex/length bounds at
    the wrapper boundary. The request never reaches the handler;
    permission/validation failures emit no audit row per Slice 3 Q8."""
    from pydantic import ValidationError as PydanticValidationError

    with pytest.raises(PydanticValidationError):
        DispatchImplementerInput(
            task_id="task-1",
            spec_path="/tmp/spec.md",
            working_directory="/tmp/wd",
            canonical_name=name,
        )


@pytest.mark.parametrize("name", _VALID_CANONICAL_NAMES + [None])
def test_dispatch_tester_input_accepts_valid_canonical_name(
    name: str | None,
) -> None:
    """Same matrix as the implementer input. Symmetric validation surface."""
    req = DispatchTesterInput(
        task_id="task-1",
        commit_hash="deadbeef" + "0" * 32,
        working_directory="/tmp/wd",
        assertion_spec="run tests",
        canonical_name=name,
    )
    assert req.canonical_name == name


@pytest.mark.parametrize("name", _INVALID_CANONICAL_NAMES)
def test_dispatch_tester_input_rejects_invalid_canonical_name(
    name: str,
) -> None:
    """Same rejection matrix as the implementer input."""
    from pydantic import ValidationError as PydanticValidationError

    with pytest.raises(PydanticValidationError):
        DispatchTesterInput(
            task_id="task-1",
            commit_hash="deadbeef" + "0" * 32,
            working_directory="/tmp/wd",
            assertion_spec="run tests",
            canonical_name=name,
        )


# ----- U8 — handler threading spy -----------------------------------------


class _FakeDispatcher:
    """Minimal spy capturing ``dispatch()`` kwargs for Slice 4 threading
    verification. Returns a degenerate DispatchOutcome so the handler can
    flatten it without further machinery."""

    def __init__(self) -> None:
        self.dispatch_calls: list[dict[str, object]] = []

    async def dispatch(self, **kwargs: object) -> object:
        from meridian_orchestrator.bridge.dispatcher import DispatchOutcome

        self.dispatch_calls.append(kwargs)
        return DispatchOutcome(
            dispatch_id="d-spy", status="completed", commit_hash="cafe" + "0" * 36
        )


async def test_dispatch_implementer_threads_canonical_name_to_dispatcher(
    tmp_path: Path,
) -> None:
    """``dispatch_implementer`` handler propagates ``request.canonical_name``
    into ``dispatcher.dispatch(...)`` kwargs. Slice 4 routes a registered
    name end-to-end; this canary pins the handler-level seam so a future
    refactor can't drop the kwarg silently."""
    spec_path = tmp_path / "spec.md"
    spec_path.write_text("implement X\n", encoding="utf-8")
    fake = _FakeDispatcher()

    await dispatch_implementer(
        dispatcher=fake,  # type: ignore[arg-type]
        registry=None,
        request=DispatchImplementerInput(
            task_id="task-1",
            spec_path=str(spec_path),
            working_directory=str(tmp_path),
            canonical_name="substrate",
        ),
        caller_agent_id="eng-agent-id",
        caller_role="engineer",
        implementer_agent_id="impl-agent-id",
    )

    assert len(fake.dispatch_calls) == 1
    assert fake.dispatch_calls[0]["canonical_name"] == "substrate"


async def test_dispatch_implementer_threads_none_canonical_name(
    tmp_path: Path,
) -> None:
    """Default ``canonical_name=None`` from the input model threads as None
    into dispatcher kwargs — the resolver swap inside ``dispatch()``
    interprets None as ``get_default()`` (per Slice 4 § 3b)."""
    spec_path = tmp_path / "spec.md"
    spec_path.write_text("implement X\n", encoding="utf-8")
    fake = _FakeDispatcher()

    await dispatch_implementer(
        dispatcher=fake,  # type: ignore[arg-type]
        registry=None,
        request=DispatchImplementerInput(
            task_id="task-1",
            spec_path=str(spec_path),
            working_directory=str(tmp_path),
        ),
        caller_agent_id="eng-agent-id",
        caller_role="engineer",
        implementer_agent_id="impl-agent-id",
    )

    assert fake.dispatch_calls[0]["canonical_name"] is None


async def test_dispatch_tester_threads_canonical_name_to_dispatcher(
    tmp_path: Path,
) -> None:
    """Same as the implementer canary, on the tester boundary."""
    fake = _FakeDispatcher()

    await dispatch_tester(
        dispatcher=fake,  # type: ignore[arg-type]
        registry=None,
        request=DispatchTesterInput(
            task_id="task-1",
            commit_hash="deadbeef" + "0" * 32,
            working_directory=str(tmp_path),
            assertion_spec="run tests",
            canonical_name="orchestrator",
        ),
        caller_agent_id="eng-agent-id",
        caller_role="engineer",
        tester_agent_id="tester-agent-id",
    )

    assert len(fake.dispatch_calls) == 1
    assert fake.dispatch_calls[0]["canonical_name"] == "orchestrator"


# ----- U7 — reserved-name interaction -------------------------------------


async def test_dispatch_implementer_input_accepts_reserved_default_name(
    tmp_path: Path,
) -> None:
    """Slice 4 Ratification #5: ``canonical_name='default'`` is callable on
    dispatch even though reserved on register/unregister. The Pydantic
    boundary admits it; the handler threads it; the dispatcher's
    resolver (per § 3d) resolves it identically to ``None``. This U7
    pins the boundary acceptance."""
    spec_path = tmp_path / "spec.md"
    spec_path.write_text("implement X\n", encoding="utf-8")
    fake = _FakeDispatcher()

    await dispatch_implementer(
        dispatcher=fake,  # type: ignore[arg-type]
        registry=None,
        request=DispatchImplementerInput(
            task_id="task-1",
            spec_path=str(spec_path),
            working_directory=str(tmp_path),
            canonical_name="default",
        ),
        caller_agent_id="eng-agent-id",
        caller_role="engineer",
        implementer_agent_id="impl-agent-id",
    )

    assert fake.dispatch_calls[0]["canonical_name"] == "default"
