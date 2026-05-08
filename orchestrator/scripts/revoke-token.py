"""Revoke a previously issued MCP auth token.

Usage:
    uv run python scripts/revoke-token.py <token_id>

Token IDs are visible via ``list-tokens.py``. Revocation is a one-way operation
that records ``revoked_at`` on the keyring record; the secret remains stored so
audits can reconstruct who held the token.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from meridian_orchestrator.auth import TokenStore


def main() -> int:
    parser = argparse.ArgumentParser(description="Revoke an MCP auth token.")
    parser.add_argument("token_id", help="The token_id (not the secret) to revoke.")
    args = parser.parse_args()

    store = TokenStore()
    if store.revoke(args.token_id):
        print(f"Token {args.token_id} revoked.")
        return 0
    print(f"No active token with id {args.token_id} (already revoked or unknown).")
    return 1


if __name__ == "__main__":
    sys.exit(main())
