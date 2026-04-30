# Implementation Plan — Milestones M1–M7

Each milestone has: deliverables, acceptance criteria, and a checkpoint with Mike. Per-milestone commits, never giant end-of-phase commits. Tests in the same commit as code. Conventional commits with milestone tag: `feat(M2): add MCP ping tool`.

## M1 — Project skeleton & storage layer

### Deliverables

- `pyproject.toml` with locked dependency versions (per `04-tech-stack.md`)
- Project structure under `src/meridian_orchestrator/` per `04-tech-stack.md`
- `main.py` with a clean startup/shutdown skeleton (no MCP yet)
- `config.py` (pydantic settings, loads `.env`)
- `storage/interface.py` — Protocol for storage backend
- `storage/sqlite.py` — `aiosqlite` implementation
- `storage/migrations.py` — forward-only migration runner
- `migrations/0001_initial.sql` — full schema per `05-data-model.md`
- `storage/models.py` — pydantic row models
- `.env.example` — `MERIDIAN_ORCH_DATA_DIR`, log level, etc.
- `.gitignore` — Python, IDE, `data/`, `logs/`, `.env`
- `scripts/init-db.py` — seeds Meridian AI company + Substrate Product project
- Top-level `README.md` — install, run, what this is
- Top-level `BACKLOG.md` — empty file with section headers (Phase 1, Phase 2, deferred)
- `tests/unit/storage/` — unit tests for migrations, model round-trips, basic CRUD

### Acceptance

- `uv sync` runs clean
- `uv run python -m meridian_orchestrator.main` starts and exits cleanly when sent SIGTERM
- `uv run python scripts/init-db.py` creates `data/orchestrator.db` with all tables
- `uv run pytest tests/unit/storage/` passes
- `uv run ruff check src/ tests/` passes
- `uv run mypy --strict src/` passes

### Checkpoint

Mike runs `uv sync && uv run python scripts/init-db.py && uv run pytest`. Confirms the DB is created with all tables, migrations idempotent on re-run, and all tests pass. No code review yet — this is "is the foundation solid".

---

## M2 — MCP server scaffolding & auth

### Deliverables

- `server.py` — MCP server constructed via Anthropic Python SDK, tool registration plumbing
- `auth.py` — token issuance, lookup, OS keychain via `keyring`
- One test tool: `ping(echo: str) -> {pong: str, agent_id: str, server_time: str}`
- Token management CLI in `scripts/`: `issue-token.py`, `revoke-token.py`, `list-tokens.py`
- README updated with: Claude Code connection instructions (`claude mcp add --transport http ...`) and the OAuth-flow narrative; static-token CLI documented for daemon-launched M3+ subagents (separate auth path)
- `tests/integration/test_mcp_ping.py` — full round-trip via MCP client

### Acceptance

- Service starts and the MCP server is reachable
- A valid token allows connection and `ping` round-trip works
- Invalid/missing token rejected cleanly
- Tokens persist across daemon restart (stored in OS keychain)
- `ping` echoes the input and returns the resolved `agent_id`
- Integration test passes

### Checkpoint

Mike runs `claude mcp add --transport http meridian-orchestrator http://127.0.0.1:8765/mcp`, starts an interactive Claude Code session, runs `/mcp` and picks Authenticate, approves in the browser-rendered approval page, and calls `ping`. Verifies he sees the echoed value and his agent_id. (The static-token CLI from `scripts/issue-token.py` exists in M2 for the M3+ daemon-launched subagent auth path; it is not the operator-onboarding path.) **This is the first real proof that the MCP architecture works on Mike's machine.**

---

## M3 — Agent config loading, permission framework, AND Engineer↔subagent bridge design

### Deliverables

- `agents/config.py` — YAML loader + pydantic validation
- `agents/registry.py` — in-memory registry, file watcher via `watchfiles`
- `agents/permissions.py` — per-tool allowlist + hierarchy enforcement
- Four YAML config files in `~/.meridian-orchestrator/config/agents/`:
  - `pm.yaml` — full system prompt from kickoff
  - `engineer.yaml` — system prompt drafted with Mike's review
  - `implementer.yaml` — migrated from `meridian-homepoc/.claude/agents/meridian-implementer.md`
  - `tester.yaml` — migrated from `meridian-homepoc/.claude/agents/meridian-verifier.md`
