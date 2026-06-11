-- ============================================================================
-- V7__diagnostic_commit.sql  (common — ANSI-portable; H2 PG-mode + Postgres)
--
-- Diagnostic engine persistence (engine-spec diagnostic.md §3.1; ADR-004
-- D2/D4): the commit-step + curation projection columns on findings, the
-- unified append-only ai_curation_events audit (findings AND generative
-- drafts), and the diagnostic_run_batches resumability ledger.
--
-- No new run table — synthesis_runs (existing, with token/cost columns) IS
-- the run record (severs S12 for this path). No draft table — the
-- AI_GENERATIVE Assertion row is the durable draft store.
--
-- Soft refs (assertion_id, subject_id, created_entity_id, synthesis_run_id)
-- carry NO DB FKs — application-enforced per the canonical-model convention.
-- The Postgres-only append-only trigger + one-active-run partial unique
-- index live in db/migration/postgresql/V7_1 (V4_1 precedent; H2 dev is
-- service-enforced only — H2-green is never acceptance).
-- ============================================================================

-- findings: commit-step + curation projection columns (additive; entities ARE the wire format)
ALTER TABLE findings ADD COLUMN assertion_id UUID;             -- soft ref -> assertions (first hop of the audit walk)
ALTER TABLE findings ADD COLUMN curation_status VARCHAR(30);   -- NULL = not subject to curation (operator/fixture rows)
ALTER TABLE findings ADD COLUMN curated_at TIMESTAMP WITH TIME ZONE;  -- projection of last curation event
ALTER TABLE findings ADD COLUMN curated_by_label VARCHAR(255);        -- denormalized actor label (cascade D11 idiom)
CREATE INDEX idx_findings_domain_curation ON findings(domain_id, curation_status);

-- unified append-only curation audit (findings AND generative drafts)
CREATE TABLE ai_curation_events (
    id UUID NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE,
    created_by VARCHAR(255),
    domain_id UUID NOT NULL,
    subject_kind VARCHAR(20) NOT NULL,        -- 'FINDING' | 'DRAFT'
    subject_id UUID NOT NULL,                 -- finding.id | assertion.id (soft refs)
    action VARCHAR(20) NOT NULL,              -- 'ACCEPT' | 'REJECT' | 'SUPERSEDE'
    rationale TEXT,                           -- service-enforced NOT NULL for REJECT
    overridden_fields TEXT,                   -- JSON delta when ACCEPT carried human edits (DRAFT path)
    created_entity_id UUID,                   -- DRAFT ACCEPT -> materialized capability id
    synthesis_run_id UUID,                    -- SUPERSEDE -> superseding run
    actor_label VARCHAR(255) NOT NULL,        -- PersonaContext now; OIDC principal Phase 5 (S9)
    actor_persona_id VARCHAR(255),
    idempotency_key VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_curation_idem UNIQUE (subject_kind, subject_id, idempotency_key)
);
CREATE INDEX idx_curation_subject ON ai_curation_events(subject_kind, subject_id);
CREATE INDEX idx_curation_domain ON ai_curation_events(domain_id);

-- resumability ledger: the batch is the atomicity unit (ADR D2)
CREATE TABLE diagnostic_run_batches (
    id UUID NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE,
    created_by VARCHAR(255),
    synthesis_run_id UUID NOT NULL,           -- soft ref -> synthesis_runs
    batch_no INT NOT NULL,
    scope VARCHAR(50) NOT NULL,               -- systems | processes | canonical-entities | domain
    target_entity_ids TEXT,                   -- JSON array; NULL for the domain-wide batch
    status VARCHAR(20) NOT NULL,              -- PENDING | RUNNING | COMPLETED | FAILED | CANCELLED | SKIPPED_BUDGET
    attempt INT NOT NULL DEFAULT 0,
    executor VARCHAR(20),                     -- in-process | orchestrator | in-process-fallback
    candidates_returned INT,
    candidates_filtered INT,
    findings_committed INT,
    filter_histogram TEXT,                    -- JSON: {badType, badSeverity, missingField, ungrounded}
    error TEXT,
    dispatch_task_id VARCHAR(100),            -- orchestrated lane only
    claimed_at TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id),
    CONSTRAINT uq_run_batch UNIQUE (synthesis_run_id, batch_no)
);
CREATE INDEX idx_diag_batches_run ON diagnostic_run_batches(synthesis_run_id);
