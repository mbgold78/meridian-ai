"""End-to-end serial-dispatch lifecycle test (M4a; exercises M4b merge protocol via single-dispatch path).

Drives :class:`BridgeDispatcher` through one Implementer dispatch using:

- A real local git repo as the substrate upstream (tmp_path/upstream)
- A real canonical clone (tmp_path/canonical) cloning that upstream
- A real per-dispatch worktree (tmp_path/worktrees/<dispatch_id>)
- A *fake* Claude Code subprocess that, on launch, makes a real commit inside
  the worktree and then calls ``complete_dispatch`` via the in-process pure
  handler. The fake exits with rc=0 once ``complete_dispatch`` has landed.

The full lifecycle covered: dispatch row transitions queued → running →
completed; canonical clone provisioned and refreshed; worktree provisioned
and cleaned up; per-dispatch token issued and then revoked; full audit
chain written. With M4b shipping the merge protocol, the happy-path test
also exercises ``--ff-only`` merge into canonical, push to upstream, and
``actual_touched_files_json`` population — so this file is broader than its
M4a name implies. Parallel-dispatch and merge-conflict scenarios live in
``test_dispatch_parallel_and_merge.py``.

Skipped if ``git`` is not on PATH on the host running the test.
"""

from __future__ import annotations

import asyncio
import shutil
import subprocess
from collections.abc import AsyncIterator
from pathlib import Path

import pytest
import pytest_asyncio

from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.bridge.canonical import CanonicalClone
from meridian_orchestrator.bridge.canonical_registry import (
    CanonicalCloneRegistry,
    CanonicalNotFoundError,
)
from meridian_orchestrator.bridge.dispatcher import (
    BridgeDispatcher,
    _dispatcher_owned_canonicals,
    DispatchLaunchContext,
    SubprocessHandle,
)
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.tools.dispatch import (
    CompleteDispatchInput,
    complete_dispatch,
)

pytestmark = pytest.mark.skipif(
    shutil.which("git") is None, reason="git is not on PATH"
)


def _git(*args: str, cwd: Path) -> None:
    subprocess.run(["git", *args], cwd=str(cwd), check=True, capture_output=True)


