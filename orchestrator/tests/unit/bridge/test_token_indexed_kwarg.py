"""Regression guards for β-S11-5 / β-S11-6 fix call sites.

Two load-bearing callers must pass ``indexed=False`` when issuing per-token
records via :meth:`TokenStore.issue`:

- :meth:`BridgeDispatcher.dispatch` — per-dispatch Implementer/Tester tokens
- :meth:`LongRunningSupervisor._spawn` — per-spawn PM/Engineer supervisor tokens

A future refactor that silently drops the kwarg would re-brick the daemon
under sustained dispatch/spawn load because the keyring ``_index``
aggregator would grow unbounded and trip Windows Credential Manager's
~2560-byte CredentialBlob limit (``CredWrite`` returns ``WinError 1783``
above the limit; ``pm_supervisor.start()`` fails at startup).

These tests inject a spy ``TokenStore`` that records every ``issue()``
call's kwargs and raises immediately to short-circuit the caller's
downstream lifecycle. The assertion is that the spy recorded
``indexed=False``. Lifecycle correctness past the ``issue`` call is
covered by ``test_dispatch_lifecycle.py`` / ``test_supervisor_base.py``;
these tests guard only the kwarg.
"""

from __future__ import annotations

import asyncio
import shutil
import subprocess
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any

import keyring
import pytest
import pytest_asyncio

from meridian_orchestrator.auth import KEYRING_SERVICE, TokenStore, _record_key
from meridian_orchestrator.bridge.canonical import CanonicalClone
from meridian_orchestrator.bridge.dispatcher import (
    BridgeDispatcher,
    DispatchLaunchContext,
    SubprocessHandle,
)
from meridian_orchestrator.bridge.supervisor_base import (
    LongRunningSupervisor,
    SupervisorLaunchContext,
    SupervisorSubprocessHandle,
)
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.transient_tokens import TransientTokenStore

pytestmark = pytest.mark.skipif(
    shutil.which("git") is None,
    reason="git is not on PATH (canonical clone requires real git)",
)


class _SpyTokenStore:
    """Minimal TokenStore stand-in: records ``issue()`` kwargs and raises
    immediately. Lets the caller's lifecycle abort at the ``issue`` call
    site without needing full downstream state (worktrees, subprocesses,
    etc.) to behave correctly.

    Not a subclass of :class:`auth.TokenStore` — duck-typing on the
    ``issue`` method is sufficient because both call sites only invoke
    ``issue``. If a future caller adds dependencies on other TokenStore
    methods, those would need to be stubbed here too.
    """

    def __init__(self) -> None:
        self.issue_calls: list[dict[str, Any]] = []

    async def issue(
        self,
        agent_id: str | None,
        role: str,
        label: str,
        *,
        source: str = "static",
        client_id: str | None = None,
        expires_at: str | None = None,
        indexed: bool = True,
    ) -> Any:
        self.issue_calls.append(
            {
                "agent_id": agent_id,
                "role": role,
                "label": label,
                "source": source,
                "client_id": client_id,
                "expires_at": expires_at,
                "indexed": indexed,
            }
        )
        # Short-circuit the caller's lifecycle. The specific exception type
        # doesn't matter — both call sites bubble it up through their
        # try/finally clauses; the test catches it via pytest.raises.
        raise RuntimeError("spy: short-circuit after recording issue kwargs")


def _git(*args: str, cwd: Path) -> None:
    subprocess.run(["git", *args], cwd=str(cwd), check=True, capture_output=True)


@pytest.fixture
def upstream_repo(tmp_path: Path) -> Path:
    """Minimal git upstream for CanonicalClone to clone from."""
    upstream = tmp_path / "upstream"
    upstream.mkdir()
    _git("init", "-b", "main", cwd=upstream)
    _git("config", "user.email", "test@example.com", cwd=upstream)
    _git("config", "user.name", "Test", cwd=upstream)
    (upstream / "README.md").write_text("substrate\n", encoding="utf-8")
    _git("add", "README.md", cwd=upstream)
    _git("commit", "-m", "initial", cwd=upstream)
    return upstream


