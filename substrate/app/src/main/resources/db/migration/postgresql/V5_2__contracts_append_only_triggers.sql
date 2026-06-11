-- ============================================================================
-- V5_2__contracts_append_only_triggers.sql  (postgresql vendor dir — NEVER on H2)
--
-- DB-level backstop for the contract engine's APPEND-ONLY audit tables
-- (engine-spec contracts.md §2.2/§10.9; V5__contracts.sql declares
-- contract_events / contract_monitor_runs / contract_party_responses /
-- contract_enforcement_decisions append-only). Backfills the V4_1 trigger
-- precedent the cascade (V4_1), agent (V6_1) and diagnostic (V7_1) engines
-- already carry: the repository surfaces expose no mutators (plain
-- Repository, pinned by the GovernedArchitectureTest ArchUnit rules) and
-- services never mutate loaded rows — but ArchUnit is structurally blind to
-- a JPA dirty-checking flush of a loaded entity and to raw-SQL/ops tamper,
-- which is exactly what these triggers catch. Cross-engine symmetry: an
-- UPDATE/DELETE that raises on cascade_level_actions / agent_status_events /
-- ai_curation_events must not succeed silently on the contract audit trail.
--
-- contract_attestations and contract_breaches are deliberately EXCLUDED —
-- they are mutable projections by design (§5.5 latest-wins upsert; §2.4
-- monotonic status machine).
--
-- H2 dev lacks the triggers (plpgsql) — service-enforced only;
-- Testcontainers-Postgres CI is the gate. H2-green is never acceptance.
-- ============================================================================

CREATE OR REPLACE FUNCTION contract_audit_append_only() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION '% is append-only (%)', TG_TABLE_NAME, TG_OP; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_ce_append_only BEFORE UPDATE OR DELETE ON contract_events
FOR EACH ROW EXECUTE FUNCTION contract_audit_append_only();

CREATE TRIGGER trg_ced_append_only BEFORE UPDATE OR DELETE ON contract_enforcement_decisions
FOR EACH ROW EXECUTE FUNCTION contract_audit_append_only();

CREATE TRIGGER trg_cmr_append_only BEFORE UPDATE OR DELETE ON contract_monitor_runs
FOR EACH ROW EXECUTE FUNCTION contract_audit_append_only();

CREATE TRIGGER trg_cpr_append_only BEFORE UPDATE OR DELETE ON contract_party_responses
FOR EACH ROW EXECUTE FUNCTION contract_audit_append_only();
