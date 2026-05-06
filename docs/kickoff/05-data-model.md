# Data Model

## Conventions across all tables

- IDs are UUIDv4 strings (`TEXT`).
- `created_at` on every table, ISO 8601 UTC, default `(datetime('now'))`.
- Mutability: rows in `audit_log` and `api_usage` are append-only. Other tables are mutable but transitions are validated in code.
- All FK references are `ON DELETE RESTRICT` unless noted; v1 has no delete operations on parent tables.

## Schema

```sql
CREATE TABLE companies (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    config_json TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE TABLE projects (
    id TEXT PRIMARY KEY,
    company_id TEXT NOT NULL REFERENCES companies(id),
    name TEXT NOT NULL,
    working_directory TEXT,
    status TEXT NOT NULL DEFAULT 'active',
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(company_id, name)
);

CREATE TABLE agents (
    id TEXT PRIMARY KEY,
    company_id TEXT NOT NULL REFERENCES companies(id),
    role TEXT NOT NULL,
    config_path TEXT NOT NULL,
    reports_to TEXT REFERENCES agents(id),
    status TEXT NOT NULL DEFAULT 'active',
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_agents_company_role ON agents(company_id, role);

CREATE TABLE tasks (
    id TEXT PRIMARY KEY,
    company_id TEXT NOT NULL REFERENCES companies(id),
    project_id TEXT NOT NULL REFERENCES projects(id),
    parent_task_id TEXT REFERENCES tasks(id),
    agent_role TEXT NOT NULL,
    description TEXT NOT NULL,
    priority INTEGER NOT NULL DEFAULT 2,
    complexity INTEGER NOT NULL DEFAULT 3,
    status TEXT NOT NULL DEFAULT 'pending',
    blocking_human INTEGER NOT NULL DEFAULT 0,
    estimated_tokens INTEGER,                  -- PM-set, used for window-aware planning (added 0002 at M4)
    actual_tokens INTEGER,                     -- Engineer-set on completion (added 0002 at M4)
    submitted_by TEXT,
    claimed_by TEXT,
    claimed_at TEXT,
    completed_at TEXT,
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_tasks_status_priority ON tasks(status, priority);
CREATE INDEX idx_tasks_agent_role ON tasks(agent_role);
CREATE INDEX idx_tasks_parent ON tasks(parent_task_id);

CREATE TABLE api_usage (
    id TEXT PRIMARY KEY,
    timestamp TEXT NOT NULL DEFAULT (datetime('now')),
    agent_id TEXT REFERENCES agents(id),
    model TEXT NOT NULL,
    input_tokens INTEGER NOT NULL,
    output_tokens INTEGER NOT NULL,
    cost_usd REAL,
    task_id TEXT REFERENCES tasks(id),
    window_start TEXT NOT NULL
);

CREATE INDEX idx_api_usage_window ON api_usage(window_start);
CREATE INDEX idx_api_usage_agent ON api_usage(agent_id);
-- Per-bucket attribution is query-time derivation from `model` plus the
-- daemon's polled `/api/oauth/usage` snapshot per
-- `project_subscription_first_architecture` memory. No bucket column.
--
-- Tier-1 cost-interrogation observability columns (`notes`, `workflow_id`,
-- `agent_role`, `task_type`) ship at M5-subscription-routing in
-- `0009_observability_cost_tier1.sql` per `BACKLOG.md` § Observability
-- roadmap. `agent_role` is auto-populated server-side from the caller's
-- resolved role; the others are optional caller-supplied cohort tags.
-- Indexes `idx_api_usage_workflow` and `idx_api_usage_role_type` ride
-- along.

CREATE TABLE audit_log (
    id TEXT PRIMARY KEY,
    timestamp TEXT NOT NULL DEFAULT (datetime('now')),
    actor TEXT NOT NULL,
    action TEXT NOT NULL,
    target TEXT,
    details_json TEXT,
    success INTEGER NOT NULL DEFAULT 1,
    error_message TEXT
);

CREATE INDEX idx_audit_timestamp ON audit_log(timestamp);
CREATE INDEX idx_audit_actor ON audit_log(actor);

CREATE TABLE inbound_messages (
    id TEXT PRIMARY KEY,
    timestamp TEXT NOT NULL DEFAULT (datetime('now')),
    telegram_message_id INTEGER UNIQUE NOT NULL,
    content TEXT NOT NULL,
    in_reply_to TEXT,
    consumed INTEGER NOT NULL DEFAULT 0,
    consumed_at TEXT,
    consumed_by_agent TEXT
);

CREATE INDEX idx_inbound_unconsumed ON inbound_messages(consumed) WHERE consumed = 0;

CREATE TABLE outbound_messages (
    id TEXT PRIMARY KEY,
    timestamp TEXT NOT NULL DEFAULT (datetime('now')),
    urgency TEXT NOT NULL,
    subject TEXT NOT NULL,
    body TEXT NOT NULL,
    related_task_id TEXT REFERENCES tasks(id),
    delivered INTEGER NOT NULL DEFAULT 0,
    delivered_at TEXT,
    telegram_message_id INTEGER
);

CREATE INDEX idx_outbound_undelivered ON outbound_messages(delivered) WHERE delivered = 0;

-- Added in 0003_pm_mode.sql at M5
CREATE TABLE pm_mode_log (
    id TEXT PRIMARY KEY,
    timestamp TEXT NOT NULL DEFAULT (datetime('now')),
    mode TEXT NOT NULL,                        -- 'interactive' | 'continuous' | 'paused_blocked' | 'paused_question'
    reason TEXT,
    triggered_by TEXT                          -- 'mike_message' | 'window_reset' | 'queue_empty' | 'rate_limit' | 'manual'
);

CREATE INDEX idx_pm_mode_timestamp ON pm_mode_log(timestamp);

CREATE TABLE _schema_version (
    version INTEGER PRIMARY KEY,
    applied_at TEXT NOT NULL DEFAULT (datetime('now'))
);
```

