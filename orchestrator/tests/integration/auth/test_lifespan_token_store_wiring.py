"""β-S11-15 regression guards: lifespan TokenStore wiring consistency.

Root cause (2026-05-17): slice 1b commit 6 wired
``TokenStore(transient_store=transient_store)`` at four call sites in
``_run_lifespan_body`` (probe, dispatcher, PM supervisor, Engineer
supervisor) but did NOT pass ``store=`` to ``serve_mcp()``. The MCP
server's ``build_server`` defaulted to ``TokenStore()`` (keyring-only)
when ``store`` was omitted — so per-spawn supervisor tokens written to
SQLite via TransientTokenStore could NOT be resolved by the server's
Bearer-token auth lookup. Supervisor subprocesses registered as OAuth
clients, attempted their first MCP tool call, hit silent auth failure,
exited rc=0 with ``last_tool_called=None``. Repeated until the
3-respawn cap fired ``supervisor.halted``.

Fix: extract one lifespan-scoped ``token_store = TokenStore(
transient_store=transient_store)`` instance and pass it to all five
sites (probe + dispatcher + PM supervisor + Engineer supervisor +
``serve_mcp(store=...)``). Reader and writer share the same
transient-aware authority.

Three guards:

1. Source-level: ``_run_lifespan_body``'s ``serve_mcp(...)`` call
   contains ``store=`` literal. Catches the immediate β-S11-15 regression
   where ``store=`` is silently dropped from the kwarg list.

2. Source-level: every ``TokenStore(`` construction inside
   ``_run_lifespan_body`` includes ``transient_store=`` OR refers to the
   single shared ``token_store`` variable. Catches a future refactor
   that re-introduces a bare ``TokenStore()`` somewhere in the lifespan,
   reopening the same reader-writer-divergence bug shape against any new
   call site.

3. Behavioral: demonstrates the bug shape — a transient token issued via
   ``TokenStore(transient_store=...)`` is NOT resolvable by a vanilla
   ``TokenStore()`` (the pre-fix server.py:357 fallback shape). This is
   the failure mode the β-S11-15 fix prevents.

This directory is intentionally FLAT (no ``__init__.py``) per the slice
1a established convention.
"""

from __future__ import annotations

import inspect
import re
from pathlib import Path

import pytest

from meridian_orchestrator import main as main_module
from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.transient_tokens import TransientTokenStore


# ---------------------------------------------------------------------------
# Source-level guards
# ---------------------------------------------------------------------------


def test_lifespan_serve_mcp_call_passes_store_kwarg() -> None:
    """β-S11-15 root-cause guard: ``_run_lifespan_body`` must pass
    ``store=`` to ``serve_mcp()``. Without it, ``build_server`` falls
    through to ``server.py:`store if store is not None else TokenStore()``
    which constructs a keyring-only TokenStore for the MCP server's auth
    path. SQLite-stored per-spawn supervisor tokens then fail Bearer
    auth, supervisor subprocesses exit clean with last_tool_called=None,
    and the 3-respawn cap halts both PM and Engineer supervisors.
    """
    source = inspect.getsource(main_module._run_lifespan_body)
    assert "serve_mcp(" in source, (
        "_run_lifespan_body source does not contain serve_mcp( — has the "
        "MCP server startup moved? Update the test to point at the new "
        "construction site AND verify the new site passes store=."
    )
    # Find the serve_mcp call's kwarg window. The call spans multiple
    # lines; ~800 chars after the opening paren covers all current
    # kwargs comfortably.
    after = source.split("serve_mcp(", 1)[1]
    window = after[:800]
    assert "store=" in window, (
        "_run_lifespan_body's serve_mcp() call does not contain `store=` "
        "kwarg within ~800 chars after the opening paren. β-S11-15: when "
        "store is omitted, server.py:357's fallback constructs a "
        "keyring-only TokenStore for the MCP server's auth path, breaking "
        "Bearer-token lookup for any SQLite-stored transient token."
    )


