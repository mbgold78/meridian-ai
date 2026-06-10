# Meridian Substrate Port Map — meridian-homepoc → substrate

**Status:** synthesis of 7 subsystem maps of `C:/Users/mbgol/dev/meridian-homepoc`, constrained by the ratified production plan (`orchestrator/docs/plan/substrate-production-plan.md`, v1, 2026-05-15).
**Target:** `C:/Users/mbgol/dev/meridian-ai/substrate` — Gradle modules `app` / `common` / `ingestion` / `engine`, package root `com.meridian.substrate`, Spring Boot 3.4.4, Java 21, React frontend at `substrate/frontend`.
**Audience:** implementation agents. Section 2 inventories are work orders — do not re-derive them from homepoc; they were extracted by dedicated mapping passes. Section 7 chunks define non-colliding file-level scopes.

**Scaffold already in place (do not re-create):** root/module `build.gradle` files (Boot 3.4.4 BOM, Java 21 toolchain, `-parameters`), `settings.gradle` with the 4 modules, `app/src/main/resources/application{,-dev,-postgres}.yml`, vendor-split Flyway locations (`db/migration/common` + `db/migration/{vendor}`), `V1__engagement_baseline.sql` (engagement table), `docker-compose.yml` (postgres:16 + Keycloak 26 + substrate-app), `Dockerfile`, `infra/keycloak/realm-substrate.json`, `docs/orchestrator-substrate-contract.md`.

---

## 1. Module mapping — homepoc → substrate

