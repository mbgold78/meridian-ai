# Contracts Engine — Implementation Spec (`engine.contract`)

**Governing ADR:** `substrate/docs/adr/ADR-002-contracts-engine.md` (read first; this spec implements those decisions and does not re-litigate them).
**Port-map slot:** Wave D2 (`port-map.md` §7). Migration `V5__contracts.sql` (V3=ingest_runs, V4=cascades). Entities in `common.domain`, repos in `common.repo`, all behavior in `engine` module, controllers + MCP transport in `app` module (dependency rule `common ← engine ← app`; ingestion never sees engine).
**Binding semantics:** port-map §6 `[CONTRACT-CONTENT]`, `[CONTRACT-ENFORCEMENT]`, `[CONTRACT-COMMITMENT-STATES]`; Decline is a legitimate first-class terminal resolution, never an error (trap #8); read-model-first; flag-not-invent (§15 of this spec is the authoritative UNKNOWN list).

Implementation agents: everything you need is in this file + the ADR. Where this spec says VERBATIM it means byte-identical to the homepoc fixture file `C:/Users/mbgol/dev/meridian-homepoc/frontend/src/demo/beacon/contracts.ts`.

---

## 1. Configuration keys

| Key | Default | Meaning |
|---|---|---|
| `substrate.contracts.enforcement.mode` | `shadow` | `off` — gateway skipped (counter only); `shadow` — full evaluation, would-be denials logged as `WOULD_DENY`, nothing blocked; `enforce` — DENY blocks. Per-engagement. |
| `substrate.contracts.decision-log.level` | `matched-only` | `matched-only`: row only decisions where ≥1 guard matched (plus all FLAG/DENY/WOULD_DENY); plain no-guard ALLOWs are metrics counters. `all`: row everything. |
| `substrate.contracts.monitor.enabled` | `true` | Master switch for the scheduler sweep. |
| `substrate.contracts.monitor.sweep-interval` | `PT60S` | Fixed-delay of the sweep (sweep claims monitors whose `next_run_at <= now`). |
| `substrate.contracts.clock.zone` | `UTC` | Zone for period-key computation. Injectable `java.time.Clock` bean everywhere (demo determinism per S15). |
| `substrate.contracts.trace.max-bytes` | `16384` | Cap on persisted monitor-run trace JSON. Truncate with `"truncated": true` marker. |
| `substrate.contracts.types.allowlist` | `cross-portfolio-opportunity, federation-invitation` | Validated allowlist for `contract_type` (ADR D15). |
| `substrate.contracts.mcp.enabled` | `true` | Mounts the `substrate-contracts` MCP server at `/mcp`. |
| `substrate.contracts.orchestrator-remediation` | `false` | When true AND `substrate.orchestrator.enabled`, breach SURFACED may submit a remediation task via orchestrator `submit_task`. Off by default. |
| `substrate.demo.contracts.activate` | `false` | DemoPack-only: records fixture acceptances to flip `co-tribal-knowledge-depopulation` ACTIVE for monitor demos (§14.2). |

Degraded mode (`substrate.orchestrator.enabled=false`): everything in this spec works unchanged except AI clause drafting (not built in v1 anyway) and the optional remediation hook above.

---

## 2. Domain model and state machines

### 2.1 Enums (new, in `common.domain.enums` or flat per existing layout)

```
ContractStatus            PROPOSED, ACTIVE, DECLINED, WITHDRAWN*, CONCLUDED*      (* schema-reserved, transition-UNREACHABLE in v1 — ADR D7)
ContractPartyType         DOMAIN, ORG_UNIT, COMBINED, EXTERNAL
PartyResponse             ACCEPTED, DECLINED
CommitmentComplianceState UNEVALUATED, COMPLIANT, AT_RISK, BREACHED, WAIVED
ComplianceClaim           ON_TRACK, COMPLETE, AT_RISK, MISSED
BreachStatus              DETECTED, SURFACED, ACKNOWLEDGED, REMEDIATED, WAIVED
BreachDetectedBy          MONITOR, HUMAN_ATTESTATION, GATEWAY_FLAG
BreachResolution          REMEDIATED, WAIVED
MonitorStatus             ACTIVE, SUSPENDED
MonitorRuleType           ATTESTATION_CADENCE, METRIC_THRESHOLD, MCP_PROBE, UNSTRUCTURED
MonitorRunOutcome         COMPLIANT, BREACH_DETECTED, INDETERMINATE, NOT_DUE, ERROR
GuardEffect               DENY, REQUIRE_ATTESTATION, FLAG
GatewayDecision           ALLOW, ALLOW_WITH_FLAG, DENY, WOULD_DENY
PrincipalType             HUMAN, AGENT, SERVICE
ContractActorType         HUMAN, AGENT, SERVICE, MONITOR, SYSTEM                  (for events; MONITOR/SYSTEM are engine-internal actors)
```

### 2.2 CONTRACT state machine

```
            all required parties ACCEPTED
 PROPOSED ─────────────────────────────────▶ ACTIVE
    │                                          │
    │ any required party responds DECLINED     │ invoke_decline (cites pre-agreed trigger,
    ▼                                          ▼  reasoning mandatory)
 DECLINED ◀────────────────────────────────────┘
 (TERMINAL, LEGITIMATE — never error-coded, never breach-colored)
```

Transitions (all under pessimistic row lock on `contracts`, §10.1):

| From → To | Trigger | Side effects (same transaction) |
|---|---|---|
| PROPOSED → ACTIVE | last required party records `ACCEPTED` | `activated_at=now`; all monitors `status=ACTIVE`, `next_run_at` initialized; all guards `active=true`; `CONTRACT_ACTIVATED` event; hash-chain anchor (ADR D14); guard-cache invalidation; `ContractActivatedEvent` after commit |
| PROPOSED → DECLINED | any required party records `DECLINED` (declineReasoning mandatory; declineTriggerId optional at proposal stage) | `resolved_at=now`; `CONTRACT_DECLINED` event; hash anchor; `ContractDeclinedEvent` after commit |
| ACTIVE → DECLINED | `invoke_decline(contractKey, declineTriggerId, reasoning)` — trigger must belong to contract; reasoning mandatory | `resolved_at=now`; ALL monitors → SUSPENDED; ALL guards → `active=false`; `DECLINE_TRIGGER_INVOKED` + `CONTRACT_DECLINED` events; hash anchor; guard-cache invalidation; `ContractDeclinedEvent` after commit |

Invariants:
- `contract_events` append-only; no UPDATE/DELETE code path exists (repository exposes save-only; enforce by not generating update methods + an ArchUnit rule banning `delete*` on the event repo).
- Party response rows immutable once written; one per party (`UNIQUE(party_id)`).
- **Content freeze (ADR D7):** parties, commitments, decline triggers, estimatedValue, enforcement text are mutable only while `status=PROPOSED` AND `contract_party_responses` is empty for the contract. Service-enforced; violation → 409 `CONTRACT_CONTENT_FROZEN`.
- DECLINED atomically suspends monitors + deactivates guards in the same transaction.
- Federation review boundary: on `invoke_decline` for `contract_type=federation-invitation`, IF `review_boundary` is non-null the gateway checks the invocation falls within `window_days` of a boundary (see §6.2); IF null → ALLOW + decision `ALLOW_WITH_FLAG` (flag-not-invent — boundary semantics undocumented).
- Open breaches never block decline (ALLOW + `ALLOW_WITH_FLAG`); they survive decline and continue their human lifecycle (§2.4).
- Terminal contracts reject further mutating actions with 409 `INVALID_CONTRACT_STATE` (this is a state error, not a "decline error" — decline itself returned 200).

### 2.3 COMMITMENT compliance (denormalized `compliance_state` on `contract_commitments`; history in events)

```
UNEVALUATED ──▶ COMPLIANT ⇄ AT_RISK ──▶ BREACHED ──▶ WAIVED (breach waived)
                    ▲                      │
                    └──────────────────────┘ (breach remediated)
```

Driven ONLY by monitor runs, attestation claims, and breach transitions — never directly editable via API.
- `BREACHED ⇔` an open breach row references the commitment.
- Evaluable only while contract is ACTIVE.
- Attestation `compliance_claim=MISSED` auto-opens a breach (`detected_by=HUMAN_ATTESTATION`); `AT_RISK` sets state AT_RISK without breach; `ON_TRACK`/`COMPLETE` set COMPLIANT for the period.

### 2.4 BREACH lifecycle

```
DETECTED ──▶ SURFACED ──▶ ACKNOWLEDGED ──▶ REMEDIATED | WAIVED
```
- DETECTED: created by a monitor run or MISSED attestation. Dedup invariant (ADR D13): at most one open breach per `(monitor_id, commitment_id, period_key)`; re-detection refreshes `last_confirmed_at` and appends a `BREACH_RECONFIRMED` event — never a second row.
- SURFACED: `surfaced_at` set + signal published + `BreachSurfacedEvent`. Kept distinct from DETECTED so surfacing can dedupe/batch yet remain audited. v1: synchronous, same sweep.
- ACKNOWLEDGED: named human actor (`acknowledged_by/at`).
- REMEDIATED: requires `remediation_evidence_refs` (Evidence/DocumentRegistry ids). Sets commitment back to COMPLIANT.
- WAIVED: human actor + rationale mandatory; **agents may never waive** (built-in non-removable guard, ADR D16). Mirrored to hash chain (ADR D14). Sets commitment to WAIVED.
- Monotonic forward-only; rows never deleted; every transition appends `contract_events` with actor identity.
- A monitor PASS over an open breach appends a `BREACH_COMPLIANCE_RESTORED` event but does NOT auto-close — human REMEDIATED confirm required. (Auto-close-on-pass is a flagged extension point, §15.)

### 2.5 MONITOR

`MonitorStatus`: `ACTIVE ⇄ SUSPENDED` (armed only while contract ACTIVE; all suspended on decline). Run outcomes per §2.1; **INDETERMINATE never opens a breach** (ADR D10) — it produces a needs-input signal (`ADHERENCE_STALE` / `ADHERENCE_UNOBSERVABLE` / `HUMAN_VERIFY_DUE`). `ERROR` (evaluator threw) is an admin signal; breach state untouched.

### 2.6 DECLINE TRIGGER standing (derived, not a mutable state column)

`DORMANT ⇄ ARMED` for predicate-bearing triggers only: the sweep evaluates `predicate` (same tiny DSL as guards, §4.4) and sets denormalized `armed=true/false` + `last_armed_at`, appending `DECLINE_TRIGGER_ARMED|DISARMED` events and emitting a `DECLINE_ELIGIBLE` signal (subtle-destructive accent — **never a breach**). Predicate-null triggers are pure human-judgment grounds, always invocable. Note: fixture trigger "Cost-share imbalance > 30% sustained over two quarters" needs temporal semantics the v1 DSL lacks → ships predicate-null (ADR D9 revisit example).

### 2.7 GATEWAY decisions

Not an entity state machine; append-only outcomes `ALLOW | ALLOW_WITH_FLAG | DENY | WOULD_DENY` (§4). Fail posture: evaluation failure ⇒ DENY for AGENT/SERVICE principals, ALLOW_WITH_FLAG for HUMAN (ADR D5).

---

## 3. Persistence

### 3.1 Placement and patterns

- Entities: `common/src/main/java/com/meridian/substrate/common/domain/` extending `BaseEntity` (UUID id, @CreationTimestamp/@UpdateTimestamp, createdBy default `system`, `@Version Long version NOT NULL`). No Lombok on entities. FK-twin pattern: writable UUID column + `@JsonIgnore @ManyToOne(LAZY, insertable=false, updatable=false)` navigation twin. JSON via `JsonNodeConverter` → TEXT (§4.5 of port-map — do NOT pre-flip to jsonb; the named flip condition is the first guard/rule-params indexing need).
- Repositories: `common/src/main/java/com/meridian/substrate/common/repo/` — derived queries + JPQL only; `ForUpdate` JPQL variants where §10 requires locks (match `CapabilityRepository` precedent).
- Soft references (bound_entity_ids, evidence_refs, contract_ids in decisions) have NO DB FKs — application-enforced, consistent with the existing pattern; comment in code.
- Migration: `app/src/main/resources/db/migration/common/V5__contracts.sql` (ANSI-portable, H2-PG-mode + Postgres) and `app/src/main/resources/db/migration/postgresql/V5_1__contracts_partial_indexes.sql` (vendor dir).

### 3.2 DDL sketch — `V5__contracts.sql` (Flyway-ready; column-per-line omitted base columns shown once)

Every table includes the base columns:
```sql
id UUID PRIMARY KEY, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
updated_at TIMESTAMP WITH TIME ZONE, created_by VARCHAR(255) NOT NULL DEFAULT 'system',
version BIGINT NOT NULL DEFAULT 0
```

```sql
-- 1. contracts — aggregate root
CREATE TABLE contracts (
  -- [base columns]
  domain_id UUID NOT NULL,
  contract_key VARCHAR(160) NOT NULL,            -- stable slug; external/MCP/UI id (ADR D8)
  contract_type VARCHAR(64) NOT NULL,            -- validated against allowlist (ADR D15)
  title VARCHAR(512) NOT NULL,
  context TEXT,
  monitor_text TEXT,                             -- enforcement.monitor plain language, VERBATIM
  monitor_mechanism TEXT,                        -- enforcement.monitorMechanism, VERBATIM (UI mono panel)
  estimated_value VARCHAR(255),                  -- display string, e.g. '$1.2M annually across four subsidiaries'
  estimated_value_amount NUMERIC(18,2),          -- nullable structured sibling
  status VARCHAR(32) NOT NULL,                   -- ContractStatus
  proposed_at TIMESTAMP WITH TIME ZONE NOT NULL,
  proposed_by VARCHAR(255) NOT NULL,
  activated_at TIMESTAMP WITH TIME ZONE,
  resolved_at TIMESTAMP WITH TIME ZONE,
  review_boundary TEXT,                          -- JSON, nullable; unenforced-when-null (§6.2)
  metadata TEXT,                                 -- JSON
  CONSTRAINT uq_contracts_domain_key UNIQUE (domain_id, contract_key)
);
CREATE INDEX idx_contracts_domain_status ON contracts(domain_id, status);

-- 2. contract_parties
CREATE TABLE contract_parties (
  -- [base columns]
  contract_id UUID NOT NULL,
  party_label VARCHAR(255) NOT NULL,             -- fixture string, e.g. 'Sentari, Tellix, Bursar (combined obligation)'
  party_type VARCHAR(32) NOT NULL,               -- ContractPartyType
  bound_entity_ids TEXT,                         -- JSON array of {entityType, entityId, slug?} soft refs (severs S5 slug links)
  mcp_principal_id VARCHAR(255),                 -- set when the party's agent/system binds via MCP (P19); Phase 5 hardens
  response_required BOOLEAN NOT NULL DEFAULT TRUE,
  sequence_order INTEGER NOT NULL
);
CREATE INDEX idx_contract_parties_contract ON contract_parties(contract_id);

-- 3. contract_party_responses — immutable once written
CREATE TABLE contract_party_responses (
  -- [base columns]
  party_id UUID NOT NULL,
  contract_id UUID NOT NULL,
  response VARCHAR(16) NOT NULL,                 -- ACCEPTED | DECLINED
  responded_by VARCHAR(255) NOT NULL,
  responded_at TIMESTAMP WITH TIME ZONE NOT NULL,
  decline_trigger_id UUID,                       -- optional citation at proposal stage
  decline_reasoning TEXT,                        -- service-enforced NOT NULL iff response=DECLINED
  CONSTRAINT uq_party_response UNIQUE (party_id)
);

-- 4. contract_commitments
CREATE TABLE contract_commitments (
  -- [base columns]
  contract_id UUID NOT NULL,
  party_id UUID NOT NULL,
  text TEXT NOT NULL,                            -- VERBATIM fixture commitment string
  due_by TIMESTAMP WITH TIME ZONE,
  cadence VARCHAR(16),                           -- MONTHLY | QUARTERLY | ... nullable
  compliance_state VARCHAR(32) NOT NULL DEFAULT 'UNEVALUATED',
  last_evaluated_at TIMESTAMP WITH TIME ZONE,
  sequence_order INTEGER NOT NULL
);
CREATE INDEX idx_contract_commitments_contract ON contract_commitments(contract_id);

-- 5. contract_decline_triggers
CREATE TABLE contract_decline_triggers (
  -- [base columns]
  contract_id UUID NOT NULL,
  text TEXT NOT NULL,                            -- VERBATIM fixture trigger string
  predicate TEXT,                                -- JSON, nullable: tiny DSL (§4.4); null = human judgment
  invocable_by_party_id UUID,                    -- null = any party
  armed BOOLEAN NOT NULL DEFAULT FALSE,          -- denormalized standing (§2.6)
  last_armed_at TIMESTAMP WITH TIME ZONE,
  sequence_order INTEGER NOT NULL
);

-- 6. contract_entity_links — navigation back-refs (linkedSubsidiarySlugs → real refs)
CREATE TABLE contract_entity_links (
  -- [base columns]
  contract_id UUID NOT NULL,
  linked_entity_type VARCHAR(64) NOT NULL,       -- DOMAIN | SYSTEM | PROCESS | CASCADE | AGENT_REGISTRATION | SUBSIDIARY_SLUG
  linked_entity_id UUID,                         -- nullable during pre-D5 window
  slug VARCHAR(160),                             -- fixture slug fallback until subsidiary canonical modeling (D5)
  label VARCHAR(255)
);
CREATE INDEX idx_contract_entity_links_contract ON contract_entity_links(contract_id);

-- 7. contract_clause_guards — PDP hot path: FIRST-CLASS COLUMNS, no JSON in the match path (ADR D9)
CREATE TABLE contract_clause_guards (
  -- [base columns]
  contract_id UUID NOT NULL,
  commitment_id UUID,
  action_type VARCHAR(255) NOT NULL,             -- e.g. 'agent.promote', 'apply_dispatch_result'
  entity_type VARCHAR(64),                       -- nullable narrowing
  effect VARCHAR(32) NOT NULL,                   -- DENY | REQUIRE_ATTESTATION | FLAG
  predicate TEXT,                                -- JSON, nullable: tiny DSL (§4.4)
  active BOOLEAN NOT NULL DEFAULT FALSE,         -- true only while contract ACTIVE
  description VARCHAR(512) NOT NULL
);
CREATE INDEX idx_guards_action_active ON contract_clause_guards(action_type, active);
CREATE INDEX idx_guards_contract ON contract_clause_guards(contract_id);

-- 8. contract_monitors
CREATE TABLE contract_monitors (
  -- [base columns]
  contract_id UUID NOT NULL,
  name VARCHAR(255) NOT NULL,                    -- e.g. 'missing-monthly-report'
  rule_type VARCHAR(32) NOT NULL,                -- ATTESTATION_CADENCE | METRIC_THRESHOLD | MCP_PROBE | UNSTRUCTURED
  rule_spec TEXT,                                -- JSON: {commitmentIds?, graceDays?, threshold?, metricKey?, requiredPartyIds?, readerRegistrationId?}
  cadence VARCHAR(16) NOT NULL,                  -- MONTHLY | QUARTERLY | DAILY | WEEKLY
  grace_days INTEGER NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'SUSPENDED',
  surface_to VARCHAR(64),                        -- e.g. OPERATING_PARTNER_DASHBOARD
  severity VARCHAR(16),                          -- Severity enum value
  last_run_at TIMESTAMP WITH TIME ZONE,
  last_outcome VARCHAR(32),
  next_run_at TIMESTAMP WITH TIME ZONE           -- DB-recoverable scheduling across restarts
);
CREATE INDEX idx_contract_monitors_due ON contract_monitors(status, next_run_at);

-- 9. contract_monitor_runs — append-only
CREATE TABLE contract_monitor_runs (
  -- [base columns]
  monitor_id UUID NOT NULL,
  started_at TIMESTAMP WITH TIME ZONE NOT NULL,
  finished_at TIMESTAMP WITH TIME ZONE,
  outcome VARCHAR(32) NOT NULL,                  -- MonitorRunOutcome
  period_key VARCHAR(16),                        -- evaluated period, e.g. '2026-07' / '2026-Q3'
  details TEXT                                   -- JSON bounded trace: rule, inputs resolved, counts/values; capped (§1)
);
CREATE INDEX idx_monitor_runs_monitor ON contract_monitor_runs(monitor_id, started_at);

-- 10. contract_attestations — idempotent per period (ADR D13)
CREATE TABLE contract_attestations (
  -- [base columns]
  commitment_id UUID NOT NULL,
  contract_id UUID NOT NULL,
  party_id UUID NOT NULL,
  period_key VARCHAR(16) NOT NULL,               -- '2026-07' (MONTHLY) / '2026-Q3' (QUARTERLY) / 'ONE_TIME'
  attested_by VARCHAR(255) NOT NULL,
  attested_at TIMESTAMP WITH TIME ZONE NOT NULL,
  compliance_claim VARCHAR(16) NOT NULL,         -- ON_TRACK | COMPLETE | AT_RISK | MISSED
  statement TEXT,
  evidence_refs TEXT,                            -- JSON array of Evidence/DocumentRegistry ids (EVIDENCE-GROUNDING shape)
  submitted_via VARCHAR(16) NOT NULL DEFAULT 'API', -- UI | MCP | API
  CONSTRAINT uq_attestation_period UNIQUE (commitment_id, period_key)  -- upsert key; history lives in contract_events
);

-- 11. contract_metric_samples — push-based v1 metric feed (ADR D11)
CREATE TABLE contract_metric_samples (
  -- [base columns]
  contract_id UUID NOT NULL,
  party_id UUID NOT NULL,
  metric_key VARCHAR(128) NOT NULL,              -- e.g. 'utilization_share', 'accuracy'
  metric_value DOUBLE PRECISION NOT NULL,
  period_key VARCHAR(16) NOT NULL,
  reported_by VARCHAR(255) NOT NULL,
  reported_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT uq_metric_sample UNIQUE (contract_id, party_id, metric_key, period_key)  -- idempotent upsert
);

-- 12. contract_breaches
CREATE TABLE contract_breaches (
  -- [base columns]
  contract_id UUID NOT NULL,
  commitment_id UUID,
  monitor_id UUID,
  period_key VARCHAR(16),
  status VARCHAR(32) NOT NULL,                   -- BreachStatus
  severity VARCHAR(16),
  summary VARCHAR(512) NOT NULL,
  detected_at TIMESTAMP WITH TIME ZONE NOT NULL,
  detected_by VARCHAR(32) NOT NULL,              -- MONITOR | HUMAN_ATTESTATION | GATEWAY_FLAG
  last_confirmed_at TIMESTAMP WITH TIME ZONE,    -- refreshed on re-detection (ADR D13)
  surfaced_at TIMESTAMP WITH TIME ZONE,
  acknowledged_by VARCHAR(255),
  acknowledged_at TIMESTAMP WITH TIME ZONE,
  resolution VARCHAR(16),                        -- REMEDIATED | WAIVED
  resolution_actor VARCHAR(255),
  resolution_rationale TEXT,                     -- mandatory for WAIVED (service-enforced)
  remediation_evidence_refs TEXT                 -- JSON; mandatory for REMEDIATED (service-enforced)
);
CREATE INDEX idx_contract_breaches_contract ON contract_breaches(contract_id, status);

-- 13. contract_events — append-only audit timeline (the contract analog of CASCADE-DURABILITY)
CREATE TABLE contract_events (
  -- [base columns]
  contract_id UUID NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  ref_id UUID,                                   -- party/commitment/breach/monitor/attestation id as applicable
  actor_type VARCHAR(16) NOT NULL,               -- ContractActorType
  actor_id VARCHAR(255) NOT NULL,
  payload TEXT,                                  -- JSON
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_contract_events_contract ON contract_events(contract_id, occurred_at);

-- 14. contract_enforcement_decisions — high-volume PDP log, separate retention profile (ADR D17)
CREATE TABLE contract_enforcement_decisions (
  -- [base columns]
  domain_id UUID NOT NULL,
  action_type VARCHAR(255) NOT NULL,
  actor_type VARCHAR(16) NOT NULL,
  actor_id VARCHAR(255) NOT NULL,
  decision VARCHAR(32) NOT NULL,                 -- GatewayDecision
  contract_ids TEXT,                             -- JSON array (no FK; queried by domain+time in v1)
  clause_ids TEXT,                               -- JSON array of guard ids
  dispatch_id VARCHAR(255),                      -- orchestrator dispatch id when actionType=apply_dispatch_result
  reason VARCHAR(512),
  latency_ms INTEGER,
  mode VARCHAR(16) NOT NULL,                     -- shadow | enforce
  decided_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_enforcement_decisions_domain_time ON contract_enforcement_decisions(domain_id, decided_at);

-- ShedLock lease table for the monitor sweep. The cascade engine (V4) may already create it;
-- IF EXISTS guard keeps the migration order-independent. Coordinate with D1 chunk owner.
CREATE TABLE IF NOT EXISTS shedlock (
  name VARCHAR(64) PRIMARY KEY,
  lock_until TIMESTAMP WITH TIME ZONE NOT NULL,
  locked_at TIMESTAMP WITH TIME ZONE NOT NULL,
  locked_by VARCHAR(255) NOT NULL
);
```

### 3.3 `db/migration/postgresql/V5_1__contracts_partial_indexes.sql` (vendor dir; H2 cannot express these — service-enforced on dev, same precedent as branding V2_1)

```sql
-- Open-breach dedup backstop (service check is primary; this catches races)
CREATE UNIQUE INDEX uq_open_breach_per_source ON contract_breaches (
  contract_id,
  COALESCE(monitor_id,    '00000000-0000-0000-0000-000000000000'::uuid),
  COALESCE(commitment_id, '00000000-0000-0000-0000-000000000000'::uuid),
  COALESCE(period_key, '')
) WHERE status IN ('DETECTED','SURFACED','ACKNOWLEDGED');
```

### 3.4 JPA entity inventory (14 — names mirror tables)

`Contract`, `ContractParty`, `ContractPartyResponse`, `ContractCommitment`, `ContractDeclineTrigger`, `ContractEntityLink`, `ContractClauseGuard`, `ContractMonitor`, `ContractMonitorRun`, `ContractAttestation`, `ContractMetricSample`, `ContractBreach`, `ContractEvent`, `ContractEnforcementDecision`. Repositories (`common.repo`): one per entity; required derived/JPQL methods:

- `ContractRepository`: `findByDomainId`, `findByDomainIdAndStatus`, `findByDomainIdAndContractKey`, JPQL `findByIdForUpdate` (PESSIMISTIC_WRITE).
- `ContractClauseGuardRepository`: `findByActionTypeAndActiveTrue`, `findByContractId`.
- `ContractMonitorRepository`: `findByStatusAndNextRunAtBefore` (+ `ForUpdate` variant; on PG the sweep adds SKIP LOCKED via native query — see §10.3), `findByContractId`.
- `ContractBreachRepository`: `findByContractIdAndStatusIn`, JPQL `findOpenBySourceForUpdate(contractId, monitorId, commitmentId, periodKey)`.
- `ContractAttestationRepository`: `findByCommitmentIdAndPeriodKey`, `findByContractId`.
- `ContractMetricSampleRepository`: `findByContractIdAndMetricKeyAndPeriodKey`.
- `ContractPartyResponseRepository`: `findByContractId`, `findByPartyId`.
- `ContractEventRepository`: `findByContractIdOrderByOccurredAtAsc` (save-only otherwise).
- `ContractEnforcementDecisionRepository`: `findByDomainIdAndDecidedAtAfter`, filtered finders for §7 endpoint params.

---

## 4. Enforcement gateway (PDP/PEPs)

### 4.1 Core types (engine module, `engine.contract.gateway`)

```java
record GovernedAction(String actionType, Principal principal, UUID domainId,
                      TargetRef target,            // nullable: {entityType, entityId}
                      JsonNode payloadSummary,     // small, never the full body
                      String dispatchId) {}       // non-null only for apply_dispatch_result

record Principal(PrincipalType type, String id) {} // HUMAN: persona id (S9 caveat) | AGENT: mcp principal | SERVICE: 'substrate-svc'

sealed interface Decision {
  record Allow(boolean flagged, List<UUID> matchedClauseIds, String flagReason) implements Decision {}
  record Deny(List<UUID> clauseIds, List<String> contractKeys, String reason, UUID decisionId) implements Decision {}
}

ContractGateway.evaluate(GovernedAction) -> Decision   // throws ContractDeniedException in enforce mode on Deny
```

### 4.2 The three PEPs (ADR D2)

1. **MCP governed tools** — every write tool in §8.3 builds a `GovernedAction` and calls the gateway before its service logic.
2. **REST service commands** — `@Governed(actionType = "process.update")` on mutating service methods; `GovernedActionAspect` (Spring AOP, `@Order` before `@Transactional` proxy? No — it must run INSIDE the transaction: order the aspect after the transaction interceptor so DENY rollback is automatic) resolves principal from `PersonaContext` (HUMAN) and domainId/target from method args via a `GovernedContextResolver` (explicit per-service resolver beans; no SpEL magic).
3. **Dispatch-result apply** — the service that applies an accepted orchestrator `get_dispatch_result` wraps the apply as `actionType=apply_dispatch_result`, principal `SERVICE/substrate-svc`, carrying `dispatchId`.

**ArchUnit gate (ships with 3c, runs in CI from day one):** every public method in the governed packages (`com.meridian.substrate.app.core.service..`, `com.meridian.substrate.engine..` service classes) that is `@Transactional` without `readOnly=true` must carry `@Governed` or `@Ungoverned(reason="…")`. The governed-package list is a constant in the test, maintained as modules grow. The `ingestion` module is exempt at module level (ADR D3) — assert the exemption is documented by requiring the `@UngovernedModule` marker on `IngestionApplication`.

### 4.3 Evaluation algorithm

```
evaluate(action):
  if mode == off: metrics.increment(allow_unevaluated); return Allow(unflagged)
  guards = guardCache.activeGuards(action.domainId, action.actionType)   // 1 indexed read on miss
           filtered by entity_type match (null entity_type = wildcard)
  if guards empty: metrics.increment(allow_no_guard); maybeRow(level=all); return Allow
  for each guard (effect precedence DENY > REQUIRE_ATTESTATION > FLAG):
    matched = guard.predicate == null || TinyDsl.eval(guard.predicate, action.payloadSummary, action.target)
    if matched:
      DENY  -> outcome = deny(guard)
      REQUIRE_ATTESTATION -> current-period attestation exists for guard.commitment_id ? pass : outcome = deny(guard, reason=ATTESTATION_MISSING)
      FLAG  -> flag(guard)
  if outcome == deny:
      row decision in REQUIRES_NEW tx                       // survives rollback (§10.4)
      mode == shadow  -> decision=WOULD_DENY; return Allow(flagged)
      mode == enforce -> decision=DENY; throw ContractDeniedException
  else: row ALLOW or ALLOW_WITH_FLAG per decision-log.level; return Allow(flagged?)
on evaluator exception:
  principal HUMAN -> Allow(flagged, reason=GATEWAY_ERROR), row ALLOW_WITH_FLAG
  principal AGENT|SERVICE -> as DENY path with reason=GATEWAY_ERROR (fail-closed)
```

Targets: p50 < 5 ms, p99 < 50 ms (ADR D1 revisit trigger). `GuardCache`: per-domain in-memory map keyed `(domainId)` → guard list, invalidated by an in-process listener on contract activation/decline/guard-change events + 30 s TTL safety net.

### 4.4 Tiny predicate DSL (shared by guards and decline-trigger arming; ADR D9)

JSON, evaluated in Java (`TinyDslEvaluator`), **never SQL-generated**:

```json
{"all": [ {"field": "payload.scope", "eq": "hipaa-attestation-forms"},
          {"field": "payload.formCount", "lte": 40} ]}
```
Nodes: `all`, `any`, `not` combinators; leaves `{"field": <dot-path into payloadSummary|target|metric>, "eq"|"neq"|"gt"|"gte"|"lt"|"lte": <scalar>}`; metric leaf `{"metric": "<metricKey>", "gt": 0.30}` resolves the latest complete-period value from `contract_metric_samples` (arming use). Unknown node or unresolvable field ⇒ predicate is INDETERMINATE ⇒ guard does not match (gateway) / trigger does not arm (scheduler) — and an `ALLOW_WITH_FLAG`/needs-input signal records that an unevaluable predicate was skipped (never silent). Anything richer is the `GuardPredicate` SPI extension point — do not extend the DSL ad hoc.

### 4.5 Wire shapes for denial

REST: HTTP 409, standard envelope, `ApiError{code: "CONTRACT_DENIAL", message: "<human summary citing contractKey + clause descriptions + decisionId>", field: null}` via a `GlobalExceptionHandler` mapping for `ContractDeniedException`. Never 5xx; never used for decline itself.
MCP: `isError=true` tool result with structured content `{"code":"CONTRACT_DENIAL","contractKeys":[...],"clauseIds":[...],"reason":"...","decisionId":"..."}` so agents adapt rather than crash.
Other 409 codes used by this engine: `CONTRACT_CONTENT_FROZEN`, `INVALID_CONTRACT_STATE`, `RESPONSE_CONFLICT`.

---

## 5. Monitors, attestations, metrics, breaches

### 5.1 Period keys

Computed from the injectable Clock in `substrate.contracts.clock.zone`: MONTHLY → `YYYY-MM`; QUARTERLY → `YYYY-Qn`; one-shot commitments → literal `ONE_TIME`. A period is *closed* once its end passes; grace runs `grace_days` after period end.

### 5.2 Scheduler

`ContractMonitorScheduler` (engine): `@Scheduled(fixedDelayString = "${substrate.contracts.monitor.sweep-interval}")` + `@SchedulerLock(name = "contract-monitor-sweep")` (ShedLock, JDBC provider). Sweep:

1. Claim due monitors: `status=ACTIVE AND next_run_at <= now` — on Postgres via native `FOR UPDATE SKIP LOCKED`; H2 dev falls back to the plain `ForUpdate` JPQL (single-node per-engagement deploy assumption, documented).
2. Dispatch to the `MonitorEvaluator` SPI impl for `rule_type`; record `contract_monitor_runs` row (append-only) with bounded trace; update `last_run_at/last_outcome/next_run_at` (next cadence boundary + jitter ≤ 60 s).
3. Evaluate predicate-bearing decline triggers of ACTIVE contracts (arming pass, §2.6) — daily granularity is fine; reuse the sweep.
4. Open/refresh breaches per outcome (§5.4); surface (set `surfaced_at`, publish events/signals).

`POST /contracts/{id}/monitors/{mid}/run-now` (and the same internally for tests) executes a single monitor synchronously, bypassing `next_run_at` but honoring locks.

### 5.3 Evaluators (`engine.contract.monitor`, SPI `MonitorEvaluator { ruleType(); evaluate(monitor, periodCtx) -> RunResult }`)

- **AttestationCadenceEvaluator** — for each commitment in `rule_spec.commitmentIds` (default: all cadence-bearing commitments of the contract): most recent closed period P; attestation exists for (commitment, P)?
  - claim ON_TRACK/COMPLETE → COMPLIANT (commitment → COMPLIANT)
  - claim AT_RISK → COMPLIANT run outcome with commitment → AT_RISK + signal
  - claim MISSED → BREACH_DETECTED (`detected_by=HUMAN_ATTESTATION` if breach originated from the attestation write itself; MONITOR if found by sweep)
  - no attestation, now ≤ periodEnd+graceDays → NOT_DUE
  - no attestation, grace exceeded → BREACH_DETECTED (commitment → BREACHED)
- **MetricThresholdEvaluator** — requires samples for `rule_spec.metricKey` from ALL `rule_spec.requiredPartyIds` for the most recent closed period; incomplete → INDETERMINATE + `ADHERENCE_STALE` signal (NEVER computes on partial data — ADR D11); complete → `|a−b| > threshold` ⇒ BREACH_DETECTED else COMPLIANT.
- **McpProbeEvaluator** — liveness only: probes the party's registered MCP endpoint (`ReaderRegistration kind=MCP_CLIENT`, id in `rule_spec.readerRegistrationId`); reachable → COMPLIANT; absent/erroring → INDETERMINATE + `ADHERENCE_UNOBSERVABLE` signal. Never a breach. Never fetches metrics.
- **UnstructuredEvaluator** — always INDETERMINATE + `HUMAN_VERIFY_DUE` signal on each cadence tick. A monitor can be weak but never silently inert.

Evaluator exception → run outcome ERROR + admin signal; breach state untouched.

### 5.4 Breach open/refresh (service `BreachService`)

```
onBreachDetected(monitorId, commitmentId, periodKey, severity, summary):
  tx { existing = breachRepo.findOpenBySourceForUpdate(...)
       if existing: existing.lastConfirmedAt = now; event BREACH_RECONFIRMED
       else: insert DETECTED row; commitment -> BREACHED; event BREACH_DETECTED }
  surface step (same sweep, separate tx): surfaced_at = now; event BREACH_SURFACED; BreachSurfacedEvent
```
Human verbs `acknowledge / remediate / waive` per §2.4 with mandatory-field service validation; `waive` carries the built-in agent-deny guard (gateway PEP on the MCP tool + principal check in the service for REST).

### 5.5 Attestations and metric samples

`AttestationService.attest(commitmentId, periodKey, claim, statement, evidenceRefs, principal)`:
upsert on `(commitment_id, period_key)` (row carries latest; every submission appends `COMMITMENT_ATTESTED` event with full payload so history is durable); claim side effects per §5.3; MISSED auto-opens breach via `BreachService`. Evidence refs are soft references to existing `Evidence`/`DocumentRegistry` rows — validated for existence, warn-don't-fail if missing (consistent soft-ref posture).
`MetricSampleService.report(contractId, partyId, metricKey, value, periodKey, principal)`: upsert on the unique key; `METRIC_REPORTED` event. Party authority: principal must map to the party (`mcp_principal_id`) — pre-Phase-5 this is advisory (spoofable header), recorded verbatim.

---

## 6. Decline mechanics

### 6.1 `invoke_decline` flow (REST `POST /contracts/{id}/decline` and MCP `invoke_decline`)

```
tx {
  contract = findByIdForUpdate; require status == ACTIVE (PROPOSED declines go through respond_to_contract)
  trigger = require declineTriggerId belongs to contract; require reasoning non-blank
  require trigger.invocable_by_party_id is null OR matches caller's party
  gateway.evaluate(actionType='contract.invoke_decline', ...)   // boundary check below; open-breach flag below
  transition ACTIVE -> DECLINED per §2.2 (suspend monitors, deactivate guards, events, hash anchor)
}
publish ContractDeclinedEvent after commit (TransactionSynchronization)
return 200 + full ContractDetailResponse (status DECLINED)      // NEVER an error code
```

### 6.2 Review boundary (federation contracts only; flag-not-invent)

`contracts.review_boundary` JSON, nullable: `{"cadence":"QUARTERLY","windowDays":14,"anchor":"activation"}`. When non-null: invocation must fall within `windowDays` after a cadence boundary measured from `anchor` (`activation` = activated_at; `calendar` = calendar quarters). Outside window → gateway DENY (enforce) / WOULD_DENY+proceed (shadow), code `CONTRACT_DENIAL`, reason `REVIEW_BOUNDARY`. When null (the default — no homepoc doc defines boundary semantics): ALLOW + decision `ALLOW_WITH_FLAG`, reason `REVIEW_BOUNDARY_UNCONFIGURED`. DemoPack seeds `review_boundary = null`.

### 6.3 Open breaches at decline

Allowed; decision rowed `ALLOW_WITH_FLAG`, reason `OPEN_BREACHES_AT_DECLINE` (product question for Mike — §15). Breaches stay open, monitors are suspended, human breach verbs remain available on the DECLINED contract.

---

## 7. REST API (app module, `/api/v1`, `{data, meta, errors}` envelope, controllers in `app.api.v1.controller.ContractController` + `ContractAdminController`)

`{idOrKey}` resolves UUID first, then `(current engagement domain, contract_key)`.

### 7.1 Read (phase 3a)

| Endpoint | Params | Response (`data`) |
|---|---|---|
| `GET /contracts` | `domainId!, status?, type?` | `[ContractCardResponse]` — `{id, contractKey, type, title, status, proposedAt, estimatedValue, partyLabels[], linkedEntities[], openBreachCount}` |
| `GET /contracts/{idOrKey}` | — | `ContractDetailResponse` (§7.3) |
| `GET /contracts/{id}/events` | `after?` (ISO ts) | `[{id, eventType, refId, actorType, actorId, payload, occurredAt}]` ascending |
| `GET /contracts/{id}/breaches` | `status?` | `[BreachResponse]` — all columns of §3.2 table 12, camelCased |
| `GET /contracts/{id}/decisions` | `since?, decision?` | `[EnforcementDecisionResponse]` |
| `GET /contracts/{id}/monitor-runs` | `monitorId?, since?` | `[{id, monitorId, startedAt, finishedAt, outcome, periodKey, details}]` |
| `GET /contracts/{id}/attestations` | `commitmentId?` | `[AttestationResponse]` |
| `GET /contracts/signals` | `domainId!` | `SignalsResponse` (§11.2) |

### 7.2 Write (3a authoring; 3b attest/breach/metrics; decline/respond endpoints exist from 3a but UI affordances stay inert — queue #28)

| Endpoint | Request body (validation on request records, never entities) | Behavior |
|---|---|---|
| `POST /contracts` | `ContractCreateRequest{contractKey!, type!, title!, context, monitorText, monitorMechanism, estimatedValue, estimatedValueAmount?, reviewBoundary?, parties![{partyLabel!, partyType!, boundEntityIds?, responseRequired, commitments![{text!, dueBy?, cadence?}]}], declineTriggers[{text!, predicate?, invocableByPartyLabel?}], monitors[{name!, ruleType!, ruleSpec?, cadence!, graceDays, surfaceTo?, severity?}], guards[{actionType!, entityType?, effect!, predicate?, description!, commitmentRef?}], linkedEntities[]}` | Creates PROPOSED contract (operator authoring; type validated against allowlist; ≥2 parties, ≥1 commitment per party). Monitors created SUSPENDED; guards created `active=false`. 201 + detail. |
| `PATCH /contracts/{id}` | partial of create shape | Legal ONLY while PROPOSED **and zero responses** (ADR D7) else 409 `CONTRACT_CONTENT_FROZEN`. |
| `POST /contracts/{id}/respond` | `{partyId!, response! (ACCEPTED\|DECLINED), declineTriggerId?, declineReasoning (required iff DECLINED)}` | Records immutable response; may transition per §2.2. Duplicate identical → 200 idempotent; conflicting → 409 `RESPONSE_CONFLICT`. Decline path returns **200**. |
| `POST /contracts/{id}/decline` | `{declineTriggerId!, reasoning!, partyId?}` | §6.1. 200 always on success-path; 409 only for `INVALID_CONTRACT_STATE`/`CONTRACT_DENIAL(REVIEW_BOUNDARY)`. |
| `POST /commitments/{id}/attest` | `{periodKey!, complianceClaim!, statement?, evidenceRefs?}` | §5.5 upsert. 200 with attestation. |
| `POST /contracts/{id}/metric-samples` | `{partyId!, metricKey!, value!, periodKey!}` | §5.5 upsert. |
| `POST /breaches/{id}/acknowledge` | `{note?}` | → ACKNOWLEDGED (named human actor from persona context). |
| `POST /breaches/{id}/remediate` | `{evidenceRefs!}` | → REMEDIATED; commitment → COMPLIANT. |
| `POST /breaches/{id}/waive` | `{rationale!}` | → WAIVED; human-only (agents 403/denied); hash-chain mirrored. |
| `POST /contracts/{id}/monitors/{mid}/run-now` | — | Operator manual trigger; returns the run row. Also the user-runnable acceptance lever. |

### 7.3 `ContractDetailResponse` — supersets the `BeaconContract` fixture shape (S5 swap is an adapter, not a redesign)

```jsonc
{
  "id": "uuid", "contractKey": "co-tribal-knowledge-depopulation",
  "type": "cross-portfolio-opportunity", "title": "...", "context": "...",
  "status": "PROPOSED", "proposedAt": "2026-04-01T00:00:00Z", "proposedBy": "...",
  "activatedAt": null, "resolvedAt": null,
  "estimatedValue": "$1.2M annually across four subsidiaries", "estimatedValueAmount": null,
  "parties": [{
    "id": "uuid", "partyLabel": "Beacon Corporate Operations", "partyType": "ORG_UNIT",
    "boundEntities": [{"entityType":"DOMAIN","entityId":"uuid","slug":null,"label":null}],
    "responseRequired": true,
    "response": null,                          // or {response, respondedBy, respondedAt, declineTriggerId, declineReasoning}
    "commitments": [{"id":"uuid","text":"Provide the knowledge-depopulation methodology and template",
                     "dueBy":null,"cadence":null,"complianceState":"UNEVALUATED","lastEvaluatedAt":null}]
  }],
  "enforcement": {
    "monitor": "<monitor_text VERBATIM>",
    "monitorMechanism": "<monitor_mechanism VERBATIM — UI font-mono panel renders unchanged>",
    "declineTriggers": [{"id":"uuid","text":"...","armed":false,"invocableByPartyId":null}]
  },
  "linkedEntities": [{"entityType":"SUBSIDIARY_SLUG","entityId":null,"slug":"curasync-clinical","label":"Curasync Clinical Systems"}],
  "monitors": [{"id":"uuid","name":"missing-monthly-report","ruleType":"ATTESTATION_CADENCE","status":"SUSPENDED",
                "cadence":"MONTHLY","graceDays":7,"lastRunAt":null,"nextRunAt":null,"lastOutcome":null}],
  "breachSummary": {"open": 0, "bySeverity": {}},
  "reviewBoundary": null
}
```

Frontend adapter (3a, at the documented `BEACON_PORTFOLIO` swap point): `BeaconContract.id ← contractKey`; `parties[].commitments` (string[]) ← `commitments[].text` in `sequenceOrder`; `enforcement.monitor/monitorMechanism/declineTriggers` ← verbatim text fields; `estimatedValue ← estimatedValue`; `linkedSubsidiarySlugs ← linkedEntities[].slug` (type SUBSIDIARY_SLUG); `proposedAt ←` date part of proposedAt. Review/Decline buttons remain `disabled` + `aria-disabled` (queue #28 — do NOT wire them; the endpoints are operator/MCP-only until Mike's session).

---

## 8. MCP server — `substrate-contracts`

### 8.1 Hosting

Official MCP Java SDK, **Streamable HTTP** transport mounted at `/mcp` by the app module (`app.mcp.ContractsMcpConfig`); tool logic lives in engine services — the app-side classes are thin adapters (keeps the MCP SDK out of `engine`'s `java-library` dependency graph). Identity: Phase 1–4 localhost trust + `X-MCP-Principal` header → `Principal(AGENT, headerValue)`; documented as spoofable (S9-equivalent); Phase 5 OIDC client-credentials binds to `contract_parties.mcp_principal_id`. Gated by `substrate.contracts.mcp.enabled`.

### 8.2 Read tools (ungoverned; built FIRST, phase 3a)

| Tool | Params | Returns |
|---|---|---|
| `list_contracts` | `domain_id?, status?, party?` | card summaries (§7.1 shape) |
| `get_contract` | `contract_key!` | full `ContractDetailResponse` JSON — parties+commitments+compliance, enforcement text VERBATIM, triggers (with armed standing), responses, open breaches |
| `get_commitments` | `contract_key?, party?, compliance_state?` | commitment rows with state |
| `get_obligations_for_principal` | — (caller identity) | what the calling party owes now: open commitments, due dates, cadence, next period_key, missing attestations. The agent-ergonomics keystone. |
| `get_breaches` | `contract_key?, status?` | breach rows |
| `get_enforcement_decisions` | `contract_key?, actor?, since?` | decision log rows |
| `check_action` | `action_type!, target_entity_type?, target_entity_id?, payload?` | advisory dry-run of the PDP: `{verdict, matchedClauses[], explanation}`. Cooperative preflight; the real check still happens at write. Never rows a decision (metrics counter only). |

### 8.3 Governed write tools (every call transits `ContractGateway`; phase 3b/3c)

| Tool | Params | Notes |
|---|---|---|
| `attest_commitment` | `commitment_id!, period_key!, compliance_claim!, statement?, evidence_refs?` | idempotent upsert (§5.5) |
| `record_commitment_progress` | `commitment_id!, note!, evidence_refs?` | appends `COMMITMENT_PROGRESS` event only |
| `report_metric` | `contract_key!, metric_key!, value!, period_key!` | feeds METRIC_THRESHOLD evaluators + arming (ADR D11); party resolved from principal |
| `respond_to_contract` | `contract_key!, party_id!, response!, decline_trigger_id?, decline_reasoning?` | §7.2 respond semantics |
| `invoke_decline` | `contract_key!, decline_trigger_id!, reasoning!` | first-class legitimate terminal action; success result, never `isError` |
| `acknowledge_breach` | `breach_id!, note?` | |
| `mark_breach_remediated` | `breach_id!, evidence_refs!` | |
| `waive_breach` | `breach_id!, rationale!` | **built-in non-removable guard denies AGENT principals** (ADR D16) |

No `adherence_ping` inbound tool: adherence liveness is the outward `MCP_PROBE` monitor (ADR D11). Contract authoring has no MCP tools (REST-only, ADR D8).

---

## 9. Service decomposition (engine module, `com.meridian.substrate.engine.contract`)

```
engine.contract.registry
  ContractRegistryService     authoring create/patch (freeze rule), respond, lifecycle transitions, invoke_decline
  ContractReadService         detail/card/obligations read models; idOrKey resolution
  ContractEventService        append-only event writes; hash-chain mirror calls (delegates to engine.decision DecisionLogService under its per-domain append lock)
  ContractSignalService       computes SignalsResponse (§11.2) at read time
engine.contract.gateway
  ContractGateway             PDP (§4.3)
  GovernedActionAspect        @Governed AOP PEP
  Governed / Ungoverned / UngovernedModule   annotations
  GovernedContextResolver     per-service principal/domain/target resolution beans
  GuardCache                  per-domain active-guard cache + invalidation listener
  TinyDslEvaluator            §4.4
  EnforcementDecisionWriter   REQUIRES_NEW decision rows + metrics counters
  ContractDeniedException
engine.contract.monitor
  ContractMonitorScheduler    ShedLock sweep (§5.2)
  MonitorEvaluator (SPI) + AttestationCadenceEvaluator / MetricThresholdEvaluator / McpProbeEvaluator / UnstructuredEvaluator
  DeclineTriggerArmingService arming pass (§2.6)
  BreachService               open/refresh/surface + human verbs (§5.4)
  AttestationService, MetricSampleService (§5.5)
  ContractMetricProvider (SPI, empty v1)   future pull-based feeds — extension point, do not implement
engine.contract.events (Spring application events, published after commit)
  ContractActivatedEvent, ContractDeclinedEvent, BreachSurfacedEvent, DeclineTriggerArmedEvent, MonitorErrorEvent
app module
  api.v1.controller.ContractController / ContractAdminController (§7)
  mcp.ContractsMcpConfig + tool adapter classes (§8)
  demo: ContractSeeder added to DemoPack (§14.2)
```

The cascade engine MAY consume `ContractDeclinedEvent`; auto-cascade creation is NOT implemented (queue #24 / plan open decision #2). Reviewers must reject any wiring beyond a listener stub.

---

## 10. Concurrency and idempotency mechanics

1. **Lifecycle transitions** (respond, decline, activate): `findByIdForUpdate` (PESSIMISTIC_WRITE) on the contract row; the last-required-acceptance check happens under the lock so two concurrent accepts cannot double-activate; H2/PG both map to `SELECT … FOR UPDATE`.
2. **Party response**: DB `UNIQUE(party_id)`; on violation re-read — identical response ⇒ idempotent 200, different ⇒ 409 `RESPONSE_CONFLICT`.
3. **Scheduler**: ShedLock leases the sweep (single runner); per-monitor claim via `FOR UPDATE SKIP LOCKED` native query on PG, plain `ForUpdate` JPQL on H2 (dev single-node). `next_run_at` persisted ⇒ restart-safe.
4. **Gateway decision rows**: written in `REQUIRES_NEW` so DENY rows survive the business-transaction rollback. Latency measured around evaluate only.
5. **Attestation/metric upserts**: catch unique-violation → re-read → update (optimistic @Version retry once).
6. **Breach dedup**: service `findOpenBySourceForUpdate` + PG partial unique index backstop (§3.3); on PG constraint violation, retry as refresh.
7. **Hash-chain mirror**: only via `DecisionLogService` under its existing per-domain append lock; contract events NEVER write the chain directly. Bounded to activation/decline/waiver (ADR D14).
8. **Guard cache**: invalidated in-process on `CONTRACT_ACTIVATED|DECLINED` and guard-mutation events; 30 s TTL backstop (single-node assumption; multi-node needs a bus — out of scope, documented).
9. **Append-only enforcement**: no update/delete repo methods for `contract_events`, `contract_monitor_runs`, `contract_party_responses`, `contract_enforcement_decisions`; ArchUnit rule pins this.

---

## 11. Notification hooks and read-model wiring (fixture seams, port-map §5)

### 11.1 Hooks

Spring application events (§9) are the v1 notification seam — consumed by: signal computation (read-time), the optional orchestrator remediation hook (`substrate.contracts.orchestrator-remediation`), and future email/webhook channels (extension point, not built). Breach SURFACED and DECLINE_ELIGIBLE are the two leader-attention signal classes; needs-input (`ADHERENCE_STALE|ADHERENCE_UNOBSERVABLE|HUMAN_VERIFY_DUE`) is a third, rendered distinctly from compliant (ADR D10 — never down-render to green).

### 11.2 `GET /contracts/signals?domainId` — severs fixture stat seam S6

```jsonc
{
  "pendingFederationInvitations": 1,   // PROPOSED federation-invitation contracts where this domain's party has no response row
  "openBreaches": {"total": 0, "bySeverity": {}},
  "declineEligible": [],               // armed triggers: {contractKey, triggerId, text}
  "needsInput": [],                    // {contractKey, monitorId, kind: ADHERENCE_STALE|ADHERENCE_UNOBSERVABLE|HUMAN_VERIFY_DUE}
  "monitorCoverage": {"structured": 2, "unstructured": 0},   // ADR D10 visibility ratio
  "coordinationPatternsThisWeek": 6,   // contract_events + cascade events count, trailing 7 days (computed, not stamped)
  "reviewBoundariesDue": []            // only for contracts with non-null review_boundary
}
```

Computed at read time from existing tables — no extra signal table in v1. The `PortfolioContextBand` / Operating-Partner signal strip swaps from fixture constants to this endpoint (S6: `pendingFederationInvitations`, `coordinationPatternsThisWeek` become engine-computed).

### 11.3 ContractDetailView swap (S5)

Phase 3a replaces the fixture import at the `BEACON_PORTFOLIO` factory swap point with a React Query hook + the §7.3 adapter. `BeaconCrossOpportunity.contractId` click-throughs resolve by `contractKey`. Unknown key → existing 404 fallback panel. Inert Review/Decline buttons keep current rendering (queue #28).

---

## 12. Degraded mode

Identical by construction (ADR D12): gateway, monitors, attestations, metrics, breaches, MCP server, signals all run on Postgres alone. With `substrate.orchestrator.enabled=false` the only differences: no AI clause drafting affordance (not built in v1 anyway), no remediation `submit_task` hook, and the `apply_dispatch_result` PEP simply never fires (no dispatches exist). Do not add conditional paths beyond these.

---

## 13. Delivery sequencing and user-runnable acceptance (ADR D18)

| Phase | Scope | User-runnable acceptance (Tester-runnable, real input, no report-only completions) |
|---|---|---|
| **3a** | V5 + entities + repos + read REST + MCP read tools + DemoPack seeds + ContractDetailView swap | `gradlew :app:bootRun` (dev, `substrate.demo.seed=true`) then: `curl :8080/api/v1/contracts/co-tribal-knowledge-depopulation` returns detail content-equivalent to the fixture (§14.1 vectors); MCP client (`npx @modelcontextprotocol/inspector` or scripted Streamable-HTTP client) `get_contract` returns same; screenshot harness captures the swapped ContractDetailView pixel-comparable to fixture render. Zero contracts when `substrate.demo.seed=false`. |
| **3b** | scheduler + evaluators + attestations + metrics + breaches + signals + respond/decline endpoints (detect, don't block) | with `substrate.demo.contracts.activate=true` and an injected fixed clock advanced past 2026-05-08 (April period end + 7 grace days): `POST .../monitors/{missing-monthly-report}/run-now` → run outcome BREACH_DETECTED; `GET .../breaches` shows DETECTED→SURFACED; `POST /commitments/{id}/attest {periodKey:"2026-05", complianceClaim:"ON_TRACK"}` then run-now → COMPLIANT; `GET /contracts/signals` shows the open-breach count move. `POST .../decline` returns **200** and status DECLINED with monitors SUSPENDED. METRIC_THRESHOLD monitor with one party's samples → INDETERMINATE + `ADHERENCE_STALE` in signals (hard requirement: zero-yield surfaces, never silent). |
| **3c** | gateway + @Governed retrofit + ArchUnit gate + governed MCP write tools; mode=shadow default | seed a guard-bearing contract (e.g. `agent.promote` DENY guard); `check_action` returns BLOCK verdict; in shadow: the governed REST write succeeds AND `GET .../decisions` shows WOULD_DENY; flip `substrate.contracts.enforcement.mode=enforce`: same write → 409 `CONTRACT_DENIAL`; same over MCP → `isError` structured payload; `waive_breach` as AGENT principal → denied. ArchUnit suite green in CI. |

Gradle/test gate: `gradlew build` green at every chunk boundary; Testcontainers-Postgres suite in CI is the acceptance gate — **H2-green is never acceptance** (no Docker on this dev machine; do not claim local verification of PG-only behavior).

---

## 14. Test plan

### 14.1 Fixture vectors (engine test cases + DemoPack seed content)

**Source of truth: `C:/Users/mbgol/dev/meridian-homepoc/frontend/src/demo/beacon/contracts.ts` — the FILE, not prose.** Known narrative-drift trap (port-map §6 caveat + appendix #9): the governance-3 spec's "suggested content" wording differs from the shipped fixture ("Implementer can refine wording"), and walkthrough prose has drifted from fixtures before (the cascade c-tribal-succession case). Copy strings from the fixture file at test-authoring time and record the source path + copy date in a test-file header comment. Beacon fixture data is fictional — DemoPack-gated only (`substrate.demo.seed`), never in a customer engagement build.

Vector 1 — `co-tribal-knowledge-depopulation` (seed status PROPOSED):
- type `cross-portfolio-opportunity`; title `Tribal-knowledge depopulation across four healthcare/insurance subs`; proposedAt 2026-04-01; estimatedValue `$1.2M annually across four subsidiaries`.
- 3 parties in order: `Beacon Corporate Operations` (3 commitments), `Curasync Clinical Systems` (3 — third is `Report monthly progress against the Beacon-provided template`), `Sentari, Tellix, Bursar (combined obligation)` (partyType COMBINED, 2 commitments).
- monitor_text + monitor_mechanism VERBATIM (mechanism: `MCP coordination contract id co-tribal-knowledge-depopulation; substrate auto-flags subsidiary missing-monthly-report after 7 grace days; surface to Operating Partner dashboard.`).
- 3 decline triggers, all predicate-null (human judgment).
- entity links: slugs `curasync-clinical, sentari-lab, tellix-sensors, bursar-campus`.
- Compiled monitor: `missing-monthly-report` = ATTESTATION_CADENCE, cadence MONTHLY, graceDays 7, surfaceTo OPERATING_PARTNER_DASHBOARD, bound to the Curasync monthly-report commitment.

Vector 2 — `fi-shared-pre-fill-agent` (seed status PROPOSED):
- type `federation-invitation`; title `Shared compliance-form pre-fill agent (Polestar invites Curasync)`; proposedAt 2026-04-15; NO estimatedValue (assert null serialization preserved — UI hides the value aside).
- 2 parties: `Polestar Life Admin (federation host)` (3 commitments), `Curasync Clinical Systems` (3 commitments).
- mechanism VERBATIM: `MCP federation contract id fi-shared-pre-fill-agent; substrate auto-flags utilization imbalance > 30% between parties for review.`
- Compiled monitor: `utilization-imbalance` = METRIC_THRESHOLD, metricKey `utilization_share`, threshold 0.30, cadence QUARTERLY, requiredPartyIds = both parties. Seeded with zero samples ⇒ first run INDETERMINATE (pin this).
- 3 decline triggers: `Polestar agent reaches accuracy threshold below 90%` gets predicate `{"metric":"accuracy","lt":0.90}` (the arming demo); `Cost-share imbalance > 30% sustained over two quarters` stays predicate-null (temporal semantics beyond v1 DSL — pin a comment referencing ADR D9); the HIPAA-framework trigger predicate-null.
- entity links: `curasync-clinical, polestar-life`; `review_boundary = null` (assert decline → ALLOW_WITH_FLAG `REVIEW_BOUNDARY_UNCONFIGURED`).
- `pendingFederationInvitations`: with this contract PROPOSED and Curasync unresponded, signals for the Curasync domain = 1.

### 14.2 DemoPack

`ContractSeeder` (after DomainSeeder in the topological order) seeds both vectors PROPOSED against the isDemo domain. `substrate.demo.contracts.activate=true` additionally records ACCEPTED responses for all required parties of vector 1 (activating it: monitors ACTIVE, guards live) — the 3b demo lever. Seeder idempotent on re-boot (keyed by `(domain, contract_key)`).

### 14.3 Unit (H2 fast lane)

- State machines: every legal transition of §2.2–2.6 + every illegal transition rejected with the right 409 code; DECLINED suspends monitors + deactivates guards atomically; WITHDRAWN/CONCLUDED unreachable (no code path constructs them — assert via exhaustive transition table).
- Content freeze: PATCH allowed pre-response; rejected `CONTRACT_CONTENT_FROZEN` after first response even while PROPOSED.
- TinyDsl table tests: all comparators, combinators, unknown-node ⇒ INDETERMINATE-not-match, dot-path resolution, metric leaf.
- Period keys: MONTHLY/QUARTERLY boundaries, zone config, grace arithmetic (fixed Clock).
- Evaluators: AttestationCadence matrix (attested ON_TRACK/COMPLETE/AT_RISK/MISSED × in-grace/past-grace/no-attestation ⇒ outcomes per §5.3); MetricThreshold complete/partial/over/under; Unstructured always INDETERMINATE+signal; McpProbe reachable/unreachable.
- INDETERMINATE-never-breaches invariant: property-style test across all evaluators — no run with outcome INDETERMINATE/NOT_DUE/ERROR creates or refreshes a breach.
- Decline-trigger arming: predicate true ⇒ armed + event + signal; false ⇒ disarm; predicate-null never armed, always invocable.
- Gateway: effect precedence, REQUIRE_ATTESTATION with/without current-period attestation, shadow vs enforce vs off, fail posture per principal type, decision-log level matched-only vs all.
- Signals computation incl. monitorCoverage ratio and pendingFederationInvitations.
- Adapter test: ContractDetailResponse → BeaconContract adapter output deep-equals the fixture objects for both vectors (frontend vitest).

### 14.4 Integration (Testcontainers-Postgres, CI lane — the gate)

- All 14 entities round-trip under `ddl-auto=validate` (V5 + V5_1 applied; JSON columns + optimistic locking included) — extends the existing entity round-trip suite.
- Gateway rollback semantics: governed write that DENYs in enforce mode rolls back the business mutation AND persists the DENY decision row (REQUIRES_NEW proof).
- Activation race: two concurrent final-party accepts ⇒ exactly one ACTIVE transition, one activation event, one hash anchor.
- Attestation upsert race: concurrent `attest` for same (commitment, period) ⇒ one row, two events.
- Breach dedup race: concurrent BREACH_DETECTED for same source ⇒ one open row (partial-index backstop fires), `last_confirmed_at` refreshed.
- ShedLock lease: second sweep instance is a no-op while leased.
- `FOR UPDATE SKIP LOCKED` claim query runs on PG (native query smoke).
- Hash-chain mirror: pinned vector test — known contract activation/decline/waiver sequence produces pinned hashes; chain `verify` stays green; concurrent contract-event + design-decision appends do not fork (port-map trap #3).
- Envelope/exception contract: 409 `CONTRACT_DENIAL` shape, decline returns 200, `GET /v3/api-docs` not double-wrapped (existing advice scoping holds with new controllers).

### 14.5 MCP suite

Scripted Streamable-HTTP client (test scope): `list_contracts`/`get_contract` parity with REST; `get_obligations_for_principal` for a bound principal; `attest_commitment` idempotency; `report_metric` then METRIC_THRESHOLD flip from INDETERMINATE→COMPLIANT/BREACH_DETECTED; `invoke_decline` success-shape (NOT isError); `waive_breach` as AGENT ⇒ structured denial; `check_action` verdict parity with a subsequent real write in enforce mode.

### 14.6 ArchUnit suite (3c, CI)

Governed-coverage rule (§4.2); append-only repo rule (§10.9); module rule (engine never imports app; ingestion never imports engine — existing); `@UngovernedModule` present on `IngestionApplication` with the ADR D3 reason string.

---

## 15. Flag-not-invent — explicit UNKNOWN-semantics extension points

These are modeled as extension points. Encoding invariants for any of them is a spec violation; each names its unblock trigger.

| # | Unknown | v1 modeling | Unblocks via |
|---|---|---|---|
| 1 | Federation review-boundary semantics (what "quarterly review boundary" means) | `review_boundary` JSON nullable; **unenforced when null** → ALLOW + `ALLOW_WITH_FLAG` | first engagement defining boundaries; Mike session |
| 2 | Contract completion / withdrawal | `WITHDRAWN`, `CONCLUDED` schema-reserved, transition-unreachable | first contract reaching natural end |
| 3 | Review/Decline product UX (queue #28) | endpoints + MCP tools exist; UI affordances stay inert | Mike product-design session |
| 4 | Decline → cascade propagation (queue #24 / plan open decision #2) | `ContractDeclinedEvent` published; NO auto-cascade | cascade-authoring session |
| 5 | Decline while breaches open (escape-hatch question) | allowed + `ALLOW_WITH_FLAG OPEN_BREACHES_AT_DECLINE`; breaches survive | same product session |
| 6 | Predicates beyond equality/threshold (incl. `sustainedFor` — fi fixture trigger) | predicate-null (human judgment); `GuardPredicate`/DSL SPI | ADR D9 revisit trigger = port-map §4.5 JSONB flip condition |
| 7 | Breach auto-close on monitor PASS | `BREACH_COMPLIANCE_RESTORED` event only; human REMEDIATED required | operator feedback; product session |
| 8 | Pull-based metric feeds / real telemetry | push-only (`report_metric` + samples table); `ContractMetricProvider` SPI empty | Phase 4+ reader/connector framework |
| 9 | Quorum/weighted acceptance for many-party contracts | unanimity of `response_required` parties only | first many-party engagement |
| 10 | Cross-engagement `contract_key` namespacing | unique per domain only | first federated multi-engagement |
| 11 | Decision-log retention/rollup | none (matched-only default limits volume) | before first high-traffic agent engagement (ADR D17) |
| 12 | Party identity hardening (spoofable headers/principals) | recorded verbatim; advisory authority checks | Phase 5 OIDC |
| 13 | Notification channels beyond dashboard signals | application events only | observability/Phase 5 |
| 14 | Monitor/Automate lifecycle-phase entry criteria (port-map `[LIFECYCLE-MACHINE]`) | contracts do NOT gate lifecycle phases; no coupling built | doc 02 semantics landing |

---

## 16. Carried risks (from ADR Consequences — implementation-relevant reminders)

- Both fixture contracts compile to **zero blocking guards** — gateway value demos need the synthetic guard-bearing contract (§13 3c). Do not let demo pressure invent guards on the fixture contracts.
- `@Governed` retrofit blast radius: land behind shadow mode; ArchUnit gate in CI same PR.
- UNSTRUCTURED monitors rubber-stamped by humans show green without checking — the `monitorCoverage` signal exists precisely so this is visible; never drop it from the dashboard.
- This dev machine has no Docker: PG-only behaviors (partial index, SKIP LOCKED, rollback semantics, ShedLock) are CI-verified ONLY. Claiming local verification of these is a false-PASS.
