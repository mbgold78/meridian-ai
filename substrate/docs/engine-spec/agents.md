# Engine Spec — Agent Provenance Registry (`engine.agent`)

**Status:** implementation-ready. Governing ADR: `substrate/docs/adr/ADR-003-agent-registry.md` (Accepted — autonomous, revisable; Mike review pending). Read the ADR first; this spec implements those decisions and does not re-litigate them.
**Port-map slot:** Wave D3 (`port-map.md` §7) — registry + read model **parallel-ok with D1/D2**; gateway enforcement **sequential after contracts 3c**. Migration `V6__agent_registry.sql` (V4=cascades, V5=contracts — both on disk, verified 2026-06-10; **no Flyway out-of-order handling is needed; do not build against that phantom risk**).
**Binding semantics:** port-map §6 `[AGENT-PROVENANCE]`, `[AGENT-STATES]`, `[AGENT-SPEC-UNIFICATION]`, `[EVIDENCE-GROUNDING]`, `[DECISIONLOG-COVERAGE]`; pause/retire/decline-shaped outcomes never error-colored (trap #8); read-model-first; flag-not-invent (§17 is the authoritative UNKNOWN list).
**Fixture truth:** `C:/Users/mbgol/dev/meridian-homepoc/frontend/src/demo/beacon/types.ts` (`BeaconAgent`, `AgentStatus`) + per-subsidiary `agents:` arrays in `.../demo/beacon/subsidiaries/*.ts` (9 files, ~70 agents) + `.../app/demo/beacon/_tabs/OperationsTab.tsx` (render contract). **There is no `agents.ts` file** — any document naming one has drifted. Where this spec says VERBATIM it means byte-identical to those files; copy strings at authoring time and record source path + copy date in a header comment (contracts §14.1 discipline).

Top-level non-negotiables:
1. **Honest verbs.** Every Ops Center verb yields (a) an attributed append-only event, (b) an authoritative state change, (c) a declared enforcement effect (`GATEWAY_BLOCK` | `RECORDED_INTENT`) disclosed in the verb response AND the detail DTO. Substrate never claims to have stopped what it cannot reach. Confirmation of a RECORDED_INTENT control is human-attested only; runtime acknowledgment is a distinct, weaker fact; sighting absence never auto-confirms.
2. **Pause / kill / retire / rejected-review are never errors.** All return HTTP 200; warn/neutral/info tones; no failure vocabulary exists in any agent enum.
3. **`agent_status_events` is append-only** (service discipline + PG trigger). The event log is the audit trail; registration-row state is a rebuildable projection.
4. **Wire vocabulary is the fixture vocabulary**: `live`, `shadow`, `paused`, `intervention-required` — kebab via `@JsonValue`. `intervention-required` is the fixture literal (NOT `in-intervention`). The additive `retired` value is **excluded from fleet lists by default** — the swapped OperationsTab `Record<AgentStatus,…>` pill map crashes on unknown values.
5. **Two-dimensional state**: `operational_mode` is machine state; open interventions are an attention overlay. An in-intervention agent is typically STILL RUNNING; pausing it is a separate evented act.
6. **The mandate is immutable** post-deploy (ADR D5). No code path updates `deployment_criteria`, `criteria_digest`, or `agent_criteria_gates` content after the DEPLOYED event. Change = retire + redeploy with `predecessor_registration_id`.
7. **Degraded mode:** zero orchestrator touchpoints exist in this engine; without the contracts module all verbs are RECORDED_INTENT and the DTO discloses `"enforcement pending gateway"`.
8. **Agents never self-administer.** Lifecycle verbs are REST-only, human-only; no MCP lifecycle tools exist; `actor_type=AGENT` is illegal on lifecycle events (service-enforced).

---

## 1. Configuration keys (`AgentProperties`, prefix `substrate.agents`)

| Key | Default | Meaning |
|---|---|---|
| `substrate.agents.mcp.enabled` | `true` | Registers agent tools on the existing `/mcp` server (requires the contracts MCP host; absent ⇒ tools absent, REST unaffected). |
| `substrate.agents.sighting-bucket-minutes` | `60` | Hourly dedupe bucket for `agent_sightings` (`UNIQUE(agent, channel, bucket_key)`). |
| `substrate.agents.sightings.retention-days` | `null` | Reserved; no purge job in v1 (named first-engagement calibration risk). |
| `substrate.agents.liveness.stale-after-hours` | `72` | STALE threshold when a telemetry channel exists but `heartbeat_expected_minutes` is unset. |
| `substrate.agents.intervention-escalation-days` | `3` | Sweeper dwell before escalating an unresolved intervention to the next oversight position. |
| `substrate.agents.control-confirm-timeout-hours` | `24` | Sweeper dwell before flagging an unconfirmed RECORDED_INTENT control to the oversight chain. |
| `substrate.agents.escalation-renotify-days` / `-limit` | `3` / `5` | Renotify windowing (cascade D5 idiom). |
| `substrate.agents.sweep-cron` | `0 0 * * * *` | Hourly escalation + UNSTRUCTURED human-verify sweep. |
| `substrate.agents.gate-eval.enabled` | `true` | STRUCTURED criteria-gate evaluation pass (rides the contracts monitor scheduler; inert without the contracts module). |

Enforcement mode rides the **existing** `substrate.contracts.enforcement.mode = off | shadow | enforce` — one ladder, not two (ADR D7). Demo seeding rides the global `substrate.demo.seed`. Clock: every engine service takes the Spring `java.time.Clock` bean (S15; DemoPack and tests inject fixed clocks). Period keys for metric samples follow contracts §5.1 conventions (caller-supplied on report; evaluator computes most-recent-closed period).

---

## 2. Module / package layout

```
common/src/main/java/com/meridian/substrate/common/domain/
  AgentRegistration.java  AgentOversightLink.java  AgentStatusEvent.java
  AgentIntervention.java  AgentComplianceReview.java  AgentCriteriaGate.java
  AgentMetricSample.java  AgentThrottleBudget.java  AgentRateCounter.java
  AgentSighting.java  AgentNotification.java
common/src/main/java/com/meridian/substrate/common/domain/enums/
  AgentOperationalMode.java  AgentWireStatus.java  AgentEventType.java
  AgentExecutionLocus.java  AgentControlEffect.java  AgentControlConfirmationState.java
  AgentSightingChannel.java  AgentObservability.java  AgentStaleness.java
  AgentInterventionStatus.java  AgentInterventionTrigger.java  AgentInterventionResolution.java
  AgentReviewKind.java  AgentReviewDecision.java
  AgentGateRuleType.java  AgentGateStanding.java
  AgentReadinessSource.java  AgentSupervisoryModel.java  AgentRetiredReason.java
  AgentNotificationType.java
common/src/main/java/com/meridian/substrate/common/repo/
  AgentRegistrationRepository.java  AgentOversightLinkRepository.java
  AgentStatusEventRepository.java   AgentInterventionRepository.java
  AgentComplianceReviewRepository.java  AgentCriteriaGateRepository.java
  AgentMetricSampleRepository.java  AgentThrottleBudgetRepository.java
  AgentRateCounterRepository.java   AgentSightingRepository.java
  AgentNotificationRepository.java
engine/src/main/java/com/meridian/substrate/engine/agent/
  AgentLifecycleService.java        // deploy + all verbs: locking, fencing, idempotency, outbox (all writes)
  AgentQueryService.java            // fleet/detail/events/signals/attention read DTOs
  AgentProjectionRebuilder.java     // deterministic replay of event log → projections
  AgentInterventionService.java     // open / acknowledge / resolve
  AgentPromotionService.java        // request / execute, review consumption, gateway transit
  AgentLivenessService.java         // sighting recording, attest-liveness, derivations
  AgentControlConfirmationService.java // acknowledge_control (MCP) + confirm-control (REST)
  AgentCriteriaGateService.java     // gate authoring-at-deploy, standing, sweeps
  AgentMetricService.java           // report tools → samples (idempotent upsert)
  AgentEscalationSweeper.java       // @Scheduled, notification-only, idempotent
  AgentRegistryGateImpl.java        // @Primary impl of engine.contract.gateway.AgentRegistryGate
                                    //   (compiled only with contracts present; see §8 wiring note)
  AgentDispatchPreflight.java       // registry preflight + result-quarantine hooks (§14)
  AgentGovernanceChainMirror.java   // DEPLOYED/PROMOTED/RETIRED → engine.decision (§11)
  AgentProperties.java              // @ConfigurationProperties("substrate.agents")
  read/  (response DTO records)
  exception/ AgentConflictException.java  AgentForbiddenException.java
app/src/main/java/com/meridian/substrate/app/api/v1/
  controller/AgentController.java   controller/AttentionController.java   // §13.3 composed feed
  request/   (request records — validation annotations live HERE only)
app/src/main/java/com/meridian/substrate/app/mcp/
  AgentMcpTools.java                // thin adapters onto engine services (§12)
app/src/main/java/com/meridian/substrate/app/demo/
  AgentDemoSeeder.java              // DemoPack event-stream replay (§15)
app/src/main/resources/db/migration/common/V6__agent_registry.sql
app/src/main/resources/db/migration/postgresql/V6_1__agent_registry_postgres.sql
```

Entity conventions (identical to cascade/contracts): extend `BaseEntity` (UUID id, `@Version Long version NOT NULL`, created_at/updated_at, created_by default `system`); FK-twin pattern (writable UUID column + `@JsonIgnore @ManyToOne(LAZY, insertable=false, updatable=false)`); **no Lombok on entities**; JSON via `JsonNodeConverter` → TEXT (port-map §4.5 — jsonb flip is a documented later one-pass migration); soft references (member ids, capability/process/initiative ids, evidence refs) have NO DB FKs — app-enforced, comment in code.

---

## 3. Enums (exact values + wire serialization)

| Enum | Values | `@JsonValue` wire form |
|---|---|---|
| `AgentOperationalMode` | `SHADOW, LIVE, PAUSED, RETIRED` | `shadow`, `live`, `paused`, `retired` |
| `AgentWireStatus` (projection, §5.2) | `LIVE, SHADOW, PAUSED, INTERVENTION_REQUIRED, RETIRED` | `live`, `shadow`, `paused`, **`intervention-required`** (fixture literal), `retired` (additive — Mike-flagged) |
| `AgentEventType` | `DEPLOYED, PROMOTION_REQUESTED, PROMOTED, PROMOTION_REJECTED, PAUSED, RESUMED, THROTTLE_SET, THROTTLE_CLEARED, KILLED, RETIRED, INTERVENTION_OPENED, INTERVENTION_ACKNOWLEDGED, INTERVENTION_RESOLVED, OVERSIGHT_CHANGED, CONTROL_ACKNOWLEDGED, CONTROL_CONFIRMED, CONTROL_VIOLATION_OBSERVED, LIVENESS_ATTESTED, PRINCIPAL_BOUND, PRINCIPAL_REVOKED, CRITERIA_STANDING_CHANGED, DISPATCH` | kebab-case |
| `AgentExecutionLocus` | `SUBSTRATE_MCP, ORCHESTRATOR, CUSTOMER_SYSTEM` | kebab-case |
| `AgentControlEffect` | `GATEWAY_BLOCK, RECORDED_INTENT` | kebab-case |
| `AgentControlConfirmationState` | `NOT_REQUIRED, PENDING, ACKNOWLEDGED, CONFIRMED` | kebab-case |
| `AgentSightingChannel` | `GATEWAY_ACTION, HEARTBEAT, METRIC_REPORT, ATTESTATION, DISPATCH_EVENT` | kebab-case |
| `AgentObservability` | `OBSERVABLE, ATTEST_ONLY` | kebab-case |
| `AgentStaleness` | `FRESH, STALE, NEVER_SEEN, NOT_EXPECTED` | kebab-case |
| `AgentInterventionStatus` | `OPEN, RESOLVED` | kebab-case |
| `AgentInterventionTrigger` | `HUMAN, MONITOR, GATEWAY_VIOLATION, DRIFT_METRIC` | kebab-case |
| `AgentInterventionResolution` | `RESUMED, RECONFIGURED, DEMOTED_TO_SHADOW, RETIRED, NO_ACTION` | kebab-case |
| `AgentReviewKind` | `PROMOTION, PERIODIC, INCIDENT` | kebab-case |
| `AgentReviewDecision` | `APPROVED, REJECTED, NEEDS_MORE_TIME` (REJECTED renders info/neutral — never error) | kebab-case |
| `AgentGateRuleType` | `STRUCTURED, UNSTRUCTURED` | kebab-case |
| `AgentGateStanding` | `UNEVALUATED, MET, AT_RISK, HUMAN_VERIFY_DUE` | kebab-case |
| `AgentReadinessSource` | `READINESS_LENS, OPERATOR_ASSERTED` | kebab-case |
| `AgentSupervisoryModel` | `FULL_AUTO, HUMAN_REVIEW_ABOVE_THRESHOLD, HUMAN_IN_LOOP` | kebab-case |
| `AgentRetiredReason` | `KILLED, RETIRED` | kebab-case |
| `AgentNotificationType` | `AGENT_DEPLOYED, INTERVENTION_OPENED, INTERVENTION_ESCALATED, CONTROL_REQUESTED, CONTROL_UNCONFIRMED, CONTROL_VIOLATION, PROMOTION_REQUESTED, PROMOTION_DECIDED, CRITERIA_AT_RISK, HUMAN_VERIFY_DUE, AGENT_RETIRED` | kebab-case |

Actor typing reuses `ContractActorType` (`HUMAN, AGENT, SERVICE, MONITOR, SYSTEM`). `budget_kind` is a **validated string**, not an enum (contracts D15 precedent): allowlist `ACTIONS_PER_WINDOW, TOKENS_PER_DAY, SCHEDULE_WINDOW, SAMPLING_PCT`. DB stores enum **names** (VARCHAR); kebab-case is JSON-only.

---

## 4. Persistence — `V6__agent_registry.sql` (ANSI-portable) + `postgresql/V6_1__agent_registry_postgres.sql`

Every table includes the base columns:
```sql
id UUID PRIMARY KEY, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
updated_at TIMESTAMP WITH TIME ZONE, created_by VARCHAR(255) NOT NULL DEFAULT 'system',
version BIGINT NOT NULL DEFAULT 0
```
Types restricted to UUID / VARCHAR / TEXT / INT / BIGINT / BOOLEAN / DOUBLE PRECISION / TIMESTAMP WITH TIME ZONE. Projection fields marked ⟲ are rebuildable from `agent_status_events`.

```sql
-- 1. agent_registrations — aggregate root
CREATE TABLE agent_registrations (
  -- [base columns]
  domain_id                 UUID NOT NULL,             -- a Beacon subsidiary IS a Domain (cascade D13)
  agent_key                 VARCHAR(160) NOT NULL,     -- slug identity ('a-pre-visit'); contract_key idiom (ADR-002 D8)
  name                      VARCHAR(255) NOT NULL,
  role_description          VARCHAR(512) NOT NULL,     -- fixture `role`
  capability_id             UUID,                      -- soft ref capabilities (fixture capabilityId)
  execution_locus           VARCHAR(30) NOT NULL,      -- AgentExecutionLocus
  mcp_principal_id          VARCHAR(255),              -- binds Principal(AGENT,…); contract_parties idiom; Phase 5 OIDC hardens
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
  supervisory_model         VARCHAR(40) NOT NULL,      -- API-required (unification posture, NOT provenance — ADR D3); record/display/publish only
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

-- 2. agent_oversight_links — ORDERED escalation chain; a table (not JSON) because it is the
--    notification fan-out + escalation routing target. Rendered "A → B → C". >=1 row at deploy.
CREATE TABLE agent_oversight_links (
  -- [base columns]
  agent_registration_id UUID NOT NULL,
  position              INT NOT NULL,                  -- 1..N
  member_id             UUID,                          -- soft ref
  label                 VARCHAR(255) NOT NULL,
  CONSTRAINT uq_aol_position UNIQUE (agent_registration_id, position)
);
CREATE INDEX idx_aol_agent ON agent_oversight_links(agent_registration_id);

-- 3. agent_status_events — APPEND-ONLY audit source of truth
CREATE TABLE agent_status_events (
  -- [base columns]
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

-- 4. agent_interventions — attention overlay; agent KEEPS operational_mode while one is open
CREATE TABLE agent_interventions (
  -- [base columns]
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

-- 5. agent_compliance_reviews — promotion gate currency (ADR D10). Records reviewer identity,
--    decision, rationale, evidence refs and NOTHING else — review-content semantics are UNKNOWN.
CREATE TABLE agent_compliance_reviews (
  -- [base columns]
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

-- 6. agent_criteria_gates — deploymentCriteria as a runtime gate (ADR D9). IMMUTABLE post-deploy
--    except standing/last_evaluated_at (denormalized standing, contracts §2.6 arming idiom).
CREATE TABLE agent_criteria_gates (
  -- [base columns]
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

-- 7. agent_metric_samples — push-based (ADR-002 D11 stance), idempotent upsert (D13 idiom)
CREATE TABLE agent_metric_samples (
  -- [base columns]
  agent_registration_id UUID NOT NULL,
  metric_key            VARCHAR(128) NOT NULL,         -- e.g. 'prefill_accuracy', 'adherence'
  metric_value          DOUBLE PRECISION NOT NULL,
  period_key            VARCHAR(16) NOT NULL,
  reported_by_principal VARCHAR(255),
  reported_at           TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uq_ams_sample UNIQUE (agent_registration_id, metric_key, period_key)
);

-- 8. agent_throttle_budgets — first-class indexed columns on the gateway hot path (never JSON predicates)
CREATE TABLE agent_throttle_budgets (
  -- [base columns]
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

-- 9. agent_rate_counters — DB-backed window counters (S12 lesson: NEVER in-memory atomics)
CREATE TABLE agent_rate_counters (
  -- [base columns]
  agent_registration_id UUID NOT NULL,
  window_start          TIMESTAMP WITH TIME ZONE NOT NULL,
  action_count          INT NOT NULL DEFAULT 0,
  CONSTRAINT uq_arc_window UNIQUE (agent_registration_id, window_start)
);

-- 10. agent_sightings — telemetry, NOT audit (deliberately separate from agent_status_events:
--     sightings have a retention profile; events are the governance trail — ADR D11)
CREATE TABLE agent_sightings (
  -- [base columns]
  agent_registration_id UUID NOT NULL,
  seen_at               TIMESTAMP WITH TIME ZONE NOT NULL,
  channel               VARCHAR(30) NOT NULL,          -- AgentSightingChannel
  detail                VARCHAR(500),
  bucket_key            VARCHAR(40) NOT NULL,          -- hourly bucket (config §1)
  CONSTRAINT uq_as_bucket UNIQUE (agent_registration_id, channel, bucket_key)
);
CREATE INDEX idx_as_agent_seen ON agent_sightings(agent_registration_id, seen_at);

-- 11. agent_notifications — transactional outbox, SAME tx as the triggering event (ADR-001 D10)
CREATE TABLE agent_notifications (
  -- [base columns]
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
```

`V6_1__agent_registry_postgres.sql` (postgresql dir ONLY — H2 dev stays service-enforced, V2_1/V4_1/V5_1 precedent):

```sql
-- Append-only audit trail
CREATE OR REPLACE FUNCTION agent_status_events_append_only() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'agent_status_events is append-only (%)', TG_OP; END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_ase_append_only BEFORE UPDATE OR DELETE ON agent_status_events
FOR EACH ROW EXECUTE FUNCTION agent_status_events_append_only();

-- At most one OPEN intervention per (agent, source_key) — engine-raised dedupe backstop
CREATE UNIQUE INDEX uq_open_intervention_per_source ON agent_interventions (
  agent_registration_id, COALESCE(source_key, '')
) WHERE status = 'OPEN';

-- One active budget per (agent, kind)
CREATE UNIQUE INDEX uq_active_budget_per_kind ON agent_throttle_budgets (
  agent_registration_id, budget_kind
) WHERE active = TRUE;

-- Principal binding is unique when present (gateway resolution correctness)
CREATE UNIQUE INDEX uq_agent_principal ON agent_registrations (mcp_principal_id)
WHERE mcp_principal_id IS NOT NULL;
```

---

## 5. State machine

### 5.1 Verbs → events → transitions (all through `AgentLifecycleService`: PESSIMISTIC_WRITE on the registration row, idempotency check under the lock, optional `expectedEventSeq` fencing, event append + projection update + outbox rows in ONE transaction — cascade §5.5 mechanics verbatim)

| # | Verb | From → To (`operational_mode`) | Guard | Event |
|---|---|---|---|---|
| M1 | deploy (`POST /agents`) | *(none)* → SHADOW (default) | deploy gate §5.4 | DEPLOYED (payload = full frozen spec snapshot) |
| M2 | deploy with `liveJustification` | *(none)* → LIVE | justification non-blank, recorded | DEPLOYED |
| M3 | promote/request | SHADOW (unchanged) | mode = SHADOW; no open request | PROMOTION_REQUESTED (payload = shadow-window summary: shadow_entered_at, sighting count, latest adherence sample if any) |
| M4 | promote | SHADOW → LIVE | unconsumed APPROVED PROMOTION review newer than the request, else 409 `PROMOTION_REVIEW_REQUIRED`; transits gateway as `actionType=agent.promote` (§8.4) | PROMOTED (payload = consumed review id; review row gets `consumed_by_event_id`) |
| M5 | *(review decided REJECTED)* | SHADOW (unchanged) | reviewer + rationale recorded | PROMOTION_REJECTED — info/neutral rendering, re-requestable |
| M6 | pause | LIVE\|SHADOW → PAUSED | reason mandatory; `mode_before_pause` recorded | PAUSED — **warn semantics, HTTP 200, never error** |
| M7 | resume | PAUSED → `mode_before_pause` | — | RESUMED |
| M8 | throttle / unthrottle | any non-RETIRED (mode unchanged) | budget payload validated | THROTTLE_SET / THROTTLE_CLEARED |
| M9 | kill | any non-RETIRED → RETIRED | reason mandatory; `retired_reason=KILLED` | KILLED |
| M10 | retire | any non-RETIRED → RETIRED | `retired_reason=RETIRED` | RETIRED |
| M11 | intervention open | mode UNCHANGED; `open_intervention_count`+1 | reason mandatory; human, or engine-raised (trigger_kind GATEWAY_VIOLATION / MONITOR; DRIFT_METRIC reserved) | INTERVENTION_OPENED |
| M12 | intervention acknowledge | unchanged | named human | INTERVENTION_ACKNOWLEDGED |
| M13 | intervention resolve | count−1; resolution=DEMOTED_TO_SHADOW additionally LIVE→SHADOW (shadow_entered_at reset); resolution=RETIRED requires a separate kill/retire verb (two events, both attributed) | resolution + note mandatory; human-only | INTERVENTION_RESOLVED |
| M14 | oversight replace | unchanged | ≥1 link; before/after chains in payload | OVERSIGHT_CHANGED |
| M15 | attest-liveness | unchanged | label + evidence | LIVENESS_ATTESTED (+ ATTESTATION sighting) |
| M16 | confirm-control | unchanged | human, evidence refs; a control is pending | CONTROL_CONFIRMED |
| M17 | acknowledge_control (MCP) | unchanged | the ONE write a PAUSED/RETIRED principal retains | CONTROL_ACKNOWLEDGED |
| M18 | bind / revoke principal | unchanged | operator REST | PRINCIPAL_BOUND / PRINCIPAL_REVOKED |

RETIRED is terminal-absorbing: any further verb → 409 `AGENT_TERMINAL` (intervention rows may still be resolved — closing the paperwork on a dead agent is legitimate). Re-deploy = new registration with `predecessor_registration_id`. There is NO amend-criteria verb (ADR D5).

### 5.2 Wire-status projection (pinned by contract test BEFORE the 6a swap)

```
retired                      if operational_mode = RETIRED        (excluded from fleet list by default)
paused                       else if operational_mode = PAUSED
intervention-required        else if open_intervention_count > 0   (fixture literal — NOT 'in-intervention')
shadow | live                else operational_mode
```
Precedence is a projector choice from a fixture that conflates mode and attention — events hold the truth; a different precedence is a projector-only change. **Flagged to Mike** (ADR D4). The four fixture pill keys + this precedence are pinned by `AgentWireStatusProjectionTest`.

### 5.3 Control-effect derivation (per verb response + detail DTO)

| Verb | SUBSTRATE_MCP locus | ORCHESTRATOR locus | CUSTOMER_SYSTEM locus |
|---|---|---|---|
| pause / kill / retire | `GATEWAY_BLOCK` only while the gateway **posture is BLOCKING** — gate bean present AND `substrate.contracts.enforcement.mode=enforce` (registry flip ⇒ gateway denies next governed call; cache invalidated, 30s TTL net). Any other posture is `RECORDED_INTENT` with an honest disclosure: bean ABSENT ⇒ `disclosure="enforcement pending gateway"`; ladder `shadow` (OBSERVE) ⇒ governed calls rowed would-deny + zombie signals, not blocked; ladder `off` (INERT) ⇒ nothing evaluated at the gateway | `RECORDED_INTENT` + no-new-dispatch preflight + result quarantine (§14); in-flight dispatches CANNOT be aborted — disclosed | `RECORDED_INTENT` + outbox notify + ack/confirm tracking |
| throttle | `GATEWAY_BLOCK` via budget rows + rate counters (§8.3) — same posture gate: claimed only while BLOCKING, else `RECORDED_INTENT` with the posture disclosure | `RECORDED_INTENT`; budgets pace substrate-side submissions | `RECORDED_INTENT`; published obligation, MCP-readable |
| resume / promote | immediate — substrate-held state IS the control plane | recorded + notified | recorded + notified |

`confirmationState`: `NOT_REQUIRED` for GATEWAY_BLOCK and for resume/promote; `PENDING` on a RECORDED_INTENT suspension until `ACKNOWLEDGED` (runtime, M17) and/or `CONFIRMED` (human, M16). CONFIRMED does not require ACKNOWLEDGED (a human may verify a runtime that never acked); neither is ever auto-derived from sighting absence.

### 5.4 Deploy gate (`POST /agents` — per-field 400s, structured codes)

Reject unless: `deployedByLabel` non-blank (`AGENT_PROVENANCE_MISSING_DEPLOYED_BY`); `deploymentCriteria` non-blank (`AGENT_PROVENANCE_MISSING_CRITERIA`); `oversightChain` ≥1 ordered entry (`AGENT_PROVENANCE_MISSING_OVERSIGHT`); `supervisoryModel` present (`AGENT_SPEC_MISSING_SUPERVISORY_MODEL`); `executionLocus` present (`AGENT_SPEC_MISSING_EXECUTION_LOCUS`); `agentKey` unique per domain (409 `AGENT_KEY_CONFLICT`); `readinessSource` present iff `agentReadinessScore` present. `deployedAt` is set server-side from the injected clock (= the act). Defaults applied as data: `operationalMode=SHADOW`, `readOnly=true`. Criteria gates are authored in the same request (§9.1); `criteria_digest` computed and frozen. The deploy itself transits the gateway as `actionType=agent.deploy`, HUMAN principal (a contract can guard agent deployment), when the contracts module is present.

### 5.5 Invariants

- **I1** No registration row exists without the four provenance facts + ≥1 oversight link (NOT NULL + gate).
- **I2** `deployment_criteria`, `criteria_digest`, gate content, `deployed_*` never change after DEPLOYED (no code path; rebuilder asserts-equal against the DEPLOYED snapshot, never rewrites).
- **I3** Every event carries actor (type + label + soft member id), `occurred_at` from the Clock, gapless `event_seq` (`UNIQUE` backstop).
- **I4** `agent_status_events` rows are never updated or deleted (repo surface + PG trigger).
- **I5** Pause/kill/retire and intervention verbs return HTTP 200; no agent enum contains error/failure vocabulary; PROMOTION_REJECTED is info-toned.
- **I6** Wire status derives ONLY via §5.2; serialization is exact fixture vocabulary.
- **I7** `last_seen_at`/`last_seen_via` change ONLY from sighting writes (channels §3) — never from registration updates.
- **I8** A RECORDED_INTENT verb response never claims enforcement; a GATEWAY_BLOCK claim is made only when the gateway bean is live.
- **I9** All mutations for one verb commit in ONE transaction: event + projections + outbox (+ review consumption on promote).
- **I10** `event_seq` on the registration equals max(event event_seq) — rebuilder watermark.
- **I11** Lifecycle events never carry `actor_type=AGENT` (service-enforced; MCP exposes no lifecycle tools).
- **I12** Engine-raised interventions dedupe on (agent, source_key): at most one OPEN row (service + V6_1 partial unique).

Repositories: `AgentStatusEventRepository` extends `Repository` (not `JpaRepository`) exposing save/find only — no update/delete in the type system (cascade §4 idiom). `AgentRegistrationRepository` carries the `findByIdForUpdate` PESSIMISTIC_WRITE JPQL + `findByDomainIdAndAgentKey` + `findByMcpPrincipalId`. `AgentRateCounterRepository` carries an atomic-increment `@Modifying` JPQL (`UPDATE … SET action_count = action_count + 1 WHERE …`) + insert-on-absent with unique-violation retry-as-increment.

---

## 6. Honest control mechanics (operations core)

1. **Disclosure before the click:** `GET /agents/{key}` carries `controlEffects: {pause: {...}, kill: {...}, throttle: {...}}`, each `{effect, disclosure}` derived per §5.3. The UI renders what a verb will actually do.
2. **Disclosure after the click:** every verb response returns `{status, enforcement: {effect, confirmationState, disclosure}}`. Scripted operators and Testers get the same honesty as the UI (acceptance-must-be-user-runnable).
3. **"Paused — unconfirmed at runtime"** renders until CONTROL_CONFIRMED for RECORDED_INTENT suspensions; "no activity observed since pause (N days)" may render as a clearly-labeled weak positive, never as confirmation.
4. **Zombie signals.** Any sighting of a PAUSED agent: record the sighting (it is true), deny the governed action (enforce mode), append `CONTROL_VIOLATION_OBSERVED`, raise/refresh an engine intervention (trigger GATEWAY_VIOLATION, source_key `active-while-paused`) and an `ACTIVE_WHILE_PAUSED` signal to the oversight chain. After kill/retire: same flow with source_key `seen-after-kill` ⇒ `SEEN_AFTER_KILL`, the highest-urgency signal class (destructive).
5. **Product copy (binding, ships with the feature — ADR D6):** *"Every agent has a registration — substrate grants no agent write surface without one. Pause, throttle, and kill are enforced absolutely on everything that transits substrate; enforced at dispatch and at result-apply for substrate-initiated agent work; and published + acknowledgment- and confirmation-tracked for external runtimes, where non-adherence is an immediate, audited signal."* Never "all agent actions everywhere are blocked."

---

## 7. REST API (app module, `/api/v1`, `{data, meta, errors}` envelope; `{idOrKey}` resolves UUID first, then `(domain, agent_key)`)

### 7.1 Read (built FIRST — phase 6a)

| Endpoint | Params | Returns |
|---|---|---|
| `GET /agents` | `domainId!`, `status?`, `includeRetired?=false` | `AgentFleetRow[]` (§7.3) |
| `GET /agents/{idOrKey}` | — | `AgentDetail` (§7.3) |
| `GET /agents/{idOrKey}/events` | `page?`, `size?` (event_seq asc) | `AgentEvent[]` — the audit timeline |
| `GET /agents/{idOrKey}/sightings` | `since?` | `AgentSighting[]` |
| `GET /agents/{idOrKey}/gates` | — | gate rows + standing |
| `GET /agents/{idOrKey}/metrics` | `metricKey?`, `since?` | sample rows |
| `GET /agents/signals` | `domainId!` | `AgentSignals` (§13.1) — severs the S6 fleet stats |
| `GET /agents/attention` | `domainId!` | `AttentionItem[]` (§13.2) |
| `GET /agents/feed` | `domainId!`, `limit?` | cross-fleet recent events + sightings merged at read (Ops Center live panel) |
| `GET /agents/notifications` | `recipientId!`, `unreadOnly?` | outbox feed + mark-read sibling `POST /agents/notifications/{id}/read` |

### 7.2 Write (operator-grade; every body REQUIRES `idempotencyKey`; `expectedEventSeq` optional → 409 `AGENT_STALE_STATE` with current `AgentDetail` in envelope `data`)

| Endpoint | Body highlights | Success |
|---|---|---|
| `POST /agents` | full deploy spec: agentKey!, name!, roleDescription!, executionLocus!, capabilityId?, deployedByLabel! (+memberId?), deploymentCriteria!, criteriaGates![{gateText!, ruleType!, predicate?, cadence?}], oversightChain![{label!, memberId?}…], supervisoryModel!, targetProcessId?, targetStepIds?, agentReadinessScore?+readinessSource?, requiredSystemAccess?, requiredDataAccess?, initiativeId?, readOnly?, scheduleWindow?, samplingPct?, mcpPrincipalId?, liveJustification?, predecessorRegistrationId? | 201 `AgentDetail` |
| `POST /agents/{idOrKey}/pause` | `{reason!}` | 200 + enforcement block |
| `POST /agents/{idOrKey}/resume` | `{}` | 200 |
| `POST /agents/{idOrKey}/throttle` | `{budgetKind!, limitValue?, windowMinutes?, note?}` / `DELETE`-shaped `…/throttle/{kind}` clears | 200 |
| `POST /agents/{idOrKey}/kill` | `{reason!}` | 200 (never error-shaped) |
| `POST /agents/{idOrKey}/retire` | `{reason!}` | 200 |
| `POST /agents/{idOrKey}/promote/request` | `{}` | 200 |
| `POST /agents/{idOrKey}/promote` | `{}` | 200, or 409 `PROMOTION_REVIEW_REQUIRED` |
| `POST /agents/{idOrKey}/reviews` | `{reviewKind!, requestedByLabel!}` then `POST /agents/reviews/{id}/decide {decision!, rationale!, evidenceRefs?}` | 200/201 |
| `POST /agents/{idOrKey}/interventions` | `{reason!, evidenceRefs?}` | 201 |
| `POST /agents/interventions/{id}/acknowledge` | `{}` | 200 |
| `POST /agents/interventions/{id}/resolve` | `{resolution!, resolutionNote!}` | 200 |
| `POST /agents/{idOrKey}/oversight` | `{chain![{label!, memberId?}…]}` (full replacement, before/after evented) | 200 |
| `POST /agents/{idOrKey}/attest-liveness` | `{statement?, evidenceRefs?}` | 200 |
| `POST /agents/{idOrKey}/confirm-control` | `{evidenceRefs!, note?}` | 200 |
| `POST /agents/{idOrKey}/principal` | `{mcpPrincipalId!}` / `DELETE …/principal` revokes | 200 |

Error codes: `AGENT_STALE_STATE`, `AGENT_ILLEGAL_TRANSITION`, `AGENT_TERMINAL`, `AGENT_KEY_CONFLICT`, `PROMOTION_REVIEW_REQUIRED` (409); `AGENT_NOT_FOUND` (404); deploy-gate 400 codes §5.4; gateway denials surface as 409 `AGENT_GATE_DENIAL` (§8.5). `GlobalExceptionHandler` gains two additive mappings (`AgentConflictException→409` with current detail in `data`, `AgentForbiddenException→403`). **A pinned contract test asserts pause/kill/retire → 200 through `ResponseEnvelopeAdvice` and that no agent enum value serializes to anything containing "error"/"fail".** Actor identity: PersonaContext headers (S9 spoofable, accepted) → OIDC Phase 5, no schema change.

### 7.3 DTO shapes (BeaconAgent superset — the S5 swap is an adapter, not a redesign)

```jsonc
// AgentFleetRow — 1:1 with BeaconAgent so OperationsTab swaps with a thin adapter
{ "id": "a-pre-visit",                       // = agent_key (fixture parity); registrationId additive
  "registrationId": "uuid",
  "name": "Pre-Visit Intake Assistant", "role": "Pre-fills intake forms from prior visits",
  "status": "live",                          // §5.2 projection, fixture vocabulary
  "recentActivity": "…",                     // DERIVED: latest sighting detail, else latest lifecycle event
                                             //   ("Paused by Compliance Officer, May 30"), else "No activity observed" — never blank, never invented
  "adherence": 96,                           // NULLABLE — latest 'adherence' metric sample; null until data exists
  "capabilityId": "eng-intake",
  "deployedBy": "Curasync RCM team", "deployedAt": "2025-09-14T00:00:00Z",
  "oversightChain": ["Curasync RCM Lead", "Curasync COO"],   // ordered labels
  // additive:
  "executionLocus": "customer-system", "supervisoryModel": "human-in-loop",
  "openInterventions": 1, "eventSeq": 7 }

// AgentDetail = AgentFleetRow + deploymentCriteria (CLICK-THROUGH ONLY — verbatim [AGENT-PROVENANCE] render
// rule: never the row line) + spec fields + criteriaGates[{gateText, ruleType, standing, lastEvaluatedAt}] +
// budgets[] + openInterventions[] + promotion {requestedAt, latestReview} + retired {at, reason, byLabel} +
// controlEffects {pause,kill,throttle: {effect, disclosure}} +
// enforcement {pendingVerb, confirmationState, requestedAt, acknowledgedAt, confirmedAt, confirmedBy} +
// liveness {lastSeenAt, lastSeenVia, lastAttestedAt, lastAttestedBy, observability, staleness,
//           heartbeatExpectedMinutes} + recentEvents[5] + lineage {predecessorRegistrationId}
```

**UI rules binding for the swap (6a):** status pill = governance state; liveness renders as a separate line ("Last seen 2h ago via gateway" / "Not observable — last attested 12 days ago by RCM Lead") — never a fabricated green dot. Null `adherence` renders an em-dash (adapter change lands WITH the swap or rows break — the fixture type requires the field). The provenance line always renders (provenance is structural post-swap). The fixture "Agents active" stat card maps to `agentsTotal` (it counts the whole non-retired fleet — fixture behavior preserved; renaming the card is a post-swap UI question, flagged).

---

## 8. Gateway integration (phase 6c — sequential after contracts 3c)

### 8.1 SPI seam (ADR D7)

`engine.contract.gateway.AgentRegistryGate` — interface + `NoopAgentRegistryGate` default bean (always allow-unflagged) — is **declared by the contracts gateway chunk** (coordinate at dispatch: it ships with contracts 3c, since `engine.contract` does not exist on disk yet). `engine.agent` contributes `AgentRegistryGateImpl` as `@Primary` (guarded `@ConditionalOnClass(ContractGateway.class)` so the agents module compiles/deploys without contracts). `ContractGateway.evaluate` invokes the gate for every `PrincipalType.AGENT` action BEFORE clause-guard evaluation; the gate's posture is built-in and non-removable (waive_breach D16 idiom). Fail posture inherits ADR-002 D5: gate-evaluation failure ⇒ fail-closed for AGENT principals.

### 8.2 Resolution + decision matrix (single ladder: `substrate.contracts.enforcement.mode`)

```
resolve(principal.id):
  1. agent_registrations.mcp_principal_id match  -> REGISTERED path (8.3)
  2. contract_parties.mcp_principal_id match     -> PARTY path: party-scoped contract verbs for OWN party allowed;
                                                    anything else -> UNREGISTERED path   (P19 carve-out — never brick a counterparty)
  3. neither                                     -> UNREGISTERED path: reads + check_action always allowed;
                                                    governed writes: shadow -> ALLOW_WITH_FLAG(AGENT_UNREGISTERED),
                                                    enforce -> DENY; EVERY touch emits a signals row ("unknown agent knocking")
```

### 8.3 REGISTERED path checks (in order; DENY in enforce / WOULD_DENY in shadow; all rowed into `contract_enforcement_decisions` — no parallel log)

1. `operational_mode ∈ {PAUSED, RETIRED}` ⇒ DENY `AGENT_CONTROL_SUSPENDED` + GATEWAY_ACTION sighting + zombie flow (§6.4). Exception: the `acknowledge_control` tool is allowed (the compliant act).
2. `operational_mode = SHADOW` ⇒ only observation-scope tools (`agent_heartbeat`, `report_agent_activity`, `report_metric`, `attest_commitment`, `record_commitment_progress`, `acknowledge_control`) + reads; all other governed writes ⇒ DENY `AGENT_SHADOW_SCOPE`. P11 observation-only trial, structural.
3. `read_only = true` ⇒ mutating actionTypes beyond the observation scope ⇒ DENY `AGENT_READ_ONLY`.
4. Budgets: active `ACTIONS_PER_WINDOW` budget ⇒ atomic increment of the current `agent_rate_counters` window inside the check transaction; over limit ⇒ DENY `AGENT_BUDGET_EXHAUSTED` + signal at exhaustion. (SCHEDULE_WINDOW / TOKENS_PER_DAY / SAMPLING_PCT: schema-ready rows, NO evaluator in v1 — a configured row of those kinds is a published obligation, never a gate check; EXTENSION POINT agent-10, consistent with ADR-003 D12 and §15 row 10. *Spec correction 2026-06-11: this row previously promised `SCHEDULE_WINDOW ⇒ DENY OUTSIDE_SCHEDULE_WINDOW`, contradicting D12, §15 row 10 and the implementation — only ACTIONS_PER_WINDOW is evaluated; no `OUTSIDE_SCHEDULE_WINDOW` reason code exists anywhere.*)
5. Open intervention (LIVE) ⇒ ALLOW + flag `AGENT_IN_INTERVENTION` — never brick the telemetry that diagnoses the problem.
6. Every allowed governed call records a GATEWAY_ACTION sighting (liveness for free).

Registry-state reads are cached beside `GuardCache` (same invalidation events + 30s TTL; invalidated by every lifecycle verb). The contracts p50 < 5 ms hot-path budget now covers both lookups — a perf assertion joins the Testcontainers suite.

### 8.4 Built-in action guards

`agent.promote` and `agent.deploy` are governed actionTypes (the contracts 3c acceptance already seeds an `agent.promote` DENY guard — the designed contracts↔agents demo beat). A built-in non-removable guard denies AGENT principals on both (agents never operate lifecycle, including their own — defense in depth atop §7's REST-only surface).

### 8.5 Wire shapes

Denials reuse ADR-002 §4.5 exactly: REST 409 envelope with `ApiError{code:"AGENT_GATE_DENIAL", message:"<reason code + agent_key + decisionId>"}`; MCP `isError=true` with structured `{"code":"AGENT_GATE_DENIAL","reason":"AGENT_CONTROL_SUSPENDED",...}` so runtimes adapt rather than crash. Never 5xx.

---

## 9. deploymentCriteria as a runtime gate (`agent_criteria_gates`)

### 9.1 Authoring (at deploy ONLY — frozen with the mandate)

The deploy request carries `criteriaGates[]`: each row is one mandate clause. `STRUCTURED` rows carry a `predicate` in **the contracts TinyDsl, verbatim** (contracts spec §4.4; metric leaves resolve the latest complete-period value from `agent_metric_samples`). Example — fixture "target 90% pre-fill accuracy": `{"metric":"prefill_accuracy","gte":0.90}`, cadence MONTHLY. Un-compilable prose ships `UNSTRUCTURED` (predicate NULL) — "zero PHI leakage" (a-msg-router) is the canonical example; human-judgment criteria are legitimate, **flagged not faked**. `criteria_digest` = SHA-256 over canonical serialization of (deployment_criteria + ordered `{gateText, ruleType, predicate, cadence}` rows); pinned digest-vector test before the chain mirror enables.

### 9.2 Evaluation (signal-only — NEVER auto-pause/auto-kill; cascade D5: the system never speaks for a human in an accountability chain)

- STRUCTURED gates evaluate on the contracts monitor scheduler cadence pass (shared ShedLock sweep; `substrate.agents.gate-eval.enabled`): predicate true ⇒ standing MET; false ⇒ standing AT_RISK + bounded `CRITERIA_STANDING_CHANGED` event + `CRITERIA_AT_RISK` outbox/attention row to oversight position 1 (deduped per gate+period) **suggesting** intervention.
- **INDETERMINATE (missing samples, unresolvable metric) NEVER marks AT_RISK** (contracts D10 hard invariant carried over) — standing stays/returns UNEVALUATED and a needs-input signal distinct from green is emitted.
- UNSTRUCTURED gates emit recurring `HUMAN_VERIFY_DUE` on their cadence via the agent engine's own sweeper (works without the contracts module) — a gate can be weak but never silently inert (no-silent-zero-yield).
- Without the contracts module: STRUCTURED gates stand UNEVALUATED and the signals feed carries the structured/unstructured/unevaluated `gateCoverage` ratio — degradation is visible, never silent.
- Because the DSL is shared, a future contract guard can cite an agent gate (metric leaf over agent metrics) — recorded as the designed composition path, NOT built (EXTENSION POINT agent-5).

`AgentMetricService.report(...)`: idempotent upsert on `(agent, metric_key, period_key)`; records a METRIC_REPORT sighting; `adherence` is just `metric_key='adherence'` — severing the S6 fixture-stamped number.

---

## 10. Liveness (`AgentLivenessService`)

`last_seen_at`/`last_seen_via` update ONLY from sighting writes: GATEWAY_ACTION (every allowed governed call, §8.3.6), HEARTBEAT (MCP `agent_heartbeat`), METRIC_REPORT, ATTESTATION (REST attest-liveness, M15), DISPATCH_EVENT (mandate-bearing dispatch activity, §14). Sightings dedupe per hourly bucket (`UNIQUE(agent, channel, bucket_key)`); duplicate-key violations swallowed as no-op. Derivations (read-time, in the DTO):

- `observability`: `OBSERVABLE` if a telemetry channel exists (bound principal, or ORCHESTRATOR locus with dispatch wiring), else `ATTEST_ONLY` (CUSTOMER_SYSTEM without MCP binding).
- `staleness`: `NOT_EXPECTED` if no channel and no expectation; `NEVER_SEEN` if channel exists and no sighting ever; `STALE` if `heartbeat_expected_minutes` set and exceeded, or unset-but-channel-exists and `stale-after-hours` exceeded; else `FRESH`.

Registration `updated_at` is NEVER conflated with liveness (I7). Sightings are telemetry with retention (config reserved, §1) — deliberately not audit.

---

## 11. Hash-chain mirroring (`AgentGovernanceChainMirror`, bounded — ADR D14)

Mirrored events: **DEPLOYED** (canonical JSON includes `criteria_digest`), **PROMOTED**, **RETIRED/KILLED** (includes `retired_reason`). Mechanism: via `DecisionLogService.appendDecision` under its existing per-domain append lock — **never write the chain directly**. Chain inputs reference `agent_key`, domain id, stable actor id, event type, occurred_at, digest — never display names ([DECISIONLOG-COVERAGE]). The resulting hash is written back to `agent_status_events.decision_log_hash` (cross-navigation). **Coordination rule (binding):** the shared mirror-entry typing (canonical-JSON layout + additive `DecisionType` values `AGENT_DEPLOYED`, `AGENT_PROMOTED`, `AGENT_RETIRED` alongside the contracts D14 set) lands in `engine.decision` by whichever engine implements first — `engine.contract` is not on disk yet; if D3-6b lands before contracts 3b, this engine lands the shared mechanism and contracts reuses it. Never fork it. A pinned hash-vector test (known event sequence ⇒ pinned hashes; `GET /decision-log/verify` stays green; concurrent agent + design-decision appends do not fork) lands BEFORE enabling (port-map trap #3).

---

## 12. MCP tools (phase 6c, same `/mcp` server — no second server)

| Tool | Kind | Params → behavior |
|---|---|---|
| `list_agents` | read (ungoverned) | `domain_id?, status?` → fleet rows (§7.3) |
| `get_agent` | read | `agent_key!` → full detail incl. gates + standing |
| `get_my_registration` | read | caller-identity-resolved: my mandate (verbatim), my gates + standing, my status, my oversight chain. Sibling of `get_obligations_for_principal` — the P19 keystone for agents. |
| `get_my_control_state` | read | mode + active budgets + schedule window — the P11 client-side self-limiting read |
| `agent_heartbeat` | governed write | `activity_summary?` → HEARTBEAT sighting (denied-but-recorded when suspended ⇒ zombie flow §6.4) |
| `report_agent_activity` | governed write | `activity_note?, metrics?[{metric_key, value, period_key}]` → sightings + samples |
| `acknowledge_control` | governed write | the ONE write a PAUSED/RETIRED principal retains → CONTROL_ACKNOWLEDGED |

Plus: the existing contracts `report_metric` resolves agent principals where bound. **No deploy/promote/pause/resume/throttle/kill/retire/intervention tools exist** — lifecycle is REST/human-only (I11; §8.4 guards as defense in depth). App-side classes are thin adapters; logic lives in engine services (contracts §8.1 hosting).

---

## 13. Signals, attention, notifications, frontend wiring

### 13.1 `GET /agents/signals?domainId` — severs fixture seam S6; explicit zeros ALWAYS present (no-silent-zero-yield)

```jsonc
{ "agentsTotal": 5, "agentsLive": 2, "agentsShadow": 1, "agentsPaused": 1,
  "agentsInterventionRequired": 1,                       // wire-status projection counts
  "interventions": {"open": 1, "oldestOpenedAt": "…"},
  "unconfirmedControls": [{"agentKey":"…","verb":"pause","requestedAt":"…","confirmationState":"pending"}],
  "controlViolations":  [{"agentKey":"…","kind":"ACTIVE_WHILE_PAUSED|SEEN_AFTER_KILL","lastSeenAt":"…"}],
  "liveness": {"stale": 0, "neverSeen": 0, "attestOnly": 0},
  "promotionsPending":  [{"agentKey":"…","requestedAt":"…"}],
  "budgetsExhausted":   [],
  "gateCoverage": {"structured": 3, "unstructured": 1, "unevaluated": 0}   // degradation visible, never silent
}
```
Computed at read from existing tables — no signal table. The OperationsTab fleet strip swaps to this: "Agents active" card ← `agentsTotal`, "Agents in intervention" ← `agentsInterventionRequired` (open-findings count stays on the findings API).

### 13.2 `GET /agents/attention` — AttentionItem-shaped (kind/tone/headline/since, exactly the cascade shape)

Tone mapping: `agent-in-intervention`, `control-violation` → **destructive** (leader-attention, resolved queue #21); `agent-paused`, `control-unconfirmed`, `criteria-at-risk` → warn; `human-verify-due` → info.

### 13.3 Composed attention feed (app module)

`AttentionController` (`GET /api/v1/attention?domainId`) merges `CascadeQueryService.attention` + `AgentQueryService.attention` into one list, sorted destructive-first then by `since`. **[CASCADE-PROPAGATION]'s "decline-pending cascades render beside in-intervention agents" is a single API call.** Engines stay decoupled — read-side composition, zero engine-to-engine writes; no cascade is ever auto-authored from an intervention (queue #24 gate).

### 13.4 Notifications + escalation

Outbox rows written in the SAME tx as the triggering event (ADR-001 D10; architecture assertion: no `@TransactionalEventListener` in `engine.agent`). Fan-out: INTERVENTION_OPENED → full oversight chain; CONTROL_REQUESTED (RECORDED_INTENT verbs) → chain position 1; CRITERIA_AT_RISK / HUMAN_VERIFY_DUE → position 1; CONTROL_VIOLATION → full chain (destructive). `AgentEscalationSweeper` (hourly, notification-only, NEVER a state transition): intervention OPEN past `intervention-escalation-days` → next chain position, walking upward per renotify window (dedupe_key `escalate:{interventionId}:{position}:{n}`, UNIQUE backstop); RECORDED_INTENT control unconfirmed past `control-confirm-timeout-hours` → `CONTROL_UNCONFIRMED` to the chain (dedupe_key `unconfirmed:{agentId}:{eventId}:{n}`).

### 13.5 OperationsTab swap (S5 seam — 6a)

Swap at the documented `BEACON_PORTFOLIO` factory seam: fixture `agents` arrays → `useAgents(domainId)` + thin `AgentFleetRow → BeaconAgent` adapter (id ← agentKey; status passes through — vocabulary identical; null adherence → em-dash; recentActivity ← derived field). Stat strip → §13.1. Provenance line always renders. `retired` never reaches the pill map (default exclusion). Verb affordances (pause/kill buttons, 409-refresh pattern, "unconfirmed at runtime" line, liveness line) are additive detail-panel work shipping with 6b — **no Ops authoring/verb UI ships ahead of its API**. Beacon fixture excision at live-swap (trap #9).

---

## 14. Orchestrator seam (consumed; ZERO touchpoints inside this engine)

This engine has **no orchestrator coupling at all** — stricter than cascade's one endpoint. Two integration hooks live OUTSIDE the engine, in the components that already own dispatching:

1. **Dispatch preflight + identity binding (ADR D2):** any substrate component dispatching on a registered agent's mandate (Phase 4: cascade-level execution, finding curation as agent X) calls `AgentDispatchPreflight.beforeSubmit(agentKey)` — PAUSED/RETIRED ⇒ refuse new submission (a governance outcome, not an error); ACTIONS_PER_WINDOW budgets pace submissions (composing with `check_budget_for_task`). The context pack includes `agent_key` + the instruction to present `X-MCP-Principal: <mcp_principal_id>` on substrate calls. On submit, a `DISPATCH` event rows onto the agent's log with the provenance digest (`{taskId, model, eventsDigest, requestedAt, landedAt}` — the `cascade_drafts.suggestion_provenance` shape) + a DISPATCH_EVENT sighting. Ephemeral workers NOT on a mandate are never registered (two identities, never conflated).
2. **Result quarantine (ADR D8):** the `apply_dispatch_result` PEP (ADR-002 D2 #3) consults the registry and **refuses to apply results attributable to a killed/paused registration** (DENY-shaped decision row + CONTROL_VIOLATION-class signal). In-flight dispatches cannot be aborted (the five-tool seam has no cancel — stated plainly in DTO disclosure); kill = no new submissions + no re-entry of in-flight output. Extending the seam with a cancel tool is a flagged Mike follow-up — do NOT wire unilaterally.

The orchestrator never learns the registry exists. Degraded mode (`substrate.orchestrator.enabled=false`): nothing in this engine changes; the preflight/quarantine hooks simply never fire.

---

## 15. DemoPack seed (`AgentDemoSeeder`, gated `substrate.demo.seed=true`, isDemo domain only)

Seeds by driving `AgentLifecycleService` + `AgentInterventionService` + `AgentMetricService` with a fixed injectable clock — **never row inserts** (cascade D16: the seed is a standing state-machine integration test). Source of truth: the subsidiary fixture FILES (`subsidiaries/*.ts`, 9 files, ~70 agents — enumerate from the files at seeder-authoring time; record source path + copy date in a header comment). Per agent: deploy with fixture provenance (deployedAt back-dated via the fixed clock = fixture date); promote the `live` ones; pause the `paused` ones with the fixture reason verbatim; open interventions for `intervention-required` with the fixture drift narrative as reason; seed fixture `recentActivity` as an ATTESTATION-channel sighting detail and fixture `adherence` as an `adherence` metric sample — the engine then computes what the fixture stamped.

**Pinned canonical vector — Curasync (5 agents, all four wire statuses):**

| agent_key | seed stream | end wire status |
|---|---|---|
| `a-pre-visit` | deploy(SHADOW) → promote | `live` |
| `a-eligibility` | deploy(SHADOW) → promote | `live` |
| `a-coding` | deploy(SHADOW) — fixture: "shadow-mode review for 12 weeks before promotion" | `shadow` |
| `a-noshow` | deploy → promote → intervention open ("Drift detected — false-positive rate up 9 pts") — **still LIVE underneath** (two-dimensional model) | `intervention-required` |
| `a-msg-router` | deploy → promote → pause ("Paused 2026-04-19 pending Compliance review of PHI-routing change") | `paused` |

Promotion seeding records an APPROVED PROMOTION review (reviewer = oversight position 1 label, rationale "seed: fixture agent operating live") before each promote — the gate invariant stays clean. Fixture agents lacking provenance (revsync etc. — the majority) get synthesized provenance **flagged seed-only fiction** (`notes` carries `SEED-FICTION:` prefix; cascade D16 precedent; trap #9 — demo data never ships in customer builds). Criteria gates: compile the polished Curasync mandates (a-pre-visit STRUCTURED `prefill_accuracy ≥ 0.90`; a-msg-router STRUCTURED `routing_accuracy ≥ 0.95` + UNSTRUCTURED "zero PHI leakage"); unpolished agents get one UNSTRUCTURED gate from their role text (seed fiction, flagged). Seeded wire statuses must reproduce each subsidiary's OperationsTab stat strip exactly (counts parity = 6a acceptance). Seeder idempotent on re-boot (keyed `(domain, agent_key)`); `substrate.demo.seed=false` ⇒ zero agents.

---

## 16. Build order + test plan

### 16.1 Sequencing (port-map D3 row: 6a/6b parallel-ok with D1/D2; 6c sequential after contracts 3c)

| Phase | Scope | User-runnable acceptance (Tester-runnable, real invocations — no report-only completions) |
|---|---|---|
| **6a** | V6 + V6_1, entities, enums, repos, deploy gate + read-only lifecycle core, read API (§7.1), DTO adapter, `AgentDemoSeeder`, OperationsTab swap | `gradlew :app:bootRun` (dev, seed=true) → `curl /api/v1/agents?domainId=<curasync>` returns the 5 Curasync agents content-equivalent to fixture incl. `intervention-required`; `curl /api/v1/agents/a-msg-router` shows deploymentCriteria + ordered oversight chain + always-present provenance; deploy missing oversightChain → 400 `AGENT_PROVENANCE_MISSING_OVERSIGHT`; `/agents/signals` matches the fixture stat strip; seed=false → empty data array + explicit-zero signals; screenshot harness captures the swapped tab. |
| **6b** | Full verb set + events read + outbox + interventions + promotion/reviews + liveness/attestation + criteria-gate rows + UNSTRUCTURED sweeper + signals/attention + escalation sweeper + chain mirror (pinned vector FIRST) + projection rebuilder | `curl POST …/pause {reason}` → 200 with `enforcement.effect="recorded-intent"` AND `disclosure="enforcement pending gateway"` (**the 6b→6c window disclosure is an acceptance item, not a note**); events feed shows PAUSED with actor; `…/promote` → 409 `PROMOTION_REVIEW_REQUIRED`; record APPROVED review → promote 200, mode live; kill → 200, `retired` excluded from default fleet list, `?includeRetired=true` shows it; `/decision-log/verify` stays green incl. the agent entries; injectable-clock escalation demo (intervention dwell → position-2 notification, exactly once); rebuilder reproduces projections byte-equal. |
| **6c** | `AgentRegistryGateImpl` + budgets/counters + MCP agent tools + GATEWAY_ACTION sightings + zombie signals + dispatch preflight/quarantine hooks, shipped under the shared shadow ladder | MCP heartbeat as paused agent (shadow mode) → sighting recorded + WOULD_DENY rowed + `ACTIVE_WHILE_PAUSED` in signals; flip `substrate.contracts.enforcement.mode=enforce` → governed write as paused agent denied with structured `AGENT_GATE_DENIAL` isError payload; kill then heartbeat → `SEEN_AFTER_KILL`; unregistered principal write → flagged (shadow) / denied (enforce) + signals row; principal matching a contract party → party-scoped verb allowed; `get_my_registration` returns the caller's verbatim mandate; throttle 2/window then third call → `AGENT_BUDGET_EXHAUSTED`; `agent.promote` DENY guard (contracts 3c seed) blocks promote in enforce. |

`gradlew build` green at every boundary. **Testcontainers-Postgres in CI is the acceptance gate; H2-green is never acceptance** (no Docker on this dev machine — do not claim local verification of PG-only behavior: trigger, partial uniques, FOR UPDATE, counter increments).

### 16.2 Test plan

1. **State-machine table tests** (unit): every legal M1–M18; every illegal combination (verbs on RETIRED → `AGENT_TERMINAL`; resume when not paused; promote without review / with consumed review / with REJECTED review; second deploy on same key → 409; intervention resolve without note); reason-mandatory CHECKs; DEMOTED_TO_SHADOW resets shadow_entered_at.
2. **Wire-status projection contract test** (pinned BEFORE the 6a swap): precedence table retired > paused > intervention-required > shadow/live; serialization literals equal the four fixture pill keys + `retired`; in-intervention LIVE agent projects `intervention-required` while `operationalMode=live` in the detail DTO.
3. **Fixture vectors**: the §15 Curasync table end-states pinned (modes, wire statuses, event counts, verbatim reasons); per-subsidiary stat-strip parity across all nine files.
4. **Idempotency + fencing**: same idempotencyKey replay → original 200, no new event; stale `expectedEventSeq` → 409 `AGENT_STALE_STATE` with current detail in `data`.
5. **Concurrency** (Testcontainers): simultaneous pause + kill → lock serializes, one wins, loser 409s or replays; concurrent promote with one APPROVED review → exactly one PROMOTED, review consumed once; concurrent engine-raised interventions same source_key → one OPEN row (partial-unique backstop fires); concurrent rate-counter increments lose no counts.
6. **Append-only** (Testcontainers): direct JDBC UPDATE/DELETE on `agent_status_events` raises from the V6_1 trigger; event repo exposes no mutators (ArchUnit).
7. **Rebuilder determinism**: seed → snapshot projections → wipe projection fields → rebuild → field-equal; immutable deploy fields assert-equal against the DEPLOYED snapshot, never rewritten; drift-detection green after every continuation vector.
8. **Honest-verb contract test** (pinned): pause/kill/retire → 200 through `ResponseEnvelopeAdvice`, `errors=[]`; every verb response carries `enforcement.effect` + `confirmationState`; RECORDED_INTENT pause renders confirmationState `pending`; MCP acknowledge → `acknowledged`; human confirm → `confirmed`; sighting absence flips NOTHING; no agent enum serializes error/failure vocabulary.
9. **Liveness derivation table**: channel × expectation × elapsed ⇒ observability/staleness matrix incl. NOT_EXPECTED never-stale and updated_at-is-not-liveness; sighting bucket dedupe.
10. **Zombie flow**: paused + heartbeat → sighting + violation event + ACTIVE_WHILE_PAUSED intervention (dedupe: second heartbeat refreshes, no second OPEN row); killed + gateway call → SEEN_AFTER_KILL destructive signal.
11. **Criteria gates**: TinyDsl reuse (no second evaluator class — ArchUnit asserts `engine.agent` contains no DSL implementation); MET/AT_RISK flips evented once per standing change; INDETERMINATE never AT_RISK (property-style across evaluator outcomes); UNSTRUCTURED recurring HUMAN_VERIFY_DUE; gateCoverage visible with contracts module absent; criteria_digest pinned vector.
12. **Gateway suite** (6c, Testcontainers): resolution order incl. party carve-out and unregistered policy per mode; suspended-deny + acknowledge_control allowed; shadow scope; read_only; budget exhaustion; fail-closed on gate exception; decision rows land in `contract_enforcement_decisions`; hot-path perf assertion (p50 budget with registry lookup).
13. **Hash-chain**: pinned vector (deploy→promote→kill sequence ⇒ pinned hashes incl. criteria_digest in the DEPLOYED entry); verify green; concurrent appends don't fork (trap #3).
14. **Outbox + sweeper**: same-tx visibility; fan-out table per §13.4; escalation windows exactly-once per dedupe key; renotify limit honored; no `@TransactionalEventListener` in engine.agent (architecture assertion).
15. **Quarantine**: apply_dispatch_result for a killed agent's task → refused + decision row; preflight refuses submission for paused agent.
16. **Degraded/whole-or-in-part**: contracts module absent → boot green, all verbs RECORDED_INTENT with disclosure, STRUCTURED gates UNEVALUATED + coverage signal; orchestrator disabled → zero behavior change.
17. **Postgres round-trip** (Testcontainers): all 11 entities persist/read under `ddl-auto=validate` against V6+V6_1; TIMESTAMPTZ, partial uniques, UNIQUE-with-NULL dedupe_key (trap #4).
18. **Adapter test** (frontend vitest): `AgentFleetRow → BeaconAgent` deep-equals fixture objects for the Curasync five; null-adherence em-dash; derived recentActivity fallback chain.

---

## 17. Flag-not-invent — explicit UNKNOWN-semantics extension points (named `// EXTENSION POINT(agent-N)` in code; encoding invariants for any of these is a spec violation)

| # | Unknown | v1 modeling | Unblocks via |
|---|---|---|---|
| 1 | Fifth-state wire vocabulary (`retired`) + kill-vs-retire + projection precedence | additive value, default-excluded, projector-only fallback | **Mike ratification** (ADR D4) |
| 2 | Mandate amendment without redeploy | immutable + retire/redeploy lineage; no amend verb | first engagement demanding it — **Mike decision**; amendments would be chain-mirrored (ADR D5) |
| 3 | Promotion review CONTENT ("HIPAA validation" requirements) | review row records reviewer/decision/rationale/evidence ONLY | first regulated promotion; Mike session (ADR D10) |
| 4 | `supervisoryModel` runtime semantics (HUMAN_REVIEW_ABOVE_THRESHOLD threshold) | recorded/displayed/published; substrate enforces nothing | engagement defining threshold semantics |
| 5 | Contract guards citing agent criteria gates | same TinyDsl makes it possible; not built | contracts D9 SPI revisit |
| 6 | Adherence computation rubric | nullable; latest `adherence` sample only; never LLM-generated (P7) | first engagement-defined rubric |
| 7 | Orchestrator dispatch cancel/pause (seam revision) | preflight + result quarantine only; in-flight uncancellable, disclosed | Mike seam-doc decision (ADR D8) |
| 8 | DRIFT_METRIC auto-intervention | trigger enum value reserved; no evaluator | Phase 4 metric evaluators |
| 9 | Plan-engine initiative intake | `initiative_id` reserved nullable soft ref | plan engine landing (S4) |
| 10 | TOKENS_PER_DAY / SAMPLING_PCT / SCHEDULE_WINDOW evaluators beyond ACTIONS_PER_WINDOW | schema-ready rows, no evaluator | first budget-of-kind demand |
| 11 | Agent-as-cascade-level-owner (cascade §13.7) | registry supplies stable UUID + slug + label idiom; NO cascade column in V6; future = one nullable `owner_agent_registration_id` on cascade tables riding this registry + the same MCP seam | queue-#24 session + first engagement wanting an agent accountable inside a chain |
| 12 | Agent-authored Assertions ([EVIDENCE-GROUNDING] ProducedBy=AGENT) | no write path; filter-at-interpretation applies when built | agent architecture doc / Phase 6 backlog item |
| 13 | Per-agent enforcement-mode override / second ladder | single shared ladder only | first engagement needing independent rollout (ADR D7) |
| 14 | Notification-table unification with cascade outbox + channel adapters | separate tables, same watermark pattern | Phase 5 channels |
| 15 | Sightings / rate-counter / decision-row retention | config key reserved; no purge job | before first high-traffic engagement |
| 16 | Oversight-chain VALIDATION against the org chart | labels + soft member FKs; `reportsTo` is a name string — mis-specified chains are silently legitimate (cascade §13.11 parity; "real oversight-chain enforcement" beyond routing/rendering is NOT delivered) | org-model hardening |
| 17 | Identity hardening | PersonaContext / X-MCP-Principal spoofable, recorded verbatim (S9) | Phase 5 OIDC — `mcp_principal_id` column ready, no schema change |
| 18 | Re-deploy lineage UX (predecessor navigation) | `predecessor_registration_id` recorded; no UI | first retire+redeploy in anger |

---

## 18. Carried risks (from ADR Consequences — implementation-relevant reminders)

- **RECORDED_INTENT ≠ enforcement.** UI copy must say "records intent and notifies" / "paused — unconfirmed at runtime"; the ADR D6 product-copy paragraph ships with the feature. Locus-unqualified "killable" anywhere is fabricated control.
- The 6b→6c window leaves even SUBSTRATE_MCP verbs recorded-intent; the disclosure field is acceptance-gated (§16.1) precisely so operators don't over-trust pause during the gap.
- Adding `retired` to the wire union breaks the swapped pill map if it ever reaches a fleet list — default exclusion + `?includeRetired` is the contract; the frontend union extension is a named coordination point.
- Seed-fiction provenance and attested adherence must never ship to customers (trap #9); the `SEED-FICTION:` marking + demo-gate is the only barrier.
- Both polished fixture mandates compile to monitors/signals and zero blocking guards until an agent population generates governed writes — demo the gateway with the 6c MCP beats (paused-agent denial, zombie heartbeat), not with invented guards on fixture agents.
- This dev machine has no Docker: V6_1 trigger, partial uniques, FOR UPDATE, and counter-increment semantics are CI-verified ONLY — claiming local verification is a false-PASS.
- The registry lookup joins the contracts gateway hot path: keep it on the `GuardCache` invalidation discipline and assert the latency budget in CI; a registry fault fail-closes ALL agent traffic (deliberate, ADR-002 D5 inheritance).

---

## Changelog

- 2026-06-11: §5.3 amended from bean-presence framing to posture framing, matching the shipped resolution (non-negotiable #1 / invariant I8; doctor-verified against `AgentControlEffects` + `AgentRegistryGate.posture()`). `GATEWAY_BLOCK` is claimed only when the gateway posture is BLOCKING — gate bean present AND `substrate.contracts.enforcement.mode=enforce`; bean-absent (ABSENT), `off` (INERT), and `shadow` (OBSERVE) postures all derive `RECORDED_INTENT` with honest per-posture disclosures, because claiming "the next governed call is denied" in shadow/off would be fabricated control. Original sentence, retained verbatim for Mike's ratification trail: "`GATEWAY_BLOCK` once contracts 3c is live (registry flip ⇒ gateway denies next governed call; cache invalidated, 30s TTL net). Before 3c: `RECORDED_INTENT` + `disclosure=\"enforcement pending gateway\"`". Pending Mike ratification of the broader §5.3/I8 tension disposition.
