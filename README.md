# Meridian Orchestrator

Internal infrastructure for Meridian AI's AI-staffed company operations.

This is the Phase 1 build of the Orchestrator — a long-lived Python daemon that coordinates an AI-staffed company hierarchy via MCP. The operator (Mike) talks to a PM agent in Claude Desktop; PM coordinates with an Engineer agent that dispatches Implementer and Tester sub-agents into the substrate codebase.

Design documents live in `docs/kickoff/`. Read `00-README.md` first.

## Status

- M1: Project skeleton + storage layer
- M2: MCP server scaffolding + auth (`ping` tool over Streamable-HTTP, keyring-backed bearer tokens)
- M3: Agent config loading + permission framework + bridge design (pending)
- M4: Task management + subagent dispatch with parallel (pending)
- M5: Budget & usage + window-aware planner + continuous-mode driver (pending)
- M6: Knowledge & routing + audit log + notification service (pending)
- M7: Service deployment + smoke test + hardening (pending)

## Requirements

- Python 3.12+ (uv will fetch one if your system Python is older)
- [`uv`](https://docs.astral.sh/uv/) for package management

## Install

```bash
uv sync
```

## Initialize the database

```bash
uv run python scripts/init-db.py
```

Creates `${MERIDIAN_ORCH_DATA_DIR}/data/orchestrator.db` (default `~/.meridian-orchestrator/data/orchestrator.db`), runs migrations, and seeds the Meridian AI company plus the Substrate Product project. Override the data dir via `.env` (copy `.env.example` to `.env` and edit).

## Run the daemon

```bash
uv run python -m meridian_orchestrator.main
```

The daemon connects to the DB, runs migrations, and starts the MCP server on
`https://127.0.0.1:8765/mcp` (configurable via `MERIDIAN_ORCH_MCP_HOST` /
`MERIDIAN_ORCH_MCP_PORT`). It refuses non-loopback bind addresses unless
`MERIDIAN_ORCH_MCP_ALLOW_NON_LOOPBACK=true` is set explicitly. Shuts down
cleanly on SIGTERM / SIGINT / SIGBREAK.

## TLS

Claude Desktop's MCP connector UI rejects `http://` URLs, so the daemon serves
Streamable-HTTP over TLS. On first startup it generates a self-signed cert
under `~/.meridian-orchestrator/certs/`:

```
certs/
├── server.crt   # PEM-encoded cert (CN=meridian-orchestrator-local, RSA-2048, SHA-256, 365-day validity)
└── server.key   # PKCS8 private key, owner-only ACL
```

The cert's SHA-256 fingerprint is logged at every startup (`event=tls.cert_loaded`).
The cert is regenerated only when missing or expired; Phase 1 has no automated
renewal (see `BACKLOG.md`).

### Trusting the cert in Claude Desktop

The first time you connect, Claude Desktop will warn that the cert is not
trusted. Either:

1. **Accept in-app** — if Claude Desktop offers a "trust this connection"
   prompt, click through it. Easiest path; trust is scoped to that connector.
2. **Import into Windows Trusted Root Certification Authorities** — fallback
   if Claude Desktop refuses self-signed certs:
   - Open `certmgr.msc` (Run → `certmgr.msc`).
   - Navigate to `Trusted Root Certification Authorities` → `Certificates`.
   - Right-click → `All Tasks` → `Import...`.
   - Select `~/.meridian-orchestrator/certs/server.crt`.
   - Confirm the security warning. Verify the SHA-256 fingerprint shown matches
     the one logged at daemon startup.

After regeneration (cert expiry or manual deletion), repeat the trust step.

## Issue an MCP token and connect Claude Desktop

Tokens authenticate per-agent MCP connections. They live in the OS keychain
(Windows Credential Manager on Mike's machine), not in the database or `.env`.

```bash
uv run python scripts/issue-token.py --role pm --label "mike-claude-desktop"
uv run python scripts/list-tokens.py
uv run python scripts/revoke-token.py <token_id>
```

`issue-token.py` prints the full token exactly once. Paste the value into
Claude Desktop's MCP connector configuration as a Bearer credential against
the URL `https://127.0.0.1:8765/mcp` (after trusting the cert per the TLS
section above). The `ping` tool round-trips and returns the resolved
`agent_id` — first proof that the auth chain works.

## Test

```bash
uv run pytest
uv run ruff check src/ tests/
uv run mypy --strict src/
```

## Layout

```
src/meridian_orchestrator/   service code
docs/kickoff/                 design documents — start at 00-README.md
tests/unit/                   mirrors src/
tests/integration/            scenario-based (M2+)
scripts/                      init-db, smoke-test, sync-substrate-knowledge
service/                      launchd/systemd/Windows Service configs (M7)
```

## License

Proprietary. © Meridian AI, LLC.
