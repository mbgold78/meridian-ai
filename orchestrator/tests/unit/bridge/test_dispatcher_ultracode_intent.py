"""Unit tests for the dispatcher's per-project ULTRACODE resolution from
the Control Plane autonomy-intent snapshot (op-ultracode-daemon).

The dispatcher reads each project's ULTRACODE flag — keyed by the
dispatch's canonical-clone name — from the SAME ``GET /api/daemon/intent``
seam the liveness watchdog uses, REUSING
``bridge.autonomy_intent.fetch_autonomy_intent`` behind a small per-instance
TTL cache (no new background poller). The resolution fails CLOSED: any CP
uncertainty (URL unset, fetch failure, canonical unknown) yields
ultracode-off so the launched ``claude`` argv stays subscription-only.

Two layers of coverage:

A. ``_get_autonomy_intent`` / ``_ultracode_enabled_for`` unit behavior —
   cache reuse (one fetch per TTL window), fail-closed when no URL, and
   correct ON/OFF resolution by canonical. These need no git / real
   subprocess; they monkeypatch the module-level ``fetch_autonomy_intent``.

B. End-to-end dispatch argv — a real :class:`BridgeDispatcher` runs a full
   ``dispatch()`` with a capturing ``subprocess_launcher`` and the
   monkeypatched intent fetch; the captured ``DispatchLaunchContext`` must
   carry ``ultracode_enabled=True`` ONLY when the snapshot says ON for the
   dispatch's canonical, and the resulting argv contains / omits the
   ``--settings '{"ultracode": true}'`` token accordingly.
"""

from __future__ import annotations

import shutil
import subprocess
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any

import pytest
import pytest_asyncio

from meridian_orchestrator.bridge import dispatcher as disp_mod
from meridian_orchestrator.bridge.autonomy_intent import (
    AutonomyIntentSnapshot,
    ProjectIntent,
)
from meridian_orchestrator.bridge.canonical import CanonicalClone
from meridian_orchestrator.bridge.canonical_registry import CanonicalCloneRegistry
from meridian_orchestrator.bridge.dispatcher import (
    BridgeDispatcher,
    DispatchLaunchContext,
    SubprocessHandle,
)
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage

pytestmark = pytest.mark.skipif(
    shutil.which("git") is None,
    reason="git is not on PATH (canonical clone requires real git)",
)

_ULTRACODE_JSON = '{"ultracode": true}'
CP_URL = "http://cp.local"


# ---------------------------------------------------------------------------
# Fixtures (mirror test_token_indexed_kwarg.py's real-canonical pattern)
# ---------------------------------------------------------------------------


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


@pytest_asyncio.fixture
async def canonical_registry(
    storage_with_task: SqliteStorage, canonical: CanonicalClone
) -> CanonicalCloneRegistry:
    reg = CanonicalCloneRegistry(storage=storage_with_task)
    await reg.ensure_default(
        path=canonical.path, upstream_url=canonical.upstream_url
    )
    return reg


def _patch_fetch_returning(
    monkeypatch: pytest.MonkeyPatch,
    snapshot: AutonomyIntentSnapshot | None,
) -> dict[str, int]:
    """Monkeypatch the dispatcher module's ``fetch_autonomy_intent`` to
    return ``snapshot``. Returns a mutable counter recording call count so
    cache-reuse can be asserted."""
    calls = {"n": 0}

    async def _fake_fetch(**_kwargs: Any) -> AutonomyIntentSnapshot | None:
        calls["n"] += 1
        return snapshot

    monkeypatch.setattr(disp_mod, "fetch_autonomy_intent", _fake_fetch)
    return calls


# ---------------------------------------------------------------------------
# A. _get_autonomy_intent / _ultracode_enabled_for unit behavior
# ---------------------------------------------------------------------------


