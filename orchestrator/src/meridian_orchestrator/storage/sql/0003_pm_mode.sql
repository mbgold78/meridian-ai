-- Migration 0003_pm_mode.sql (M5-pm-mode-and-window-state).
--
-- Adds pm_mode_log: PM's mode-transition observability log per
-- 05-data-model.md § pm_mode_log. Records mode flips (interactive ↔
-- continuous, etc.) for Mike's dashboard inspection and for Engineer's
-- mode-aware planning via get_window_state's current_pm_mode field.
--
-- Two write paths land at M5-pm-mode-and-window-state:
--
-- 1. PM-initiated via the set_pm_mode MCP tool — deliberate transitions
--    (entering paused_question when surfacing a blocker, returning to
--    continuous after Mike's reply lands).
-- 2. Daemon-initiated via the auto-flip writer — observes Mike's MCP
--    token use and writes mode='interactive' with triggered_by=
--    'mike_message'; flips back to continuous 30 minutes after the
--    last such call.
--
-- Observability table — not load-bearing state. Continuous-mode PM
-- polls this on each planning iteration to detect Mike's presence;
-- get_window_state surfaces the latest mode + timestamp via
-- current_pm_mode and most_recent_pm_mode_log_timestamp so callers
-- don't need a separate get_pm_mode tool.
--
-- CHECK constraints on mode and triggered_by: defense-in-depth per
-- 0008_dispatches_status_check.sql's precedent. Python types catch
-- developer-introduced typos at edit time; CHECK catches the same
-- class of bug at write time, including writes from manual SQL or
-- future external integrations. mode is NOT NULL with an unconditional
-- CHECK; triggered_by is nullable so the CHECK uses the
-- "IS NULL OR ... IN (...)" pattern.
--
-- Reservation gap: 0003 was reserved for this migration at M1 (per
-- 0001_initial.sql header) and held through M4. Migration numbering
-- is sequential by application order, not ship order — the runner
-- applies whatever new files are present in numeric order on startup,
-- so the apparent gap closing here is functionally safe.

CREATE TABLE pm_mode_log (
    id TEXT PRIMARY KEY,
    timestamp TEXT NOT NULL DEFAULT (datetime('now')),
    mode TEXT NOT NULL CHECK (mode IN (
        'interactive', 'continuous', 'paused_blocked', 'paused_question'
    )),
    reason TEXT,
    triggered_by TEXT CHECK (triggered_by IS NULL OR triggered_by IN (
        'mike_message', 'window_reset', 'queue_empty', 'rate_limit', 'manual'
    ))
);

CREATE INDEX idx_pm_mode_timestamp ON pm_mode_log(timestamp);