@pytest.fixture
def upstream_repo(tmp_path: Path) -> Path:
    """A non-bare git repo simulating Mike's substrate upstream.

    Has one commit on ``main`` so the canonical clone can fast-forward
    against ``origin/main`` after fetch.
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
    await s.execute("INSERT INTO companies (id, name) VALUES ('co1', 'Meridian AI')")
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


class _FakeSubprocess:
    """Minimal SubprocessHandle the dispatcher accepts.

    Set the ``returncode`` directly to simulate exit; ``wait()`` resolves on
    the internal exit event. ``terminate``/``kill`` set returncode and signal
    the event so the dispatcher's terminate-on-finally path completes.
    """

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
    """Make a real commit on the dispatch branch and return the new sha."""
    _git("config", "user.email", "test@example.com", cwd=worktree)
    _git("config", "user.name", "Test", cwd=worktree)
    (worktree / "feature.txt").write_text("dispatch wrote this\n", encoding="utf-8")
    _git("add", "feature.txt", cwd=worktree)
    _git("commit", "-m", message, cwd=worktree)
    sha = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=str(worktree),
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    return sha


async def test_serial_implementer_dispatch_completes(
    storage: SqliteStorage, upstream_repo: Path, tmp_path: Path
) -> None:
    canonical = CanonicalClone(
        path=tmp_path / "data" / "substrate-canonical",
        upstream_url=str(upstream_repo),
        default_branch="main",
    )
    await canonical.ensure_provisioned()
    token_store = TokenStore()

    fake_proc = _FakeSubprocess()
    background: list[asyncio.Task[None]] = []

    async def fake_launcher(ctx: DispatchLaunchContext) -> SubprocessHandle:
        async def simulate() -> None:
            # Let the dispatcher's await loop start polling.
            await asyncio.sleep(0)
            commit_sha = await asyncio.to_thread(
                _commit_in_worktree, ctx.working_directory, "M4a: feature.txt"
            )
            await complete_dispatch(
                storage=storage,
                token_store=token_store,
                request=CompleteDispatchInput(
                    dispatch_id=ctx.dispatch_id,
                    status="completed",
                    commit_hash=commit_sha,
                    artifacts=["feature.txt"],
                    notes="M4a serial happy path",
                ),
                caller_agent_id="impl-agent-id",
                caller_role="implementer",
            )
            fake_proc.signal_clean_exit()

        background.append(asyncio.create_task(simulate()))
        return fake_proc

    canonical_registry = CanonicalCloneRegistry(storage=storage)
    await canonical_registry.ensure_default(
        path=canonical.path, upstream_url=canonical.upstream_url
    )
    dispatcher = await BridgeDispatcher.create(
        storage=storage,
        token_store=token_store,
        canonical_registry=canonical_registry,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=fake_launcher,
        poll_interval_seconds=0.02,
    )

    spec_path = tmp_path / "spec.md"
    spec_path.write_text("Implement feature X.\n", encoding="utf-8")

    outcome = await dispatcher.dispatch(
        task_id="task-1",
        role="implementer",
        role_agent_id="impl-agent-id",
        prompt=spec_path.read_text(encoding="utf-8"),
        spec_path_descriptor=spec_path,
        actor="eng-agent-id",
        requested_working_directory=str(upstream_repo),
    )

    assert outcome.status == "completed"
    assert outcome.commit_hash is not None
    assert len(outcome.commit_hash) == 40
    assert outcome.notes == "M4a serial happy path"
    assert outcome.artifacts == ["feature.txt"]

    row = await storage.fetch_one(
        "SELECT status, commit_hash, completed_at, started_at, token_id, "
        "subprocess_pid FROM dispatches WHERE id = ?",
        (outcome.dispatch_id,),
    )
    assert row is not None
    assert row["status"] == "completed"
    assert row["commit_hash"] == outcome.commit_hash
    assert row["completed_at"] is not None
    assert row["started_at"] is not None
    assert row["subprocess_pid"] == 99999

    # The worktree was cleaned up post-completion. Slice 4 C2 nested
    # the path layout under `<canonical_name>/`; no-arg dispatches
    # resolve to 'default' so the worktree lived at
    # `<root>/default/<dispatch_id>/` until cleanup.
    worktree_path = (
        tmp_path / "data" / "dispatch-worktrees" / "default" / outcome.dispatch_id
    )
    assert not worktree_path.exists()

    # The token was revoked.
    assert row["token_id"] is not None
    revoked = await token_store.lookup_by_token_id(row["token_id"])
    assert revoked is not None
    assert revoked.revoked_at is not None

    # Full audit chain is present and ordered.
    audit = await storage.fetch_all(
        "SELECT action, actor FROM audit_log "
        "WHERE target = ? ORDER BY timestamp, rowid",
        (outcome.dispatch_id,),
    )
    actions = [a["action"] for a in audit]
    assert actions[0] == "dispatch.issued"
    assert "dispatch.token_created" in actions
    assert "dispatch.subprocess_started" in actions
    assert "dispatch.complete_called" in actions
    assert "dispatch.token_revoked" in actions
    assert actions[-1] == "dispatch.subprocess_terminated"
    # The Engineer's agent_id is the actor on dispatch.issued.
    issued = next(a for a in audit if a["action"] == "dispatch.issued")
    assert issued["actor"] == "eng-agent-id"


async def test_subprocess_exits_without_complete_dispatch_marks_failed(
    storage: SqliteStorage, upstream_repo: Path, tmp_path: Path
) -> None:
    """If the subprocess exits before complete_dispatch, the dispatch is failed."""
    canonical = CanonicalClone(
        path=tmp_path / "data" / "substrate-canonical",
        upstream_url=str(upstream_repo),
        default_branch="main",
    )
    await canonical.ensure_provisioned()
    token_store = TokenStore()

    fake_proc = _FakeSubprocess()
    background: list[asyncio.Task[None]] = []

    async def fake_launcher(ctx: DispatchLaunchContext) -> SubprocessHandle:
        async def simulate() -> None:
            # Exit immediately without calling complete_dispatch.
            await asyncio.sleep(0)
            fake_proc.signal_clean_exit()

        background.append(asyncio.create_task(simulate()))
        return fake_proc

    canonical_registry = CanonicalCloneRegistry(storage=storage)
    await canonical_registry.ensure_default(
        path=canonical.path, upstream_url=canonical.upstream_url
    )
    dispatcher = await BridgeDispatcher.create(
        storage=storage,
        token_store=token_store,
        canonical_registry=canonical_registry,
        worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        subprocess_launcher=fake_launcher,
        poll_interval_seconds=0.02,
    )

    spec_path = tmp_path / "spec.md"
    spec_path.write_text("never completes.\n", encoding="utf-8")

    outcome = await dispatcher.dispatch(
        task_id="task-1",
        role="implementer",
        role_agent_id="impl-agent-id",
        prompt=spec_path.read_text(encoding="utf-8"),
        spec_path_descriptor=spec_path,
        actor="eng-agent-id",
    )

    assert outcome.status == "failed"
    row = await storage.fetch_one(
        "SELECT status, notes FROM dispatches WHERE id = ?",
        (outcome.dispatch_id,),
    )
    assert row is not None
    assert row["status"] == "failed"
    assert row["notes"] is not None
    assert "without complete_dispatch" in row["notes"]


# ----- M5-canonical-clone-mgmt slice 4 — I2, I3, I4 -----------------------


async def test_dispatch_without_canonical_name_writes_default_literal(
    storage: SqliteStorage, upstream_repo: Path, tmp_path: Path
) -> None:
    """I2 — backward-compat. A dispatch with no ``canonical_name`` kwarg
    produces ``dispatches.canonical_name='default'`` (Ratification #4
    explicit literal), and ``dispatch.issued`` + ``dispatch.merged``
    audit payloads carry ``canonical_name='default'`` (Ratification #2
    minimal-4). Operator-pin #5 — existing callers route through the
    new code path identically to pre-Slice-4."""
    import json as _json

    canonical = CanonicalClone(
        path=tmp_path / "data" / "substrate-canonical",
        upstream_url=str(upstream_repo),
        default_branch="main",
    )
    await canonical.ensure_provisioned()
    token_store = TokenStore()

    fake_proc = _FakeSubprocess()
    background: list[asyncio.Task[None]] = []

    async def fake_launcher(ctx: DispatchLaunchContext) -> SubprocessHandle:
        async def simulate() -> None:
            await asyncio.sleep(0)
            commit_sha = await asyncio.to_thread(
                _commit_in_worktree, ctx.working_directory, "slice4 I2"
            )
            await complete_dispatch(
                storage=storage,
                token_store=token_store,
                request=CompleteDispatchInput(
                    dispatch_id=ctx.dispatch_id,
                    status="completed",
                    commit_hash=commit_sha,
                    notes="I2 default",
                ),
                caller_agent_id="impl-agent-id",
                caller_role="implementer",
            )
            fake_proc.signal_clean_exit()

        background.append(asyncio.create_task(simulate()))
        return fake_proc

    canonical_registry = CanonicalCloneRegistry(storage=storage)
    await canonical_registry.ensure_default(
        path=canonical.path, upstream_url=canonical.upstream_url
    )
    try:
        dispatcher = await BridgeDispatcher.create(
            storage=storage,
            token_store=token_store,
            canonical_registry=canonical_registry,
            worktrees_root=tmp_path / "data" / "dispatch-worktrees",
            subprocess_launcher=fake_launcher,
            poll_interval_seconds=0.02,
        )

        outcome = await dispatcher.dispatch(
            task_id="task-1",
            role="implementer",
            role_agent_id="impl-agent-id",
            prompt="ignored",
            actor="eng-agent-id",
            # NOTE: canonical_name kwarg omitted — preserves pre-Slice-4
            # caller shape. The dispatcher's resolver swap interprets
            # None as get_default(); the INSERT writes the explicit
            # 'default' literal per Ratification #4.
        )

        assert outcome.status == "completed"
        row = await storage.fetch_one(
            "SELECT canonical_name FROM dispatches WHERE id = ?",
            (outcome.dispatch_id,),
        )
        assert row is not None
        assert row["canonical_name"] == "default"

        # Audit payload on dispatch.issued contains the field with the
        # resolved literal.
        issued = await storage.fetch_one(
            "SELECT details_json FROM audit_log "
            "WHERE target = ? AND action = 'dispatch.issued'",
            (outcome.dispatch_id,),
        )
        assert issued is not None
        issued_details = _json.loads(issued["details_json"])
        assert issued_details["canonical_name"] == "default"

        # Audit payload on dispatch.merged contains the field too —
        # _merge_and_record threading symmetric to Slice 2's canonical
        # parameter pattern.
        merged = await storage.fetch_one(
            "SELECT details_json FROM audit_log "
            "WHERE target = ? AND action = 'dispatch.merged'",
            (outcome.dispatch_id,),
        )
        assert merged is not None
        merged_details = _json.loads(merged["details_json"])
        assert merged_details["canonical_name"] == "default"
    finally:
        _dispatcher_owned_canonicals.discard(canonical.path)


async def test_dispatch_unknown_canonical_name_raises_and_inserts_nothing(
    storage: SqliteStorage, upstream_repo: Path, tmp_path: Path
) -> None:
    """I3 — operator-pin #2. ``canonical_name='never-registered'`` raises
    ``CanonicalNotFoundError`` (verbatim per Ratification #3, stable
    code 'CANONICAL_NOT_FOUND'); the dispatcher's resolver fails BEFORE
    any side effect:

    - No ``dispatches`` row inserted (negative DB assert).
    - No ``dispatch.issued`` audit row written (negative audit assert).
    - No token issued.
    - No worktree directory created.
    """
    canonical = CanonicalClone(
        path=tmp_path / "data" / "substrate-canonical",
        upstream_url=str(upstream_repo),
        default_branch="main",
    )
    await canonical.ensure_provisioned()
    token_store = TokenStore()

    async def fake_launcher(ctx: DispatchLaunchContext) -> SubprocessHandle:
        raise AssertionError(
            "launcher must not run on the CanonicalNotFoundError path"
        )

    canonical_registry = CanonicalCloneRegistry(storage=storage)
    await canonical_registry.ensure_default(
        path=canonical.path, upstream_url=canonical.upstream_url
    )
    worktrees_root = tmp_path / "data" / "dispatch-worktrees"
    try:
        dispatcher = await BridgeDispatcher.create(
            storage=storage,
            token_store=token_store,
            canonical_registry=canonical_registry,
            worktrees_root=worktrees_root,
            subprocess_launcher=fake_launcher,
            poll_interval_seconds=0.02,
        )

        before_count = await storage.fetch_one(
            "SELECT COUNT(*) AS n FROM dispatches"
        )
        assert before_count is not None
        baseline_dispatches = int(before_count["n"])

        with pytest.raises(CanonicalNotFoundError) as exc_info:
            await dispatcher.dispatch(
                task_id="task-1",
                role="implementer",
                role_agent_id="impl-agent-id",
                prompt="ignored",
                actor="eng-agent-id",
                canonical_name="never-registered",
            )

        # Stable error code (Ratification #3).
        assert exc_info.value.code == "CANONICAL_NOT_FOUND"

        # Negative DB assert: no dispatch row added.
        after_count = await storage.fetch_one(
            "SELECT COUNT(*) AS n FROM dispatches"
        )
        assert after_count is not None
        assert int(after_count["n"]) == baseline_dispatches

        # Negative audit assert: no dispatch.* audit row.
        audit = await storage.fetch_all(
            "SELECT action FROM audit_log WHERE action LIKE 'dispatch.%'"
        )
        assert audit == []

        # Worktrees root either absent or empty (no dispatch_id minted).
        assert not worktrees_root.exists() or not any(worktrees_root.iterdir())
    finally:
        _dispatcher_owned_canonicals.discard(canonical.path)


async def test_dispatch_with_reserved_default_name_routes_identically(
    storage: SqliteStorage, upstream_repo: Path, tmp_path: Path
) -> None:
    """I4 — operator-pin #1. ``canonical_name='default'`` succeeds and
    resolves identically to the no-arg case (I2). Slice 4 Ratification
    #5 — reserved name 'default' is callable on dispatch (callability ≠
    register/unregister-ability)."""
    import json as _json

    canonical = CanonicalClone(
        path=tmp_path / "data" / "substrate-canonical",
        upstream_url=str(upstream_repo),
        default_branch="main",
    )
    await canonical.ensure_provisioned()
    token_store = TokenStore()

    fake_proc = _FakeSubprocess()
    background: list[asyncio.Task[None]] = []

    async def fake_launcher(ctx: DispatchLaunchContext) -> SubprocessHandle:
        async def simulate() -> None:
            await asyncio.sleep(0)
            commit_sha = await asyncio.to_thread(
                _commit_in_worktree, ctx.working_directory, "slice4 I4"
            )
            await complete_dispatch(
                storage=storage,
                token_store=token_store,
                request=CompleteDispatchInput(
                    dispatch_id=ctx.dispatch_id,
                    status="completed",
                    commit_hash=commit_sha,
                    notes="I4 explicit default",
                ),
                caller_agent_id="impl-agent-id",
                caller_role="implementer",
            )
            fake_proc.signal_clean_exit()

        background.append(asyncio.create_task(simulate()))
        return fake_proc

    canonical_registry = CanonicalCloneRegistry(storage=storage)
    await canonical_registry.ensure_default(
        path=canonical.path, upstream_url=canonical.upstream_url
    )
    try:
        dispatcher = await BridgeDispatcher.create(
            storage=storage,
            token_store=token_store,
            canonical_registry=canonical_registry,
            worktrees_root=tmp_path / "data" / "dispatch-worktrees",
            subprocess_launcher=fake_launcher,
            poll_interval_seconds=0.02,
        )

        outcome = await dispatcher.dispatch(
            task_id="task-1",
            role="implementer",
            role_agent_id="impl-agent-id",
            prompt="ignored",
            actor="eng-agent-id",
            canonical_name="default",
        )

        assert outcome.status == "completed"
        row = await storage.fetch_one(
            "SELECT canonical_name FROM dispatches WHERE id = ?",
            (outcome.dispatch_id,),
        )
        assert row is not None
        assert row["canonical_name"] == "default"

        issued = await storage.fetch_one(
            "SELECT details_json FROM audit_log "
            "WHERE target = ? AND action = 'dispatch.issued'",
            (outcome.dispatch_id,),
        )
        assert issued is not None
        issued_details = _json.loads(issued["details_json"])
        assert issued_details["canonical_name"] == "default"

        merged = await storage.fetch_one(
            "SELECT details_json FROM audit_log "
            "WHERE target = ? AND action = 'dispatch.merged'",
            (outcome.dispatch_id,),
        )
        assert merged is not None
        merged_details = _json.loads(merged["details_json"])
        assert merged_details["canonical_name"] == "default"
    finally:
        _dispatcher_owned_canonicals.discard(canonical.path)


# ----- M5-canonical-clone-mgmt slice 4 (C2) — I1 named-canonical lifecycle ----


@pytest.fixture
def substrate_upstream_repo(tmp_path: Path) -> Path:
    """Second upstream simulating Mike's substrate-canonical upstream.

    Independent of `upstream_repo` so the named-canonical dispatch's
    merge lands on the substrate upstream and the default-canonical's
    HEAD remains observably untouched (I1 pin assertion (d)+(e)).
    """
    upstream = tmp_path / "substrate-upstream"
    upstream.mkdir()
    _git("init", "-b", "main", cwd=upstream)
    _git("config", "user.email", "test@example.com", cwd=upstream)
    _git("config", "user.name", "Test", cwd=upstream)
    _git("config", "receive.denyCurrentBranch", "ignore", cwd=upstream)
    (upstream / "SUBSTRATE.md").write_text("substrate root\n", encoding="utf-8")
    _git("add", "SUBSTRATE.md", cwd=upstream)
    _git("commit", "-m", "substrate initial", cwd=upstream)
    return upstream


async def test_dispatch_against_named_substrate_canonical_lifecycle(
    storage: SqliteStorage,
    upstream_repo: Path,
    substrate_upstream_repo: Path,
    tmp_path: Path,
) -> None:
    """I1 — full lifecycle against a NAMED canonical (operator-pin #3).

    Two upstream repos in tmp_path; the registry registers both as
    `default` + `substrate`. Engineer dispatches Implementer against
    `substrate`. Verifies:

    (a) dispatch row has `canonical_name='substrate'` (DB SELECT).
    (b) worktree path is `<root>/substrate/<dispatch_id>/` (filesystem
        path captured in the dispatches row, since the directory itself
        is cleaned up post-completion).
    (c) `dispatch.issued` audit payload contains `canonical_name='substrate'`.
    (d) merge lands on the substrate canonical's main (substrate-upstream
        HEAD advances).
    (e) the default canonical's upstream HEAD is untouched.

    Operator-pin #4 (nested worktree path includes canonical name) is
    exercised by leg (b).
    """
    import json as _json

    default_canonical = CanonicalClone(
        path=tmp_path / "data" / "substrate-canonical",
        upstream_url=str(upstream_repo),
        default_branch="main",
    )
    await default_canonical.ensure_provisioned()
    substrate_canonical = CanonicalClone(
        path=tmp_path / "data" / "substrate-named-canonical",
        upstream_url=str(substrate_upstream_repo),
        default_branch="main",
    )
    await substrate_canonical.ensure_provisioned()

    # Capture pre-dispatch upstream HEADs to verify (d)+(e).
    default_upstream_head_before = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=str(upstream_repo),
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    substrate_upstream_head_before = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=str(substrate_upstream_repo),
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()

    token_store = TokenStore()
    fake_proc = _FakeSubprocess()
    background: list[asyncio.Task[None]] = []

    async def fake_launcher(ctx: DispatchLaunchContext) -> SubprocessHandle:
        async def simulate() -> None:
            await asyncio.sleep(0)
            commit_sha = await asyncio.to_thread(
                _commit_in_worktree, ctx.working_directory, "I1 substrate dispatch"
            )
            await complete_dispatch(
                storage=storage,
                token_store=token_store,
                request=CompleteDispatchInput(
                    dispatch_id=ctx.dispatch_id,
                    status="completed",
                    commit_hash=commit_sha,
                    notes="I1 named canonical",
                ),
                caller_agent_id="impl-agent-id",
                caller_role="implementer",
            )
            fake_proc.signal_clean_exit()

        background.append(asyncio.create_task(simulate()))
        return fake_proc

    canonical_registry = CanonicalCloneRegistry(storage=storage)
    await canonical_registry.ensure_default(
        path=default_canonical.path, upstream_url=default_canonical.upstream_url
    )
    await canonical_registry.register(
        name="substrate",
        path=substrate_canonical.path,
        upstream_url=substrate_canonical.upstream_url,
    )
    worktrees_root = tmp_path / "data" / "dispatch-worktrees"
    try:
        dispatcher = await BridgeDispatcher.create(
            storage=storage,
            token_store=token_store,
            canonical_registry=canonical_registry,
            worktrees_root=worktrees_root,
            subprocess_launcher=fake_launcher,
            poll_interval_seconds=0.02,
        )

        outcome = await dispatcher.dispatch(
            task_id="task-1",
            role="implementer",
            role_agent_id="impl-agent-id",
            prompt="ignored",
            actor="eng-agent-id",
            canonical_name="substrate",
        )

        # Leg (a): dispatch row carries the resolved name.
        assert outcome.status == "completed"
        row = await storage.fetch_one(
            "SELECT canonical_name, working_directory FROM dispatches "
            "WHERE id = ?",
            (outcome.dispatch_id,),
        )
        assert row is not None
        assert row["canonical_name"] == "substrate"

        # Leg (b): worktree path nested under `<root>/substrate/<id>/`.
        # Directory itself cleaned up post-completion; the dispatches row
        # captures the working_directory used at dispatch time.
        expected_worktree = worktrees_root / "substrate" / outcome.dispatch_id
        assert Path(row["working_directory"]) == expected_worktree
        assert not expected_worktree.exists()

        # Leg (c): dispatch.issued audit payload contains the field +
        # the nested working_directory.
        issued = await storage.fetch_one(
            "SELECT details_json FROM audit_log "
            "WHERE target = ? AND action = 'dispatch.issued'",
            (outcome.dispatch_id,),
        )
        assert issued is not None
        issued_details = _json.loads(issued["details_json"])
        assert issued_details["canonical_name"] == "substrate"
        assert (
            Path(issued_details["working_directory"])
            == worktrees_root / "substrate" / outcome.dispatch_id
        )

        # Leg (d): substrate upstream advanced — merge landed on its main.
        substrate_upstream_head_after = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=str(substrate_upstream_repo),
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
        assert substrate_upstream_head_after != substrate_upstream_head_before, (
            "substrate upstream HEAD should advance after a substrate-dispatch "
            "merge; canonical-name routing landed the merge on the wrong upstream"
        )

        # Leg (e): default upstream is untouched.
        default_upstream_head_after = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=str(upstream_repo),
            capture_output=True,
            text=True,
            check=True,
        ).stdout.strip()
        assert default_upstream_head_after == default_upstream_head_before, (
            "default upstream HEAD must NOT advance when the dispatch was "
            "explicitly routed to the substrate canonical; cross-canonical "
            "contamination indicates the resolver swap is broken"
        )
    finally:
        _dispatcher_owned_canonicals.discard(default_canonical.path)
        _dispatcher_owned_canonicals.discard(substrate_canonical.path)
