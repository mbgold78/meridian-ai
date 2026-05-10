"""Shared logic for operator role-wrapper scripts.

The hyphen-named entry-point scripts (``operate-as-pm.py``,
``operate-as-engineer.py``) are thin wrappers that import from this
module. Hyphenated filenames can't be imported as Python identifiers
(``scripts.operate-as-pm`` is a parser error in an import statement);
this module's underscore-prefixed name keeps the shared
implementation importable via ``from scripts._operator_wrapper import
operator_main``.

See :mod:`scripts.operate-as-pm` and :mod:`scripts.operate-as-engineer`
module docstrings for operator-facing pre-conditions and invocation
shape.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path

import yaml


def _project_root() -> Path:
    """Resolve the orchestrator project root from this module's location.

    ``scripts/`` lives at ``<orchestrator-project>/scripts/``; project
    root is the parent.
    """
    return Path(__file__).resolve().parent.parent


def _load_role_system_prompt(role: str) -> str:
    """Read ``<project>/config/agents/<role>.yaml`` and return its
    ``system_prompt`` field. Raises ``SystemExit`` with an
    operator-facing message on missing file or missing/empty field.
    """
    yaml_path = _project_root() / "config" / "agents" / f"{role}.yaml"
    if not yaml_path.exists():
        raise SystemExit(
            f"FATAL: agent yaml not found at {yaml_path}. "
            f"Verify the orchestrator project layout includes "
            f"config/agents/{role}.yaml."
        )
    raw = yaml_path.read_text(encoding="utf-8")
    parsed = yaml.safe_load(raw)
    if not isinstance(parsed, dict):
        raise SystemExit(
            f"FATAL: {yaml_path} is not a YAML mapping; cannot extract "
            f"system_prompt."
        )
    system_prompt = parsed.get("system_prompt")
    if not isinstance(system_prompt, str) or not system_prompt:
        raise SystemExit(
            f"FATAL: {yaml_path} has no ``system_prompt`` field "
            f"(or empty); cannot pre-load role discipline."
        )
    return system_prompt


def _resolve_claude_binary() -> str:
    """Resolve the ``claude`` CLI from PATH; fail-fast if missing."""
    binary = shutil.which("claude")
    if binary is None:
        raise SystemExit(
            "FATAL: ``claude`` CLI not found on PATH. Install Claude "
            "Code or update PATH; see https://code.claude.com/docs."
        )
    return binary


def build_argv(role: str, passthrough_args: list[str]) -> list[str]:
    """Compose the claude invocation argv for ``role``.

    Argv shape: ``claude --dangerously-skip-permissions
    --append-system-prompt <yaml.system_prompt> [<passthrough>...]``.
    The skip-permissions flag matches the operator-driven session
    convention; per-tool prompts during normal interactive use add
    friction the operator can opt back into via ``/permissions``.

    Exposed publicly (no leading underscore) for unit tests that verify
    argv composition without spawning a subprocess.
    """
    system_prompt = _load_role_system_prompt(role)
    claude_binary = _resolve_claude_binary()
    return [
        claude_binary,
        "--dangerously-skip-permissions",
        "--append-system-prompt",
        system_prompt,
        *passthrough_args,
    ]


def operator_main(role: str, passthrough_args: list[str]) -> int:
    """Spawn ``claude`` with role-discipline pre-loaded.

    Returns the spawned process's exit code. Uses ``os.execvp`` on POSIX
    so the operator's session takes over the terminal cleanly; falls
    back to ``subprocess.call`` on Windows (Windows lacks fork; execvp
    replaces the Python process but the parent shell may not handle the
    transition cleanly).
    """
    argv = build_argv(role, passthrough_args)
    if sys.platform == "win32":
        return subprocess.call(argv)
    os.execvp(argv[0], argv)
    return 0  # unreachable on POSIX after execvp; kept for type-checker
