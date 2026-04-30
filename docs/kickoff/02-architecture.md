# Architecture

## Service shape

The Orchestrator is a long-lived Python daemon. It exposes its capabilities over MCP. Claude Code is the human interface — Mike opens an interactive Claude Code session with the orchestrator MCP server connected (PM role bound via OAuth at first connection; see M2 in `08-implementation-plan.md`). There is no custom chat UI, no webserver, no browser app for v1. (The kickoff originally specified Claude Desktop's GUI Custom Connectors; M2 surfaced that those are cloud-mediated and cannot reach a localhost daemon — see `11-engineer-bridge.md` § Cross-doc cleanup scope for context. Phase 1 binds plain HTTP on loopback; Claude Code's RFC 8252 loopback OAuth flow is the supported connector path.)

```
┌────────────────────────────┐         ┌──────────────────────────────────┐
│  Claude Code (Mike)        │         │  Orchestrator daemon (Python)    │
│  ┌──────────────────────┐  │ ─MCP→   │                                  │
│  │ PM system prompt     │  │         │  ┌────────────────────────────┐  │
│  │ (configured locally) │  │         │  │ MCP server (anthropic SDK) │  │
│  └──────────────────────┘  │         │  ├────────────────────────────┤  │
└────────────────────────────┘         │  │ Auth (keyring tokens)      │  │
                                       │  │ Permission framework        │  │
                                       │  │ Hierarchy enforcement       │  │
                                       │  │ Tool dispatch (async)       │  │
                                       │  ├────────────────────────────┤  │
                                       │  │ Storage (aiosqlite)         │  │
                                       │  │ Audit log writer            │  │
                                       │  │ Budget/usage tracker        │  │
                                       │  │ Knowledge FS layer          │  │
                                       │  │ Engineer↔subagent bridge    │  │
                                       │  └────────────────────────────┘  │
                                       └──────────────────────────────────┘
                                                      │
                                                      ↓
                                       ┌──────────────────────────────────┐
                                       │  Filesystem & SQLite              │
                                       │  ~/.meridian-orchestrator/        │
                                       └──────────────────────────────────┘
                                                      ↓
                                       ┌──────────────────────────────────┐
                                       │  Engineer-dispatched sub-agents   │
                                       │  operating in                     │
                                       │  C:\Users\mbgol\dev\              │
                                       │    meridian-homepoc\              │
                                       └──────────────────────────────────┘
```

## Local-only for v1

Mike's laptop. No remote access. No Tailscale. No cloud. Architecture remains cloud-portable so a future move is a config change, not a rewrite, but no remote work happens in Phase 1.

