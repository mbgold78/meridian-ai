-- 0016_autonomy_liveness_violations.sql
-- Operator Fix Window 2026-05-26: AutonomyLivenessWatchdog persistence.
-- Purpose: daemon-owned table for the liveness watchdog to dedup violations
-- across ticks, track escalation state, and surface the active set via the
-- GET /api/liveness read API.
--
-- The watchdog runs every interval_seconds (default 60s) and inserts one row
-- per (violation_type, project_id, task_id, dispatch_id) tuple. Subsequent
-- detections of the same violation UPDATE the existing row (tick_count++,
-- last_seen_at = now). When a violation is no longer detected, resolved_at
-- gets set. Escalation fires after tick_count >= escalation_ticks (default 2).
--
-- violation_key is the UNIQUE composite dedup key built as
--   f"{violation_type}:{project_id or ''}:{task_id or ''}:{dispatch_id or ''}"
-- so the watchdog's INSERT OR IGNORE / UPDATE WHERE violation_key=? path is
-- atomic at the SQL layer without read-modify-write races.
--
-- Greenfield-safe: additive migration; no existing rows referenced.

CREATE TABLE liveness_violations (
    id                  TEXT PRIMARY KEY,
    violation_type      TEXT NOT NULL,
    violation_key       TEXT NOT NULL UNIQUE,
    project_id          TEXT,
    task_id             TEXT,
    dispatch_id         TEXT,
    observed_state_json TEXT,
    safe_next_action    TEXT,
    pm_task_id          TEXT,
    first_seen_at       TEXT NOT NULL DEFAULT (datetime('now')),
    last_seen_at        TEXT NOT NULL DEFAULT (datetime('now')),
    tick_count          INTEGER NOT NULL DEFAULT 1,
    escalated           INTEGER NOT NULL DEFAULT 0,
    escalated_at        TEXT,
    resolved_at         TEXT
);

CREATE INDEX idx_liveness_violations_violation_key ON liveness_violations(violation_key);
CREATE INDEX idx_liveness_violations_active        ON liveness_violations(resolved_at) WHERE resolved_at IS NULL;
CREATE INDEX idx_liveness_violations_pm_task       ON liveness_violations(pm_task_id);
