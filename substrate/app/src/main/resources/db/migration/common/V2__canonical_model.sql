-- ============================================================================
-- V2__canonical_model.sql
-- Substrate canonical model — squash of homepoc migrations V1–V11 into the
-- post-V8 final shape (port-map §4.3). Tables are created directly with their
-- final names (processes / process_roles / process_system_links — never
-- manual_*), final columns (process_id, affected_process_ids,
-- domains.is_demo, capabilities.sequence_order) and final index names
-- (idx_processes_domain_id etc.).
--
-- Portability contract: this file runs on BOTH H2 (MODE=PostgreSQL,
-- DATABASE_TO_LOWER=TRUE) and Postgres 16. Only ANSI-portable DDL here:
-- UUID, TIMESTAMP WITH TIME ZONE, TEXT, DOUBLE PRECISION, FLOAT, BIGINT,
-- INTEGER, BOOLEAN, VARCHAR. Postgres-only DDL (the branding single-ACTIVE
-- partial unique index) lives in db/migration/postgresql/V2_1.
--
-- JSON-shaped fields are stored as TEXT for H2/Postgres portability
-- (JsonNodeConverter handles serialization — port-map §4.5). A later
-- TEXT→JSONB flip is a vendor-dir migration, not an entity change.
--
-- Column names must match the com.meridian.substrate.common.domain entities
-- EXACTLY — ddl-auto=validate is enforced in every profile and fails boot on
-- drift (port-map Appendix #1).
--
-- Naming deltas vs homepoc's replayed end state (cosmetic only — Hibernate
-- validate does not inspect constraint names; nothing in code references
-- them):
--   * fk_mp_system_links_system → fk_process_system_links_system
--   * fk_mp_roles_member        → fk_process_roles_member
--     (homepoc V8 renamed the process-side FK constraint names but left
--     these two carrying the old manual_process table prefix; a squash has
--     no reason to reproduce that inconsistency.)
-- The V11 sequence_order backfill (UPDATE … ROW_NUMBER()) is absorbed: a
-- fresh schema has no rows to backfill (port-map §4.4).
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. domains (no FK dependencies) — V1 + is_demo (V7)
-- ---------------------------------------------------------------------------
CREATE TABLE domains (
    id                  UUID            NOT NULL,
    name                VARCHAR(255)    NOT NULL,
    description         TEXT,
    taxonomy_config     TEXT,
    regulatory_context  TEXT,
    metadata            TEXT,
    is_demo             BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by          VARCHAR(255)    NOT NULL,
    version             BIGINT          NOT NULL,
    PRIMARY KEY (id)
);

-- ---------------------------------------------------------------------------
-- 2. systems (FK → domains) — V1
-- ---------------------------------------------------------------------------
CREATE TABLE systems (
    id              UUID            NOT NULL,
    domain_id       UUID,
    name            VARCHAR(255)    NOT NULL,
    vendor          VARCHAR(255),
    description     TEXT,
    system_type     VARCHAR(255),
    deployed_year   INTEGER,
    status          VARCHAR(255),
    business_line   VARCHAR(255),
    metadata        TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by      VARCHAR(255)    NOT NULL,
    version         BIGINT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_systems_domain FOREIGN KEY (domain_id) REFERENCES domains(id)
);

CREATE INDEX idx_systems_domain_id ON systems(domain_id);

-- ---------------------------------------------------------------------------
-- 3. system_uploads (FK → systems) — V1
-- ---------------------------------------------------------------------------
CREATE TABLE system_uploads (
    id              UUID            NOT NULL,
    system_id       UUID,
    file_name       VARCHAR(255),
    file_size       BIGINT,
    content_type    VARCHAR(255),
    storage_path    VARCHAR(255),
    metadata        TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by      VARCHAR(255)    NOT NULL,
    version         BIGINT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_system_uploads_system FOREIGN KEY (system_id) REFERENCES systems(id)
);

CREATE INDEX idx_system_uploads_system_id ON system_uploads(system_id);

