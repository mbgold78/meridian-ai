-- Migration 0012_audit_sample_log.sql (M5-continuous-pm-driver commit 4).
--
-- Adds audit_sample_log: random-audit-sampling persistence per
-- BACKLOG.md § Observability roadmap (silent-compliance defenses,
-- M5-continuous-pm-driver scope) and 05-data-model.md §
-- audit_sample_log. Each row captures one sampler decision: the
-- source event that was sampled, the kind of source event, and the
-- async re-evaluation lifecycle state.
--
-- One write path lands at this commit:
--
-- 1. PM-initiated record_sampleable_event MCP tool — PM's loop
--    driver subprocess (commits 7-8) calls this hook after observing
--    sampleable events; sampler picks per audit_sample_percentage
--    config; if selected, writes an audit_sample_log row with
--    re_eval_state='pending'.
--
-- The async re-evaluator (the actual handler that processes
-- pending → in_progress → complete with re_eval_finding text;
-- possibly LLM-driven) is OUT of scope at commit 4 per F5.2 Q10
-- Reading A disposition; deferred to the post-M5-flag-flips
-- focused-observability sub-milestone per F5.6.C (b)-refined
-- disposition. BACKLOG.md § Carry-forward to future sub-milestones
-- carries the deferral entry.
--
-- Schema decisions (per F5.2 + F5.6 dispositions, 2026-05-05):
--
-- - **source_event_kind CHECK constraint** (Q1 Reading A per F5.6.B
--   Q1 reading reversal): closed 5-value enum locks taxonomy at
--   write time. Defense-in-depth alongside the
--   audit/sampler.py:SourceEventKind Literal. The 5 values match
--   the events PM's loop driver will observe and emit
--   record_sampleable_event for; future expansion (Engineer
--   continuous loop's dispatch.* sampling, operator-driven
--   additional kinds) lands via migration mirroring
--   0008_dispatches_status_check.sql precedent.
-- - **source_event_id polymorphic free TEXT, NOT NULL, no FK**
--   (Q2 Reading A): per-kind id semantics documented in
--   05-data-model.md (4 kinds reference audit_log.id; the 5th
--   pm.spin_rationale.recorded references pm_spin_rationale_log.id
--   per commit 3 Q6 Reading A — pm_spin_rationale events have no
--   audit_log integration). Multi-table referential integrity not
--   enforced at write time; dangling references possible if source
--   row is deleted. Acceptable trade-off for heterogeneous-event
--   classifier-plus-id pattern.
-- - **re_eval_state CHECK constraint** (Q5 Reading A): closed
--   3-value state-machine enum (pending / in_progress / complete).
--   Default 'pending' on INSERT — sampler writes don't specify the
--   state. State transitions to in_progress / complete by the
--   future re-evaluator (deferred per Q10).
-- - **re_eval_finding nullable TEXT**: populated only when
--   re_eval_state='complete' (or 'in_progress' with progress notes,
--   if the future re-evaluator chooses that shape). Free-form text
--   for the LLM-driven re-evaluation outcome.
-- - **re_eval_completed_at nullable TEXT**: populated only when
--   re_eval_state='complete'. Mirrors the completed_at NULL-then-
--   set pattern from pm_current_focus (commit 2).
-- - **Single composite index on (re_eval_state, sampled_at)**:
--   supports the future re-evaluator's "all pending in time order"
--   scan (primary load-bearing read shape) AND operator-side "all
--   events sampled in time window" queries somewhat efficiently.
--   Mirrors commit 2/3 single-index discipline. Additional cohort
--   indexes (by source_event_kind) deferred to BACKLOG per the same
--   trigger pattern as commit 2's bound_workflow_id deferral.
-- - **No audit_log integration** (Q6 Reading A per F4.5.3 reasoning,
--   continued from commit 3): audit_sample_log IS itself the
--   durable record of what was sampled. Cross-recording into
--   audit_log would create two-write-paths-for-same-event with
--   divergence risk and operator confusion about canonical source.
--   audit_log scope is cross-cutting events needing cross-domain
--   query; sample selection events query target IS this table
--   directly.
--
-- Reservation gap closes here: 0011 was the most recent applied
-- (M5-continuous-pm-driver commit 3); 0012 fills the next sequential
-- slot and matches the F2.4 revised migration count (three new
-- migrations at this sub-milestone: 0010 / 0011 / 0012). 0004
-- remains reserved for M6 notifications per 0001_initial.sql header.

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
