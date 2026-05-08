-- Migration 0008_dispatches_status_check.sql (M4c-compaction).
--
-- Adds a CHECK constraint on dispatches.status enforcing the lifecycle
-- enum at the DB layer:
--
--   queued | running | completed | declined | failed | timed_out
--                  | cancelled | compacted
--
-- (`compacted` joins the terminal-states list at M4c-compaction — Engineer
-- subprocess exits cleanly after signaling compaction; daemon marks the
-- row terminal with status='compacted' and respawns. See
-- 11-engineer-bridge.md § Engineer compaction.)
--
-- Precedent: this is the first CHECK constraint shipped on any column
-- in the schema. Other status-like enum columns (tasks.status,
-- agents.status, projects.status, etc.) currently rely on application-
-- layer Python literal types for enforcement. Future migrations should
-- consider adding similar CHECK constraints on those columns when
-- editing them, for the same defense-in-depth reason that motivated
-- this one (Python types catch developer-introduced typos at edit
-- time; CHECK catches the same class of bug at write time, including
-- writes from manual SQL or future external integrations).
--
-- SQLite has no ALTER TABLE ADD CONSTRAINT, so this migration uses
-- the CREATE-INSERT-DROP-RENAME pattern (same shape as 0007). The
-- new table has the constraint; all existing rows already conform
-- (per the application-layer enum) so the INSERT-SELECT cannot
-- violate the new CHECK. Indexes recreated post-rename.
--
-- The application-layer set bridge.dispatcher._TERMINAL_STATES already
-- includes 'compacted'. This migration aligns DB-level enforcement
-- with the Python set so the two stay in sync.

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
