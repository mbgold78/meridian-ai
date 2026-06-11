# Engine Spec — Diagnostic Engine (`engine.diagnostic`) + Computed Statistics (D4 + D5)

**Status:** implementation-ready. Governing ADR: `substrate/docs/adr/ADR-004-diagnostic-engine.md` (Accepted — autonomous, revisable; Mike review pending).
**Binding inputs:** `substrate/docs/port-map.md` §6 `[EVIDENCE-GROUNDING]` + §5 (S2/S3/S6/S12) + §2.3 (mode services, commit-step gap, dead-code mirage list) + §4 (Postgres strategy), `substrate/docs/adr/ADR-001`/`ADR-002` + `engine-spec/cascade.md` §13 / `contracts.md` §11.2+§15, `substrate/docs/orchestrator-substrate-contract.md`, homepoc `docs/redesign/06-plan-designer.md` (TransformationPlan/Initiative schema) + `07-ai-assistance-patterns.md` (P11/P12), Glacier corpus `substrate/ingestion/samples/`.
**Audience:** implementation agents. This spec is the design; do not re-derive decisions. Anything marked **EXTENSION POINT** is deliberately NOT designed — flag, don't invent (§15).

Top-level non-negotiables:
1. **The pinned mode services stay byte-identical in contract.** `ValidativeModeService`/`GenerativeModeService` allowlist + differential-grounding semantics are phase2a-pinned; this engine consumes their DTOs downstream. The only permitted touches are the `CandidateFilter` extraction (§7.2, no behavior change), two additive nullable `FindingCandidate` components with a compatibility constructor (§5.1), the `find_evidence` tool registration, and prompt lines naming the new tool/fields. **Any semantic edit to a pinned phase2a test is a scope alarm — stop.**
2. **One grounding chokepoint.** All candidates — in-process or orchestrator-dispatched — transit the same `CandidateFilter` before any persistence. Agents are advisors, never mutators.
3. **Reject/decline/cancel are never error-colored.** REJECT and CANCEL return HTTP 200; `ZERO_YIELD` is a loud product outcome, not a 5xx; `SKIPPED_BUDGET` renders neutral.
4. **No silent zero-yield, anywhere**: zero parseable candidates is a hard FAIL; all-filtered is explained with per-batch counts; uninitialized phase states are a distinct count, never 0 stalls; absent engines render `…Available:false`, never 0.
5. **Read-model-first build order** (§14). The provenance walk ships before any generation exists.
6. **Degraded mode:** with `substrate.orchestrator.enabled=false`, everything works via the in-process executor; exactly one bean (`OrchestratorDiagnosticExecutor`) disappears. No other conditional paths.
7. **Testcontainers-Postgres is the acceptance gate — H2-green never is** (this dev machine cannot run Docker; CI runs the gate). The `dev-shared` profile (§11.1) is local acceptance convenience only.

---

## 1. Module / package layout

```
common/src/main/java/com/meridian/substrate/common/domain/
  AiCurationEvent.java  DiagnosticRunBatch.java
  TransformationPlan.java  TransformationInitiative.java          // D5 thin slice (V8)
  (Finding.java gains: assertionId, curationStatus, curatedAt, curatedByLabel — §3.1)
common/src/main/java/com/meridian/substrate/common/repo/
  AiCurationEventRepository.java  DiagnosticRunBatchRepository.java
  TransformationPlanRepository.java  TransformationInitiativeRepository.java
engine/src/main/java/com/meridian/substrate/engine/diagnostic/
  DiagnosticRunService.java         // run lifecycle: start/resume/cancel/finalize (+supersession in finalize tx)
  DiagnosticRunPlanner.java         // deterministic batch plan; dryRun support
  DiagnosticBatchExecutor.java      // claim batch → execute (outside tx) → commit+ledger (one tx)
  FindingCommitService.java         // FindingCandidate → Finding (the S2 sever)
  DraftCommitService.java           // AI_GENERATIVE Assertion → Capability(DRAFT)
  CurationService.java              // accept/reject for findings AND drafts; ai_curation_events
  ProvenanceWalkService.java        // /findings/{id}/provenance + /diagnostics/verify read model
  CandidateFilter.java              // extracted parse→allowlist→grounding→Assertion core (§7.2)
  DiagnosticExecutor.java           // SPI: executeBatch(run, batch) → List<FindingCandidate>
  InProcessDiagnosticExecutor.java  // default bean — degraded-mode base case
  OrchestratorDiagnosticExecutor.java // @ConditionalOnProperty(substrate.orchestrator.enabled)
  GroundingVerifier.java            // SPI; ExistenceGroundingVerifier default (§6.2)
  DiagnosticAttestationHook.java    // interface; NoopDiagnosticAttestationHook default bean
  DiagnosticProperties.java         // @ConfigurationProperties("substrate.diagnostic")
  read/   (response DTO records)    exception/ DiagnosticConflictException.java
engine/src/main/java/com/meridian/substrate/engine/ai/tools/impl/
  FindEvidenceTool.java             // §7.1 — registered Tool bean over RelationalEvidenceRetriever
app/src/main/java/com/meridian/substrate/app/api/v1/controller/
  DiagnosticController.java  FindingCurationController.java
  GenerativeCommitController.java  PortfolioController.java  InitiativeController.java
app/src/main/java/com/meridian/substrate/app/core/service/
  PortfolioReadService.java         // D5 — cross-engine composition (§12)
app/src/main/java/com/meridian/substrate/app/cli/
  DiagnosticRunner.java             // ApplicationRunner, substrate.diagnostic.run=true (§10.3)
app/src/main/resources/db/migration/common/V7__diagnostic_commit.sql
app/src/main/resources/db/migration/postgresql/V7_1__diagnostic_append_only_and_active_run.sql
app/src/main/resources/db/migration/common/V8__transformation_initiatives.sql
app/src/main/resources/application-dev-shared.yml                  // §11.1
```

Entity conventions: extend `BaseEntity`; FK = writable UUID column + `@JsonIgnore @ManyToOne(LAZY, insertable=false, updatable=false)` navigation twin; **no Lombok on entities**; JSON via `JsonNodeConverter` → TEXT (§4.5 jsonb deferral holds); soft refs (assertion_id, subject_id, created_entity_id, synthesis_run_id, target_* ids) have NO DB FKs — app-enforced, comment in code. Every service takes the Spring `java.time.Clock` bean (S15).

Migration numbering: V5 = contracts (landed), V6 = agent registry (reserved, D3), **V7 = diagnostic**, **V8 = transformation-initiative thin slice** (declared S4 deviation, ADR D13).

---

## 2. Configuration keys

```yaml
substrate:
  diagnostic:
    scopes: systems,processes,canonical-entities   # default scope enumeration (§4.2)
    batch-size: 20                                 # targets per batch
    max-attempts: 3                                # per-batch retry ceiling on LlmRateLimitException
    stale-batch-minutes: 30                        # RUNNING older than this is resumable as FAILED
    executor: auto                                 # auto | in-process | orchestrator
    dispatch:
      fallback: in-process                         # in-process | skip  (budget refusal / dispatch failure)
    sync: false                                    # true = run synchronously (tests)
    attest-to-decision-log: false                  # DiagnosticAttestationHook gate (ADR D12)
    auto-after-ingest: false                       # NAMED EXTENSION — not built in v1 (§15.5)
    run: false                                     # CLI runner gate (§10.3)
    domain-name:                                   # CLI: resolve domain by name
    required-min-findings: 0                       # CLI/REST default
  stats:
    stall-threshold-days: 90                       # §12.1 — config knob, not invariant
    pattern-window-days: 7                         # delegated to contracts §11.2 (window stays theirs)
```

