"""Unit tests for ``bridge.mcp_config`` — per-dispatch MCP server config
JSON helpers (M5-acceptance commit 3.5).

Convention 17 sub-type (c) Invariant enforcement: tests construct
representative inputs, invoke the helper, and assert on file existence
+ JSON shape. Convention 17 sub-type (a) Contract: signature shape on
``write_mcp_config`` + role-conditional Playwright presence/absence.

JSON schema verified empirically (2026-05-08):
``{"mcpServers": {"<name>": {"type": "stdio" | "http", ...}}}``. The
test asserts on the exact shape so future drift surfaces here rather
than at runtime.

Hyphen preservation in ``meridian-orchestrator`` server name is
load-bearing per empirical verification (2026-05-08): subprocess
permission-denial messages name canonical orchestrator MCP tools as
``mcp__meridian-orchestrator__<tool>`` (hyphen preserved; not normalized
to underscore as ``claude_ai_Gmail`` convention shows for spaces/dots).
The JSON-key naming + the prefix transformation at
``tools/dispatch.py:_ORCHESTRATOR_MCP_TOOL_PREFIX`` must agree on the
hyphen-preserved form.
"""

from __future__ import annotations

import json
from pathlib import Path

from meridian_orchestrator.bridge.mcp_config import (
    mcp_config_path,
    write_mcp_config,
)


def test_mcp_config_path_creates_parent_directory(tmp_path: Path) -> None:
    """``mcp_config_path`` creates ``<dispatches_root>/<dispatch_id>/``
    via idempotent ``mkdir(parents=True, exist_ok=True)`` + returns the
    file path under that directory. Symmetric with
    ``stderr_drain.stderr_log_path`` shape — both helpers create the
    same parent directory; whichever runs first creates, second no-ops."""
    dispatches_root = tmp_path / "dispatches"
    result = mcp_config_path(dispatches_root, "dispatch-abc-123")

    assert result is not None
    assert result == dispatches_root / "dispatch-abc-123" / "mcp_config.json"
    assert (dispatches_root / "dispatch-abc-123").is_dir()


def test_mcp_config_path_idempotent_on_existing_directory(tmp_path: Path) -> None:
    """Second call against the same path is idempotent; mirrors
    ``stderr_log_path`` shape that runs alongside in production."""
    dispatches_root = tmp_path / "dispatches"
    first = mcp_config_path(dispatches_root, "d-idem")
    second = mcp_config_path(dispatches_root, "d-idem")

    assert first == second
    assert (dispatches_root / "d-idem").is_dir()


def test_write_mcp_config_tester_includes_playwright(tmp_path: Path) -> None:
    """Tester role: JSON includes both meridian-orchestrator HTTP MCP
    entry (with ``Authorization: Bearer <token>`` header) AND playwright
    stdio MCP entry. Hyphen preserved in ``meridian-orchestrator`` server
    name per canonical Claude Code MCP naming convention (verified
    empirically 2026-05-08)."""
    path = tmp_path / "mcp_config.json"
    ok = write_mcp_config(
        path,
        daemon_url="http://127.0.0.1:8765/mcp",
        token="mor_abc123_secret",
        role="tester",
    )

    assert ok is True
    assert path.exists()
    payload = json.loads(path.read_text(encoding="utf-8"))

    # Top-level mcpServers key (verified empirically 2026-05-08 via
    # `claude --mcp-config <file> --strict-mcp-config` end-to-end probe
    # with stdio Playwright entry returning real example.com page title)
    assert "mcpServers" in payload
    servers = payload["mcpServers"]
    assert set(servers.keys()) == {"meridian-orchestrator", "playwright"}, (
        f"tester mcp_config server set drifted: got {set(servers.keys())!r}; "
        "expected exactly {meridian-orchestrator, playwright}. "
        "Implementer-vs-tester role asymmetry is the architectural intent."
    )

    # meridian-orchestrator entry shape
    orch = servers["meridian-orchestrator"]
    assert orch["type"] == "http"
    assert orch["url"] == "http://127.0.0.1:8765/mcp"
    assert orch["headers"] == {"Authorization": "Bearer mor_abc123_secret"}, (
        "Authorization header literal drifted from `Bearer <token>` form. "
        "FastMCP at server.py:198,219,222 expects this exact shape via "
        "MCP SDK auth_context middleware."
    )

    # playwright entry shape
    pw = servers["playwright"]
    assert pw["type"] == "stdio"
    assert pw["command"] == "npx"
    assert pw["args"] == ["@playwright/mcp@latest"]


def test_write_mcp_config_implementer_omits_playwright(tmp_path: Path) -> None:
    """Implementer role: JSON includes ONLY meridian-orchestrator HTTP
    MCP entry; NO playwright entry. Role-asymmetry preserved (Tester
    does browser-driven verification; Implementer does code changes)."""
    path = tmp_path / "mcp_config.json"
    ok = write_mcp_config(
        path,
        daemon_url="http://127.0.0.1:8765/mcp",
        token="mor_xyz_secret",
        role="implementer",
    )

    assert ok is True
    payload = json.loads(path.read_text(encoding="utf-8"))
    servers = payload["mcpServers"]
    assert set(servers.keys()) == {"meridian-orchestrator"}, (
        f"implementer mcp_config server set drifted: got {set(servers.keys())!r}; "
        "expected exactly {meridian-orchestrator}. Implementer must not "
        "see playwright (role-asymmetry)."
    )
    assert "playwright" not in servers


def test_write_mcp_config_preserves_hyphen_in_server_name(tmp_path: Path) -> None:
    """The orchestrator FastMCP server name at server.py:368 is
    ``meridian-orchestrator`` (hyphen). Empirical verification at commit
    3 prep (2026-05-08) confirmed Claude Code's MCP tool prefix
    transformation preserves hyphens in server names — subprocess
    permission-denial messages named tools as
    ``mcp__meridian-orchestrator__list_agents``. The JSON key here MUST
    match server.py's registered name exactly; if the registered name
    changes, update both this assertion AND
    ``tools/dispatch.py:_ORCHESTRATOR_MCP_TOOL_PREFIX`` in lockstep."""
    path = tmp_path / "mcp_config.json"
    write_mcp_config(
        path,
        daemon_url="http://127.0.0.1:8765/mcp",
        token="t",
        role="tester",
    )

    payload = json.loads(path.read_text(encoding="utf-8"))
    server_keys = list(payload["mcpServers"].keys())
    assert "meridian-orchestrator" in server_keys, (
        f"server name normalization drifted: got {server_keys!r}; "
        "canonical FastMCP server name preserves hyphens. Underscore "
        "form `meridian_orchestrator` would not match server.py:368 "
        "registration and would fail subprocess MCP discovery."
    )
    # Defense-in-depth: explicit absence of underscore form
    assert "meridian_orchestrator" not in server_keys
