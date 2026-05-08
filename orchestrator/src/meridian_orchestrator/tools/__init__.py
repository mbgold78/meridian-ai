"""MCP tool implementations.

Each tool module wires its inputs/outputs (pydantic models per
``docs/kickoff/06-mcp-tools-phase-one.md``) and its handler into the
:func:`server.build_server` registration step. M3 ships ``agents`` (list,
config, org-chart). M4 onward adds tasks, dispatch, audit, etc.
"""

from __future__ import annotations
