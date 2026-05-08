"""Unit tests for ``bridge.launch_env.build_subprocess_env``.

Verifies the sanitization contract per
``project_subscription_first_architecture`` memory § B-5 and the
helper's docstring:

- ``ANTHROPIC_API_KEY`` stripped by default; preserved with
  ``allow_api_key=True``.
- ``ANTHROPIC_AUTH_TOKEN`` stripped regardless of ``allow_api_key``.
- ``CLAUDE_CODE_OAUTH_TOKEN`` preserved (subscription auth).
- Non-Anthropic env vars preserved (``PATH``, ``HOME``, etc.).
- ``MERIDIAN_ORCHESTRATOR_TOKEN`` and ``MERIDIAN_ORCHESTRATOR_DISPATCH_ID``
  injected from kwargs.
- Input ``base_env`` not mutated.

Future readers: if Anthropic introduces a new credential env var,
extend ``_ALWAYS_STRIPPED_VARS`` in the helper module and add a test
here covering the new var. The maintenance-discipline note in the
helper's docstring names this expectation.
"""

from __future__ import annotations

import pytest

from meridian_orchestrator.bridge.launch_env import build_subprocess_env


def test_strips_anthropic_api_key_by_default() -> None:
    base = {
        "ANTHROPIC_API_KEY": "sk-ant-secret",
        "PATH": "/usr/bin",
    }
    env = build_subprocess_env(
        token="tok-1", dispatch_id="d-1", base_env=base
    )
    assert "ANTHROPIC_API_KEY" not in env


def test_strips_anthropic_auth_token_by_default() -> None:
    base = {
        "ANTHROPIC_AUTH_TOKEN": "auth-secret",
        "PATH": "/usr/bin",
    }
    env = build_subprocess_env(
        token="tok-1", dispatch_id="d-1", base_env=base
    )
    assert "ANTHROPIC_AUTH_TOKEN" not in env


def test_preserves_claude_code_oauth_token() -> None:
    """The OAuth subscription credential is what makes the subscription-
    first architecture work; stripping it would break the subscription
    path entirely."""
    base = {
        "CLAUDE_CODE_OAUTH_TOKEN": "claude-oauth-secret",
        "PATH": "/usr/bin",
    }
    env = build_subprocess_env(
        token="tok-1", dispatch_id="d-1", base_env=base
    )
    assert env["CLAUDE_CODE_OAUTH_TOKEN"] == "claude-oauth-secret"


def test_preserves_non_anthropic_env_vars() -> None:
    """Non-Anthropic env vars (PATH, HOME, etc.) needed for normal
    subprocess operation are passed through unchanged."""
    base = {
        "PATH": "/usr/bin:/usr/local/bin",
        "HOME": "/home/test",
        "USER": "test",
        "LANG": "en_US.UTF-8",
    }
    env = build_subprocess_env(
        token="tok-1", dispatch_id="d-1", base_env=base
    )
    assert env["PATH"] == "/usr/bin:/usr/local/bin"
    assert env["HOME"] == "/home/test"
    assert env["USER"] == "test"
    assert env["LANG"] == "en_US.UTF-8"


def test_injects_orchestrator_token_and_dispatch_id() -> None:
    base: dict[str, str] = {}
    env = build_subprocess_env(
        token="my-token-secret", dispatch_id="dispatch-abc", base_env=base
    )
    assert env["MERIDIAN_ORCHESTRATOR_TOKEN"] == "my-token-secret"
    assert env["MERIDIAN_ORCHESTRATOR_DISPATCH_ID"] == "dispatch-abc"


def test_allow_api_key_true_preserves_anthropic_api_key() -> None:
    """The explicit overflow flow at M5-flag-flips when
    extra_usage.is_enabled: true. allow_api_key=True is the only path
    that preserves ANTHROPIC_API_KEY."""
    base = {"ANTHROPIC_API_KEY": "sk-ant-overflow"}
    env = build_subprocess_env(
        token="tok-1",
        dispatch_id="d-1",
        allow_api_key=True,
        base_env=base,
    )
    assert env["ANTHROPIC_API_KEY"] == "sk-ant-overflow"


def test_allow_api_key_true_still_strips_anthropic_auth_token() -> None:
    """ANTHROPIC_AUTH_TOKEN is not the documented overflow path even
    when overflow is enabled; explicit-intent overflow uses
    ANTHROPIC_API_KEY only."""
    base = {
        "ANTHROPIC_API_KEY": "sk-ant-overflow",
        "ANTHROPIC_AUTH_TOKEN": "auth-secret",
    }
    env = build_subprocess_env(
        token="tok-1",
        dispatch_id="d-1",
        allow_api_key=True,
        base_env=base,
    )
    assert env["ANTHROPIC_API_KEY"] == "sk-ant-overflow"
    assert "ANTHROPIC_AUTH_TOKEN" not in env


def test_does_not_mutate_input_base_env() -> None:
    """The helper returns a new dict; callers should be able to rely on
    base_env (often os.environ) being unchanged."""
    base = {
        "ANTHROPIC_API_KEY": "sk-ant-secret",
        "ANTHROPIC_AUTH_TOKEN": "auth-secret",
        "PATH": "/usr/bin",
    }
    base_snapshot = dict(base)
    build_subprocess_env(token="tok-1", dispatch_id="d-1", base_env=base)
    assert base == base_snapshot, (
        "build_subprocess_env mutated its base_env input; the contract "
        "is to return a new dict and leave the input untouched. Callers "
        "rely on this when passing os.environ."
    )


def test_default_base_env_reads_from_os_environ(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """When base_env is not passed, the helper reads from os.environ.
    Verified by setting an env var via monkeypatch and observing it in
    the returned env."""
    monkeypatch.setenv("MERIDIAN_ORCH_LAUNCH_ENV_TEST_VAR", "sentinel-value")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "should-be-stripped")
    env = build_subprocess_env(token="tok-1", dispatch_id="d-1")
    assert env["MERIDIAN_ORCH_LAUNCH_ENV_TEST_VAR"] == "sentinel-value"
    # Sanitization still applies when reading from os.environ.
    assert "ANTHROPIC_API_KEY" not in env


def test_combined_realistic_scenario() -> None:
    """End-to-end-shape: a realistic daemon env with mixed Anthropic +
    non-Anthropic vars, default allow_api_key=False, all rules apply."""
    base = {
        "ANTHROPIC_API_KEY": "sk-ant-leak-attempt",
        "ANTHROPIC_AUTH_TOKEN": "alt-auth-leak-attempt",
        "CLAUDE_CODE_OAUTH_TOKEN": "subscription-credential",
        "PATH": "/usr/bin",
        "HOME": "/home/mike",
        "MERIDIAN_ORCH_DATA_DIR": "/data",
    }
    env = build_subprocess_env(
        token="dispatch-token-secret",
        dispatch_id="d-xyz",
        base_env=base,
    )
    # Anthropic credentials sanitized
    assert "ANTHROPIC_API_KEY" not in env
    assert "ANTHROPIC_AUTH_TOKEN" not in env
    # Subscription credential preserved
    assert env["CLAUDE_CODE_OAUTH_TOKEN"] == "subscription-credential"
    # Non-Anthropic vars preserved
    assert env["PATH"] == "/usr/bin"
    assert env["HOME"] == "/home/mike"
    assert env["MERIDIAN_ORCH_DATA_DIR"] == "/data"
    # Per-launch vars injected
    assert env["MERIDIAN_ORCHESTRATOR_TOKEN"] == "dispatch-token-secret"
    assert env["MERIDIAN_ORCHESTRATOR_DISPATCH_ID"] == "d-xyz"