### Note on `_schema_version`

The kickoff DDL had `_schema_version` without a primary key. `PRIMARY KEY` on `version` is now in place — each migration is recorded as one row, and re-running migration N is an idempotent insert (`INSERT OR IGNORE`). Mike approved this as-authored; the alternative (every migration *attempt* tracked) was considered and rejected as forensics overhead unjustified for a greenfield Phase 1 with simple migrations.

### Note on `agents` — `UNIQUE(company_id, role)` dropped

The original kickoff DDL constrained one agent per role per company. That constraint was dropped at Mike's direction before M1 starts. Reasoning:

- Phase 2 will likely run multiple specialists of the same role (e.g., a second Backend Engineer when one is overloaded). Migrating later is mechanical but unnecessary if avoidable now.
- Agent registration is a deliberate operation driven by config files. The constraint protects against a failure mode (accidentally registering two agents with the same role) that doesn't exist in practice.

Replacement: a non-unique index on `(company_id, role)` for query performance. Phase 2 reasoning is in `12-phase-two-engineering-team.md`.

## Field semantics

### `tasks.status`

State machine:

```
pending  ─→  claimed  ─→  in_progress  ─→  completed
   │           │              │              │
   │           │              ↓              │
   │           │           failed            │
   │           │                             │
   │           └──→  declined ──────────────→│
   │                                         │
   └──→  cancelled ──────────────────────────┘
```

`declined` is a sub-agent's first-class upward action. It is **not** `failed`. The audit log distinguishes them.

`cancelled` is set by the submitter (or Mike) to withdraw a task before claim/completion.

### `tasks.priority`

`1` highest, `5` lowest. Default `2`. Used as the secondary index on the queue (status, priority).

### `tasks.complexity`

`1` lowest, `5` highest. Used by `check_budget_for_task` to recommend a model tier and by the window-aware planner alongside `estimated_tokens`.

### `tasks.estimated_tokens`

PM's pre-dispatch estimate of token cost for the task. Used by the window-aware planner to decide what fits in remaining window capacity. Stub estimator at M5 with simple heuristics (complexity rating × constant); refined as actual data accumulates. NULL on tasks submitted before M4 or by callers that didn't estimate.

### `tasks.actual_tokens`

Engineer-set on task completion: the actual token cost of executing the task (sum across Implementer/Tester dispatches under it). Used to refine the estimator over time. NULL until the task reaches a terminal state.

### `tasks.blocking_human`

