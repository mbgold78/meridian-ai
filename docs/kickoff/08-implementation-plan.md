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
- `bridge/` — implementation of the M3-approved bridge architecture per `11-engineer-bridge.md`, **including parallel concurrent dispatch with isolated working directories per dispatch and merge logic for landing parallel branches**. Concrete modules: `bridge/dispatcher.py`, `bridge/canonical.py`, `bridge/worktree.py`, `bridge/timeouts.py`, `bridge/supervisor_base.py` (`LongRunningSupervisor` abstract base — heartbeat watchdog, SIGTERM/grace/SIGKILL chain, respawn, audit emission), and `bridge/engineer_supervisor.py` (extends the base with engineer-specific subprocess/cwd/token + the Engineer compaction-on-context-threshold mechanism). `bridge/pm_supervisor.py` is **deferred to M5** — it depends on `pm_mode_log` (0003), `get_window_state` (M5), and `set_pm_mode` (M5); shipping it at M4 would be unverified surface. M5's `PMSupervisor` extends the same `LongRunningSupervisor` base shipped at M4c. M4 is split into M4a (storage + complete_dispatch + serial dispatch_implementer), M4b (parallel + worktree merge protocol + dispatch_tester), and M4c (timeout + heartbeat + engineer-supervisor + engineer compaction + remaining tools); checkpoint after each sub-milestone.
- `supervisor.halt()` escalation surface: audit-only at M4c (writes `dispatch.heartbeat_timeout_cap` audit row + structlog `ERROR`; supervisor enters terminal halted state with no auto-relaunch; recovery via service restart). `notify_human` amendment ships at M6 alongside `tools/notify.py` and the `outbound_messages` table. See `11-engineer-bridge.md` § Cap escalation surface for full semantics.
- Migration `0005_dispatches.sql` — `dispatches` table per `11-engineer-bridge.md` § Storage shape, including the `engineer_decisions_json`, `actual_touched_files_json`, `merge_conflict_files_json` columns and the `idx_dispatches_role_completed` partial index
- `tools/dispatch.py` — `dispatch_implementer`, `dispatch_tester`, `get_dispatch_status`, `get_dispatch_result`, **plus the bridge-spec-formalized M4 tool deltas: `complete_dispatch` (sub-agent → Engineer structured-output channel), `get_dispatch_events` (Engineer's polling subscription for in-flight audit events; subtree-scoped; schema in `11-engineer-bridge.md` § `get_dispatch_events` MCP tool), and `get_recent_dispatches` (Engineer's substrate touch map cache read path; PM's post-mortem queries; subtree-scoped; schema in `11-engineer-bridge.md` § `get_recent_dispatches` MCP tool)**
- `tools/supervisor.py` — `signal_compaction` MCP tool (engineer-only), shipped at M4c-compaction. Distinct from `tools/dispatch.py` because supervisor-lifecycle is a different operational concern from sub-agent dispatch. M5 lands additional supervisor tools (PMSupervisor mode transitions) in this same module
- Migration `0006_dispatches_last_tool_called.sql` (M4c-supervisor-base; ALTER ADD COLUMN), `0007_dispatches_task_id_nullable.sql` (M4c-supervisor-base; CREATE-INSERT-DROP-RENAME to drop the NOT NULL on `task_id`), `0008_dispatches_status_check.sql` (M4c-compaction; CREATE-INSERT-DROP-RENAME to add CHECK constraint on `status` enforcing the lifecycle enum at the DB layer; precedent-setter for similar constraints on other status-like columns)
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

### M4 status (REOPENED 2026-05-03)

**M4 was marked closed on 2026-05-01 but acceptance was not met.** During
M5-continuous-pm-driver prep checkpoint (F2.1 commit-1 contradiction
surface, 2026-05-03), grounding scan revealed `tools/tasks.py` and the six
task-management MCP tools (`submit_task`, `get_queue`, `update_task_status`,
`set_task_priority`, `claim_task`, `get_subtasks`) were never implemented.

The M4 acceptance criteria at lines 122-128 require, among others, the
PM→Engineer→Implementer→Engineer→PM cycle (the "load-bearing demo"
checkpoint at line 132) and the double-claim CONFLICT / role-mismatch
PERMISSION_DENIED behaviors. Without `submit_task`, the cycle's first hop
has no production code path. Without `claim_task`, the conflict-and-role
behaviors aren't verifiable.

The integration test path that does work uses direct SQL fixture inserts
(e.g., `tests/integration/test_dispatch_parallel_and_merge.py:92`:
`INSERT INTO tasks (id, company_id, project_id, agent_role, description) ...`).
Production-side MCP path is structurally non-functional today.

**Disposition:** M4 reopens. This is a spec-vs-implementation drift, not
an authorized deferral. Permitting "M4 is closed despite unmet acceptance"
retroactively redefines every closure under the closeout convention to
mean "we stopped working on it" rather than "we verified it works." That
redefinition is rejected.

**Remediation routing:** M4d-task-management-surface, scheduled after
M5-continuous-pm-driver and before Phase 1 closeout. See M4d entry below.

**Discovery context:** the **phase-boundary acceptance-criteria-coverage
scan** convention (newly established 2026-05-03; see
`docs/kickoff/00-README.md` § Phase-boundary acceptance-criteria-coverage
scan) was triggered during M5-continuous-pm-driver grounding to verify
no further phantom-surface drift exists. Output:
`docs/working/phase-1-acceptance-drift.md`. Result: drift localized to
M4 task-management surface; no other M-level drift discovered.

### M4 trajectory (closed 2026-05-01; reopened 2026-05-03)

M4 shipped in eight commits across four sub-milestones plus one mid-milestone fix:

- **M4a** (storage + dispatch lifecycle, `15ec0eb`): `0002_task_planning.sql`, `0005_dispatches.sql`, `BridgeDispatcher` serial path, four dispatch MCP tool handlers (`dispatch_implementer`, `complete_dispatch`, `get_dispatch_status`, `get_dispatch_result`), audit chain.
- **M4b** (parallel + merge protocol, `cb9277c`): `--ff-only` post-dispatch merge, `CanonicalDivergedError`, `dispatch_tester`, asyncio.Lock canonical operations, Windows `.git/config.lock` mitigation.
- **M4b.1** (rebase-before-ff-only fix, `f3e0f52`): resolved spec line 147 vs sequence-diagram line 522-524 contradiction; parallel disjoint dispatches both merge cleanly via rebase before ff-only.
- **`test(M4b)` race window backfill** (`e431005`): forced SELECT-before-returncode race + regression-verification dance closing the M4b.1 defensive re-read coverage gap.
- **M4c-supervisor-base** (`af0695c` + `0b50e66` test): `LongRunningSupervisor` ABC, watchdog (60s check / 30min timeout / three-respawns-per-hour cap with `halt`), `bridge/activity.py` writer helper, MCP middleware integration via `_record_caller_activity`, end-to-end activity-recording test.
- **M4c-engineer-supervisor** (`c6da070`): `EngineerSupervisor` extending the base, `Settings.engineer_supervisor_dir` + `enable_engineer_supervisor` flag (default off), main.py wiring with the lazy-supervisor-lookup closure pattern. Surfaced two real datetime bugs in production-shape integration tests (naive-vs-aware, second-precision truncation).
- **M4c-compaction** (`ab99f88`): `signal_compaction` MCP tool in new `tools/supervisor.py`, subprocess-exit detection branch in the watchdog, `_compaction_pending` in-process flag, `dispatches.status` CHECK constraint via migration `0008`, six new audit actions.
- **M4c-tooling** (`8667098`): `bridge/timeouts.py` (model→tps + `calculate_timeout`), `get_recent_dispatches` and `get_dispatch_events` query tools with shared `_scoping_where_clause` helper, BridgeDispatcher's `model` parameter for tps-driven timeout.

Six migrations shipped: `0001_initial`, `0002_task_planning`, `0005_dispatches`, `0006_dispatches_last_tool_called`, `0007_dispatches_task_id_nullable`, `0008_dispatches_status_check`. Reservation gap (`0003_pm_mode` at M5, `0004_notifications` at M6) preserved.

**Final test count:** 155 passed (M2 had ~30; M3 added ~30; M4 added ~95). ruff check clean. mypy --strict src/ clean (31 source files). Suite runtime ~28s.

### M4-accumulated BACKLOG items (deferred to post-M4 milestones)

Surfaced during M4c prep checkpoints; tracked in `BACKLOG.md` and dedicated project memories:

- **Manual `supervisor.halt()` clear without service restart** (`BACKLOG.md`) — admin command to clear the halt flag in-place. Phase 1 acceptable as service restart; future operability improvement.
- **Supervisor respawn paths to single-SQL-transaction atomicity** (`BACKLOG.md`) — current respawn uses in-process `_current_dispatch_id is None` to gate the watchdog; retrofit to single-transaction when external readers of supervisor state are introduced (M5 PMSupervisor lifecycle visualization or M6 `notify_human` surfacing).
- **`MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR` flag flip** (M5; project memory: `m5_engineer_supervisor_flag_flip`) — verify `subscription_5h` attribution accuracy, compaction-at-80% firing, heartbeat calibration before flipping.
- **Production dispatch wrapper closure** (M5; project memory: `m5_dispatch_wrapper_gap_closure`) — five MCP wrappers + BridgeDispatcher construction in main.py + end-to-end MCP integration tests. **Shipped at M5 kickoff (commits `9bf6d8a`–`a184faf`).**
- **Engineer compaction production trigger wiring** (M5) — amend `engineer.yaml` system_prompt to call `signal_compaction` when `get_window_state(bucket='subscription_5h') >= 80%`.
- **`bridge/timeouts.py` calibration** (M4-acceptance follow-up) — Mike's manual integration test measures observed tps for at least one real Sonnet and one real Opus dispatch; updates the table before M5 closes. Per spec § Hard timeout calculation calibration note.
- **`signal_compaction` tool wrapper for sub-agent dispatch tools** (M5; part of the dispatch wrapper closure) — currently signal_compaction has its MCP wrapper, but the M4 dispatch tools (dispatch_implementer/dispatch_tester/complete_dispatch/get_dispatch_status/get_dispatch_result) do not. Closes alongside the wrapper closure work.

### Pattern observation: M4 built; M5 wires

M4 shipped complete machinery. M5's task is to wire the machinery into production-exercisable paths. Every M5 deliverable should be evaluable against this lens — what M4 piece does this enable to actually run? See M5 § Deliverables for the explicit forward-references (production dispatch wrapper closure, engineer-supervisor flag flip, engineer compaction production trigger wiring).

**Caveat (2026-05-03):** the "M4 built complete machinery" claim is
*partly* true — the dispatch surface is complete; the task-management
surface (six MCP tools) was not built. The pattern observation holds for
the dispatch surface only. M4d (below) closes the task-management gap
before Phase 1 closeout.

---

## M4d — Task management surface (REOPENING REMEDIATION)

**Status:** scheduled. Lands after M5-continuous-pm-driver and before
Phase 1 closeout.

**Trigger:** M4 reopened 2026-05-03 (see M4 status section above).
Acceptance criteria at lines 122-128 not met because `tools/tasks.py` and
the six task-management MCP tools were never implemented.

### Deliverables

- `tools/tasks.py` implementing six MCP tools per
  `06-mcp-tools-phase-one.md:30-122`:
  - `submit_task` — caller-role-managed-target hierarchy check; optional
    `parent_task_id` with claimed-by-or-submitted-by enforcement; audit
    `task.submit`. **Optional `task_type` field** lands here per the
    M5-continuous-pm-driver F1.1 disposition (deferred from
    M5-continuous-pm-driver to M4d so it ships with the seed-task
    population path).
  - `get_queue` — hierarchy-scoped read; not audited (high frequency).
  - `update_task_status` — claimed-by-or-submitted-by enforcement;
    state-machine-validated transitions; audit `task.status_change`.
  - `set_task_priority` — submitter-or-claimed-by-or-ancestor; audit
    `task.priority_change`.
  - `claim_task` — conditional UPDATE (`WHERE status = 'pending'`) for
    idempotency; audit `task.claim`.
  - `get_subtasks` — hierarchy-scoped read.
- Migration `0010_tasks_task_type.sql` — `ALTER TABLE tasks ADD COLUMN
  task_type TEXT;` Schema-version 10. Pairs with `submit_task`'s
  task_type schema field.
- Server.py wrappers for the six tools, mirroring the established pattern
  (`_resolve_agent_id` → `_observe_caller` → handler call → typed
  output). Pairs with the M5-wrapper-closure pattern from M5 kickoff.
- Hierarchy enforcement: `submit_task` calls into
  `agents/permissions.py:is_descendant` (caller's-direct-report check);
  `update_task_status` and `set_task_priority` call descendant + claimed-by
  helpers.
- Integration tests: PM → Engineer → Implementer chain via real MCP path
  (no direct SQL fixture seed). Covers double-claim CONFLICT, role-mismatch
  PERMISSION_DENIED, decline propagation upward through
  `update_task_status('declined', notes=...)`.
- Acceptance demo: Mike walks the PM→Engineer→Implementer→Engineer→PM
  cycle in his interactive Claude Code session (the load-bearing demo
  from M4 acceptance line 132).

### Acceptance

- M4 acceptance criteria at lines 122-128 met end-to-end via production
  MCP path, not test fixtures.
- Audit log captures full chain via `get_audit_log` (M6 deliverable; if
  M6 hasn't shipped before M4d, audit reconstruction via direct SQL is
  acceptable for M4d closure pending M6).
- Mike's load-bearing demo runs without manual SQL.

### Remediation discipline

M4d's closeout doc (`docs/closeouts/M4d-task-management-surface.md`)
follows the M5-subscription-routing closeout convention. Drift-detection
scan at chain close. Phase-boundary acceptance-criteria-coverage scan at
Phase 1 closeout.

### Pre-M4d discovery

The M4d remediation was discovered during M5-continuous-pm-driver
grounding's F2.1 commit-1 contradiction surface (2026-05-03). See
`docs/working/phase-1-acceptance-drift.md` for the scan that confirmed
drift was localized to M4 task-management.

---

## M5 — Budget & usage tools, window-aware planner, continuous-mode driver

### Deliverables

- `tools/budget.py` — `record_api_call`, `get_token_usage`, `get_burn_rate`, `check_budget_for_task`, **`get_window_state`** (with `pace_recommendation` computed per the table in `02-architecture.md` § Window-aware burn (per bucket); per-bucket queries supported via `bucket="subscription_5h"|"subscription_7d"|"subscription_7d_opus"|"subscription_7d_sonnet"|"api_key_overflow"` parameter; `snapshot_age_seconds` and `null_counters` exposed for cache-staleness and per-counter null-fallback visibility; `current_pm_mode` and `most_recent_pm_mode_log_timestamp` returned for mode-flip detection by callers)
- `tools/pm_mode.py` — `set_pm_mode` MCP tool + daemon-side auto-flip writer (writes `mode='interactive'` to `pm_mode_log` whenever an MCP call from a `(source='oauth', role='pm')` token is observed)
- Migration `0003_pm_mode.sql` — `pm_mode_log` table + `idx_pm_mode_timestamp`
- Migration `0009_observability_cost_tier1.sql` — tier-1 cost-interrogation cohort columns on `api_usage` (`notes`, `workflow_id`, `agent_role`, `task_type`) plus indexes `idx_api_usage_workflow` and `idx_api_usage_role_type`. `agent_role` auto-populated server-side from caller's resolved role; the others are optional caller-supplied tags. Per `BACKLOG.md` § Observability roadmap. Ships at M5-subscription-routing.
- **OAuth usage poller** — background asyncio task polling `https://api.anthropic.com/api/oauth/usage` (`anthropic-beta: oauth-2025-04-20`) at 30s default cadence; exponential backoff on 429 (60→120→240→480→600s ceiling per Issue #31021); `oauth_usage.polling_ceiling_reached` audit row at ceiling. Cached snapshot drives `get_window_state`, `check_budget_for_task`, and the per-bucket aggregation in `get_token_usage` and `get_utilization_report`. See `project_subscription_first_architecture` memory.
- **Subprocess env sanitization helper** — new `bridge/launch_env.py:build_subprocess_env(token, dispatch_id, *, allow_api_key=False)`. Both `bridge/dispatcher.py:_default_launcher` and `bridge/supervisor_base.py`'s subprocess launcher call this helper; `allow_api_key=False` strips `ANTHROPIC_API_KEY` and any other Anthropic credential env vars per [anthropics/claude-code#12352](https://github.com/anthropics/claude-code/issues/12352). `allow_api_key=True` reserved for explicit overflow flow at M5-flag-flips when `extra_usage.is_enabled: true`.
- Model→price table (per `claude-haiku-4-5`, `claude-sonnet-4-6`, `claude-opus-4-7`)
- 5-hour and 7-day rolling window aggregation per agent, per model, derived from `api_usage` rows joined against the daemon's polled snapshot at query time
- By-agent, by-model, by-counter aggregations (the bucket dimension is query-time-derived from `model` plus the polled snapshot, not stored on `api_usage` rows — see `project_subscription_first_architecture` memory § B-3)
- Burn rate projection per counter
- Token-cost estimator (stub: `complexity * constant`; refined as actual data accumulates)
- **Subscription-first model routing in `check_budget_for_task`** per `06-mcp-tools-phase-one.md` § `check_budget_for_task` — subscription with normal/upgrade pace as default; downgrade Opus → Sonnet when `subscription_7d_opus` is high; `info`-urgency to Mike on `subscription_5h` rate-limit; `urgent` when subscription saturated and `extra_usage.is_enabled: false`
- **Continuous-mode driver** — the asyncio supervisor task that launches and manages the long-running PM Claude Code subprocess per the M3-approved bridge architecture. Reads `pm_mode_log` to detect Mike's interactive presence and pauses dispatch when active. Reads `get_window_state(bucket="subscription_5h")` as primary pacing signal.
- **Production dispatch wrapper closure** — M4 shipped `BridgeDispatcher` and the pure dispatch handlers (`dispatch_implementer`, `dispatch_tester`, `complete_dispatch`, `get_dispatch_status`, `get_dispatch_result`) but did NOT ship the corresponding `@mcp.tool` wrappers in `server.py` or the BridgeDispatcher construction in `main.py`'s lifespan. M4 tests use direct handler invocation. M5 closes this gap: five wrappers in `server.py` (each following the established pattern — `_resolve_agent_id` → `_record_caller_activity` → handler call), BridgeDispatcher construction in `main.py` (canonical clone provisioning, worktree dir, lazy-supervisor-lookup-style `get_dispatcher` callable, shutdown coordination), end-to-end MCP integration tests through the real Streamable-HTTP stack. Pairs naturally with `MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR=true` flip — both are "wire up the production path that M4 built the machinery for." See M5 prep memory `project_m5_dispatch_wrapper_gap_closure` for the full closure plan. **Shipped at the start of M5** (commits `9bf6d8a`–`a184faf`).
- **Engineer-supervisor flag flip** — `MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR` defaults to `false` at M4c. M5 flips it to `true` after verifying `subscription_5h` attribution accuracy, compaction-at-80% firing, and heartbeat calibration against real Engineer iteration cadence. See M5 prep memory `project_m5_engineer_supervisor_flag_flip` for the verification checklist. Engineer's prompt is updated to call `signal_compaction` when `get_window_state(bucket='subscription_5h') >= 80%`.
- **Engineer compaction production trigger wiring** — at M4c-compaction, `signal_compaction` MCP tool exists and the daemon-side respawn machinery is live, but no production code triggers it. M5 amends `config/agents/engineer.yaml`'s `system_prompt` to call `signal_compaction` when `subscription_5h` utilization crosses 80%. The trigger threshold is configurable; the test path that fires `signal_compaction` directly remains for integration testing.
- **Bucket-attribution investigation — closed at M5 kickoff (2026-05-01).** Experiment 4 (operator-run `curl /api/oauth/usage`) confirmed the H4 path: `/api/oauth/usage` exposes per-counter normalized utilization. The premium/standard framing was a pre-research misunderstanding; the actual axis is per-model-within-subscription. See `project_subscription_first_architecture` memory for the full outcome, the canonical bucket names, and the routing logic that downstream sub-milestones encode.
- Unit tests with seeded data crossing window boundaries, pace-recommendation table coverage per bucket, `pm_mode_log` auto-flip on `(source='oauth', role='pm')` MCP calls, continuous-mode pause-on-interactive, `subscription_7d_opus`-high → Opus-downgrade routing, subscription-saturated-with-overflow-disabled `urgent` surface, OAuth-usage-polling 429 backoff schedule, `null_counters` fallback path
- **Subscription-saturated integration test** — end-to-end scenario forcing `subscription_5h` to high utilization, verifying new dispatches degrade (Opus → Sonnet downgrade when `subscription_7d_opus` high; queue + `urgent` surface when subscription saturated and overflow disabled), in-flight dispatches finish under their original budget, and `get_utilization_report` reflects per-counter aggregation correctly
- **OAuth-usage-polling integration test** — fake the `/api/oauth/usage` endpoint; verify 30s default cadence, 60→120→240→480→600s backoff on 429, `oauth_usage.polling_ceiling_reached` audit at ceiling, `snapshot_age_seconds` advancement, `null_counters` propagation through `get_window_state`

### Acceptance

- Window boundary tests correct per counter (5-hour and 7-day cycle resets land in the new window for the relevant counter per `resets_at` from the polled snapshot)
- Multi-agent multi-model aggregation correct, with per-counter dimension derived at query time from `model` plus the polled snapshot
- Burn rate projection is sensible per counter (rate × remaining seconds in window + observed-so-far)
- `pace_recommendation` matches the table for every quadrant of (utilization, minutes-to-reset) within each counter
- Daemon auto-flips `pm_mode_log` to `interactive` when an MCP call from a `(source='oauth', role='pm')` token is observed; flips back to `continuous` 30 minutes after the last such call
- Continuous-mode driver pauses high-token dispatches when `mode='interactive'` is active
- Dashboard burn rate consistent with `get_burn_rate` direct call, broken down by counter
- Subscription-saturated test passes end-to-end (subscription_5h conserve → tier downgrade → urgent surface when overflow disabled)
- OAuth-usage-polling backoff schedule verified end-to-end against a faked endpoint
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
