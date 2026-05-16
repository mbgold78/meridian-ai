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

import pytest
import pytest_asyncio

from meridian_orchestrator.bridge.canonical import CanonicalClone
from meridian_orchestrator.bridge.dispatcher import BridgeDispatcher
from meridian_orchestrator.bridge.supervisor_base import LongRunningSupervisor
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage

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

    def issue(
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
