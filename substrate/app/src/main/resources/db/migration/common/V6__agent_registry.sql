-- ============================================================================
-- V6__agent_registry.sql
-- Agent Provenance Registry persistence (engine-spec agents.md §4;
-- ADR-003-agent-registry D1/D3/D4/D5/D9/D10/D11/D12/D13).
--
-- Eleven tables:
--   agent_registrations        aggregate root; provenance NOT NULL (a
--                              provenance-less agent row cannot exist — I1);
--                              lifecycle/control/liveness blocks are
--                              rebuildable projections (⟲) of the event log
--   agent_oversight_links      ORDERED escalation chain (a table, not JSON —
--                              it is the notification fan-out target)
--   agent_status_events        APPEND-ONLY audit source of truth (V6_1 trigger
--                              on Postgres; H2 dev stays service-enforced)
--   agent_interventions        attention overlay; mode is NOT changed by an
--                              open intervention (two-dimensional state, D4)
--   agent_compliance_reviews   promotion-gate currency (single-use, D10)
--   agent_criteria_gates       deploymentCriteria as runtime gate (TinyDsl
--                              VERBATIM in predicate; frozen at deploy, D9)
--   agent_metric_samples       push-based metric feed (idempotent upsert)
--   agent_throttle_budgets     first-class budget columns (never JSON, D12)
--   agent_rate_counters        DB-backed window counters (S12 lesson)
--   agent_sightings            liveness telemetry, NOT audit (D11)
--   agent_notifications        transactional outbox (same-tx as event, D13)
--
-- Portability contract: ANSI-portable DDL only — runs on BOTH H2
-- (MODE=PostgreSQL) and Postgres 16. Types restricted to UUID / VARCHAR /
-- TEXT / INT / BIGINT / BOOLEAN / DOUBLE PRECISION / TIMESTAMP WITH TIME
-- ZONE. JSON-shaped fields are TEXT (JsonNodeConverter — port-map §4.5; the
-- jsonb flip is a documented later one-pass migration).
--
-- Postgres-only artifacts (append-only trigger, partial uniques) live in
-- db/migration/postgresql/V6_1 (precedent V2_1/V4_1/V5_1; H2-green is never
-- acceptance).
--
-- Soft references by design (NO DB FKs; application-enforced): domain_id,
-- capability_id, member ids, process/step ids, initiative_id, predecessor,
-- evidence_refs, event_id and all agent_registration_id cross-references.
--
-- Column names must match the com.meridian.substrate.common.domain entities
-- EXACTLY — ddl-auto=validate is enforced in every profile and fails boot on
-- drift.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. agent_registrations — aggregate root
-- ---------------------------------------------------------------------------
CREATE TABLE agent_registrations (
    id                        UUID PRIMARY KEY,
    created_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                TIMESTAMP WITH TIME ZONE,
    created_by                VARCHAR(255) NOT NULL DEFAULT 'system',
    version                   BIGINT NOT NULL DEFAULT 0,
    domain_id                 UUID NOT NULL,             -- a Beacon subsidiary IS a Domain (cascade D13)
    agent_key                 VARCHAR(160) NOT NULL,     -- slug identity ('a-pre-visit'); contract_key idiom (ADR-002 D8)
    name                      VARCHAR(255) NOT NULL,
    role_description          VARCHAR(512) NOT NULL,     -- fixture `role`
    capability_id             UUID,                      -- soft ref capabilities (fixture capabilityId)
    execution_locus           VARCHAR(30) NOT NULL,      -- AgentExecutionLocus
    mcp_principal_id          VARCHAR(255),              -- binds Principal(AGENT,…); Phase 5 OIDC hardens
    -- provenance — REQUIRED at deploy ([AGENT-PROVENANCE]); immutable except oversight links
    deployed_by_member_id     UUID,                      -- soft ref organization_members (cascade D11 owner idiom)
    deployed_by_label         VARCHAR(255) NOT NULL,
    deployed_at               TIMESTAMP WITH TIME ZONE NOT NULL,  -- server clock (injectable, S15)
    deployment_criteria       TEXT NOT NULL,             -- VERBATIM prose mandate; IMMUTABLE post-deploy (ADR D5)
    criteria_digest           VARCHAR(64) NOT NULL,      -- SHA-256(prose + ordered gates); anchored in DEPLOYED chain entry
    predecessor_registration_id UUID,                    -- retire+redeploy lineage (soft ref)
    -- AgentDeploymentSpec unification ([AGENT-SPEC-UNIFICATION], doc 06)
    target_process_id         UUID,                      -- soft ref processes (nullable: fixtures bind capability, not process)
    target_step_ids           TEXT,                      -- JSON array of ProcessStep UUIDs
    agent_readiness_score     INT,                       -- CHECK below; nullable
    readiness_source          VARCHAR(32),               -- READINESS_LENS | OPERATOR_ASSERTED; service: NOT NULL iff score present
    required_system_access    TEXT,                      -- JSON
    required_data_access      TEXT,                      -- JSON
    supervisory_model         VARCHAR(40) NOT NULL,      -- API-required (unification posture, NOT provenance — ADR D3)
    initiative_id             UUID,                      -- reserved soft ref; plan engine does not exist (EXTENSION POINT agent-9)
    notes                     TEXT,
    -- P11 safety defaults as data
    read_only                 BOOLEAN NOT NULL DEFAULT TRUE,
    schedule_window           TEXT,                      -- JSON, nullable
    sampling_pct              INT,
    -- lifecycle projections ⟲
    operational_mode          VARCHAR(20) NOT NULL,      -- AgentOperationalMode
    mode_before_pause         VARCHAR(20),               -- resume returns HERE
    open_intervention_count   INT NOT NULL DEFAULT 0,
    event_seq                 INT NOT NULL DEFAULT 0,    -- fencing token, +1 per event (cascade D8)
    shadow_entered_at         TIMESTAMP WITH TIME ZONE,  -- renders "shadow-mode N weeks"; reset on DEMOTED_TO_SHADOW
    promotion_requested_at    TIMESTAMP WITH TIME ZONE,
    retired_at                TIMESTAMP WITH TIME ZONE,
    retired_reason            VARCHAR(20),               -- KILLED | RETIRED (Mike-flagged distinction)
    retired_by_label          VARCHAR(255),
    -- control-confirmation projections ⟲ (RECORDED_INTENT tracking)
    control_pending_verb      VARCHAR(20),               -- pause | kill | retire | throttle … NULL = none pending
    control_requested_at      TIMESTAMP WITH TIME ZONE,
    control_acknowledged_at   TIMESTAMP WITH TIME ZONE,  -- runtime saw the order (MCP acknowledge_control)
    control_confirmed_at      TIMESTAMP WITH TIME ZONE,  -- human attested it stopped (REST confirm-control)
    control_confirmed_by_label VARCHAR(255),
    -- liveness projections ⟲ (from sightings ONLY — never row recency)
    last_seen_at              TIMESTAMP WITH TIME ZONE,
    last_seen_via             VARCHAR(30),               -- AgentSightingChannel
    last_attested_at          TIMESTAMP WITH TIME ZONE,
    last_attested_by_label    VARCHAR(255),
    heartbeat_expected_minutes INT,                      -- NULL = "not expected to report" (never stale-red)
    CONSTRAINT uq_agent_domain_key UNIQUE (domain_id, agent_key),
    CONSTRAINT ck_agent_readiness CHECK (agent_readiness_score IS NULL
                                         OR (agent_readiness_score >= 0 AND agent_readiness_score <= 100)),
    CONSTRAINT ck_agent_sampling  CHECK (sampling_pct IS NULL OR (sampling_pct >= 0 AND sampling_pct <= 100))
);
CREATE INDEX idx_agents_domain_mode ON agent_registrations(domain_id, operational_mode);
CREATE INDEX idx_agents_principal   ON agent_registrations(mcp_principal_id);   -- gateway hot path

