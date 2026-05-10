"""Operator wrapper: open Claude Code session pre-loaded with PM
role-discipline.

Reads ``config/agents/pm.yaml``'s ``system_prompt`` field and spawns
``claude`` with ``--append-system-prompt <yaml.system_prompt>`` so the
operator's interactive session inherits PM's behavioral discipline at
session start. The operator's session uses OAuth authentication against
the running orchestrator daemon (Component 1's OAuth-PM registry binding
+ alpha persistence).

Pre-conditions (script does NOT verify; documented for the operator):
- Daemon running locally (``uv run python -m meridian_orchestrator.main``)
- ``meridian-orchestrator`` MCP registered (``claude mcp add --transport
  http -s user meridian-orchestrator http://127.0.0.1:8765/mcp``)
- OAuth flow completed (or will be completed inside the spawned session
  via ``/mcp`` -> Authenticate)

Invocation:
    uv run python -m scripts.operate-as-pm
    uv run python -m scripts.operate-as-pm --resume
    uv run python -m scripts.operate-as-pm --resume <session-id>

Any extra command-line args are forwarded verbatim to the underlying
``claude`` invocation. The operator retains full access to claude's flag
surface — composability over lock-in.

Convention: matches kebab-case naming used by other ``scripts/`` modules
(``init-db``, ``issue-token``, ``list-tokens``, ``revoke-token``);
invoked via ``uv run python -m scripts.<name>`` consistent with existing
project tooling.

Implementation lives in :mod:`scripts._operator_wrapper`; this script
is a thin entry point so the symmetric ``operate-as-engineer.py`` reuses
the same code without filename-import-identifier conflicts (hyphens are
parser errors in Python import statements).
"""

from __future__ import annotations

import sys

from scripts._operator_wrapper import operator_main

if __name__ == "__main__":
    sys.exit(operator_main("pm", sys.argv[1:]))
