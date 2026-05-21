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
from meridian_orchestrator.bridge.canonical_registry import (
    CanonicalCloneRegistry,
    CanonicalNotFoundError,
)
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


# ----- M5-canonical-clone-mgmt slice 4 -----------------------------------
#
# U2 — resolver swap (None → get_default; named → get_by_name; missing →
# CanonicalNotFoundError propagates; no row/token/worktree on failure).
# U3 — _insert_dispatch_row writes the resolved name (explicit literal
# per Ratification #4, not column DEFAULT reliance).
# U6 — dispatch.issued audit payload contains the canonical_name field.
#
# All three exercise the dispatcher up to (but not including) token
# issuance by injecting `_SpyTokenStore.issue()` which raises after
# recording its kwargs. The resolver swap, INSERT, and dispatch.issued
# audit all happen BEFORE token issuance, so the spy lets us observe
# their effects from storage without provisioning a worktree.


@pytest.mark.asyncio
async def test_dispatch_resolver_uses_get_default_when_canonical_name_is_none(
    storage_with_task: SqliteStorage,
    canonical: CanonicalClone,
    tmp_path: Path,
) -> None:
    """``dispatch(canonical_name=None)`` resolves via ``registry.get_default()``
    once. Verifies no call to ``get_by_name`` (other than the one
    ``get_default()`` itself makes internally — but the swap should not
    call the public ``get_by_name`` directly).
    """
    registry = await _make_registry(storage_with_task, canonical)

    get_default_calls = 0
    get_by_name_calls: list[str] = []
    original_get_default = registry.get_default
    original_get_by_name = registry.get_by_name

    async def counting_get_default() -> CanonicalClone:
        nonlocal get_default_calls
        get_default_calls += 1
        return await original_get_default()

    async def counting_get_by_name(name: str) -> CanonicalClone:
        get_by_name_calls.append(name)
        return await original_get_by_name(name)

    registry.get_default = counting_get_default  # type: ignore[method-assign]
    registry.get_by_name = counting_get_by_name  # type: ignore[method-assign]

    try:
        dispatcher = await BridgeDispatcher.create(
            storage=storage_with_task,
            token_store=_SpyTokenStore(),  # type: ignore[arg-type]
            canonical_registry=registry,
            worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        )
        get_default_calls = 0
        get_by_name_calls.clear()

        with pytest.raises(RuntimeError, match="spy: short-circuit"):
            await dispatcher.dispatch(
                task_id="task-1",
                role="implementer",
                role_agent_id="impl-agent-id",
                prompt="ignored",
                actor="eng-agent-id",
                canonical_name=None,
            )

        assert get_default_calls == 1
        # get_by_name may be called by get_default()'s own internal
        # implementation (Slice 1 has get_default() delegate to
        # get_by_name("default")). The Slice 4 resolver swap MUST NOT
        # call get_by_name directly when canonical_name is None.
        # Discriminator: when the swap chose get_default(), the public
        # get_by_name call (if any) must be for "default" only, never
        # for an arbitrary canonical_name string.
        assert all(n == "default" for n in get_by_name_calls)
    finally:
        _dispatcher_owned_canonicals.discard(canonical.path)


@pytest.mark.asyncio
async def test_dispatch_resolver_uses_get_by_name_when_canonical_name_provided(
    storage_with_task: SqliteStorage,
    canonical: CanonicalClone,
    tmp_path: Path,
) -> None:
    """``dispatch(canonical_name='default')`` resolves via
    ``registry.get_by_name('default')``. The default canonical row
    matches the lookup (per Slice 1's registry contract). The swap
    must NOT call ``get_default()`` when canonical_name is non-None
    (the two paths are mutually exclusive per § 3b)."""
    registry = await _make_registry(storage_with_task, canonical)

    get_default_calls = 0
    get_by_name_calls: list[str] = []
    original_get_default = registry.get_default
    original_get_by_name = registry.get_by_name

    async def counting_get_default() -> CanonicalClone:
        nonlocal get_default_calls
        get_default_calls += 1
        return await original_get_default()

    async def counting_get_by_name(name: str) -> CanonicalClone:
        get_by_name_calls.append(name)
        return await original_get_by_name(name)

    registry.get_default = counting_get_default  # type: ignore[method-assign]
    registry.get_by_name = counting_get_by_name  # type: ignore[method-assign]

    try:
        dispatcher = await BridgeDispatcher.create(
            storage=storage_with_task,
            token_store=_SpyTokenStore(),  # type: ignore[arg-type]
            canonical_registry=registry,
            worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        )
        get_default_calls = 0
        get_by_name_calls.clear()

        with pytest.raises(RuntimeError, match="spy: short-circuit"):
            await dispatcher.dispatch(
                task_id="task-1",
                role="implementer",
                role_agent_id="impl-agent-id",
                prompt="ignored",
                actor="eng-agent-id",
                canonical_name="default",
            )

        assert get_default_calls == 0, (
            "dispatch(canonical_name='default') must not call get_default(); "
            "the resolver swap routes the explicit name through get_by_name."
        )
        assert get_by_name_calls == ["default"]
    finally:
        _dispatcher_owned_canonicals.discard(canonical.path)


