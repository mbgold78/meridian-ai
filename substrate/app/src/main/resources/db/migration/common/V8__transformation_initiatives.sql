-- ============================================================================
-- V8__transformation_initiatives.sql  (common — ANSI-portable; H2 PG-mode +
-- Postgres)
--
-- D5 thin slice (engine-spec diagnostic.md §3.3; ADR-004 D13) — DECLARED S4
-- DEVIATION: doc-06 TransformationPlan/TransformationInitiative fields ported
-- faithfully so progressPct becomes an honest, operator-tracked stat (fixture
-- seam S6), but planning-engine semantics (wave sequencing rules, plan
-- state-machine gates, rationalization lens) are NOT implemented — spec
-- §15.9, EXTENSION POINT(diagnostic-9). The fixture GET /transformation/plan
-- stays behind its flag, untouched.
--
-- Soft refs (plan_id, target_capability_id, target_process_id,
-- target_system_id, prerequisites, evidence_ids) carry NO DB FKs —
-- application-enforced per the canonical-model convention.
-- ============================================================================

CREATE TABLE transformation_plans (
    id UUID NOT NULL, version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE, updated_at TIMESTAMP WITH TIME ZONE, created_by VARCHAR(255),
    domain_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(30) NOT NULL,              -- DRAFT|UNDER_REVIEW|APPROVED|EXECUTING|COMPLETE|ARCHIVED (doc-06; transitions unenforced v1)
    target_horizon_months INT,
    reduction_mode VARCHAR(20),               -- AGGRESSIVE|BALANCED|CONSERVATIVE (lens param; lens not built)
    approved_by VARCHAR(255),
    approved_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);
CREATE INDEX idx_tplans_domain ON transformation_plans(domain_id);

CREATE TABLE transformation_initiatives (
    id UUID NOT NULL, version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE, updated_at TIMESTAMP WITH TIME ZONE, created_by VARCHAR(255),
    plan_id UUID NOT NULL,                    -- soft ref -> transformation_plans
    name VARCHAR(255) NOT NULL,
    description TEXT,
    initiative_type VARCHAR(30) NOT NULL,     -- AGENT_DEPLOY|SYSTEM_RETIRE|PROCESS_REDESIGN|ORG_CHANGE|REGULATORY|CAPABILITY_ADD|CAPABILITY_RETIRE
    status VARCHAR(20) NOT NULL,              -- PLANNED|IN_PROGRESS|COMPLETE|DEFERRED|CANCELLED
    target_capability_id UUID,
    target_process_id UUID,
    target_system_id UUID,
    wave INT,
    owner_name VARCHAR(255),
    estimated_months INT,
    complexity VARCHAR(10),                   -- LOW|MEDIUM|HIGH
    annual_savings_usd BIGINT,
    prerequisites TEXT,                       -- JSON UUID array (dependency graph; unvalidated v1)
    evidence_ids TEXT,                        -- JSON UUID array
    progress_pct INT NOT NULL DEFAULT 0,      -- operator-maintained; COMPLETE => rendered 100 (only computed rule)
    progress_note TEXT,
    progress_updated_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);
CREATE INDEX idx_tinitiatives_plan ON transformation_initiatives(plan_id);
