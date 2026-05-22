-- Migration 0014_canonical_clones.sql (M5-canonical-clone-mgmt slice 1).
--
-- Two coupled changes laying the storage substrate for the daemon to
-- know about more than a single canonical clone:
--
-- 1. Adds the canonical_clones registry table — id (TEXT PK),
--    name (TEXT NOT NULL UNIQUE), path, upstream_url, default_branch
--    (DEFAULT 'main'), created_at (DEFAULT datetime('now')). Slice 1
--    materializes the 'default' row at startup via
--    bridge.canonical_registry.CanonicalCloneRegistry.ensure_default;
--    Slices 2+ extend to multi-canonical operations (register/unregister
--    surfaced as MCP tools, per-dispatch canonical scoping).
--
-- 2. Adds dispatches.canonical_name (TEXT NOT NULL DEFAULT 'default').
--    Slice 1 does not yet thread canonical_name into the dispatcher;
--    the column ships with a 'default' default so existing writers
--    remain correct and pre-migration rows inherit the canonical they
--    were actually targeting (the only canonical that existed).
--
-- SQLite has no ALTER TABLE ADD COLUMN that emits a CHECK constraint
-- alongside the new column, and the task explicitly directs the
-- rewrite pattern for consistency with 0013_tasks_task_type_and_status_
-- check.sql's precedent (ALTER TABLE ADD COLUMN would also work here
-- since canonical_name carries no CHECK, but the rewrite shape keeps
-- the migration's blast-radius surface uniform with prior CHECK-adding
-- migrations). The CREATE-INSERT-DROP-RENAME pattern preserves all
-- column shapes accumulated through 0008:
--   - task_id NULLABLE per 0007_dispatches_task_id_nullable.sql
--     (supervisor rows carry no task_id)
--   - status CHECK constraint per 0008_dispatches_status_check.sql
--     (8-value enum: queued | running | completed | declined | failed |
--     timed_out | cancelled | compacted)
--   - last_tool_called per 0006_dispatches_last_tool_called.sql
--
-- Greenfield safety: at migration time, existing dispatches rows
-- (if any) inherit canonical_name='default' via the column default —
-- this is correct because the 'default' canonical is the only
-- canonical that has ever existed in production code paths up to
-- Slice 1. The matching canonical_clones 'default' row is NOT seeded
-- by this migration (per the data-free-migrations convention);
-- bridge.canonical_registry.CanonicalCloneRegistry.ensure_default
-- materializes it at startup with the runtime-resolved path +
-- upstream_url, neither of which is knowable from inside a migration.
--
-- Indexes from 0005_dispatches.sql explicitly recreated post-RENAME
-- (mirrors the 0013 pattern; SQLite's RENAME does not preserve
-- indexes since they were defined against the dropped table name).

CREATE TABLE canonical_clones (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    path TEXT NOT NULL,
    upstream_url TEXT NOT NULL,
    default_branch TEXT NOT NULL DEFAULT 'main',
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

-- Preserve all column shapes accumulated through 0008:
--   - task_id NULLABLE per 0007 (supervisor rows)
--   - status CHECK constraint per 0008 (8-value enum)
--   - last_tool_called per 0006
CREATE TABLE dispatches_new (
    id TEXT PRIMARY KEY,
    task_id TEXT REFERENCES tasks(id),
    role TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'queued' CHECK (status IN (
        'queued', 'running', 'completed', 'declined', 'failed',
        'timed_out', 'cancelled', 'compacted'
    )),
    working_directory TEXT NOT NULL,
    spec_path TEXT,
    commit_hash TEXT,
    decline_reason TEXT,
    estimated_tokens INTEGER,
    actual_tokens INTEGER,
    timeout_seconds INTEGER NOT NULL,
    timeout_override_seconds INTEGER,
    keep_worktree_on_failure INTEGER NOT NULL DEFAULT 0,
    token_id TEXT,
    subprocess_pid INTEGER,
    engineer_decisions_json TEXT,
    issued_at TEXT NOT NULL DEFAULT (datetime('now')),
    started_at TEXT,
    last_activity_at TEXT,
    completed_at TEXT,
    timed_out_at TEXT,
    merge_conflict_files_json TEXT,
    actual_touched_files_json TEXT,
    artifacts_json TEXT,
    test_results_json TEXT,
    queue_items_raised_json TEXT,
    notes TEXT,
    last_tool_called TEXT,
    canonical_name TEXT NOT NULL DEFAULT 'default'
);

INSERT INTO dispatches_new
    (id, task_id, role, status, working_directory, spec_path, commit_hash,
     decline_reason, estimated_tokens, actual_tokens, timeout_seconds,
     timeout_override_seconds, keep_worktree_on_failure, token_id,
     subprocess_pid, engineer_decisions_json, issued_at, started_at,
     last_activity_at, completed_at, timed_out_at, merge_conflict_files_json,
     actual_touched_files_json, artifacts_json, test_results_json,
     queue_items_raised_json, notes, last_tool_called)
SELECT
    id, task_id, role, status, working_directory, spec_path, commit_hash,
    decline_reason, estimated_tokens, actual_tokens, timeout_seconds,
    timeout_override_seconds, keep_worktree_on_failure, token_id,
    subprocess_pid, engineer_decisions_json, issued_at, started_at,
    last_activity_at, completed_at, timed_out_at, merge_conflict_files_json,
    actual_touched_files_json, artifacts_json, test_results_json,
    queue_items_raised_json, notes, last_tool_called
FROM dispatches;

DROP TABLE dispatches;

ALTER TABLE dispatches_new RENAME TO dispatches;

-- Recreate the four indexes from 0005_dispatches.sql.
CREATE INDEX idx_dispatches_task ON dispatches(task_id);
CREATE INDEX idx_dispatches_status ON dispatches(status);
CREATE INDEX idx_dispatches_role_status ON dispatches(role, status);
CREATE INDEX idx_dispatches_role_completed ON dispatches(role, status, completed_at) WHERE status='completed';
