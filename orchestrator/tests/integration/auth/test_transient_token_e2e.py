"""End-to-end self-access contract test against an SQLite-backed transient token.

Verifies the load-bearing dispatch self-access contract (audit
production-row 4 in the M5-sqlite-transient-token-store parent spec) end
to end: when a per-dispatch token is issued via
:class:`TokenStore` wired to a :class:`TransientTokenStore`, the
dispatch lifecycle revokes the token in its terminal-state cleanup, and
the Implementer's subsequent ``get_dispatch_result`` call (which
internally calls
:func:`meridian_orchestrator.tools.dispatch._enforce_self_dispatch_access`)
must still resolve the record by ``token_id`` and confirm ``agent_id``
ownership.

The contract is dual-backend symmetric: the keyring path already
returned revoked records via ``lookup_by_token_id`` (auth.py line 369);
this test pins the same semantics on the SQLite backend. A regression
here would brick every Implementer dispatch under the SQLite-routed
configuration — the Implementer would land its ``complete_dispatch``
call, the dispatcher's finally clause would revoke the per-dispatch
token, and the Implementer's follow-up ``get_dispatch_result`` would
then fail with ``OrchestratorPermissionError`` because the revoked
record could no longer be resolved.

Per the slice 1a spec's spec-gap resolution authority: this test runs
the dispatch through :class:`BridgeDispatcher` for the
issuance+revocation half (so the contract is exercised against the real
production code path) and then calls
``_enforce_self_dispatch_access`` directly for the permission-check
half (faster + more deterministic than spinning a real subprocess
Implementer that re-enters the daemon's HTTP MCP endpoint).
"""

from __future__ import annotations

import asyncio
import shutil
import subprocess
from collections.abc import AsyncIterator
from pathlib import Path

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
from meridian_orchestrator.errors import (
    PermissionError as OrchestratorPermissionError,
)
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.tools.dispatch import (
    CompleteDispatchInput,
    _enforce_self_dispatch_access,
    complete_dispatch,
)
from meridian_orchestrator.transient_tokens import TransientTokenStore

pytestmark = pytest.mark.skipif(
    shutil.which("git") is None, reason="git is not on PATH"
)


def _git(*args: str, cwd: Path) -> None:
    subprocess.run(["git", *args], cwd=str(cwd), check=True, capture_output=True)


@pytest.fixture
def upstream_repo(tmp_path: Path) -> Path:
    """Non-bare git upstream the canonical clone can fast-forward against.

    Mirrors the upstream fixture in
    ``tests/integration/test_dispatch_lifecycle.py``; replicated here to
    keep the e2e test self-contained without cross-package fixture
    imports.
    """
    upstream = tmp_path / "upstream"
    upstream.mkdir()
    _git("init", "-b", "main", cwd=upstream)
    _git("config", "user.email", "test@example.com", cwd=upstream)
    _git("config", "user.name", "Test", cwd=upstream)
    _git("config", "receive.denyCurrentBranch", "ignore", cwd=upstream)
    (upstream / "README.md").write_text("substrate\n", encoding="utf-8")
    _git("add", "README.md", cwd=upstream)
    _git("commit", "-m", "initial", cwd=upstream)
    return upstream


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
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description) VALUES ('task-1', 'co1', 'proj1', 'implementer', "
        "'no-op')"
    )
    yield s
    await s.close()


class _FakeSubprocess:
    """Minimal SubprocessHandle the dispatcher accepts. Mirrors the helper
    in ``tests/integration/test_dispatch_lifecycle.py``."""

    def __init__(self) -> None:
        self._returncode: int | None = None
        self._exit = asyncio.Event()

    @property
    def pid(self) -> int | None:
        return 99999

    @property
    def returncode(self) -> int | None:
        return self._returncode

    async def wait(self) -> int:
        await self._exit.wait()
        assert self._returncode is not None
        return self._returncode

    def terminate(self) -> None:
        if self._returncode is None:
            self._returncode = 0
            self._exit.set()

    def kill(self) -> None:
        self._returncode = -9
        self._exit.set()

    def signal_clean_exit(self) -> None:
        self._returncode = 0
        self._exit.set()


def _commit_in_worktree(worktree: Path, message: str) -> str:
    _git("config", "user.email", "test@example.com", cwd=worktree)
    _git("config", "user.name", "Test", cwd=worktree)
    (worktree / "feature.txt").write_text(
        "transient e2e wrote this\n", encoding="utf-8"
    )
    _git("add", "feature.txt", cwd=worktree)
    _git("commit", "-m", message, cwd=worktree)
    return subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=str(worktree),
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()


