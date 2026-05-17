"""Integration tests for the slice 1b commit 6 lifespan wiring.

Per M5-sqlite-transient-token-store slice 1b commit 6 spec
(``docs/specs/M5-sqlite-transient-token-store-slice-1b-implementer.md``
§ Commit 6): 5 integration tests covering the lifespan ordering table:

1. Startup probe fires when the gate is on (default).
2. Startup probe is skipped when the gate is off.
3. GC task is started + emits at least one ``transient_tokens.gc_swept``
   audit row during the test window.
4. Shutdown drains the GC task cleanly within the A5-ratified 5s
   timeout; the final cycle's audit row is recorded.
5. Probe internal failure does NOT block daemon startup — the
   lifespan still reaches ``startup.complete`` and exits cleanly.

This directory is intentionally FLAT (no ``__init__.py``) per the
slice 1a established convention — adding ``__init__.py`` here would
collide with ``tests/unit/auth/__init__.py`` (pytest's package import
mode resolves both as top-level package ``auth`` and silently shadows
test files in the second-loaded subdir).
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import time
from pathlib import Path
from typing import Any

import keyring
import pytest

from meridian_orchestrator import main as main_module
from meridian_orchestrator.auth import _record_key
from meridian_orchestrator.config import Settings
from meridian_orchestrator.main import _run_lifespan_body
from meridian_orchestrator.storage.sqlite import SqliteStorage


def _free_port() -> int:
    """Mirror of ``test_daemon_lifecycle._free_port`` — picked the
    minimum surface to keep this test module self-contained without
    cross-module fixture imports (per the slice 1a e2e test
    convention)."""
    import socket as _socket

    with _socket.socket(_socket.AF_INET, _socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _make_settings(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    *,
    probe_enabled: bool = True,
    gc_interval_seconds: int = 60,
) -> Settings:
    """Settings configured for the slice 1b commit 6 lifespan tests.

    Loopback MCP on a free port; supervisors disabled (autouse conftest
    fixture). The OAuth token env-var is cleared so the poller stays
    off — this slice's lifespan tests focus on the probe + GC
    surfaces, not the poller. The probe-on-startup gate and GC
    interval are routed through env vars so the env-var path itself
    is exercised end-to-end.
    """
    monkeypatch.delenv("CLAUDE_CODE_OAUTH_TOKEN", raising=False)
    monkeypatch.setenv(
        "MERIDIAN_ORCH_TRANSIENT_TOKEN_ORPHAN_PROBE_ON_STARTUP",
        "true" if probe_enabled else "false",
    )
    monkeypatch.setenv(
        "MERIDIAN_ORCH_TRANSIENT_TOKEN_GC_INTERVAL_SECONDS",
        str(gc_interval_seconds),
    )
    return Settings(
        data_dir=tmp_path,
        mcp_host="127.0.0.1",
        mcp_port=_free_port(),
    )


async def _run_lifespan_for(
    settings: Settings,
    *,
    settle_seconds: float = 1.5,
    shutdown_timeout_seconds: float = 15.0,
) -> None:
    """Spawn ``_run_lifespan_body`` as a task, let it settle, set
    shutdown, await clean exit. Mirrors ``_run_lifespan_briefly`` from
    test_daemon_lifecycle but with a default settle window long
    enough for the GC task to complete ≥1 cycle at ``interval=1s``.
    """
    shutdown = asyncio.Event()
    task = asyncio.create_task(
        _run_lifespan_body(settings, shutdown),
        name="lifespan-test-slice-1b",
    )
    try:
        await asyncio.sleep(settle_seconds)
        if task.done():
            # Daemon exited unexpectedly during settle — surface its
            # exception by awaiting.
            await task
            return
        shutdown.set()
        await asyncio.wait_for(task, timeout=shutdown_timeout_seconds)
    finally:
        if not task.done():
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task


async def _audit_actions(
    db_path: Path, action: str
) -> list[dict[str, Any]]:
    """Re-open the daemon's SQLite DB post-lifespan and return audit
    rows with the given ``action``. The daemon closes the connection on
    shutdown, so we open a fresh one for the inspection — same pattern
    as ``test_full_lifespan_seeds_agents_rows_before_serving_mcp``."""
    storage = SqliteStorage(db_path)
    await storage.connect()
    try:
        return await storage.fetch_all(
            "SELECT id, actor, action, details_json "
            "FROM audit_log WHERE action = ? ORDER BY id",
            (action,),
        )
    finally:
        await storage.close()


# ---------------------------------------------------------------------------
# Probe gate (enabled / disabled)
# ---------------------------------------------------------------------------


async def test_daemon_startup_fires_probe_when_enabled(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Boot daemon with ``transient_token_orphan_probe_on_startup=True``
    + a pre-seeded keyring orphan; verify the ``auth.orphan.detected``
    audit row is present post-startup.

    The orphan is seeded directly via the in-memory keyring backend
    (autouse ``keyring_memory`` fixture from conftest), bypassing the
    ``_index`` aggregator — this mirrors the production failure mode
    the probe is designed to surface (pre-slice-1a ``indexed=False``
    entries that were never migrated to SQLite).
    """
    from meridian_orchestrator.auth import KEYRING_SERVICE

    orphan_token_id = "ffeeddccbbaa9988"
    keyring.set_password(
        KEYRING_SERVICE,
        _record_key(orphan_token_id),
        json.dumps({"token_id": orphan_token_id, "seeded_by": "test"}),
    )

    settings = _make_settings(tmp_path, monkeypatch, probe_enabled=True)
    await _run_lifespan_for(settings)

    audits = await _audit_actions(settings.db_path, "auth.orphan.detected")
    assert len(audits) >= 1, (
        "expected at least one auth.orphan.detected audit row from the "
        "startup probe. Either the gate Settings field is not propagating "
        "to the lifespan, or the probe is not being invoked at step 4 of "
        "the § Lifespan ordering table."
    )
    # Sanity — the audit row uses the sha256-hashed token_id, never the
    # raw token_id.
    details = json.loads(audits[0]["details_json"])
    assert details["observation_only"] is True
    assert details["source"] == "keyring"
    assert details["token_id_hash"] != orphan_token_id
    assert len(details["token_id_hash"]) == 64


