"""Issue a new MCP auth token and print it to stdout exactly once.

Usage:
    uv run python scripts/issue-token.py --role pm --label "mike-claude-desktop"

The printed token is the only time the secret is shown. Paste it into
Claude Desktop's MCP connector configuration immediately. Re-issue if lost.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from meridian_orchestrator.auth import TokenStore


def main() -> int:
    parser = argparse.ArgumentParser(description="Issue a new MCP auth token.")
    parser.add_argument(
        "--role",
        required=True,
        help="Agent role this token authenticates as (pm, engineer, implementer, tester).",
    )
    parser.add_argument(
        "--label",
        default="",
        help="Free-text label for the token (e.g. 'mike-claude-desktop').",
    )
    parser.add_argument(
        "--agent-id",
        default=None,
        help="Optional pre-allocated agent UUID. Generated if omitted.",
    )
    args = parser.parse_args()

    store = TokenStore()
    issued = store.issue(agent_id=args.agent_id, role=args.role, label=args.label)
    print("Token issued. Copy it now — it will not be shown again.")
    print()
    print(f"  token:     {issued.token}")
    print(f"  token_id:  {issued.record.token_id}")
    print(f"  agent_id:  {issued.record.agent_id}")
    print(f"  role:      {issued.record.role}")
    print(f"  label:     {issued.record.label}")
    print(f"  issued_at: {issued.record.issued_at}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
