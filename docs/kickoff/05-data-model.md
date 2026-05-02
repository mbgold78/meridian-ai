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
