-- ============================================================================
-- V6_1__agent_registry_postgres.sql  (postgresql vendor dir ONLY — NEVER on H2)
-- Postgres-only backstops for the agent registry (engine-spec agents.md §4;
-- ADR-003 D1/D7/D12; precedent V2_1/V4_1/V5_1 — H2 dev stays
-- service-enforced; H2-green is never acceptance).
-- ============================================================================

-- Append-only audit trail (spec non-negotiable #3 / invariant I4): the
-- repository surface exposes no mutators and services never mutate loaded
-- rows; this trigger raises on any UPDATE/DELETE that slips past service
-- discipline.
CREATE OR REPLACE FUNCTION agent_status_events_append_only() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'agent_status_events is append-only (%)', TG_OP; END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_ase_append_only BEFORE UPDATE OR DELETE ON agent_status_events
FOR EACH ROW EXECUTE FUNCTION agent_status_events_append_only();

-- At most one OPEN intervention per (agent, source_key) — engine-raised
-- dedupe backstop (invariant I12)
CREATE UNIQUE INDEX uq_open_intervention_per_source ON agent_interventions (
  agent_registration_id, COALESCE(source_key, '')
) WHERE status = 'OPEN';

-- One active budget per (agent, kind) (ADR D12)
CREATE UNIQUE INDEX uq_active_budget_per_kind ON agent_throttle_budgets (
  agent_registration_id, budget_kind
) WHERE active = TRUE;

-- Principal binding is unique when present (gateway resolution correctness,
-- ADR D7)
CREATE UNIQUE INDEX uq_agent_principal ON agent_registrations (mcp_principal_id)
WHERE mcp_principal_id IS NOT NULL;
