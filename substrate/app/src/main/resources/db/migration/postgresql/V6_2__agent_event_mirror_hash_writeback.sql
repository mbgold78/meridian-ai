-- ============================================================================
-- V6_2__agent_event_mirror_hash_writeback.sql  (postgresql vendor dir ONLY)
-- Phase 6b chain mirror (engine-spec agents.md §11; ADR-003 D14): the mirrored
-- hash is "written back to agent_status_events.decision_log_hash
-- (cross-navigation)" — the ONE sanctioned mutation of an audit row. The V6_1
-- append-only trigger function is amended to permit EXACTLY that transition:
-- decision_log_hash NULL -> NOT NULL with every audit column byte-identical
-- (updated_at/version move with the JPA dirty update and are base-column
-- bookkeeping, not audit content). Any other UPDATE, and every DELETE, still
-- raises (spec non-negotiable #3 / invariant I4).
-- ============================================================================

CREATE OR REPLACE FUNCTION agent_status_events_append_only() RETURNS trigger AS $$
BEGIN
  IF TG_OP = 'UPDATE'
     AND OLD.decision_log_hash IS NULL
     AND NEW.decision_log_hash IS NOT NULL
     AND (to_jsonb(OLD) - 'decision_log_hash' - 'updated_at' - 'version')
       = (to_jsonb(NEW) - 'decision_log_hash' - 'updated_at' - 'version') THEN
    RETURN NEW;
  END IF;
  RAISE EXCEPTION 'agent_status_events is append-only (%)', TG_OP;
END;
$$ LANGUAGE plpgsql;
