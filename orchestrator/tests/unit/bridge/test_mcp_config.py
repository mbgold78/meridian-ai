"""Unit tests for ``bridge.mcp_config`` — per-dispatch / per-spawn MCP
server config JSON helpers + allowed-tools resolver (M5-acceptance commits
3.5 + 3.6; allowed-tools resolver moved into this module at commit 3.6
from ``tools/dispatch.py`` after the third ``bridge/`` → ``tools/`` watch
threshold fired and disposition was extraction-to-bridge per architectural
fit).

Convention 17 sub-type (c) Invariant enforcement: tests construct
representative inputs, invoke the helper, and assert on file existence
+ JSON shape + resolved tool list shape. Convention 17 sub-type (a)
Contract: signature shape on ``write_mcp_config`` + role-conditional
Playwright presence/absence; ``resolve_allowed_tools_for_role`` accepts
None registry without crashing.

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
``bridge/mcp_config.py:_ORCHESTRATOR_MCP_TOOL_PREFIX`` must agree on the
hyphen-preserved form.
"""

from __future__ import annotations

import json
from pathlib import Path

from meridian_orchestrator.bridge.mcp_config import (
    mcp_config_path,
    resolve_allowed_tools_for_role,
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
    ``bridge/mcp_config.py:_ORCHESTRATOR_MCP_TOOL_PREFIX`` in lockstep."""
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


def test_write_mcp_config_pm_omits_playwright(tmp_path: Path) -> None:
    """PM role (supervisor side; commit 3.6): JSON includes ONLY
    meridian-orchestrator HTTP MCP entry. PM-supervisor doesn't run
    browser-driven verification — Playwright is tester-only."""
    path = tmp_path / "mcp_config.json"
    ok = write_mcp_config(
        path,
        daemon_url="http://127.0.0.1:8765/mcp",
        token="mor_pm_secret",
        role="pm",
    )

    assert ok is True
    payload = json.loads(path.read_text(encoding="utf-8"))
    servers = payload["mcpServers"]
    assert set(servers.keys()) == {"meridian-orchestrator"}
    assert "playwright" not in servers


def test_write_mcp_config_engineer_omits_playwright(tmp_path: Path) -> None:
    """Engineer role (supervisor side; commit 3.6): JSON includes ONLY
    meridian-orchestrator HTTP MCP entry. Engineer-supervisor coordinates
    via dispatch_implementer / dispatch_tester MCP calls; doesn't run
    browser-driven verification — Playwright is tester-only."""
    path = tmp_path / "mcp_config.json"
    ok = write_mcp_config(
        path,
        daemon_url="http://127.0.0.1:8765/mcp",
        token="mor_eng_secret",
        role="engineer",
    )

    assert ok is True
    payload = json.loads(path.read_text(encoding="utf-8"))
    servers = payload["mcpServers"]
    assert set(servers.keys()) == {"meridian-orchestrator"}
    assert "playwright" not in servers


# ---------------------------------------------------------------------------
# resolve_allowed_tools_for_role — moved from tools/dispatch.py at commit
# 3.6 per Path Y disposition (extraction-to-bridge after the third
# bridge/ → tools/ watch-pattern threshold fired).
#
# Convention 17 sub-type (a) Contract: helper accepts None registry
# without crashing; returns []; preserves the test-fixture argv shape per
# ``feedback_optional_dependency_in_helpers`` precedent.
#
# Convention 17 sub-type (c) Invariant: orchestrator MCP tool prefix
# transformation produces the canonical
# ``mcp__meridian-orchestrator__<tool>`` shape (hyphen preserved per
# empirical verification 2026-05-08; convention seen with claude.ai HTTP
# MCPs in the deferred-tools list normalizes spaces/dots to underscore
# but preserves hyphens as valid identifier chars).
# ---------------------------------------------------------------------------


def test_resolve_allowed_tools_for_role_returns_empty_when_registry_none() -> None:
    """The helper handles ``registry=None`` by returning ``[]`` — empty
    result causes the launcher (dispatcher OR supervisor) to omit
    ``--allowedTools`` entirely, preserving the test-fixture argv shape."""
    assert resolve_allowed_tools_for_role(None, "tester") == []
    assert resolve_allowed_tools_for_role(None, "implementer") == []
    assert resolve_allowed_tools_for_role(None, "pm") == []
    assert resolve_allowed_tools_for_role(None, "engineer") == []


async def test_resolve_allowed_tools_for_role_prefixes_orchestrator_tools_with_canonical_naming(
    tmp_path: Path,
) -> None:
    """The helper prefixes orchestrator MCP tools (from
    ``AgentConfig.mcp_tools_allowed``) with the canonical
    ``mcp__meridian-orchestrator__`` prefix and concatenates with
    ``AgentConfig.allowed_tools`` (built-in + non-orchestrator MCP).

    Empirical anchor (2026-05-08): subprocess permission-denial messages
    name the canonical orchestrator MCP tool as
    ``mcp__meridian-orchestrator__list_agents`` — hyphen preserved
    (NOT normalized to ``mcp__meridian_orchestrator__...``). This test
    locks the prefix literal against future drift; if the orchestrator's
    FastMCP server name at ``server.py:368`` changes, update both sites
    in lockstep.

    Output ordering invariant: built-in/external first (yaml literal
    order), orchestrator tools second (yaml literal order with prefix).
    Stable across runs so test fixtures asserting on the exact list
    don't flap.

    Tests all four roles (implementer, tester, pm, engineer) since the
    helper is role-agnostic and supervisor side (pm + engineer) shares
    the resolver after the commit 3.6 file-move.
    """
    from meridian_orchestrator.agents.registry import AgentRegistry
    from meridian_orchestrator.auth import TokenStore
    from meridian_orchestrator.bridge.mcp_config import _ORCHESTRATOR_MCP_TOOL_PREFIX

    # Lock the prefix literal at the test layer — if the constant is
    # changed without the empirical re-verification (e.g., to
    # mcp__meridian_orchestrator__ via a misremembered convention), this
    # assertion fires. Brittle by design per Convention 9 code-inspection
    # sentinel observability.
    assert _ORCHESTRATOR_MCP_TOOL_PREFIX == "mcp__meridian-orchestrator__", (
        "orchestrator MCP tool prefix drifted from the empirical canonical "
        "form. Verified 2026-05-08: subprocess permission-denial named "
        "`mcp__meridian-orchestrator__list_agents` (hyphen preserved). "
        "If FastMCP server name at server.py:368 changes, update both "
        "the constant and this assertion in lockstep."
    )

    # Construct a real AgentRegistry from the repo's yamls — exercises
    # the full helper code path including AgentConfig.allowed_tools +
    # AgentConfig.mcp_tools_allowed access. Tokens issued via a per-test
    # TokenStore service so initial_load() resolves agent_ids and the
    # snapshot includes all four roles (mirrors test_registry.py's
    # pattern at test_initial_load_resolves_uuids_from_keyring).
    repo_config_dir = (
        Path(__file__).resolve().parents[3] / "config" / "agents"
    )
    agents_dir = tmp_path / "agents"
    agents_dir.mkdir()
    for role in ("pm", "engineer", "implementer", "tester"):
        (agents_dir / f"{role}.yaml").write_text(
            (repo_config_dir / f"{role}.yaml").read_text(encoding="utf-8"),
            encoding="utf-8",
        )
    store = TokenStore(service="resolve-allowed-tools-bridge-test")
    for role in ("pm", "engineer", "implementer", "tester"):
        await store.issue(agent_id=None, role=role, label=f"{role}-test")
    registry = AgentRegistry(agents_dir, token_store=store)
    await registry.initial_load()

    # Tester: built-ins + Playwright + prefixed orchestrator tools
    tester_tools = resolve_allowed_tools_for_role(registry, "tester")
    assert tester_tools[0] == "Read"
    assert "mcp__meridian-orchestrator__update_task_status" in tester_tools
    assert "mcp__meridian-orchestrator__complete_dispatch" in tester_tools
    assert "mcp__playwright__browser_navigate" in tester_tools
    # No bare orchestrator tool names — proves prefix transformation ran
    assert "update_task_status" not in tester_tools

    # Implementer: built-ins + prefixed orchestrator tools; NO Playwright
    implementer_tools = resolve_allowed_tools_for_role(registry, "implementer")
    assert "mcp__meridian-orchestrator__complete_dispatch" in implementer_tools
    assert not any(t.startswith("mcp__playwright__") for t in implementer_tools)

    # PM (supervisor side; commit 3.6): built-ins + prefixed orchestrator
    # tools; NO Playwright. Same shape as implementer per
    # supervisor/dispatcher symmetry of the resolver.
    pm_tools = resolve_allowed_tools_for_role(registry, "pm")
    assert "mcp__meridian-orchestrator__notify_human" in pm_tools
    assert "mcp__meridian-orchestrator__write_pm_handoff" in pm_tools
    assert not any(t.startswith("mcp__playwright__") for t in pm_tools)

    # Engineer (supervisor side; commit 3.6): built-ins + prefixed
    # orchestrator tools; NO Playwright.
    engineer_tools = resolve_allowed_tools_for_role(registry, "engineer")
    assert "mcp__meridian-orchestrator__dispatch_implementer" in engineer_tools
    assert "mcp__meridian-orchestrator__dispatch_tester" in engineer_tools
    assert not any(t.startswith("mcp__playwright__") for t in engineer_tools)
