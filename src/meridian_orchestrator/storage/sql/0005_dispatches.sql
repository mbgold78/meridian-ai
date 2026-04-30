-- Dispatch tracking (M4).
--
-- One row per Engineer-driven sub-agent dispatch (Implementer/Tester) or
-- per long-running supervisor instance (engineer-supervisor; pm-continuous
-- ships at M5 but uses this same table). Multiple dispatches per task are
-- expected — retries, staged execution, parallel work — so this is a
-- separate table rather than a tasks ALTER.
--
-- Key columns per 11-engineer-bridge.md § Storage shape:
--   - token_id holds the keyring token_id (short hex prefix), never the secret
--   - actual_touched_files_json populated by daemon from `git diff --name-only`
--     post-completion; consumed by Engineer's substrate touch map cache via
--     get_recent_dispatches
--   - merge_conflict_files_json populated when --ff-only fails on the dispatch
--     branch merge into substrate-canonical/main (M4b)
--   - engineer_decisions_json captures the altitude-1 decisions Engineer made
--     when composing the spec; sub-agent reads as constraints
--   - status terminal values: completed | declined | failed | timed_out |
--     cancelled | compacted (the last is M4c-specific for engineer-supervisor)

CREATE TABLE dispatches (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES tasks(id),
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
    notes TEXT
);

CREATE INDEX idx_dispatches_task ON dispatches(task_id);
CREATE INDEX idx_dispatches_status ON dispatches(status);
CREATE INDEX idx_dispatches_role_status ON dispatches(role, status);
CREATE INDEX idx_dispatches_role_completed ON dispatches(role, status, completed_at) WHERE status='completed';