@pytest.mark.asyncio
async def test_dispatch_propagates_canonical_not_found(
    storage_with_task: SqliteStorage,
    canonical: CanonicalClone,
    tmp_path: Path,
) -> None:
    """``dispatch(canonical_name='never-registered')`` raises
    ``CanonicalNotFoundError`` verbatim (Ratification #3 — no
    try/except wrap). On this path: NO dispatch row insert, NO token
    issued, NO worktree directory provisioned, NO dispatch.issued
    audit row. The resolver fails BEFORE any of these side-effects."""
    registry = await _make_registry(storage_with_task, canonical)

    worktrees_root = tmp_path / "data" / "dispatch-worktrees"

    spy = _SpyTokenStore()
    try:
        dispatcher = await BridgeDispatcher.create(
            storage=storage_with_task,
            token_store=spy,  # type: ignore[arg-type]
            canonical_registry=registry,
            worktrees_root=worktrees_root,
        )

        with pytest.raises(CanonicalNotFoundError):
            await dispatcher.dispatch(
                task_id="task-1",
                role="implementer",
                role_agent_id="impl-agent-id",
                prompt="ignored",
                actor="eng-agent-id",
                canonical_name="never-registered",
            )

        # No dispatch row inserted.
        rows = await storage_with_task.fetch_all("SELECT id FROM dispatches")
        assert rows == []

        # No token issued.
        assert spy.issue_calls == []

        # No dispatch.issued audit row.
        audit = await storage_with_task.fetch_all(
            "SELECT action FROM audit_log WHERE action LIKE 'dispatch.%'"
        )
        assert audit == []

        # No worktree directory provisioned. The worktree path layout
        # is `<root>/<dispatch_id>/` at C1 (pre-C2); the dispatch_id
        # was never minted, so the root either doesn't exist or exists
        # empty.
        assert not worktrees_root.exists() or not any(worktrees_root.iterdir())
    finally:
        _dispatcher_owned_canonicals.discard(canonical.path)


