-- ============================================================================
-- V5_1__contracts_partial_indexes.sql  (postgresql vendor dir — NEVER on H2)
-- Open-breach dedup backstop (engine-spec contracts.md §3.3; ADR-002 D13):
-- at most one OPEN breach per (contract, monitor, commitment, period).
-- The service check under row lock (§10.6) is primary; this catches races.
-- H2 dev cannot express partial indexes — service-enforced there, same
-- precedent as branding V2_1 (H2-green is never acceptance).
-- ============================================================================

CREATE UNIQUE INDEX uq_open_breach_per_source ON contract_breaches (
  contract_id,
  COALESCE(monitor_id,    '00000000-0000-0000-0000-000000000000'::uuid),
  COALESCE(commitment_id, '00000000-0000-0000-0000-000000000000'::uuid),
  COALESCE(period_key, '')
) WHERE status IN ('DETECTED','SURFACED','ACKNOWLEDGED');
