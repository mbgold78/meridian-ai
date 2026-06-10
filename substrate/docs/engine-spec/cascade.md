# Engine Spec — Cascading Evaluation Engine (`engine.cascade`)

**Status:** implementation-ready. Governing ADR: `substrate/docs/adr/ADR-001-cascade-engine.md` (Accepted — autonomous, revisable; Mike review pending).
**Binding inputs:** `substrate/docs/port-map.md` §6 [CASCADE-*] blocks + §2.7 + §4 (Postgres strategy) + §5 (fixture seams), `substrate/docs/orchestrator-substrate-contract.md`, plan §9 Phase 2, homepoc fixture `meridian-homepoc/frontend/src/demo/beacon/cascades.ts` + `types.ts`, checkpoint-queue #21 (resolved: destructive = leader-attention), #22/#23/#24 (open, gating).
**Audience:** implementation agents. This spec is the design; do not re-derive decisions. Anything marked **EXTENSION POINT** is deliberately NOT designed — flag, don't invent (§13).

Top-level non-negotiables:
1. **Decline is never an error.** No ERROR/FAILED value exists in any cascade enum. DECLINE and RESOLVE(declined) return HTTP 200 with success/neutral envelope semantics. `resolved-declined` is a peer terminal state to `resolved-looks-good`.
2. **`cascade_level_actions` is append-only.** No UPDATE/DELETE anywhere (service discipline + PG trigger). The level chain IS the audit trail, authored as the work happens.
3. **Read-model-first build order** (§12). Leader-grade authoring UX is gated on the queue-#24 Mike session; the write API here is deliberately operator-grade and shape-neutral.
4. **Degraded mode:** with `substrate.orchestrator.enabled=false` everything works except `POST /api/v1/cascade-drafts/{id}/suggest` (404). No other orchestrator coupling exists.
5. **Wire vocabulary is the fixture vocabulary** (kebab-case via `@JsonValue`).

---

## 1. Module / package layout

```
common/src/main/java/com/meridian/substrate/common/domain/
  Cascade.java  CascadeLevel.java  CascadeLevelAction.java
  CascadeDraft.java  CascadeNotification.java
common/src/main/java/com/meridian/substrate/common/domain/enums/
  CascadeState.java  CascadeLevelState.java  CascadeActionType.java
  CascadeAuthoringMode.java  CascadeNotificationType.java
  CascadeAttentionSignal.java  CascadeDraftStatus.java  CascadeSuggestionState.java
common/src/main/java/com/meridian/substrate/common/repo/
  CascadeRepository.java  CascadeLevelRepository.java  CascadeLevelActionRepository.java
  CascadeDraftRepository.java  CascadeNotificationRepository.java
engine/src/main/java/com/meridian/substrate/engine/cascade/
  CascadeLifecycleService.java      // state machine + locking + outbox (all writes)
  CascadeQueryService.java          // read DTOs
  CascadeProjectionRebuilder.java   // deterministic replay of action log → projections
  CascadeDraftService.java
  OrchestratorSuggestClient.java    // @ConditionalOnProperty substrate.orchestrator.enabled
  CascadeNotificationService.java   // feed reads, mark-read, dedupe-key insert helper
  CascadeEscalationSweeper.java     // @Scheduled, idempotent
  CascadeAttestationHook.java       // interface; NoopCascadeAttestationHook default bean
  CascadeProperties.java            // @ConfigurationProperties("substrate.cascade")
  read/  (response DTO records)
  exception/ CascadeConflictException.java  CascadeForbiddenException.java
app/src/main/java/com/meridian/substrate/app/api/v1/
  controller/CascadeController.java  controller/CascadeDraftController.java
  request/   (request records — validation annotations live HERE only, per port rule)
app/src/main/java/com/meridian/substrate/app/demo/
  CascadeDemoSeeder.java            // DemoPack action-stream replay (§11.4)
app/src/main/resources/db/migration/common/V4__cascades.sql
app/src/main/resources/db/migration/postgresql/V4_1__cascade_actions_append_only_trigger.sql
```

Entity conventions: extend `BaseEntity` (UUID id GenerationType.UUID, `@Version Long version NOT NULL`, createdAt/updatedAt, createdBy default "system"); FK modeled as writable UUID column + `@JsonIgnore @ManyToOne(LAZY, insertable=false, updatable=false)` navigation twin; **no Lombok on entities**; JSON columns via `JsonNodeConverter` → TEXT (port-map §4.5 — jsonb flip is a documented later one-pass migration); soft references (member ids, finding id, affected domain id) have NO DB FKs — app-enforced, comment in code.

Clock: every engine service takes the Spring `java.time.Clock` bean (dev/prod = system clock; DemoPack and tests inject fixed clocks — fixture seam S15).

---

## 2. Enums (exact values + wire serialization)

| Enum | Values | `@JsonValue` wire form |
|---|---|---|
| `CascadeState` | `IN_FLIGHT, RESOLVED_LOOKS_GOOD, RESOLVED_DECLINED` | `in-flight`, `resolved-looks-good`, `resolved-declined` |
| `CascadeLevelState` | `LOOKING, ABSORBED, CASCADED, DECLINED` | `looking`, `absorbed`, `cascaded`, `declined` |
| `CascadeActionType` | `ISSUE, CASCADE, ABSORB, DECLINE, RECASCADE, RESOLVE_LOOKS_GOOD, RESOLVE_DECLINED` | kebab-case |
| `CascadeAuthoringMode` | `FREE_FORM, IMPERATIVE, AI_SUGGESTED` | kebab-case |
| `CascadeDraftStatus` | `OPEN, ISSUED, DISCARDED` | kebab-case |
| `CascadeSuggestionState` | `NONE, PENDING, READY, ERROR` (AI-assist *plumbing* status only — never governance state) | kebab-case |
| `CascadeNotificationType` | `CASCADE_ISSUED, LEVEL_ACTIVATED, LEVEL_CASCADED, LEVEL_ABSORBED, LEVEL_DECLINED, DECLINE_PENDING_ORIGINATOR, SIBLING_DECLINE, LEVEL_OVERDUE, DECLINE_OVERDUE, CASCADE_RESOLVED_LOOKS_GOOD, CASCADE_RESOLVED_DECLINED` | kebab-case |
| `CascadeAttentionSignal` | `DECLINE_PENDING, ESCALATING, ABSORBED, RESOLVED_LOOKS_GOOD, RESOLVED_DECLINED` | `decline-pending`, `escalating`, `absorbed`, `resolved-looks-good`, `resolved-declined` |

