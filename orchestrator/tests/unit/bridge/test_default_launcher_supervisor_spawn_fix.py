"""Unit tests for the supervisor-spawn WinError 206 fix
(Operator Fix Window 2026-05-27).

Pre-fix the supervisor launcher passed the entire yaml ``system_prompt``
(30-37 KB) as a single argv element via ``-p <prompt>``. Windows
``CreateProcessW`` rejects argv exceeding ~32 KB with WinError 206. The
fix writes the playbook to ``<dispatches_root>/<dispatch_id>/system_prompt.md``
and passes it via ``claude --append-system-prompt-file <path>`` with a
small role-aware kickoff string as ``-p``. These tests pin the new shape
+ guard the argv-size regression with a synthetic 100 KB prompt.
"""

from __future__ import annotations

import asyncio
from pathlib import Path
from typing import Any
from unittest.mock import AsyncMock

import pytest

from meridian_orchestrator.bridge.supervisor_base import (
    SupervisorLaunchContext,
    _default_launcher,
    _supervisor_kickoff,
)


@pytest.fixture
def captured_subprocess_args(
    monkeypatch: pytest.MonkeyPatch,
) -> list[tuple[Any, ...]]:
    """Capture positional args to asyncio.create_subprocess_exec without
    actually spawning. Mirrors the pattern in test_default_launcher_effort.py."""
    captured: list[tuple[Any, ...]] = []

    async def fake_create(*args: Any, **kwargs: Any) -> AsyncMock:
        captured.append(args)
        proc = AsyncMock()
        proc.pid = 12345
        proc.returncode = None
        return proc

    monkeypatch.setattr(asyncio, "create_subprocess_exec", fake_create)
    return captured


def _make_ctx(
    *,
    prompt: str = "the supervisor playbook body — pretend this is 30+ KB",
    role: str = "pm-continuous",
    dispatches_root: Path | None = None,
    dispatch_id: str = "dispatch-test-1",
) -> SupervisorLaunchContext:
    return SupervisorLaunchContext(
        dispatch_id=dispatch_id,
        role=role,
        prompt=prompt,
        working_directory=Path("/tmp/test-cwd"),
        token="test-token",
        effort="high",
        allowed_tools=[],
        mcp_config_path=None,
        dispatches_root=dispatches_root,
    )


# --- Kickoff helper ---------------------------------------------------


def test_supervisor_kickoff_pm_role_resolves_to_pm_label() -> None:
    assert "Meridian PM supervisor" in _supervisor_kickoff("pm-continuous")


def test_supervisor_kickoff_engineer_role_resolves_to_engineer_label() -> None:
    assert "Meridian Engineer supervisor" in _supervisor_kickoff(
        "engineer-supervisor"
    )


def test_supervisor_kickoff_unknown_role_falls_back_to_literal() -> None:
    # Unknown roles should still produce a sensible kickoff using the
    # raw role string — defensive against future supervisor types.
    out = _supervisor_kickoff("some-future-supervisor")
    assert "Meridian some-future-supervisor supervisor" in out


def test_supervisor_kickoff_is_small() -> None:
    """The kickoff must stay tiny so the argv doesn't regress."""
    assert len(_supervisor_kickoff("pm-continuous")) < 300
    assert len(_supervisor_kickoff("engineer-supervisor")) < 300


# --- Default launcher: file-and-pointer path -------------------------


async def test_launcher_writes_system_prompt_file_with_exact_prompt(
    captured_subprocess_args: list[tuple[Any, ...]],
    tmp_path: Path,
) -> None:
    """When ``dispatches_root`` is provided, the launcher writes the full
    ``ctx.prompt`` to ``<dispatches_root>/<dispatch_id>/system_prompt.md``
    byte-for-byte (no truncation, no transformation)."""
    big_prompt = "## Supervisor playbook\n" + ("X" * 2048) + "\nEOF"
    ctx = _make_ctx(
        prompt=big_prompt,
        dispatches_root=tmp_path / "dispatches",
        dispatch_id="dispatch-write-test",
    )
    await _default_launcher(ctx)
    sysprompt_path = (
        tmp_path / "dispatches" / "dispatch-write-test" / "system_prompt.md"
    )
    assert sysprompt_path.is_file()
    assert sysprompt_path.read_text(encoding="utf-8") == big_prompt


async def test_launcher_argv_uses_append_system_prompt_file_flag(
    captured_subprocess_args: list[tuple[Any, ...]],
    tmp_path: Path,
) -> None:
    """Post-fix argv: ``--append-system-prompt-file <path>`` carries the
    bulk; ``-p <small kickoff>`` carries the user message. The huge prompt
    body must NOT appear as an argv element."""
    big_prompt = "PLAYBOOK_SENTINEL_4096\n" + ("Y" * 4096)
    ctx = _make_ctx(
        prompt=big_prompt,
        role="pm-continuous",
        dispatches_root=tmp_path / "dispatches",
        dispatch_id="dispatch-shape-test",
    )
    await _default_launcher(ctx)
    assert len(captured_subprocess_args) == 1
    argv = list(captured_subprocess_args[0])
    # Flag present with the correct path.
    assert "--append-system-prompt-file" in argv
    flag_idx = argv.index("--append-system-prompt-file")
    path_str = argv[flag_idx + 1]
    assert path_str.endswith("system_prompt.md")
    assert "dispatch-shape-test" in path_str
    # -p argument is the small kickoff, not the playbook.
    assert "-p" in argv
    p_idx = argv.index("-p")
    p_value = argv[p_idx + 1]
    assert "Meridian PM supervisor" in p_value
    # Critical regression guard: the huge prompt body must not have leaked
    # into any argv element.
    assert big_prompt not in argv
    assert not any("PLAYBOOK_SENTINEL_4096" in a for a in argv)