| homepoc source | substrate target | Disposition |
|---|---|---|
| `common/src/main/java/com/meridian/homepoc/common/domain/**` (24 entities, 26 enums, BaseEntity, JsonNodeConverter) | `common/src/main/java/com/meridian/substrate/common/domain/**` | Port verbatim + package rename |
| `common/src/main/java/com/meridian/homepoc/common/ai/**` (LlmClient layer, SdkAnthropicClient, LlmProperties) | `common/src/main/java/com/meridian/substrate/common/ai/**` | Port; drop deprecated `AnthropicClient` alias; config prefix `meridian.llm` → `substrate.llm` |
| `app/src/main/java/com/meridian/homepoc/app/**` repositories (27) | `common/src/main/java/com/meridian/substrate/common/repo/**` | **Relocated to common** so app, engine, and ingestion share one repository layer (eliminates homepoc's duplicate `Converter*Repository` set) |
| `app/src/main/java/com/meridian/homepoc/app/**` web layer (controllers, envelope, filters, exception handling, config) | `app/src/main/java/com/meridian/substrate/app/**` | Port + harden (PATCH in CORS, externalized origins, auth interceptor seam) |
| `app/.../app/ai/**` (orchestrator, tools, mode services, assertion, prompts, safety, cost, eval) + `app/.../decision/**` | `engine/src/main/java/com/meridian/substrate/engine/{ai,decision}/**` | Port + fix protocol/dead-wiring; controllers stay in app |
| `app/.../app/seed/**` + `resources/seed/*.json` | `app/.../app/demo/**` + `app/src/main/resources/demopack/` | Port as dev/demo-profile-gated DemoPack facility (`substrate.demo.seed=false` default); never the production data path |
| `app/src/main/resources/db/migration/V1–V11` | `app/src/main/resources/db/migration/common/V2__canonical_model.sql` (+ `db/migration/postgresql/V2_1__*.sql`) | **Squashed rewrite** — see §4 |
| `converter/src/main/java/com/meridian/homepoc/converter/**` | `ingestion/src/main/java/com/meridian/substrate/ingestion/**` | Port + rebuild commit path (idempotency, IngestRun audit, name→UUID resolution) |
| *(no homepoc counterpart)* cascade / contract / agent-provenance / diagnostic-commit engines | `engine/src/main/java/com/meridian/substrate/engine/{cascade,contract,agent,diagnostic}/**` | **Net-new builds**, constrained by §6 |
| `frontend/**` (Vite + React 18 + TS) | `substrate/frontend/**` | Port tooling + tokens + product surfaces + 4 Beacon dashboards; exclude orphaned legacy features and walkthrough machinery |
| `docs/redesign/**`, `phases/specs/06-governance-*.md`, `docs/decisions/**`, `docs/positioning/why-now.md` | governing constraints (no code) | Semantics copied into §6; ratified copy is altitude-3 locked |

**Module dependency rule (already encoded in build files):** `common` ← `engine` ← `app`; `common` ← `ingestion`. `app` and `ingestion` are boot apps; `common` and `engine` are `java-library`. Ingestion never depends on engine or app. "Whole or in part": ingestion runs as a standalone CLI/boot app against the same Postgres; app runs without orchestrator (degraded mode — `substrate.orchestrator.enabled=false` hides/no-ops AI-assist affordances).

---

## 2. Port inventory per subsystem

### 2.1 common — canonical data model

Target: `substrate/common`, package `com.meridian.substrate.common.domain` (+ `.domain.enums` if splitting enums out — keep homepoc's flat layout unless it already separates them; match source layout).

Key files (source → role):

| Source | Role / port note |
|---|---|
| `C:/Users/mbgol/dev/meridian-homepoc/common/src/main/java/com/meridian/homepoc/common/domain/BaseEntity.java` | @MappedSuperclass: UUID id (GenerationType.UUID), @CreationTimestamp createdAt, @UpdateTimestamp updatedAt, createdBy default "system", @Version Long version. Port unchanged. |
| `.../common/domain/JsonNodeConverter.java` | AttributeConverter<JsonNode,String> → TEXT. **Kept as the JSON persistence mechanism (see §4 JSONB decision).** |
| `.../common/domain/Domain.java` | Top-level org unit; everything hangs off domainId. Keep `isDemo` flag. |
| `.../common/domain/Process.java` | Core unit of tracked work (post-V8 name). |
| `.../common/domain/Assertion.java` | Triple-shaped provenance store (ProducedBy enum, evidence chain). |
| `.../common/ai/LlmClient.java`, `SdkAnthropicClient.java`, `AnthropicClientConfig.java`, `config/LlmProperties.java` | Provider-neutral LLM layer; see 2.3. |
| `.../common/build.gradle` | Already mirrored at `substrate/common/build.gradle` — do not duplicate. |
| `app/src/main/resources/db/migration/` V1–V11 | Schema source of truth for the squash (§4). |
| `app/.../app/config/JacksonConfig.java` | JavaTimeModule + Hibernate6Module + ISO dates; load-bearing — entities ARE the wire format. |

**Entity inventory (24 — port all, preserving table/column names and the FK-UUID + @JsonIgnore lazy navigation-twin pattern):**

- Domain (table `domains`): name!, description TEXT, taxonomyConfig/regulatoryContext/metadata JSON, isDemo boolean!
- SystemEntity (table `systems`): domainId + lazy Domain, name!, vendor, description, systemType, deployedYear, status (free-text String), businessLine, metadata JSON
- Process (table `processes`): domainId, name!, description!, classification ManualProcessClassification!, frequency, primaryOwner, businessLine, defenseLine, documentationQuality, estimatedFte Double, riskRating, supportingArtifacts JSON, metadata JSON
- ProcessStep (table `process_steps`): processId!, sequenceOrder int!, name!, description, stepType, executionMode ProcessStepExecutionMode, automationCandidate boolean!, automationRationale, agentReadinessScore Integer, linkedSystemId, assignedPersonId, estimatedMinutes
- ProcessRole (table `process_roles`): processId!, memberId!, participation!, timeCommitment, automationCandidate, automationRationale
- ProcessSystemLink (table `process_system_links`): processId!, systemId!, linkType LinkType!, dataElements JSON, frequency, status LinkStatus, aiInferred
- OrganizationMember (table `organization_members`): domainId, name!, title, department, businessLine, reportsTo (name string, not FK), metadata JSON
- CanonicalEntity (table `canonical_entities`): domainId, systemId, name!, entityType!, description, attributes JSON, executionMode, processId, metadata JSON
- EntityOverlap (table `entity_overlaps`): domainId, sourceEntityId, targetEntityId, overlapType String, confidence Double, details JSON, aiInferred
- RoleOverlap (table `role_overlaps`): domainId, sourceMemberId, targetMemberId, overlapType, details JSON, aiInferred
- Integration (table `integrations`): domainId, sourceSystemId, targetSystemId, name, integrationType, integrationStatus, dataElements JSON, frequency, description, metadata JSON
- Finding (table `findings`): domainId, findingType!, severity, status, title!, description!, affectedSystemIds JSON array, affectedProcessIds JSON array, evidence JSON, recommendation, aiGenerated, synthesisRunId, metadata JSON
- DesignDecision (table `design_decisions`): domainId, decisionType!, status!, title!, description!, rationale, impactSummary JSON, affectedSystemIds/affectedProcessIds JSON, previousHash/currentHash (hash-chain), metadata JSON
- SynthesisRun (table `synthesis_runs`): domainId, runType, status (free-text), startedAt/completedAt, findingsGenerated, model, totalInputTokens/totalOutputTokens, totalCostEstimate
- ChatInteraction (table `chat_interactions`): domainId, personaId, userMessage/assistantMessage, toolCalls JSON, model, promptTemplateId/Version, inputTokens/outputTokens, latencyMs, refusalDetected, costEstimate
- Assertion (table `assertions`, indexed domain_id/produced_by/subject): domainId!, subject!(500), predicate!(255), object!, confidence Float, evidenceIds JSON, producedBy!, producedByContext JSON
- Evidence (table `evidence`, indexed entity_id/domain_id): entityId!, entityType!(100) (polymorphic soft ref), domainId, documentRegistryId, sourceFile, confidence Float, label(500), extractor(100)
- DocumentRegistry (table `document_registry`): sourceFile!, sourceFileSha256!, sourceFileSize, documentType, sourceSystem, ingestedToTachyon!=false, tachyonFileId, tachyonCollection, ingestionStatus!=LOCAL_ONLY, ingestedAt, extractedTextBytes, processedByConverterRunId, metadata JSON — tachyon fields: keep columns for now, rename decision deferred until the real document index lands (Phase 4)
- SystemUpload (table `system_uploads`): systemId, fileName, fileSize, contentType, storagePath, metadata JSON
- Capability (table `capabilities`): domainId!, parentCapabilityId (self-tree), name!, displayName!, description, level int!, status!=DRAFT, sourcePackId, aiGenerated boolean!, metadata JSON, sequenceOrder int!
- CapabilityNameHistory (table `capability_name_history`): capabilityId!, fromDisplayName!, toDisplayName!, rationale
- CapabilityProcessLink (table `capability_process_links`): capabilityId!, processId!, linkType(50), aiInferred boolean!
- CapabilitySystemLink (table `capability_system_links`): capabilityId!, systemId!, linkType(50), aiInferred boolean!
- PhaseState (table `phase_states`, unique(domain_id,phase)): domainId!, phase LifecyclePhase!, status!=NOT_STARTED, enteredAt, blockers JSON, checkpoints JSON
- ReaderRegistration (table `reader_registrations`): domainId!, kind ReaderKind!, name!, uri(2048), authContext JSON, registeredAt/lastReadAt, lastFingerprintHash(255), status!=ACTIVE, coverageScore Double, metadata JSON
- BrandingConfig (table `branding_configs`, indexed domain_id/status): domainId!, appDisplayName!, tagline(512), colorTokensLight!/colorTokensDark! JSON, fontBody!/fontHeading!/fontMono!, spacingTokens/radiusTokens/shadowTokens JSON, 6 blob keys, terminologyOverrides! JSON, email* fields, legalFooterText, privacyPolicyUrl/termsOfServiceUrl/customCssUrl(512), status!=DRAFT

**Enum inventory (26 — port verbatim, pure value sets):** BrandingConfigStatus (DRAFT, ACTIVE); CapabilityStatus (DRAFT, ACTIVE, DEPRECATED); DecisionStatus (PROPOSED, APPROVED, REJECTED, SUPERSEDED); DecisionType (CONSOLIDATE, RETIRE, INTEGRATE, DECOUPLE, MODERNIZE, ELIMINATE_MANUAL_PROCESS, AUTOMATE_MANUAL_PROCESS, FORMALIZE_MANUAL_PROCESS, ABSORB_MANUAL_PROCESS); DefenseLine (FIRST_LINE, SECOND_LINE, THIRD_LINE, AUDIT); DocumentType (SCHEMA, USER_GUIDE, SAMPLE_DATA, ARCH_SPEC, ORG_FILE, REFERENCE, PROCESS_DOC, OTHER); DocumentationQuality (DOCUMENTED, PARTIALLY_DOCUMENTED, UNDOCUMENTED, TRIBAL_ONLY); ExecutionMode (SYSTEM, MANUAL, HYBRID); FindingStatus (OPEN, ACKNOWLEDGED, IN_PROGRESS, RESOLVED, WONT_FIX); FindingType (DUPLICATION, COVERAGE_GAP, STALE_ASSESSMENT, ROLE_OVERLAP, INTEGRATION_BROKEN, DATA_QUALITY, MANUAL_PROCESS_DEPENDENCY, UNDOCUMENTED_WORKFLOW, COMPENSATING_CONTROL_RISK, TRIBAL_KNOWLEDGE_RISK, SHADOW_WORKFLOW_DETECTED, MANUAL_RECONCILIATION_VOLUME); IngestionStatus (LOCAL_ONLY, PENDING, INDEXING, READY, FAILED); IntegrationStatus (ACTIVE, BROKEN, DEPRECATED, PLANNED); IntegrationType (BATCH, REALTIME, MANUAL, API, FILE_TRANSFER); LifecyclePhase (PLUG_IN, DESIGN, MAP, PLAN, EXECUTE, MONITOR, AUTOMATE); LinkStatus (ACTIVE, BROKEN, DEPRECATED); LinkType (BRIDGES_FROM, BRIDGES_TO, COMPENSATES_FOR, FEEDS_INTO, READS_FROM, PARALLEL_TO); ManualProcessClassification (SIGN_OFF, RECONCILIATION, BRIDGE, COMPENSATING_CONTROL, SHADOW_WORKFLOW, TRIBAL_KNOWLEDGE, OTHER); Participation (OWNER, EXECUTOR, APPROVER, REVIEWER, CONSULTED, INFORMED); PhaseStatus (NOT_STARTED, IN_PROGRESS, BLOCKED, COMPLETE); ProcessStepExecutionMode (MANUAL, SEMI_AUTOMATED, AUTOMATED); ProducedBy (AI_GENERATIVE, AI_ASSISTIVE, AI_VALIDATIVE, HUMAN, AGENT); ReaderKind (STRUCTURED, DOCUMENT, TRANSCRIPT, VISUAL, HUMAN, MCP_CLIENT); ReaderStatus (ACTIVE, STALE, ERROR, DEPRECATED); RiskRating (LOW, MEDIUM, HIGH, CRITICAL); Severity (LOW, MEDIUM, HIGH, CRITICAL); StepType (HUMAN, SYSTEM, DECISION, WAIT, HANDOFF).

**AI DTO/record inventory (port to `common.ai`):** CompletionRequest (model, systemPrompt, List<Message>, maxTokens, temperature=0.7, metadata; nested Message(role, content)); CompletionResponse (id, content, model, stopReason, TokenUsage); CompletionChunk (id, delta, stopReason, usage); TokenUsage (inputTokens, outputTokens, totalTokens()); ToolCallRequest (model, systemPrompt, messages, List<ToolDefinition>, maxTokens, temperature, metadata; nested ToolDefinition(name, description, inputSchema)); ToolCallResponse (id, model, stopReason, List<ContentBlock>, usage; ContentBlock with text/toolUse factories); LlmRateLimitException. **DROP** the deprecated `AnthropicClient` alias interface. Extend the message model to carry content blocks (needed by the orchestrator protocol fix, 2.3).

**API request/envelope DTOs (port to `app.api.v1`):** ApiResponse<T> (data, ApiMeta, List<ApiError>; ok()/error() factories); ApiMeta (timestamp, requestId, version, personaLevel); ApiError (code, message, field); CapabilityCreateRequest; CapabilityUpdateRequest; CapabilityLinkRequest; CapabilityReparentRequest; CapabilitySequenceRequest; ProcessStepCreateRequest; ReaderRegistrationCreateRequest.

**Hard rules from the map (port-blocking):**
- Every FK is modeled twice: writable UUID column + read-only `@JsonIgnore @ManyToOne(LAZY, insertable=false, updatable=false)` navigation twin. The UUID columns are the wire contract. Preserve exactly.
- Soft references (Evidence entityId+entityType, Finding/DesignDecision affected*Ids JSON arrays, Assertion.evidenceIds, ProcessStep.linkedSystemId/assignedPersonId, OrganizationMember.reportsTo) have NO DB FKs — integrity is application-enforced. Do not "fix" by adding FKs without a decision; document in code comments.
- No Lombok on entities (declared but unused in homepoc) — keep handwritten getters/setters; Lombok stays compileOnly in build for other code.
- Validation annotations live only on `app.api.v1` request records, never on entities.
- Free-text status fields (SystemEntity.status, SynthesisRun.status/runType, overlap.overlapType, capability link.linkType) port AS-IS; tightening to enums is a behavior change to seed data + frontend — out of scope for Phase 1.
- Optimistic locking @Version is NOT NULL — any bulk path bypassing JPA (ingestion) must populate version/created_at/updated_at/created_by.
- Verify with a Postgres Testcontainers round-trip test: all 24 entities persist + read under ddl-auto=validate (JSON columns + optimistic locking included). H2-green is not the gate.

### 2.2 backend web layer (homepoc app module)

Target: `substrate/app`, package `com.meridian.substrate.app`, preserving layering `api/v1/{controller,request,response}`, `core/{service,exception}`, `infrastructure/{web,reactive,storage}`, `config`. Repositories move to `common.repo` (§1). `MeridianApplication` → `SubstrateApplication` (exists) gains `@EntityScan("com.meridian.substrate.common.domain")`, `@EnableJpaRepositories("com.meridian.substrate")`, `@EnableJpaAuditing`.

**Endpoint inventory (~60 — the /api/v1 contract the ported frontend depends on; keep every path/shape and the {data, meta, errors} envelope):**

AI: POST /api/v1/ai/assistive (SuggestRequest → SuggestResponse; allowlist SUGGEST_DESCRIPTION/DETECT_NAME_COLLISION/SUGGEST_PARENT_CAPABILITY) · POST /api/v1/ai/generative (GenerateRequest → GenerateResponse drafts) · POST /api/v1/ai/validative (ValidateRequest → ValidateResponse findings).
Branding: GET /branding/configs?domainId · GET /branding/configs/{id} · GET /branding/configs/active?domainId (persisted ACTIVE or transient defaults) · POST /branding/configs (admin-gated) · PATCH /branding/configs/{id} (admin) · POST /branding/configs/{id}/activate (admin; demote-then-promote, PESSIMISTIC_WRITE) · POST /branding/assets (admin multipart, PNG/JPEG/SVG/WEBP/ICO ≤5MiB → BlobStore).
Capabilities: GET /capabilities?domainId · GET /capabilities/{id} · POST /capabilities · PATCH /capabilities/{id} (rename → CapabilityNameHistory) · PATCH /capabilities/{id}/parent · PATCH /capabilities/{id}/sequence (clamped, sibling pessimistic lock) · GET /capabilities/{id}/name-history · GET/POST/DELETE process-links and system-links endpoints.
Chat: POST /chat (ToolCallingOrchestrator; persists ChatInteraction) · GET /chat/stream (SSE, envelope-exempt, MDC via MdcContextLifter).
LLM/cost: GET /llm/cost-summary.
Decision log: GET /decision-log?domainId · GET /decision-log/verify?domainId · POST /design/decisions · GET /design/decisions(+/{id}) · POST /design/decisions/{id}/approve|reject · POST /design/impact-analysis.
DNA: GET /dna · GET /dna/preamble.
Domains: GET /domains · GET /domains/{id}.
Ecosystem: GET /ecosystem/graph?domainId · GET /ecosystem/findings · GET /ecosystem/overlaps · GET /ecosystem/manual-dependencies.
Evidence: GET /evidence/entity?entityId · GET /evidence/domain?domainId.
Health/info: GET /health · GET /info.
Intelligence: GET /intelligence/thesis?domainId&mode · GET /intelligence/assistant?domainId (both deterministic, no LLM).
Organization: GET /organization/members · GET /organization/role-overlaps.
Phase states: GET /phase-states?domainId · GET /phase-states/{phase} · POST /phase-states/initialize.
Processes: GET /processes?domainId · GET /processes/{id} · GET /processes/{id}/system-links · GET /processes/{id}/roles · GET/POST/PATCH/DELETE /processes/{processId}/steps(/{stepId}).
Readers: GET/POST/DELETE /readers(/{id}) · POST /readers/{id}/probe · POST /sources/probe (live outbound HTTP — see risks).
Systems: GET /systems?domainId · GET /systems/{id} · GET /systems/{id}/entities · GET /systems/{id}/integrations.
Transformation: GET /transformation/plan — **fixture today** (serves transformation-plan-mockup.json verbatim); port behind a feature flag until the plan engine lands.
SPA fallback: SpaController forwards /ecosystem, /diagnostics, /designer, /transformation, /roadmap, /decisions (+/**) → /index.html.

**Cross-cutting infrastructure (port first; engine-agnostic, high quality):**
- ResponseEnvelopeAdvice — wraps every @RestController return in ApiResponse; skips already-wrapped, SSE, null. Fix the `supports()`-returns-true-unconditionally issue: exclude springdoc (`/v3/api-docs`) and actuator paths or scope to `com.meridian.substrate.app.api`.
- GlobalExceptionHandler — all 9 mappings: EntityNotFound/NoResource→404, TypeMismatch/IllegalArgument→400, MethodArgumentNotValid→400 per-field, OptimisticLocking→409, LlmRefusal→422, LlmRateLimit→429, ResponseStatusException passthrough, catch-all→500; never leaks stack traces.
- RequestIdFilter (UUID → MDC `requestId` + X-Request-Id header) + MdcContextLifter/ReactiveMdcConfig (JVM-global Reactor hook for SSE MDC — verify no conflict with other instrumentation in the larger app).
- PersonaInterceptor + @RequestScope PersonaContext (X-Persona-Level/X-Persona-Id headers, default L3_ANALYST) — port as-is for Phase 1 (anonymous-auth mode); replaced as identity source by OIDC in Phase 5. BrandingAdminAuthorizer (X-Persona-Level: ADMIN gate) ports with the same caveat.
- CorsConfig — **add PATCH** to allowedMethods (missing in homepoc); externalize origins to `substrate.cors.allowed-origins` property.
- WebConfig (interceptor registration, index.html no-cache, /assets/** 1yr immutable), SpaController, JacksonConfig (JavaTimeModule + Hibernate6Module — load-bearing for entity-direct serialization; port together with the @JsonIgnore discipline).
- logback-spring.xml — LogstashEncoder JSON with requestId/persona MDC keys; plain pattern in demo profile.

**Service inventory (port in dependency order):** DomainService, SystemService, OrganizationService, ProcessService, ProcessStepService, FindingService, EvidenceService, EcosystemService(+EcosystemGraph) — thin CRUD wrappers. CapabilityService (tree CRUD, rename history, reparent cycle checks, sequence reorder under PESSIMISTIC_WRITE sibling locks — keep locking semantics). BrandingConfigService (single-ACTIVE invariant via pessimistic lock + BrandingDefaults transient fallback). DecisionLogService + HashChainVerification (→ engine.decision, see 2.3). ReductionService (deterministic thesis; **mark savings heuristics for replacement** — hardcoded vendor $180K/legacy $120K/ancillary $80K/core $250K/default $60K/+$15K-per-finding). AssistantService (deterministic completeness rubric). ImpactAnalysisService (LLM-backed). DnaService + DnaConfig (`meridian.dna.*` → `substrate.dna.*`; defaults hardcode "Meridian Bancorp · Risk Consolidation" — must come from engagement config). PhaseStateService. ReaderRegistrationService + ProbeService (SSRF surface — needs allowlist/async before production exposure). ManualProcessAnalyzer. BlobStore abstraction (MinioBlobStore / UnconfiguredBlobStore; production object-store decision open). SeedOrchestrator + 11 seeders + SeedContext/SeedFiles (→ demo pack, gated).

**Repository inventory (27 — relocate to `common.repo`, port verbatim; derived queries and the 6 JPQL @Query methods are dialect-neutral; PESSIMISTIC_WRITE maps to SELECT…FOR UPDATE on PG):** DomainRepository; SystemRepository(findByDomainId); SystemUploadRepository(findBySystemId); OrganizationMemberRepository(findByDomainId); RoleOverlapRepository(findByDomainId); ProcessRepository(findByDomainId, findByClassification); ProcessStepRepository(findByProcessIdOrderBySequenceOrderAsc); ProcessRoleRepository(findByProcessId, findByMemberId); ProcessSystemLinkRepository(findByProcessId, findBySystemId); CanonicalEntityRepository(findByDomainId, findBySystemId); EntityOverlapRepository(findByDomainId); IntegrationRepository(findByDomainId, findBySourceSystemIdOrTargetSystemId); FindingRepository(findByDomainId, findByFindingType, findByDomainIdAndFindingType); DesignDecisionRepository(findByDomainId, findByDomainIdAndStatus); ChatInteractionRepository(findByDomainIdOrderByCreatedAtDesc); SynthesisRunRepository(findByDomainId); DocumentRegistryRepository(findBySourceFileSha256); EvidenceRepository(findByEntityId, findByDomainId, findByEntityIdAndEntityType, findByDocumentRegistryId); AssertionRepository(findByDomainId, findByDomainIdAndProducedBy, findBySubject); CapabilityRepository(findByDomainId, findByDomainIdAndParentCapabilityIdIsNull, JPQL sibling queries + ForUpdate variants); CapabilityNameHistoryRepository; CapabilityProcessLinkRepository; CapabilitySystemLinkRepository; PhaseStateRepository(findByDomainId, findByDomainIdAndPhase); ReaderRegistrationRepository(findByDomainId, findByDomainIdAndKind); BrandingConfigRepository(findByDomainId, findFirstByDomainIdAndStatus + ForUpdate JPQL).

**Config keys to carry (renamed `meridian.*` → `substrate.*`, into application.yml):** llm.provider/anthropic.api-key/model/max-tokens/timeout-ms, llm.tools.max-calls-per-request/dispatch-timeout-ms, llm.cost.input-rate-per-million/output-rate-per-million (externalize — no hardcoded `claude-opus-4-6` or $15/$75 defaults in code), llm.safety.refusal-fallback-message, dna.*, blob-store.*, data/uploads/staging dirs, demo.seed (default false), multipart 5MB limits, springdoc. **Delete** homepoc's dev-profile `ddl-auto=update` override — validate everywhere.

**Tests to carry:** phase1 CRUD, phase2a AI-mode integration, phase2b1 branding/capability suites → onto Testcontainers-Postgres. They encode the behavioral contract (envelope shape, 403 admin gate, 409 optimistic-lock, reorder clamping, single-ACTIVE branding invariant).

### 2.3 AI orchestration (homepoc app/ai + common/ai)

Target: LLM client layer → `common.ai` (2.1); orchestration/tools/modes/assertion/decision-log → `engine` module (`com.meridian.substrate.engine.ai.*`, `engine.decision`); controllers remain in `app.api.v1`.

**Tool inventory (10 — port; they depend only on repos + Jackson):** count_by, find_canonical_entities, find_findings, find_organization_members, find_overlaps, find_processes, find_systems, get_decision_log, get_evidence_for_finding, get_system_profile (impl classes `CountByTool` … `GetSystemProfileTool`). They use findAll + in-memory filtering — acceptable to port as-is; flag for pagination later.

**Prompt inventory (copy into engine resources):** prompts/analyst-architect.md, analyst-auditor.md, analyst-dataowner.md, analyst-executive.md, analyst-sme.md, designer-impact.md; eval/sample-test-cases.json. **Fix during port:** loader resolves `analyst-{lowercased level}.md`, so `data_owner` needs `analyst-data_owner.md` (file is `analyst-dataowner.md`); L3_ANALYST and tech_executive silently fall back to DEFAULT_PROMPT — add files or make fallback loud. `designer-impact.md` is orphaned (ImpactAnalysisService builds its prompt inline) — wire it or delete it.

**Service inventory + required fixes (do NOT blind-port):**
- ToolCallingOrchestrator — multi-turn loop, MAX_ITERATIONS=10, DNA preamble + persona prompt, persona-filtered tools. **Protocol fix:** replace text-flattened tool results (`[Tool Result: …]` user messages) with native assistant `tool_use` blocks + user `tool_result` blocks via the SDK (requires content-block message model in common.ai DTOs). **Enforce** `tools.maxCallsPerRequest` (configured but ignored in homepoc).
- ToolRegistry — auto-discovers Tool beans; **move the hardcoded static persona→tool allowlist map to configuration** (it's compile-time static and duplicated with prompt files).
- ToolDispatcher — persona access check, timeout, MDC propagation; replace the unbounded cached thread pool with a bounded per-tool executor before production load.
- GenerativeModeService / AssistiveModeService / ValidativeModeService — keep allowlist + differential-grounding contracts VERBATIM (best-tested logic; phase2a tests pin them). Then build the **missing commit step** (engine.diagnostic): FindingCandidate → persisted Finding(aiGenerated=true, linked to its Assertion) and DraftEntity → Capability — homepoc deliberately never implemented these; they are the "real diagnostic generation" rebuild (plan §6).
- AssertionService + Assertion substrate — port before the mode services (all three persist through it).
- DecisionLogService + HashChainVerification (engine.decision) — append-only SHA-256 chain, canonical JSON (TreeMap-sorted keys, volatile/hash fields excluded), GENESIS root. **Preserve the canonical-JSON field set EXACTLY** or chains can never verify; add a unit test pinning a known hash vector; **add a per-domain advisory lock (or unique(previous_hash) constraint)** — homepoc appendDecision has no locking, concurrent appends can fork the chain. Chain inputs reference immutable internal `name`/id, never displayName.
- PersonaPromptLoader, DnaService/DnaConfig, SurfaceContext/AiMode/Surface, PersonaContext — port.
- SafetyHandler — **unwired in homepoc (zero callers; refusalDetected hardcoded false)** — wire: route complete() results through it, set ChatInteraction.refusalDetected truthfully.
- CostTracker — **recordUsage never called; /llm/cost-summary always zeros** — wire recordUsage at every LlmClient call site (orchestrator, ChatService stream completion, AssistiveModeService.singleShot, ImpactAnalysisService) or via an LlmClient decorator; fix streaming token accounting (SdkAnthropicClient emits chunks with null usage → surface usage from message_delta events); persist usage (synthesis_runs/chat_interactions already carry token columns) instead of restart-lost atomics.
- RelationalEvidenceRetriever — unwired but well-shaped (8 EvidenceQueryType branches); decision: **wire it as the evidence-assembly layer** for mode-service prompt construction (replacing AssistiveModeService's direct evidenceRepository call) — it is the natural seam for a future vector/document retriever.
- PromptRegressionHarness — real code, zero callers; **expose through a runnable surface** (Spring Boot CLI runner or Gradle task) per the acceptance-must-be-user-runnable rule; expand eval/ beyond the single placeholder case, include tool-calling regression cases against MockAnthropicClient.
- MockAnthropicClient — port (`substrate.llm.provider=mock` for tests).
- ChatService — sync chat + SSE streaming; persists ChatInteraction.

**Known dead-code mirage (consumers must not assume these work in homepoc):** SafetyHandler, CostTracker.recordUsage, RelationalEvidenceRetriever, PromptRegressionHarness, designer-impact.md, infer-compensating-controls.md, infer-shadow-workflows.md.

### 2.4 converter → ingestion

Target: `substrate/ingestion`, package `com.meridian.substrate.ingestion.*`. Build file exists (opencsv 5.9, anthropic SDK, postgres runtime). **Treat pipeline correctness as unverified** — the convert→commit chain likely never ran end-to-end in homepoc (prompt output emits name strings; link entities need UUIDs; app demo data came from app/seed, not this pipeline). Zero tests exist.

**Inventory (port + fix):**
- CLI: ConverterCommand → `IngestionCommand` — commands convert/stage/commit/convert-and-stage, --input/--output flags. **Fix: non-zero exit codes on failure; HARD FAIL on zero-yield with structured event line + distinct exit code** (no-silent-zero-yield norm; homepoc always exits 0).
- Readers (port verbatim — solid): FileReader interface (supportedExtension() + read(Path)→RawContent), CsvFileReader, JsonFileReader, TextFileReader (.txt + .md). **Fix routing:** ManualProcessCsvReader (virtual ext `manual-process-csv`) and WorkflowTextReader (virtual ext `workflow`) are unreachable dead code — replace the virtual-extension hack with explicit reader selection by document tag/MIME, and consume the structured `proposals` metadata (ManualProcessProposal + SystemLinkProposal/RoleProposal) instead of discarding it.
- DocumentSource seam (port unchanged — designed extension point): DocumentSource, DocumentRef, RawDocument, SourceQuery, LocalFileDocumentSource (sourceType `local-file`; `tachyon-ingested` reserved for RAG).
- Orchestration: ConversionPipeline (PROMPT_MAPPINGS first-match-wins table — **externalize to configuration**; remove hardcoded MAX_TOKENS=4096; add chunking for context overflow; unify model config — homepoc has `converter.model=claude-sonnet-4-20250514` vs `meridian.llm.anthropic.model=claude-opus-4-6` split-brain), PromptLoader (cached {{var}} substitution), ResponseParser (**replace regex fence-stripping with schema-enforced structured output via tool use/response_format**).
- Prompts (6, copy): convert-csv-systems.md, convert-csv-manual-process.md, convert-csv-entities.md, convert-text-workflow-description.md, infer-compensating-controls.md (unreferenced), infer-shadow-workflows.md (unreferenced — keep as raw material for the diagnostic engine, mark unwired).
- CommitService → **rebuild as idempotent re-ingest**: (a) new `IngestRun` audit entity (runId, startedAt/finishedAt, sourceDir, status, per-entity-type counts, error list) stamped onto DocumentRegistry.processedByConverterRunId (specced field, never set in homepoc); (b) DocumentRegistry sha256 lookup → skip/supersede already-ingested files; (c) per-entity-type natural-key upsert (e.g., domainId+name) instead of saveAll-with-fresh-UUIDs; (d) register DocumentRegistry rows for ORIGINAL source files at convert time, not just staged JSON at commit time. **Build the missing name→UUID link-resolution stage** (systemName/processName/memberName → FK UUIDs) before persisting ProcessSystemLink/ProcessRole/Finding. **Fix resolveEntityType substring ordering bug** ('system' matches process-system-link.json before the link check, misrouting links to the system repo).
- Repositories: DELETE the duplicate Converter*Repository set (11 interfaces) — ingestion uses `common.repo`.
- StagingService — port (human-review gate).
- Datasource: single shared Postgres with Flyway owned by app module migrations (eliminates homepoc's split-brain: converter wrote ./data/meridian while app read ./data/meridian-homepoc). Ingestion runs `flyway.enabled=false` + `ddl-auto=validate` against the same DB, sequenced after app has migrated (or shares the migration classpath — decide in chunk I1).
- Service-mode entry: add REST upload + async ingest job in `app` delegating to ingestion services (Phase 4; CLI is the Phase 1 surface).
- No sample corpus exists anywhere in homepoc — author one (CSV/JSON/text inputs) as part of the test chunk; acceptance requires a Tester-runnable CLI invocation against real input.
- Tests (none exist — all net-new): per-reader unit tests, prompt-resolution table tests, ResponseParser malformed-JSON tests, commit idempotency integration test (run twice over same staging dir → identical row counts).

### 2.5 frontend SPA

Target: `substrate/frontend`. Vite outDir → `../app/src/main/resources/static`; dev proxy `/api` → `http://localhost:8080`. **Wire a Gradle task dependency so `:app:processResources` runs after frontend build** (homepoc's documented staleness trap: Vite writes src/main/resources/static but a running Boot serves build/resources/main/static).

**Port (in this order):**
1. Tooling skeleton verbatim: package.json (same script names/pins: vite ^5.4, vitest 2.1.9, jsdom 25, @testing-library/react 16.3.2, @playwright/test 1.59.1 [library-only], tsx 4.21, TS ^5.5.4, eslint ^8.57 + @typescript-eslint ^7.18, tailwind ^3.4.7), vite.config.ts (repoint outDir), vitest.config.ts, tsconfig.json + tsconfig.node.json, .eslintrc.cjs, postcss.config.js, tailwind.config.ts, components.json, index.html (retitle; consider self-hosting fonts — Google Fonts CDN + hardcoded `class="dark"` flash are inherited otherwise), src/test/setup.ts (matchMedia polyfill, NoopResizeObserver, CSS-var/dark-class/localStorage cleanup — react-arborist and ThemeProvider fail under jsdom without them).
2. Design-token foundation: styles/tokens.css (canonical token source: HSL triplets, light + .dark, 9 industry hues, 7 lifecycle-phase hues), styles/index.css, tailwind.config.ts var-backed colors, components/ui/* (shadcn primitives: button, dialog, input, scroll-area, separator, tooltip), lib/utils.ts cn (**consolidate duplicate shared/utils/cn.ts**), shared/components (CanvasLayout, Badge, Card, DataTable, ErrorBanner, LoadingSpinner, PageHeader, SeverityBar; drop legacy Layout/Navbar).
3. Theming/branding: types/branding.ts + types/capability.ts (update sync comments to point at com.meridian.substrate classes + substrate Flyway numbering), app/theme/* (ThemeProvider, applyBrandingToDocument, useTheme, useTerminology, defaultTerminology, demoBranding DEV fallback, ThemeProvider tests). **Replace the first-domain assumption** (getDomains → data[0]) with real workspace/engagement domain resolution; define a production fallback policy — ThemeProvider currently hard-blocks the app if the branding fetch fails outside DEV, so the branding endpoint must ship in the first backend increment.
4. API layer: api/client.ts (axios, baseURL /api/v1, persona header interceptor — placeholder until OIDC), all 18 endpoint modules (ai, branding, capabilities, chat [+EventSource streamChat], cost, decisions, dna, domains, ecosystem, evidence, findings, intelligence, organization, processes, sources, systems, transformation), shared/types/api.ts, shared/hooks/useApi.ts. **The endpoints barrel omits dna/evidence/intelligence/sources — audit by grepping imports, not the barrel.** EventSource/raw-fetch paths (streamChat, AmbientChatBar, ChatPanel) bypass the axios interceptor — route through an authenticated path when auth lands.
5. State: Redux store with personaSlice (keep); **drop chatSlice + boardSlice with their orphaned features**; keep the zustand capability-designer store (UI-only state; server state stays in React Query).
6. CapabilityDesigner wholesale (features/capability-designer + 2 test files + capabilities.ts/ai.ts endpoints) — cleanest backend-connected surface, the template for new substrate surfaces.
7. LivingCanvas + features/canvas (nodes, LensRail, WaveScrubber, SummaryRibbon, Legend, WelcomeOverlay, CompletenessBadge, DnaPill, AddSourceModal, AmbientChatBar, drawer/ with RisksTab/RoadmapTab/DecisionsTab/ThesisTab/AssistantTab/ProvenanceTab). **Replace hardcoded DOMAIN_ID `3bf350fc-e026-495c-a368-01865dce54ca`** (also in AmbientChatBar; SolverModal is excluded) with workspace context — against Postgres it silently returns empty graphs. Replace retirementMonth()/processRetirementMonth() heuristics and the `$1.1M` literal with plan-API data. Migrate hardcoded slate-950/meridian legacy styling onto the token system.
8. FutureStateDesigner (features/designer: DecisionForm, ImpactAnalysisPanel) against design-decisions/impact-analysis/decision-log endpoints.
9. The 4 Beacon dashboards (the product's strongest UX — port per plan Phase 1 "with mock data", then wire to engines in Phases 2–4): DemoLandingPage persona picker, CorporateOperatingPartnerView (5 zones + cascade grid), SubsidiaryCooView + 4 tabs (CapabilitiesTab, EnvironmentTab, PlanTab, OperationsTab), CascadeDetailView, ContractDetailView. Port the components under a product (non-/demo) route namespace; keep `src/demo/beacon` fixtures TEMPORARILY as the Phase 1 mock-data source behind the documented single swap point (`BEACON_PORTFOLIO` factory in demo/beacon/index.ts); Phases 2–4 replace fixture imports with React Query hooks + adapters from substrate DTOs to the Beacon* view types (or re-type the views). DEMO_CURRENT_DATE → injectable clock (server time in production, fixed clock retained for demo determinism).
10. **EXCLUDE (do not port):** features/board, features/ecosystem, features/diagnostic, features/transformation, features/decisionLog, features/home, features/chat panel components, features/_prototype + elk-proto-run.mjs, shared Layout/Navbar, scripts/capture-walkthrough.ts, demo-screenshots/walkthrough machinery, the checked-in static bundle in app resources. All Beacon fixture narrative data is fictional (NOTES.md constraints) — excise at route+directory level when the live swap happens, never ship to a customer engagement build.
11. Screenshot harness: port scripts/capture-demo-screenshots.ts + scripts/README.md with substrate's route list; keep two-viewport timestamped-bundle structure.
12. Quality gate from day one: `npm run type-check && npm run lint && npm run test && npm run build` (lint enforces --max-warnings 0). Tests that exist: ThemeProvider, CapabilityDesigner read, CapabilityDesigner mutations — everything else has no regression net. No playwright.config or e2e suite exists in homepoc (Playwright is library-only); a real e2e project is post-port work (plan §8 names Playwright golden-path coverage).

### 2.6 frontend build/quality tooling — folded into 2.5 steps 1, 11, 12

Notable facts agents must not re-litigate: there is NO Playwright e2e suite to port; vitest config is standalone (not merged with vite.config); eslint is 8.x legacy-format (flat-config migration is deliberate post-port work); scripts import `.ts` extensions (tsx-runner-specific); mixed state libs (redux + zustand + react-query) are all genuinely used by kept surfaces except chat/board slices.

### 2.7 Domain semantics (docs) — governing constraints

Not code; constraints. Source docs (read before designing engine APIs): `docs/redesign/01-thesis-and-principles.md` (P1–P19), `02-customer-lifecycle.md`, `04-capability-designer.md`, `05-environment-designer.md`, `06-plan-designer.md`, `07-ai-assistance-patterns.md`, `08-nomenclature-and-versioning.md`, `10-demo-mode.md`, `11-architecture-deltas.md`, `post-phase-2-backlog.md`; `phases/specs/06-governance-{1,2,3}.md`; `phases/checkpoints/06-governance-*.md`, `06-block-5.md`; `phases/checkpoint-queue.md` (#21 resolved, #24 open); `docs/decisions/2026-04-26-frontend-first-backend-conforms.md`; `docs/positioning/why-now.md` (ratified, altitude-3 locked — carry copy unchanged). The full rule set is §6.

---

## 3. Package rename plan

| From | To |
|---|---|
| `com.meridian.homepoc.common.domain` | `com.meridian.substrate.common.domain` |
| `com.meridian.homepoc.common.ai(.config)` | `com.meridian.substrate.common.ai(.config)` |
| *(new)* repositories from `com.meridian.homepoc.app.*` | `com.meridian.substrate.common.repo` |
| `com.meridian.homepoc.app.api.v1.*` | `com.meridian.substrate.app.api.v1.*` |
| `com.meridian.homepoc.app.core.*` | `com.meridian.substrate.app.core.*` (exception: DecisionLog → engine) |
| `com.meridian.homepoc.app.infrastructure.*` | `com.meridian.substrate.app.infrastructure.*` |
| `com.meridian.homepoc.app.config` | `com.meridian.substrate.app.config` |
| `com.meridian.homepoc.app.seed` | `com.meridian.substrate.app.demo` (DemoPack facility) |
| `com.meridian.homepoc.app.branding` | `com.meridian.substrate.app.branding` |
| `com.meridian.homepoc.app.ai.{orchestration,tools,generative,assistive,validative,assertion,prompt,safety,cost,eval,retrieval,client,persona}` | `com.meridian.substrate.engine.ai.{…same}` |
| `com.meridian.homepoc.app.decision.{log,impact}` | `com.meridian.substrate.engine.decision.{log,impact}` |
| `com.meridian.homepoc.app.dna` | `com.meridian.substrate.engine.dna` |
| `com.meridian.homepoc.converter.*` | `com.meridian.substrate.ingestion.*` (cli, orchestration, readers, source, staging, commit) |
| *(net-new)* | `com.meridian.substrate.engine.cascade`, `engine.contract`, `engine.agent`, `engine.diagnostic` |

Conventions:
- **Config prefix:** every `meridian.*` property → `substrate.*` (`substrate.llm.*`, `substrate.demo.seed`, `substrate.dna.*`, `substrate.blob-store.*`, `substrate.cors.*`). The scaffold already establishes `substrate.engagement.id`, `substrate.orchestrator.*`, `substrate.auth.mode`, `substrate.ai.anthropic-api-key` — reconcile `substrate.ai.anthropic-api-key` with `substrate.llm.anthropic.api-key` (keep the latter; update application.yml).
- **Entry points:** `MeridianApplication` → `SubstrateApplication` (exists); `ConverterApplication` → `IngestionApplication` (exists). Annotations: `@EntityScan("com.meridian.substrate.common.domain")`, `@EnableJpaRepositories("com.meridian.substrate")`, `@EnableJpaAuditing` on both boot apps.
- **Table/column names do NOT change** — the rename is Java-package + config-prefix only; the wire contract (/api/v1 paths, entity JSON shapes) and DB identifiers stay homepoc-final (post-V8).
- Frontend TS sync comments in `types/branding.ts` / `types/capability.ts` re-point at substrate classes/migrations.
- Bean-name / @ConditionalOnProperty values referencing `meridian.llm.provider` update to `substrate.llm.provider` (anthropic default matchIfMissing, mock for tests).

---

## 4. Postgres strategy

Constraints: Postgres in production; **this dev machine has NO Docker**; docker-compose still ships for Docker-capable environments (already present: substrate-app + postgres:16-alpine + Keycloak 26).

**4.1 Profiles (scaffolded — keep):**
- `dev` (default): H2 in-memory `MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH` — zero external dependencies, runs on this machine. Chosen over embedded-Postgres (zonky et al.) for zero-native-binary friction on Windows; H2-PG-mode limits are mitigated by 4.4.
- `postgres`: real Postgres via `SUBSTRATE_DB_*` env (compose, Meridian-hosted testing, customer env).
- Tests: unit/H2 fast lane + a Testcontainers-Postgres integration lane that runs wherever Docker exists (CI per plan Phase 0; NOT runnable on this dev machine — CI is the gate).

**4.2 Migration layout (vendor-split, scaffolded):** `spring.flyway.locations: classpath:db/migration/common, classpath:db/migration/{vendor}`.
- `db/migration/common/` — ANSI-portable SQL, runs on both H2-PG-mode and Postgres. V1__engagement_baseline.sql exists.
- `db/migration/postgresql/` — Postgres-only DDL (partial indexes, anything H2 rejects). Create dir in chunk B2.
- `db/migration/h2/` — H2-only shims if ever needed (avoid; prefer portable SQL in common).
- Version allocation: integer versions for common; `Vn_m` (dot versions) reserved for vendor dirs so vendor migrations slot between common ones without collisions.

**4.3 The canonical-model migration — squash, don't replay.** Author `db/migration/common/V2__canonical_model.sql` collapsing homepoc V1–V11 into the POST-V8 final shape:
- Tables created directly with final names: `processes`, `process_roles`, `process_system_links` (never manual_*), columns `process_id`, `affected_process_ids`, indexes `idx_processes_domain_id` etc. — names must match the ported entities EXACTLY or `ddl-auto=validate` fails at boot. Validation gate: boot the app on both profiles after C1+B2 land.
- Include from the start: `domains.is_demo`, `capabilities.sequence_order`, `assertions`, `branding_configs`, `evidence`, `capabilities` family, `phase_states` (unique(domain_id,phase)), `reader_registrations`, plus all 16 V1 tables.
- Types: UUID, TIMESTAMP WITH TIME ZONE, TEXT, DOUBLE PRECISION, FLOAT — all valid on both H2-PG-mode and Postgres.
- `V2_1__active_branding_partial_index.sql` in `db/migration/postgresql/`: `CREATE UNIQUE INDEX uq_branding_configs_active_per_domain ON branding_configs(domain_id) WHERE status = 'ACTIVE';` — homepoc V10 deliberately omitted this because H2 rejects partial indexes; on H2 dev the invariant stays service-enforced (acceptable: dev only).
- Engine-net-new tables land as V3+ (cascades/cascade_levels, contracts/contract_parties, agent_registrations, ingest_runs) in their own chunks.

**4.4 H2-is-not-Postgres discipline:**
- `flyway-database-postgresql` is already in app/build.gradle (required by Flyway 10.x under Boot 3.4 — homepoc lacked it and would fail at startup on PG).
- `ddl-auto=validate` everywhere; no `update` override in any profile.
- Every migration + JPQL/native query is verified by the Testcontainers-Postgres suite in CI — H2-green runs are never the acceptance gate (case sensitivity, TEXT/CLOB, TIMESTAMPTZ, implicit-cast leniency all differ).
- FLOAT confidence columns and `UPDATE…FROM ROW_NUMBER()` style SQL from homepoc V11 are not replayed (squash absorbs them), but any future backfill SQL must be written for PG and tested there.

**4.5 JSON columns — decision: keep `JsonNodeConverter` over TEXT for Phase 1.** Rationale: (a) works identically on H2-PG-mode and Postgres, honoring the no-Docker dev constraint; (b) homepoc designed the converter to be Postgres-portable deliberately; (c) per-engagement deploys are FRESH databases (plan §8 data isolation), so a later TEXT→JSONB flip is a migration-file addition with no legacy-data risk; (d) mapping `AttributeConverter<JsonNode,String>` onto native jsonb columns requires `stringtype=unspecified` or `@JdbcTypeCode(SqlTypes.JSON)` rework — fragility we don't need before a real jsonb query exists. **Deferred flip condition:** the first time an engine needs jsonb indexing/querying (likely contract `monitorMechanism` rules or assertion search), add a `db/migration/postgresql` migration converting the ~20 JSON columns (`ALTER … TYPE jsonb USING col::jsonb`) + GIN indexes, and switch the entity mapping in one pass. Until then, Postgres can still index expressions via `(col::jsonb)` functional indexes in the postgresql vendor dir if needed. Logged as open question for ratification before first customer engagement.

**4.6 Schema ownership:** the `app` module owns all migrations. `ingestion` runs `flyway.enabled=false`, `ddl-auto=validate` against the same datasource (no homepoc split-brain: one DB, one schema owner). docker-compose remains the Docker-capable path; dev on this machine uses `gradlew :app:bootRun` (dev profile).

---

## 5. Fixture seams to sever

| # | Seam (homepoc) | Replacement in substrate |
|---|---|---|
| S1 | SeedOrchestrator + 11 Seeders + `resources/seed/*.json` (all domains/systems/processes/members/links/overlaps/findings/decisions; gated `meridian.demo.seed=true` in dev/demo) | Keep the topological Seeder framework as a **DemoPack** facility (`substrate.demo.seed`, default false, isDemo Domain only — redesign doc 10). Production population flows through the ingestion module + engines. Never the production data path. |
| S2 | `seed/findings.json` (21 hand-written findings, aiGenerated=false via DiagnosticSeeder) — every Finding visible in the product is fixture | engine.diagnostic commit step: ValidativeModeService FindingCandidate → persisted Finding(aiGenerated=true) linked to its Assertion, generated against ingested data (plan Phase 4). |
| S3 | EvidenceSeeder synthetic Evidence (fake DocumentRegistry rows, hash 'seed-systems-sha256', extractor='seed') — all grounding enforcement validates against this pool | Real Evidence written by ingestion (DocumentRegistry at convert time, Evidence per committed entity, IngestRun provenance). Note: grounding checks only evidence EXISTENCE, not semantic support — flag as product-correctness gap once real data flows. |
| S4 | GET /transformation/plan serves `transformation-plan-mockup.json` verbatim | Real TransformationPlan/TransformationInitiative entities + planning engine (redesign doc 06 schema); fixture behind a feature flag until then. |
| S5 | Beacon demo model (subsidiaries/cascades/contracts/portfolio/agents) — frontend TypeScript fixtures ONLY, zero Java entities | Net-new canonical entities + engines (engine.cascade, engine.contract, engine.agent) + portfolio/subsidiary read APIs. Port the SEMANTICS (§6), not the fixture shape (2026-04-26 decision): string ownerLabels → identity FKs; optional provenance → required at deploy; slug links → real references. Frontend swap point: `BEACON_PORTFOLIO` factory in demo/beacon/index.ts. |
| S6 | Fixture-stamped statistics (lifecycleStallCount=3, coordinationPatternsThisWeek=6, pendingFederationInvitations, progressPct, surfacedAt) | All become **engine-computed**: stall count from PhaseState dwell-time, patterns from contract/cascade activity, invitations from contract registry, progress from initiative tracking, surfacedAt from real timestamps. |
| S7 | H2 file DB MODE=PostgreSQL (jdbc:h2:file:…) | §4: Postgres in production, H2-PG-mode in-memory dev profile, vendor-split Flyway. |
| S8 | MockAnthropicClient (`meridian.llm.provider=mock`) | Keep as the test seam (`substrate.llm.provider=mock`); real path SdkAnthropicClient + ANTHROPIC_API_KEY. |
| S9 | X-Persona-Level/X-Persona-Id spoofable headers as authn/authz (PersonaInterceptor, BrandingAdminAuthorizer) | Phase 1: anonymous-allowed mode (`substrate.auth.mode=anonymous`, scaffolded). Phase 5: generic OIDC (Keycloak dev realm scaffolded); OIDC principal becomes the identity source; admin gates consult real roles; hash-chain actor identity becomes IAM-compatible stable user id. |
| S10 | DnaConfig defaults hardcoding 'Meridian Bancorp · Risk Consolidation' | Per-engagement DNA from engagement config/data (`substrate.dna.*` + engagement row), no baked demo identity. |
| S11 | BlobStore → UnconfiguredBlobStore default (throws), MinIO localhost option | Production object-store decision (S3 in customer AWS per plan §5; MinIO acceptable for compose). Open decision — keep abstraction, wire per-env config. |
| S12 | CostTracker in-memory atomics (reset on restart, never recorded) | Wire recordUsage + persist to synthesis_runs/chat_interactions token columns. |
| S13 | DocumentRegistry 'tachyon' columns referencing a nonexistent external index | Keep columns Phase 1; resolve (real document/search backend or rename) when Phase 4 ingestion lands. |
| S14 | Hardcoded DOMAIN_ID UUID + '$1.1M' + retirement heuristics in LivingCanvas/AmbientChatBar | Workspace/engagement-derived domain context; plan-API-driven stats. |
| S15 | DEMO_CURRENT_DATE='2026-04-26' deterministic clock | Server time in production; injectable clock retained for demo mode (temperature=0 + isDemo Domain pairing, redesign doc 10). |
| S16 | converter ./data/meridian vs app ./data/meridian-homepoc DB split-brain; flyway-disabled converter | Single shared Postgres, app owns schema (§4.6). |
| S17 | eval/sample-test-cases.json single placeholder case | Expanded eval suite + runnable PromptRegressionHarness surface (CLI/Gradle task), tool-calling-aware cases. |
| S18 | Checked-in stale frontend bundle in app resources/static | Built by substrate/frontend pipeline with Gradle task wiring; never committed. |
| S19 | AppProperties ./data ./uploads ./staging local dirs | Production storage layout decision with deploy tooling (Phase 5); keep configurable dirs meanwhile. |

---

## 6. Domain-semantic rules constraining the net-new engines

Copied faithfully from the domain-semantics extraction (sources: `phases/specs/06-governance-{1,2,3}.md`, `docs/redesign/*`, `phases/checkpoint-queue.md`, `phases/checkpoints/06-block-5.md`). These are binding on `engine.cascade`, `engine.contract`, `engine.agent`, `engine.decision`, `engine.diagnostic`.

**[CASCADE-LIFECYCLE]** Cascade aggregate states exactly as the fixtures imply: `currentState ∈ {in-flight, resolved-looks-good, resolved-declined}`. A cascade is in-flight while any level is still acting; it resolves either when the originator accepts the outcome (looks-good) or when a Decline propagates to L0 and is accepted (resolved-declined). A documented Decline is a LEGITIMATE terminal resolution, not a failure state — the engine must not model declined as error.

**[CASCADE-LEVELS]** Per-level states: `looking` (level has received the cascade, not yet acted), `absorbed` (level takes the work itself — terminates downward propagation at that level), `cascaded` (level translated/refined the critique and passed it to level+1), `declined` (level pushes back UP with mandatory declineReasoning). State-conditional payloads: refinedCritique required for cascaded/absorbed; declineReasoning required for declined; every level action is timestamped with an owner. ⚠ `looking` and `absorbed` are never exercised by any fixture — their semantics are inferred from naming and one spec sentence; validate before encoding engine invariants.

**[CASCADE-DURABILITY]** The L0 originalCritique is durable through the entire chain — every level sees it unmodified alongside each level's refinedCritique translation. acceptanceCriteria is authored at origination and explicitly enumerates acceptable outcomes including "a Decline with documented reasoning" (see c-vendor-cost fixture). The level chain IS the audit trail — "not retrofitted, authored as the work happens" — so level records must be append-only.

**[CASCADE-PROPAGATION]** Arbitrary depth (fixtures exercise 2 and 3 levels; design for N); exactly one active bottom-of-chain level at a time (linear chain in v1, not a tree); dashboard state derives from the bottom level (declined → "Decline pending" leader-attention signal using destructive semantics per resolved queue #21; cascaded → "Escalating" info semantics); decline-pending cascades surface on the originator's dashboard NEXT TO in-intervention agents — both categories demand human attention; cascades may optionally bind to an affected entity (affectedSubsidiarySlug → real reference in substrate).

**[CASCADE-VISIBILITY]** Single-throat-to-choke is the core product claim: the L0 originator can inspect every level's decision, translation, and timing without traversing the chain of command for permission. The read API must expose the full level timeline to the originator; "drill to level N" interaction semantics are intentionally undefined (queue #23) — do not invent them.

**[CASCADE-AUTHORING-GAP]** The cascade WRITE path is explicitly undesigned (queue #24: AI-suggested vs free-form vs terse-imperative critique authoring; entry points; issuance preview/routing). Build the engine read model and state machine first; gate authoring API design on a product-design session with Mike — do not infer authoring semantics from fixtures. (Plan open decision #2 adds: does Decline propagate to parallel cascades or just the issuing one? auto-escalation timeout?)

**[CONTRACT-CONTENT]** A coordination contract contains: type (cross-portfolio-opportunity | federation-invitation — extensible), title, context framing, 2..N parties each with partyLabel + explicit commitments[] (a party may be a combined obligation across multiple subsidiaries), enforcement block, optional estimatedValue, linked entity references, proposedAt. Commitments are concrete, dated, measurable obligations ("document three highest-risk single-owner processes by Q3").

**[CONTRACT-ENFORCEMENT]** Enforcement means three things (P19): (1) monitor — plain-language description of how compliance is checked (cadence + validator); (2) monitorMechanism — machine-executable rule bound to the contract id (e.g. "auto-flag subsidiary missing-monthly-report after 7 grace days; surface to Operating Partner dashboard", "auto-flag utilization imbalance > 30%") — in substrate this becomes a real scheduled monitor producing dashboard signals; (3) MCP as the adherence protocol — participating systems/agents join the substrate by exposing an MCP surface implementing their contract role; adherence is observable, non-adherence is a signal, and "agents cannot route around the contract — the contract is the substrate". (Enforcement model — runtime check vs static analyzer vs wrapper service vs hybrid — is plan open decision #3, surfaces at Phase 3 start.)

**[CONTRACT-COMMITMENT-STATES]** States implied by fixtures and surfaces: proposed (proposedAt, Review/Decline affordances rendered first-class), accepted/active (monitors running), declined. declineTriggers[] are pre-agreed legitimate refusal grounds (regulatory gating, incompatible framework change, sustained cost-share imbalance, accuracy below threshold) — invoking one is valid contract behavior, rendered with subtle destructive accent, NOT a violation; federation contracts permit Decline at quarterly review boundaries. Decline on a contract connects to the cascade Decline primitive (a triggered decline propagates upward as a first-class response). Review/Decline product flows are explicitly unbuilt (queue #28 inert pattern) — design before implementing.

**[AGENT-PROVENANCE]** Every deployed agent carries durable identity + provenance: deployedBy (human/team), deployedAt, deploymentCriteria (the accountable mandate: what it was deployed to do plus a measurable target — "90% pre-fill accuracy", "escalate denials within 4 hours", "shadow-mode review 12 weeks before promotion"), oversightChain (ordered escalation chain of accountable roles, rendered as a sequence with →, 2-3 deep in fixtures). In substrate these are REQUIRED at deploy time (fixtures made them optional only for typecheck pragmatics). Render rule: provenance metadata is ambient row-level context; deploymentCriteria appears only in the click-through detail surface. deploymentCriteria becomes a runtime gate (plan §5).

**[AGENT-STATES]** Operational states from fixtures: live/operating, shadow (trial mode; promotion to production gated on compliance/regulatory review — e.g. HIPAA validation), paused (attention-required, warning semantics — e.g. paused pending Compliance review of a PHI-routing change), in-intervention (human intervention demanded; surfaced to the leader alongside decline-pending cascades). Status is mechanical and actionable on the same surface admins use to act (P12: every agent visible, inspectable, throttleable, pausable, killable through the Agent Operations Center; no agent operates outside this control surface; "install and forget" is not a deployment model).

**[AGENT-SPEC-UNIFICATION]** Unify the demo provenance fields with redesign doc 06's AgentDeploymentSpec when designing the agent registry: targetProcessId + targetStepIds, agentReadinessScore 0-100 from the deterministic readiness lens, requiredSystemAccess/requiredDataAccess, supervisoryModel ∈ {FULL_AUTO, HUMAN_REVIEW_ABOVE_THRESHOLD, HUMAN_IN_LOOP}, deployedAt. deploymentCriteria (free text in demo) should become the human-readable projection of this structured spec. Apply P11 safety defaults (read-only, rate-budgeted, off-peak, backoff, sampling, observation-only trial) and "filter at interpretation" (agents emit capability-level assertions, not raw events).

**[DECISIONLOG-CHAIN]** Each DesignDecision is serialized as canonical JSON and SHA-256 hashed with linkage to the previous entry; chain root is the literal GENESIS sentinel (verified POC chain: GENESIS → D-001 → … → D-005); HashChainVerification re-walks and validates the chain and is exposed in UI. Port the POC DecisionLogService as reference — exact canonical-JSON field order and hash-input layout live in code; confirm against app source before reimplementing, or chains will be incompatible. Add the concurrency fix (per-domain advisory lock or unique(previous_hash)).

**[DECISIONLOG-COVERAGE]** What gets chained: every Plan-phase decision, Execute-phase design decisions, rename operations (DesignDecision type RENAME, status APPLIED), bulk renames (one DesignDecision covering the batch; individual renames share a batchId and rationale). Chain inputs must reference entities by immutable internal `name`/id, never displayName, so customer renames cannot invalidate historical chain entries (doc 08). Actor identity on chain entries must be a stable user identifier compatible with future IAM binding.

**[LIFECYCLE-MACHINE]** PhaseState semantics: 7 phases (PLUG_IN continuous/never exits + DESIGN→MAP→PLAN→EXECUTE→MONITOR→AUTOMATE sequenced), status ∈ {NOT_STARTED, IN_PROGRESS, BLOCKED, COMPLETE}, structured blockers JSON, per-phase checkpoints JSON with pass/fail. Entry/exit criteria per doc 02 (e.g. Design exits only when capability tree ACTIVE with no root/L1 DRAFT nodes and a non-author review; Plan exits on APPROVED plan with waves/owners/milestones). Monitor/Automate entry criteria are UNKNOWN — flag, don't invent.

**[EVIDENCE-GROUNDING]** Architectural invariant: AI output → Assertion → Evidence record → DocumentRegistry → Reader source (P6). All generative output lands as DRAFT entities requiring per-item human acceptance; ungrounded AI output is filtered, never presented. Analytical lenses (rationalization, readiness) are deterministic computations; the LLM narrates results but never generates them (P7) — this is the audit-defensibility contract for regulated buyers.

**Engine sequencing rule (from the extraction):** user-runnable read surfaces first (cascade timeline, contract detail, agent fleet with provenance — the three demo beats), then deterministic lenses, then write paths. Schedule the cascade-authoring design session (queue #24) with Mike before speccing any cascade write API; same for contract Review/Decline flows.

**Fixture test-vector caveat:** 05-walkthrough.md describes c-tribal-succession as terminal Decline-pending, but the block-5 checkpoint records it as all-cascaded/escalating (c-vendor-cost holds the terminal decline). Verify actual fixture content (`frontend/src/demo/beacon/cascades.ts`) before reusing as engine test vectors.

---

## 7. Build order — discrete chunks with file-level scope

Rules: a chunk is dispatched to exactly one agent; chunks sharing a tag row may run concurrently only if both are [parallel-ok] and their scopes are disjoint (scopes below are disjoint by construction). Every chunk's acceptance includes a user-runnable verification (build/boot/test command) — no report-only completions. `gradlew build` must stay green at every chunk boundary.

**Wave A — foundations (Phase 1 start)**

- **A1 [sequential — blocks nearly everything]** Canonical model port. Scope: `common/src/main/java/com/meridian/substrate/common/domain/**` (BaseEntity, JsonNodeConverter, 26 enums, 24 entities per §2.1). No repo/migration work. Acceptance: `gradlew :common:build` green; entity column/table names diffed against homepoc finals.
- **A2 [parallel-ok with A1]** LLM client layer port. Scope: `common/src/main/java/com/meridian/substrate/common/ai/**` (LlmClient, DTO records extended for content blocks, LlmProperties prefix `substrate.llm`, SdkAnthropicClient, AnthropicClientConfig, LlmRateLimitException; drop AnthropicClient alias). Acceptance: `:common:build` + unit tests for DTO mapping.
- **A3 [parallel-ok with A1/A2]** Frontend scaffold + token foundation. Scope: `frontend/` configs (package.json, vite/vitest/tsconfigs, eslint, tailwind, postcss, components.json, index.html, src/test/setup.ts), `frontend/src/styles/**`, `frontend/src/components/ui/**`, `frontend/src/lib/**`, `frontend/src/shared/components/**`. Acceptance: `npm run type-check && npm run lint && npm run build` green; outDir lands in `app/src/main/resources/static`.

**Wave B — persistence + plumbing (after A1)**

- **B1 [sequential after A1]** Repository layer. Scope: `common/src/main/java/com/meridian/substrate/common/repo/**` (27 interfaces per §2.2). Acceptance: `:common:build`.
- **B2 [sequential after A1; parallel-ok with B1]** Flyway squash. Scope: `app/src/main/resources/db/migration/common/V2__canonical_model.sql`, `app/src/main/resources/db/migration/postgresql/V2_1__active_branding_partial_index.sql` (+ create vendor dirs). Acceptance: `gradlew :app:bootRun -Pprofile=dev` boots with ddl-auto=validate against A1 entities (requires B1+B3 minimal app wiring — coordinate: B2 verifies after B3 merges).
- **B3 [parallel-ok with B1/B2, after A1]** App cross-cutting web layer. Scope: `app/src/main/java/com/meridian/substrate/app/{infrastructure/**, core/exception/**, config/**, api/v1/ApiResponse|ApiMeta|ApiError + request records}`, `app/src/main/resources/logback-spring.xml`, application.yml additions (`substrate.llm.*` etc.). Includes envelope advice scoping fix, CORS PATCH + externalized origins, RequestIdFilter, MdcContextLifter, PersonaInterceptor/PersonaContext, JacksonConfig, SpaController, WebConfig. Acceptance: app boots dev profile; /api/v1/health + /api/v1/info return enveloped responses; /v3/api-docs not double-wrapped.

**Wave C — feature surface (after B1+B2+B3)**

- **C1 [parallel-ok]** Core CRUD services + controllers. Scope: `app/.../core/service/{Domain,System,Organization,Process,ProcessStep,Finding,Evidence,Ecosystem,PhaseState,ReaderRegistration,Probe,Reduction,Assistant,ManualProcessAnalyzer}*`, matching `app/.../api/v1/controller/**` (domains, systems, processes, steps, organization, ecosystem, evidence, phase-states, readers, sources, intelligence, health/info, transformation-behind-flag). Acceptance: ported phase1 CRUD tests green on H2; endpoint inventory §2.2 responds.
- **C2 [parallel-ok, disjoint from C1]** Capability + Branding verticals. Scope: `app/.../core/service/{Capability*,BrandingConfig*}`, `app/.../branding/**` (BrandingAdminAuthorizer, BlobStore, MinioBlobStore, UnconfiguredBlobStore, BlobStoreProperties), capability/branding controllers. Acceptance: ported phase2b1 tests green (locking, rename history, reorder clamp, single-ACTIVE invariant, 403 admin gate).
- **C3 [parallel-ok, after A2+B1]** Engine AI orchestration. Scope: `engine/src/main/java/com/meridian/substrate/engine/{ai/**, dna/**}`, `engine/src/main/resources/{prompts/**, eval/**}` — ToolRegistry/ToolDispatcher/10 tools, ToolCallingOrchestrator with native tool_use/tool_result protocol + enforced maxCallsPerRequest, mode services with verbatim grounding contracts, AssertionService, PersonaPromptLoader (filename fixes), SafetyHandler + CostTracker wiring, RelationalEvidenceRetriever wiring, MockAnthropicClient, PromptRegressionHarness + runnable surface. App-side chat/ai/dna/cost controllers go in C5. Acceptance: ported phase2a integration tests green against mock provider; harness runnable via documented command.
- **C4 [parallel-ok, after A1+B1]** Decision log engine. Scope: `engine/src/main/java/com/meridian/substrate/engine/decision/**` (DecisionLogService, HashChainVerification, ImpactAnalysisService) + pinned hash-vector unit test + per-domain append lock. Acceptance: chain verify test green incl. known-vector pin; concurrent-append test does not fork.
- **C5 [sequential after C3+C4]** AI-facing controllers. Scope: `app/.../api/v1/controller/{Chat,AssistiveMode,GenerativeMode,ValidativeMode,DecisionLog,Design,Dna,Cost}Controller` + SSE stream wiring. Acceptance: POST /chat round-trips against mock; /decision-log/verify green; /llm/cost-summary non-zero after a mock call.
- **C6 [parallel-ok, after B1+B2]** Ingestion port. Scope: `ingestion/src/main/java/com/meridian/substrate/ingestion/**`, `ingestion/src/main/resources/**` (prompts, application.yml), `app/src/main/resources/db/migration/common/V3__ingest_runs.sql` (IngestRun entity lands in `common.domain` — coordinate the one-file touch on common with B-owners, or land entity+migration through this chunk after B2 merges). Includes reader routing fix, structured-output parser, idempotent commit + name→UUID resolution, exit codes, sample corpus under `ingestion/samples/`, unit + idempotency tests. Acceptance: Tester-runnable CLI invocation over the sample corpus; re-run yields identical row counts; zero-yield hard-fails with distinct exit code.
- **C7 [parallel-ok, after A3]** Frontend theming + API layer + state. Scope: `frontend/src/{types/**, app/theme/**, api/**, shared/types/**, shared/hooks/**, app/store.ts, app/hooks.ts}`. Acceptance: ThemeProvider tests green; endpoint modules compile against §2.2 inventory.
- **C8 [sequential after C7]** Product surfaces. Scope: `frontend/src/features/{capability-designer/**, canvas/**, designer/**, personas/**}`, `frontend/src/App.tsx`, `frontend/src/main.tsx`. DOMAIN_ID/workspace-context fix, token-system migration for canvas styling. Acceptance: CapabilityDesigner tests green; canvas renders against seeded dev backend.
- **C9 [parallel-ok with C8, after C7]** Beacon dashboards (mock-data phase). Scope: `frontend/src/features/portfolio/**` (new namespace), ported components from homepoc `app/demo/beacon/**` + `features/demo-beacon/**`, fixtures retained at `frontend/src/fixtures/beacon/**` behind the single swap point. Acceptance: 4 dashboards render; screenshot harness captures them.
- **C10 [sequential after C1+C2]** DemoPack. Scope: `app/src/main/java/com/meridian/substrate/app/demo/**`, `app/src/main/resources/demopack/*.json`, `substrate.demo.seed` gate. Acceptance: dev boot with seed=true populates an isDemo domain; seed=false leaves DB empty.
- **C11 [sequential — Phase 1 close]** Integration gate. Scope: `app/src/test/**` Testcontainers-Postgres suite (entity round-trip, migration validate, behavioral contract tests), CI workflow file, frontend build→processResources Gradle wiring (`app/build.gradle` + `frontend` task). Acceptance: CI green on Postgres containers; compose stack validated in a Docker-capable environment. **Phase 1 exit: substrate runs the existing demo against Postgres in a container, identical to homepoc otherwise.**

**Wave D — engines (Phases 2–4; each gated on design input per §6)**

- **D1 [sequential; read-model first; authoring gated on Mike session (queue #24)]** Cascade engine. Scope: `common/src/main/java/.../domain/{Cascade,CascadeLevel}.java` + enums, `db/migration/common/V4__cascades.sql`, `engine/.../cascade/**`, `app/.../api/v1/controller/CascadeController` (read API: full level timeline), frontend CascadeDetailView swap from fixtures. Constraints: §6 CASCADE-* blocks verbatim; append-only levels; declined ≠ error.
- **D2 [sequential; enforcement model gated on Phase 3 strategic decision]** Contract engine. Scope: `common/.../domain/{Contract,ContractParty,…}`, `db/migration/common/V5__contracts.sql`, `engine/.../contract/**` (registry + scheduled monitors executing monitorMechanism + MCP adherence surface), `app/.../api/v1/controller/ContractController`, frontend ContractDetailView + PortfolioContextBand swap. Constraints: §6 CONTRACT-* blocks.
- **D3 [parallel-ok with D1/D2 for registry+read; gating enforcement sequential after D2 design]** Agent provenance registry. Scope: `common/.../domain/AgentRegistration` (+AgentDeploymentSpec unification per §6), `db/migration/common/V6__agent_registry.sql`, `engine/.../agent/**` (required-provenance deploy gate, status feed, Ops Center verbs pause/throttle/kill even if stubbed), `app/.../api/v1/controller/AgentController`, OperationsTab swap.
- **D4 [sequential after C3+C6]** Diagnostic commit step + real generation. Scope: `engine/.../diagnostic/**` (FindingCandidate→Finding commit, DraftEntity→Capability commit), ingestion→canonical→diagnostic E2E pipeline, eval-suite expansion. Acceptance: end-to-end run against ingested sample corpus produces persisted aiGenerated=true Findings with Assertion+Evidence chains.
- **D5 [sequential after D1–D4 incrementally]** Computed statistics + dashboard live-wiring (severs S6): portfolio/subsidiary read APIs, stall detection, pattern surfacing, invitation counts, progressPct, surfacedAt.

**Wave E — Phase 5 (out of port-map scope, listed for sequencing):** OIDC (Keycloak realm scaffolded), per-engagement deploy tooling, observability. Auth replaces persona-header trust (S9) across B3 surfaces.

---

## Appendix — known traps for implementation agents

1. ddl-auto=validate fails at boot if V2 squash drifts from entity finals (post-V8 names) — diff before commit.
2. Entities ARE the wire format — any entity field change silently changes the API the ported frontend depends on; Hibernate6Module + @JsonIgnore discipline is load-bearing.
3. Hash-chain canonical JSON must match homepoc byte-for-byte semantics (TreeMap order, excluded fields) — pin a vector test first.
4. H2-green is never acceptance — Testcontainers-Postgres in CI is the gate (this dev machine cannot run it; do not "verify" with Docker locally).
5. Persona prompt fallback is silent for 3 of 7 levels in homepoc — fix loudly, don't reproduce.
6. Dead-code mirage list (§2.3) — these features do NOT work in homepoc; wiring them is in-scope work, not regression.
7. Converter pipeline was never proven end-to-end; treat every commit-path behavior as unverified.
8. Decline is a legitimate resolution everywhere (cascade levels, contract triggers) — never map to failure/error states.
9. Beacon fixture data is fictional and narrative-laden — never ship it in a customer engagement build; excise at route+directory level.
10. ResponseEnvelopeAdvice wraps everything in homepoc — scope it, or springdoc/actuator break.
11. Vite-output staleness: bake frontend→processResources into Gradle, not docs.
12. The why-now positioning copy is altitude-3 ratified — carry into substrate-facing copy unchanged; edits require Mike.