async def test_dispatch_self_access_resolves_after_transient_revoke(
    storage: SqliteStorage, upstream_repo: Path, tmp_path: Path
) -> None:
    """End-to-end self-access contract against an SQLite-backed token.

    Drives a full Implementer dispatch through
    :class:`BridgeDispatcher` with a :class:`TokenStore` wired to a
    :class:`TransientTokenStore`. The fake launcher reaches
    ``complete_dispatch`` (status='completed'); the dispatcher's
    finally clause then revokes the per-dispatch transient token. The
    test then simulates the Implementer's follow-up
    ``get_dispatch_result`` call by invoking
    :func:`_enforce_self_dispatch_access` directly — the revoked
    SQLite-backed record must still resolve via
    ``token_store.lookup_by_token_id`` and the ``agent_id`` comparison
    must succeed (no :class:`OrchestratorPermissionError`).
    """
    canonical = CanonicalClone(
        path=tmp_path / "data" / "substrate-canonical",
        upstream_url=str(upstream_repo),
        default_branch="main",
    )
    await canonical.ensure_provisioned()

    transient_store = TransientTokenStore(storage)
    token_store = TokenStore(transient_store=transient_store)

    fake_proc = _FakeSubprocess()
    background: list[asyncio.Task[None]] = []

    async def fake_launcher(ctx: DispatchLaunchContext) -> SubprocessHandle:
        async def simulate() -> None:
            await asyncio.sleep(0)
            commit_sha = await asyncio.to_thread(
                _commit_in_worktree,
                ctx.working_directory,
                "slice 1a commit 4: e2e transient token contract",
            )
            await complete_dispatch(
                storage=storage,
                token_store=token_store,
                request=CompleteDispatchInput(
                    dispatch_id=ctx.dispatch_id,
                    status="completed",
                    commit_hash=commit_sha,
                    artifacts=["feature.txt"],
                    notes="transient-token e2e",
                ),
                caller_agent_id="impl-agent-id",
                caller_role="implementer",
            )
            fake_proc.signal_clean_exit()

        background.append(asyncio.create_task(simulate()))
        return fake_proc

    dispatcher = BridgeDispatcher(
        storage=storage,
        token_store=token_store,
        canonical=canonical,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=fake_launcher,
        poll_interval_seconds=0.02,
    )

    outcome = await dispatcher.dispatch(
        task_id="task-1",
        role="implementer",
        role_agent_id="impl-agent-id",
        prompt="spec body ignored — fake launcher drives the lifecycle.",
        actor="eng-agent-id",
    )
    # Drain the in-flight simulate() task to keep the event loop tidy.
    for t in background:
        await t

    assert outcome.status == "completed"

    row = await storage.fetch_one(
        "SELECT token_id FROM dispatches WHERE id = ?",
        (outcome.dispatch_id,),
    )
    assert row is not None and row["token_id"] is not None
    storage_token_id = row["token_id"]

    # Pin the routing destination: the per-dispatch token must have
    # landed in transient_tokens, not in the keyring. If a future
    # refactor flips routing back to keyring under the indexed=False
    # path, this assertion catches it before the revoke-and-resolve
    # contract surfaces as a runtime failure for Implementer's
    # get_dispatch_result.
    transient_row = await storage.fetch_one(
        "SELECT revoked_at FROM transient_tokens WHERE token_id = ?",
        (storage_token_id,),
    )
    assert transient_row is not None, (
        "per-dispatch token did not land in transient_tokens; the e2e "
        "contract this test pins would still pass by accident via the "
        "keyring fallback. Fix routing before trusting this test."
    )
    assert transient_row["revoked_at"] is not None, (
        "dispatcher's finally clause should have revoked the per-dispatch "
        "transient token after terminal-state cleanup."
    )
    assert (
        keyring.get_password(KEYRING_SERVICE, _record_key(storage_token_id))
        is None
    ), (
        "per-dispatch token leaked into the keyring backend; routing "
        "should have placed it in transient_tokens only."
    )

    # Self-access contract: the Implementer's get_dispatch_result calls
    # _enforce_self_dispatch_access with the storage's token_id and the
    # caller's agent_id. The revoked SQLite-backed record must resolve
    # via lookup_by_token_id (which returns revoked records — load-
    # bearing per audit production-row 4) and the agent_id comparison
    # must succeed for the dispatch's own Implementer.
    await _enforce_self_dispatch_access(
        token_store=token_store,
        storage_token_id=storage_token_id,
        caller_agent_id="impl-agent-id",
    )

    # Negative pin: a different caller_agent_id must NOT pass the
    # ownership check, even though the record itself resolves. Without
    # this pin the positive assertion above could silently pass for a
    # broken lookup_by_token_id that returned a non-None placeholder.
    with pytest.raises(OrchestratorPermissionError):
        await _enforce_self_dispatch_access(
            token_store=token_store,
            storage_token_id=storage_token_id,
            caller_agent_id="someone-else",
        )