async def test_launcher_argv_total_size_bounded_under_4k_with_100k_prompt(
    captured_subprocess_args: list[tuple[Any, ...]],
    tmp_path: Path,
) -> None:
    """Regression guard for the WinError 206 root cause: even with a
    100 KB ``ctx.prompt`` (>>> 32 KB Windows CreateProcess limit), the
    total argv size stays under 4 KB."""
    huge_prompt = "Z" * (100 * 1024)
    ctx = _make_ctx(
        prompt=huge_prompt,
        dispatches_root=tmp_path / "dispatches",
        dispatch_id="dispatch-100k-test",
    )
    await _default_launcher(ctx)
    argv = list(captured_subprocess_args[0])
    total = sum(len(str(a)) for a in argv)
    assert total < 4096, (
        f"argv total {total} bytes — regression vs Windows 32KB CreateProcess limit"
    )


async def test_launcher_engineer_role_kickoff(
    captured_subprocess_args: list[tuple[Any, ...]],
    tmp_path: Path,
) -> None:
    """Engineer-role kickoff must say "Engineer supervisor" (mirror of
    the PM kickoff test, verifying the role label routes correctly)."""
    ctx = _make_ctx(
        prompt="engineer playbook",
        role="engineer-supervisor",
        dispatches_root=tmp_path / "dispatches",
        dispatch_id="dispatch-eng-test",
    )
    await _default_launcher(ctx)
    argv = list(captured_subprocess_args[0])
    p_value = argv[argv.index("-p") + 1]
    assert "Meridian Engineer supervisor" in p_value


# --- Default launcher: legacy fallback path --------------------------


async def test_launcher_falls_back_to_inline_prompt_when_dispatches_root_none(
    captured_subprocess_args: list[tuple[Any, ...]],
) -> None:
    """Test-fixture path: when ``dispatches_root`` is None (custom
    subprocess_launcher injections in many existing integration tests),
    the launcher falls back to the legacy inline ``-p <ctx.prompt>``
    shape. The new flag must NOT appear."""
    legacy_prompt = "the original inline prompt body"
    ctx = _make_ctx(prompt=legacy_prompt, dispatches_root=None)
    await _default_launcher(ctx)
    argv = list(captured_subprocess_args[0])
    assert "--append-system-prompt-file" not in argv
    # -p still carries the literal prompt
    assert legacy_prompt in argv


async def test_launcher_falls_back_when_dir_create_fails(
    captured_subprocess_args: list[tuple[Any, ...]],
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    """If the per-dispatch directory cannot be created (disk full,
    permission denied, etc.), the helper returns None and the launcher
    falls back to the legacy inline-prompt path rather than failing the
    dispatch — same operational policy as stderr capture (best-effort
    diagnostics never break the primary lifecycle)."""
    # Patch the helper to simulate the OSError → None path.
    monkeypatch.setattr(
        "meridian_orchestrator.bridge.supervisor_base.system_prompt_file_path",
        lambda *a, **k: None,
    )
    inline = "playbook body forced inline"
    ctx = _make_ctx(
        prompt=inline,
        dispatches_root=tmp_path / "dispatches",
        dispatch_id="dispatch-fallback-test",
    )
    await _default_launcher(ctx)
    argv = list(captured_subprocess_args[0])
    assert "--append-system-prompt-file" not in argv
    assert inline in argv


# --- Stderr-drain path helper -----------------------------------------


def test_system_prompt_file_path_helper_creates_dir(tmp_path: Path) -> None:
    """The helper must create the per-dispatch directory and return the
    expected path under it."""
    from meridian_orchestrator.bridge.stderr_drain import system_prompt_file_path

    result = system_prompt_file_path(tmp_path / "dispatches", "dispatch-abc")
    assert result is not None
    assert result.name == "system_prompt.md"
    assert result.parent.name == "dispatch-abc"
    assert result.parent.is_dir()


def test_system_prompt_file_path_returns_none_on_mkdir_failure(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """OSError from mkdir → None return (best-effort)."""
    from meridian_orchestrator.bridge import stderr_drain

    def _fail_mkdir(self: Path, *a: Any, **kw: Any) -> None:
        raise OSError("simulated disk full")

    monkeypatch.setattr(Path, "mkdir", _fail_mkdir)
    result = stderr_drain.system_prompt_file_path(
        tmp_path / "dispatches", "dispatch-mkdir-fail"
    )
    assert result is None
