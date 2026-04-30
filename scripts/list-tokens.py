"""List all MCP auth tokens (active and revoked) without revealing secrets.

Usage:
    uv run python scripts/list-tokens.py
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from meridian_orchestrator.auth import TokenStore


def main() -> int:
    store = TokenStore()
    records = store.list_records()
    if not records:
        print("No tokens issued yet.")
        return 0
    print(f"{'token_id':<18} {'role':<14} {'status':<10} {'agent_id':<38} label")
    print("-" * 100)
    for r in records:
        status = "revoked" if r.revoked_at else "active"
        print(f"{r.token_id:<18} {r.role:<14} {status:<10} {r.agent_id:<38} {r.label}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
