-- ============================================================================
-- V5__contracts.sql
-- Coordination-contract engine persistence (engine-spec contracts.md §3.2;
-- ADR-002-contracts-engine D7/D8/D9/D10/D13/D14/D17).
--
-- Fourteen tables + the ShedLock lease table:
--   contracts                        aggregate root (slug contract_key per domain, ADR D8)
--   contract_parties                 2..N parties, ordered
--   contract_party_responses         immutable once written (UNIQUE(party_id))
--   contract_commitments             denormalized compliance_state; history in events
--   contract_decline_triggers        pre-agreed legitimate refusal grounds; derived arming
--   contract_entity_links            navigation back-refs (slug fallback pre-D5)
--   contract_clause_guards           PDP hot path — FIRST-CLASS COLUMNS (ADR D9)
--   contract_monitors                DB-recoverable scheduling (next_run_at)
--   contract_monitor_runs            APPEND-ONLY run log with bounded trace
--   contract_attestations            idempotent per (commitment_id, period_key) (ADR D13)
--   contract_metric_samples          push-based v1 metric feed (ADR D11)
--   contract_breaches                monotonic forward-only lifecycle (§2.4)
--   contract_events                  APPEND-ONLY audit timeline (contract analog of CASCADE-DURABILITY)
--   contract_enforcement_decisions   high-volume PDP log, separate retention profile (ADR D17)
--   shedlock                         monitor-sweep lease (IF NOT EXISTS — order-independent vs V4 owner)
--
-- Portability contract: ANSI-portable DDL only — runs on BOTH H2
-- (MODE=PostgreSQL) and Postgres 16. JSON-shaped fields are TEXT
-- (JsonNodeConverter — port-map §4.5; do NOT pre-flip to jsonb; the named
-- flip condition is the first guard/rule-params indexing need).
--
-- The Postgres-only open-breach partial unique index lives in
-- db/migration/postgresql/V5_1 (ADR D13; precedent V2_1 — H2 dev stays
-- service-enforced; H2-green is never acceptance).
--
-- Soft references by design (NO DB FKs; application-enforced, canonical-model
-- convention port-map §2.1): domain_id, bound_entity_ids, evidence_refs,
-- contract_ids/clause_ids in decisions, ref_id on events, and all
-- contract/party/commitment/monitor cross-references.
--
-- Column names must match the com.meridian.substrate.common.domain entities
-- EXACTLY — ddl-auto=validate is enforced in every profile and fails boot on
-- drift.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. contracts — aggregate root
-- ---------------------------------------------------------------------------
CREATE TABLE contracts (
    id                      UUID PRIMARY KEY,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE,
    created_by              VARCHAR(255)    NOT NULL DEFAULT 'system',
    version                 BIGINT          NOT NULL DEFAULT 0,
    domain_id               UUID            NOT NULL,
    contract_key            VARCHAR(160)    NOT NULL,   -- stable slug; external/MCP/UI id (ADR D8)
    contract_type           VARCHAR(64)     NOT NULL,   -- validated against allowlist (ADR D15)
    title                   VARCHAR(512)    NOT NULL,
    context                 TEXT,
    monitor_text            TEXT,                       -- enforcement.monitor plain language, VERBATIM
    monitor_mechanism       TEXT,                       -- enforcement.monitorMechanism, VERBATIM (UI mono panel)
    estimated_value         VARCHAR(255),               -- display string, e.g. '$1.2M annually across four subsidiaries'
    estimated_value_amount  NUMERIC(18,2),              -- nullable structured sibling
    status                  VARCHAR(32)     NOT NULL,   -- ContractStatus
    proposed_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    proposed_by             VARCHAR(255)    NOT NULL,
    activated_at            TIMESTAMP WITH TIME ZONE,
    resolved_at             TIMESTAMP WITH TIME ZONE,
    review_boundary         TEXT,                       -- JSON, nullable; unenforced-when-null (§6.2)
    metadata                TEXT,                       -- JSON
    CONSTRAINT uq_contracts_domain_key UNIQUE (domain_id, contract_key)
);
CREATE INDEX idx_contracts_domain_status ON contracts(domain_id, status);

-- ---------------------------------------------------------------------------
-- 2. contract_parties
-- ---------------------------------------------------------------------------
CREATE TABLE contract_parties (
    id                  UUID PRIMARY KEY,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE,
    created_by          VARCHAR(255)    NOT NULL DEFAULT 'system',
    version             BIGINT          NOT NULL DEFAULT 0,
    contract_id         UUID            NOT NULL,
    party_label         VARCHAR(255)    NOT NULL,   -- fixture string, e.g. 'Sentari, Tellix, Bursar (combined obligation)'
    party_type          VARCHAR(32)     NOT NULL,   -- ContractPartyType
    bound_entity_ids    TEXT,                       -- JSON array of {entityType, entityId, slug?} soft refs (severs S5 slug links)
    mcp_principal_id    VARCHAR(255),               -- set when the party's agent/system binds via MCP (P19); Phase 5 hardens
    response_required   BOOLEAN         NOT NULL DEFAULT TRUE,
    sequence_order      INTEGER         NOT NULL
);
CREATE INDEX idx_contract_parties_contract ON contract_parties(contract_id);

