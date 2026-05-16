"""List indexed MCP auth tokens (active and revoked) without revealing secrets.

**Intentional exclusion**: high-churn transient credentials issued by the
daemon — per-dispatch Implementer/Tester tokens and per-spawn PM/Engineer
supervisor tokens — are **NOT** surfaced by this command. They authenticate
normally via MCP Bearer flow and revoke normally on dispatch terminal
cleanup, but they are not registered in the keyring ``_index`` aggregator
to prevent unbounded keyring blob growth (β-S11-5 / β-S11-6 root cause:
Windows Credential Manager's per-credential blob limit is ~2560 bytes;
exceeding it causes ``CredWrite`` to return ``WinError 1783`` and
boot-blocks the daemon). For visibility into transient-token lifecycle,
query the orchestrator ``audit_log`` and ``dispatches`` tables instead —
``dispatch.token_created`` / ``dispatch.token_revoked`` audit rows carry
the full per-token timeline.

What this command DOES show:
- Static seed tokens issued via ``scripts/issue-token.py`` (mike-cd label
  for PM/Engineer/Implementer/Tester roles).
- OAuth tokens issued via Claude Code's connector flow (long-lived,
  one per operator-on-machine; alpha persistence).

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
        print("No indexed tokens issued yet.")
        print(
            "(Transient per-dispatch and per-spawn supervisor tokens are "
            "intentionally excluded; see module docstring.)"
        )
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
