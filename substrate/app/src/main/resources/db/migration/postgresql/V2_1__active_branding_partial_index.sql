-- ============================================================================
-- V2_1__active_branding_partial_index.sql  (Postgres-only vendor migration)
--
-- DB-level safety net for the "one ACTIVE branding config per domain"
-- invariant. DRAFT rows may coexist per domain; only ACTIVE is unique.
--
-- Homepoc V10 deliberately omitted this because H2 rejects the WHERE clause
-- on CREATE UNIQUE INDEX (partial indexes are not supported even in
-- PostgreSQL compatibility mode). On the H2 dev profile the invariant stays
-- service-enforced by BrandingConfigService's pessimistic demote-then-promote
-- (acceptable: dev only — port-map §4.3).
--
-- Lives in db/migration/postgresql/ per the vendor-split layout (§4.2):
-- dot-versions (Vn_m) are reserved for vendor dirs so they slot between
-- common migrations without collisions.
-- ============================================================================

CREATE UNIQUE INDEX uq_branding_configs_active_per_domain
    ON branding_configs (domain_id)
    WHERE status = 'ACTIVE';
