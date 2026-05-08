"""Integration tests for launcher env sanitization.

Verifies that ``bridge/dispatcher.py:_default_launcher`` and
``bridge/supervisor_base.py:_default_launcher`` actually route their
env construction through :func:`bridge.launch_env.build_subprocess_env`
— i.e., the wiring between the helper and the launchers is correct.

Distinct from ``tests/unit/bridge/test_launch_env.py``: those tests
verify the helper's sanitization rules in isolation. These tests
verify the *wiring* — that a regression in either launcher (e.g., a
future commit reverts to ``os.environ.copy()`` or paraphrases the
helper without preserving the contract) fails the test informatively.

Without these tests, the existing lifecycle tests would stay green
even if ANTHROPIC_API_KEY started leaking into subprocess envs again,
because lifecycle tests don't set the env var or assert its absence.
This file is the durable enforcement of the #12352 vulnerability
closure landed in M5-record-api-call commit 2.
"""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any

import pytest

from meridian_orchestrator.bridge.dispatcher import (
    DispatchLaunchContext,
)
from meridian_orchestrator.bridge.dispatcher import (
    _default_launcher as dispatcher_default_launcher,
)
from meridian_orchestrator.bridge.supervisor_base import (
    SupervisorLaunchContext,
)
from meridian_orchestrator.bridge.supervisor_base import (
    _default_launcher as supervisor_default_launcher,
)


class _FakeProcess:
    """Minimal asyncio.subprocess.Process stand-in."""

    @property
    def pid(self) -> int:
        return 4242

    @property
    def returncode(self) -> int | None:
        return None

    async def wait(self) -> int:
        return 0

    def terminate(self) -> None:
        pass

    def kill(self) -> None:
        pass


async def test_dispatcher_default_launcher_strips_anthropic_api_key(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """The bridge.dispatcher._default_launcher routes env construction
    through build_subprocess_env, stripping ANTHROPIC_API_KEY from the
    subprocess env even when it's set on the daemon's os.environ.

    Closes the #12352 vulnerability: a daemon host with ANTHROPIC_API_KEY
    set would silently bill all sub-agent dispatches against the API
    key, bypassing the OAuth subscription the orchestrator is designed
    around. This test asserts the launcher actually invokes the helper
    that prevents this.
    """
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-leak-marker-dispatcher")
    captured: dict[str, Any] = {}

    async def fake_create_subprocess_exec(
        *args: Any, **kwargs: Any
    ) -> _FakeProcess:
        captured["args"] = args
        captured["env"] = kwargs.get("env")
        return _FakeProcess()

    monkeypatch.setattr(
        asyncio, "create_subprocess_exec", fake_create_subprocess_exec
    )

    ctx = DispatchLaunchContext(
        dispatch_id="d-1",
        role="implementer",
        prompt="test prompt",
        working_directory=tmp_path,
        token="dispatcher-token-secret",
    )
    await dispatcher_default_launcher(ctx)

    env = captured["env"]
    assert env is not None
    assert "ANTHROPIC_API_KEY" not in env, (
        "dispatcher._default_launcher leaked ANTHROPIC_API_KEY into the "
        "subprocess env. The launcher must route through "
        "bridge.launch_env.build_subprocess_env to strip Anthropic "
        "credential vars per #12352. A future commit may have reverted "
        "the helper routing or paraphrased it without preserving the "
        "sanitization contract."
    )
    # Sanity: the helper's other contracts are also intact through
    # the launcher path.
    assert env["MERIDIAN_ORCHESTRATOR_TOKEN"] == "dispatcher-token-secret"
    assert env["MERIDIAN_ORCHESTRATOR_DISPATCH_ID"] == "d-1"


async def test_supervisor_default_launcher_strips_anthropic_api_key(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """Symmetric to the dispatcher launcher test. The
    bridge.supervisor_base._default_launcher must also route env
    construction through build_subprocess_env. Long-running supervisors
    (Engineer at M4c, PM at M5) launched with a leaking API_KEY would
    silently bypass the OAuth subscription for the entire daemon
    lifetime per #12352.
    """
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-leak-marker-supervisor")
    captured: dict[str, Any] = {}

    async def fake_create_subprocess_exec(
        *args: Any, **kwargs: Any
    ) -> _FakeProcess:
        captured["args"] = args
        captured["env"] = kwargs.get("env")
        return _FakeProcess()

    monkeypatch.setattr(
        asyncio, "create_subprocess_exec", fake_create_subprocess_exec
    )

    ctx = SupervisorLaunchContext(
        dispatch_id="sup-1",
        role="engineer-supervisor",
        prompt="supervisor prompt",
        working_directory=tmp_path,
        token="supervisor-token-secret",
    )
    await supervisor_default_launcher(ctx)

    env = captured["env"]
    assert env is not None
    assert "ANTHROPIC_API_KEY" not in env, (
        "supervisor_base._default_launcher leaked ANTHROPIC_API_KEY into "
        "the subprocess env. The launcher must route through "
        "bridge.launch_env.build_subprocess_env to strip Anthropic "
        "credential vars per #12352. A future commit may have reverted "
        "the helper routing or paraphrased it without preserving the "
        "sanitization contract."
    )
    # Sanity: the helper's other contracts are also intact.
    assert env["MERIDIAN_ORCHESTRATOR_TOKEN"] == "supervisor-token-secret"
    assert env["MERIDIAN_ORCHESTRATOR_DISPATCH_ID"] == "sup-1"
