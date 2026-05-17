"""End-to-end M5-wrapper-closure integration tests.

Exercises the five dispatch wrappers registered in ``server.py`` against a
real Streamable-HTTP MCP server. Engineer calls ``dispatch_implementer``
over the wire; the fake Implementer subprocess calls ``complete_dispatch``
over the wire; Engineer reads ``get_dispatch_status`` and
``get_dispatch_result`` over the wire. The full M4 dispatch machinery
(canonical clone, worktree, lifecycle, merge protocol) runs underneath.

Skipped if ``git`` is not on PATH on the host running the test.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import shutil
import socket
import subprocess
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from pathlib import Path

import pytest
import pytest_asyncio
from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client

from meridian_orchestrator.agents.registry import AgentRegistry
from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.bridge.canonical import CanonicalClone
from meridian_orchestrator.bridge.dispatcher import (
    BridgeDispatcher,
    DispatchLaunchContext,
    SubprocessHandle,
)
from meridian_orchestrator.bridge.timeouts import (
    HARD_TIMEOUT_FLOOR_SECONDS,
    MODEL_THROUGHPUT_TPS,
)
from meridian_orchestrator.config import Settings
from meridian_orchestrator.server import build_server
from meridian_orchestrator.server import serve as serve_mcp
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage

pytestmark = pytest.mark.skipif(
    shutil.which("git") is None, reason="git is not on PATH"
)

REPO_CONFIG_DIR = Path(__file__).resolve().parents[2] / "config" / "agents"


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


def _git(*args: str, cwd: Path) -> None:
    subprocess.run(
        ["git", *args], cwd=str(cwd), check=True, capture_output=True
    )


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _seed_repo_yamls(into_dir: Path) -> None:
    into_dir.mkdir(parents=True, exist_ok=True)
    for role in ("pm", "engineer", "implementer", "tester"):
        (into_dir / f"{role}.yaml").write_text(
            (REPO_CONFIG_DIR / f"{role}.yaml").read_text(encoding="utf-8"),
            encoding="utf-8",
        )


@pytest.fixture
def upstream_repo(tmp_path: Path) -> Path:
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
    # Seed a task with a high estimated_tokens so calculate_timeout
    # produces a value clearly above the 300s floor — lets test 5
    # assert the math against MODEL_THROUGHPUT_TPS.
    await s.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description, estimated_tokens) "
        "VALUES ('task-1', 'co1', 'proj1', 'implementer', 'no-op', 240000)"
    )
    yield s
    await s.close()


@pytest.fixture
def settings(tmp_path: Path, upstream_repo: Path) -> Settings:
    return Settings(
        data_dir=tmp_path,
        mcp_host="127.0.0.1",
        mcp_port=_free_port(),
        substrate_working_directory=upstream_repo,
    )


@pytest_asyncio.fixture
async def registry_with_tokens(
    settings: Settings,
) -> AsyncIterator[tuple[AgentRegistry, TokenStore]]:
    """Build a registry with engineer/implementer/tester static tokens
    issued so registry.get(role) resolves to a real RegisteredAgent."""
    _seed_repo_yamls(settings.agents_dir)
    token_store = TokenStore()
    for role in ("engineer", "implementer", "tester"):
        await token_store.issue(agent_id=None, role=role, label=f"{role}-test")
    registry = AgentRegistry(settings.agents_dir, token_store=token_store)
    async with registry.lifespan():
        yield registry, token_store


# ---------------------------------------------------------------------------
# Fake subprocess that calls complete_dispatch over MCP
# ---------------------------------------------------------------------------


class _FakeProc:
    """SubprocessHandle whose returncode is controlled by the test."""

    def __init__(self) -> None:
        self._returncode: int | None = None
        self._exit = asyncio.Event()

    @property
    def pid(self) -> int | None:
        return 4242

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
        if self._returncode is None:
            self._returncode = -9
            self._exit.set()

    def signal_clean_exit(self) -> None:
        if self._returncode is None:
            self._returncode = 0
            self._exit.set()


def _make_implementer_launcher(
    *, base_url: str, worktree_commit_sha: str | None = None
):
    """Return a launcher whose 'subprocess' makes a real worktree commit
    and then calls complete_dispatch over MCP using the issued per-dispatch
    token.

    Matches the test_dispatch_lifecycle.py launcher pattern, but the
    complete_dispatch call lands over the live MCP server rather than via
    direct handler invocation.
    """
    background: list[asyncio.Task[object]] = []

    async def launcher(ctx: DispatchLaunchContext) -> SubprocessHandle:
        proc = _FakeProc()

        async def simulate() -> None:
            # Make a real commit in the worktree so the merge protocol
            # has something to land. Mirror the test_dispatch_lifecycle
            # commit pattern.
            cwd = ctx.working_directory
            (cwd / "phase.txt").write_text(
                f"dispatch={ctx.dispatch_id}\n", encoding="utf-8"
            )
            await asyncio.to_thread(_git, "add", "phase.txt", cwd=cwd)
            await asyncio.to_thread(
                _git, "commit", "-m", "phase", cwd=cwd
            )
            commit_sha = await asyncio.to_thread(
                lambda: subprocess.check_output(
                    ["git", "rev-parse", "HEAD"], cwd=str(cwd)
                )
                .decode()
                .strip()
            )

            await _call_complete_dispatch(
                base_url=base_url,
                token=ctx.token,
                dispatch_id=ctx.dispatch_id,
                status="completed",
                commit_hash=commit_sha,
                notes="ok",
            )
            proc.signal_clean_exit()

        background.append(asyncio.create_task(simulate(), name="fake-impl"))
        return proc

    return launcher, background


# ---------------------------------------------------------------------------
# MCP client helpers
# ---------------------------------------------------------------------------


async def _call_tool(
    url: str, token: str, name: str, args: dict[str, object]
) -> object:
    headers = {"Authorization": f"Bearer {token}"}
    async with (
        streamablehttp_client(url, headers=headers) as (read, write, _),
        ClientSession(read, write) as session,
    ):
        await session.initialize()
        return await session.call_tool(name, args)


async def _call_complete_dispatch(
    *,
    base_url: str,
    token: str,
    dispatch_id: str,
    status: str,
    commit_hash: str | None = None,
    notes: str | None = None,
) -> None:
    url = f"{base_url}/mcp"
    args: dict[str, object] = {"dispatch_id": dispatch_id, "status": status}
    if commit_hash is not None:
        args["commit_hash"] = commit_hash
    if notes is not None:
        args["notes"] = notes
    await _call_tool(url, token, "complete_dispatch", args)


def _structured(result: object) -> dict[str, object]:
    structured = getattr(result, "structuredContent", None)
    if structured is not None:
        return dict(structured)
    text = result.content[0].text  # type: ignore[union-attr]
    return dict(json.loads(text))


# ---------------------------------------------------------------------------
# Server harness
# ---------------------------------------------------------------------------


@asynccontextmanager
async def _running_server(
    settings: Settings,
    storage: SqliteStorage,
    registry: AgentRegistry,
    dispatcher: BridgeDispatcher | None,
) -> AsyncIterator[None]:
    ready = asyncio.Event()

    def get_dispatcher() -> BridgeDispatcher | None:
        return dispatcher

    task = asyncio.create_task(
        serve_mcp(
            settings,
            ready=ready,
            registry=registry,
            storage=storage,
            get_dispatcher=get_dispatcher,
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


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------


async def test_engineer_dispatch_implementer_full_lifecycle_over_mcp(
    settings: Settings,
    storage: SqliteStorage,
    upstream_repo: Path,
    registry_with_tokens: tuple[AgentRegistry, TokenStore],
    tmp_path: Path,
) -> None:
    """Test 1 of the prep: Engineer dispatches Implementer over the wire.

    Engineer's MCP call to dispatch_implementer blocks until the fake
    Implementer subprocess (also calling MCP, via complete_dispatch)
    lands a terminal state. Then Engineer reads get_dispatch_status and
    get_dispatch_result over the wire and verifies the audit chain.
    """
    registry, token_store = registry_with_tokens
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    # Provision canonical and dispatcher with the fake launcher.
    canonical = CanonicalClone(
        path=settings.substrate_canonical_dir,
        upstream_url=str(upstream_repo),
    )
    await canonical.ensure_provisioned()
    settings.dispatch_worktrees_root.mkdir(parents=True, exist_ok=True)

    launcher, _bg = _make_implementer_launcher(base_url=base_url)
    dispatcher = BridgeDispatcher(
        storage=storage,
        token_store=token_store,
        canonical=canonical,
        worktrees_root=settings.dispatch_worktrees_root,
        subprocess_launcher=launcher,
        poll_interval_seconds=0.02,
    )

    # Spec file the Implementer subprocess would read. M4 reads via the
    # daemon (handler reads spec_path on dispatch); content is opaque to
    # this test.
    spec_path = tmp_path / "phase-spec.md"
    spec_path.write_text("# Phase\nApply the change.\n", encoding="utf-8")

    eng = registry.get("engineer")
    assert eng is not None
    eng_token = await token_store.issue(
        agent_id=eng.agent_id, role="engineer", label="engineer-supervisor"
    )

    try:
        async with _running_server(settings, storage, registry, dispatcher):
            # Engineer dispatches over MCP. This blocks until the fake
            # Implementer's complete_dispatch call lands.
            disp_result = await _call_tool(
                f"{base_url}/mcp",
                eng_token.token,
                "dispatch_implementer",
                {
                    "task_id": "task-1",
                    "spec_path": str(spec_path),
                    "working_directory": str(upstream_repo),
                },
            )
            disp_out = _structured(disp_result)
            assert disp_out["status"] == "completed"
            assert disp_out["commit_hash"] is not None
            dispatch_id = disp_out["dispatch_id"]

            # get_dispatch_status (post-terminal: lifecycle fields populated)
            status_result = await _call_tool(
                f"{base_url}/mcp",
                eng_token.token,
                "get_dispatch_status",
                {"dispatch_id": dispatch_id},
            )
            status_out = _structured(status_result)
            assert status_out["status"] == "completed"
            assert status_out["role"] == "implementer"
            assert status_out["completed_at"] is not None

            # get_dispatch_result (post-terminal: structured outcome)
            result_result = await _call_tool(
                f"{base_url}/mcp",
                eng_token.token,
                "get_dispatch_result",
                {"dispatch_id": dispatch_id},
            )
            result_out = _structured(result_result)
            assert result_out["status"] == "completed"
            assert result_out["notes"] == "ok"
    finally:
        with contextlib.suppress(KeyError):
            from meridian_orchestrator.bridge.dispatcher import (
                _dispatcher_owned_canonicals,
            )
            _dispatcher_owned_canonicals.discard(canonical.path)

    # Audit chain present.
    audit = await storage.fetch_all(
        "SELECT action FROM audit_log WHERE target = ? ORDER BY rowid",
        (dispatch_id,),
    )
    actions = {a["action"] for a in audit}
    assert "dispatch.issued" in actions
    assert "dispatch.token_created" in actions
    assert "dispatch.subprocess_started" in actions
    assert "dispatch.complete_called" in actions
    assert "dispatch.token_revoked" in actions


async def test_record_caller_activity_writer_fires_for_wrappers(
    settings: Settings,
    storage: SqliteStorage,
    registry_with_tokens: tuple[AgentRegistry, TokenStore],
) -> None:
    """Test 2: dispatches.last_activity_at and last_tool_called update
    when wrappers are invoked under a token whose token_id matches an
    active non-terminal dispatch row.
    """
    registry, token_store = registry_with_tokens
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    eng = registry.get("engineer")
    assert eng is not None
    eng_token = await token_store.issue(
        agent_id=eng.agent_id, role="engineer", label="engineer-supervisor"
    )

    # Pre-seed an engineer-supervisor dispatch row whose token_id is the
    # engineer token (mimicking the supervisor lifecycle row). The
    # activity writer keys off (token_id, status NOT terminal).
    await storage.execute(
        "INSERT INTO dispatches (id, role, status, working_directory, "
        "timeout_seconds, token_id) "
        "VALUES ('eng-sup-row', 'engineer-supervisor', 'running', "
        "'/tmp/wt', 1800, ?)",
        (eng_token.record.token_id,),
    )

    async with _running_server(settings, storage, registry, dispatcher=None):
        # Call a wrapper that does not need the dispatcher (query tool).
        await _call_tool(
            f"{base_url}/mcp",
            eng_token.token,
            "get_recent_dispatches",
            {"limit": 5},
        )

    row = await storage.fetch_one(
        "SELECT last_activity_at, last_tool_called FROM dispatches "
        "WHERE id = 'eng-sup-row'"
    )
    assert row is not None
    assert row["last_activity_at"] is not None
    assert row["last_tool_called"] == "get_recent_dispatches"


async def test_dispatcher_not_ready_returns_conflict(
    settings: Settings,
    storage: SqliteStorage,
    registry_with_tokens: tuple[AgentRegistry, TokenStore],
    tmp_path: Path,
) -> None:
    """Test 6: wrappers called before dispatcher is constructed return a
    structured ConflictError, not a 500."""
    registry, token_store = registry_with_tokens
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    eng = registry.get("engineer")
    assert eng is not None
    eng_token = await token_store.issue(
        agent_id=eng.agent_id, role="engineer", label="engineer-supervisor"
    )
    spec_path = tmp_path / "phase-spec.md"
    spec_path.write_text("# Phase\n", encoding="utf-8")

    async with _running_server(settings, storage, registry, dispatcher=None):
        result = await _call_tool(
            f"{base_url}/mcp",
            eng_token.token,
            "dispatch_implementer",
            {
                "task_id": "task-1",
                "spec_path": str(spec_path),
                "working_directory": str(tmp_path),
            },
        )

    assert getattr(result, "isError", False) is True
    text = result.content[0].text  # type: ignore[union-attr]
    assert "no dispatcher" in text.lower() or "conflict" in text.lower()


async def test_pm_token_cannot_call_dispatch_implementer(
    settings: Settings,
    storage: SqliteStorage,
    upstream_repo: Path,
    registry_with_tokens: tuple[AgentRegistry, TokenStore],
    tmp_path: Path,
) -> None:
    """Test 3a: PM-role token gets permission-denied at handler
    _require_role; the wrapper does not double-check.

    The dispatcher is constructed (with a fake launcher) so the wrapper
    advances past its dispatcher-readiness check; the handler's
    _require_role is what rejects the PM caller. Without a dispatcher
    the test would surface ConflictError instead, masking the
    permission-enforcement assertion.
    """
    registry, token_store = registry_with_tokens
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    canonical = CanonicalClone(
        path=settings.substrate_canonical_dir,
        upstream_url=str(upstream_repo),
    )
    await canonical.ensure_provisioned()
    settings.dispatch_worktrees_root.mkdir(parents=True, exist_ok=True)
    launcher, _bg = _make_implementer_launcher(base_url=base_url)
    dispatcher = BridgeDispatcher(
        storage=storage,
        token_store=token_store,
        canonical=canonical,
        worktrees_root=settings.dispatch_worktrees_root,
        subprocess_launcher=launcher,
        poll_interval_seconds=0.02,
    )

    pm_token = await token_store.issue(
        agent_id="pm-uuid", role="pm", label="pm-test"
    )
    spec_path = tmp_path / "phase-spec.md"
    spec_path.write_text("# Phase\n", encoding="utf-8")

    try:
        async with _running_server(settings, storage, registry, dispatcher):
            result = await _call_tool(
                f"{base_url}/mcp",
                pm_token.token,
                "dispatch_implementer",
                {
                    "task_id": "task-1",
                    "spec_path": str(spec_path),
                    "working_directory": str(tmp_path),
                },
            )
    finally:
        with contextlib.suppress(KeyError):
            from meridian_orchestrator.bridge.dispatcher import (
                _dispatcher_owned_canonicals,
            )
            _dispatcher_owned_canonicals.discard(canonical.path)

    assert getattr(result, "isError", False) is True
    text = result.content[0].text  # type: ignore[union-attr]
    assert "pm" in text.lower() or "permission" in text.lower()


async def test_pm_token_cannot_call_complete_dispatch(
    settings: Settings,
    storage: SqliteStorage,
    registry_with_tokens: tuple[AgentRegistry, TokenStore],
) -> None:
    """Test 3b: PM-role token cannot call complete_dispatch (impl/tester
    only)."""
    registry, _token_store = registry_with_tokens
    token_store = TokenStore()
    pm_token = await token_store.issue(
        agent_id="pm-uuid", role="pm", label="pm-test"
    )
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    async with _running_server(settings, storage, registry, dispatcher=None):
        result = await _call_tool(
            f"{base_url}/mcp",
            pm_token.token,
            "complete_dispatch",
            {"dispatch_id": "ghost", "status": "completed"},
        )

    assert getattr(result, "isError", False) is True


async def test_dispatch_implementer_resolves_model_via_registry(
    settings: Settings,
    storage: SqliteStorage,
    upstream_repo: Path,
    registry_with_tokens: tuple[AgentRegistry, TokenStore],
    tmp_path: Path,
) -> None:
    """Test 5: assert M4c-tooling timeouts.py wiring is live in
    production. Implementer's tier is 'sonnet'; expected timeout is
    ``max(estimated_tokens // tps, HARD_TIMEOUT_FLOOR_SECONDS)`` where
    tps is the registry-resolved sonnet tps. If MODEL_THROUGHPUT_TPS or
    calculate_timeout's logic ever changes, this test fails informatively.
    """
    registry, token_store = registry_with_tokens
    base_url = f"http://{settings.mcp_host}:{settings.mcp_port}"

    canonical = CanonicalClone(
        path=settings.substrate_canonical_dir,
        upstream_url=str(upstream_repo),
    )
    await canonical.ensure_provisioned()
    settings.dispatch_worktrees_root.mkdir(parents=True, exist_ok=True)

    launcher, _bg = _make_implementer_launcher(base_url=base_url)
    dispatcher = BridgeDispatcher(
        storage=storage,
        token_store=token_store,
        canonical=canonical,
        worktrees_root=settings.dispatch_worktrees_root,
        subprocess_launcher=launcher,
        poll_interval_seconds=0.02,
    )

    spec_path = tmp_path / "phase-spec.md"
    spec_path.write_text("# Phase\n", encoding="utf-8")

    eng = registry.get("engineer")
    assert eng is not None
    eng_token = await token_store.issue(
        agent_id=eng.agent_id, role="engineer", label="engineer-supervisor"
    )

    try:
        async with _running_server(settings, storage, registry, dispatcher):
            disp_result = await _call_tool(
                f"{base_url}/mcp",
                eng_token.token,
                "dispatch_implementer",
                {
                    "task_id": "task-1",
                    "spec_path": str(spec_path),
                    "working_directory": str(upstream_repo),
                },
            )
            disp_out = _structured(disp_result)
            dispatch_id = disp_out["dispatch_id"]
    finally:
        with contextlib.suppress(KeyError):
            from meridian_orchestrator.bridge.dispatcher import (
                _dispatcher_owned_canonicals,
            )
            _dispatcher_owned_canonicals.discard(canonical.path)

    row = await storage.fetch_one(
        "SELECT timeout_seconds FROM dispatches WHERE id = ?", (dispatch_id,)
    )
    assert row is not None
    expected = max(
        240000 // MODEL_THROUGHPUT_TPS["claude-sonnet-4-6"],
        HARD_TIMEOUT_FLOOR_SECONDS,
    )
    assert row["timeout_seconds"] == expected, (
        f"timeout_seconds={row['timeout_seconds']} != "
        f"expected={expected} = max({240000} // "
        f"{MODEL_THROUGHPUT_TPS['claude-sonnet-4-6']}, "
        f"{HARD_TIMEOUT_FLOOR_SECONDS})"
    )


async def test_dispatch_initiation_tools_unregistered_when_dispatcher_absent(
    settings: Settings,
    storage: SqliteStorage,
    registry_with_tokens: tuple[AgentRegistry, TokenStore],
) -> None:
    """build_server with get_dispatcher=None should NOT register
    dispatch_implementer / dispatch_tester. (Symmetric with how
    get_engineer_supervisor=None gates signal_compaction.)"""
    from meridian_orchestrator.auth import TokenStore

    registry, _ = registry_with_tokens
    mcp = build_server(
        settings,
        store=TokenStore(),
        registry=registry,
        storage=storage,
        get_dispatcher=None,
    )
    tools = await mcp.list_tools()
    names = {t.name for t in tools}
    assert "dispatch_implementer" not in names
    assert "dispatch_tester" not in names
    # complete_dispatch / get_dispatch_status / get_dispatch_result do
    # not require a dispatcher and SHOULD be registered.
    assert "complete_dispatch" in names
    assert "get_dispatch_status" in names
    assert "get_dispatch_result" in names


