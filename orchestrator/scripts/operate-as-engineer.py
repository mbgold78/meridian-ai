"""Operator wrapper: open Claude Code session pre-loaded with Engineer
role-discipline.

Mirror of :mod:`scripts.operate-as-pm` for the Engineer role. Loads
``config/agents/engineer.yaml`` system_prompt instead. Use case:
operator manually acts as Engineer for verification or ad-hoc dispatch
when the Engineer-supervisor isn't running OR for development-time
debugging of Engineer's prompt evolution.

Pre-conditions: identical to operate-as-pm. See its module docstring.

Invocation:
    uv run python -m scripts.operate-as-engineer
    uv run python -m scripts.operate-as-engineer --resume

Implementation lives in :mod:`scripts._operator_wrapper`.
"""

from __future__ import annotations

import sys

from scripts._operator_wrapper import operator_main

if __name__ == "__main__":
    sys.exit(operator_main("engineer", sys.argv[1:]))