async def test_ultracode_enabled_for_resolves_on_by_canonical(
    storage_with_task: SqliteStorage,
    canonical_registry: CanonicalCloneRegistry,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A snapshot with ultracode=True for the canonical resolves True."""
    snap = AutonomyIntentSnapshot(
        by_canonical={
            "curasync": ProjectIntent(
                project_id="proj1",
                autonomy="Continuous",
                callback_armed=True,
                ultracode=True,
                canonical="curasync",
            )
        }
    )
    _patch_fetch_returning(monkeypatch, snap)
    dispatcher = await BridgeDispatcher.create(
        storage=storage_with_task,
        token_store=object(),  # type: ignore[arg-type]  # never used on this path
        canonical_registry=canonical_registry,
        worktrees_root=tmp_path / "wt",
        control_plane_url=CP_URL,
    )
    assert await dispatcher._ultracode_enabled_for("curasync") is True
    # Unknown canonical -> fail closed.
    assert await dispatcher._ultracode_enabled_for("other") is False
    assert await dispatcher._ultracode_enabled_for(None) is False


async def test_ultracode_enabled_for_fail_closed_when_no_url(
    storage_with_task: SqliteStorage,
    canonical_registry: CanonicalCloneRegistry,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """No control_plane_url -> never fetches; every canonical fails closed."""
    calls = _patch_fetch_returning(
        monkeypatch,
        AutonomyIntentSnapshot(
            by_canonical={
                "curasync": ProjectIntent(
                    project_id="proj1",
                    autonomy="Continuous",
                    callback_armed=True,
                    ultracode=True,
                    canonical="curasync",
                )
            }
        ),
    )
    dispatcher = await BridgeDispatcher.create(
        storage=storage_with_task,
        token_store=object(),  # type: ignore[arg-type]
        canonical_registry=canonical_registry,
        worktrees_root=tmp_path / "wt",
        control_plane_url=None,
    )
    assert await dispatcher._ultracode_enabled_for("curasync") is False
    assert calls["n"] == 0, "no URL must skip the CP fetch entirely"


async def test_ultracode_enabled_for_fail_closed_when_fetch_returns_none(
    storage_with_task: SqliteStorage,
    canonical_registry: CanonicalCloneRegistry,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A None snapshot (CP unreachable / non-2xx / malformed — all swallowed
    by fetch_autonomy_intent) -> fail closed."""
    _patch_fetch_returning(monkeypatch, None)
    dispatcher = await BridgeDispatcher.create(
        storage=storage_with_task,
        token_store=object(),  # type: ignore[arg-type]
        canonical_registry=canonical_registry,
        worktrees_root=tmp_path / "wt",
        control_plane_url=CP_URL,
    )
    assert await dispatcher._ultracode_enabled_for("curasync") is False


async def test_intent_snapshot_cached_within_ttl(
    storage_with_task: SqliteStorage,
    canonical_registry: CanonicalCloneRegistry,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The snapshot is fetched at most once per TTL window — repeated
    lookups reuse the cache rather than spawning a new fetch (no poller,
    bounded CP round-trips)."""
    snap = AutonomyIntentSnapshot(
        by_canonical={
            "curasync": ProjectIntent(
                project_id="proj1",
                autonomy="Continuous",
                callback_armed=True,
                ultracode=True,
                canonical="curasync",
            )
        }
    )
    calls = _patch_fetch_returning(monkeypatch, snap)
    dispatcher = await BridgeDispatcher.create(
        storage=storage_with_task,
        token_store=object(),  # type: ignore[arg-type]
        canonical_registry=canonical_registry,
        worktrees_root=tmp_path / "wt",
        control_plane_url=CP_URL,
        intent_cache_ttl_seconds=300.0,
    )
    assert await dispatcher._ultracode_enabled_for("curasync") is True
    assert await dispatcher._ultracode_enabled_for("curasync") is True
    assert await dispatcher._ultracode_enabled_for("other") is False
    assert calls["n"] == 1, "snapshot must be cached within the TTL window"


# ---------------------------------------------------------------------------
# B. End-to-end dispatch argv: --settings token presence by snapshot state
# ---------------------------------------------------------------------------


class _CapturingLauncher:
    """subprocess_launcher that records the ctx + the argv the production
    default launcher would build, then short-circuits the lifecycle so the
    test stays fast (no real claude subprocess)."""

    def __init__(self) -> None:
        self.ctx: DispatchLaunchContext | None = None

    async def __call__(self, ctx: DispatchLaunchContext) -> SubprocessHandle:
        self.ctx = ctx
        raise RuntimeError("test: short-circuit after ctx captured")


def _argv_for(ctx: DispatchLaunchContext) -> list[str]:
    """Reconstruct the leading argv the default launcher composes for this
    ctx (the ultracode-relevant prefix). Mirrors the launcher's conditional
    so the dispatch-boundary assertion is in argv terms, not just the bool."""
    argv: list[str] = ["claude", "--effort", ctx.effort]
    if ctx.ultracode_enabled:
        argv.extend(["--settings", _ULTRACODE_JSON])
    return argv


async def _run_dispatch_capture(
    *,
    storage: SqliteStorage,
    canonical_registry: CanonicalCloneRegistry,
    tmp_path: Path,
    control_plane_url: str | None,
) -> DispatchLaunchContext:
    # token_store.issue is called BEFORE the launcher, so supply a real
    # TransientTokenStore-backed TokenStore; the dispatch then reaches the
    # capturing launcher, which records the ctx and short-circuits the rest
    # of the lifecycle.
    from meridian_orchestrator.auth import TokenStore
    from meridian_orchestrator.transient_tokens import TransientTokenStore

    launcher = _CapturingLauncher()
    dispatcher = await BridgeDispatcher.create(
        storage=storage,
        token_store=TokenStore(transient_store=TransientTokenStore(storage)),
        canonical_registry=canonical_registry,
        worktrees_root=tmp_path / "wt",
        subprocess_launcher=launcher,
        control_plane_url=control_plane_url,
    )
    with pytest.raises(RuntimeError, match="test: short-circuit"):
        await dispatcher.dispatch(
            task_id="task-1",
            role="implementer",
            role_agent_id="impl-agent-id",
            prompt="do the thing",
            actor="eng-agent-id",
        )
    assert launcher.ctx is not None
    return launcher.ctx


async def test_dispatch_argv_has_settings_when_canonical_ultracode_on(
    storage_with_task: SqliteStorage,
    canonical_registry: CanonicalCloneRegistry,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """End-to-end: the default canonical resolves to 'default'; a snapshot
    that turns ultracode ON for 'default' makes the dispatch carry the
    --settings token."""
    snap = AutonomyIntentSnapshot(
        by_canonical={
            "default": ProjectIntent(
                project_id="proj1",
                autonomy="Continuous",
                callback_armed=True,
                ultracode=True,
                canonical="default",
            )
        }
    )
    _patch_fetch_returning(monkeypatch, snap)
    ctx = await _run_dispatch_capture(
        storage=storage_with_task,
        canonical_registry=canonical_registry,
        tmp_path=tmp_path,
        control_plane_url=CP_URL,
    )
    assert ctx.ultracode_enabled is True
    assert "--settings" in _argv_for(ctx)
    assert _ULTRACODE_JSON in _argv_for(ctx)


async def test_dispatch_argv_omits_settings_when_canonical_ultracode_off(
    storage_with_task: SqliteStorage,
    canonical_registry: CanonicalCloneRegistry,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Snapshot present but ultracode OFF for the canonical -> no token."""
    snap = AutonomyIntentSnapshot(
        by_canonical={
            "default": ProjectIntent(
                project_id="proj1",
                autonomy="Continuous",
                callback_armed=True,
                ultracode=False,
                canonical="default",
            )
        }
    )
    _patch_fetch_returning(monkeypatch, snap)
    ctx = await _run_dispatch_capture(
        storage=storage_with_task,
        canonical_registry=canonical_registry,
        tmp_path=tmp_path,
        control_plane_url=CP_URL,
    )
    assert ctx.ultracode_enabled is False
    assert "--settings" not in _argv_for(ctx)


async def test_dispatch_argv_fail_closed_when_snapshot_unavailable(
    storage_with_task: SqliteStorage,
    canonical_registry: CanonicalCloneRegistry,
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """CP unreachable (fetch returns None) -> dispatch fails closed to
    ultracode-off; argv stays subscription-only."""
    _patch_fetch_returning(monkeypatch, None)
    ctx = await _run_dispatch_capture(
        storage=storage_with_task,
        canonical_registry=canonical_registry,
        tmp_path=tmp_path,
        control_plane_url=CP_URL,
    )
    assert ctx.ultracode_enabled is False
    assert "--settings" not in _argv_for(ctx)
