package com.meridian.substrate.app.api.v1.controller;

import com.meridian.substrate.app.api.v1.request.AgentAttestLivenessRequest;
import com.meridian.substrate.app.api.v1.request.AgentConfirmControlRequest;
import com.meridian.substrate.app.api.v1.request.AgentDeployRequest;
import com.meridian.substrate.app.api.v1.request.AgentInterventionOpenRequest;
import com.meridian.substrate.app.api.v1.request.AgentInterventionResolveRequest;
import com.meridian.substrate.app.api.v1.request.AgentOversightRequest;
import com.meridian.substrate.app.api.v1.request.AgentPrincipalRequest;
import com.meridian.substrate.app.api.v1.request.AgentReasonedVerbRequest;
import com.meridian.substrate.app.api.v1.request.AgentReviewDecideRequest;
import com.meridian.substrate.app.api.v1.request.AgentReviewRequest;
import com.meridian.substrate.app.api.v1.request.AgentThrottleRequest;
import com.meridian.substrate.app.api.v1.request.AgentVerbRequest;
import com.meridian.substrate.app.infrastructure.web.PersonaContext;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.enums.AgentExecutionLocus;
import com.meridian.substrate.common.domain.enums.AgentGateRuleType;
import com.meridian.substrate.common.domain.enums.AgentInterventionResolution;
import com.meridian.substrate.common.domain.enums.AgentInterventionTrigger;
import com.meridian.substrate.common.domain.enums.AgentReadinessSource;
import com.meridian.substrate.common.domain.enums.AgentReviewDecision;
import com.meridian.substrate.common.domain.enums.AgentReviewKind;
import com.meridian.substrate.common.domain.enums.AgentSupervisoryModel;
import com.meridian.substrate.common.domain.enums.AgentWireStatus;
import com.meridian.substrate.engine.agent.AgentActor;
import com.meridian.substrate.engine.agent.AgentControlConfirmationService;
import com.meridian.substrate.engine.agent.AgentControlEffects;
import com.meridian.substrate.engine.agent.AgentInterventionService;
import com.meridian.substrate.engine.agent.AgentLifecycleService;
import com.meridian.substrate.engine.agent.AgentNotificationService;
import com.meridian.substrate.engine.agent.AgentProjectionRebuilder;
import com.meridian.substrate.engine.agent.AgentPromotionService;
import com.meridian.substrate.engine.agent.AgentQueryService;
import com.meridian.substrate.engine.agent.read.AgentAttentionItem;
import com.meridian.substrate.engine.agent.read.AgentDetail;
import com.meridian.substrate.engine.agent.read.AgentEnforcement;
import com.meridian.substrate.engine.agent.read.AgentEventView;
import com.meridian.substrate.engine.agent.read.AgentFeedItem;
import com.meridian.substrate.engine.agent.read.AgentFleetRow;
import com.meridian.substrate.engine.agent.read.AgentInterventionView;
import com.meridian.substrate.engine.agent.read.AgentMetricSampleView;
import com.meridian.substrate.engine.agent.read.AgentNotificationView;
import com.meridian.substrate.engine.agent.read.AgentReviewView;
import com.meridian.substrate.engine.agent.read.AgentSightingView;
import com.meridian.substrate.engine.agent.read.AgentSignals;
import com.meridian.substrate.engine.agent.read.AgentVerbResponse;
import com.meridian.substrate.engine.contract.gateway.AgentRegistryGate;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Agent registry endpoints (engine-spec agents.md §7; ADR-003 D17): the §5.4
 * deploy gate, the §7.1 read surface (fleet / {idOrKey} detail / events /
 * sightings / gates / metrics / signals / attention / feed / notifications)
 * and the full §7.2 verb set (6b).
 *
 * <p><b>Honest verbs (§6.2, binding):</b> every verb answers
 * {@code {status, enforcement{effect, confirmationState, disclosure}, agent}}
 * — pause / kill / retire return HTTP 200 ALWAYS (warn/neutral semantics,
 * never error-colored: spec non-negotiable #2 / invariant I5); during the
 * 6b→6c window every effect is {@code recorded-intent} with the disclosure
 * {@code "enforcement pending gateway"} (ADR-003 D17 — an ACCEPTANCE item).
 *
 * <p>Envelope: {@code {data, meta, errors}} via {@code ResponseEnvelopeAdvice}.
 * Conflicts are 409 with spec codes ({@code AGENT_STALE_STATE},
 * {@code AGENT_ILLEGAL_TRANSITION}, {@code AGENT_TERMINAL},
 * {@code AGENT_KEY_CONFLICT}, {@code PROMOTION_REVIEW_REQUIRED}) and the
 * CURRENT {@link AgentDetail} in {@code data}; resolution misses are 404
 * {@code AGENT_NOT_FOUND}. Mappings live in {@code GlobalExceptionHandler}.
 *
 * <p>Actor identity (§5.5): PersonaContext headers — {@code X-Persona-Id} →
 * actor member id when parseable as a UUID, {@code X-Persona-Label} (falling
 * back to the persona-level display) → actor label. REST callers are
 * HUMAN-typed pre-Phase-5 (S9 spoofable, accepted); lifecycle verbs are
 * REST-only/human-only (invariant I11) — the M17 runtime acknowledgment has
 * NO REST endpoint (its transport is the 6c MCP tool).
 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {

    private final AgentLifecycleService lifecycleService;
    private final AgentPromotionService promotionService;
    private final AgentInterventionService interventionService;
    private final AgentControlConfirmationService controlConfirmationService;
    private final AgentNotificationService notificationService;
    private final AgentProjectionRebuilder projectionRebuilder;
    private final AgentQueryService queryService;
    private final PersonaContext personaContext;
    private final AgentRegistryGate registryGate;
    private final Clock clock;

    public AgentController(AgentLifecycleService lifecycleService,
                           AgentPromotionService promotionService,
                           AgentInterventionService interventionService,
                           AgentControlConfirmationService controlConfirmationService,
                           AgentNotificationService notificationService,
                           AgentProjectionRebuilder projectionRebuilder,
                           AgentQueryService queryService,
                           PersonaContext personaContext,
                           AgentRegistryGate registryGate,
                           Clock clock) {
        this.lifecycleService = lifecycleService;
        this.promotionService = promotionService;
        this.interventionService = interventionService;
        this.controlConfirmationService = controlConfirmationService;
        this.notificationService = notificationService;
        this.projectionRebuilder = projectionRebuilder;
        this.queryService = queryService;
        this.personaContext = personaContext;
        this.registryGate = registryGate;
        this.clock = clock;
    }

    // ------------------------------------------------------------ §5.4 deploy

    /**
     * §5.1 M1/M2 deploy — 201 with the new {@link AgentDetail}; an idempotent
     * replay of the same {@code idempotencyKey} returns 200 with the original
     * result re-projected (cascade D8 semantics).
     */
    @PostMapping
    public ResponseEntity<AgentDetail> deploy(@Valid @RequestBody AgentDeployRequest request) {
        AgentLifecycleService.DeployOutcome outcome = lifecycleService.deploy(toCommand(request));
        return ResponseEntity
                .status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(outcome.detail());
    }

    // ------------------------------------------------------------ §7.2 verbs

    /** M6 pause — 200, warn semantics, never error-colored; suspension enforcement block. */
    @PostMapping("/{idOrKey}/pause")
    public AgentVerbResponse pause(@PathVariable String idOrKey,
                                   @RequestParam(required = false) UUID domainId,
                                   @Valid @RequestBody AgentReasonedVerbRequest request) {
        AgentRegistration reg = lifecycleService.pause(
                resolveId(idOrKey, domainId), request.idempotencyKey(), currentActor(),
                request.reason(), request.expectedEventSeq());
        return verbResponse(reg, true);
    }

    /** M7 resume — PAUSED → mode_before_pause. */
    @PostMapping("/{idOrKey}/resume")
    public AgentVerbResponse resume(@PathVariable String idOrKey,
                                    @RequestParam(required = false) UUID domainId,
                                    @Valid @RequestBody AgentVerbRequest request) {
        AgentRegistration reg = lifecycleService.resume(
                resolveId(idOrKey, domainId), request.idempotencyKey(), currentActor(),
                request.expectedEventSeq());
        return verbResponse(reg, false);
    }

    /** M8 throttle — validated budget row + THROTTLE_SET. */
    @PostMapping("/{idOrKey}/throttle")
    public AgentVerbResponse throttle(@PathVariable String idOrKey,
                                      @RequestParam(required = false) UUID domainId,
                                      @Valid @RequestBody AgentThrottleRequest request) {
        AgentRegistration reg = lifecycleService.throttle(
                resolveId(idOrKey, domainId), request.idempotencyKey(), currentActor(),
                request.budgetKind(), request.limitValue(), request.windowMinutes(),
                request.note(), request.expectedEventSeq());
        return verbResponse(reg, true);
    }

    /** M8 unthrottle — {@code DELETE …/throttle/{kind}} clears the active budget of that kind. */
    @DeleteMapping("/{idOrKey}/throttle/{kind}")
    public AgentVerbResponse unthrottle(@PathVariable String idOrKey,
                                        @PathVariable String kind,
                                        @RequestParam(required = false) UUID domainId,
                                        @Valid @RequestBody AgentVerbRequest request) {
        AgentRegistration reg = lifecycleService.unthrottle(
                resolveId(idOrKey, domainId), request.idempotencyKey(), currentActor(),
                kind, request.expectedEventSeq());
        return verbResponse(reg, false);
    }

    /** M9 kill — 200 (never error-shaped); terminal-absorbing; chain-mirrored. */
    @PostMapping("/{idOrKey}/kill")
    public AgentVerbResponse kill(@PathVariable String idOrKey,
                                  @RequestParam(required = false) UUID domainId,
                                  @Valid @RequestBody AgentReasonedVerbRequest request) {
        AgentRegistration reg = lifecycleService.kill(
                resolveId(idOrKey, domainId), request.idempotencyKey(), currentActor(),
                request.reason(), request.expectedEventSeq());
        return verbResponse(reg, true);
    }

    /** M10 retire — orderly terminal absorption; chain-mirrored. */
    @PostMapping("/{idOrKey}/retire")
    public AgentVerbResponse retire(@PathVariable String idOrKey,
                                    @RequestParam(required = false) UUID domainId,
                                    @Valid @RequestBody AgentReasonedVerbRequest request) {
        AgentRegistration reg = lifecycleService.retire(
                resolveId(idOrKey, domainId), request.idempotencyKey(), currentActor(),
                request.reason(), request.expectedEventSeq());
        return verbResponse(reg, true);
    }

    /** M3 promote/request — mode stays SHADOW; shadow-window summary evented. */
    @PostMapping("/{idOrKey}/promote/request")
    public AgentVerbResponse requestPromotion(@PathVariable String idOrKey,
                                              @RequestParam(required = false) UUID domainId,
                                              @Valid @RequestBody AgentVerbRequest request) {
        AgentRegistration reg = promotionService.requestPromotion(
                resolveId(idOrKey, domainId), request.idempotencyKey(), currentActor(),
                request.expectedEventSeq());
        return verbResponse(reg, false);
    }

    /** M4 promote — 200 live, or 409 {@code PROMOTION_REVIEW_REQUIRED}; transits agent.promote. */
    @PostMapping("/{idOrKey}/promote")
    public AgentVerbResponse promote(@PathVariable String idOrKey,
                                     @RequestParam(required = false) UUID domainId,
                                     @Valid @RequestBody AgentVerbRequest request) {
        AgentRegistration reg = promotionService.promote(
                resolveId(idOrKey, domainId), request.idempotencyKey(), currentActor(),
                request.expectedEventSeq());
        return verbResponse(reg, false);
    }

    /** Review request — 201; gate INPUT, not a lifecycle event (ADR-003 D10). */
    @PostMapping("/{idOrKey}/reviews")
    public ResponseEntity<AgentReviewView> recordReview(@PathVariable String idOrKey,
                                                        @RequestParam(required = false) UUID domainId,
                                                        @Valid @RequestBody AgentReviewRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(AgentReviewView.from(
                promotionService.recordReview(resolveId(idOrKey, domainId),
                        parseWire(AgentReviewKind.class, request.reviewKind(), "reviewKind"),
                        request.requestedByLabel())));
    }

    /** M5 review decision — REJECTED renders info/neutral, re-requestable; never error. */
    @PostMapping("/reviews/{id}/decide")
    public AgentReviewView decideReview(@PathVariable UUID id,
                                        @Valid @RequestBody AgentReviewDecideRequest request) {
        String reviewer = request.reviewerLabel() != null && !request.reviewerLabel().isBlank()
                ? request.reviewerLabel() : currentActor().label();
        return AgentReviewView.from(promotionService.decideReview(id,
                parseWire(AgentReviewDecision.class, request.decision(), "decision"),
                reviewer, request.reviewerMemberId(), request.rationale(),
                request.evidenceRefs()));
    }

    /** M11 intervention open — 201; mode UNCHANGED (attention overlay, ADR-003 D4). */
    @PostMapping("/{idOrKey}/interventions")
    public ResponseEntity<AgentInterventionView> openIntervention(
            @PathVariable String idOrKey,
            @RequestParam(required = false) UUID domainId,
            @Valid @RequestBody AgentInterventionOpenRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(AgentInterventionView.from(
                interventionService.open(resolveId(idOrKey, domainId), request.idempotencyKey(),
                        currentActor(), request.reason(), request.evidenceRefs(),
                        AgentInterventionTrigger.HUMAN, null, request.expectedEventSeq())));
    }

    /** M12 intervention acknowledge. */
    @PostMapping("/interventions/{id}/acknowledge")
    public AgentInterventionView acknowledgeIntervention(@PathVariable UUID id,
                                                         @Valid @RequestBody AgentVerbRequest request) {
        return AgentInterventionView.from(interventionService.acknowledge(
                id, request.idempotencyKey(), currentActor()));
    }

    /** M13 intervention resolve — resolution + note mandatory; never error-colored. */
    @PostMapping("/interventions/{id}/resolve")
    public AgentInterventionView resolveIntervention(
            @PathVariable UUID id,
            @Valid @RequestBody AgentInterventionResolveRequest request) {
        return AgentInterventionView.from(interventionService.resolve(
                id, request.idempotencyKey(), currentActor(),
                parseWire(AgentInterventionResolution.class, request.resolution(), "resolution"),
                request.resolutionNote()));
    }

    /** M14 oversight replace — full replacement, before/after evented. */
    @PostMapping("/{idOrKey}/oversight")
    public AgentVerbResponse replaceOversight(@PathVariable String idOrKey,
                                              @RequestParam(required = false) UUID domainId,
                                              @Valid @RequestBody AgentOversightRequest request) {
        List<AgentLifecycleService.OversightEntry> chain = request.chain().stream()
                .map(e -> new AgentLifecycleService.OversightEntry(e.label(), e.memberId()))
                .toList();
        AgentRegistration reg = lifecycleService.replaceOversight(
                resolveId(idOrKey, domainId), request.idempotencyKey(), currentActor(),
                chain, request.expectedEventSeq());
        return verbResponse(reg, false);
    }

    /** M15 attest-liveness — human evidence; LIVENESS_ATTESTED + ATTESTATION sighting. */
    @PostMapping("/{idOrKey}/attest-liveness")
    public AgentVerbResponse attestLiveness(@PathVariable String idOrKey,
                                            @RequestParam(required = false) UUID domainId,
                                            @Valid @RequestBody AgentAttestLivenessRequest request) {
        AgentRegistration reg = lifecycleService.attestLiveness(
                resolveId(idOrKey, domainId), request.idempotencyKey(), currentActor(),
                request.statement(), request.evidenceRefs(), request.expectedEventSeq());
        return verbResponse(reg, false);
    }

    /** M16 confirm-control — human attestation that a RECORDED_INTENT control took effect. */
    @PostMapping("/{idOrKey}/confirm-control")
    public AgentVerbResponse confirmControl(@PathVariable String idOrKey,
                                            @RequestParam(required = false) UUID domainId,
                                            @Valid @RequestBody AgentConfirmControlRequest request) {
        AgentRegistration reg = controlConfirmationService.confirmControl(
                resolveId(idOrKey, domainId), request.idempotencyKey(), currentActor(),
                request.evidenceRefs(), request.note(), request.expectedEventSeq());
        return verbResponse(reg, true);
    }

    /** M18 bind principal. */
    @PostMapping("/{idOrKey}/principal")
    public AgentVerbResponse bindPrincipal(@PathVariable String idOrKey,
                                           @RequestParam(required = false) UUID domainId,
                                           @Valid @RequestBody AgentPrincipalRequest request) {
        AgentRegistration reg = lifecycleService.bindPrincipal(
                resolveId(idOrKey, domainId), request.idempotencyKey(), currentActor(),
                request.mcpPrincipalId(), request.expectedEventSeq());
        return verbResponse(reg, false);
    }

    /** M18 revoke principal. */
    @DeleteMapping("/{idOrKey}/principal")
    public AgentVerbResponse revokePrincipal(@PathVariable String idOrKey,
                                             @RequestParam(required = false) UUID domainId,
                                             @Valid @RequestBody AgentVerbRequest request) {
        AgentRegistration reg = lifecycleService.revokePrincipal(
                resolveId(idOrKey, domainId), request.idempotencyKey(), currentActor(),
                request.expectedEventSeq());
        return verbResponse(reg, false);
    }

    /**
     * Operator-recovery lane (engine-spec agents.md §16.2 #7 — additive to
     * §7.2, the cascade-rebuilder app-exposure precedent): deterministic
     * replay of the append-only event log onto the ⟲ projections; immutable
     * deploy fields are assert-equal verified, never rewritten.
     */
    @PostMapping("/{idOrKey}/rebuild-projections")
    public AgentDetail rebuildProjections(@PathVariable String idOrKey,
                                          @RequestParam(required = false) UUID domainId) {
        return queryService.detail(projectionRebuilder.rebuild(resolveId(idOrKey, domainId)));
    }

    // ------------------------------------------------------------ §7.1 reads

    /**
     * Fleet list — {@code retired} EXCLUDED by default (the swapped
     * OperationsTab {@code Record<AgentStatus,…>} pill map crashes on unknown
     * statuses; {@code ?includeRetired=true} is the opt-in contract, ADR-003
     * D4). {@code status} filters on the §5.2 wire projection (fixture
     * vocabulary, e.g. {@code intervention-required}).
     */
    @GetMapping
    public List<AgentFleetRow> fleet(@RequestParam UUID domainId,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(defaultValue = "false") boolean includeRetired) {
        return queryService.fleet(domainId,
                parseWire(AgentWireStatus.class, status, "status"), includeRetired);
    }

    /**
     * §13.1 fleet signals — explicit zeros ALWAYS present (no-silent-zero-
     * yield): an empty domain answers all-zero counts and empty arrays. The
     * OperationsTab stat strip swaps onto this ("Agents active" ←
     * {@code agentsTotal}; "Agents in intervention" ←
     * {@code agentsInterventionRequired}).
     */
    @GetMapping("/signals")
    public AgentSignals signals(@RequestParam UUID domainId) {
        return queryService.signals(domainId);
    }

    /** §13.2 agent attention rows (AttentionItem-shaped; tone mapping binding). */
    @GetMapping("/attention")
    public List<AgentAttentionItem> attention(@RequestParam UUID domainId) {
        return queryService.attention(domainId);
    }

    /** §7.1 cross-fleet live feed — recent events + sightings merged at read. */
    @GetMapping("/feed")
    public List<AgentFeedItem> feed(@RequestParam UUID domainId,
                                    @RequestParam(defaultValue = "25") int limit) {
        return queryService.feed(domainId, limit);
    }

    /** §7.1 outbox feed for a recipient ({@code unreadOnly?}). */
    @GetMapping("/notifications")
    public List<AgentNotificationView> notifications(
            @RequestParam UUID recipientId,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return notificationService.forRecipient(recipientId, unreadOnly).stream()
                .map(AgentNotificationView::from)
                .toList();
    }

    /** §7.1 mark-read sibling — idempotent watermark. */
    @PostMapping("/notifications/{id}/read")
    public AgentNotificationView markNotificationRead(@PathVariable UUID id) {
        return AgentNotificationView.from(notificationService.markRead(id, clock.instant()));
    }

    /**
     * §7 {@code {idOrKey}} — UUID first, then {@code agent_key}
     * ({@code ?domainId} scopes a key that exists in several domains).
     * Returns the full {@link AgentDetail}: deploymentCriteria (CLICK-THROUGH
     * ONLY — this is the click-through), ordered oversight chain,
     * always-present provenance, §6.1 controlEffects + enforcement +
     * liveness blocks; 404 {@code AGENT_NOT_FOUND} otherwise.
     */
    @GetMapping("/{idOrKey}")
    public AgentDetail detail(@PathVariable String idOrKey,
                              @RequestParam(required = false) UUID domainId) {
        return queryService.detailByIdOrKey(idOrKey, domainId);
    }

    /** §7.1 audit timeline — event_seq asc, paged. */
    @GetMapping("/{idOrKey}/events")
    public List<AgentEventView> events(@PathVariable String idOrKey,
                                       @RequestParam(required = false) UUID domainId,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "100") int size) {
        return queryService.events(queryService.resolve(idOrKey, domainId), page, size);
    }

    /** §7.1 telemetry sightings ({@code since?}). */
    @GetMapping("/{idOrKey}/sightings")
    public List<AgentSightingView> sightings(@PathVariable String idOrKey,
                                             @RequestParam(required = false) UUID domainId,
                                             @RequestParam(required = false) Instant since) {
        return queryService.sightings(queryService.resolve(idOrKey, domainId), since);
    }

    /** §7.1 criteria-gate rows + denormalized standing. */
    @GetMapping("/{idOrKey}/gates")
    public List<AgentDetail.CriteriaGateView> gates(@PathVariable String idOrKey,
                                                    @RequestParam(required = false) UUID domainId) {
        return queryService.gates(queryService.resolve(idOrKey, domainId));
    }

    /** §7.1 metric samples ({@code metricKey?}, {@code since?}). */
    @GetMapping("/{idOrKey}/metrics")
    public List<AgentMetricSampleView> metrics(@PathVariable String idOrKey,
                                               @RequestParam(required = false) UUID domainId,
                                               @RequestParam(required = false) String metricKey,
                                               @RequestParam(required = false) Instant since) {
        return queryService.metrics(queryService.resolve(idOrKey, domainId), metricKey, since);
    }

    // ------------------------------------------------------------------ mapping

    /** §6.2: the refreshed detail + the honesty block, one shape for every verb. */
    private AgentVerbResponse verbResponse(AgentRegistration reg, boolean suspension) {
        AgentDetail detail = queryService.detail(reg);
        AgentEnforcement enforcement = suspension
                ? AgentControlEffects.suspensionEnforcement(reg, registryGate.posture())
                : AgentControlEffects.statefulEnforcement(reg, registryGate.posture());
        return new AgentVerbResponse(detail.status(), enforcement, detail);
    }

    private UUID resolveId(String idOrKey, UUID domainId) {
        return queryService.resolve(idOrKey, domainId).getId();
    }

    private AgentLifecycleService.DeployCommand toCommand(AgentDeployRequest r) {
        List<AgentLifecycleService.CriteriaGateCommand> gates = r.criteriaGates().stream()
                .map(g -> new AgentLifecycleService.CriteriaGateCommand(
                        g.gateText(),
                        parseWire(AgentGateRuleType.class, g.ruleType(), "criteriaGates[].ruleType"),
                        g.predicate(),
                        g.cadence()))
                .toList();
        List<AgentLifecycleService.OversightEntry> chain = r.oversightChain() == null ? List.of()
                : r.oversightChain().stream()
                        .map(e -> new AgentLifecycleService.OversightEntry(e.label(), e.memberId()))
                        .toList();
        return new AgentLifecycleService.DeployCommand(
                r.idempotencyKey(),
                currentActor(),
                r.domainId(),
                r.agentKey(),
                r.name(),
                r.roleDescription(),
                parseWire(AgentExecutionLocus.class, r.executionLocus(), "executionLocus"),
                r.capabilityId(),
                r.deployedByLabel(),
                r.deployedByMemberId(),
                r.deploymentCriteria(),
                gates,
                chain,
                parseWire(AgentSupervisoryModel.class, r.supervisoryModel(), "supervisoryModel"),
                r.targetProcessId(),
                r.targetStepIds(),
                r.agentReadinessScore(),
                parseWire(AgentReadinessSource.class, r.readinessSource(), "readinessSource"),
                r.requiredSystemAccess(),
                r.requiredDataAccess(),
                r.initiativeId(),
                r.notes(),
                r.readOnly(),
                r.scheduleWindow(),
                r.samplingPct(),
                r.mcpPrincipalId(),
                r.liveJustification(),
                r.predecessorRegistrationId());
    }

    /** §5.5 persona-header actor; HUMAN-typed pre-Phase-5 (S9 accepted; OIDC Phase 5). */
    private AgentActor currentActor() {
        UUID memberId = null;
        String personaId = personaContext.getPersonaId();
        if (personaId != null && !personaId.isBlank()) {
            try {
                memberId = UUID.fromString(personaId.trim());
            } catch (IllegalArgumentException notAUuid) {
                // non-UUID persona ids carry no member identity — label-only actor (§5.5)
            }
        }
        String label = personaContext.getPersonaLabel();
        if (label == null || label.isBlank()) {
            label = personaContext.getPersonaLevel();
        }
        return AgentActor.human(memberId, label);
    }

    /**
     * Parses an optional kebab-case wire value ({@code @JsonValue} vocabulary,
     * spec §3) into its enum; {@code null}/blank flows through so the §5.4
     * service gate answers with its STRUCTURED missing-field code; unknown
     * values become a 400 via the {@code IllegalArgumentException} mapping.
     */
    private static <E extends Enum<E>> E parseWire(Class<E> type, String raw, String param) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid value for '" + param + "': " + raw
                    + " (expected one of " + Arrays.stream(type.getEnumConstants())
                            .map(e -> e.name().toLowerCase(Locale.ROOT).replace('_', '-'))
                            .collect(Collectors.joining(", ")) + ")");
        }
    }
}
