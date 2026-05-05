-- Migration 0011_pm_spin_rationale_log.sql (M5-continuous-pm-driver commit 3).
--
-- Adds pm_spin_rationale_log: PM's per-iteration keep-alive-vs-shutdown
-- rationale log per BACKLOG.md § Observability roadmap (fitness tier 1,
-- M5-continuous-pm-driver scope) and 05-data-model.md §
-- pm_spin_rationale_log. Each row captures one loop-iteration decision:
-- whether PM kept itself alive or surfaced a shutdown recommendation,
-- and the rationale_tag classifying why.
--
-- One write path lands at this commit:
--
-- 1. PM-initiated record_spin_rationale via the MCP tool — one row per
--    planning iteration (per F4.2 Q1 Reading A: per-tick, no dedup).
--    Loop driver at commits 7-8 will be the production caller; commit 3
--    ships the persistence + writer + MCP wrapper so the consumer can
--    integrate against the real surface without co-shipping coupling.
--
-- Schema decisions (per F4.2 dispositions, 2026-05-04):
--
-- - **decision CHECK constraint** (Q2 Reading A): closed 2-value enum
--   locks taxonomy at write time. Defense-in-depth alongside the
--   tools/pm_spin.py:Decision Literal — Python types catch developer
--   typos at edit time, the CHECK catches the same class of bug from
--   manual SQL or future external integrations. Sentinel test pins the
--   enum.
-- - **rationale_tag CHECK constraint** (F3.6 Q3 Reading A from
--   Followup-2 + this commit's enum sentinel): closed 4-value enum
--   (queued_work / mike_presence / heartbeat_cheap /
--   forgot_to_shutdown). Same defense-in-depth shape as decision.
--   Note "mike_presence" not "mike-presence" — underscore convention
--   matches mode/triggered_by enums.
-- - **Single index on iteration_timestamp** (mirrors commit 2's Q5
--   Reading B disposition): the loop's append-only writes don't need
--   indexed lookup at write time; time-windowed roll-up queries (the
--   primary read path for tier-2 fitness surfaces) benefit from the
--   timestamp index. rationale_tag-cohort and decision-cohort indexes
--   deferred — operator-side ad-hoc SQL is sequential-scan cheap on a
--   <1M-row table; promote to typed indexes when a real query workload
--   materializes (mirrors the bound_workflow_id deferral pattern from
--   commit 2).
-- - **rationale_free_text nullable**: rationale_tag is the primary
--   cohort signal (closed enum); free_text is supplementary detail
--   PM may attach for diagnostic context. Most rows leave it NULL.
-- - **No audit_log integration** (Q6 Reading A per F4.5.3 reasoning
--   revision): pm_spin_rationale_log IS itself the durable record;
--   cross-recording into audit_log would create two-write-paths with
--   divergence risk and operator confusion about canonical source.
--   Audit_log scope is cross-cutting events needing cross-domain
--   query; pm_spin events query target IS this table directly.
--
-- Reservation gap closes here: 0010 was the most recent applied
-- (M5-continuous-pm-driver commit 2); 0011 fills the next sequential
-- slot. 0004 remains reserved for M6 notifications per
-- 0001_initial.sql header.

CREATE TABLE pm_spin_rationale_log (
    id TEXT PRIMARY KEY,
    iteration_timestamp TEXT NOT NULL DEFAULT (datetime('now')),
    decision TEXT NOT NULL CHECK (decision IN (
        'kept_alive', 'shutdown_recommended'
    )),
    rationale_tag TEXT NOT NULL CHECK (rationale_tag IN (
        'queued_work', 'mike_presence', 'heartbeat_cheap', 'forgot_to_shutdown'
    )),
    rationale_free_text TEXT
);

CREATE INDEX idx_pm_spin_iteration_timestamp ON pm_spin_rationale_log(iteration_timestamp);