Implications baked in:
- Storage is abstracted behind an interface (`storage/` module) so SQLite + local FS can be swapped for Postgres + S3 later as a single layer change.
- Auth uses OS keychain via `keyring` (Windows Credential Manager on Mike's machine). No remote secret manager dependency.
- All paths in code are relative to `${MERIDIAN_ORCH_DATA_DIR}` (default `~/.meridian-orchestrator/`), set in `.env` and overridable.

## Multi-tenant ready, single-tenant in v1

The schema includes `company_id` and `project_id` columns on every operational table. Phase 1 seeds:
- One company: **Meridian AI**
- One project: **Meridian Substrate Product**
- Working directory for substrate work: `C:\Users\mbgol\dev\meridian-homepoc\` (parameterized)

When Mike eventually adds a second company or a second project, no migrations needed.

## Single async process

One Python process, `asyncio` everywhere. No separate worker queue (no Celery, no Redis) in v1. The MCP server, the audit logger, the budget tracker, the daily-backup task, and the agent-config file watcher all run as `asyncio` tasks under one supervisor.

When tool calls are heavy (e.g., subagent dispatch), they run as awaited tasks; the MCP request returns when the dispatch completes. For long-running dispatches, the design uses ID-and-poll: `dispatch_*` returns a dispatch_id, Engineer polls `get_dispatch_status` and `get_dispatch_result`. This pattern is established in `06-mcp-tools-phase-one.md` and finalized at M3 with the bridge architecture.

## MCP-as-service, not webserver

There is no HTTP API. The only protocol is MCP. The MCP server is constructed using Anthropic's official Python MCP SDK (`mcp` package). Authentication is a per-agent token (issued by Mike, stored in OS keychain). Each MCP connection presents a token; the server resolves the token to an agent_id and uses that agent_id for every permission check downstream.

Tools are async functions registered with the MCP server. Each tool has a pydantic input schema and output schema. Tool errors are domain exceptions (`AuthError`, `PermissionError`, `BudgetError`, `NotFoundError`) — never bare `Exception`. Errors don't leak permission graph details: the response is "permission denied for tool X" with no enumeration of who can call what.

## Audited writes

Every state-changing tool call is recorded in `audit_log` with: actor (agent_id), timestamp, action, target, details_json, success boolean, and error_message if failed. Reads are not audited by default to control noise; specific reads (e.g., `get_audit_log` itself, `read_knowledge` for sensitive paths) can be opted in.

The audit log is the source of truth for "who did what, when, on whose behalf." When sub-agents are dispatched, the dispatch chain is captured in `details_json` so a Decline propagation can be reconstructed.

## Permission-scoped tools

Each agent's YAML config lists `mcp_tools_allowed`. The MCP server enforces the list server-side — not by trust. Hierarchy enforcement is a property of the permission framework, not a separate system:

- PM has `submit_task`, `get_queue`, knowledge tools, dashboard tools — but **not** `dispatch_implementer`. PM cannot bypass Engineer.
- Engineer has dispatch tools and task management — but **not** `get_dashboard_summary`. Engineer cannot surface to Mike directly.
- Implementer/Tester have minimal tools: `record_api_call`, `read_knowledge`, `update_task_status` (own claimed tasks only). They cannot read the queue, cannot submit new tasks, cannot dispatch.

Full list and rationale in `06-mcp-tools-phase-one.md`.

## Idempotency

Tool calls are safe to retry. State transitions check current state before applying. Deterministic IDs are used where they make sense (e.g., audit_log entries are UUIDs but seeded such that the same logical event produces the same hash if needed). `claim_task` uses a conditional UPDATE (`WHERE status = 'pending'`) to make double-claim a no-op rather than a corruption.

## Subscription-first cost model

Phase 1 runs entirely on Mike's Claude Pro/Max subscription. The Engineer↔subagent bridge architecture chosen at M3 must be subscription-compatible by default. The current likely choice is Option A (subprocess Claude Code), which inherits the subscription via Claude Code's existing auth.

API credits remain available as overflow for specific high-token dispatches but are not required for Phase 1 completion. Rate-limit handling is graceful: log the event, surface to PM, surface to Mike via session report, do not silently retry.

## Subscription utilization model

The subscription-first cost model has a corollary: **idle subscription capacity is wasted capacity.** Phase 1 commits to *aggressive* utilization — PM operates continuously when Mike is not interactive, dispatching work to Engineer as fast as the rolling 5-hour window allows. The Orchestrator is "deliberate about budget," not "polite about budget."

### PM operating modes

PM has two first-class operating modes:

- **Interactive** — Mike is in an interactive Claude Code session with PM. Mike's session has priority; PM throttles continuous dispatch to leave window headroom for the conversation. Sliding 30-minute window from Mike's last message before PM returns to continuous mode.
- **Continuous** — Default when Mike is not interactive. PM autonomously advances the queue, dispatches to Engineer in parallel where dependencies allow, sends batched Telegram updates, pauses only on `question` / `urgent` items that genuinely require Mike. Independent threads continue while waiting on a Mike reply to one open question.

**Architectural realization (decided at M3, see `11-engineer-bridge.md`):** both the interactive PM and the continuous PM are Claude Code subprocesses. The interactive PM is the session Mike opens directly (authenticated via OAuth — Claude Code's loopback flow against the orchestrator's OAuth AS). The continuous PM is a long-running, daemon-launched Claude Code subprocess (authenticated via a daemon-issued static token with `label='pm-continuous'`). The two PM instances are different Claude conversations with the same system prompt and MCP access; they coordinate through the database via `pm_mode_log` and the task queue, never directly. When the daemon observes an MCP call from a token with `(source='oauth', role='pm')` — which is unforgeable, set only by the OAuth provider at issuance — it writes `mode='interactive'` to `pm_mode_log`, and the continuous-mode subprocess pauses dispatch on its next planning iteration. The M3 bridge decision is Option A (subprocess Claude Code) with MCP-tool completion (the `complete_dispatch` tool) as the structured-output mechanism for sub-agent dispatch.

### Window-aware burn

PM consumes `get_window_state` on every planning iteration. The pace recommendation is one of four states:

| Window state | Pace | PM behavior |
|---|---|---|
| > 50% remaining, > 2h to reset | `normal` | Dispatch as needed |
| > 50% remaining, < 1h to reset | `upgrade` | Actively promote lower-priority work into unused capacity |
| < 50% remaining, > 1h to reset | `normal` (defer optional polish-tier) | Continue at normal pace; defer non-essential work to next window |
| < 20% remaining | `conserve` | Finish in-flight work, no new high-token dispatches |
| Rate-limited | `rate_limited` | Surface as `question`-urgency to Mike, resume aggressively at reset |

The `upgrade` transition is the load-bearing behavior — PM actively looks for work to promote into windows with unused capacity rather than passively waiting for reset.

### Parallel sub-agent dispatch

Engineer dispatches Implementer/Tester subagents in parallel where the work has no file-level dependencies. Two phases touching different substrate files dispatch simultaneously; two phases touching the same files must serialize. Engineer maintains a dispatch graph that drives the parallelism decisions.

The bridge architecture (M3) must support: concurrent subprocess invocations, isolated working directories per dispatch (e.g., per-dispatch git worktrees against per-dispatch branches), and merge logic for landing parallel branches back into the canonical working tree. This is not a Phase 2 optimization — it ships in Phase 1 because subscription utilization roughly doubles compared to single-threaded sequential dispatch.

### Utilization targets

During Mike's work hours (8 AM – 6 PM ET, weekdays), continuous-mode utilization should run **70–90% of available subscription capacity.** Lower utilization is acceptable when (a) Mike is interactive heavily, (b) the queue has genuinely no dispatchable work, (c) Engineer is rate-limited on substrate codebase work, or (d) PM is paused on a legitimate `question` to Mike with no other independent work. Lower utilization for any other reason is surfaced to Mike as `question`-urgency.

End-of-session reports include a per-window utilization breakdown with attributed reasons for any unused capacity. PM is honest about under-utilization — if the autonomous chain isn't delivering on the "burn the subscription" promise, Mike sees it.

## Notifications (M6)

A Telegram-based notification service implements bidirectional PM↔Mike messaging. PM is the only agent with notification access — Engineer/Implementer/Tester surface upward through the existing task chain.

**Outbound** (PM→Mike): the `notify_human` tool persists to `outbound_messages` and dispatches via the Telegram Bot API. Three urgency tiers:

- `urgent` — strategic non-downgradable escalations or blocking failures. Sent immediately, prefixed `[URGENT]`.
- `question` — answer required to proceed; non-strategic. Sent immediately, prefixed `[QUESTION]`. Marks the related task `blocking_human=1`.
- `info` — foundational decisions, downgrade logs, end-of-session reports. Batched delivery (max 4 per send), end-of-session or hourly.

**Inbound** (Mike→PM): a background asyncio task long-polls Telegram's `getUpdates` endpoint at 30-second intervals, persisting new messages to `inbound_messages`. PM consumes via `get_unread_replies` on session start.

**Failure modes:**

| Condition | Behavior |
|---|---|
| Telegram API unreachable | Outbound queues. Exponential backoff retry. Audit log captures attempts. Dashboard shows backlog if >1h. |
| Bot token invalid | Service starts with notification disabled. Log error. Dashboard shows status indicator. |
| `getUpdates` malformed | Log, skip, continue polling. |
| Inbound message references a deleted task | Store anyway; `in_reply_to` set to NULL. PM handles on consumption. |

**Operational notes:**

- Telegram rate limit: 30 msgs/sec to a single chat. Phase 1 won't approach this.
- Telegram message body limit: 4096 chars. Long bodies truncated with `[...truncated, see audit log]` suffix; full content always in audit log.
- `.env` (containing `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID`) is `.gitignore`'d.
- Inbound polling continues across daemon restart via Telegram's 24h update retention. The service resumes from the last `update_id` checkpoint.

Manual setup steps for Mike are documented in `14-telegram-setup.md`.

## Process model summary

| Concern | v1 choice |
|---|---|
| Process | Single Python daemon |
| Concurrency | `asyncio` |
| Inter-process | None (everything in-process) |
| Storage | SQLite via `aiosqlite` + filesystem |
| Secrets | OS keychain via `keyring`; `.env` for service config (gitignored) |
| Logging | `structlog` JSON to `~/.meridian-orchestrator/logs/` |
| Service manager | launchd (Mac), systemd (Linux), Windows Service (Mike's machine) |
| Backup | SQLite dump on startup + daily; retain last 7 |
| Bridge to sub-agents | M3-decided per `11-engineer-bridge.md`: Option A (subprocess Claude Code) with `complete_dispatch` MCP-tool completion; per-dispatch git worktrees off a daemon-owned canonical clone; parallel concurrent dispatch supported |
| PM operating modes | Interactive (Mike's Claude Code session, OAuth-authenticated) and continuous (daemon-launched Claude Code subprocess, static-token-authenticated); coordinated via `pm_mode_log` |
| Subscription utilization | Aggressive — target 70–90% during work hours; window-aware burn with `upgrade` / `conserve` pace transitions |
| Notifications | Telegram bot, PM-only, bidirectional, M6 deliverable |
