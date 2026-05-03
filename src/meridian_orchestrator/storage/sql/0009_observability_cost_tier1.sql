-- Migration 0009_observability_cost_tier1.sql (M5-subscription-routing).
--
-- Tier-1 cost-interrogation observability per BACKLOG.md § Observability
-- roadmap. Captures dimensions for cohort-based cost analysis without
-- pre-computing baselines: capture is upfront; derivation is later.
--
-- Four columns appended to api_usage:
--
-- - notes:        free-form agent self-annotation (cost-interrogation
--                 tier 1; ad-hoc tagging by the calling agent).
-- - workflow_id:  cohort key for "tokens spent on this end-to-end
--                 workflow." Population semantics deliberately deferred
--                 (BACKLOG entry frames three plausible protocols);
--                 ships as nullable optional input on RecordApiCallInput.
-- - agent_role:   denormalized from agents.role for query-side cohort
--                 aggregation without a JOIN. Auto-populated server-side
--                 from the caller's resolved role per Q6α disposition;
--                 not a request input field. Every row populated from
--                 day 1 — no NULL agent_role rows accumulate.
-- - task_type:    cohort key for "tokens spent on this kind of work."
--                 Population semantics deliberately deferred (BACKLOG
--                 entry frames three plausible protocols); ships as
--                 nullable optional input on RecordApiCallInput.
--
-- All four columns are nullable TEXT — no CHECK constraints, no
-- DEFAULTs. agent_role is server-populated and so non-null in
-- practice for new rows; existing rows from before this migration
-- remain NULL on agent_role until a future hygiene-commit backfill
-- (deferred — not load-bearing for routing).
--
-- Two indexes:
--
-- - idx_api_usage_workflow:    enables "all calls in this workflow"
--                              cohort queries.
-- - idx_api_usage_role_type:   enables "tokens by role × task_type"
--                              aggregation queries.
--
-- No CHECK constraints on agent_role even though the four-role enum is
-- known (pm/engineer/implementer/tester); mirrors agents.role's
-- permissiveness in 0001_initial.sql. Phase 2 multi-tenancy may add
-- agent role types we haven't named; the Python type system enforces
-- the current enum at the API boundary.
--
-- Schema-version 0009 fills the next un-reserved slot (0004 reserved
-- for M6 notifications per 0001_initial.sql header). Migration runner
-- discovers files numerically and applies pending; the apparent gap
-- 0008 → 0009 is functionally safe.

ALTER TABLE api_usage ADD COLUMN notes TEXT;
ALTER TABLE api_usage ADD COLUMN workflow_id TEXT;
ALTER TABLE api_usage ADD COLUMN agent_role TEXT;
ALTER TABLE api_usage ADD COLUMN task_type TEXT;

CREATE INDEX idx_api_usage_workflow ON api_usage(workflow_id);
CREATE INDEX idx_api_usage_role_type ON api_usage(agent_role, task_type);