-- ---------------------------------------------------------------------------
-- 2. agent_oversight_links — ORDERED escalation chain; a table (not JSON)
--    because it is the notification fan-out + escalation routing target.
--    Rendered "A → B → C". >=1 row at deploy.
-- ---------------------------------------------------------------------------
CREATE TABLE agent_oversight_links (
    id                    UUID PRIMARY KEY,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE,
    created_by            VARCHAR(255) NOT NULL DEFAULT 'system',
    version               BIGINT NOT NULL DEFAULT 0,
    agent_registration_id UUID NOT NULL,
    position              INT NOT NULL,                  -- 1..N
    member_id             UUID,                          -- soft ref
    label                 VARCHAR(255) NOT NULL,
    CONSTRAINT uq_aol_position UNIQUE (agent_registration_id, position)
);
CREATE INDEX idx_aol_agent ON agent_oversight_links(agent_registration_id);

-- ---------------------------------------------------------------------------
-- 3. agent_status_events — APPEND-ONLY audit source of truth
-- ---------------------------------------------------------------------------
CREATE TABLE agent_status_events (
    id                    UUID PRIMARY KEY,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE,
    created_by            VARCHAR(255) NOT NULL DEFAULT 'system',
    version               BIGINT NOT NULL DEFAULT 0,
    agent_registration_id UUID NOT NULL,
    domain_id             UUID NOT NULL,
    event_seq             INT NOT NULL,                  -- 1..N per agent, gapless
    event_type            VARCHAR(40) NOT NULL,          -- AgentEventType
    actor_type            VARCHAR(16) NOT NULL,          -- ContractActorType; AGENT illegal on lifecycle events (service)
    actor_member_id       UUID,
    actor_label           VARCHAR(255) NOT NULL,         -- PersonaContext now (S9 spoofable, accepted), OIDC Phase 5
    reason                TEXT,                          -- CHECK below for PAUSED/KILLED/RETIRED
    payload               TEXT,                          -- JSON: DEPLOYED = full frozen spec snapshot; OVERSIGHT_CHANGED = before/after;
                                                         --       THROTTLE_SET = budget; DISPATCH = provenance digest {taskId, model,
                                                         --       eventsDigest, requestedAt, landedAt}; PROMOTED = consumed review id
    evidence_refs         TEXT,                          -- JSON array of Evidence/DocumentRegistry ids
    idempotency_key       VARCHAR(128) NOT NULL,
    occurred_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    decision_log_hash     VARCHAR(64),                   -- set when mirrored (DEPLOYED/PROMOTED/RETIRED/KILLED), cross-navigation
    CONSTRAINT uq_ase_idem UNIQUE (agent_registration_id, idempotency_key),
    CONSTRAINT uq_ase_seq  UNIQUE (agent_registration_id, event_seq),
    CONSTRAINT ck_ase_reason CHECK (
        (event_type IN ('PAUSED','KILLED','RETIRED') AND reason IS NOT NULL)
        OR event_type NOT IN ('PAUSED','KILLED','RETIRED'))
);
CREATE INDEX idx_ase_agent_seq ON agent_status_events(agent_registration_id, event_seq);
CREATE INDEX idx_ase_domain    ON agent_status_events(domain_id, occurred_at);

