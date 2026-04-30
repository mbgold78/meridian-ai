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
    header = (
        f"{'token_id':<18} {'role':<10} {'source':<8} {'status':<10} "
        f"{'client_id':<24} {'agent_id':<38} label"
    )
    print(header)
    print("-" * len(header))
    for r in records:
        if r.revoked_at:
            status = "revoked"
        elif r.is_expired():
            status = "expired"
        else:
            status = "active"
        client = r.client_id or "-"
        print(
            f"{r.token_id:<18} {r.role:<10} {r.source:<8} {status:<10} "
            f"{client:<24} {r.agent_id:<38} {r.label}"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
