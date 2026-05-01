-- Migration 0007_dispatches_task_id_nullable.sql (M4c-supervisor-base).
--
-- Drops the NOT NULL constraint on dispatches.task_id. NULL semantically
-- represents a long-running supervisor dispatch row (engineer-supervisor
-- at M4c, pm-continuous at M5) which has no parent task — supervisors
-- orchestrate other dispatches; they don't work on tasks themselves.
--
-- Sub-agent dispatch rows (Implementer/Tester) continue to require
-- task_id; the constraint is enforced at the application layer via
-- separate insertion paths:
--   - BridgeDispatcher.dispatch() (sub-agent path) requires task_id
--     as a non-optional parameter (Python type signature).
--   - LongRunningSupervisor.start() (supervisor path) inserts the row
--     directly with task_id=NULL, bypassing BridgeDispatcher.
-- The two call sites have distinct contracts; no shared insert path
-- means no runtime guard is needed.
--
-- See 11-engineer-bridge.md § Storage shape for the spec text.
--
-- SQLite has no ALTER TABLE ALTER COLUMN, so this migration uses the
-- CREATE-INSERT-DROP-RENAME pattern. The new table preserves the FK
-- to tasks(id) (just nullable now); all existing rows have valid
-- task_ids per the prior NOT NULL constraint, so the INSERT-SELECT
-- cannot violate the FK. No other tables reference dispatches.id, so
-- DROP TABLE doesn't break inbound FKs.
--
-- The full transaction (BEGIN/CREATE/INSERT/DROP/RENAME/CREATE INDEX/
-- COMMIT) runs atomically per the migration runner's storage.transaction()
-- wrapper. SQLite DDL statements are transactional, so a mid-flight
-- crash rolls back cleanly (dispatches restored from before BEGIN).
--
-- Indexes are recreated post-rename. idx_dispatches_task is preserved
-- unchanged: SQLite indexes NULL by default, and `WHERE task_id = ?`
-- queries naturally skip supervisor rows.

CREATE TABLE dispatches_new (
    id TEXT PRIMARY KEY,
    task_id TEXT REFERENCES tasks(id),
    role TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'queued',
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
    last_tool_called TEXT
);

INSERT INTO dispatches_new
    (id, task_id, role, status, working_directory, spec_path,
     commit_hash, decline_reason, estimated_tokens, actual_tokens,
     timeout_seconds, timeout_override_seconds, keep_worktree_on_failure,
     token_id, subprocess_pid, engineer_decisions_json,
     issued_at, started_at, last_activity_at, completed_at, timed_out_at,
     merge_conflict_files_json, actual_touched_files_json,
     artifacts_json, test_results_json, queue_items_raised_json,
     notes, last_tool_called)
SELECT
    id, task_id, role, status, working_directory, spec_path,
    commit_hash, decline_reason, estimated_tokens, actual_tokens,
    timeout_seconds, timeout_override_seconds, keep_worktree_on_failure,
    token_id, subprocess_pid, engineer_decisions_json,
    issued_at, started_at, last_activity_at, completed_at, timed_out_at,
    merge_conflict_files_json, actual_touched_files_json,
    artifacts_json, test_results_json, queue_items_raised_json,
    notes, last_tool_called
FROM dispatches;

DROP TABLE dispatches;

ALTER TABLE dispatches_new RENAME TO dispatches;

CREATE INDEX idx_dispatches_task ON dispatches(task_id);
CREATE INDEX idx_dispatches_status ON dispatches(status);
CREATE INDEX idx_dispatches_role_status ON dispatches(role, status);
CREATE INDEX idx_dispatches_role_completed ON dispatches(role, status, completed_at) WHERE status='completed';