-- ---------------------------------------------------------------------------
-- 4. organization_members — V1
--    (domain_id is a soft reference by design: no DB FK in homepoc; integrity
--    is application-enforced. Do not "fix" without a decision — port-map §2.1.)
-- ---------------------------------------------------------------------------
CREATE TABLE organization_members (
    id              UUID            NOT NULL,
    domain_id       UUID,
    name            VARCHAR(255)    NOT NULL,
    title           VARCHAR(255),
    department      VARCHAR(255),
    business_line   VARCHAR(255),
    reports_to      VARCHAR(255),
    metadata        TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by      VARCHAR(255)    NOT NULL,
    version         BIGINT          NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_organization_members_domain_id ON organization_members(domain_id);

-- ---------------------------------------------------------------------------
-- 5. processes (FK → domains) — V1 manual_processes, final name per V8
-- ---------------------------------------------------------------------------
CREATE TABLE processes (
    id                      UUID            NOT NULL,
    domain_id               UUID,
    name                    VARCHAR(255)    NOT NULL,
    description             TEXT            NOT NULL,
    classification          VARCHAR(50)     NOT NULL,
    frequency               VARCHAR(255),
    primary_owner           VARCHAR(255),
    business_line           VARCHAR(255),
    defense_line            VARCHAR(50),
    documentation_quality   VARCHAR(50),
    estimated_fte           DOUBLE PRECISION,
    risk_rating             VARCHAR(50),
    supporting_artifacts    TEXT,
    metadata                TEXT,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by              VARCHAR(255)    NOT NULL,
    version                 BIGINT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_processes_domain FOREIGN KEY (domain_id) REFERENCES domains(id)
);

CREATE INDEX idx_processes_domain_id ON processes(domain_id);

-- ---------------------------------------------------------------------------
-- 6. process_steps (FK → processes, systems, organization_members) — V4
--    linked_system_id / assigned_person_id carry FKs; they are nullable soft
--    assignments in the entity.
-- ---------------------------------------------------------------------------
CREATE TABLE process_steps (
    id                      UUID            NOT NULL,
    process_id              UUID            NOT NULL,
    sequence_order          INTEGER         NOT NULL,
    name                    VARCHAR(255)    NOT NULL,
    description             TEXT,
    step_type               VARCHAR(50),
    execution_mode          VARCHAR(50),
    automation_candidate    BOOLEAN         NOT NULL DEFAULT FALSE,
    automation_rationale    TEXT,
    agent_readiness_score   INTEGER,
    linked_system_id        UUID,
    assigned_person_id      UUID,
    estimated_minutes       INTEGER,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by              VARCHAR(255)    NOT NULL,
    version                 BIGINT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_process_steps_process FOREIGN KEY (process_id) REFERENCES processes(id),
    CONSTRAINT fk_process_steps_system FOREIGN KEY (linked_system_id) REFERENCES systems(id),
    CONSTRAINT fk_process_steps_person FOREIGN KEY (assigned_person_id) REFERENCES organization_members(id)
);

CREATE INDEX idx_process_steps_process_id ON process_steps(process_id);
CREATE INDEX idx_process_steps_linked_system_id ON process_steps(linked_system_id);
CREATE INDEX idx_process_steps_assigned_person_id ON process_steps(assigned_person_id);

-- ---------------------------------------------------------------------------
-- 7. process_roles (FK → processes, organization_members) — V1
--    manual_process_roles, final name/columns per V8
-- ---------------------------------------------------------------------------
CREATE TABLE process_roles (
    id                      UUID            NOT NULL,
    process_id              UUID            NOT NULL,
    member_id               UUID            NOT NULL,
    participation           VARCHAR(50)     NOT NULL,
    time_commitment         VARCHAR(255),
    automation_candidate    BOOLEAN,
    automation_rationale    TEXT,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by              VARCHAR(255)    NOT NULL,
    version                 BIGINT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_process_roles_process FOREIGN KEY (process_id) REFERENCES processes(id),
    CONSTRAINT fk_process_roles_member FOREIGN KEY (member_id) REFERENCES organization_members(id)
);

CREATE INDEX idx_process_roles_process_id ON process_roles(process_id);
CREATE INDEX idx_process_roles_member_id ON process_roles(member_id);

-- ---------------------------------------------------------------------------
-- 8. process_system_links (FK → processes, systems) — V1
--    manual_process_system_links, final name/columns per V8
-- ---------------------------------------------------------------------------
CREATE TABLE process_system_links (
    id                  UUID            NOT NULL,
    process_id          UUID            NOT NULL,
    system_id           UUID            NOT NULL,
    link_type           VARCHAR(50)     NOT NULL,
    data_elements       TEXT,
    frequency           VARCHAR(255),
    status              VARCHAR(50),
    ai_inferred         BOOLEAN,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by          VARCHAR(255)    NOT NULL,
    version             BIGINT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_process_system_links_process FOREIGN KEY (process_id) REFERENCES processes(id),
    CONSTRAINT fk_process_system_links_system FOREIGN KEY (system_id) REFERENCES systems(id)
);

CREATE INDEX idx_process_system_links_process_id ON process_system_links(process_id);
CREATE INDEX idx_process_system_links_system_id ON process_system_links(system_id);

-- ---------------------------------------------------------------------------
-- 9. canonical_entities (FK → systems, processes) — V1, process_id per V8
-- ---------------------------------------------------------------------------
CREATE TABLE canonical_entities (
    id                  UUID            NOT NULL,
    domain_id           UUID,
    system_id           UUID,
    name                VARCHAR(255)    NOT NULL,
    entity_type         VARCHAR(255)    NOT NULL,
    description         TEXT,
    attributes          TEXT,
    execution_mode      VARCHAR(50),
    process_id          UUID,
    metadata            TEXT,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by          VARCHAR(255)    NOT NULL,
    version             BIGINT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_canonical_entities_system FOREIGN KEY (system_id) REFERENCES systems(id),
    CONSTRAINT fk_canonical_entities_process FOREIGN KEY (process_id) REFERENCES processes(id)
);

CREATE INDEX idx_canonical_entities_domain_id ON canonical_entities(domain_id);
CREATE INDEX idx_canonical_entities_system_id ON canonical_entities(system_id);
CREATE INDEX idx_canonical_entities_process_id ON canonical_entities(process_id);

-- ---------------------------------------------------------------------------
-- 10. entity_overlaps (FK → canonical_entities x2) — V1
-- ---------------------------------------------------------------------------
CREATE TABLE entity_overlaps (
    id                  UUID            NOT NULL,
    domain_id           UUID,
    source_entity_id    UUID,
    target_entity_id    UUID,
    overlap_type        VARCHAR(255),
    confidence          DOUBLE PRECISION,
    details             TEXT,
    ai_inferred         BOOLEAN,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by          VARCHAR(255)    NOT NULL,
    version             BIGINT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_entity_overlaps_source FOREIGN KEY (source_entity_id) REFERENCES canonical_entities(id),
    CONSTRAINT fk_entity_overlaps_target FOREIGN KEY (target_entity_id) REFERENCES canonical_entities(id)
);

CREATE INDEX idx_entity_overlaps_domain_id ON entity_overlaps(domain_id);
CREATE INDEX idx_entity_overlaps_source_entity_id ON entity_overlaps(source_entity_id);
CREATE INDEX idx_entity_overlaps_target_entity_id ON entity_overlaps(target_entity_id);

-- ---------------------------------------------------------------------------
-- 11. integrations (FK → systems x2) — V1
-- ---------------------------------------------------------------------------
CREATE TABLE integrations (
    id                  UUID            NOT NULL,
    domain_id           UUID,
    source_system_id    UUID,
    target_system_id    UUID,
    name                VARCHAR(255),
    integration_type    VARCHAR(50),
    integration_status  VARCHAR(50),
    data_elements       TEXT,
    frequency           VARCHAR(255),
    description         TEXT,
    metadata            TEXT,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by          VARCHAR(255)    NOT NULL,
    version             BIGINT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_integrations_source FOREIGN KEY (source_system_id) REFERENCES systems(id),
    CONSTRAINT fk_integrations_target FOREIGN KEY (target_system_id) REFERENCES systems(id)
);

CREATE INDEX idx_integrations_domain_id ON integrations(domain_id);
CREATE INDEX idx_integrations_source_system_id ON integrations(source_system_id);
CREATE INDEX idx_integrations_target_system_id ON integrations(target_system_id);

-- ---------------------------------------------------------------------------
-- 12. role_overlaps (FK → organization_members x2) — V1
-- ---------------------------------------------------------------------------
CREATE TABLE role_overlaps (
    id                  UUID            NOT NULL,
    domain_id           UUID,
    source_member_id    UUID,
    target_member_id    UUID,
    overlap_type        VARCHAR(255),
    details             TEXT,
    ai_inferred         BOOLEAN,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by          VARCHAR(255)    NOT NULL,
    version             BIGINT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_role_overlaps_source FOREIGN KEY (source_member_id) REFERENCES organization_members(id),
    CONSTRAINT fk_role_overlaps_target FOREIGN KEY (target_member_id) REFERENCES organization_members(id)
);

CREATE INDEX idx_role_overlaps_domain_id ON role_overlaps(domain_id);
CREATE INDEX idx_role_overlaps_source_member_id ON role_overlaps(source_member_id);
CREATE INDEX idx_role_overlaps_target_member_id ON role_overlaps(target_member_id);

-- ---------------------------------------------------------------------------
-- 13. synthesis_runs — V1 (no FK)
-- ---------------------------------------------------------------------------
CREATE TABLE synthesis_runs (
    id                      UUID            NOT NULL,
    domain_id               UUID,
    run_type                VARCHAR(255),
    status                  VARCHAR(255),
    started_at              TIMESTAMP WITH TIME ZONE,
    completed_at            TIMESTAMP WITH TIME ZONE,
    findings_generated      INTEGER,
    model                   VARCHAR(255),
    total_input_tokens      INTEGER,
    total_output_tokens     INTEGER,
    total_cost_estimate     DOUBLE PRECISION,
    metadata                TEXT,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by              VARCHAR(255)    NOT NULL,
    version                 BIGINT          NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_synthesis_runs_domain_id ON synthesis_runs(domain_id);

-- ---------------------------------------------------------------------------
-- 14. findings — V1, affected_process_ids per V8
--     (synthesis_run_id + affected_*_ids are soft references — no FK by
--     design; JSON arrays of UUIDs as TEXT.)
-- ---------------------------------------------------------------------------
CREATE TABLE findings (
    id                      UUID            NOT NULL,
    domain_id               UUID,
    finding_type            VARCHAR(50)     NOT NULL,
    severity                VARCHAR(50),
    status                  VARCHAR(50),
    title                   VARCHAR(255)    NOT NULL,
    description             TEXT            NOT NULL,
    affected_system_ids     TEXT,
    affected_process_ids    TEXT,
    evidence                TEXT,
    recommendation          TEXT,
    ai_generated            BOOLEAN,
    synthesis_run_id        UUID,
    metadata                TEXT,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by              VARCHAR(255)    NOT NULL,
    version                 BIGINT          NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_findings_domain_id ON findings(domain_id);
CREATE INDEX idx_findings_synthesis_run_id ON findings(synthesis_run_id);

-- ---------------------------------------------------------------------------
-- 15. design_decisions — V1, affected_process_ids per V8
--     (previous_hash/current_hash: append-only SHA-256 chain, engine.decision)
-- ---------------------------------------------------------------------------
CREATE TABLE design_decisions (
    id                      UUID            NOT NULL,
    domain_id               UUID,
    decision_type           VARCHAR(50)     NOT NULL,
    status                  VARCHAR(50)     NOT NULL,
    title                   VARCHAR(255)    NOT NULL,
    description             TEXT            NOT NULL,
    rationale               TEXT,
    impact_summary          TEXT,
    affected_system_ids     TEXT,
    affected_process_ids    TEXT,
    previous_hash           VARCHAR(255),
    current_hash            VARCHAR(255),
    metadata                TEXT,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by              VARCHAR(255)    NOT NULL,
    version                 BIGINT          NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_design_decisions_domain_id ON design_decisions(domain_id);

-- ---------------------------------------------------------------------------
-- 16. chat_interactions — V1
-- ---------------------------------------------------------------------------
CREATE TABLE chat_interactions (
    id                          UUID            NOT NULL,
    domain_id                   UUID,
    persona_id                  VARCHAR(255),
    user_message                TEXT,
    assistant_message           TEXT,
    tool_calls                  TEXT,
    model                       VARCHAR(255),
    prompt_template_id          VARCHAR(255),
    prompt_template_version     VARCHAR(255),
    input_tokens                INTEGER,
    output_tokens               INTEGER,
    latency_ms                  BIGINT,
    refusal_detected            BOOLEAN,
    cost_estimate               DOUBLE PRECISION,
    metadata                    TEXT,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by                  VARCHAR(255)    NOT NULL,
    version                     BIGINT          NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_chat_interactions_domain_id ON chat_interactions(domain_id);

-- ---------------------------------------------------------------------------
-- 17. document_registry — V1
--     (tachyon_* columns kept for now; rename decision deferred until the
--     real document index lands — port-map §2.1.)
-- ---------------------------------------------------------------------------
CREATE TABLE document_registry (
    id                              UUID            NOT NULL,
    source_file                     VARCHAR(255)    NOT NULL,
    source_file_sha256              VARCHAR(255)    NOT NULL,
    source_file_size                BIGINT,
    document_type                   VARCHAR(50),
    source_system                   VARCHAR(255),
    ingested_to_tachyon             BOOLEAN         NOT NULL DEFAULT FALSE,
    tachyon_file_id                 VARCHAR(255),
    tachyon_collection              VARCHAR(255),
    ingestion_status                VARCHAR(50)     NOT NULL DEFAULT 'LOCAL_ONLY',
    ingested_at                     TIMESTAMP WITH TIME ZONE,
    extracted_text_bytes            BIGINT,
    processed_by_converter_run_id   UUID,
    metadata                        TEXT,
    created_at                      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by                      VARCHAR(255)    NOT NULL,
    version                         BIGINT          NOT NULL,
    PRIMARY KEY (id)
);

-- ---------------------------------------------------------------------------
-- 18. evidence — V2
--     (entity_id + entity_type form a polymorphic soft reference — no FK by
--     design.)
-- ---------------------------------------------------------------------------
CREATE TABLE evidence (
    id                      UUID            NOT NULL,
    entity_id               UUID            NOT NULL,
    entity_type             VARCHAR(100)    NOT NULL,
    domain_id               UUID,
    document_registry_id    UUID,
    source_file             VARCHAR(255),
    confidence              FLOAT,
    label                   VARCHAR(500),
    extractor               VARCHAR(100),
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by              VARCHAR(255)    NOT NULL,
    version                 BIGINT          NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_evidence_entity_id ON evidence(entity_id);
CREATE INDEX idx_evidence_domain_id ON evidence(domain_id);

-- ---------------------------------------------------------------------------
-- 19. assertions — V9
--     Triple-shaped provenance substrate. All five produced_by values
--     (AI_GENERATIVE, AI_ASSISTIVE, AI_VALIDATIVE, HUMAN, AGENT) are valid
--     from day one. evidence_ids is a soft-reference JSON array.
-- ---------------------------------------------------------------------------
CREATE TABLE assertions (
    id                      UUID            NOT NULL,
    domain_id               UUID            NOT NULL,
    subject                 VARCHAR(500)    NOT NULL,
    predicate               VARCHAR(255)    NOT NULL,
    object                  TEXT            NOT NULL,
    confidence              FLOAT,
    evidence_ids            TEXT,
    produced_by             VARCHAR(50)     NOT NULL,
    produced_by_context     TEXT,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by              VARCHAR(255)    NOT NULL,
    version                 BIGINT          NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_assertions_domain_id ON assertions(domain_id);
CREATE INDEX idx_assertions_produced_by ON assertions(produced_by);
CREATE INDEX idx_assertions_subject ON assertions(subject);

-- ---------------------------------------------------------------------------
-- 20. capabilities (FK → domains; self-referential tree) — V3 +
--     sequence_order (V11; default 0, no backfill needed on a fresh schema)
-- ---------------------------------------------------------------------------
CREATE TABLE capabilities (
    id                      UUID            NOT NULL,
    domain_id               UUID            NOT NULL,
    parent_capability_id    UUID,
    name                    VARCHAR(255)    NOT NULL,
    display_name            VARCHAR(255)    NOT NULL,
    description             TEXT,
    level                   INTEGER         NOT NULL,
    status                  VARCHAR(50)     NOT NULL,
    source_pack_id          UUID,
    ai_generated            BOOLEAN         NOT NULL DEFAULT FALSE,
    metadata                TEXT,
    sequence_order          INTEGER         NOT NULL DEFAULT 0,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by              VARCHAR(255)    NOT NULL,
    version                 BIGINT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_capabilities_domain FOREIGN KEY (domain_id) REFERENCES domains(id),
    CONSTRAINT fk_capabilities_parent FOREIGN KEY (parent_capability_id) REFERENCES capabilities(id)
);

CREATE INDEX idx_capabilities_domain_id ON capabilities(domain_id);
CREATE INDEX idx_capabilities_parent_capability_id ON capabilities(parent_capability_id);
CREATE INDEX idx_capabilities_parent_sequence ON capabilities(parent_capability_id, sequence_order);

-- ---------------------------------------------------------------------------
-- 21. capability_name_history (FK → capabilities) — V3
-- ---------------------------------------------------------------------------
CREATE TABLE capability_name_history (
    id                      UUID            NOT NULL,
    capability_id           UUID            NOT NULL,
    from_display_name       VARCHAR(255)    NOT NULL,
    to_display_name         VARCHAR(255)    NOT NULL,
    rationale               TEXT,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by              VARCHAR(255)    NOT NULL,
    version                 BIGINT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_capability_name_history_capability FOREIGN KEY (capability_id) REFERENCES capabilities(id)
);

CREATE INDEX idx_capability_name_history_capability_id ON capability_name_history(capability_id);

-- ---------------------------------------------------------------------------
-- 22. capability_process_links (FK → capabilities, processes) — V3,
--     process_id per V8 (constraint/index names carried forward from V3 as
--     homepoc's final shape kept them)
-- ---------------------------------------------------------------------------
CREATE TABLE capability_process_links (
    id                      UUID            NOT NULL,
    capability_id           UUID            NOT NULL,
    process_id              UUID            NOT NULL,
    link_type               VARCHAR(50),
    ai_inferred             BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by              VARCHAR(255)    NOT NULL,
    version                 BIGINT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_cap_proc_links_capability FOREIGN KEY (capability_id) REFERENCES capabilities(id),
    CONSTRAINT fk_cap_proc_links_process FOREIGN KEY (process_id) REFERENCES processes(id)
);

CREATE INDEX idx_cap_proc_links_capability_id ON capability_process_links(capability_id);
CREATE INDEX idx_cap_proc_links_process_id ON capability_process_links(process_id);

-- ---------------------------------------------------------------------------
-- 23. capability_system_links (FK → capabilities, systems) — V3
-- ---------------------------------------------------------------------------
CREATE TABLE capability_system_links (
    id                      UUID            NOT NULL,
    capability_id           UUID            NOT NULL,
    system_id               UUID            NOT NULL,
    link_type               VARCHAR(50),
    ai_inferred             BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by              VARCHAR(255)    NOT NULL,
    version                 BIGINT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_cap_sys_links_capability FOREIGN KEY (capability_id) REFERENCES capabilities(id),
    CONSTRAINT fk_cap_sys_links_system FOREIGN KEY (system_id) REFERENCES systems(id)
);

CREATE INDEX idx_cap_sys_links_capability_id ON capability_system_links(capability_id);
CREATE INDEX idx_cap_sys_links_system_id ON capability_system_links(system_id);

-- ---------------------------------------------------------------------------
-- 24. phase_states (FK → domains; unique (domain_id, phase)) — V5
-- ---------------------------------------------------------------------------
CREATE TABLE phase_states (
    id              UUID            NOT NULL,
    domain_id       UUID            NOT NULL,
    phase           VARCHAR(50)     NOT NULL,
    status          VARCHAR(50)     NOT NULL,
    entered_at      TIMESTAMP WITH TIME ZONE,
    blockers        TEXT,
    checkpoints     TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by      VARCHAR(255)    NOT NULL,
    version         BIGINT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_phase_states_domain FOREIGN KEY (domain_id) REFERENCES domains(id),
    CONSTRAINT uk_phase_states_domain_phase UNIQUE (domain_id, phase)
);

CREATE INDEX idx_phase_states_domain_id ON phase_states(domain_id);

-- ---------------------------------------------------------------------------
-- 25. reader_registrations (FK → domains) — V6
-- ---------------------------------------------------------------------------
CREATE TABLE reader_registrations (
    id                      UUID            NOT NULL,
    domain_id               UUID            NOT NULL,
    kind                    VARCHAR(50)     NOT NULL,
    name                    VARCHAR(255)    NOT NULL,
    uri                     VARCHAR(2048),
    auth_context            TEXT,
    registered_at           TIMESTAMP WITH TIME ZONE,
    last_read_at            TIMESTAMP WITH TIME ZONE,
    last_fingerprint_hash   VARCHAR(255),
    status                  VARCHAR(50)     NOT NULL,
    coverage_score          DOUBLE PRECISION,
    metadata                TEXT,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by              VARCHAR(255)    NOT NULL,
    version                 BIGINT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_reader_registrations_domain FOREIGN KEY (domain_id) REFERENCES domains(id)
);

CREATE INDEX idx_reader_registrations_domain_id ON reader_registrations(domain_id);
CREATE INDEX idx_reader_registrations_kind ON reader_registrations(kind);

-- ---------------------------------------------------------------------------
-- 26. branding_configs (FK → domains) — V10
--     One-ACTIVE-per-domain invariant: service-enforced everywhere
--     (BrandingConfigService pessimistic demote-then-promote); additionally
--     DB-enforced on Postgres via the partial unique index in
--     db/migration/postgresql/V2_1 — H2 rejects partial indexes, so on the
--     dev profile the invariant stays service-only (acceptable: dev only,
--     port-map §4.3).
-- ---------------------------------------------------------------------------
CREATE TABLE branding_configs (
    id                      UUID            NOT NULL,
    domain_id               UUID            NOT NULL,

    -- Identity
    app_display_name        VARCHAR(255)    NOT NULL,
    tagline                 VARCHAR(512),

    -- Color tokens (JSON)
    color_tokens_light      TEXT            NOT NULL,
    color_tokens_dark       TEXT            NOT NULL,

    -- Typography
    font_body               VARCHAR(255)    NOT NULL,
    font_heading            VARCHAR(255)    NOT NULL,
    font_mono               VARCHAR(255)    NOT NULL,

    -- Spacing / radius / shadow (optional overrides)
    spacing_tokens          TEXT,
    radius_tokens           TEXT,
    shadow_tokens           TEXT,

    -- Asset blob keys
    logo_primary_blob_key   VARCHAR(255),
    logo_mark_blob_key      VARCHAR(255),
    logo_light_blob_key     VARCHAR(255),
    logo_dark_blob_key      VARCHAR(255),
    favicon_blob_key        VARCHAR(255),
    login_splash_blob_key   VARCHAR(255),

    -- Terminology overrides (JSON map)
    terminology_overrides   TEXT            NOT NULL,

    -- Email branding (defined ahead of consumption — P15 commitment)
    email_sender_address    VARCHAR(255),
    email_sender_name       VARCHAR(255),
    email_reply_to          VARCHAR(255),
    email_header_html       TEXT,
    email_footer_html       TEXT,
    email_primary_color     VARCHAR(32),

    -- Legal
    legal_footer_text       TEXT,
    privacy_policy_url      VARCHAR(512),
    terms_of_service_url    VARCHAR(512),

    -- Advanced
    custom_css_url          VARCHAR(512),

    -- Lifecycle
    status                  VARCHAR(32)     NOT NULL,

    -- Audit (matches BaseEntity)
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by              VARCHAR(255)    NOT NULL,
    version                 BIGINT          NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT fk_branding_configs_domain FOREIGN KEY (domain_id) REFERENCES domains(id)
);

CREATE INDEX idx_branding_configs_domain_id ON branding_configs(domain_id);
CREATE INDEX idx_branding_configs_status    ON branding_configs(status);