- Agent rows seeded on startup from configs (with `reports_to` resolved)
- Three new MCP tools: `list_agents`, `get_agent_config`, `get_org_chart`
- **Updated `docs/kickoff/11-engineer-bridge.md`** — full design with chosen architecture, Mike-reviewed and approved. **Must address two questions: (1) sub-agent dispatch with parallel concurrent invocations, isolated working directories, merge logic; (2) continuous-mode PM driver — how the daemon launches and supervises the long-running PM Claude Code subprocess.**
- PM YAML includes the `Continuous mode` and `Subscription utilization expectations` sections in its system prompt (per `07-agent-configs-phase-one.md`)
- Engineer YAML stub captures parallel-dispatch, window-awareness, and utilization-reporting constraints
- Tests: config validation, hot reload, permission decisions (with hierarchy edge cases), denial-error format

### Acceptance

- All four YAMLs load and validate
- Editing a YAML and saving triggers hot reload (verified by structured log + a permission test that flips on reload)
- Permission checks correct for all matrix entries in `06-mcp-tools-phase-one.md` § Permission summary
- Denial errors do not leak permission graph details
- Engineer↔subagent bridge architecture documented in `11-engineer-bridge.md` and Mike-approved (covers both dispatch and continuous-mode PM driving)

### Checkpoint

Mike: (a) edits `pm.yaml` to remove `get_dashboard_summary` from the allowlist, sees the hot-reload log line, calls the tool from his interactive Claude Code session, gets a clean `PERMISSION_DENIED`, restores the config, sees reload, gets success. (b) Reviews `11-engineer-bridge.md` and approves the bridge architecture (both dispatch and continuous-mode driving) before M4 implementation begins.

---

## M4 — Task management + subagent dispatch (with parallel)

### Deliverables

