-- Migration 0010_pm_current_focus.sql (M5-continuous-pm-driver commit 2).
--
-- Adds pm_current_focus: PM's deliverable-contract narration table per
-- BACKLOG.md § Observability roadmap (narration tier 1, M5-continuous-
-- pm-driver scope) and 05-data-model.md § pm_current_focus. Each row
-- captures one work-unit boundary PM declares for itself: an objective,
-- the kind of output it produces (output_type), and the acceptance
-- criteria the actual output is validated against at completion.
--
-- Three write paths land at this commit:
--
-- 1. PM-initiated declare via the declare_current_focus MCP tool — opens
--    a new focus and (per Q8 Reading A) auto-supersedes any prior
--    incomplete focus in the same transaction.
-- 2. PM-initiated complete via the complete_current_focus MCP tool —
--    closes the active focus with contract_validated_bool +
--    contract_finding_text. Validation logic is loop-side (F2.2 A1
--    disposition); this writer just records the result.
-- 3. PM-initiated read via the get_current_focus MCP tool — surfaces
--    the active focus to the continuous-loop driver subprocess at
--    commits 7-8 (which cannot consume Python helpers across the
--    subprocess boundary; see BACKLOG § Observability roadmap scope
--    clarification 2026-05-04).
--
-- Schema decisions (per F3.6 dispositions, 2026-05-04):
--
-- - **output_type CHECK constraint** (Q1 Reading A): closed 5-value
--   enum locks taxonomy at write time. Defense-in-depth alongside the
--   tools/pm_focus.py:OutputType Literal — Python types catch developer
--   typos at edit time, the CHECK catches the same class of bug from
--   manual SQL or future external integrations. Sentinel test pins the
--   enum.
-- - **bound_workflow_id FK** (Q3 Reading A): TEXT REFERENCES tasks(id),
--   nullable, ON DELETE NO ACTION (the SQLite default; named here for
--   the future reader). PM-deliberate declare-time intent benefits from
--   referential integrity at write — typos or stale task ids fail
--   loudly. NO ACTION blocks task deletion when referencing focus rows
--   exist; SET NULL would silently destroy the historical "this focus
--   was bound to workflow X" narration claim. Different shape from
--   api_usage.workflow_id (which is dual-path: caller-supplied free-form
--   labels OR server-derived chain-walks; FK there would over-constrain
--   the caller-supplied path).
-- - **No pm_mode_log_id FK** (Q2 Reading B): foci change at PM's
--   deliberate declaration; modes flip asynchronously on Mike-interrupts.
--   The relationship is co-incident-not-owned; FK coupling produces
--   stale-FK risk on every mode flip mid-focus. Operator-side
--   "what mode was PM in when this focus landed" reconstructs via
--   timestamp-join against pm_mode_log.
-- - **Single index on declared_at** (Q5 Reading B): the loop's
--   at-most-once-per-tick read_current_focus lookup is well within
--   sequential-scan bounds; bound_workflow_id index deferred (BACKLOG
--   entry 2026-05-04). The api_usage.workflow_id index from migration
--   0009 is the load-bearing surface for cost-cohort queries; pm_current_focus
--   row counts stay 100x+ smaller and don't drive cohort workload.
-- - **Nullable contract_validated_bool / contract_finding_text**: only
--   set on complete; declare-time rows leave both NULL. SQLite stores
--   bool as INTEGER (0/1).
--
-- Reservation gap closes here: 0009 was the most recent applied; 0010
-- fills the next sequential slot. 0004 remains reserved for M6
-- notifications per 0001_initial.sql header.

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