-- ---------------------------------------------------------------------------
-- 4. agent_interventions — attention overlay; agent KEEPS operational_mode
--    while one is open
-- ---------------------------------------------------------------------------
CREATE TABLE agent_interventions (
    id                    UUID PRIMARY KEY,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE,
    created_by            VARCHAR(255) NOT NULL DEFAULT 'system',
    version               BIGINT NOT NULL DEFAULT 0,
    agent_registration_id UUID NOT NULL,
    domain_id             UUID NOT NULL,
    status                VARCHAR(16) NOT NULL,          -- OPEN | RESOLVED
    trigger_kind          VARCHAR(30) NOT NULL,          -- HUMAN | MONITOR | GATEWAY_VIOLATION | DRIFT_METRIC
    source_key            VARCHAR(160),                  -- dedupe key for engine-raised rows (e.g. 'active-while-paused')
    reason                TEXT NOT NULL,
    evidence_refs         TEXT,                          -- JSON
    opened_by_member_id   UUID,
    opened_by_label       VARCHAR(255) NOT NULL,
    opened_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    acknowledged_by_label VARCHAR(255),
    acknowledged_at       TIMESTAMP WITH TIME ZONE,
    resolved_by_label     VARCHAR(255),
    resolved_at           TIMESTAMP WITH TIME ZONE,
    resolution            VARCHAR(30),                   -- AgentInterventionResolution; service: NOT NULL iff RESOLVED
    resolution_note       TEXT                           -- service: NOT NULL iff RESOLVED
);
CREATE INDEX idx_ai_agent_status ON agent_interventions(agent_registration_id, status);