- `tools/tasks.py` — `submit_task`, `get_queue`, `update_task_status`, `set_task_priority`, `claim_task`, `get_subtasks`
- `submit_task` accepts optional `estimated_tokens`; `update_task_status` to `completed` accepts `actual_tokens`
- Migration `0002_task_planning.sql` — `ALTER TABLE tasks ADD COLUMN estimated_tokens INTEGER; ALTER TABLE tasks ADD COLUMN actual_tokens INTEGER;`
- Task priority is first-class in the queue read paths (`get_queue` ordering on `(status, priority)`); the planner consumes priority + `estimated_tokens` for window-aware decisions starting M5
- `audit/logger.py` — audit log writer wired into every state-changing tool
- `bridge/` — implementation of the M3-approved bridge architecture per `11-engineer-bridge.md`, **including parallel concurrent dispatch with isolated working directories per dispatch and merge logic for landing parallel branches**. Concrete modules: `bridge/dispatcher.py`, `bridge/pm_supervisor.py`, `bridge/engineer_supervisor.py`, `bridge/canonical.py`, `bridge/worktree.py`, `bridge/timeouts.py`
- Migration `0005_dispatches.sql` — `dispatches` table per `11-engineer-bridge.md` § Storage shape, including the `engineer_decisions_json`, `actual_touched_files_json`, `merge_conflict_files_json` columns and the `idx_dispatches_role_completed` partial index
- `tools/dispatch.py` — `dispatch_implementer`, `dispatch_tester`, `get_dispatch_status`, `get_dispatch_result`, **plus the bridge-spec-formalized M4 tool deltas: `complete_dispatch` (sub-agent → Engineer structured-output channel), `get_dispatch_events` (Engineer's polling subscription for in-flight audit events; subtree-scoped; schema in `11-engineer-bridge.md` § `get_dispatch_events` MCP tool), and `get_recent_dispatches` (Engineer's substrate touch map cache read path; PM's post-mortem queries; subtree-scoped; schema in `11-engineer-bridge.md` § `get_recent_dispatches` MCP tool)**
- `DispatchImplementerInput` and `DispatchTesterInput` schemas extended with the `engineer_decisions: list[EngineerDecision] = []` field per `11-engineer-bridge.md` § `engineer_decisions` field. `dispatch.issued` audit row's `details_json` captures the full payload; sub-agent reads as constraints (no acknowledgment field)
- Daemon populates `dispatches.actual_touched_files_json` from `git diff --name-only <commit>~1 <commit>` against the canonical clone post-`complete_dispatch` and post-`--ff-only` merge. NULL when no commit landed
- Indexes verified via `EXPLAIN QUERY PLAN` on the queue read paths AND the `idx_dispatches_role_completed` partial index that backs Engineer's `get_recent_dispatches` scans
- Unit tests: happy path, validation errors, permission denial, not-found, conflict (double claim, illegal transition), **parallel dispatch (Engineer dispatches two non-conflicting Implementers concurrently, both complete, both branches merge cleanly), parallel dispatch with file-conflict (forced serialization), `engineer_decisions` payload propagation through `dispatch.issued` audit details_json, `actual_touched_files_json` population after clean merge, `get_dispatch_events` and `get_recent_dispatches` hierarchy scoping (Engineer sees own subtree, PM sees full subtree, Implementer/Tester denied)**
- Integration test: full PM → Engineer → Implementer (real dispatch into `meridian-homepoc`) → Engineer → PM cycle, plus a parallel-dispatch scenario in the same `meridian-homepoc` working tree, plus a substrate touch map cache E2E (Engineer dispatches Implementer; daemon populates touch set on completion; subsequent Engineer iteration reads via `get_recent_dispatches` and uses the touch set for parallelization)

### Acceptance

- All unit tests pass
- The integration test produces a real commit in `meridian-homepoc`'s upstream (via the daemon-owned canonical clone's push, against a scratch branch or no-op task that touches only a sandbox file)
- Audit log captures the full chain — Mike can query `get_audit_log` and reconstruct the dispatch; Engineer can query `get_dispatch_events` for the same rows scoped to its own dispatches
- Substrate touch map cache populates correctly: at least one Implementer dispatch lands a commit, `actual_touched_files_json` reflects the diff, and `get_recent_dispatches` returns the parsed list
- Double-claim returns `CONFLICT`, not corruption
- Role-mismatch claim returns `PERMISSION_DENIED`
- Sub-agent dispatched without `engineer_decisions` constraints does not differ behaviorally from sub-agent dispatched with empty `engineer_decisions: []` (the field's absence is equivalent to its empty-list default; no NULL-vs-empty hazards)

### Checkpoint

Mike walks through a real PM→Engineer→Implementer→Engineer→PM cycle in his interactive Claude Code session, observes the work happening in `meridian-homepoc` (via the daemon's substrate-canonical clone fast-forwarded to upstream main, which Mike's working tree pulls from), queries the audit log, sees the chain. **This is Phase 1's load-bearing demo: the company actually staffs itself.**

---

## M5 — Budget & usage tools, window-aware planner, continuous-mode driver

### Deliverables

- `tools/budget.py` — `record_api_call`, `get_token_usage`, `get_burn_rate`, `check_budget_for_task`, **`get_window_state`** (with `pace_recommendation` computed per the table in `02-architecture.md` § Window-aware burn (per bucket); per-bucket queries supported via `bucket="premium"|"standard"` parameter; `fallback_bucket_state` populated when queried bucket recommends `conserve` or `rate_limited`; `current_pm_mode` and `most_recent_pm_mode_log_timestamp` returned for mode-flip detection by callers)
- `tools/pm_mode.py` — `set_pm_mode` MCP tool + daemon-side auto-flip writer (writes `mode='interactive'` to `pm_mode_log` whenever an MCP call from a `(source='oauth', role='pm')` token is observed)
- Migration `0003_pm_mode.sql` — `pm_mode_log` table + `idx_pm_mode_timestamp`
- Migration `0006_buckets.sql` — `ALTER TABLE api_usage ADD COLUMN bucket TEXT NOT NULL DEFAULT 'standard'`; `idx_api_usage_bucket_window`. Backfills existing rows to `'standard'` (correct for pre-2026-04-30 state)
- Model→price table (per `claude-haiku-4-5`, `claude-sonnet-4-6`, `claude-opus-4-7`)
- 5-hour rolling window calculation per agent **per bucket** (PM-on-premium and PM-on-standard tracked separately; Engineer/Implementer/Tester each have their own per-bucket windows)
- By-agent, by-model, and by-bucket aggregations
- Burn rate projection per bucket
- Token-cost estimator (stub: `complexity * constant`; refined as actual data accumulates)
- **Bucket-aware model routing in `check_budget_for_task`** per `06-mcp-tools-phase-one.md` § `check_budget_for_task` — premium when premium has normal/upgrade pace; fallback to standard with tier downgrade when premium is `conserve`; `info`-urgency to Mike on premium `rate_limited`; `urgent` when both depleted
- **Continuous-mode driver** — the asyncio supervisor task that launches and manages the long-running PM Claude Code subprocess per the M3-approved bridge architecture. Reads `pm_mode_log` to detect Mike's interactive presence and pauses dispatch when active. Reads `get_window_state(bucket="premium")` as primary signal.
- **Bucket-attribution investigation and documentation** — investigate the actual signal source for bucket attribution from inside a Claude Code subprocess (response headers, Claude Code env vars, billing API field, or other). Document the chosen mechanism in `docs/kickoff/04-tech-stack.md` or a new `docs/operations/bucket-attribution.md`. The `record_api_call.bucket` field's reliability depends on this investigation; if no signal source exists, bucket attribution remains advisory and `'unknown'` will dominate `get_utilization_report` until a mechanism is found.
- Unit tests with seeded data crossing window boundaries, pace-recommendation table coverage per bucket, `pm_mode_log` auto-flip on `(source='oauth', role='pm')` MCP calls, continuous-mode pause-on-interactive, premium-conserve fallback to standard, both-depleted urgent surface, `record_api_call` accepts and persists `bucket='unknown'` correctly
- **Premium-fallback integration test** — end-to-end scenario forcing premium into `conserve`, verifying new dispatches route to `standard` with tier downgrade, in-flight dispatches finish on their original bucket, and `get_utilization_report` reflects the bucket attribution correctly
- **Bucket-attribution manual test** — operator runs at least one premium dispatch and one standard dispatch on Mike's actual machine; verifies `record_api_call` self-report yields the correct bucket value (not `'unknown'`); confirms `get_utilization_report` reflects the right bucket on each. If the manual test reveals `'unknown'` is the only achievable value (no attribution mechanism available), document the gap and accept advisory-only routing for Phase 1; queue a Phase 2 follow-up to revisit when Anthropic exposes the signal

### Acceptance

- Window boundary tests correct per bucket (events at 18000-second multiples land in the new window for the relevant bucket)
- Multi-agent multi-model multi-bucket aggregation correct
- Burn rate projection is sensible per bucket (rate × remaining seconds in window + observed-so-far)
- `pace_recommendation` matches the table for every quadrant of (percent-used, minutes-to-reset) within each bucket
- Daemon auto-flips `pm_mode_log` to `interactive` when an MCP call from a `(source='oauth', role='pm')` token is observed; flips back to `continuous` 30 minutes after the last such call
- Continuous-mode driver pauses high-token dispatches when `mode='interactive'` is active
- Dashboard burn rate consistent with `get_burn_rate` direct call, broken down by bucket
- Premium-fallback test passes end-to-end (premium conserve → standard fallback → tier downgrade → bucket-attribution audit)
- Bucket-attribution mechanism investigated and documented; manual test on Mike's machine confirms attribution works on at least one premium and one standard dispatch — OR documents the gap and accepts `'unknown'`-dominant attribution as a Phase 2 follow-up
- All tests pass

### Checkpoint

Mike calls `record_api_call`, `get_token_usage`, `get_burn_rate`, `get_window_state` directly from his interactive Claude Code session and inspects the JSON output. Confirms numbers reconcile. Observes `pm_mode_log` auto-flip on his tool calls (gated on `(source='oauth', role='pm')` per the M3 bridge spec). Continuous-mode driver runs in the background; Mike can `tail` the log and see it pausing/resuming as he interacts.

---

## M6 — Knowledge & routing tools, audit log query, and notification service

### Deliverables

- `tools/knowledge.py` — `read_knowledge`, `list_knowledge`, `write_knowledge` with glob permissions and SHA256 audit
- `tools/routing.py` — `get_dashboard_summary` returning Markdown
- `tools/audit.py` — `get_audit_log` (Mike-only)
- `tools/notify.py` — `notify_human`, `get_unread_replies`, `get_message_history` (PM-only)
- `notify/` module — Telegram bot wrapper:
  - Outbound: `httpx`-based send to Telegram Bot API, exponential backoff on failure, audit log on every attempt
  - Inbound: long-polling background asyncio task on `getUpdates` at 30s intervals, persisting to `inbound_messages`, idempotent on `telegram_message_id`
- Migration `0004_notifications.sql` — `inbound_messages` and `outbound_messages` tables with indexes
- `tools/utilization.py` — `get_utilization_report` (per-window breakdown with attributed unused-capacity reasons)
- Dashboard summary includes utilization stats: current window state, last 24h utilization, recent unused-capacity reasons, recent `pm_mode_log` transitions
- `pm_mode_log` queryable via the dashboard (recent N transitions surfaced in the markdown summary)
- Service startup validates `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` env vars; missing/invalid → notification disabled with logged warning, daemon continues
- Graceful degradation: Telegram unreachable → outbound queues with exponential backoff; inbound polling retries silently; `getUpdates` malformed responses logged and skipped
- Knowledge directory seeded with:
  - `/README.md`
  - `/product/substrate/foundation-doc.md` (synced from `meridian-homepoc/docs/design/visual-foundation.md`)
  - `/product/substrate/queue.md` (synced from `meridian-homepoc/phases/checkpoint-queue.md`)
  - `/company/decisions/` (empty, Mike populates)
- Sync script `scripts/sync-substrate-knowledge.py` — pulls foundation doc and queue from substrate path
- Unit tests: glob permissions, path traversal rejection, audit-log filters, dashboard markdown well-formedness, notification PM-only permission enforcement, urgency-tier batching (info batches, urgent/question immediate), telegram_message_id idempotency on inbound polling, blocking_human auto-flip on `question` urgency and auto-clear on reply consumption

### Acceptance

- Permitted reads work
- Denied reads error cleanly without leaking structure
- Path traversal (`../`, absolute paths escaping root) rejected
- Dashboard markdown renders cleanly when piped to a Markdown viewer
- Audit log filters work (by actor, action, time range)
- Sync script populates substrate knowledge correctly
- Notification end-to-end: PM sends `info` via `notify_human`, message arrives at Telegram, Mike replies via Telegram, reply lands in `inbound_messages` with `consumed=0`, PM reads via `get_unread_replies` and consumes
- Permission enforcement: Engineer/Implementer/Tester get `PERMISSION_DENIED` on all three notification tools
- Service starts cleanly even when Telegram env vars are missing (notification disabled, warning logged, all other functionality intact)

### Checkpoint

Two-part checkpoint:

(1) Mike chats with PM in his interactive Claude Code session, asks about the foundation doc, observes PM call `read_knowledge`, surface the contents. Confirms PM's decision-altitude framing uses the foundation context.

(2) Mike completes the manual Telegram setup steps in `docs/kickoff/14-telegram-setup.md`, sends a test `notify_human` from his interactive Claude Code session with PM, receives the message on his phone, replies, observes PM consume the reply on next session start.

**This is the first time the AI-staffed company demonstrably reasons over its own knowledge AND can reach Mike without him initiating.**

M6 absorbs notification work; estimated +2-3 days vs. the original M6 scope. Does not extend overall Phase 1 timeline meaningfully.

---

## M7 — Service deployment, smoke test, hardening

### Deliverables

- `service/meridian-orchestrator.plist` — launchd config (Mac)
- `service/meridian-orchestrator.service` — systemd unit (Linux)
- `service/install.md` — install instructions including Windows Service install via NSSM or `sc.exe` (Mike's primary path)
- Auto-restart on crash (via service manager + a small supervisor in `main.py`)
- Daily SQLite backup task (`asyncio.create_task` running at startup + every 24h)
- Log rotation via `structlog` + size-and-date driven file handler
- `scripts/smoke-test.py` — end-to-end PM→Engineer→Implementer→Tester chain
- README final pass: install, configure, run, troubleshoot
- `.env.example` final pass

### Acceptance

- Service installs and starts on Mike's Windows machine via the install.md procedure
- Service survives a hard kill (kill -9 / Stop-Process -Force) — restarted by the service manager
- Daily backup file appears at `~/.meridian-orchestrator/backups/`
- Old backups (>7 days) are pruned
- Logs rotate to a new file at midnight UTC
- Smoke test passes in <60 seconds end-to-end (extended from 30s to accommodate the continuous-mode + parallel-dispatch scenario)
- **Smoke test includes a continuous-mode scenario:** PM dispatches 3 phases in parallel via Engineer; Engineer parallelizes Implementer/Tester subagents; all complete; merge logic lands all branches; utilization is reported correctly via `get_utilization_report`; `pm_mode_log` shows the expected transitions
- All Phase 1 tests green

### Checkpoint

Mike installs as a Windows Service on his actual laptop, restarts the machine, confirms automatic startup, opens his interactive Claude Code session, runs through the smoke test scenario manually with PM, sees the company-staffed-by-AI experience working as designed. **Phase 1 done.**

---

## Cross-cutting requirements per milestone

- Tests in the same commit as code
- `ruff check` and `mypy --strict` clean before each commit
- Conventional commit messages with milestone tag
- Per-milestone CHANGELOG entry under `## [Unreleased]` until M7 closes, then version bump to `1.0.0`
- Each milestone ends with a checkpoint message to Mike summarizing what was done, what was decided autonomously, what's next

## Timeline impact from utilization addendum

The utilization addendum (parallel concurrent dispatch in M3-M4, window-aware planning + continuous-mode driver in M5, utilization reporting in M6, expanded smoke test in M7) adds approximately **+3-5 days** to the original Phase 1 estimate. The tradeoff: subscription utilization roughly doubles compared to single-threaded sequential dispatch, and PM continues advancing the work queue when Mike isn't at the keyboard.

This is a deliberate scope addition Mike has explicitly authorized. Don't second-guess it.
