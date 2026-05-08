"""Subprocess environment construction with Anthropic-credential sanitization.

The daemon launches Claude Code subprocesses for two purposes:

- Per-dispatch sub-agents (Implementer, Tester) via :mod:`bridge.dispatcher`
- Long-running supervisors (Engineer at M4c, PM at M5) via
  :mod:`bridge.supervisor_base`

Both paths construct subprocess environments that inherit from the
daemon process's environment (``os.environ``) and add the per-launch
``MERIDIAN_ORCHESTRATOR_TOKEN`` and ``MERIDIAN_ORCHESTRATOR_DISPATCH_ID``
vars. The naive ``os.environ.copy()`` pattern leaks any Anthropic
credential env vars set on the daemon host into the subprocess, with
two consequences:

1. **Subscription bypass** (`anthropics/claude-code#12352`_): when
   ``ANTHROPIC_API_KEY`` is set in the subprocess env, all Claude Code
   API calls bill against the API key and ignore the OAuth subscription.
   Phase 1 runs entirely on Mike's OAuth subscription per the
   subscription-first cost model
   (``project_subscription_first_architecture`` memory); subscription
   bypass is silent overflow billing.
2. **Auth ambiguity**: ``ANTHROPIC_AUTH_TOKEN`` (alternative SDK
   credential) creates a similar bypass through a less-documented path.

This module provides :func:`build_subprocess_env` as the single point
of construction for subprocess environments. Both launchers call it.
The helper enforces the sanitization rules below; callers that bypass
the helper are incorrect by spec.

**Stripped by default** (``allow_api_key=False``):

- ``ANTHROPIC_API_KEY`` — the documented overflow-bypass var per
  `anthropics/claude-code#12352`_.
- ``ANTHROPIC_AUTH_TOKEN`` — alternative Anthropic SDK credential; not
  the documented overflow path. Stripping it keeps the auth surface
  unambiguous.

**Preserved always**:

- ``CLAUDE_CODE_OAUTH_TOKEN`` — the OAuth subscription credential.
  Stripping this would break the subscription path entirely.
- All non-Anthropic env vars (``PATH``, ``HOME``, ``USER``, etc.) —
  needed for normal subprocess operation.

**When ``allow_api_key=True``**:

- ``ANTHROPIC_API_KEY`` is preserved (the explicit overflow flow,
  reserved for M5-flag-flips when ``extra_usage.is_enabled: true``).
- ``ANTHROPIC_AUTH_TOKEN`` is **still stripped** (it's not the
  documented overflow path even when overflow is enabled; explicit-
  intent overflow uses ``ANTHROPIC_API_KEY``).

**Maintenance discipline**: if Anthropic introduces new credential env
vars in the future (e.g., ``ANTHROPIC_BEARER_TOKEN``,
``ANTHROPIC_CLIENT_SECRET``, etc.), audit this helper against current
SDK conventions before assuming the allowlist is complete. The
sanitization set is **not auto-updating**; new credential paths can
silently bypass the subscription if the helper isn't kept in sync.

.. _anthropics/claude-code#12352: https://github.com/anthropics/claude-code/issues/12352
"""

from __future__ import annotations

import os
from collections.abc import Mapping

# Env var names stripped by default. Extend this set when new Anthropic
# credential paths are introduced — see § Maintenance discipline in the
# module docstring.
_ALWAYS_STRIPPED_VARS: frozenset[str] = frozenset({"ANTHROPIC_AUTH_TOKEN"})

# Env var name stripped by default but preserved when allow_api_key=True
# (the explicit overflow flow at M5-flag-flips).
_OVERFLOW_GATED_VAR: str = "ANTHROPIC_API_KEY"


def build_subprocess_env(
    *,
    token: str,
    dispatch_id: str,
    allow_api_key: bool = False,
    base_env: Mapping[str, str] | None = None,
) -> dict[str, str]:
    """Construct a subprocess env dict with Anthropic-credential sanitization.

    Args:
        token: The dispatch/supervisor token. Set as
            ``MERIDIAN_ORCHESTRATOR_TOKEN`` in the returned env.
        dispatch_id: The dispatch_id for the launched subprocess. Set as
            ``MERIDIAN_ORCHESTRATOR_DISPATCH_ID`` in the returned env.
        allow_api_key: When ``True``, preserves ``ANTHROPIC_API_KEY`` in
            the env (explicit overflow flow). When ``False`` (default),
            strips it. ``ANTHROPIC_AUTH_TOKEN`` is stripped regardless.
        base_env: The base environment to inherit from. Defaults to
            :data:`os.environ`. Tests pass an explicit dict to control
            which vars are present in the input env.

    Returns:
        A new dict suitable for passing to ``asyncio.create_subprocess_exec``'s
        ``env=`` parameter. The input ``base_env`` is not mutated.

    The caller is responsible for any per-launch ``env_extras`` (e.g.,
    ``DispatchLaunchContext.env_extras``) — apply them via
    ``env.update(ctx.env_extras)`` AFTER this helper returns. Callers
    that want their extras sanitized should pre-filter before passing.
    """
    source = base_env if base_env is not None else os.environ
    env = dict(source)

    for stripped in _ALWAYS_STRIPPED_VARS:
        env.pop(stripped, None)

    if not allow_api_key:
        env.pop(_OVERFLOW_GATED_VAR, None)

    env["MERIDIAN_ORCHESTRATOR_TOKEN"] = token
    env["MERIDIAN_ORCHESTRATOR_DISPATCH_ID"] = dispatch_id

    return env


__all__ = ["build_subprocess_env"]