@pytest_asyncio.fixture
async def storage_with_task(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    """Minimal storage: schema migrated + one task row so BridgeDispatcher's
    ``_insert_dispatch_row`` can satisfy the foreign-key reference. Empty
    audit_log + dispatches tables; the spy raises before either gets
    interesting writes."""
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


@pytest_asyncio.fixture
async def canonical(tmp_path: Path, upstream_repo: Path) -> CanonicalClone:
    """Real CanonicalClone provisioned against the upstream fixture. The spy
    raises before any canonical methods are invoked, but BridgeDispatcher's
    constructor requires a real instance (not None)."""
    c = CanonicalClone(
        path=tmp_path / "canonical",
        upstream_url=str(upstream_repo),
    )
    await c.ensure_provisioned()
    return c


@pytest.mark.asyncio
async def test_bridge_dispatcher_passes_indexed_false_for_per_dispatch_tokens(
    storage_with_task: SqliteStorage,
    canonical: CanonicalClone,
    tmp_path: Path,
) -> None:
    """β-S11-5/6 regression guard: ``BridgeDispatcher.dispatch`` must call
    ``TokenStore.issue`` with ``indexed=False`` for per-dispatch
    Implementer/Tester tokens. Silent removal of this kwarg re-bricks the
    daemon by reintroducing unbounded keyring index growth."""
    spy = _SpyTokenStore()
    dispatcher = BridgeDispatcher(
        storage=storage_with_task,
        token_store=spy,  # type: ignore[arg-type]
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
    )

    with pytest.raises(RuntimeError, match="spy: short-circuit"):
        await dispatcher.dispatch(
            task_id="task-1",
            role="implementer",
            role_agent_id="impl-agent-id",
            prompt="ignored",
            actor="eng-agent-id",
        )

    assert len(spy.issue_calls) == 1, (
        "BridgeDispatcher.dispatch should call TokenStore.issue exactly once "
        "per dispatch attempt; got " + str(len(spy.issue_calls))
    )
    call = spy.issue_calls[0]
    assert call["indexed"] is False, (
        "BridgeDispatcher.dispatch must pass indexed=False to "
        "TokenStore.issue. Without this, per-dispatch tokens accumulate in "
        "the keyring `_index` aggregator and trip Windows Credential "
        "Manager's ~2560-byte CredentialBlob limit (WinError 1783; "
        "boot-blocks the daemon). β-S11-5 / β-S11-6 in findings catalog."
    )
    # Sanity-check the rest of the call shape so a future refactor that
    # changes label/role conventions also surfaces here.
    assert call["role"] == "implementer"
    assert call["label"].startswith("dispatch:")


class _MinimalSupervisor(LongRunningSupervisor):
    """Concrete LongRunningSupervisor subclass with the minimum hook
    implementations needed to exercise ``_spawn``. The spy raises before
    any of the subclass-specific behavior matters."""

    def _subprocess_prompt(self) -> str:
        return "test-prompt"

    def _working_directory(self) -> Path:
        return Path.cwd()  # never used; spy raises before launcher fires

    def _token_role(self) -> str:
        return "engineer"

    def _token_label(self) -> str:
        return "test-supervisor"

    def _supervisor_agent_id(self) -> str:
        return "test-agent-id"

    def _subprocess_allowed_tools(self) -> list[str]:
        return []


@pytest.mark.asyncio
async def test_long_running_supervisor_passes_indexed_false_for_per_spawn_tokens(
    tmp_path: Path,
) -> None:
    """β-S11-5/6 regression guard: ``LongRunningSupervisor._spawn`` must call
    ``TokenStore.issue`` with ``indexed=False`` for per-spawn supervisor
    tokens. Same failure mode as the dispatcher case — under supervisor
    respawn cycles the index grows past the CredentialBlob limit and
    boot-blocks the daemon."""
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    try:
        await run_migrations(s)

        spy = _SpyTokenStore()
        supervisor = _MinimalSupervisor(
            storage=s,
            token_store=spy,  # type: ignore[arg-type]
        )

        with pytest.raises(RuntimeError, match="spy: short-circuit"):
            await supervisor.start()

        assert len(spy.issue_calls) == 1, (
            "LongRunningSupervisor._spawn should call TokenStore.issue "
            "exactly once per spawn; got " + str(len(spy.issue_calls))
        )
        call = spy.issue_calls[0]
        assert call["indexed"] is False, (
            "LongRunningSupervisor._spawn must pass indexed=False to "
            "TokenStore.issue. Without this, per-spawn supervisor tokens "
            "accumulate in the keyring `_index` and trip Windows Credential "
            "Manager's ~2560-byte CredentialBlob limit (WinError 1783; "
            "boot-blocks the daemon). β-S11-5 / β-S11-6 in findings catalog."
        )
        # Sanity-check the call shape against the supervisor hook outputs.
        assert call["role"] == "engineer"
        assert call["label"] == "test-supervisor"
        assert call["agent_id"] == "test-agent-id"
    finally:
        await s.close()


def test_dispatcher_source_contains_indexed_false_literal() -> None:
    """Static guard complementing the runtime spy test. inspect-based check
    that ``BridgeDispatcher.dispatch`` contains the literal ``indexed=False``
    near the ``self._token_store.issue`` call site. Catches the case where
    a refactor moves the issue call into a helper that the runtime test
    might not exercise the same way."""
    import inspect

    source = inspect.getsource(BridgeDispatcher.dispatch)
    assert "self._token_store.issue" in source
    # Find the issue() call argument list; assert indexed=False is in it.
    after_issue = source.split("self._token_store.issue", 1)[1]
    # The call closes at the first matching `)` after a balanced section.
    # A loose check on the next ~500 chars is sufficient for regression
    # detection without parsing the AST.
    issue_window = after_issue[:500]
    assert "indexed=False" in issue_window, (
        "BridgeDispatcher.dispatch source does not contain literal "
        "indexed=False within ~500 chars after self._token_store.issue. "
        "If this assertion failed because the call moved, update the test "
        "to match the new location AND verify the runtime spy test above "
        "still asserts indexed=False. Do NOT delete this guard without "
        "replacing it with equivalent coverage."
    )


def test_supervisor_base_source_contains_indexed_false_literal() -> None:
    """Static guard for LongRunningSupervisor._spawn. Same rationale as the
    dispatcher source check."""
    import inspect

    source = inspect.getsource(LongRunningSupervisor._spawn)
    assert "self._token_store.issue" in source
    after_issue = source.split("self._token_store.issue", 1)[1]
    issue_window = after_issue[:500]
    assert "indexed=False" in issue_window, (
        "LongRunningSupervisor._spawn source does not contain literal "
        "indexed=False within ~500 chars after self._token_store.issue. "
        "See comment in test_dispatcher_source_contains_indexed_false_literal "
        "for the disposition discipline."
    )


# ---- Slice 1a commit 4: SQLite-backed transient routing landing tests ----
#
# The two tests below replace the spy with a real
# ``TokenStore(transient_store=TransientTokenStore(...))`` and assert the
# per-dispatch / per-spawn token lands in the ``transient_tokens`` SQLite
# table, NOT in the keyring ``token:<id>`` entry. The ``indexed=False``
# kwarg checked above is necessary but not sufficient — with the routing
# layer in place, the destination flipped from keyring to SQLite, and
# this is what fully closes β-S11-5/6's keyring-blob-growth surface (the
# original ``indexed=False`` kept the id out of the ``_index`` aggregator
# but the per-token record still went to keyring). To keep these tests
# fast and deterministic the launcher raises after the issue lands;
# downstream lifecycle (subprocess, terminate, merge) is covered in the
# existing dispatch and supervisor integration tests.


async def _raising_dispatcher_launcher(
    ctx: DispatchLaunchContext,
) -> SubprocessHandle:
    raise RuntimeError("test: short-circuit after transient issue lands")


async def _raising_supervisor_launcher(
    ctx: SupervisorLaunchContext,
) -> SupervisorSubprocessHandle:
    raise RuntimeError("test: short-circuit after transient issue lands")


@pytest.mark.asyncio
async def test_dispatcher_per_dispatch_token_lands_in_transient(
    storage_with_task: SqliteStorage,
    canonical: CanonicalClone,
    tmp_path: Path,
) -> None:
    """Slice 1a commit 4 call-site verification: when
    :class:`BridgeDispatcher` runs ``self._token_store.issue(...,
    indexed=False)`` through a :class:`TokenStore` wired with a real
    :class:`TransientTokenStore`, the per-dispatch token's
    ``token:<token_id>`` record must land in the SQLite
    ``transient_tokens`` table — and NOT in the keyring entry under
    ``token:<token_id>``. The keyring ``_index`` aggregator absence is
    already covered by the kwarg test above; this test verifies the
    routing destination switched."""
    transient_store = TransientTokenStore(storage_with_task)
    token_store = TokenStore(transient_store=transient_store)

    dispatcher = BridgeDispatcher(
        storage=storage_with_task,
        token_store=token_store,
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=_raising_dispatcher_launcher,
    )

    with pytest.raises(RuntimeError, match="test: short-circuit"):
        await dispatcher.dispatch(
            task_id="task-1",
            role="implementer",
            role_agent_id="impl-agent-id",
            prompt="ignored",
            actor="eng-agent-id",
        )

    row = await storage_with_task.fetch_one(
        "SELECT token_id FROM dispatches WHERE task_id = ?", ("task-1",)
    )
    assert row is not None and row["token_id"] is not None, (
        "BridgeDispatcher.dispatch should have set dispatches.token_id "
        "after the issue call landed."
    )
    token_id = row["token_id"]

    transient_row = await storage_with_task.fetch_one(
        "SELECT agent_id, role, label FROM transient_tokens "
        "WHERE token_id = ?",
        (token_id,),
    )
    assert transient_row is not None, (
        "expected a transient_tokens row for the per-dispatch token "
        "(token_id=" + token_id + "); SQLite-backed routing is not "
        "active for indexed=False issuance through TokenStore."
    )
    assert transient_row["agent_id"] == "impl-agent-id"
    assert transient_row["role"] == "implementer"
    assert transient_row["label"].startswith("dispatch:")

    assert (
        keyring.get_password(KEYRING_SERVICE, _record_key(token_id)) is None
    ), (
        "per-dispatch token leaked into the keyring backend; with "
        "transient_store wired the token:<id> record must live in "
        "transient_tokens only — keyring growth would re-open β-S11-5/6."
    )


@pytest.mark.asyncio
async def test_supervisor_per_spawn_token_lands_in_transient(
    tmp_path: Path,
) -> None:
    """Slice 1a commit 4 call-site verification: same routing-destination
    check as the dispatcher version, against
    :meth:`LongRunningSupervisor._spawn`. Per-spawn supervisor tokens
    were the other half of the β-S11-5/6 surface; the SQLite backend
    closes that side too."""
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    try:
        await run_migrations(s)
        transient_store = TransientTokenStore(s)
        token_store = TokenStore(transient_store=transient_store)

        supervisor = _MinimalSupervisor(
            storage=s,
            token_store=token_store,
            subprocess_launcher=_raising_supervisor_launcher,
        )

        with pytest.raises(RuntimeError, match="test: short-circuit"):
            await supervisor.start()

        row = await s.fetch_one(
            "SELECT token_id FROM dispatches WHERE token_id IS NOT NULL "
            "ORDER BY rowid DESC LIMIT 1"
        )
        assert row is not None and row["token_id"] is not None, (
            "LongRunningSupervisor._spawn should have set "
            "dispatches.token_id after the issue call landed."
        )
        token_id = row["token_id"]

        transient_row = await s.fetch_one(
            "SELECT agent_id, role, label FROM transient_tokens "
            "WHERE token_id = ?",
            (token_id,),
        )
        assert transient_row is not None, (
            "expected a transient_tokens row for the per-spawn supervisor "
            "token (token_id=" + token_id + "); SQLite-backed routing "
            "is not active for indexed=False supervisor issuance."
        )
        assert transient_row["agent_id"] == "test-agent-id"
        assert transient_row["role"] == "engineer"
        assert transient_row["label"] == "test-supervisor"

        assert (
            keyring.get_password(KEYRING_SERVICE, _record_key(token_id))
            is None
        ), (
            "per-spawn supervisor token leaked into the keyring backend; "
            "with transient_store wired the token:<id> record must live "
            "in transient_tokens only."
        )
    finally:
        await s.close()
