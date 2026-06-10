-- ============================================================================
-- V3__ingest_runs.sql
-- Ingestion run audit table (port-map §2.4 / §7 chunk C6).
--
-- One row per ingestion CLI run (convert or commit).
-- document_registry.processed_by_converter_run_id references ingest_runs.id
-- as a soft reference (no DB FK — consistent with the canonical model's
-- application-enforced soft-reference convention, port-map §2.1).
--
-- Portability contract: ANSI-portable DDL only — runs on both H2
-- (MODE=PostgreSQL) and Postgres 16. JSON-shaped fields are TEXT
-- (JsonNodeConverter — port-map §4.5).
--
-- Column names must match com.meridian.substrate.common.domain.IngestRun
-- EXACTLY — ddl-auto=validate is enforced in every profile.
-- ============================================================================

CREATE TABLE ingest_runs (
    id                  UUID            NOT NULL,
    domain_id           UUID,
    command             VARCHAR(50),
    source_dir          VARCHAR(1024),
    status              VARCHAR(50)     NOT NULL,
    started_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at         TIMESTAMP WITH TIME ZONE,
    files_processed     INTEGER,
    files_skipped       INTEGER,
    entities_committed  INTEGER,
    entities_updated    INTEGER,
    error_count         INTEGER,
    entity_counts       TEXT,
    errors              TEXT,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by          VARCHAR(255)    NOT NULL,
    version             BIGINT          NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_ingest_runs_domain_id ON ingest_runs(domain_id);
CREATE INDEX idx_ingest_runs_started_at ON ingest_runs(started_at);
