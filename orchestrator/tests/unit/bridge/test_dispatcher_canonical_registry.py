"""Regression guards for M5-canonical-clone-mgmt slice 2.

Pins the async-factory + per-call canonical resolution seam that Slice 2
introduced:

- :meth:`BridgeDispatcher.create` is the construction surface.
- :meth:`BridgeDispatcher.__init__` no longer accepts ``canonical:`` —
  the legacy kwarg raises ``TypeError``. Same for ``create()``.
- ``dispatch()`` resolves the canonical via
  ``self._canonical_registry.get_default()`` once per call.
- The process-wide ``_dispatcher_owned_canonicals`` guard registers the
  default canonical's path during ``create()`` and a second factory
  invocation against the same registry trips ``RuntimeError``.

These guards canary the Slice 4 seam: when ``dispatch.canonical_name``
becomes load-bearing, ``get_default()`` will be replaced with
``get_by_name(canonical_name)``. The per-call resolution test ensures the
plumbing is exercised end-to-end, not bypassed via a cached field on the
dispatcher instance.
"""

from __future__ import annotations

import shutil
import subprocess
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any

import pytest
import pytest_asyncio

from meridian_orchestrator.bridge.canonical import CanonicalClone
from meridian_orchestrator.bridge.canonical_registry import CanonicalCloneRegistry
from meridian_orchestrator.bridge.dispatcher import (
    BridgeDispatcher,
    _dispatcher_owned_canonicals,
)
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage

pytestmark = pytest.mark.skipif(
    shutil.which("git") is None,
    reason="git is not on PATH (canonical clone requires real git)",
)