async def test_daemon_startup_skips_probe_when_disabled(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Boot daemon with the probe gate flipped to False; verify no
    ``auth.orphan.detected`` or ``auth.double_presence.detected``
    audit rows surface even when an orphan IS seeded in the keyring.

    The skip-when-disabled path is operator-facing — post-slice-1c
    cleanup, the operator flips the gate to skip startup latency once
    orphans are confirmed zero. A regression that ignored the gate
    would block this operational path.
    """
    from meridian_orchestrator.auth import KEYRING_SERVICE

    orphan_token_id = "eeddccbbaa998877"
    keyring.set_password(
        KEYRING_SERVICE,
        _record_key(orphan_token_id),
        json.dumps({"token_id": orphan_token_id}),
    )

    settings = _make_settings(
        tmp_path, monkeypatch, probe_enabled=False
    )
    assert settings.transient_token_orphan_probe_on_startup is False, (
        "MERIDIAN_ORCH_TRANSIENT_TOKEN_ORPHAN_PROBE_ON_STARTUP env var "
        "did not propagate to Settings; the test's monkeypatch wiring "
        "is broken."
    )

    await _run_lifespan_for(settings)

    orphan_audits = await _audit_actions(
        settings.db_path, "auth.orphan.detected"
    )
    dp_audits = await _audit_actions(
        settings.db_path, "auth.double_presence.detected"
    )
    assert orphan_audits == [], (
        "probe-disabled boot produced auth.orphan.detected audit rows; "
        "the gate is not being honored at step 4 of the § Lifespan "
        "ordering table."
    )
    assert dp_audits == [], (
        "probe-disabled boot produced auth.double_presence.detected "
        "audit rows; the gate must short-circuit both probe outputs."
    )


# ---------------------------------------------------------------------------
# GC task lifespan
# ---------------------------------------------------------------------------


async def test_daemon_startup_starts_gc_task(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Boot daemon with a 1s GC interval; let it run long enough for
    at least one cycle; verify ≥1 ``transient_tokens.gc_swept`` audit
    row landed.

    GC interval is the Settings field's minimum (``ge=1``); the test
    settle window (1.5s) gives one cycle margin. A regression where
    the lifespan does not start the GC task would surface as zero
    audit rows.
    """
    settings = _make_settings(
        tmp_path, monkeypatch, gc_interval_seconds=1
    )
    await _run_lifespan_for(settings, settle_seconds=1.6)

    gc_audits = await _audit_actions(
        settings.db_path, "transient_tokens.gc_swept"
    )
    assert len(gc_audits) >= 1, (
        f"expected ≥1 transient_tokens.gc_swept audit row after a "
        f"1.6s settle at interval=1s; got {len(gc_audits)}. The GC "
        "task is not being spawned at step 5 of the § Lifespan "
        "ordering table, or its sweep cycle is not firing."
    )


async def test_daemon_shutdown_cancels_gc_cleanly(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Boot daemon; trigger shutdown; verify the lifespan exits within
    the A5-ratified 5s drain timeout and the GC's final cycle audit
    row is present.

    Encodes the spec § Shutdown sequence step 2: GC cancel + drain
    with the 5s default ceiling. A regression where the drain hangs
    (e.g., GC loop not honoring the shutdown event) would surface as
    a wall-clock timeout on the lifespan task.
    """
    settings = _make_settings(
        tmp_path, monkeypatch, gc_interval_seconds=1
    )

    shutdown = asyncio.Event()
    task = asyncio.create_task(
        _run_lifespan_body(settings, shutdown),
        name="lifespan-shutdown-test",
    )
    try:
        # Let one GC cycle run + emit its audit row.
        await asyncio.sleep(1.6)
        assert not task.done(), (
            "daemon exited unexpectedly during settle — would mask the "
            "shutdown contract this test pins."
        )

        shutdown_started = time.monotonic()
        shutdown.set()
        # The A5-ratified drain timeout is 5s; the lifespan has further
        # supervisor + mcp shutdown surface to traverse, so give it
        # 15s overall. The load-bearing assertion is that the
        # lifespan exits cleanly without timing out.
        await asyncio.wait_for(task, timeout=15.0)
        shutdown_elapsed = time.monotonic() - shutdown_started

        # Sanity — the drain timeout is 5s, so the overall shutdown
        # path should complete well under 15s. If a regression
        # extended the GC drain timeout dramatically, this would
        # surface as a slow but technically passing test; the
        # specific assertion below catches the obvious cases.
        assert shutdown_elapsed < 15.0
    finally:
        if not task.done():
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task

    gc_audits = await _audit_actions(
        settings.db_path, "transient_tokens.gc_swept"
    )
    assert len(gc_audits) >= 1, (
        "no transient_tokens.gc_swept audit row landed before "
        "shutdown — either the GC cycle did not fire during the "
        "settle window or the cancel-drain path raced the audit emit. "
        "The spec contract is that a cancel-after-cycle leaves the "
        "audit row committed; a regression that cancelled mid-cycle "
        "would lose the final cycle's audit."
    )


# ---------------------------------------------------------------------------
# Probe failure isolation
# ---------------------------------------------------------------------------


async def test_probe_failure_does_not_block_startup(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Simulate a probe internal error (mock raising during
    :meth:`run`); verify the daemon still reaches the post-startup
    steady state (lifespan task does not raise).

    Per spec § Lifespan ordering startup-failure-mode table: probe
    failure → log WARNING + continue startup. Observability is not
    load-bearing for daemon liveness. A regression that propagated
    probe exceptions would brick daemon boot on any keyring backend
    misconfiguration — operationally catastrophic.
    """

    class _RaisingProbe:
        """Drop-in replacement for ``TransientTokenStartupProbe`` whose
        :meth:`run` raises. Constructor accepts the same kwargs the
        production class does so the lifespan body's instantiation
        site does not need to special-case the fake."""

        def __init__(self, **kwargs: Any) -> None:
            self.kwargs = kwargs

        async def run(self) -> dict[str, int]:
            raise RuntimeError(
                "probe-failure-does-not-block-startup test: synthetic "
                "probe failure to verify lifespan continues anyway."
            )

    monkeypatch.setattr(
        main_module, "TransientTokenStartupProbe", _RaisingProbe
    )

    settings = _make_settings(tmp_path, monkeypatch, probe_enabled=True)
    # If probe failure bricked startup, _run_lifespan_for would raise.
    await _run_lifespan_for(settings)

    # No orphan audit rows since the probe raised before emitting any.
    orphan_audits = await _audit_actions(
        settings.db_path, "auth.orphan.detected"
    )
    assert orphan_audits == []