def test_lifespan_every_token_store_construction_is_transient_aware() -> None:
    """β-S11-15 broader guard: every ``TokenStore(`` construction inside
    ``_run_lifespan_body`` must either (a) include ``transient_store=`` or
    (b) be the single shared ``token_store = TokenStore(...)`` assignment
    that the rest of the lifespan reuses. A bare ``TokenStore()`` would
    re-open the reader-writer-divergence bug shape against whatever
    component received it.

    Mechanism: ``inspect`` the function source, scan for ``TokenStore(``
    occurrences, and check the immediately-following argument list.
    Comment lines (which contain ``TokenStore`` references for
    documentation) are excluded by the leading-``#`` filter.
    """
    source = inspect.getsource(main_module._run_lifespan_body)
    # Strip comment-only lines so the documentation references don't
    # trip the scan.
    code_lines = [
        line for line in source.splitlines()
        if not line.lstrip().startswith("#")
    ]
    code = "\n".join(code_lines)

    # Find every TokenStore(...) construction. Match the opening paren
    # and the matching close paren on the same logical block (≤3 lines).
    pattern = re.compile(r"\bTokenStore\(", re.MULTILINE)
    matches = list(pattern.finditer(code))
    assert matches, (
        "no TokenStore() construction found inside _run_lifespan_body — "
        "test is stale; update to match the refactored lifespan wiring."
    )

    for match in matches:
        start = match.end()
        # Extract the argument list — read until the matching `)` with
        # simple paren-depth tracking, capped at 300 chars defensively.
        depth = 1
        end = start
        for i, ch in enumerate(code[start:start + 300]):
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    end = start + i
                    break
        args = code[start:end].strip()
        assert "transient_store=" in args, (
            f"TokenStore(...) construction at offset {match.start()} in "
            f"_run_lifespan_body does NOT include `transient_store=` "
            f"in its argument list (args=`{args[:120]}`). β-S11-15 "
            f"reopened: this is the bug shape — bare TokenStore() reads "
            f"keyring only and cannot resolve SQLite-stored transient "
            f"tokens written by the supervisor/dispatcher writers. Either "
            f"thread the shared lifespan token_store variable to this "
            f"construction site, or pass transient_store= explicitly."
        )


# ---------------------------------------------------------------------------
# Behavioral guard — bug-shape demonstration
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_transient_token_invisible_to_keyring_only_token_store(
    tmp_path: Path,
) -> None:
    """β-S11-15 bug-shape demonstration: a transient token issued via
    TokenStore-wired-with-TransientTokenStore (writer) is NOT resolvable
    by a vanilla TokenStore() (reader without transient_store). This is
    exactly the inconsistency the β-S11-15 fix prevents — pre-fix,
    server.py:357 fell through to vanilla TokenStore() and silently
    rejected all Bearer auth lookups for SQLite-stored tokens.

    Failure mode signature (before fix): ``supervisor.halted`` audit
    events for both PM-continuous and engineer-supervisor with
    ``last_tool_called_per_dispatch=[None, None, None]`` and
    ``recovery='service restart required'`` ~4 minutes after daemon
    boot.
    """
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    try:
        await run_migrations(s)

        transient_store = TransientTokenStore(s)
        writer = TokenStore(transient_store=transient_store)

        # Writer issues a transient token — lands in SQLite
        # transient_tokens via the indexed=False routing.
        issued = await writer.issue(
            agent_id="test-agent-id",
            role="engineer",
            label="test-bug-shape",
            indexed=False,
        )

        # Bug shape (pre-fix): a vanilla TokenStore() — the kind
        # constructed by server.py:357's fallback when build_server is
        # called without `store=` — cannot resolve the token.
        reader_buggy = TokenStore()
        result_buggy = await reader_buggy.lookup(issued.token)
        assert result_buggy is None, (
            "expected keyring-only TokenStore to NOT resolve "
            "SQLite-stored transient token; got a non-None record. The "
            "bug-shape demonstration is invalidated — either the routing "
            "fell back to keyring (slice 1a regression) or vanilla "
            "TokenStore now exposes SQLite by default (also a fix path; "
            "would obsolete this test)."
        )

        # Fix shape: a TokenStore wired with the same transient_store
        # the writer used DOES resolve the token. This is the invariant
        # the β-S11-15 fix preserves by extracting a single
        # lifespan-scoped token_store instance and passing it to both
        # writers (dispatcher/supervisors) AND the reader (serve_mcp).
        reader_fixed = TokenStore(transient_store=transient_store)
        result_fixed = await reader_fixed.lookup(issued.token)
        assert result_fixed is not None, (
            "transient-aware TokenStore should resolve the SQLite-stored "
            "transient token; got None. Either TransientTokenStore.lookup "
            "is broken or the TokenStore wiring no longer delegates."
        )
        assert result_fixed.agent_id == "test-agent-id"
        assert result_fixed.role == "engineer"
    finally:
        await s.close()