-- ---------------------------------------------------------------------------
-- 3. contract_party_responses — immutable once written
-- ---------------------------------------------------------------------------
CREATE TABLE contract_party_responses (
    id                  UUID PRIMARY KEY,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE,
    created_by          VARCHAR(255)    NOT NULL DEFAULT 'system',
    version             BIGINT          NOT NULL DEFAULT 0,
    party_id            UUID            NOT NULL,
    contract_id         UUID            NOT NULL,
    response            VARCHAR(16)     NOT NULL,   -- ACCEPTED | DECLINED
    responded_by        VARCHAR(255)    NOT NULL,
    responded_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    decline_trigger_id  UUID,                       -- optional citation at proposal stage
    decline_reasoning   TEXT,                       -- service-enforced NOT NULL iff response=DECLINED
    CONSTRAINT uq_party_response UNIQUE (party_id)
);

-- ---------------------------------------------------------------------------
-- 4. contract_commitments
-- ---------------------------------------------------------------------------
CREATE TABLE contract_commitments (
    id                  UUID PRIMARY KEY,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE,
    created_by          VARCHAR(255)    NOT NULL DEFAULT 'system',
    version             BIGINT          NOT NULL DEFAULT 0,
    contract_id         UUID            NOT NULL,
    party_id            UUID            NOT NULL,
    text                TEXT            NOT NULL,   -- VERBATIM fixture commitment string
    due_by              TIMESTAMP WITH TIME ZONE,
    cadence             VARCHAR(16),                -- MONTHLY | QUARTERLY | ... nullable
    compliance_state    VARCHAR(32)     NOT NULL DEFAULT 'UNEVALUATED',
    last_evaluated_at   TIMESTAMP WITH TIME ZONE,
    sequence_order      INTEGER         NOT NULL
);
CREATE INDEX idx_contract_commitments_contract ON contract_commitments(contract_id);

-- ---------------------------------------------------------------------------
-- 5. contract_decline_triggers
-- ---------------------------------------------------------------------------
CREATE TABLE contract_decline_triggers (
    id                      UUID PRIMARY KEY,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE,
    created_by              VARCHAR(255)    NOT NULL DEFAULT 'system',
    version                 BIGINT          NOT NULL DEFAULT 0,
    contract_id             UUID            NOT NULL,
    text                    TEXT            NOT NULL,   -- VERBATIM fixture trigger string
    predicate               TEXT,                       -- JSON, nullable: tiny DSL (§4.4); null = human judgment
    invocable_by_party_id   UUID,                       -- null = any party
    armed                   BOOLEAN         NOT NULL DEFAULT FALSE,  -- denormalized standing (§2.6)
    last_armed_at           TIMESTAMP WITH TIME ZONE,
    sequence_order          INTEGER         NOT NULL
);

-- ---------------------------------------------------------------------------
-- 6. contract_entity_links — navigation back-refs (linkedSubsidiarySlugs → real refs)
-- ---------------------------------------------------------------------------
CREATE TABLE contract_entity_links (
    id                  UUID PRIMARY KEY,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE,
    created_by          VARCHAR(255)    NOT NULL DEFAULT 'system',
    version             BIGINT          NOT NULL DEFAULT 0,
    contract_id         UUID            NOT NULL,
    linked_entity_type  VARCHAR(64)     NOT NULL,   -- DOMAIN | SYSTEM | PROCESS | CASCADE | AGENT_REGISTRATION | SUBSIDIARY_SLUG
    linked_entity_id    UUID,                       -- nullable during pre-D5 window
    slug                VARCHAR(160),               -- fixture slug fallback until subsidiary canonical modeling (D5)
    label               VARCHAR(255)
);
CREATE INDEX idx_contract_entity_links_contract ON contract_entity_links(contract_id);