`ProducedBy` (existing enum) is reused on `cascades`: `HUMAN` or `AI_ASSISTIVE` only (an issued cascade is always human-promoted; `AI_GENERATIVE` appears only inside draft suggestion provenance). DB stores enum **names** (`VARCHAR`); kebab-case is JSON-only.

Attention signal derivation (projection, recomputed on every transition):
- terminal → `RESOLVED_LOOKS_GOOD` / `RESOLVED_DECLINED`
- frontier state `DECLINED` → `DECLINE_PENDING` (destructive surface = leader-attention, queue #21 resolved)
- frontier state `LOOKING` → `ESCALATING` (info; equals the fixtures' "bottom acted level = cascaded, response still out")
- frontier state `ABSORBED` → `ABSORBED` (success tone, pending originator review)
- frontier state can never be `CASCADED` while in-flight (invariant I6).
- `overdue` is a separate boolean overlay flag, never a signal value.

---

## 3. Persistence — `V4__cascades.sql` (ANSI-portable; H2 PG-mode + Postgres)

All tables carry BaseEntity columns: `id UUID PRIMARY KEY, version BIGINT NOT NULL, created_at TIMESTAMP WITH TIME ZONE, updated_at TIMESTAMP WITH TIME ZONE, created_by VARCHAR(255)`. Shown below are the domain columns only. Types restricted to UUID / VARCHAR / TEXT / INT / BIGINT / BOOLEAN / TIMESTAMP WITH TIME ZONE (valid on both vendors).

### 3.1 `cascades` — aggregate root (projection fields marked ⟲ are rebuildable from actions)

```sql
CREATE TABLE cascades (
  -- identity & scope
  domain_id                 UUID NOT NULL,            -- engagement org scope (holding-co domain)
  cascade_group_id          UUID,                     -- ADR D2: optional parallel-cascade group
  predecessor_cascade_id    UUID,                     -- reissue lineage (soft ref)
  -- immutable issuance artifacts (CASCADE-DURABILITY; never updated after insert)
  origin_owner_member_id    UUID,                     -- soft ref organization_members
  origin_owner_label        VARCHAR(255) NOT NULL,
  originated_at             TIMESTAMP WITH TIME ZONE NOT NULL,
  original_critique         TEXT NOT NULL,
  acceptance_criteria       TEXT NOT NULL,
  summary                   VARCHAR(500),             -- reserved, unrendered (queue #22)
  affected_domain_id        UUID,                     -- soft ref domains (Beacon subsidiary IS a Domain)
  origin_finding_id         UUID,                     -- soft ref findings (authoring-from-finding, UX undesigned)
  authoring_mode            VARCHAR(20) NOT NULL,
  produced_by               VARCHAR(20) NOT NULL,     -- HUMAN | AI_ASSISTIVE
  draft_id                  UUID,                     -- soft ref cascade_drafts
  response_sla_days         INT,                      -- NULL = engagement default applied at issue; 0 = disabled
  -- lifecycle projections ⟲
  current_state             VARCHAR(30) NOT NULL,     -- CascadeState
  chain_seq                 INT NOT NULL DEFAULT 0,   -- fencing token, +1 per action
  frontier_depth            INT NOT NULL,             -- bottom-of-chain (see §5.1)
  frontier_state            VARCHAR(20) NOT NULL,     -- CascadeLevelState of frontier level
  attention_signal          VARCHAR(30) NOT NULL,     -- CascadeAttentionSignal
  overdue                   BOOLEAN NOT NULL DEFAULT FALSE,
  -- resolution accountability record ⟲ (from RESOLVE_* action)
  resolved_at               TIMESTAMP WITH TIME ZONE,
  resolved_by_member_id     UUID,
  resolved_by_label         VARCHAR(255),
  resolution_note           TEXT,
  accepted_decline_action_id UUID                     -- soft ref cascade_level_actions; set iff RESOLVED_DECLINED
);
CREATE INDEX idx_cascades_domain_id ON cascades(domain_id);
CREATE INDEX idx_cascades_state     ON cascades(domain_id, current_state);
CREATE INDEX idx_cascades_group     ON cascades(cascade_group_id);
CREATE INDEX idx_cascades_origin    ON cascades(origin_owner_member_id);
```

### 3.2 `cascade_levels` — one row per depth (mutable projection ⟲; truth in actions)

```sql
CREATE TABLE cascade_levels (
  cascade_id        UUID NOT NULL,
  depth             INT  NOT NULL,                    -- 0 = originator; contiguous 0..N
  owner_member_id   UUID,                             -- soft ref; current-round owner
  owner_label       VARCHAR(255) NOT NULL,
  current_state     VARCHAR(20) NOT NULL,             -- CascadeLevelState
  round             INT NOT NULL DEFAULT 1,           -- incremented on RECASCADE reactivation
  refined_critique  TEXT,                             -- current-round projection (fixture DTO shape)
  decline_reasoning TEXT,                             -- current-round projection
  activated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
  acted_at          TIMESTAMP WITH TIME ZONE,
  response_deadline TIMESTAMP WITH TIME ZONE,         -- NULL when SLA disabled
  overdue           BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT uq_cascade_levels_depth UNIQUE (cascade_id, depth)
);
CREATE INDEX idx_cascade_levels_cascade ON cascade_levels(cascade_id);
```

### 3.3 `cascade_level_actions` — APPEND-ONLY source of truth

```sql
CREATE TABLE cascade_level_actions (
  cascade_id          UUID NOT NULL,
  level_id            UUID NOT NULL,                  -- cascade_levels row acted on (depth 0 for ISSUE/RESOLVE_*)
  depth               INT  NOT NULL,
  round               INT  NOT NULL,                  -- level's round at action time
  action              VARCHAR(30) NOT NULL,           -- CascadeActionType
  actor_member_id     UUID,
  actor_label         VARCHAR(255) NOT NULL,
  refined_critique    TEXT,                           -- ISSUE/CASCADE/ABSORB/RECASCADE
  decline_reasoning   TEXT,                           -- DECLINE
  resolution_note     TEXT,                           -- RESOLVE_LOOKS_GOOD / RESOLVE_DECLINED
  next_owner_member_id UUID,                          -- ISSUE/CASCADE/RECASCADE routing
  next_owner_label    VARCHAR(255),
  accepted_decline_action_id UUID,                    -- RESOLVE_DECLINED only
  acted_at            TIMESTAMP WITH TIME ZONE NOT NULL,
  idempotency_key     VARCHAR(128) NOT NULL,          -- ADR D8: mandatory
  chain_seq           INT NOT NULL,                   -- 1..N per cascade, gapless
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
```

`V4_1__cascade_actions_append_only_trigger.sql` (postgresql dir ONLY — H2 dev stays service-enforced, precedent V2_1):

```sql
CREATE OR REPLACE FUNCTION cascade_actions_append_only() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'cascade_level_actions is append-only (%)', TG_OP; END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_cla_append_only BEFORE UPDATE OR DELETE ON cascade_level_actions
FOR EACH ROW EXECUTE FUNCTION cascade_actions_append_only();
```

### 3.4 `cascade_drafts` — mutable authoring persistence (outside the audit trail)

```sql
CREATE TABLE cascade_drafts (
  domain_id            UUID NOT NULL,
  author_member_id     UUID,
  author_label         VARCHAR(255) NOT NULL,
  original_critique    TEXT,
  acceptance_criteria  TEXT,
  summary              VARCHAR(500),
  affected_domain_id   UUID,
  origin_finding_id    UUID,
  cascade_group_id     UUID,
  authoring_mode       VARCHAR(20) NOT NULL,
  response_sla_days    INT,
  suggested_critique   TEXT,
  suggested_acceptance_criteria TEXT,
  suggestion_provenance TEXT,                        -- JSON (JsonNodeConverter): {taskId, model, eventsDigest, requestedAt, landedAt}
  suggestion_state     VARCHAR(20) NOT NULL DEFAULT 'NONE',
  orchestrator_task_id VARCHAR(255),
  status               VARCHAR(20) NOT NULL DEFAULT 'OPEN',
  issued_cascade_id    UUID
);
CREATE INDEX idx_cascade_drafts_domain ON cascade_drafts(domain_id, status);
```

### 3.5 `cascade_notifications` — transactional outbox (ADR D10: written IN the action tx)

```sql
CREATE TABLE cascade_notifications (
  cascade_id          UUID NOT NULL,
  action_id           UUID,                           -- triggering action; NULL for sweeper rows
  depth               INT,
  notification_type   VARCHAR(40) NOT NULL,
  recipient_member_id UUID,
  recipient_label     VARCHAR(255) NOT NULL,
  payload             TEXT,                           -- JSON (JsonNodeConverter)
  dedupe_key          VARCHAR(160),                   -- NULL legal & non-unique-checked on both vendors
  read_at             TIMESTAMP WITH TIME ZONE,       -- in-app feed
  dispatched_at       TIMESTAMP WITH TIME ZONE,       -- Phase-5 channel watermark
  CONSTRAINT uq_cn_dedupe UNIQUE (dedupe_key)
);
CREATE INDEX idx_cn_recipient ON cascade_notifications(recipient_member_id, read_at);
CREATE INDEX idx_cn_cascade   ON cascade_notifications(cascade_id);
```

Vendor note: multiple NULL `dedupe_key` rows are legal under UNIQUE on both Postgres and H2 PG-mode (NULLs distinct). Verify in the Testcontainers suite anyway (port-map trap #4).

---

## 4. Repositories (`common.repo`)

```java
interface CascadeRepository extends JpaRepository<Cascade, UUID> {
  List<Cascade> findByDomainId(UUID domainId);
  List<Cascade> findByDomainIdAndCurrentState(UUID domainId, CascadeState state);
  List<Cascade> findByCascadeGroupIdAndCurrentState(UUID groupId, CascadeState state);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select c from Cascade c where c.id = :id")
  Optional<Cascade> findByIdForUpdate(UUID id);                    // the per-cascade serialization point
}
interface CascadeLevelRepository extends JpaRepository<CascadeLevel, UUID> {
  List<CascadeLevel> findByCascadeIdOrderByDepthAsc(UUID cascadeId);
  Optional<CascadeLevel> findByCascadeIdAndDepth(UUID cascadeId, int depth);
}
interface CascadeLevelActionRepository extends Repository<CascadeLevelAction, UUID> {  // NO JpaRepository:
  CascadeLevelAction save(CascadeLevelAction a);                   // insert-only surface — no delete/deleteAll inherited
  Optional<CascadeLevelAction> findById(UUID id);
  List<CascadeLevelAction> findByCascadeIdOrderByChainSeqAsc(UUID cascadeId);
  Optional<CascadeLevelAction> findByCascadeIdAndIdempotencyKey(UUID cascadeId, String key);
  long countByCascadeIdAndActedAtAfter(UUID cascadeId, Instant after);
}
interface CascadeDraftRepository extends JpaRepository<CascadeDraft, UUID> {
  List<CascadeDraft> findByDomainIdAndStatus(UUID domainId, CascadeDraftStatus status);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select d from CascadeDraft d where d.id = :id")
  Optional<CascadeDraft> findByIdForUpdate(UUID id);               // issuance serialization point
}
interface CascadeNotificationRepository extends JpaRepository<CascadeNotification, UUID> {
  List<CascadeNotification> findByRecipientMemberIdAndReadAtIsNullOrderByCreatedAtDesc(UUID recipientId);
  List<CascadeNotification> findByCascadeIdOrderByCreatedAtAsc(UUID cascadeId);
  List<CascadeNotification> findByCascadeIdAndNotificationType(UUID cascadeId, CascadeNotificationType type);
  boolean existsByDedupeKey(String dedupeKey);
}
```

The action repository deliberately extends `Repository` (not `JpaRepository`) so no update/delete methods exist anywhere in the type system (ADR D9).

---

## 5. State machine

### 5.1 Definitions

- **Frontier** (`cascades.frontier_depth/frontier_state`): the bottom-of-chain — the single level whose state currently determines the dashboard signal and who may act first-touch. Maintained transitionally (and rebuildable): ISSUE → 1; CASCADE at frontier k → k+1; RECASCADE over declined k+1 → k+1; endorse-DECLINE or ABSORB at k responding to declined k+1 → k; otherwise unchanged.
- **Pending decline**: exists iff `frontier_state = DECLINED`. The pending-decline SET is the maximal contiguous run of DECLINED levels starting at `frontier_depth` and extending downward (deeper). `accepted_decline_action_id` must reference the latest DECLINE action of a level in that run.
- **Act-once discipline**: a level in LOOKING acts exactly once (leaves LOOKING). A level in CASCADED retains exactly the three decline-response verbs, gated on `depth+1 == frontier_depth AND frontier_state == DECLINED`. Levels in ABSORBED or DECLINED never act again. (Yes: endorse-decline is a second action by a CASCADED level — that is the design, ADR D4.)
- **Round**: `cascade_levels.round` starts at 1; RECASCADE reactivation increments the reactivated level's round, resets its projections (state=LOOKING, new owner, refined_critique/decline_reasoning NULL, acted_at NULL, activated_at=now, deadline recomputed, overdue=false). Prior-round facts remain only in the action log.

### 5.2 Aggregate transitions (`cascades.current_state`)

| # | From | Action | Guard | To |
|---|---|---|---|---|
| A1 | *(none)* | ISSUE (via draft `/issue`) | draft OPEN; critique+criteria non-blank; level-1 owner named | IN_FLIGHT |
| A2 | IN_FLIGHT | RESOLVE_LOOKS_GOOD | actor = originator; resolutionNote non-blank; any time in-flight (incl. when acceptanceCriteria enumerated a documented Decline as acceptable — c-vendor-cost pattern) | RESOLVED_LOOKS_GOOD |
| A3 | IN_FLIGHT | RESOLVE_DECLINED | actor = originator; resolutionNote non-blank; a pending decline exists (frontier DECLINED); acceptedDeclineActionId references a DECLINE action in the pending run | RESOLVED_DECLINED |

Both terminal states are absorbing; any further action → 409 `CASCADE_TERMINAL`. No reopen verb (**EXTENSION POINT** §13.8); reissue = new cascade with `predecessor_cascade_id`.

ISSUE mechanics (one tx): create `cascades` row (IN_FLIGHT, chain_seq=1, frontier=1) + L0 level row (depth 0, CASCADED, owner=originator, refined_critique = request `originatorRefinedCritique` defaulting to original_critique) + L1 level row (LOOKING, round 1, owner from request, deadline per SLA) + ISSUE action row (depth 0, chain_seq 1, refined_critique = L0's, next_owner = L1 owner) + notifications (§8) + mark draft ISSUED with `issued_cascade_id`.

### 5.3 Level transitions (`cascade_levels.current_state`)

| # | From (depth k) | Verb / action row | Guard (beyond actor = current owner of depth k, cascade IN_FLIGHT) | Effects |
|---|---|---|---|---|
| L1 | LOOKING → CASCADED | `CASCADE` | k = frontier; refinedCritique non-blank; nextOwner named | depth k+1 LOOKING: created (round 1) or reactivated (round+1) if a stale row exists from an earlier round; frontier = k+1 |
| L2 | LOOKING → ABSORBED | `ABSORB` | k = frontier; refinedCritique non-blank | downward propagation terminates; frontier stays k; cascade remains IN_FLIGHT awaiting originator resolution |
| L3 | LOOKING → DECLINED | `DECLINE` | k = frontier; declineReasoning non-blank | frontier stays k; decline-pending begins (signal flips; notifications §8; sibling advisories ADR D2) |
| L4 | CASCADED → DECLINED *(endorse-decline)* | `DECLINE` | k ≥ 1; k+1 = frontier; frontier DECLINED; own declineReasoning non-blank | frontier = k (decline run grows upward) |
| L5 | CASCADED → CASCADED *(reactivates k+1)* | `RECASCADE` | k+1 = frontier; frontier DECLINED; new refinedCritique non-blank; nextOwner named (may differ from prior owner) | k+1 → LOOKING, round+1, owner per nextOwner; frontier = k+1 |
| L6 | CASCADED → ABSORBED *(absorb-over-decline)* | `ABSORB` | k+1 = frontier; frontier DECLINED; refinedCritique non-blank | level k takes the work; frontier = k; k+1 stays DECLINED in history (its decline is answered, no longer pending) |

L0 (depth 0) is created CASCADED at issuance and has no level verbs; originator verbs are A2/A3 only.

### 5.4 Invariants (enforced in `CascadeLifecycleService`; DB constraints back-stop where portable)

- **I1** At most one level in LOOKING at any instant; zero LOOKING when frontier is DECLINED/ABSORBED or state terminal.
- **I2** Depths are contiguous 0..N; `UNIQUE(cascade_id, depth)`; linear chain, arbitrary N (fixtures exercise 2–3; design for N); no tree in v1.
- **I3** `original_critique` + `acceptance_criteria` immutable after issuance — no code path updates them; visible unmodified at every level (CASCADE-DURABILITY).
- **I4** Payload conditionals (DB CHECK + service): refinedCritique ⟺ ISSUE/CASCADE/ABSORB/RECASCADE; declineReasoning ⟺ DECLINE; resolutionNote ⟺ RESOLVE_*; nextOwner ⟺ ISSUE/CASCADE/RECASCADE.
- **I5** Every action carries actor (member id soft + label) + acted_at (from the injected Clock) + chain_seq (gapless, `UNIQUE(cascade_id, chain_seq)`).
- **I6** Frontier state ∈ {LOOKING, ABSORBED, DECLINED} while IN_FLIGHT (CASCADE always advances the frontier). Assert; never silently repair.
- **I7** Decline never maps to an error: L3/L4 and A3 return HTTP 200; DTO tone fields carry neutral/leader-attention semantics; no failure vocabulary anywhere.
- **I8** `cascade_level_actions` rows are never updated or deleted (service + PG trigger).
- **I9** All mutations for one command commit in ONE transaction: action insert + level projection + aggregate projection + outbox notifications (+ draft flip on ISSUE).
- **I10** chain_seq on the aggregate equals max(action chain_seq); projection watermark for the rebuilder.

### 5.5 Concurrency + idempotency mechanics (ADR D8)

Per-command sequence inside `CascadeLifecycleService` (single `@Transactional` method per verb):
1. `cascadeRepository.findByIdForUpdate(id)` — PESSIMISTIC_WRITE serializes the cascade (PG `SELECT … FOR UPDATE`; honored by H2 PG-mode).
2. Idempotency check under the lock: `findByCascadeIdAndIdempotencyKey` — hit → return the original action's result re-projected as 200 (no new row). The UNIQUE constraint is the backstop.
3. Fencing: if request carries `expectedChainSeq` and it ≠ `cascade.chain_seq` → throw `CascadeConflictException(CASCADE_STALE_STATE)` → 409 with current `CascadeDetail` in the envelope `data` (UI renders "chain moved" refresh, not flakiness).
4. Guard validation (§5.2/5.3) → violations throw `CascadeConflictException(CASCADE_ILLEGAL_TRANSITION)` (409) or `CascadeForbiddenException(CASCADE_NOT_LEVEL_OWNER | CASCADE_NOT_ORIGINATOR)` (403).
5. Append action (chain_seq+1), update projections, write notifications, invoke `CascadeAttestationHook` on RESOLVE_* (no-op default), commit.

Issuance uses `cascadeDraftRepository.findByIdForUpdate` as the lock (no cascade row yet); re-issuing an ISSUED draft returns the existing cascade (200, idempotent).

Two owners responding simultaneously: the lock serializes; the loser either replays idempotently (same key → original 200) or fails its guard → 409 with current state. No retry loops in the engine; the client refreshes.

Actor identity: `PersonaContext` headers (X-Persona-Id → actor_member_id when parseable as UUID, X-Persona-Label / persona display → actor_label) in Phase 2 — spoofable, accepted per S9; OIDC principal replaces the source in Phase 5 with no schema change.

---

## 6. REST API (all under `/api/v1`, `{data, meta, errors}` envelope, controllers in `app`)

Read endpoints ship FIRST (§12) and double as the read-only surface orchestrator-dispatched agents may query (contract doc — agents are advisors; **no agent write path exists**).

### 6.1 Read

| Endpoint | Query params | Returns |
|---|---|---|
| `GET /cascades` | `domainId!`, `state?`, `signal?`, `originOwnerId?`, `affectedDomainId?`, `groupId?` | `CascadeSummary[]` |
| `GET /cascades/{id}` | — | `CascadeDetail` (404 unknown id) |
| `GET /cascades/{id}/actions` | `page?`, `size?` (default all; chain_seq asc) | `CascadeAction[]` — the originator's single-throat-to-choke timeline |
| `GET /cascades/attention` | `domainId!`, `ownerId?` (omit = originator-level view for the Operating Partner strip) | `AttentionItem[]` |
| `GET /cascades/notifications` | `recipientId!`, `unreadOnly?=true` | `CascadeNotificationDto[]` |
| `GET /cascades/stats` | `domainId!` | `CascadeStats` |

```jsonc
// CascadeSummary
{ "id": "…", "originatedBy": "Operating Partner", "originatedByMemberId": null,
  "originatedAt": "2026-04-08T00:00:00Z", "originalCritique": "…", "acceptanceCriteria": "…",
  "summary": null, "currentState": "in-flight", "attentionSignal": "decline-pending",
  "overdue": false, "levelCount": 3, "frontierDepth": 2, "frontierState": "declined",
  "frontierOwnerLabel": "Curasync HIM Director", "lastActivityAt": "2026-04-22T00:00:00Z",
  "affectedDomainId": "…|null", "affectedDomainName": "Curasync Clinical Systems|null",
  "cascadeGroupId": null, "predecessorCascadeId": null, "chainSeq": 4 }

// CascadeDetail = CascadeSummary fields +
{ "levels": [   // ordered by depth asc — 1:1 with the Beacon CascadeLevel view type
    { "level": 0, "ownerLabel": "Operating Partner", "ownerMemberId": null,
      "state": "cascaded", "round": 1, "refinedCritique": "…", "declineReasoning": null,
      "activatedAt": "…", "actedAt": "…", "timestamp": "2026-04-08",   // timestamp = actedAt date, fixture compat
      "responseDeadline": null, "overdue": false } ],
  "resolution": { "resolvedAt": "…", "resolvedByLabel": "…", "note": "…",
                  "acceptedDeclineActionId": "…|null" } /* null while in-flight */,
  "siblingDeclineAdvisories": [   // ADR D2 — read-only, derived from SIBLING_DECLINE notifications
    { "decliningCascadeId": "…", "depth": 2, "declinedAt": "…", "reasoningExcerpt": "…" } ] }

// CascadeAction
{ "id": "…", "chainSeq": 3, "depth": 2, "round": 1, "action": "decline",
  "actorLabel": "Curasync HIM Director", "actorMemberId": null,
  "refinedCritique": null, "declineReasoning": "…", "resolutionNote": null,
  "nextOwnerLabel": null, "actedAt": "2026-04-22T00:00:00Z" }

// AttentionItem — whose action is demanded right now
{ "cascadeId": "…", "kind": "level-looking | respond-to-decline | decline-pending-originator | overdue",
  "depth": 2, "ownerLabel": "…", "since": "…", "tone": "info | warn | destructive",
  "headline": "Decline pending — Curasync HIM Director declined at L2" }
// tone mapping: decline-pending-originator + respond-to-decline → destructive (leader-attention, queue #21);
// overdue → warn; level-looking → info. Rendered beside in-intervention agents (CASCADE-PROPAGATION).

// CascadeStats — feeds S6 engine-computed dashboard statistics
{ "domainId": "…", "total": 3, "byState": {"in-flight": 3, "resolved-looks-good": 0, "resolved-declined": 0},
  "bySignal": {"decline-pending": 1, "escalating": 2, "absorbed": 0},
  "overdueCount": 0, "actionsLast7Days": 4 }
```

### 6.2 Write — level/lifecycle commands (operator-grade; every body REQUIRES `idempotencyKey`; `expectedChainSeq` optional)

| Endpoint | Body (request record, validation annotations here) | Success | Errors |
|---|---|---|---|
| `POST /cascades/{id}/levels/{depth}/cascade` | `{idempotencyKey!, expectedChainSeq?, refinedCritique!, nextOwnerMemberId?, nextOwnerLabel!}` | 200 `CascadeDetail` | 403/404/409 |
| `POST /cascades/{id}/levels/{depth}/absorb` | `{idempotencyKey!, expectedChainSeq?, refinedCritique!}` | 200 `CascadeDetail` | 403/404/409 |
| `POST /cascades/{id}/levels/{depth}/decline` | `{idempotencyKey!, expectedChainSeq?, declineReasoning!}` | **200 ALWAYS for a legitimate decline** | 403/404/409 only for stale/illegal/forbidden |
| `POST /cascades/{id}/levels/{depth}/recascade` | `{idempotencyKey!, expectedChainSeq?, refinedCritique!, nextOwnerMemberId?, nextOwnerLabel!}` | 200 `CascadeDetail` | 403/404/409 |
| `POST /cascades/{id}/resolve` | `{idempotencyKey!, expectedChainSeq?, resolution!: "looks-good"\|"declined", resolutionNote!, acceptedDeclineActionId (required iff declined)}` | 200 `CascadeDetail` | 403/404/409 |
| `POST /cascades/notifications/{id}/read` | — | 200 | 404 |

Error envelope codes (all carried in `errors[].code`; `GlobalExceptionHandler` gains two additive mappings — `CascadeConflictException→409` with current `CascadeDetail` in `data`, `CascadeForbiddenException→403`):
`CASCADE_STALE_STATE`, `CASCADE_ILLEGAL_TRANSITION`, `CASCADE_TERMINAL` (409); `CASCADE_NOT_LEVEL_OWNER`, `CASCADE_NOT_ORIGINATOR` (403); bean-validation 400s per field. **A pinned contract test asserts decline → 200 through `ResponseEnvelopeAdvice` and that no handler maps decline flows into error envelopes** (binding: decline ≠ error).

### 6.3 Write — drafts (authoring-shape-neutral; leader UX gated on queue #24)

| Endpoint | Body / behavior |
|---|---|
| `POST /cascade-drafts` | `{domainId!, authoringMode!, originalCritique?, acceptanceCriteria?, summary?, affectedDomainId?, originFindingId?, cascadeGroupId?, responseSlaDays?}` → 201 `CascadeDraftDto`. FREE_FORM + IMPERATIVE fully usable now. |
| `PATCH /cascade-drafts/{id}` | partial update of the same fields; only while OPEN (409 otherwise) |
| `GET /cascade-drafts/{id}` · `GET /cascade-drafts?domainId&status` | read |
| `POST /cascade-drafts/{id}/suggest` | AI-suggest via orchestrator (§7). **404 when `substrate.orchestrator.enabled=false`** (hidden affordance per contract doc). 202 + `suggestionState: "pending"` when accepted. |
| `POST /cascade-drafts/{id}/discard` | OPEN → DISCARDED, 200 |
| `POST /cascade-drafts/{id}/issue` | `{level1OwnerLabel!, level1OwnerMemberId?, originatorRefinedCritique?, idempotencyKey!}` → validates critique+criteria present → creates the cascade (§5.2 A1) → 201 `CascadeDetail`. Re-issue of an ISSUED draft → 200 with the existing cascade (idempotent). `produced_by` = AI_ASSISTIVE iff the issued critique/criteria text derives from a landed suggestion, else HUMAN. |

DTO note: draft DTO exposes `suggestionState` (`none|pending|ready|error`) — this is AI-assist plumbing status, NOT governance state; `error` here means the orchestrator dispatch failed, never anything about declines.

---

## 7. Orchestrator MCP seam (consumed; the engine's ONLY orchestrator touchpoint)

`OrchestratorSuggestClient` (`@ConditionalOnProperty(name="substrate.orchestrator.enabled", havingValue="true")`), used exclusively by `CascadeDraftService.suggest()`:

1. `check_budget_for_task(...)` — preflight; on budget refusal set `suggestion_state=ERROR` with provenance note (no governance impact).
2. `submit_task(agent_role="engineer", description=<context pack>, task_type="cascade-critique-draft")` — context pack: authoring intent, draft fields, linked Finding text (if `origin_finding_id`), canonical org context references (read-only GET URLs, not payload dumps). Store returned task id in `orchestrator_task_id`, `suggestion_state=PENDING`.
3. Poll `get_dispatch_status(task_id)` (async via `@Scheduled` poll or on-read refresh — implementer's choice; no blocking HTTP).
4. On completion: `get_dispatch_result(task_id)` → `suggested_critique` / `suggested_acceptance_criteria`; `get_dispatch_events(task_id)` → digest into `suggestion_provenance` JSON (engagement audit trail of the AI assist; ProducedBy=AI_GENERATIVE *inside provenance only*). `suggestion_state=READY`.

Substrate never calls `dispatch_implementer`/`dispatch_tester`/PM-loop tools. Dispatched agents read substrate context via the §6.1 GET endpoints; agent output enters the system ONLY as draft suggestion text that a human may promote (P6: ungrounded AI output never auto-issues).

**MCP tools exposed BY substrate: none in Phase 2.** When the Phase 3 contract engine lands the substrate-side MCP server, register read-only aliases over §6.1: `cascade_list(domain_id, state?)` → GET /cascades; `cascade_get(cascade_id)` → GET /cascades/{id}; `cascade_timeline(cascade_id)` → GET /cascades/{id}/actions. No write tools, ever, for agents (**EXTENSION POINT** §13.7 covers agent-as-level-owner).

---

## 8. Notification hooks (transactional outbox — rows written in the SAME tx as the action)

| Trigger | Rows written (type → recipient) |
|---|---|
| ISSUE | `CASCADE_ISSUED` → originator (receipt/record); `LEVEL_ACTIVATED` → L1 owner |
| CASCADE at k | `LEVEL_ACTIVATED` → k+1 owner; `LEVEL_CASCADED` → originator (visibility feed) |
| ABSORB (L2 or L6) | `LEVEL_ABSORBED` → originator AND → k−1 owner (if k ≥ 2) |
| DECLINE (L3 first-touch or L4 endorse) | `LEVEL_DECLINED` → k−1 owner (the response owner; skip when k−1 = 0); `DECLINE_PENDING_ORIGINATOR` → originator; `SIBLING_DECLINE` → each in-flight same-group sibling's originator, `dedupe_key = "sibling:"+siblingCascadeId+":"+decliningCascadeId` (one advisory per declining-cascade/sibling pair regardless of how many levels decline) |
| RECASCADE | `LEVEL_ACTIVATED` → k+1 owner (round n) |
| RESOLVE_LOOKS_GOOD | `CASCADE_RESOLVED_LOOKS_GOOD` → every distinct level owner depth ≥ 1 |
| RESOLVE_DECLINED | `CASCADE_RESOLVED_DECLINED` → every distinct level owner depth ≥ 1 |
| Sweeper (§9) | `LEVEL_OVERDUE` → frontier owner + k−1 owner + originator; `DECLINE_OVERDUE` → originator + response owner |

Payload JSON carries `{cascadeId, depth, round, actionId?, headline, excerpt}`. In-app feed = `GET /cascades/notifications` + mark-read. Phase 5 channel adapters (email/webhook behind `substrate.cascade.notification-channels`) poll rows where `dispatched_at IS NULL` — at-least-once, decoupled transports. Insert helper: check `existsByDedupeKey` then insert; UNIQUE backstop; duplicate-key violation swallowed as no-op (sweeper races).

---

## 9. Escalation sweeper (`CascadeEscalationSweeper`)

`@Scheduled(cron = "${substrate.cascade.sweep-cron:0 0 * * * *}")` (hourly). NEVER transitions state (ADR D5).

- SLA resolution at issue time: request `responseSlaDays` absent → `substrate.cascade.default-response-sla-days` (default 7); explicit `0` → disabled (`response_deadline` NULL). Stored per cascade; level deadline = `activated_at + sla days`, recomputed on (re)activation.
- LOOKING overdue: for each in-flight cascade whose frontier is LOOKING past deadline — windows at `deadline + n * overdue-renotify-days` (n = 0..`overdue-renotify-limit`); for each elapsed window not yet notified, insert `LEVEL_OVERDUE` rows with `dedupe_key = "overdue:"+cascadeId+":"+depth+":"+round+":"+n` and set `overdue=true` on the level + cascade rows.
- Decline-pending dwell: frontier DECLINED with the latest DECLINE action older than SLA → `DECLINE_OVERDUE`, `dedupe_key = "decline-overdue:"+cascadeId+":"+declineActionId+":"+n`, same windowing.
- Idempotent under restarts and double-fires by construction (deterministic keys + UNIQUE backstop): **at most one notification per level per deadline window** (graft).
- `overdue` flags are cleared by `CascadeLifecycleService` on any transition of the affected level/cascade (not by the sweeper).

Config (`CascadeProperties`, prefix `substrate.cascade`): `default-response-sla-days=7`, `overdue-renotify-days=3`, `overdue-renotify-limit=5`, `sweep-cron`, `attest-to-decision-log=false` (reserved, §13.10), plus the global `substrate.orchestrator.enabled` and `substrate.demo.seed`.

---

## 10. Projection rebuilder (`CascadeProjectionRebuilder`)

`rebuild(cascadeId)` / `rebuildAll(domainId)`: under the per-cascade lock, replay `cascade_level_actions` in chain_seq order through the same pure transition functions the lifecycle service uses (extract them into a package-private `CascadeStateMachine` class so live path and replay path share one implementation), re-deriving: all `cascade_levels` rows (state, round, owner, refined/decline projections, activated/acted timestamps from action `acted_at`) and `cascades` derived fields (`current_state, chain_seq, frontier_*, attention_signal, resolved_*, resolution_note, accepted_decline_action_id`). Immutable issuance fields are verified (assert-equal against the ISSUE action payload), never rewritten. `overdue` flags and `response_deadline` are recomputed from SLA config + clock. Notifications are NOT replayed (the outbox is history, not a projection).

Purpose: restore-after-restart safety (plan §5 decision-log analog), drift detection (CI test compares live projections to a fresh rebuild), and the mechanism by which later-ratified `looking`/`absorbed` semantics apply retroactively without data migration (ADR D1/D12). Exposed as an actuator-style admin operation or Boot CLI runner — implementer's choice, but it must be user-runnable (documented command), not just a bean.

---

## 11. Read-model wiring to the Beacon UI (port-map §5 S5 seam)

1. Swap point: the `BEACON_PORTFOLIO` factory (ported to `frontend/src/fixtures/beacon/index.ts` by chunk C9). Replace fixture cascade imports with React Query hooks (`useCascades(domainId)`, `useCascade(id)`) + a thin adapter `CascadeDetail → BeaconCascade` / `CascadeSummary → grid row`. The DTO `levels[]` shape is 1:1 with the `CascadeLevel` view type (`level, ownerLabel, state, refinedCritique?, declineReasoning?, timestamp`) — kebab-case states match; `timestamp` is the acted/activated ISO date.
2. **LOOKING rows:** the engine returns frontier LOOKING rows the fixtures never contained (fixtures only show acted levels). The view type's state union already includes `'looking'`; the adapter passes them through; `CascadeDetailView` needs a minimal "awaiting response since {date}" rendering beat for them (small UI addition; semantics stay one-sentence, §13.1).
3. `affectedSubsidiarySlug` → `affectedDomainId` (+ `affectedDomainName` for display): a Beacon subsidiary is a `Domain` row. The dashboard grid signal chip maps `attentionSignal`: `decline-pending` → destructive (leader-attention, beside in-intervention agents), `escalating` → info, `absorbed` → success, `overdue` flag → warn overlay.
4. The "Cascades you've issued" grid sorts: decline-pending first, then overdue, then escalating by lastActivityAt desc — operator attention order.
5. **409 handling (new pattern for this UI):** on `CASCADE_STALE_STATE` / `CASCADE_ILLEGAL_TRANSITION`, the envelope `data` carries current `CascadeDetail`; the client must refresh the view and toast "cascade moved on — review current state", never render a generic failure. Required before any write affordance ships.
6. Beacon fixture data stays demo-only; excision at route+directory level at live-swap (port-map trap #9). `DEMO_CURRENT_DATE` → injectable clock already handled by C9/S15.

### 11.4 DemoPack seed (`CascadeDemoSeeder`, gated `substrate.demo.seed=true`, isDemo domain only)

Seeds by driving `CascadeDraftService` + `CascadeLifecycleService` with a fixed clock per action — **never row inserts** — replaying the three fixtures from `meridian-homepoc/frontend/src/demo/beacon/cascades.ts`. All seeds use `responseSlaDays=0` (SLA disabled — keeps the demo deterministic; overdue paths are covered by tests, not demo data). Member ids: resolve owner labels against seeded `organization_members` by name within the demo domain when present, else label-only.

| Fixture | Action stream (dates 00:00Z) | End state |
|---|---|---|
| `c-vendor-cost` | issue 04-08 (L1: Curasync COO) → L1 cascade 04-11 (→ Curasync HIM Director) → L2 **decline** 04-22 | in-flight, frontier L2 DECLINED, signal `decline-pending` — **the terminal-decline test vector (NOT c-tribal-succession; walkthrough drift trap, port-map §6)** |
| `c-tribal-succession` | issue 04-12 (L1: Sentari COO) → L1 cascade 04-17 (→ Sentari Lab Operations Director) → L2 cascade 04-24 (→ "Sentari Senior MT" †) | in-flight, frontier L3 LOOKING, signal `escalating` |
| `c-tellix-velocity` | issue 04-09 (L1: Tellix COO) → L1 cascade 04-19 (→ "Tellix Engineering Director" †) | in-flight, frontier L2 LOOKING, signal `escalating` |

† Seed-only owner labels derived from fixture narrative text (the fixtures omit LOOKING rows entirely). Flagged fiction: demo data never ships to a customer build. Original critiques, acceptance criteria, refined critiques, decline reasoning, and L0 refined one-liners are copied verbatim from `cascades.ts`.

---

## 12. Build order + test plan

Build order (binding read-model-first, chunk D1): **(1)** V4/V4_1 migration + entities + enums + repos → **(2)** `CascadeStateMachine` + projections + `CascadeQueryService` + read controllers → **(3)** DemoPack action-stream seed (needs the lifecycle service core, so lifecycle lands here in engine-internal form) → **(4)** Beacon swap at `BEACON_PORTFOLIO` → **(5)** command REST API + conflict handler mappings → **(6)** sweeper → **(7)** drafts + suggest behind the seam. `gradlew build` green at every boundary.

User-runnable acceptance (per the standing acceptance rule — no report-only completions):
```
gradlew :app:bootRun --args='--spring.profiles.active=dev --substrate.demo.seed=true'
curl -s localhost:8080/api/v1/cascades?domainId=<demo-domain-id>
# → 3 cascades; c-vendor-cost attentionSignal=decline-pending; other two escalating
curl -s localhost:8080/api/v1/cascades/<c-vendor-cost-id>/actions   # → 4 actions, chain_seq 1..4
```

Test plan (H2 fast lane for units; **Testcontainers-Postgres is the acceptance gate — H2-green never is; this dev machine cannot run Docker, CI runs the gate**):

1. **State-machine table tests** (unit, pure `CascadeStateMachine`): every legal transition A1–A3, L1–L6; every illegal combination (act on non-frontier, act-twice, verbs on ABSORBED/DECLINED levels, resolve-declined without pending decline, endorse-decline when frontier not DECLINED, post-terminal anything → CASCADE_TERMINAL); payload conditionals I4; frontier rules incl. L6 absorb-over-decline leaving k+1 historically DECLINED but not pending; pending-decline-run definition; signal derivation table (§2) incl. I6 assertion.
2. **Fixture vectors** (integration, seeded via the DemoPack path): pin the §11.4 end states — level rows, states, rounds, frontier, signal, chain_seq, verbatim critique/reasoning text. Explicitly pin: `c-vendor-cost` is the decline-pending vector and `c-tribal-succession` is all-cascaded/escalating (guards against the 05-walkthrough narrative drift recurring in test authoring).
3. **Continuation vectors** (extend the seeds in-test): c-vendor-cost → originator resolve(declined, acceptedDeclineActionId=L2 decline) → `resolved-declined`, resolution block populated; c-vendor-cost alt-path → L1 endorse-decline then originator accepts L1's decline; c-vendor-cost alt-path → L1 recascade → L2 round 2 LOOKING → L2 cascades round 2 (deepening after recascade); c-vendor-cost alt-path → L1 absorb-over-decline → signal `absorbed`; c-tellix → resolve(looks-good) with note. Terminal absorption: any action after resolution → 409.
4. **Idempotency + fencing**: replay same idempotencyKey → original 200 body, no new action row; stale expectedChainSeq → 409 `CASCADE_STALE_STATE` with current detail in `data`; draft re-issue → same cascade id.
5. **Concurrency** (Testcontainers): two threads, same cascade, simultaneous L2 decline + L2 cascade → exactly one action row wins, loser 409s; lock serialization verified; same-key double-submit race → one row + replayed response.
6. **Append-only** (Testcontainers): direct JDBC UPDATE/DELETE on `cascade_level_actions` raises from the V4_1 trigger; repository surface exposes no mutators (compile-time, but assert via architecture test if ArchUnit is present).
7. **Rebuilder determinism**: seed → snapshot projections → corrupt/wipe `cascade_levels` + derived `cascades` fields → `rebuild` → assert field-equal with snapshot; drift-detection comparison green after every continuation vector.
8. **Sweeper idempotency** (fixed clock): SLA 7d, advance clock past deadline → sweep twice → exactly one `LEVEL_OVERDUE` per recipient (dedupe_key); advance one renotify window → second notification; advance past limit → no further rows; level acts → overdue flags cleared; SLA=0 cascade → never swept.
9. **Decline-never-error contract test** (pinned, MockMvc through `ResponseEnvelopeAdvice` + `GlobalExceptionHandler`): decline → 200 enveloped, `errors=[]`; resolve(declined) → 200; no cascade enum value serializes to anything containing "error"/"fail".
10. **Notification outbox**: rows present in the same tx (assert via TransactionTemplate visibility test); fan-out table of §8 per action; sibling advisory once per declining-cascade/sibling pair even after a second endorse-decline; crash-safety argument: no AFTER_COMMIT listeners exist (architecture assertion: no `@TransactionalEventListener` in `engine.cascade`).
11. **Degraded mode**: `substrate.orchestrator.enabled=false` → `/suggest` 404, all other endpoints + sweeper + seed green; `=true` with mock MCP client → suggestion lands in draft `suggested_*` + provenance digest; issued cascade `produced_by=AI_ASSISTIVE`.
12. **Postgres round-trip** (Testcontainers): all 5 entities persist/read under `ddl-auto=validate` against V4+V4_1; TIMESTAMPTZ + UNIQUE-with-NULL-dedupe_key semantics verified (port-map traps #1, #4).

---

## 13. UNKNOWN-semantics extension points (flag-not-invent — encoded ONLY at the fidelity stated; each is a named `// EXTENSION POINT(cascade-N)` comment in code)

1. **LOOKING dwell/reassignment/expiry** — encoded: initial state on activation + overdue notification only. NOT encoded: reassignment verb, expiry, delegation. (port-map §6 ⚠ — never fixture-exercised.)
2. **ABSORBED completion semantics** — encoded: terminates downward propagation, refinedCritique required, cascade stays in-flight pending originator resolution. NOT encoded: progress tracking, completion evidence, originator sign-off requirements on absorbed work.
3. **Drill-to-L[N]** (queue #23) — no drill verb, no drill affordance; the full timeline read is the only navigation. Restore decision belongs to the #24 session.
4. **Heading/summary shape** (queue #22) — `summary` column reserved + nullable, unrendered; authoring shape determines it.
5. **Leader authoring UX** (queue #24) — write API is operator-grade and shape-neutral; entry points, preview, routing suggestions, AI-edit-approve loops all undesigned. Do not build frontend authoring ahead of the Mike session.
6. **Cross-cascade structural decline settlement** — advisory `SIBLING_DECLINE` notifications only (ADR D2); no state coupling; an operator-cited `decline_reference` link is the designed next step if demanded.
7. **Agent as level owner** — owner refs are member-or-label only; `agent_registration_id` ownership rides the D3 agent registry + the same MCP seam, later.
8. **Reopen/rework after terminal** — terminal states absorbing; reissue lineage via `predecessor_cascade_id` is the only loop.
9. **Timeout auto-advance/auto-reassign** — never in v1; would land as explicit per-cascade policy, default off (ADR D5).
10. **Decision-log attestation** — `CascadeAttestationHook` seam + reserved config flag; digest-anchoring into `engine.decision` NOT implemented in Phase 2 (ADR D15).
11. **Routing validation against the org chart** — `OrganizationMember.reportsTo` is a name string, not an FK; mis-routing is silently legitimate in v1 (named risk, not an invariant).
12. **Monitor/Automate phase-entry interplay** — cascade activity as lifecycle-phase entry criteria is UNKNOWN ([LIFECYCLE-MACHINE]); no coupling encoded.