-- ---------------------------------------------------------------------------
-- 5. agent_compliance_reviews — promotion gate currency (ADR D10). Records
--    reviewer identity, decision, rationale, evidence refs and NOTHING else —
--    review-content semantics are UNKNOWN.
-- ---------------------------------------------------------------------------
CREATE TABLE agent_compliance_reviews (
    id                    UUID PRIMARY KEY,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE,
    created_by            VARCHAR(255) NOT NULL DEFAULT 'system',
    version               BIGINT NOT NULL DEFAULT 0,
    agent_registration_id UUID NOT NULL,
    review_kind           VARCHAR(20) NOT NULL,          -- PROMOTION | PERIODIC | INCIDENT
    requested_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    requested_by_label    VARCHAR(255) NOT NULL,
    reviewer_member_id    UUID,
    reviewer_label        VARCHAR(255),
    decided_at            TIMESTAMP WITH TIME ZONE,
    decision              VARCHAR(20),                   -- APPROVED | REJECTED | NEEDS_MORE_TIME
    rationale             TEXT,                          -- service: NOT NULL at decision
    evidence_refs         TEXT,                          -- JSON
    consumed_by_event_id  UUID                           -- single-use: set when a PROMOTED event consumes this review
);
CREATE INDEX idx_acr_agent ON agent_compliance_reviews(agent_registration_id, review_kind);

-- ---------------------------------------------------------------------------
-- 6. agent_criteria_gates — deploymentCriteria as a runtime gate (ADR D9).
--    IMMUTABLE post-deploy except standing/last_evaluated_at.
-- ---------------------------------------------------------------------------
CREATE TABLE agent_criteria_gates (
    id                    UUID PRIMARY KEY,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE,
    created_by            VARCHAR(255) NOT NULL DEFAULT 'system',
    version               BIGINT NOT NULL DEFAULT 0,
    agent_registration_id UUID NOT NULL,
    sequence_order        INT NOT NULL,
    gate_text             TEXT NOT NULL,                 -- VERBATIM mandate clause ('target 90% pre-fill accuracy')
    rule_type             VARCHAR(20) NOT NULL,          -- STRUCTURED | UNSTRUCTURED
    predicate             TEXT,                          -- JSON: the contracts TinyDsl (§4.4 of contracts spec) VERBATIM — no second DSL.
                                                         -- metric leaves resolve against agent_metric_samples. NULL iff UNSTRUCTURED.
    cadence               VARCHAR(16),                   -- MONTHLY | QUARTERLY | WEEKLY | DAILY
    standing              VARCHAR(24) NOT NULL DEFAULT 'UNEVALUATED',
    last_evaluated_at     TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_acg_order UNIQUE (agent_registration_id, sequence_order)
);

-- ---------------------------------------------------------------------------
-- 7. agent_metric_samples — push-based (ADR-002 D11 stance), idempotent
--    upsert (D13 idiom)
-- ---------------------------------------------------------------------------
CREATE TABLE agent_metric_samples (
    id                    UUID PRIMARY KEY,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE,
    created_by            VARCHAR(255) NOT NULL DEFAULT 'system',
    version               BIGINT NOT NULL DEFAULT 0,
    agent_registration_id UUID NOT NULL,
    metric_key            VARCHAR(128) NOT NULL,         -- e.g. 'prefill_accuracy', 'adherence'
    metric_value          DOUBLE PRECISION NOT NULL,
    period_key            VARCHAR(16) NOT NULL,
    reported_by_principal VARCHAR(255),
    reported_at           TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_ams_sample UNIQUE (agent_registration_id, metric_key, period_key)
);