-- ---------------------------------------------------------------------------
-- 7. contract_clause_guards — PDP hot path: FIRST-CLASS COLUMNS, no JSON in
--    the match path (ADR D9)
-- ---------------------------------------------------------------------------
CREATE TABLE contract_clause_guards (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE,
    created_by      VARCHAR(255)    NOT NULL DEFAULT 'system',
    version         BIGINT          NOT NULL DEFAULT 0,
    contract_id     UUID            NOT NULL,
    commitment_id   UUID,
    action_type     VARCHAR(255)    NOT NULL,   -- e.g. 'agent.promote', 'apply_dispatch_result'
    entity_type     VARCHAR(64),                -- nullable narrowing
    effect          VARCHAR(32)     NOT NULL,   -- DENY | REQUIRE_ATTESTATION | FLAG
    predicate       TEXT,                       -- JSON, nullable: tiny DSL (§4.4)
    active          BOOLEAN         NOT NULL DEFAULT FALSE,  -- true only while contract ACTIVE
    description     VARCHAR(512)    NOT NULL
);
CREATE INDEX idx_guards_action_active ON contract_clause_guards(action_type, active);
CREATE INDEX idx_guards_contract ON contract_clause_guards(contract_id);

-- ---------------------------------------------------------------------------
-- 8. contract_monitors
-- ---------------------------------------------------------------------------
CREATE TABLE contract_monitors (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE,
    created_by      VARCHAR(255)    NOT NULL DEFAULT 'system',
    version         BIGINT          NOT NULL DEFAULT 0,
    contract_id     UUID            NOT NULL,
    name            VARCHAR(255)    NOT NULL,   -- e.g. 'missing-monthly-report'
    rule_type       VARCHAR(32)     NOT NULL,   -- ATTESTATION_CADENCE | METRIC_THRESHOLD | MCP_PROBE | UNSTRUCTURED
    rule_spec       TEXT,                       -- JSON: {commitmentIds?, graceDays?, threshold?, metricKey?, requiredPartyIds?, readerRegistrationId?}
    cadence         VARCHAR(16)     NOT NULL,   -- MONTHLY | QUARTERLY | DAILY | WEEKLY
    grace_days      INTEGER         NOT NULL DEFAULT 0,
    status          VARCHAR(16)     NOT NULL DEFAULT 'SUSPENDED',
    surface_to      VARCHAR(64),                -- e.g. OPERATING_PARTNER_DASHBOARD
    severity        VARCHAR(16),                -- Severity enum value
    last_run_at     TIMESTAMP WITH TIME ZONE,
    last_outcome    VARCHAR(32),
    next_run_at     TIMESTAMP WITH TIME ZONE    -- DB-recoverable scheduling across restarts
);
CREATE INDEX idx_contract_monitors_due ON contract_monitors(status, next_run_at);

-- ---------------------------------------------------------------------------
-- 9. contract_monitor_runs — append-only
-- ---------------------------------------------------------------------------
CREATE TABLE contract_monitor_runs (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE,
    created_by      VARCHAR(255)    NOT NULL DEFAULT 'system',
    version         BIGINT          NOT NULL DEFAULT 0,
    monitor_id      UUID            NOT NULL,
    started_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at     TIMESTAMP WITH TIME ZONE,
    outcome         VARCHAR(32)     NOT NULL,   -- MonitorRunOutcome
    period_key      VARCHAR(16),                -- evaluated period, e.g. '2026-07' / '2026-Q3'
    details         TEXT                        -- JSON bounded trace: rule, inputs resolved, counts/values; capped (§1)
);
CREATE INDEX idx_monitor_runs_monitor ON contract_monitor_runs(monitor_id, started_at);

-- ---------------------------------------------------------------------------
-- 10. contract_attestations — idempotent per period (ADR D13)
-- ---------------------------------------------------------------------------
CREATE TABLE contract_attestations (
    id                  UUID PRIMARY KEY,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE,
    created_by          VARCHAR(255)    NOT NULL DEFAULT 'system',
    version             BIGINT          NOT NULL DEFAULT 0,
    commitment_id       UUID            NOT NULL,
    contract_id         UUID            NOT NULL,
    party_id            UUID            NOT NULL,
    period_key          VARCHAR(16)     NOT NULL,   -- '2026-07' (MONTHLY) / '2026-Q3' (QUARTERLY) / 'ONE_TIME'
    attested_by         VARCHAR(255)    NOT NULL,
    attested_at         TIMESTAMP WITH TIME ZONE NOT NULL,
    compliance_claim    VARCHAR(16)     NOT NULL,   -- ON_TRACK | COMPLETE | AT_RISK | MISSED
    statement           TEXT,
    evidence_refs       TEXT,                       -- JSON array of Evidence/DocumentRegistry ids (EVIDENCE-GROUNDING shape)
    submitted_via       VARCHAR(16)     NOT NULL DEFAULT 'API',  -- UI | MCP | API
    CONSTRAINT uq_attestation_period UNIQUE (commitment_id, period_key)  -- upsert key; history lives in contract_events
);