class _SpyTokenStore:
    """Minimal TokenStore stand-in: records ``issue()`` kwargs and raises
    immediately. Mirrors the spy in
    ``tests/unit/bridge/test_token_indexed_kwarg.py``. Lets the
    dispatcher's per-call canonical resolution be observed without the
    rest of the dispatch lifecycle executing.
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
        raise RuntimeError("spy: short-circuit after recording issue kwargs")


def _git(*args: str, cwd: Path) -> None:
    subprocess.run(["git", *args], cwd=str(cwd), check=True, capture_output=True)


@pytest.fixture
def upstream_repo(tmp_path: Path) -> Path:
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
    c = CanonicalClone(
        path=tmp_path / "canonical",
        upstream_url=str(upstream_repo),
    )
    await c.ensure_provisioned()
    return c


async def _make_registry(
    storage: SqliteStorage, canonical: CanonicalClone
) -> CanonicalCloneRegistry:
    registry = CanonicalCloneRegistry(storage=storage)
    await registry.ensure_default(
        path=canonical.path, upstream_url=canonical.upstream_url
    )
    return registry


@pytest.mark.asyncio
async def test_constructor_accepts_registry_field(
    storage_with_task: SqliteStorage,
    canonical: CanonicalClone,
    tmp_path: Path,
) -> None:
    """``BridgeDispatcher.create`` stores the registry as
    ``_canonical_registry`` and drops the legacy ``_canonical`` instance
    field. Slice 2 of M5-canonical-clone-mgmt removes per-instance
    canonical state — every dispatch resolves through the registry.
    """
    registry = await _make_registry(storage_with_task, canonical)
    try:
        dispatcher = await BridgeDispatcher.create(
            storage=storage_with_task,
            token_store=_SpyTokenStore(),  # type: ignore[arg-type]
            canonical_registry=registry,
            worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        )

        assert dispatcher._canonical_registry is registry
        assert not hasattr(dispatcher, "_canonical"), (
            "BridgeDispatcher instance still carries the legacy `_canonical` "
            "field; Slice 2 removed it in favor of per-call resolution via "
            "`_canonical_registry.get_default()`. If this attribute was added "
            "back deliberately, update the Slice 2 spec's Item 1 first."
        )
    finally:
        _dispatcher_owned_canonicals.discard(canonical.path)


@pytest.mark.asyncio
async def test_constructor_rejects_legacy_canonical_kwarg(
    storage_with_task: SqliteStorage,
    canonical: CanonicalClone,
    tmp_path: Path,
) -> None:
    """Bare ``BridgeDispatcher(canonical=...)`` and the factory
    ``BridgeDispatcher.create(canonical=...)`` both raise ``TypeError``
    because neither signature accepts the legacy keyword.

    Prevents a future refactor from silently restoring the per-instance
    canonical field. The spec is explicit: the construction API is
    ``await BridgeDispatcher.create(canonical_registry=...)`` only.
    """
    with pytest.raises(TypeError):
        BridgeDispatcher(  # type: ignore[call-arg]
            storage=storage_with_task,
            token_store=_SpyTokenStore(),  # type: ignore[arg-type]
            canonical=canonical,
            worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        )

    with pytest.raises(TypeError):
        await BridgeDispatcher.create(  # type: ignore[call-arg]
            storage=storage_with_task,
            token_store=_SpyTokenStore(),  # type: ignore[arg-type]
            canonical=canonical,
            worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        )


@pytest.mark.asyncio
async def test_dispatch_resolves_canonical_via_get_default_per_call(
    storage_with_task: SqliteStorage,
    canonical: CanonicalClone,
    tmp_path: Path,
) -> None:
    """``dispatch()`` calls ``self._canonical_registry.get_default()``
    once per invocation.

    Canary for Slice 4: when ``get_default()`` is replaced with
    ``get_by_name(dispatch.canonical_name)``, this test pins the seam so
    a refactor that bypasses the registry (e.g., caching the resolved
    canonical on the dispatcher instance) surfaces immediately.

    The spy token store raises after ``issue()`` so the dispatcher
    short-circuits before fetching / provisioning the worktree. The
    per-call resolution happens at the top of ``dispatch()``, before
    token issuance — so even with the short-circuit the assertion holds.
    """
    registry = await _make_registry(storage_with_task, canonical)

    get_default_calls = 0
    original_get_default = registry.get_default

    async def counting_get_default() -> CanonicalClone:
        nonlocal get_default_calls
        get_default_calls += 1
        return await original_get_default()

    registry.get_default = counting_get_default  # type: ignore[method-assign]

    try:
        dispatcher = await BridgeDispatcher.create(
            storage=storage_with_task,
            token_store=_SpyTokenStore(),  # type: ignore[arg-type]
            canonical_registry=registry,
            worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        )
        # `create()` itself awaits `get_default()` once for the guard
        # registration; reset the counter so the assertion below
        # measures the dispatch() call's resolution count in isolation.
        assert get_default_calls == 1
        get_default_calls = 0

        with pytest.raises(RuntimeError, match="spy: short-circuit"):
            await dispatcher.dispatch(
                task_id="task-1",
                role="implementer",
                role_agent_id="impl-agent-id",
                prompt="ignored",
                actor="eng-agent-id",
            )

        assert get_default_calls == 1, (
            "dispatch() must resolve the canonical via "
            "`self._canonical_registry.get_default()` exactly once per "
            "invocation. A count of 0 means the dispatcher is reading "
            "cached state instead of the registry — Slice 4's per-dispatch "
            "routing depends on the registry being consulted on every "
            "dispatch. A count > 1 means duplicate lookups; the "
            "_merge_and_record seam should accept canonical as a "
            "parameter, not re-resolve via the registry."
        )
    finally:
        _dispatcher_owned_canonicals.discard(canonical.path)


@pytest.mark.asyncio
async def test_dispatcher_owned_canonicals_populated_after_create(
    storage_with_task: SqliteStorage,
    canonical: CanonicalClone,
    tmp_path: Path,
) -> None:
    """``create()`` registers the default canonical's path in the
    process-wide guard set, and a second factory invocation against the
    same registry raises ``RuntimeError`` with the existing message.

    The guard exists because the per-instance ``asyncio.Lock`` on
    canonical-mutating operations only serializes within one dispatcher;
    two dispatchers against the same canonical would race on
    ``git worktree add``, ``merge --ff-only``, and ``push origin``.
    """
    registry = await _make_registry(storage_with_task, canonical)
    try:
        dispatcher = await BridgeDispatcher.create(
            storage=storage_with_task,
            token_store=_SpyTokenStore(),  # type: ignore[arg-type]
            canonical_registry=registry,
            worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        )
        assert canonical.path in _dispatcher_owned_canonicals
        assert dispatcher._canonical_registry is registry

        with pytest.raises(RuntimeError, match="another dispatcher already targets"):
            await BridgeDispatcher.create(
                storage=storage_with_task,
                token_store=_SpyTokenStore(),  # type: ignore[arg-type]
                canonical_registry=registry,
                worktrees_root=tmp_path / "data" / "dispatch-worktrees-2",
            )
    finally:
        _dispatcher_owned_canonicals.discard(canonical.path)