-- ---------------------------------------------------------------------------
-- 8. agent_throttle_budgets — first-class indexed columns on the gateway hot
--    path (never JSON predicates)
-- ---------------------------------------------------------------------------
CREATE TABLE agent_throttle_budgets (
    id                    UUID PRIMARY KEY,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE,
    created_by            VARCHAR(255) NOT NULL DEFAULT 'system',
    version               BIGINT NOT NULL DEFAULT 0,
    agent_registration_id UUID NOT NULL,
    budget_kind           VARCHAR(40) NOT NULL,          -- validated string: ACTIONS_PER_WINDOW | TOKENS_PER_DAY | SCHEDULE_WINDOW | SAMPLING_PCT
    limit_value           DOUBLE PRECISION,
    window_minutes        INT,
    active                BOOLEAN NOT NULL DEFAULT TRUE,
    set_by_label          VARCHAR(255) NOT NULL,
    set_at                TIMESTAMP WITH TIME ZONE NOT NULL,
    note                  VARCHAR(512)
);
CREATE INDEX idx_atb_agent_active ON agent_throttle_budgets(agent_registration_id, active);

-- ---------------------------------------------------------------------------
-- 9. agent_rate_counters — DB-backed window counters (S12 lesson: NEVER
--    in-memory atomics)
-- ---------------------------------------------------------------------------
CREATE TABLE agent_rate_counters (
    id                    UUID PRIMARY KEY,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE,
    created_by            VARCHAR(255) NOT NULL DEFAULT 'system',
    version               BIGINT NOT NULL DEFAULT 0,
    agent_registration_id UUID NOT NULL,
    window_start          TIMESTAMP WITH TIME ZONE NOT NULL,
    action_count          INT NOT NULL DEFAULT 0,
    CONSTRAINT uq_arc_window UNIQUE (agent_registration_id, window_start)
);

-- ---------------------------------------------------------------------------
-- 10. agent_sightings — telemetry, NOT audit (deliberately separate from
--     agent_status_events: sightings have a retention profile; events are
--     the governance trail — ADR D11)
-- ---------------------------------------------------------------------------
CREATE TABLE agent_sightings (
    id                    UUID PRIMARY KEY,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE,
    created_by            VARCHAR(255) NOT NULL DEFAULT 'system',
    version               BIGINT NOT NULL DEFAULT 0,
    agent_registration_id UUID NOT NULL,
    seen_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    channel               VARCHAR(30) NOT NULL,          -- AgentSightingChannel
    detail                VARCHAR(500),
    bucket_key            VARCHAR(40) NOT NULL,          -- hourly bucket (config §1)
    CONSTRAINT uq_as_bucket UNIQUE (agent_registration_id, channel, bucket_key)
);
CREATE INDEX idx_as_agent_seen ON agent_sightings(agent_registration_id, seen_at);

-- ---------------------------------------------------------------------------
-- 11. agent_notifications — transactional outbox, SAME tx as the triggering
--     event (ADR-001 D10)
-- ---------------------------------------------------------------------------
CREATE TABLE agent_notifications (
    id                    UUID PRIMARY KEY,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE,
    created_by            VARCHAR(255) NOT NULL DEFAULT 'system',
    version               BIGINT NOT NULL DEFAULT 0,
    domain_id             UUID NOT NULL,
    agent_registration_id UUID NOT NULL,
    event_id              UUID,                          -- triggering event; NULL for sweeper rows
    notification_type     VARCHAR(40) NOT NULL,          -- AgentNotificationType
    recipient_member_id   UUID,
    recipient_label       VARCHAR(255) NOT NULL,         -- oversight-chain fan-out target
    tone                  VARCHAR(16),                   -- info | warn | destructive
    payload               TEXT,                          -- JSON
    dedupe_key            VARCHAR(160),                  -- NULL legal; UNIQUE backstop
    read_at               TIMESTAMP WITH TIME ZONE,
    dispatched_at         TIMESTAMP WITH TIME ZONE,      -- Phase-5 channel watermark
    CONSTRAINT uq_an_dedupe UNIQUE (dedupe_key)
);
CREATE INDEX idx_an_recipient ON agent_notifications(recipient_member_id, read_at);
