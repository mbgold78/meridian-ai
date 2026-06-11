-- ============================================================================
-- V4_1__cascade_actions_append_only_trigger.sql  (Postgres-only vendor migration)
--
-- DB-level backstop for the append-only invariant on cascade_level_actions
-- (engine-spec cascade.md §3.3 invariant I8; ADR-001 D9): the action log IS
-- the audit trail — rows are never updated or deleted. The repository surface
-- exposes no mutators (CascadeLevelActionRepository extends Repository, not
-- JpaRepository) and services never mutate loaded rows; this trigger raises
-- on any UPDATE/DELETE that slips past service discipline.
--
-- H2 dev lacks the trigger (plpgsql; precedent: V2_1 partial index, port-map
-- §4.3) — the dev profile stays service-enforced and Testcontainers-Postgres
-- CI is the gate. H2-green is never acceptance.
--
-- Lives in db/migration/postgresql/ per the vendor-split layout (§4.2):
-- dot-versions (Vn_m) are reserved for vendor dirs so they slot between
-- common migrations without collisions.
-- ============================================================================

CREATE OR REPLACE FUNCTION cascade_actions_append_only() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'cascade_level_actions is append-only (%)', TG_OP; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_cla_append_only BEFORE UPDATE OR DELETE ON cascade_level_actions
FOR EACH ROW EXECUTE FUNCTION cascade_actions_append_only();
