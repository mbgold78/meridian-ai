# Meridian Orchestrator

Internal infrastructure for Meridian AI's AI-staffed company operations.

This is the Phase 1 build of the Orchestrator — a long-lived Python daemon that coordinates an AI-staffed company hierarchy via MCP. The operator (Mike) talks to a PM agent in Claude Code (the CLI); PM coordinates with an Engineer agent that dispatches Implementer and Tester sub-agents into the substrate codebase.

Design documents live in `docs/kickoff/`. Read `00-README.md` first.

## Status

- M1: Project skeleton + storage layer
- M2: MCP server scaffolding + auth (`ping` tool over Streamable-HTTP on loopback; OAuth 2.0 AS for Claude Code's connector; static keyring tokens for daemon-launched subagents)
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

### One-time operator setup

Before the first daemon start, copy agent yamls from the project repo to
the runtime data directory:

```powershell
New-Item -ItemType Directory -Force -Path "$env:USERPROFILE\.meridian-orchestrator\config\agents" | Out-Null
Copy-Item "config\agents\*.yaml" "$env:USERPROFILE\.meridian-orchestrator\config\agents\"
```

Set the substrate working directory env var (or add to `.env`):

```powershell
$env:MERIDIAN_ORCH_SUBSTRATE_WORKING_DIRECTORY="C:\path\to\your\substrate"
```

Issue agent role tokens (one-time per machine):

```powershell
uv run python -m scripts.issue-token --role pm --label "$env:USERNAME-cd"
uv run python -m scripts.issue-token --role engineer --label "$env:USERNAME-cd"
uv run python -m scripts.issue-token --role implementer --label "$env:USERNAME-cd"
uv run python -m scripts.issue-token --role tester --label "$env:USERNAME-cd"
```

(Auto-seeding of the runtime yaml dir is pinned in BACKLOG.md
§ "Agent yaml runtime-dir auto-seeding" — until that ships, the manual
copy above is operationally required.)

### Start the daemon

```bash
uv run python -m meridian_orchestrator.main
```

The daemon connects to the DB, runs migrations, and starts the MCP server on
`http://127.0.0.1:8765/mcp` (configurable via `MERIDIAN_ORCH_MCP_HOST` /
`MERIDIAN_ORCH_MCP_PORT`). It refuses non-loopback bind addresses unless
`MERIDIAN_ORCH_MCP_ALLOW_NON_LOOPBACK=true` is set explicitly. Shuts down
cleanly on SIGTERM / SIGINT / SIGBREAK.

### Operator wrappers (shortcut for interactive PM / Engineer sessions)

Once the daemon is running and OAuth flow is completed once via
`/mcp` → Authenticate inside a Claude Code session, you can spawn
role-disciplined sessions via the wrapper scripts:

```bash
uv run python -m scripts.operate-as-pm
uv run python -m scripts.operate-as-engineer
```

Both load the role's `system_prompt` from `config/agents/<role>.yaml` and
spawn `claude --dangerously-skip-permissions --append-system-prompt
<system_prompt>`. Extra command-line args are forwarded to the
underlying `claude` invocation (e.g., `--resume <session-id>`).

**Why plain HTTP and not TLS?** Phase 1 binds loopback only. There is no
over-the-wire threat between processes on `127.0.0.1`; an attacker who can
sniff loopback traffic already has the privileges to read the keyring
directly. TLS adds operational complexity (cert generation, OS trust store
import, Bun-vs-Node trust mismatches in MCP clients) for zero security gain
on a loopback socket. When Phase 2 deploys to a non-loopback bind, TLS
goes back in.

## Auth: two coexisting flows

The daemon accepts Bearer tokens from two sources, both stored in the OS
keychain (Windows Credential Manager on Mike's machine):

| Source   | Used by                                         | How issued                                 |
|----------|-------------------------------------------------|--------------------------------------------|
| `oauth`  | Mike's PM agent via Claude Code's connector     | OAuth 2.0 authorization-code flow + PKCE  |
| `static` | M3+ daemon-launched Engineer/Implementer/Tester | `scripts/issue-token.py` (operator action) |

Tokens from either source flow through the same verifier; the role on the
token determines what MCP tools the caller can invoke. `list-tokens.py` shows
the source so you can audit which credentials came from where.

### Claude Code (OAuth) — the load-bearing path for Mike

Claude Code's MCP connector does the OAuth dance automatically using
RFC 8252 loopback redirects, which is exactly the local-OAuth pattern this
daemon implements. (Claude Desktop's GUI Custom Connectors are *not*
supported — that flow is cloud-mediated and cannot reach a localhost
daemon. See the design notes in `docs/kickoff/`.)

```bash
claude mcp add --transport http -s user meridian-orchestrator http://127.0.0.1:8765/mcp
claude
```

The `-s user` scope flag is load-bearing — without it `claude mcp add`
defaults to local scope (per `claude mcp add --help`), binding the
registration to the current cwd's claude config and making the
orchestrator MCP server invisible to the Claude Code session when
launched from any other directory. Verified empirically at M5-acceptance
commit 3 prep (2026-05-08).

Inside the Claude Code session, `/mcp` shows the server with an Authenticate
option. Picking it triggers:

1. Discovery: GET `/.well-known/oauth-authorization-server`
2. RFC 7591 dynamic client registration: POST `/register` with a loopback
   `redirect_uri` like `http://localhost:<port>/callback`
3. Browser opens to `/authorize`; the daemon redirects to a local **approval
   page** (`/approve`) that shows the connector name, redirect URI, scopes
   requested, and the role the token will be granted. Read it. Click **Approve**.
4. Browser redirects back to Claude Code's loopback listener with an
   authorization code; Claude Code POSTs to `/token` (PKCE-verified) and
   stores the resulting access token.
5. Future MCP calls include `Authorization: Bearer <token>`. The `ping` tool
   round-trips and returns the caller's resolved `agent_id`.

The issued token has `source=oauth` and a 30-day expiry. Revoke any time
via `scripts/revoke-token.py <token_id>`, or via Claude Code's `/mcp` menu.

**Loopback-only redirect URIs.** The daemon refuses any `redirect_uri` that
isn't `127.0.0.1`, `::1`, or `localhost` — same threat-model reasoning as
the loopback-bind default. Phase 1 has no reason to allow off-machine
redirects.

### Subagent tokens (static) — for M3+

M3 introduces Engineer/Implementer/Tester subagents that run as daemon-
launched subprocesses. They cannot do an interactive OAuth flow, so the
operator pre-issues static bearer tokens for them via the CLI:

```bash
uv run python scripts/issue-token.py --role engineer --label "engineer-default"
uv run python scripts/list-tokens.py
uv run python scripts/revoke-token.py <token_id>
```

`issue-token.py` prints the full token exactly once. The subagent reads it
from environment / config at launch time. These tokens have `source=static`
and no expiry until revoked.

**Don't use static tokens for Claude Code.** Claude Code's connector UI does
OAuth automatically; manually pasting a static token wouldn't fit the flow.
Static tokens are for non-interactive M3+ daemon-launched agents.

### Long-running supervisor preconditions (M4c+)

Two long-running supervisors run inside the daemon at flag-flip time:
`EngineerSupervisor` (M4c, dormant by default) and `PMSupervisor` (M5,
dormant by default). Both follow the same operator-setup pattern: a
**static seeding token issued once** before the daemon starts, used by
the `AgentRegistry` at startup to resolve the supervisor's `agent_id`.
The supervisor itself issues a separate per-spawn token at launch time
with a distinguishing `label` — the seed token is for registry
resolution only, never used for actual MCP calls.

```bash
# Required once before enabling MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR=true
uv run python scripts/issue-token.py --role engineer --label "engineer-seed"

# Required once before enabling MERIDIAN_ORCH_ENABLE_PM_SUPERVISOR=true
uv run python scripts/issue-token.py --role pm --label "pm-seed"
```

The `--label` is operator-facing only — anything that distinguishes it
from a future supervisor-issued token is fine. The supervisors mint
their own per-spawn tokens labeled `engineer-supervisor` and
`pm-continuous` respectively at `start()` time; those are the tokens the
subprocess actually authenticates with.

If the registry can't resolve a supervisor's role at daemon startup
(seed token missing or revoked), construction raises a `ValidationError`
naming the missing role. The daemon refuses to start with the supervisor
flag enabled until the operator issues the token.

| Setting                                  | Default | Description                                              |
|------------------------------------------|---------|----------------------------------------------------------|
| `MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR` | `false` | Engineer supervisor on. Requires `engineer` seed token. |
| `MERIDIAN_ORCH_ENABLE_PM_SUPERVISOR`     | `false` | PM continuous supervisor on. Requires `pm` seed token.   |
| `MERIDIAN_ORCH_SUBSTRATE_WORKING_DIRECTORY` | (unset) | Path to operator's substrate clone. Required when engineer supervisor flag is on (canonical-clone provisioning reads `origin` from here). |

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
