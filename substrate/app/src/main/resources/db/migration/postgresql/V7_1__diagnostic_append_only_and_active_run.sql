-- ============================================================================
-- V7_1__diagnostic_append_only_and_active_run.sql  (Postgres-only vendor migration)
--
-- DB-level backstops for the diagnostic engine (engine-spec diagnostic.md
-- §3.2; ADR-004 D4/D11):
--
--   1. Append-only enforcement on ai_curation_events (V4_1 precedent): the
--      curation audit IS the accountability record — rows are never updated
--      or deleted. The repository surface exposes no mutators
--      (AiCurationEventRepository extends Repository, not JpaRepository);
--      this trigger raises on any UPDATE/DELETE that slips past service
--      discipline. H2 dev lacks the trigger (plpgsql) — service-enforced
--      only; Testcontainers-Postgres CI is the gate. H2-green is never
--      acceptance.
--
--   2. One active diagnostic run per domain — DB backstop for the
--      domain-scoped service lock (409 DIAGNOSTIC_RUN_ACTIVE). Partial
--      unique index over PLANNED/RUNNING diagnostic synthesis_runs; H2
--      rejects partial indexes (precedent V2_1, port-map §4.3).
-- ============================================================================

-- append-only enforcement on the curation audit (V4_1 precedent; H2 dev = service-enforced only)
CREATE OR REPLACE FUNCTION raise_ai_curation_events_immutable() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'ai_curation_events is append-only'; END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_ai_curation_events_append_only
  BEFORE UPDATE OR DELETE ON ai_curation_events
  FOR EACH ROW EXECUTE FUNCTION raise_ai_curation_events_immutable();

-- one active diagnostic run per domain — backstop for the service lock (ADR D11)
CREATE UNIQUE INDEX uq_active_diagnostic_run_per_domain
  ON synthesis_runs(domain_id)
  WHERE run_type = 'diagnostic' AND status IN ('PLANNED','RUNNING');