`substrate.llm.*` (provider/model/cost rates) and `substrate.engine.tools.persona-access.*` are existing keys; `find_evidence` is added to the persona-access defaults for the analyst levels that hold the other read tools.

---

## 3. Persistence

### 3.1 `V7__diagnostic_commit.sql` (common — ANSI-portable; H2 PG-mode + Postgres)

```sql
-- findings: commit-step + curation projection columns (additive; entities ARE the wire format)
ALTER TABLE findings ADD COLUMN assertion_id UUID;             -- soft ref → assertions (first hop of the audit walk)
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
    created_entity_id UUID,                   -- DRAFT ACCEPT → materialized capability id
    synthesis_run_id UUID,                    -- SUPERSEDE → superseding run
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
    synthesis_run_id UUID NOT NULL,           -- soft ref → synthesis_runs
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
```

No new run table — `synthesis_runs` (existing, with token/cost columns) IS the run record (severs S12 for this path). No draft table — the `AI_GENERATIVE` Assertion row is the durable draft store (`assertion.object` already holds `proposedFields`); rejection needs no entity row, only the curation event.

### 3.2 `V7_1__diagnostic_append_only_and_active_run.sql` (postgresql vendor dir)

```sql
-- append-only enforcement on the curation audit (V4_1 precedent; H2 dev = service-enforced only)
CREATE OR REPLACE FUNCTION raise_ai_curation_events_immutable() RETURNS trigger AS $$
BEGIN RAISE EXCEPTION 'ai_curation_events is append-only'; END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER trg_ai_curation_events_append_only
  BEFORE UPDATE OR DELETE ON ai_curation_events
  FOR EACH ROW EXECUTE FUNCTION raise_ai_curation_events_immutable();

-- one active diagnostic run per domain — backstop for the service lock (ADR D11)
CREATE UNIQUE INDEX uq_active_diagnostic_run_per_domain
  ON synthesis_runs(domain_id)
  WHERE run_type = 'diagnostic' AND status IN ('PLANNED','RUNNING');
```

Repositories expose no update/delete methods for `AiCurationEvent`; ArchUnit pins this (§14 test 15).

### 3.3 `V8__transformation_initiatives.sql` (common) — D5 thin slice (declared S4 deviation)

doc-06 fields ported faithfully; planning-engine semantics (wave sequencing rules, plan state-machine gates, rationalization lens) are **NOT implemented** — §15.9.

```sql
CREATE TABLE transformation_plans (
    id UUID NOT NULL, version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE, updated_at TIMESTAMP WITH TIME ZONE, created_by VARCHAR(255),
    domain_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(30) NOT NULL,              -- DRAFT|UNDER_REVIEW|APPROVED|EXECUTING|COMPLETE|ARCHIVED (doc-06; transitions unenforced v1)
    target_horizon_months INT,
    reduction_mode VARCHAR(20),               -- AGGRESSIVE|BALANCED|CONSERVATIVE (lens param; lens not built)
    approved_by VARCHAR(255),
    approved_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);
CREATE INDEX idx_tplans_domain ON transformation_plans(domain_id);

CREATE TABLE transformation_initiatives (
    id UUID NOT NULL, version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE, updated_at TIMESTAMP WITH TIME ZONE, created_by VARCHAR(255),
    plan_id UUID NOT NULL,                    -- soft ref → transformation_plans
    name VARCHAR(255) NOT NULL,
    description TEXT,
    initiative_type VARCHAR(30) NOT NULL,     -- AGENT_DEPLOY|SYSTEM_RETIRE|PROCESS_REDESIGN|ORG_CHANGE|REGULATORY|CAPABILITY_ADD|CAPABILITY_RETIRE
    status VARCHAR(20) NOT NULL,              -- PLANNED|IN_PROGRESS|COMPLETE|DEFERRED|CANCELLED
    target_capability_id UUID,
    target_process_id UUID,
    target_system_id UUID,
    wave INT,
    owner_name VARCHAR(255),
    estimated_months INT,
    complexity VARCHAR(10),                   -- LOW|MEDIUM|HIGH
    annual_savings_usd BIGINT,
    prerequisites TEXT,                       -- JSON UUID array (dependency graph; unvalidated v1)
    evidence_ids TEXT,                        -- JSON UUID array
    progress_pct INT NOT NULL DEFAULT 0,      -- operator-maintained; COMPLETE ⇒ rendered 100 (only computed rule)
    progress_note TEXT,
    progress_updated_at TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (id)
);
CREATE INDEX idx_tinitiatives_plan ON transformation_initiatives(plan_id);
```

---

## 4. SynthesisRun lifecycle (the run model)

### 4.1 Status vocabulary (free-text column, documented — never enum-tightened)

```
PLANNED → RUNNING → COMPLETED | COMPLETED_WITH_ERRORS | ZERO_YIELD | FAILED | CANCELLED
```

| Status | Meaning | Color semantics |
|---|---|---|
| `PLANNED` | run row + batch plan persisted, execution not started | neutral |
| `RUNNING` | ≥1 batch executing | info |
| `COMPLETED` | every batch COMPLETED (or SKIPPED_BUDGET); yield requirement met (or `requiredMinFindings=0` with mandatory explanation when committed=0) | success |
| `COMPLETED_WITH_ERRORS` | mixed: ≥1 batch COMPLETED, ≥1 FAILED after `max-attempts` | warn — never error-colored as a whole |
| `ZERO_YIELD` | all batches ran; candidates were produced but committed < `requiredMinFindings` (incl. all-filtered with required>0) | **loud product failure** (exit 3) |
| `FAILED` | zero parseable candidates across ALL batches (`reason=NO_CANDIDATES` — always hard fail), or all batches FAILED | error |
| `CANCELLED` | operator cancel | neutral — cancel is never error-colored |

