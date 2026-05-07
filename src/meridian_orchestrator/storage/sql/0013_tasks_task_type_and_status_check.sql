-- Migration 0013_tasks_task_type_and_status_check.sql (M4d-task-management-surface commit 1).
--
-- Two coupled changes to the tasks table:
--
-- 1. Adds tasks.task_type (TEXT, nullable) — cohort key per BACKLOG
--    entry "Resolve api_usage.task_type population protocol" (γ
--    protocol resolved at M4d-task-management-surface Q1 disposition,
--    2026-05-07): operator threads task_type at submit_task time;
--    agents inherit through the dispatch chain via parent_task_id walk
--    in record_api_call (commit 7 of M4d extends the existing
--    workflow_id α-denormalization recursive CTE to also resolve
--    task_type from the chain root).
--
-- 2. Adds CHECK constraint on tasks.status enforcing the seven-value
--    state-machine enum that the application layer (storage.models.
--    TaskStatus Literal) has enforced since M1. Defense-in-depth per
--    0008_dispatches_status_check.sql precedent — Python types catch
--    typos at edit time, the CHECK catches the same class of bug from
--    manual SQL or future external integrations.
--
-- task_type taxonomy (per Q1 disposition, 2026-05-07): nine values
-- across coordination shape (managing-other-work) and production
-- shape (doing-the-work) plus explicit-unknown fallback:
--
--   Coordination: planning, dispatching, monitoring, review
--   Production:   investigation, synthesis, execution, revision
--   Fallback:     uncategorized
--
-- The taxonomy describes work-**shape** (cognitive operation), NOT
-- work-**domain** (product/area). Meridian Orchestrator is product-
-- agnostic infrastructure; tasks.task_type lives in the data model
-- used by every product the orchestrator runs. Per-product
-- work_domain (e.g., 'code_implementation', 'substrate_review') is
-- BACKLOG work — currently lives in details_json as free-text;
-- future per-product taxonomy lands when orchestrator runs against
-- multiple products.
--
-- Coordination-shape values correlate with PM role; production-shape
-- values correlate with Engineer/Implementer/Tester roles. Cohort
-- queries gain discriminability across both axes (work-shape ×
-- agent_role).
--
-- SQLite has no ALTER TABLE ADD CONSTRAINT, so this migration uses
-- the CREATE-INSERT-DROP-RENAME pattern (same shape as
-- 0007_dispatches_task_id_nullable.sql and
-- 0008_dispatches_status_check.sql). The new table has both
-- constraints; all existing rows already conform to TaskStatus's
-- seven-value enum (per the application-layer Literal that has been
-- the only writer since M1) so the INSERT-SELECT cannot violate the
-- new CHECK. Indexes recreated post-rename to match the original
-- 0001_initial.sql + 0002_task_planning.sql shape.
--
-- Greenfield safety: at migration time (first M4d daemon start, OR
-- test-fixture migration runs), tasks holds no rows that other
-- tables reference — submit_task ships at M4d, so pre-M4d tasks
-- INSERTs do not exist in production code paths. DROP TABLE tasks
-- succeeds because dispatches/api_usage/pm_current_focus/
-- outbound_messages reference rows have not been written.

CREATE TABLE tasks_new (
    id TEXT PRIMARY KEY,
    company_id TEXT NOT NULL REFERENCES companies(id),
    project_id TEXT NOT NULL REFERENCES projects(id),
    parent_task_id TEXT REFERENCES tasks_new(id),
    agent_role TEXT NOT NULL,
    description TEXT NOT NULL,
    priority INTEGER NOT NULL DEFAULT 2,
    complexity INTEGER NOT NULL DEFAULT 3,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN (
        'pending', 'claimed', 'in_progress',
        'completed', 'failed', 'declined', 'cancelled'
    )),
    task_type TEXT CHECK (task_type IN (
        'planning', 'dispatching', 'monitoring', 'review',
        'investigation', 'synthesis', 'execution', 'revision',
        'uncategorized'
    )),
    blocking_human INTEGER NOT NULL DEFAULT 0,
    estimated_tokens INTEGER,
    actual_tokens INTEGER,
    submitted_by TEXT,
    claimed_by TEXT,
    claimed_at TEXT,
    completed_at TEXT,
    notes TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

INSERT INTO tasks_new
    (id, company_id, project_id, parent_task_id, agent_role,
     description, priority, complexity, status, blocking_human,
     estimated_tokens, actual_tokens, submitted_by, claimed_by,
     claimed_at, completed_at, notes, created_at)
SELECT
    id, company_id, project_id, parent_task_id, agent_role,
    description, priority, complexity, status, blocking_human,
    estimated_tokens, actual_tokens, submitted_by, claimed_by,
    claimed_at, completed_at, notes, created_at
FROM tasks;

DROP TABLE tasks;

ALTER TABLE tasks_new RENAME TO tasks;

CREATE INDEX idx_tasks_status_priority ON tasks(status, priority);
CREATE INDEX idx_tasks_agent_role ON tasks(agent_role);
CREATE INDEX idx_tasks_parent ON tasks(parent_task_id);