-- ---------------------------------------------------------------------------
-- 11. contract_metric_samples — push-based v1 metric feed (ADR D11)
-- ---------------------------------------------------------------------------
CREATE TABLE contract_metric_samples (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE,
    created_by      VARCHAR(255)    NOT NULL DEFAULT 'system',
    version         BIGINT          NOT NULL DEFAULT 0,
    contract_id     UUID            NOT NULL,
    party_id        UUID            NOT NULL,
    metric_key      VARCHAR(128)    NOT NULL,   -- e.g. 'utilization_share', 'accuracy'
    metric_value    DOUBLE PRECISION NOT NULL,
    period_key      VARCHAR(16)     NOT NULL,
    reported_by     VARCHAR(255)    NOT NULL,
    reported_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_metric_sample UNIQUE (contract_id, party_id, metric_key, period_key)  -- idempotent upsert
);

-- ---------------------------------------------------------------------------
-- 12. contract_breaches
-- ---------------------------------------------------------------------------
CREATE TABLE contract_breaches (
    id                          UUID PRIMARY KEY,
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                  TIMESTAMP WITH TIME ZONE,
    created_by                  VARCHAR(255)    NOT NULL DEFAULT 'system',
    version                     BIGINT          NOT NULL DEFAULT 0,
    contract_id                 UUID            NOT NULL,
    commitment_id               UUID,
    monitor_id                  UUID,
    period_key                  VARCHAR(16),
    status                      VARCHAR(32)     NOT NULL,   -- BreachStatus
    severity                    VARCHAR(16),
    summary                     VARCHAR(512)    NOT NULL,
    detected_at                 TIMESTAMP WITH TIME ZONE NOT NULL,
    detected_by                 VARCHAR(32)     NOT NULL,   -- MONITOR | HUMAN_ATTESTATION | GATEWAY_FLAG
    last_confirmed_at           TIMESTAMP WITH TIME ZONE,   -- refreshed on re-detection (ADR D13)
    surfaced_at                 TIMESTAMP WITH TIME ZONE,
    acknowledged_by             VARCHAR(255),
    acknowledged_at             TIMESTAMP WITH TIME ZONE,
    resolution                  VARCHAR(16),                -- REMEDIATED | WAIVED
    resolution_actor            VARCHAR(255),
    resolution_rationale        TEXT,                       -- mandatory for WAIVED (service-enforced)
    remediation_evidence_refs   TEXT                        -- JSON; mandatory for REMEDIATED (service-enforced)
);
CREATE INDEX idx_contract_breaches_contract ON contract_breaches(contract_id, status);

-- ---------------------------------------------------------------------------
-- 13. contract_events — append-only audit timeline (the contract analog of
--     CASCADE-DURABILITY). No UPDATE/DELETE code path exists: insert-only
--     repository surface (extends Repository) + ArchUnit rule (3c) pins it.
-- ---------------------------------------------------------------------------
CREATE TABLE contract_events (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE,
    created_by      VARCHAR(255)    NOT NULL DEFAULT 'system',
    version         BIGINT          NOT NULL DEFAULT 0,
    contract_id     UUID            NOT NULL,
    event_type      VARCHAR(64)     NOT NULL,
    ref_id          UUID,                       -- party/commitment/breach/monitor/attestation id as applicable
    actor_type      VARCHAR(16)     NOT NULL,   -- ContractActorType
    actor_id        VARCHAR(255)    NOT NULL,
    payload         TEXT,                       -- JSON
    occurred_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_contract_events_contract ON contract_events(contract_id, occurred_at);

-- ---------------------------------------------------------------------------
-- 14. contract_enforcement_decisions — high-volume PDP log, separate
--     retention profile (ADR D17)
-- ---------------------------------------------------------------------------
CREATE TABLE contract_enforcement_decisions (
    id              UUID PRIMARY KEY,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE,
    created_by      VARCHAR(255)    NOT NULL DEFAULT 'system',
    version         BIGINT          NOT NULL DEFAULT 0,
    domain_id       UUID            NOT NULL,
    action_type     VARCHAR(255)    NOT NULL,
    actor_type      VARCHAR(16)     NOT NULL,
    actor_id        VARCHAR(255)    NOT NULL,
    decision        VARCHAR(32)     NOT NULL,   -- GatewayDecision
    contract_ids    TEXT,                       -- JSON array (no FK; queried by domain+time in v1)
    clause_ids      TEXT,                       -- JSON array of guard ids
    dispatch_id     VARCHAR(255),               -- orchestrator dispatch id when actionType=apply_dispatch_result
    reason          VARCHAR(512),
    latency_ms      INTEGER,
    mode            VARCHAR(16)     NOT NULL,   -- shadow | enforce
    decided_at      TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_enforcement_decisions_domain_time ON contract_enforcement_decisions(domain_id, decided_at);

-- ---------------------------------------------------------------------------
-- ShedLock lease table for the monitor sweep. The cascade engine (V4) may
-- already create it; IF EXISTS guard keeps the migration order-independent.
-- Coordinate with D1 chunk owner.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS shedlock (
    name        VARCHAR(64) PRIMARY KEY,
    lock_until  TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);