(The losing designs' `COMPLETED_EMPTY` is subsumed: committed=0 with `requiredMinFindings=0` is `COMPLETED` + a mandatory explanation + an explicit `yield=0` event line — a clean domain is legitimate; silence is not.)

### 4.2 Start

`DiagnosticRunService.startRun(domainId, scopes?, targetEntityIds?, batchSize?, requiredMinFindings?, executor?, idempotencyKey)`:
1. Enforce **one active run per domain**: domain-scoped service lock; an existing `PLANNED|RUNNING` diagnostic run → 409 `DIAGNOSTIC_RUN_ACTIVE` (PG partial-unique backstop §3.2; H2 service-enforced).
2. Persist `SynthesisRun(runType='diagnostic', status=PLANNED, startedAt=now, model=<configured>)`; `metadata` records: scopes, requiredMinFindings, batchSize, executor, trigger actor (PersonaContext label), promptTemplateVersion, and a **cost-rate snapshot** (`substrate.llm.cost.*` values at run time — rates drift; audits don't).
3. `DiagnosticRunPlanner` enumerates enabled scopes, orders entity ids deterministically (`createdAt,id`), chunks into batches of `batch-size`, appends one domain-wide batch (`scope='domain'`, no targets) for cross-entity observations; persists `diagnostic_run_batches` rows PENDING. `dryRun=true` returns the plan without creating anything but the response body.
4. Status → RUNNING; execution proceeds async on a bounded executor (`substrate.diagnostic.sync=true` runs inline for tests). Batches run **sequentially within a run** (rate-limit friendly).

### 4.3 Per-batch execution (`DiagnosticBatchExecutor`)

1. Claim: batch PENDING → RUNNING, `claimed_at=now`, `attempt+1` (own short tx).
2. Generate **outside any tx**: the selected `DiagnosticExecutor` produces candidates (§8). `LlmRateLimitException` → exponential backoff retry up to `max-attempts`, then batch FAILED (run continues).
3. Commit **in ONE tx**: `FindingCommitService` persists surviving findings (§5) + batch ledger update (status COMPLETED, counters, filter histogram) + run counter increments. A crash between 2 and 3 leaves RUNNING with stale `claimed_at`; resume treats RUNNING older than `stale-batch-minutes` as FAILED and re-executes — **duplicates are impossible because a failed batch committed nothing**. No cross-LLM-run semantic dedupe is attempted; the tx boundary is the dedupe.

### 4.4 Resume / cancel

- `POST /api/v1/diagnostics/runs/{id}/resume` — re-executes PENDING / FAILED / stale-RUNNING batches; resuming a terminal run is an idempotent no-op 200.
- `POST /api/v1/diagnostics/runs/{id}/cancel` — PENDING batches → CANCELLED; the in-flight batch finishes its current step; run → CANCELLED. 200, neutral.

### 4.5 Finalization (one tx)

When no PENDING/RUNNING batches remain: compute terminal status per §4.1 + the zero-yield trichotomy (§10.2); set `completedAt`, `findingsGenerated` (= committed count), `totalInputTokens`/`totalOutputTokens` summed from `OrchestratorResult`s, `totalCostEstimate` from the snapshotted rates; write metadata: per-reason filter histogram totals, refusalDetected, executor mix, dispatch-provenance digests (`get_dispatch_events`), `supersededFindingIds`. **Supersession executes here** (ADR D3): iff terminal status ∈ {COMPLETED, COMPLETED_WITH_ERRORS}, mark prior `(domainId, scope-set)` findings still PENDING_REVIEW and not created by this run as SUPERSEDED — one `ai_curation_events` row each (`action=SUPERSEDE`, `synthesis_run_id`=this run, actor_label='system:diagnostic'). ACCEPTED/REJECTED rows are never touched. ZERO_YIELD/FAILED/CANCELLED supersede nothing. Finally, `DiagnosticAttestationHook.onRunFinalized(run)` fires (no-op default).

---

## 5. The commit step (severs S2)

### 5.1 Candidate contract extension (additive only)

`FindingCandidate` gains two trailing nullable components — `title` and `findingType` (raw string as emitted) — plus a compatibility constructor with the original arity delegating nulls, so existing pinned tests compile unchanged. The validative prompt gains lines requesting `title` (short, ≤200 chars) and `findingType` (one of the canonical 12 values). Allowlist + grounding semantics are verbatim-unchanged.

### 5.2 `FindingCommitService.commit(run, batch, candidate)` — exact mapping

| Finding column | Source | Rule |
|---|---|---|
| `domain_id` | run | — |
| `ai_generated` | constant | `true` |
| `synthesis_run_id` | run.id | — |
| `assertion_id` | `candidate.assertionId()` | **NOT NULL for committed AI findings** (service-enforced); the Assertion(AI_VALIDATIVE) row was persisted by the filter at validation time |
| `curation_status` | constant | `PENDING_REVIEW` |
| `status` | constant | `OPEN` |
| `title` | candidate.title, else first sentence of description truncated 200 | findings.title is NOT NULL |
| `description` | candidate.description | required (filter guarantees non-blank) |
| `recommendation` | candidate.suggestedAction | — |
| `severity` | CRITICAL→CRITICAL, HIGH→HIGH, MEDIUM→MEDIUM, **INFO→LOW** | documented lossy mapping; pinned by test; REVISIT with Mike |
| `finding_type` | candidate.findingType validated against the 12-value `FindingType` enum | invalid/absent → **differential default**: STRUCTURAL→`COVERAGE_GAP`, CONTENT→`DATA_QUALITY`, plus `metadata.findingTypeDefaulted=true`. An invalid LLM-supplied type NEVER discards a grounded candidate. |
| `affected_system_ids` / `affected_process_ids` | from `affectedEntityType`+`affectedEntityId` after in-domain existence check | unresolvable → `metadata.unresolvedAffectedEntity`, finding still commits |
| `evidence` | **enriched snapshot** `[{evidenceId, label, sourceFile, documentRegistryId, confidence}]` joined from the grounding-validated Evidence rows | render-ready; snapshot drift detectable via `chainComplete` (§6.1). Empty array for evidence-less STRUCTURAL findings. |
| `metadata` | JSON | `{assertionId, candidateType: STRUCTURAL\|CONTENT, groundingMode: "EXISTENCE_ONLY", batchNo, executor, model, dispatchTaskId?, findingTypeDefaulted?, unresolvedAffectedEntity?}` |

**Grounding-chain requirement (P6, exact):** every committed CONTENT finding must satisfy, at commit time, the full walkable chain
`Finding.assertion_id → Assertion(producedBy=AI_VALIDATIVE) → Assertion.evidenceIds (≥1) → Evidence rows in-domain → Evidence.documentRegistryId → DocumentRegistry (sha256, processedByConverterRunId) → ingest_runs` — with `Evidence.sourceFile` tracing to a Reader-registered original source. The filter (§7.2) guarantees the Assertion+Evidence hops; the commit service re-asserts evidence resolution when building the snapshot (a row vanishing between filter and commit fails THAT candidate with `filter_histogram.ungrounded+1`, not the batch). STRUCTURAL findings legitimately terminate at the Assertion (self-justifying model-state observations per the pinned contract); they carry `evidence=[]` and dashboards render them distinctly, never error-colored. **No duplicate Evidence rows are ever minted by this engine.**

### 5.3 `DraftCommitService` — DraftEntity → Capability (the generative sibling)

`commitDraft(assertionId, overrides?, idempotencyKey, persona)`:
1. Load Assertion; require `producedBy=AI_GENERATIVE ∧ predicate='proposes_creation'`; require no prior ACCEPT/REJECT event for `(DRAFT, assertionId)` (the `uq_curation_idem` key + a lookup; already-accepted → idempotent 200 with the existing capability id; conflicting accepted-vs-reject → 409).
2. **Re-verify grounding at materialization time**: every `assertion.evidenceIds` entry must still resolve to an in-domain Evidence row; failure → 409 `DRAFT_GROUNDING_LOST` (the proposal is stale against current evidence; never silently materialized).
3. Materialize `Capability(status=DRAFT, aiGenerated=true, name/displayName/description from assertion.object proposedFields, parentId→parentCapabilityId rename per the GenerativeModeService javadoc contract, metadata={assertionId, synthesisRunId?})`. Parent resolution: proposedFields.parentId must resolve to an in-domain capability, else committed parentless with `metadata.unresolvedParent=true`.
4. Apply human `overrides` AFTER asserted fields; record the delta as `overridden_fields` JSON on the ACCEPT event — the human edit is itself provenance.
5. Append `ai_curation_events(DRAFT, assertionId, ACCEPT, created_entity_id=capability.id)` in the same tx. The verb is `@Governed(actionType="capability.commit_draft")`, HUMAN principal.

`rejectDraft(assertionId, rationale!, idempotencyKey, persona)` → curation event only (`action=REJECT`, rationale NOT NULL); no entity row exists or is created; 200. **There is no bulk-accept verb** (P6/doc-07; structural). Capability is the only materialization target in v1 (§15.6).

---

## 6. Grounding honesty + provenance read model (severs S3's silence, not S3 itself)

### 6.1 `ProvenanceWalkService` — ships FIRST (build step 2)

- `GET /api/v1/findings/{id}/provenance` → one response materializing: finding (incl. curation projection) → assertion (subject/predicate/object/confidence/producedBy/producedByContext) → evidence[] (label, confidence, sourceFile, extractor) → documentRegistry[] (sourceFile, sha256, ingestionStatus, processedByConverterRunId) → ingestRuns[] → readerRegistration (when a registry row traces to one) → curationEvents[] (the human decisions, in order). Plus `chainComplete: boolean` (false on ANY dangling hop — surfaced, never elided), `groundingMode` (from metadata; `EXISTENCE_ONLY` for all v1 AI findings), and `fixtureGrounded: boolean` (any hop with `extractor='seed'`).
- `GET /api/v1/diagnostics/verify?domainId` → sweep of every `aiGenerated=true` finding: `{total, chainComplete, brokenChains:[{findingId, missingHop}], fixtureGrounded:[...], groundingModes:{EXISTENCE_ONLY: n}}` — the diagnostic twin of `/decision-log/verify`. Works against DemoPack data before any generation exists (fixture chains visible and labeled).

### 6.2 S3 posture (binding on UI + copy)

`groundingMode='EXISTENCE_ONLY'` is persisted data and appears in every provenance DTO and the curation UI. Product copy: "evidence citations are existence-verified; semantic support is human-verified at acceptance" — NEVER "machine-verified grounding". The curation accept affordance MUST render the cited evidence inline (labels, source files, registry links via this walk); the ACCEPT event is the durable record a named human verified semantic support. `GroundingVerifier` SPI ships with `ExistenceGroundingVerifier` only; any future SEMANTIC verifier is **advisory-only by contract** — may lower confidence, never auto-pass (P7). Granularity limit stated: Evidence is document-level; passage-level via `EvidenceChunk` is §15.2.

---

## 7. The `find_evidence` tool + `CandidateFilter` extraction

### 7.1 `FindEvidenceTool` (closes the citation-vocabulary gap — ADR D6)

Verified gap: `get_evidence_for_finding` requires an existing `findingId`; against freshly-ingested data the model cannot learn valid Evidence UUIDs, so the differential-grounding filter (ValidativeModeService line 165) structurally zeroes ALL CONTENT findings. New read tool:

- name `find_evidence`; schema `{domainId (required, injected from context like sibling tools), entityId?, entityType?, limit? (default 25, max 100)}`.
- Backed by the existing `RelationalEvidenceRetriever` (`EvidenceQueryType.EVIDENCE` branch / `EvidenceRepository.findByEntityId|findByDomainId|findByEntityIdAndEntityType`); returns `[{evidenceId, entityId, entityType, label, sourceFile, confidence, extractor}]` — exactly the vocabulary a CONTENT candidate must cite.
- Registered as a Tool bean (auto-discovered by `ToolRegistry`); persona-access defaults extended; named in the VALIDATIVE and GENERATIVE prompt instructions ("use find_evidence to discover citable evidence ids; CONTENT findings citing ids you did not retrieve will be filtered").
- Run metadata records grounded-rate; a run whose CONTENT candidates were ALL filtered emits `DIAG_EVENT kind=all_content_filtered` (loud, non-fatal unless the trichotomy says otherwise).
- Pinned-surface discipline: `MockAnthropicClient` scripted tool-call cases must prove existing eval expectations are unperturbed by the registration + prompt extension (§13; any semantic pinned-test edit = stop).

### 7.2 `CandidateFilter` extraction

The parse→type/severity-allowlist→required-fields→differential-grounding→`AssertionService.record(AI_VALIDATIVE)` core of `ValidativeModeService.validateAndBuild` moves to `CandidateFilter` (engine.diagnostic), consumed by: (a) `ValidativeModeService` (delegation — public contract and behavior byte-identical; phase2a tests green unmodified), and (b) `OrchestratorDiagnosticExecutor` result processing. The filter emits a per-candidate outcome (`KEPT | BAD_TYPE | BAD_SEVERITY | MISSING_FIELD | UNGROUNDED`) feeding the batch `filter_histogram`. Refactor acceptance: zero pinned-test edits beyond the additive record components in §5.1.

---

## 8. Generation executors (`DiagnosticExecutor` SPI)

### 8.1 `InProcessDiagnosticExecutor` (default — degraded mode IS the base case)

Per batch: builds a `ValidateRequest(domainId, surface, scope, targetEntityIds)` and calls `ValidativeModeService.validate(...)` → `ToolCallingOrchestrator` → `LlmClient` (`SdkAnthropicClient`, or `MockAnthropicClient` under `substrate.llm.provider=mock` — fixture seam S8, the CI lane). Candidates are already filtered + asserted by the mode service; the executor returns them with the `OrchestratorResult` token/model/refusal data for ledger/run accounting. This deliberately upgrades the contract doc's degraded-mode line ("operator-authored findings instead of AI generation") to "direct-LlmClient generation still works"; the contract doc receives a versioned amendment at slice close (ADR follow-up #5).

### 8.2 `OrchestratorDiagnosticExecutor` (`substrate.orchestrator.enabled=true` AND `substrate.diagnostic.executor ∈ {auto-with-orchestrator, orchestrator}`)

Per batch: `check_budget_for_task` preflight → `submit_task(agent_role='diagnostic-analyst', task_type='diagnostic-generation', description=context pack referencing public read-API GET URLs — never payload dumps)` → non-blocking `get_dispatch_status` polling → `get_dispatch_result` candidate JSON → **re-validated through the same `CandidateFilter`** (agent output enters substrate only via substrate's own write path) → the apply is wrapped as `GovernedAction(actionType=apply_dispatch_result, principal=SERVICE 'substrate-svc', dispatchId)` — ADR-002 PEP #3. `get_dispatch_events` digest → run metadata + per-finding `metadata.dispatchTaskId`. Substrate consumes ONLY the five contract-doc tools; never PM-loop tools.

**Fallback semantics:** budget refusal or dispatch FAILED → per `substrate.diagnostic.dispatch.fallback`: `in-process` (default) re-executes the batch on the in-process executor, recorded as `executor='in-process-fallback'`; `skip` marks the batch `SKIPPED_BUDGET` (neutral, non-error — budget refusal is a governance non-event per the contract doc; SKIPPED_BUDGET batches don't block COMPLETED). Known dependency: `OrchestratorMcpClient` has no production transport until Phase-3 MCP SDK work — this executor is build-order step 8 and can slip without blocking the product path.

---

## 9. Curation (`CurationService`)

- `accept(FINDING, findingId, rationale?, idempotencyKey, persona)` — requires curationStatus ∈ {PENDING_REVIEW, REJECTED} (ACCEPT-after-REJECT is legal recourse); appends event; projects `curation_status=ACCEPTED, curated_at, curated_by_label` onto the finding row. `@Governed("finding.accept")`, HUMAN.
- `reject(FINDING, findingId, rationale!, idempotencyKey, persona)` — rationale service-enforced NOT NULL → 400 `RATIONALE_REQUIRED` when absent; row stays; projection REJECTED. **200, never error-colored.** `@Governed("finding.reject")`.
- Replay of the same idempotencyKey returns the original result 200, no new event (UNIQUE backstop §3.1). Last event wins the projection; history is never deleted (PG trigger).
- DRAFT-kind events are written by `DraftCommitService` (§5.3) through this service's append path — one audit table, one discipline.
- SUPERSEDE events are system-authored at run finalization only (§4.5); there is no operator supersede verb.
- **List-filter defaults:** `GET /api/v1/ecosystem/findings` (the existing list surface) gains additive `?curationStatus=`, `?includeRejected=` (default false), `?aiGenerated=`; default filter = `curation_status NOT IN ('REJECTED','SUPERSEDED') OR curation_status IS NULL`. NULL = not subject to curation (operator/fixture rows — DiagnosticSeeder rows stay NULL; demo behavior unchanged). Client (COO) surface composition additionally excludes `PENDING_REVIEW` (ADR D10 gate — flagged Mike decision). **Implementation step: grep-audit frontend consumers of /ecosystem/findings** (endpoints-barrel-omission trap precedent).

---

## 10. API surface + CLI

### 10.1 REST (app module, `{data, meta, errors}` envelope)

| Endpoint | Notes |
|---|---|
| `POST /api/v1/diagnostics/runs` | `{domainId!, scopes?, targetEntityIds?, batchSize?, requiredMinFindings?, executor?, dryRun?, idempotencyKey!}` → 202 `{runId}` (200 plan body when dryRun). 409 `DIAGNOSTIC_RUN_ACTIVE`. |
| `GET /api/v1/diagnostics/runs?domainId=` | run summaries, newest first |
| `GET /api/v1/diagnostics/runs/{id}` | status, counts, batch ledger, filter histogram, explanation, token/cost totals, provenance digest |
| `POST /api/v1/diagnostics/runs/{id}/resume` | §4.4 |
| `POST /api/v1/diagnostics/runs/{id}/cancel` | §4.4; 200 neutral |
| `GET /api/v1/diagnostics/verify?domainId=` | §6.1 chain-integrity sweep |
| `GET /api/v1/findings/{id}/provenance` | §6.1 full walk |
| `POST /api/v1/findings/{id}/accept` | `{rationale?, idempotencyKey!}` → 200 |
| `POST /api/v1/findings/{id}/reject` | `{rationale!, idempotencyKey!}` → 200 |
| `POST /api/v1/ai/generative/commit` | `{assertionId!, overrides?, idempotencyKey!}` → 201 Capability(DRAFT) |
| `POST /api/v1/ai/generative/reject` | `{assertionId!, rationale!, idempotencyKey!}` → 200 |
| `GET /api/v1/ecosystem/findings` | additive params per §9 |
| `GET /api/v1/portfolio` · `GET /api/v1/portfolio/subsidiaries/{domainId}` | §12 |
| `POST /api/v1/transformation/plans` · `POST /api/v1/transformation/plans/{id}/initiatives` · `PATCH /api/v1/initiatives/{id}/progress` | operator-grade thin-slice CRUD; progress patch = `{progressPct (0..100)!, progressNote?}`, `@Governed("initiative.update_progress")`. No wave/state-machine validation (§15.9). The fixture `GET /transformation/plan` stays behind its flag, untouched. |

### 10.2 Zero-yield trichotomy (no-silent-zero-yield, binding)

1. **Zero parseable candidates across ALL batches** → run `FAILED reason=NO_CANDIDATES` — ALWAYS a hard fail ("command exited 0" is never "product worked"), distinct from all-filtered.
2. **Candidates produced, all filtered** (committed=0): `requiredMinFindings>0` → `ZERO_YIELD`; else `COMPLETED` with a **mandatory explanation** + per-batch filtered counts + `DIAG_EVENT kind=yield reason=ALL_FILTERED yield=0`.
3. **committed < requiredMinFindings** → `ZERO_YIELD` (`reason=BELOW_REQUIRED required=N committed=M`).

Additionally: ALL CONTENT candidates filtered (regardless of STRUCTURAL yield) → `DIAG_EVENT kind=all_content_filtered` warning; STRUCTURAL-only yield → `DIAG_EVENT kind=no_content_findings` (Mike ruling pending, ADR follow-up #6).

### 10.3 CLI — `DiagnosticRunner` (EvalRunner/IngestionCommand idiom)

```
gradlew :app:bootRun --args="--spring.profiles.active=dev-shared --substrate.diagnostic.run=true \
  --substrate.diagnostic.domain-name=Glacier --substrate.diagnostic.required-min-findings=1 \
  --substrate.llm.provider=mock"
```

`ApplicationRunner` gated on `substrate.diagnostic.run=true`; resolves domain by name; executes a synchronous run through the SAME governed services (no `@Ungoverned` — diagnostics run on possibly-ACTIVE engagements, unlike the D3 ingestion exemption); emits structured `DIAG_EVENT` lines per batch + a run summary:

```
DIAG_EVENT kind=run_started runId=… domain=… scopes=… batches=N executor=in-process
DIAG_EVENT kind=batch_completed runId=… batch=3/7 returned=9 filtered=2 committed=7
DIAG_EVENT kind=run_finalized runId=… status=COMPLETED committed=18 filtered=5 superseded=12 tokensIn=… tokensOut=… costEstimate=…
```

**Exit codes** (mirrors EvalRunner): `0` ran-and-yield-ok (incl. legitimate `COMPLETED yield=0` when required-min-findings=0 — with the explicit yield event) · `2` run FAILED (incl. NO_CANDIDATES) · `3` HARD FAIL `ZERO_YIELD` · `4` misconfiguration (unknown domain, runner without required props).

---

## 11. E2E pipeline — Glacier corpus is the acceptance input

### 11.1 `dev-shared` profile (local two-process acceptance on the Docker-less machine)

Verified gap: app dev = `jdbc:h2:mem:substrate`; ingestion dev = `jdbc:h2:file:./data/ingestion-dev` — two different databases. Fix: new `app` profile `dev-shared` with `url: jdbc:h2:file:./data/engagement-dev;AUTO_SERVER=TRUE;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH`; ingestion's `dev` profile **realigned** to the same URL (`./data/ingestion-dev` retired — breaking dev-only change, flagged at slice close). Flyway ownership unchanged (shared migration classpath per I1). H2 AUTO_SERVER cross-process locking is acceptance convenience, NOT the gate.

### 11.2 Tester-runnable sequence (real input, no report-only completion)

```
1. gradlew :ingestion:bootRun --args="convert-and-stage --input samples"        # ANTHROPIC_API_KEY, or --substrate.llm.provider=mock
   → INGEST_EVENT commit-ready, exit 0
2. gradlew :ingestion:bootRun --args="commit --domain Glacier"
   → INGEST_EVENT commit_complete, entityCounts > 0, Evidence rows extractor='ingestion'
3. gradlew :app:bootRun --args="--spring.profiles.active=dev-shared"
4. curl :8080/api/v1/domains                                                    # → Glacier domainId
5. curl -X POST :8080/api/v1/diagnostics/runs -d '{"domainId":"…","requiredMinFindings":1,"idempotencyKey":"e2e-1"}'
6. poll GET /api/v1/diagnostics/runs/{id} → COMPLETED, batch ledger green
7. curl '/api/v1/ecosystem/findings?domainId=…&aiGenerated=true&curationStatus=PENDING_REVIEW'
   → rows: aiGenerated=true, synthesisRunId set, evidence[].sourceFile = glacier-*.csv
8. curl /api/v1/findings/{id}/provenance → chainComplete=true, groundingMode=EXISTENCE_ONLY,
   evidence extractor='ingestion', documentRegistry sha256 + processedByConverterRunId present
9. POST one accept; POST one reject (with rationale); re-run step 5 →
   remaining PENDING superseded, ACCEPTED/REJECTED untouched; third run → stable counts
10. curl /api/v1/diagnostics/verify?domainId=… → brokenChains=[], fixtureGrounded=[]
```

Negative proof: empty domain + `requiredMinFindings=1` → `ZERO_YIELD`, CLI exit 3, `DIAG_EVENT kind=zero_yield`.

**Real-LLM discipline:** the anthropic-provider run may legitimately vary; exactly ONE framed re-run is permitted (tactical-retry rule), and the run-detail JSON (`GET /runs/{id}` body) is preserved to a durable Mike-accessible path — `substrate/docs/acceptance/diagnostic-glacier-<date>.json` — before any worktree is reaped. STRUCTURAL-only yield passes the gate but logs `no_content_findings` (ADR follow-up #6).

### 11.3 CI gate (the real gate)

Single-JVM Testcontainers-Postgres e2e in the `IngestionPipelineE2eTest` pattern: ingestion services convert+commit the Glacier corpus in-process → diagnostic run with a **scripted `MockAnthropicClient` responder** that (a) issues a `find_evidence` tool call, (b) emits one grounded CONTENT candidate citing real Evidence ids read from the tool result, (c) one ungrounded candidate that MUST be filtered, (d) one invalid-severity candidate that MUST be filtered → asserts committed/filtered counts, the enriched evidence snapshot, chainComplete=true on the committed CONTENT finding, resume idempotency, supersession on re-run, and the zero-yield path. H2-green is never acceptance.

---

## 12. D5 — Computed statistics (severs S6; P7: deterministic lenses compute, the LLM narrates — never generates analytics)

`PortfolioReadService` (app.core — cross-engine composition) + `PortfolioController`. A Beacon subsidiary IS a Domain (ADR-001 D13); the portfolio = the engagement's non-demo domain set (demo isolation honored). DTOs speak fixture vocabulary at the `BEACON_PORTFOLIO` swap seam; **every stats DTO distinguishes absent-vs-zero structurally** and carries per-source sub-counts + a `computedFrom` meta block. No stats tables, no LLM in the path, computed per request (REVISIT at >50 domains or aggressive polling).

### 12.1 `lifecycleStallCount` (portfolio) — exact formula

```
sequencedPhases  := [DESIGN, MAP, PLAN, EXECUTE, MONITOR, AUTOMATE]      // PLUG_IN excluded (continuous, never exits)
currentPhase(d)  := the PhaseState row of d with the highest sequencedPhases index
                    whose status ∈ {IN_PROGRESS, BLOCKED}; undefined if none
stalled(d)       := currentPhase(d) defined AND (
                      status = BLOCKED
                      OR (status = IN_PROGRESS
                          AND now − max(enteredAt, updatedAt) > stall-threshold-days))
lifecycleStallCount := |{ d ∈ portfolio : stalled(d) }|
unphased            := |{ d ∈ portfolio : d has zero PhaseState rows }|   // SEPARATE field, never folded in
```

Checkpoint/blocker edits count as movement via `updatedAt`. Threshold default 90d (`substrate.stats.stall-threshold-days`) — the fixture's "90+ days" one-liner becomes a config knob; first-engagement calibration item.

### 12.2 `coordinationPatternsThisWeek` (portfolio) — delegated

≡ the contract engine's `ContractSignalService` §11.2 computation (contract_events + cascade events, trailing `pattern-window-days`). `PortfolioReadService` injects the same bean — one computation, two surfaces, zero drift. The DTO exposes the sub-counts that computation already owns (`{contractEvents, cascadeActions}`) + `computedFrom`; pre-D2-3b the contract term contributes 0 **visibly**. Richer union (monitor signals, sibling-decline advisories) = §15.10, a SignalService-spec amendment, never a portfolio-side fork.

### 12.3 `pendingFederationInvitations` (per subsidiary) — delegated

≡ §11.2: `COUNT(contracts c: c.type='federation-invitation' ∧ c.status=PROPOSED ∧ ∃ party p of c binding this domain with response_required ∧ ∄ contract_party_responses row for p)`. Via `ContractSignalService`.

### 12.4 `surfacedAt` (per finding) — exact rule

```
surfacedAt(f) := f.curationStatus IS NULL        → f.createdAt        // operator/fixture findings
                 f.curationStatus = ACCEPTED      → f.curatedAt        // the ACCEPT event instant = client-visibility instant
                 otherwise (PENDING_REVIEW/REJECTED/SUPERSEDED) → not client-surfaced (excluded from COO views)
```

"Surfaced N days ago" is computed CLIENT-side from the served `surfacedAt`. *Spec correction 2026-06-11 (S15 status, honest):* the backend half of S15 is severed (system `Clock` default; `DemoFixedClockConfig` is the opt-in demo lever), but the FRONTEND half is built-yet-unarmed — `clock.ts` ships `setPortfolioClock` with no production caller, so the active portfolio clock is the fixture date (`DEMO_CURRENT_DATE`). That is CORRECT today (the findings region renders fixture findings, whose dates are coherent with the fixture clock) — but the findings live swap (FIX-06 slice) MUST arm the live clock in the same change, or every real finding surfaced after the fixture date renders "Surfaced 0 days ago" forever (the `daysBetween` clamp). The swap checklist item "DEMO_CURRENT_DATE → injectable clock already handled by C9/S15" covers the SEAM existing, not the swap having happened. **Flagged product decision (ADR D10/follow-up #1):** if Mike rules AI findings client-visible pre-curation, this becomes `createdAt` everywhere — projection change only.

### 12.5 `progressPct` (per initiative) — V8 thin slice

```
renderedProgressPct(i) := i.status = COMPLETE ? 100 : i.progress_pct    // the ONLY computed rule
```

Operator-maintained via `PATCH /api/v1/initiatives/{id}/progress`; `progress_updated_at` staleness is surfaced in the DTO. No derivation from cascade/phase activity — that would fabricate analytics (rejected, ADR D13).

### 12.6 Finding rollups (per subsidiary)

`findingCounts := GROUP BY severity over findings WHERE domain_id=d AND (curation_status NOT IN ('REJECTED','SUPERSEDED') OR curation_status IS NULL)`; client-surface variant additionally excludes `PENDING_REVIEW`. Sub-counts: `{aiGenerated, operatorAuthored}`. A rejected AI claim never inflates a dashboard.

### 12.7 Composition fields

- `lifecyclePhase` (per subsidiary) := `currentPhase(d)` per §12.1; undefined → `phaseStatesUninitialized:true` needs-input signal.
- Agent stats := D3 registry read service when the bean exists (counts by AgentStatus live/shadow/paused/in-intervention; in-intervention agents render beside decline-pending cascades per [CASCADE-PROPAGATION]); absent → `agentsAvailable:false`. `aiReadiness` is **omitted** (no computation basis until D3's readiness lens) — flag-not-invent.
- `coordinationSignals` strip: stall count + patterns + decline-pending cascades (D1 attention feed) + in-intervention agents (D3) + needs-input classes (contracts §11.1).
- `crossPortfolioOpportunities` := contracts `type='cross-portfolio-opportunity'` via the contract read model.
- `companyName` := engagement/DNA config (S10) — never baked demo identity.

`GET /api/v1/portfolio` → `{companyName, subsidiaries:[{domainId, name, lifecyclePhase, stats…}], coordinationSignals, crossPortfolioOpportunities, unphased, computedFrom}`; `GET /api/v1/portfolio/subsidiaries/{domainId}` → composition of capabilities/processes/systems (existing repos), findings (with surfacedAt/aiGenerated), agents, initiatives (V8), pendingFederationInvitations, attention items. The C9 dashboards swap at the `BEACON_PORTFOLIO` factory seam with thin adapters.

---

## 13. Eval-suite + regression-harness expansion (severs S17 for the diagnostic path)

Expand `engine/src/main/resources/eval/` beyond the single placeholder, runnable through the EXISTING documented EvalRunner command + exit codes (0/2/3/4):

- **Validative JSON-contract cases:** well-formed candidate array; prose-wrapped array; hallucinated severity `URGENT` (filtered); missing `suggestedAction` (filtered); CONTENT candidate with fabricated evidence ids (filtered); optional `title`/`findingType` round-trip; invalid `findingType` kept-with-default.
- **Generative allowlist cases:** extra `reasoning` field dropped; no allowlisted fields → filtered; ungrounded proposal → filtered.
- **Tool-grounding cases:** `find_evidence` scripted tool-call against `MockAnthropicClient` → CONTENT candidate citing returned ids survives; **regression proof that registering find_evidence + prompt extension does not perturb the pre-existing eval expectations** (the pinned-surface guard).
- Harness gains `mode: validative|generative` asserting structured parseability + filter outcomes (extends the existing TestCase shape).

Pipeline-level regression is Testcontainers integration testing (§14), not harness cases. `SynthesisRun.metadata` records `promptTemplateVersion` + model per run so prompt drift is attributable run-over-run — the harness catches regressions pre-merge; run metadata catches them in the field.

---

## 14. Build order + test plan

Build order (read-model-first, binding; `gradlew build` green at every boundary; acceptance at every step = boot + curl/CLI):

1. **V7 + V7_1 + entities/repos** (Finding columns, AiCurationEvent, DiagnosticRunBatch) — boots under `ddl-auto=validate` on both profiles.
2. **ProvenanceWalkService + `/findings/{id}/provenance` + `/diagnostics/verify`** — user-runnable against DemoPack immediately (fixture chains visible, `extractor='seed'` classed) before any generation exists.
3. **`find_evidence` tool + `CandidateFilter` extraction** — zero behavior change; phase2a green unmodified.
4. **Run lifecycle + planner + batch executor + FindingCommitService + in-process executor + REST + `DiagnosticRunner`** — mock-provider acceptance; `dev-shared` profile lands here.
5. **CurationService + curation REST verbs + list-filter defaults + supersession-at-finalization.**
6. **DraftCommitService + generative commit/reject endpoints.**
7. **Glacier E2E (§11.2) + Testcontainers e2e (§11.3) + eval expansion (§13).**
8. **OrchestratorDiagnosticExecutor** behind the flags (can slip — in-process is the whole-or-in-part base case; PEP#3 governed-apply wiring point lands here, full gateway transit when contracts 3c ships).
9. **D5:** V8 + thin-slice CRUD + PortfolioReadService + portfolio endpoints (composing D1 attention feed, D2 ContractSignalService, D3 registry as available) → dashboard live-wiring at `BEACON_PORTFOLIO` + screenshot harness + distinct absent-vs-zero rendering. (Acceptance lever: DemoPack fixed-clock seed flips stall count 0→1 demonstrably.)

Test plan (H2 fast lane for units; **Testcontainers-Postgres is the gate**):

1. **CandidateFilter equivalence:** extraction lands with phase2a suites green, byte-identical filter outcomes (table test across all filter reasons).
2. **Mapping pins (§5.2):** severity matrix incl. INFO→LOW; findingType passthrough/default/invalid-kept; title fallback truncation; enriched evidence snapshot shape; unresolved affected-entity still commits.
3. **Differential grounding pinned:** CONTENT-without-resolving-evidence filtered; STRUCTURAL evidence-less passes with `evidence=[]`; evidence vanishing between filter and commit fails the candidate, not the batch.
4. **Batch atomicity + resume:** kill between generation and commit → batch RUNNING/stale → resume re-executes, zero duplicate findings; resume of terminal run = no-op 200; cancel semantics; SKIPPED_BUDGET neutral and non-blocking for COMPLETED.
5. **Zero-yield trichotomy matrix:** NO_CANDIDATES→FAILED/exit 2; all-filtered with required>0→ZERO_YIELD/exit 3; all-filtered with required=0→COMPLETED + mandatory explanation + yield event; committed<required→ZERO_YIELD; all_content_filtered + no_content_findings events fire.
6. **Supersession:** finalization-tx only; same-(domain,scope-set) PENDING superseded with event rows; ACCEPTED/REJECTED untouched; run-twice stable pending set; ZERO_YIELD/FAILED/CANCELLED supersede nothing; mid-run crash leaves prior PENDING co-present until resume finalizes (pinned as documented behavior).
7. **Curation:** REJECT without rationale → 400; idempotency replay → original 200, one event; ACCEPT-after-REJECT projects ACCEPTED; default list filter excludes REJECTED/SUPERSEDED, NULL rows unaffected (DiagnosticSeeder demo parity pinned); `includeRejected=true` restores.
8. **Draft commit:** producedBy/predicate guards; grounding re-verified at commit (`DRAFT_GROUNDING_LOST` 409); parentId→parentCapabilityId rename; overrides delta on ACCEPT event; reject creates no entity; double-accept idempotent; no bulk verb exists (compile-time absence + API surface assertion).
9. **Provenance walk:** chainComplete=true over a corpus-grounded finding; dangling-evidence hop → chainComplete=false with named missing hop; fixtureGrounded classification on seed chains; verify sweep aggregates.
10. **Concurrency:** one-active-run 409 under racing starts (service lock; PG partial-unique backstop fires under Testcontainers); curation idempotency race → one event row.
11. **Executor fallback:** scripted budget refusal → in-process fallback recorded `executor='in-process-fallback'`; `fallback=skip` → SKIPPED_BUDGET; dispatched result transits CandidateFilter (a tampered ungrounded candidate from the "agent" is filtered) and the apply carries `actionType=apply_dispatch_result`/SERVICE.
12. **CLI exit-code matrix** (IngestionCommandExitCodeTest idiom): 0/2/3/4 incl. the legit-zero `COMPLETED yield=0` exit-0-with-event case.
13. **Testcontainers e2e** (§11.3) — the D4 gate.
14. **D5 stats units (fixed clock):** stall matrix — BLOCKED counts; IN_PROGRESS dwell over/under threshold; updatedAt movement resets; PLUG_IN never current; unphased separate; COMPLETE-only domains not stalled. surfacedAt projection per §12.4. progressPct COMPLETE⇒100. Delegation test: portfolio number == ContractSignalService number by construction (same bean). Absent-vs-zero DTO shape (`agentsAvailable:false` vs 0). Adapter deep-equals at the BEACON_PORTFOLIO seam (frontend vitest).
15. **Governance/ArchUnit:** all §10 write verbs `@Governed` with the named actionTypes; no `@Ungoverned` in `engine.diagnostic`; append-only repo rule for `ai_curation_events`; PG trigger raises on direct JDBC UPDATE/DELETE (Testcontainers).
16. **Postgres round-trip:** all new/altered entities persist+read under `ddl-auto=validate` against V7+V7_1+V8; TIMESTAMPTZ semantics (port-map traps #1/#4).
17. **Never-error-colored contract test** (MockMvc through envelope advice): reject → 200 `errors=[]`; cancel → 200; ZERO_YIELD run detail renders as product outcome, not exception; no diagnostic enum/status value serializes through an error mapping.

---

## 15. Flag-not-invent — explicit UNKNOWN-semantics extension points (`// EXTENSION POINT(diagnostic-N)` comments in code)

| # | Unknown | v1 modeling | Unblocks via |
|---|---|---|---|
| 1 | Semantic grounding verification | `GroundingVerifier` SPI, existence-only impl; future SEMANTIC verifier **advisory-only by contract** (may lower confidence, never auto-pass — P7) | regulated-buyer demand; embedding/judge infra |
| 2 | Passage-level evidence | document-level Evidence rows only; `EvidenceChunk`/retriever seam named | vector/document retriever (port-map §2.3) |
| 3 | Batch-accept curation affordance | structurally absent; per-item events only | Mike product session (ADR follow-up #2) |
| 4 | Client visibility of uncurated AI findings (+ surfacedAt semantics) | PENDING_REVIEW gate encoded as the S3 compensating control; one-line projection change if overruled | Mike product session (ADR follow-up #1) |
| 5 | Auto-run-after-ingest | `substrate.diagnostic.auto-after-ingest` named, default false, NOT built — pipeline is operator-paced | operator feedback |
| 6 | Generative targets beyond Capability | rejected by the mode service (no allowlist) — commit step matches | second entity type shipping through generative |
| 7 | Deterministic structural validators (replace LLM STRUCTURAL findings) | LLM STRUCTURAL findings accepted per pinned contract | first-corpus review; P7-alignment push |
| 8 | Agent-proposed findings as AGENT principals | none — orchestrated lane is SERVICE-applied only | D3 registry + governed MCP tools (never a bypass) |
| 9 | Planning engine proper (waves, rationalization lens, plan state machine, doc-06 sequencing rules) | V8 is a thin operator-tracked slice; transitions/wave rules unvalidated; fixture `/transformation/plan` stays flagged | S4 design session (V8 amendments flagged cheap, fresh-DB posture) |
| 10 | Richer coordinationPatterns union (monitor signals, sibling advisories) | §11.2 number verbatim, delegated | contracts SignalService spec amendment (ADR follow-up #4) |
| 11 | Supersession scope normalization | exact scope-set match only; overlapping scopes can accumulate pendings | operator triage feedback (ADR follow-up #7) |
| 12 | Decision-log attestation of curation | `DiagnosticAttestationHook` no-op; per-run digest behind `attest-to-decision-log` (default false); per-item mirroring rejected (D14 volume rationale) | regulated-buyer demand |
| 13 | INFO severity rendering / findingType taxonomy | INFO→LOW + differential defaults, metadata-marked | first-real-corpus review with Mike (ADR follow-up #3) |
| 14 | Run/batch retention + run archival | unbounded | first-engagement calibration |
| 15 | `aiReadiness` stat | omitted (`null` + `computedFrom` notes absence) | D3 readiness lens |
| 16 | STRUCTURAL-only acceptance ruling | passes gate + `no_content_findings` event | Mike ruling (ADR follow-up #6) |

---

## 16. Carried risks (implementation-relevant reminders)

- **The curation UX is load-bearing for S3.** If accept renders without the inline evidence chain, the audit-defensibility claim is hollow. Copy must never say "machine-verified grounding". Evidence is document-level until §15.2 lands — say so in any compliance conversation.
- **Pinned-surface blast radius:** the FindingCandidate additive fields, CandidateFilter extraction, find_evidence registration, and prompt extensions all touch the best-tested Phase-2a code. The compatibility constructor + scripted-mock eval proofs are the mitigation; any *semantic* pinned-test edit is a stop condition.
- **Default list-filter change** silently alters `/ecosystem/findings` consumers — frontend grep-audit is a named step, not optional.
- **H2 AUTO_SERVER (dev-shared)** is new territory (file locking, cross-process TIMESTAMPTZ parity) and is never the gate; PG-only behaviors (append-only trigger, partial unique index) are CI-verified ONLY on this machine — claiming local verification is a false-PASS.
- **Real-LLM yield is non-deterministic:** Glacier is deliberately defect-seeded so `requiredMinFindings=1` should pass, but a run can come back empty or all-filtered; exactly one framed re-run, artifact preserved durably (§11.2). The mock lane is the deterministic CI proof; the real lane is the product proof.
- **Curation identity is spoofable persona headers until Phase 5 OIDC** (S9) — the actor_label on ACCEPT events, the core audit artifact, is unauthenticated in Phases 2–4.
- **The orchestrated executor is transport-blocked** until the MCP Java SDK work lands (shared with the cascade suggest seam) — in-process is the shipping path; PEP#3 governed-apply gets little real exercise until contracts 3c + transport both exist.
- **Demo pressure on D5:** progressPct stays operator-tracked and agent stats degrade to `agentsAvailable:false` — the honest no-data renderings must survive review; fabricating progress from activity is the named failure mode.
- **JSON columns are TEXT** (§4.5); first jsonb-indexed query need (e.g. filter-histogram analytics) triggers the documented one-pass flip.