`1` if Mike's input is blocking the task. PM uses this to flag escalations in the dashboard summary. Set to `1` automatically when PM sends a `notify_human(urgency='question', related_task_id=...)`; cleared when Mike's reply lands and PM consumes it via `get_unread_replies`.

**Single-writer invariant (Phase 1).** PM is the only agent permitted to write to `blocking_human`, and writes happen only through the implementation paths of these two tools — `notify_human` (sets to 1 when urgency is `question`) and `get_unread_replies` (clears to 0 on consumption of an inbound message whose `in_reply_to` references a `question`-urgency outbound). No other tool, no admin path, no retry logic, no migration touches this field. The storage layer enforces this by exposing no public mutator for `blocking_human`; the only writes are inside the two notification-tool implementations, both inside a single SQLite transaction with the corresponding `outbound_messages` / `inbound_messages` write. This eliminates the race window that would exist if multiple writers could flip the flag concurrently.

If Phase 2 needs additional writers (e.g., an admin override tool), that's a deliberate change requiring revisiting this invariant — not a quiet addition.

### `tasks.submitted_by` and `tasks.claimed_by`

Free text. By convention:
- For agents: the agent's `id` (UUID from `agents` table).
- For Mike: the literal string `human:mike` (so it's distinguishable from any UUID).
- Migrations or system actions: `system:<action>` (e.g., `system:hot_reload`).

This is intentionally not a FK so non-agent actors are first-class.

### `agents.reports_to`

`NULL` means the agent reports to the human (Mike). For a child agent, this is the parent agent's `id`. Used by:
- Permission framework to enforce hierarchy
- `get_org_chart` tool
- Audit log when reconstructing dispatch chains

### `api_usage.window_start`

The 5-hour window the call belongs to, computed at insert time and indexed. Allows fast `SUM(input_tokens), SUM(output_tokens)` over the current and previous windows without recomputing window boundaries on every read.

Window boundary algorithm: `floor(timestamp_epoch / 18000) * 18000` (5 hours = 18000 seconds). UTC.

### `inbound_messages`

Stores Telegram messages from Mike to PM. Polled in 30-second intervals via Telegram's `getUpdates` long-polling. `telegram_message_id` is the Telegram-side message id (UNIQUE so re-polling the same update is idempotent — duplicate inserts are a no-op via `INSERT OR IGNORE`). `consumed` flips when PM reads the message via `get_unread_replies`. `consumed_by_agent` records which agent consumed it (effectively always PM in Phase 1, but stored for audit). `in_reply_to` references an `outbound_messages.id` when Mike's reply is recognizably a response to a specific outbound; otherwise NULL.

### `outbound_messages`

Stores PM-to-Mike notifications dispatched via the Telegram bot. `urgency` is one of `urgent`, `question`, `info`. `delivered` flips when the Telegram API confirms send; `telegram_message_id` is populated from the Telegram response. `related_task_id` ties the notification to a specific task when applicable (and triggers `tasks.blocking_human=1` when urgency is `question`).

### `pm_mode_log`

Observability table — not load-bearing state. Records PM mode transitions for Mike to inspect via the dashboard. Two paths write to it:

1. PM itself, via `set_pm_mode` — deliberate transitions (entering `paused_question` when surfacing a blocker, returning to `continuous` after Mike's reply lands).
2. The daemon directly, on Mike's MCP token use — auto-flip to `interactive` with `triggered_by='mike_message'`. Continuous-mode PM polls this table on each planning iteration to detect Mike's presence.

`reason` is free text (PM populates with context like "blocked on cascade-authoring direction"). `triggered_by` is the cause (`mike_message`, `window_reset`, `queue_empty`, `rate_limit`, `manual`).

PM does not read this table to determine its own current mode — that is held in memory by the running PM process. **However**, PM does read this table to detect *Mike's* mode-flip activity (per item 2 above): continuous-mode PM polls `pm_mode_log` on each planning iteration, reads the **most recent row's timestamp and mode**, and adjusts its planning loop based on whether `mode='interactive'` is fresh enough to indicate Mike's presence. The two reads are distinct — PM's own current mode is in-memory state (never read from this table); Mike-presence detection is the most recent row's timestamp and mode (read from this table). The log exists for auditing, the utilization report, the dashboard, and Mike-presence detection.

### `pm_current_focus`

Narration tier-1 table per `BACKLOG.md` § Observability roadmap (M5-continuous-pm-driver scope). Each row captures one work-unit boundary PM declares for itself: an objective, the kind of output it produces (`output_type`), and the acceptance criteria the actual output is validated against at completion. Loop-side validation (F2.2 A1 disposition) computes the contract-validated finding; the writer records the result.

```sql
CREATE TABLE pm_current_focus (
    id TEXT PRIMARY KEY,
    objective TEXT NOT NULL,
    output_type TEXT NOT NULL CHECK (output_type IN (
        'document', 'code_change', 'decision', 'review', 'investigation'
    )),
    acceptance_criteria TEXT NOT NULL,
    bound_workflow_id TEXT REFERENCES tasks(id) ON DELETE NO ACTION,
    declared_at TEXT NOT NULL DEFAULT (datetime('now')),
    completed_at TEXT,
    contract_validated_bool INTEGER,
    contract_finding_text TEXT
);

CREATE INDEX idx_pm_focus_declared_at ON pm_current_focus(declared_at);
```

Three write paths (all PM-initiated; see `06-mcp-tools-phase-one.md`):

1. `declare_current_focus` — opens a new focus and (per F3.6 Q8 Reading A) auto-supersedes any prior incomplete focus in the same transaction. The prior row's `completed_at` is set, `contract_finding_text='superseded'`, `contract_validated_bool` left NULL. The at-most-one-active-focus invariant is preserved across Mike-interrupt-driven pivots.
2. `complete_current_focus` — closes the active focus with the loop's validation result. Sets `completed_at`, `contract_validated_bool` (1 for True / 0 for False), and `contract_finding_text` from the request.
3. Auto-supersede via path (1) above — implicit closure of a prior focus when a new one is declared.

**Constraint shape** (per F3.6 dispositions, 2026-05-04):

- `output_type` CHECK enforces the closed 5-value enum (Q1 Reading A). Defense-in-depth alongside the `tools/pm_focus.py:OutputType` Literal.
- `bound_workflow_id` FK with NO ACTION on delete (Q3 Reading A): write-time integrity catches PM-references-nonexistent-task; deletion blocks if focus rows reference. SET NULL would silently destroy narration record fidelity. Different shape from `api_usage.workflow_id` (which is dual-path and ships without FK at 0009).
- `contract_validated_bool` and `contract_finding_text` nullable: only set on complete; declare-time rows leave both NULL. SQLite stores bool as INTEGER (0/1).
- `pm_current_focus` does NOT carry a `pm_mode_log_id` FK (Q2 Reading B): foci change at PM's deliberate declaration; modes flip asynchronously on Mike-interrupts. Co-incident-not-owned relationships use timestamp-join, not FK coupling. The operator-side "what mode was PM in when this focus landed" question reconstructs via `pm_mode_log` timestamp comparison.

**Single index on `declared_at`** (Q5 Reading B): the loop's at-most-once-per-tick `read_current_focus` lookup uses sequential scan well within bounds. `idx_pm_focus_bound_workflow` deferred per `BACKLOG.md` § Deferred (entry dated 2026-05-04); revisit when workflow-cohort queries against `pm_current_focus` materialize. The `api_usage.workflow_id` index from migration 0009 is the load-bearing surface for cost-cohort queries; pm_current_focus row counts stay 100x+ smaller.

### `pm_spin_rationale_log`

Fitness tier-1 table per `BACKLOG.md` § Observability roadmap (M5-continuous-pm-driver scope). Each row captures one loop-iteration keep-alive-vs-shutdown decision: whether PM kept itself alive (`kept_alive`) or surfaced a shutdown recommendation (`shutdown_recommended`), classified by a closed-enum `rationale_tag` (`queued_work` / `mike_presence` / `heartbeat_cheap` / `forgot_to_shutdown`). Optional `rationale_free_text` carries supplementary diagnostic context.

```sql
CREATE TABLE pm_spin_rationale_log (
    id TEXT PRIMARY KEY,
    iteration_timestamp TEXT NOT NULL DEFAULT (datetime('now')),
    decision TEXT NOT NULL CHECK (decision IN (
        'kept_alive', 'shutdown_recommended'
    )),
    rationale_tag TEXT NOT NULL CHECK (rationale_tag IN (
        'queued_work', 'mike_presence', 'heartbeat_cheap', 'forgot_to_shutdown'
    )),
    rationale_free_text TEXT
);

CREATE INDEX idx_pm_spin_iteration_timestamp ON pm_spin_rationale_log(iteration_timestamp);
```

One write path: PM-initiated `record_spin_rationale` MCP tool (PM-only). Per-tick emission, no dedup (F4.2 Q1 Reading A) — every loop iteration produces a row regardless of whether the (decision, rationale_tag) tuple matches the prior iteration. Faithful timeline preserves operator-relevant oscillation signals; coalescing would hide them. The loop driver at commits 7-8 will be the production caller; commit 3 ships persistence + writer + MCP wrapper so the consumer can integrate against the real surface without co-shipping coupling.

**Constraint shape** (per F4.2 dispositions, 2026-05-04):

- `decision` CHECK enforces the 2-value enum (Q2 Reading A). Defense-in-depth alongside the `tools/pm_spin.py:Decision` Literal.
- `rationale_tag` CHECK enforces the 4-value enum (F3.6 Q3 Reading A from Followup-2). Defense-in-depth alongside `RationaleTag` Literal.
- `rationale_free_text` nullable: `rationale_tag` is the primary cohort signal; free_text is supplementary detail PM may attach for diagnostic context. Most rows leave it NULL.

**Single index on `iteration_timestamp`** (mirrors commit 2's Q5 Reading B disposition): the loop's append-only writes don't need indexed lookup at write time; time-windowed roll-up queries (the primary read path for tier-2 fitness surfaces) benefit from the timestamp index. `rationale_tag`-cohort and `decision`-cohort indexes deferred — operator-side ad-hoc SQL is sequential-scan cheap on a <1M-row table; promote to typed indexes when a real query workload materializes.

**No audit_log integration** (Q6 Reading A per F4.5.3 reasoning): `pm_spin_rationale_log` IS itself the durable record. Cross-recording into `audit_log` would create two-write-paths-for-same-event with divergence risk between table and audit, plus operator confusion about canonical source. `audit_log` scope is cross-cutting events needing cross-domain query; pm_spin events query target IS this table directly. Volume is a second-order observation (~2880 rows/day at 30s tick cadence; commit 7 finalizes cadence) but not the load-bearing reason for skipping audit integration.

### `audit_sample_log`

Random-audit-sampling persistence per `BACKLOG.md` § Observability roadmap (silent-compliance defenses, M5-continuous-pm-driver scope). Each row captures one sampler decision: the source event that was sampled, the kind of source event, and the async re-evaluation lifecycle state. PM's continuous loop driver subprocess (commits 7-8) calls `record_sampleable_event` after observing sampleable events; `audit/sampler.py:Sampler` coin-flips per `Settings.audit_sample_percentage` and writes an `audit_sample_log` row with `re_eval_state='pending'` if selected.

```sql
CREATE TABLE audit_sample_log (
    id TEXT PRIMARY KEY,
    sampled_at TEXT NOT NULL DEFAULT (datetime('now')),
    source_event_kind TEXT NOT NULL CHECK (source_event_kind IN (
        'pm.current_focus.completed',
        'pm.current_focus.contract_violation',
        'pm.current_focus.superseded',
        'pm.spin_rationale.recorded',
        'dispatch.complete_called'
    )),
    source_event_id TEXT NOT NULL,
    re_eval_state TEXT NOT NULL DEFAULT 'pending' CHECK (re_eval_state IN (
        'pending', 'in_progress', 'complete'
    )),
    re_eval_finding TEXT,
    re_eval_completed_at TEXT
);

CREATE INDEX idx_audit_sample_state_sampled_at
    ON audit_sample_log(re_eval_state, sampled_at);
```

The async re-evaluator (the handler that processes `pending → in_progress → complete` with `re_eval_finding` text; possibly LLM-driven) is **out of scope** at commit 4 per F5.2 Q10 Reading A disposition. Deferred to the post-M5-flag-flips focused-observability sub-milestone per F5.6.C disposition; `BACKLOG.md` § Carry-forward to future sub-milestones carries the deferral entry.

**Constraint shape** (per F5.2 + F5.6 dispositions, 2026-05-05):

- `source_event_kind` CHECK enforces the closed 5-value enum (Q1 reading reversal per F5.6.B). Defense-in-depth alongside the `audit/sampler.py:SourceEventKind` Literal. Future expansion (Engineer's continuous-loop dispatch.* sampling, operator-driven additional kinds) lands via migration mirroring `0008_dispatches_status_check.sql` precedent.
- `source_event_id` polymorphic free TEXT, NOT NULL, no FK (Q2 Reading A). Per-kind id semantic table:

| event_kind | source_event_id semantic |
|---|---|
| `pm.current_focus.completed` | `audit_log.id` of the corresponding audit row |
| `pm.current_focus.contract_violation` | `audit_log.id` of the corresponding audit row |
| `pm.current_focus.superseded` | `audit_log.id` of the corresponding audit row |
| `pm.spin_rationale.recorded` | `pm_spin_rationale_log.id` of the row (no audit per commit 3 Q6 Reading A) |
| `dispatch.complete_called` | `audit_log.id` of the corresponding audit row |

Multi-table referential integrity not enforced at write time; dangling references possible if source row is deleted. Acceptable trade-off for the heterogeneous-event classifier-plus-id pattern.

- `re_eval_state` CHECK enforces the closed 3-value state-machine enum (Q5 Reading A): `pending` / `in_progress` / `complete`. Default `pending` on INSERT — sampler writes don't specify state. Transitions to `in_progress` / `complete` via the future async re-evaluator.
- `re_eval_finding` and `re_eval_completed_at` nullable: populated only by the future re-evaluator on completion.
- Single composite index on `(re_eval_state, sampled_at)`: supports the future re-evaluator's "all pending in time order" scan AND operator-side time-window queries somewhat efficiently. Mirrors commit 2/3 single-index discipline.

**Excluded `source_event_kind` values + rationale:**

- `pm.current_focus.declared` — declares are unmodifiable assertions (no validation outcome to re-evaluate).
- `pm_mode.transition` — low-volume; sampling barely makes sense.
- `api_usage.recorded` — high-volume; cost cohort sampled differently (per-call cost-interrogation tier 1 already shipped at M5-subscription-routing).
- `dispatch.subprocess_terminated` and other dispatch lifecycle events — `dispatch.complete_called` is the load-bearing terminal event (where validation outcome is captured); subprocess_terminated is the wrapper event. Add via migration if subprocess_terminated sampling becomes useful.

**No audit_log integration** (Q6 Reading A per F4.5.3 reasoning, continued from commit 3): `audit_sample_log` IS itself the durable record of what was sampled. Cross-recording into `audit_log` would create two-write-paths-for-same-event with divergence risk and operator confusion about canonical source.

## Filesystem layout

Root: `${MERIDIAN_ORCH_DATA_DIR}` (default `~/.meridian-orchestrator/`).

```
~/.meridian-orchestrator/
├── data/
│   └── orchestrator.db           # SQLite database
├── knowledge/
│   ├── company/
│   │   └── decisions/            # Mike-approved strategic/foundational decisions
│   ├── product/
│   │   └── substrate/
│   │       ├── stage-6-checkpoints/
│   │       ├── work-logs/                # Engineer-written work artifacts
│   │       ├── foundation-doc.md         # synced from meridian-homepoc/docs/design/visual-foundation.md
│   │       ├── queue.md                  # synced from meridian-homepoc/phases/checkpoint-queue.md
│   │       └── decisions/                # PM-write-only foundational/strategic decisions
│   └── README.md
├── config/
│   ├── orchestrator.yaml
│   └── agents/
│       ├── pm.yaml
│       ├── engineer.yaml
│       ├── implementer.yaml
│       └── tester.yaml
├── logs/
│   ├── orchestrator.log                  # symlink to today's log for easy tailing
│   └── orchestrator-YYYY-MM-DD.log       # daily rotated
└── backups/
    └── orchestrator-YYYY-MM-DD.db        # last 7 retained
```

Everything under `data/` and `logs/` is owned by the daemon and not edited by hand. Everything under `config/` and `knowledge/` is hot-reloadable and editable by Mike (with appropriate care).

## Migrations

Forward-only. Idempotent at the table-creation level (`CREATE TABLE IF NOT EXISTS`). Applied automatically on startup.

Migration file format: `migrations/NNNN_description.sql`. Each migration is a single SQL transaction. The migration runner records the applied version in `_schema_version` after success. If the migration runner crashes mid-migration, the next startup detects the partial state and aborts loudly — no auto-rollback.

Phase 1 migration plan:

| File | Milestone | Adds |
|---|---|---|
| `0001_initial.sql` | M1 | Original schema: companies, projects, agents (with non-unique role index), tasks (without `estimated_tokens`/`actual_tokens`), api_usage, audit_log |
| `0002_task_planning.sql` | M4 | `ALTER TABLE tasks ADD COLUMN estimated_tokens INTEGER; ALTER TABLE tasks ADD COLUMN actual_tokens INTEGER;` |
| `0005_dispatches.sql` | M4 | `dispatches` table per `11-engineer-bridge.md` § Storage shape; multi-per-task dispatch lifecycle tracking |
| `0003_pm_mode.sql` | M5 | `pm_mode_log` table + `idx_pm_mode_timestamp` |
| `0004_notifications.sql` | M6 | `inbound_messages`, `outbound_messages`, plus partial indexes |
| `0009_observability_cost_tier1.sql` | M5 | `api_usage` cohort columns (`notes`, `workflow_id`, `agent_role`, `task_type`) + indexes (`idx_api_usage_workflow`, `idx_api_usage_role_type`) per `BACKLOG.md` § Observability roadmap |
| `0010_pm_current_focus.sql` | M5-continuous-pm-driver | `pm_current_focus` table + `idx_pm_focus_declared_at` per `BACKLOG.md` § Observability roadmap (narration tier 1, M5-continuous-pm-driver scope) |
| `0011_pm_spin_rationale_log.sql` | M5-continuous-pm-driver | `pm_spin_rationale_log` table + `idx_pm_spin_iteration_timestamp` per `BACKLOG.md` § Observability roadmap (fitness tier 1, M5-continuous-pm-driver scope) |
| `0012_audit_sample_log.sql` | M5-continuous-pm-driver | `audit_sample_log` table + `idx_audit_sample_state_sampled_at` per `BACKLOG.md` § Observability roadmap (silent-compliance defenses, M5-continuous-pm-driver scope) |

No `bucket` column ships in `api_usage`. The premium-first cost model superseded by the subscription-first architecture (Experiment 4, 2026-05-01) drops per-call bucket attribution from the schema in favor of query-time derivation from `model` plus the daemon's polled `/api/oauth/usage` snapshot. See `project_subscription_first_architecture` memory.

The split-by-milestone approach is the default for clean traceability. Folding multiple migrations into earlier files is acceptable if no production state exists at the time the later milestone starts (e.g., if Phase 1 build accelerates and M4 starts before M1 has been used in real work, fold 0002 into 0001). Collapse only deliberately.

Migration numbering is sequential by *application order*, not by milestone ship order. M4 ships 0002 + 0005; M5 ships 0003 + 0006; M6 ships 0004. Each migration is independent (no cross-references between 0002/0005 and 0003/0004/0006), so the apparent gap and overlap are functionally safe — the migration runner applies whatever new files are in the sql/ dir on startup, in numeric order.

## Backup

On startup and once every 24 hours, the daemon dumps `orchestrator.db` to `backups/orchestrator-YYYY-MM-DD.db`. SQLite's `VACUUM INTO` is used (atomic, consistent, doesn't lock the live DB).

Retention: last 7. Older files deleted after the new one lands successfully.

Restore is a manual operation (Mike copies a backup file into `data/`). Phase 1 doesn't ship automated restore.

## Initial seed

`scripts/init-db.py` (M1) seeds:

```sql
INSERT INTO companies (id, name) VALUES ('<uuid>', 'Meridian AI');
INSERT INTO projects (id, company_id, name, working_directory)
  VALUES ('<uuid>', '<company_id>', 'Meridian Substrate Product', 'C:\Users\mbgol\dev\meridian-homepoc');
```

Agent rows are seeded by the agent registry on startup from the YAML configs (M3), not the init script.