@pytest.mark.asyncio
async def test_insert_dispatch_row_writes_resolved_name(
    storage_with_task: SqliteStorage,
    canonical: CanonicalClone,
    tmp_path: Path,
) -> None:
    """U3: ``_insert_dispatch_row`` writes the explicit resolved name
    into ``dispatches.canonical_name``, per Ratification #4. The spy
    raises after recording token issuance kwargs (which fires AFTER
    INSERT) so the dispatcher's INSERT lands but the rest of the
    lifecycle short-circuits. Verifies both the named-canonical and
    None-canonical (default literal) paths."""
    # Register an additional named canonical for the named-route test.
    named_path = tmp_path / "named-canonical"
    await canonical.ensure_provisioned()
    # Use the same upstream URL and provisioned path object trick:
    # for the INSERT test we only need a row that exists; the
    # dispatcher's resolver re-reads it and re-fetches against the
    # path. To keep the test cheap, register a row whose path is the
    # same as the default canonical's (the SELECTed row drives the
    # resolved CanonicalClone, but the test doesn't progress past
    # INSERT into the fetch/worktree step).
    registry = await _make_registry(storage_with_task, canonical)
    await registry.register(
        name="named-c",
        path=canonical.path,
        upstream_url=canonical.upstream_url,
    )

    # Suppress the second dispatcher's owned-canonical-guard collision
    # (Slice 2 guard) — the second dispatch path here reuses the same
    # canonical path that the first dispatcher registered.
    try:
        dispatcher = await BridgeDispatcher.create(
            storage=storage_with_task,
            token_store=_SpyTokenStore(),  # type: ignore[arg-type]
            canonical_registry=registry,
            worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        )

        # Path 1: canonical_name=None → resolved literal "default".
        with pytest.raises(RuntimeError, match="spy: short-circuit"):
            await dispatcher.dispatch(
                task_id="task-1",
                role="implementer",
                role_agent_id="impl-agent-id",
                prompt="ignored",
                actor="eng-agent-id",
                canonical_name=None,
            )

        # Path 2: canonical_name="named-c" → resolved literal "named-c".
        with pytest.raises(RuntimeError, match="spy: short-circuit"):
            await dispatcher.dispatch(
                task_id="task-1",
                role="implementer",
                role_agent_id="impl-agent-id",
                prompt="ignored",
                actor="eng-agent-id",
                canonical_name="named-c",
            )

        # Path 3: canonical_name="default" → resolved literal "default".
        with pytest.raises(RuntimeError, match="spy: short-circuit"):
            await dispatcher.dispatch(
                task_id="task-1",
                role="implementer",
                role_agent_id="impl-agent-id",
                prompt="ignored",
                actor="eng-agent-id",
                canonical_name="default",
            )

        rows = await storage_with_task.fetch_all(
            "SELECT canonical_name FROM dispatches ORDER BY issued_at, rowid"
        )
        assert [r["canonical_name"] for r in rows] == [
            "default",
            "named-c",
            "default",
        ]
    finally:
        _dispatcher_owned_canonicals.discard(canonical.path)


@pytest.mark.asyncio
async def test_dispatch_issued_audit_payload_contains_canonical_name(
    storage_with_task: SqliteStorage,
    canonical: CanonicalClone,
    tmp_path: Path,
) -> None:
    """U6 (subset): ``dispatch.issued`` audit payload contains a
    ``canonical_name`` field equal to the resolved name. Ratification #2
    minimal-4 — this kind is one of the four that gain the field. The
    other three (``dispatch.merged``, ``dispatch.merge_conflict``,
    ``dispatch.canonical_diverged``) fire later in the lifecycle and
    are exercised through the full-lifecycle integration tests in
    ``tests/integration/test_dispatch_lifecycle.py`` (I2/I4) and
    ``tests/integration/test_dispatch_parallel_and_merge.py``."""
    import json as _json

    registry = await _make_registry(storage_with_task, canonical)
    await registry.register(
        name="named-c",
        path=canonical.path,
        upstream_url=canonical.upstream_url,
    )

    try:
        dispatcher = await BridgeDispatcher.create(
            storage=storage_with_task,
            token_store=_SpyTokenStore(),  # type: ignore[arg-type]
            canonical_registry=registry,
            worktrees_root=tmp_path / "data" / "dispatch-worktrees",
        )

        with pytest.raises(RuntimeError, match="spy: short-circuit"):
            await dispatcher.dispatch(
                task_id="task-1",
                role="implementer",
                role_agent_id="impl-agent-id",
                prompt="ignored",
                actor="eng-agent-id",
                canonical_name="named-c",
            )

        with pytest.raises(RuntimeError, match="spy: short-circuit"):
            await dispatcher.dispatch(
                task_id="task-1",
                role="implementer",
                role_agent_id="impl-agent-id",
                prompt="ignored",
                actor="eng-agent-id",
                canonical_name=None,
            )

        audit_rows = await storage_with_task.fetch_all(
            "SELECT details_json FROM audit_log WHERE action = 'dispatch.issued' "
            "ORDER BY timestamp, rowid"
        )
        payloads = [_json.loads(r["details_json"]) for r in audit_rows]
        assert len(payloads) == 2
        assert payloads[0]["canonical_name"] == "named-c"
        assert payloads[1]["canonical_name"] == "default"
    finally:
        _dispatcher_owned_canonicals.discard(canonical.path)
