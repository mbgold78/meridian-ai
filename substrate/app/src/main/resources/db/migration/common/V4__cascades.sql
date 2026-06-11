-- ============================================================================
-- V4__cascades.sql
-- Cascading evaluation engine persistence (engine-spec cascade.md §3;
-- ADR-001-cascade-engine D1/D2/D5/D6/D8/D9/D10).
--
-- Five tables:
--   cascades               aggregate root (projection fields rebuildable from actions)
--   cascade_levels         one row per depth (mutable projection; truth in actions)
--   cascade_level_actions  APPEND-ONLY source of truth (the audit trail)
--   cascade_drafts         mutable authoring persistence (outside the audit trail)
--   cascade_notifications  transactional outbox (written IN the action tx)
--
-- Portability contract: ANSI-portable DDL only — runs on BOTH H2
-- (MODE=PostgreSQL) and Postgres 16. Types restricted to UUID / VARCHAR /
-- TEXT / INT / BIGINT / BOOLEAN / TIMESTAMP WITH TIME ZONE. JSON-shaped
-- fields are TEXT (JsonNodeConverter — port-map §4.5); the jsonb flip is a
-- documented later one-pass vendor migration.
--
-- The Postgres-only append-only trigger on cascade_level_actions lives in
-- db/migration/postgresql/V4_1 (ADR D9; precedent V2_1 — H2 dev stays
-- service-enforced; H2-green is never acceptance).
--
-- Soft references by design (NO DB FKs; application-enforced, canonical-model
-- convention port-map §2.1): all member ids, domain_id / affected_domain_id,
-- origin_finding_id, draft_id, cascade/level/action cross-references.
--
-- Column names must match the com.meridian.substrate.common.domain entities
-- EXACTLY — ddl-auto=validate is enforced in every profile and fails boot on
-- drift.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. cascades — aggregate root (spec §3.1)
-- ---------------------------------------------------------------------------
CREATE TABLE cascades (
    id                          UUID            NOT NULL,
    -- identity & scope
    domain_id                   UUID            NOT NULL,   -- engagement org scope (holding-co domain)
    cascade_group_id            UUID,                       -- ADR D2: optional parallel-cascade group
    predecessor_cascade_id      UUID,                       -- reissue lineage (soft ref)
    -- immutable issuance artifacts (CASCADE-DURABILITY; never updated after insert)
    origin_owner_member_id      UUID,                       -- soft ref organization_members
    origin_owner_label          VARCHAR(255)    NOT NULL,
    originated_at               TIMESTAMP WITH TIME ZONE NOT NULL,
    original_critique           TEXT            NOT NULL,
    acceptance_criteria         TEXT            NOT NULL,
    summary                     VARCHAR(500),               -- reserved, unrendered (queue #22)
    affected_domain_id          UUID,                       -- soft ref domains (Beacon subsidiary IS a Domain)
    origin_finding_id           UUID,                       -- soft ref findings (authoring-from-finding, UX undesigned)
    authoring_mode              VARCHAR(20)     NOT NULL,
    produced_by                 VARCHAR(20)     NOT NULL,   -- HUMAN | AI_ASSISTIVE
    draft_id                    UUID,                       -- soft ref cascade_drafts
    response_sla_days           INT,                        -- NULL = engagement default applied at issue; 0 = disabled
    -- lifecycle projections (rebuildable from actions)
    current_state               VARCHAR(30)     NOT NULL,   -- CascadeState
    chain_seq                   INT             NOT NULL DEFAULT 0,  -- fencing token, +1 per action
    frontier_depth              INT             NOT NULL,   -- bottom-of-chain (spec §5.1)
    frontier_state              VARCHAR(20)     NOT NULL,   -- CascadeLevelState of frontier level
    attention_signal            VARCHAR(30)     NOT NULL,   -- CascadeAttentionSignal
    overdue                     BOOLEAN         NOT NULL DEFAULT FALSE,
    -- resolution accountability record (from RESOLVE_* action)
    resolved_at                 TIMESTAMP WITH TIME ZONE,
    resolved_by_member_id       UUID,
    resolved_by_label           VARCHAR(255),
    resolution_note             TEXT,
    accepted_decline_action_id  UUID,                       -- soft ref cascade_level_actions; set iff RESOLVED_DECLINED
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by                  VARCHAR(255)    NOT NULL,
    version                     BIGINT          NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_cascades_domain_id ON cascades(domain_id);
CREATE INDEX idx_cascades_state     ON cascades(domain_id, current_state);
CREATE INDEX idx_cascades_group     ON cascades(cascade_group_id);
CREATE INDEX idx_cascades_origin    ON cascades(origin_owner_member_id);

-- ---------------------------------------------------------------------------
-- 2. cascade_levels — one row per depth (mutable projection; truth in actions)
--    (spec §3.2)
-- ---------------------------------------------------------------------------
CREATE TABLE cascade_levels (
    id                  UUID            NOT NULL,
    cascade_id          UUID            NOT NULL,
    depth               INT             NOT NULL,   -- 0 = originator; contiguous 0..N
    owner_member_id     UUID,                       -- soft ref; current-round owner
    owner_label         VARCHAR(255)    NOT NULL,
    current_state       VARCHAR(20)     NOT NULL,   -- CascadeLevelState
    round               INT             NOT NULL DEFAULT 1,  -- incremented on RECASCADE reactivation
    refined_critique    TEXT,                       -- current-round projection (fixture DTO shape)
    decline_reasoning   TEXT,                       -- current-round projection
    activated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    acted_at            TIMESTAMP WITH TIME ZONE,
    response_deadline   TIMESTAMP WITH TIME ZONE,   -- NULL when SLA disabled
    overdue             BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by          VARCHAR(255)    NOT NULL,
    version             BIGINT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_cascade_levels_depth UNIQUE (cascade_id, depth)
);

CREATE INDEX idx_cascade_levels_cascade ON cascade_levels(cascade_id);

-- ---------------------------------------------------------------------------
-- 3. cascade_level_actions — APPEND-ONLY source of truth (spec §3.3)
--    No UPDATE/DELETE anywhere: service discipline + insert-only repository
--    surface everywhere; PG trigger backstop in postgresql/V4_1 (ADR D9).
-- ---------------------------------------------------------------------------
CREATE TABLE cascade_level_actions (
    id                          UUID            NOT NULL,
    cascade_id                  UUID            NOT NULL,
    level_id                    UUID            NOT NULL,   -- cascade_levels row acted on (depth 0 for ISSUE/RESOLVE_*)
    depth                       INT             NOT NULL,
    round                       INT             NOT NULL,   -- level's round at action time
    action                      VARCHAR(30)     NOT NULL,   -- CascadeActionType
    actor_member_id             UUID,
    actor_label                 VARCHAR(255)    NOT NULL,
    refined_critique            TEXT,                       -- ISSUE/CASCADE/ABSORB/RECASCADE
    decline_reasoning           TEXT,                       -- DECLINE
    resolution_note             TEXT,                       -- RESOLVE_LOOKS_GOOD / RESOLVE_DECLINED
    next_owner_member_id        UUID,                       -- ISSUE/CASCADE/RECASCADE routing
    next_owner_label            VARCHAR(255),
    accepted_decline_action_id  UUID,                       -- RESOLVE_DECLINED only
    acted_at                    TIMESTAMP WITH TIME ZONE NOT NULL,
    idempotency_key             VARCHAR(128)    NOT NULL,   -- ADR D8: mandatory
    chain_seq                   INT             NOT NULL,   -- 1..N per cascade, gapless
    created_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by                  VARCHAR(255)    NOT NULL,
    version                     BIGINT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_cla_idem  UNIQUE (cascade_id, idempotency_key),
    CONSTRAINT uq_cla_seq   UNIQUE (cascade_id, chain_seq),
    CONSTRAINT ck_cla_refined  CHECK (
        (action IN ('ISSUE','CASCADE','ABSORB','RECASCADE') AND refined_critique IS NOT NULL)
        OR action NOT IN ('ISSUE','CASCADE','ABSORB','RECASCADE')),
    CONSTRAINT ck_cla_decline  CHECK (
        (action = 'DECLINE' AND decline_reasoning IS NOT NULL) OR action <> 'DECLINE'),
    CONSTRAINT ck_cla_resolve  CHECK (
        (action IN ('RESOLVE_LOOKS_GOOD','RESOLVE_DECLINED') AND resolution_note IS NOT NULL)
        OR action NOT IN ('RESOLVE_LOOKS_GOOD','RESOLVE_DECLINED')),
    CONSTRAINT ck_cla_nextowner CHECK (
        (action IN ('ISSUE','CASCADE','RECASCADE') AND next_owner_label IS NOT NULL)
        OR action NOT IN ('ISSUE','CASCADE','RECASCADE'))
);

CREATE INDEX idx_cla_cascade_seq   ON cascade_level_actions(cascade_id, chain_seq);
CREATE INDEX idx_cla_cascade_depth ON cascade_level_actions(cascade_id, depth);

-- ---------------------------------------------------------------------------
-- 4. cascade_drafts — mutable authoring persistence, outside the audit trail
--    (spec §3.4; ADR D6 — a cascade is born IN_FLIGHT only on human /issue)
-- ---------------------------------------------------------------------------
CREATE TABLE cascade_drafts (
    id                              UUID            NOT NULL,
    domain_id                       UUID            NOT NULL,
    author_member_id                UUID,
    author_label                    VARCHAR(255)    NOT NULL,
    original_critique               TEXT,
    acceptance_criteria             TEXT,
    summary                         VARCHAR(500),
    affected_domain_id              UUID,
    origin_finding_id               UUID,
    cascade_group_id                UUID,
    authoring_mode                  VARCHAR(20)     NOT NULL,
    response_sla_days               INT,
    suggested_critique              TEXT,
    suggested_acceptance_criteria   TEXT,
    suggestion_provenance           TEXT,           -- JSON (JsonNodeConverter): {taskId, model, eventsDigest, requestedAt, landedAt}
    suggestion_state                VARCHAR(20)     NOT NULL DEFAULT 'NONE',
    orchestrator_task_id            VARCHAR(255),
    status                          VARCHAR(20)     NOT NULL DEFAULT 'OPEN',
    issued_cascade_id               UUID,
    created_at                      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at                      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by                      VARCHAR(255)    NOT NULL,
    version                         BIGINT          NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_cascade_drafts_domain ON cascade_drafts(domain_id, status);

-- ---------------------------------------------------------------------------
-- 5. cascade_notifications — transactional outbox (ADR D10: written IN the
--    action tx; spec §3.5). Multiple NULL dedupe_key rows are legal under
--    UNIQUE on both Postgres and H2 PG-mode (NULLs distinct) — verified in
--    the Testcontainers suite (port-map trap #4).
-- ---------------------------------------------------------------------------
CREATE TABLE cascade_notifications (
    id                  UUID            NOT NULL,
    cascade_id          UUID            NOT NULL,
    action_id           UUID,                       -- triggering action; NULL for sweeper rows
    depth               INT,
    notification_type   VARCHAR(40)     NOT NULL,
    recipient_member_id UUID,
    recipient_label     VARCHAR(255)    NOT NULL,
    payload             TEXT,                       -- JSON (JsonNodeConverter)
    dedupe_key          VARCHAR(160),               -- NULL legal & non-unique-checked on both vendors
    read_at             TIMESTAMP WITH TIME ZONE,   -- in-app feed
    dispatched_at       TIMESTAMP WITH TIME ZONE,   -- Phase-5 channel watermark
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by          VARCHAR(255)    NOT NULL,
    version             BIGINT          NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_cn_dedupe UNIQUE (dedupe_key)
);

CREATE INDEX idx_cn_recipient ON cascade_notifications(recipient_member_id, read_at);
CREATE INDEX idx_cn_cascade   ON cascade_notifications(cascade_id);
