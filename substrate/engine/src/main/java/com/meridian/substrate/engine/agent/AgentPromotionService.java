package com.meridian.substrate.engine.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentComplianceReview;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.AgentStatusEvent;
import com.meridian.substrate.common.domain.enums.AgentEventType;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.domain.enums.AgentReviewDecision;
import com.meridian.substrate.common.domain.enums.AgentReviewKind;
import com.meridian.substrate.common.repo.AgentComplianceReviewRepository;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.common.repo.AgentSightingRepository;
import com.meridian.substrate.common.repo.AgentStatusEventRepository;
import com.meridian.substrate.engine.agent.exception.AgentConflictException;
import com.meridian.substrate.engine.contract.gateway.Governed;
import com.meridian.substrate.engine.contract.gateway.GovernedActions;
import com.meridian.substrate.engine.contract.gateway.Ungoverned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Promotion lane (engine-spec agents.md §5.1 M3/M4/M5 + review rows; ADR-003
 * D10): SHADOW→LIVE is two evented steps — (1) {@link #requestPromotion}
 * records PROMOTION_REQUESTED with the shadow-window summary; (2)
 * {@link #promote} executes ONLY against an unconsumed APPROVED PROMOTION
 * review newer than the request — else 409 {@code PROMOTION_REVIEW_REQUIRED}
 * — and transits the gateway as {@code actionType=agent.promote} (the
 * contracts 3c acceptance seeds a DENY guard on that name). The review row
 * records reviewer identity, decision, rationale, evidence refs and NOTHING
 * else. EXTENSION POINT(agent-3): review CONTENT semantics (what "HIPAA
 * validation before promotion" concretely requires) are UNKNOWN — the entity
 * must never quietly encode invented compliance criteria; first regulated
 * promotion is a Mike session (ADR-003 D10).
 *
 * <p>Mechanics are the cascade §5.5 idiom verbatim ({@link AgentVerbMechanics}):
 * PESSIMISTIC_WRITE, idempotency under the lock, {@code expectedEventSeq}
 * fencing, event append + projection update + review consumption in ONE
 * transaction (I9). REJECTED renders info/neutral and is re-requestable —
 * never error-colored (spec non-negotiable #2). PROMOTED events chain-mirror
 * (§11).
 */
@Service
public class AgentPromotionService {

    private static final Logger log = LoggerFactory.getLogger(AgentPromotionService.class);

    private final AgentRegistrationRepository registrationRepository;
    private final AgentStatusEventRepository eventRepository;
    private final AgentComplianceReviewRepository reviewRepository;
    private final AgentSightingRepository sightingRepository;
    private final AgentQueryService queryService;
    private final AgentGovernanceChainMirror chainMirror;
    private final AgentRegistryLookup registryLookup;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public AgentPromotionService(AgentRegistrationRepository registrationRepository,
                                 AgentStatusEventRepository eventRepository,
                                 AgentComplianceReviewRepository reviewRepository,
                                 AgentSightingRepository sightingRepository,
                                 AgentQueryService queryService,
                                 AgentGovernanceChainMirror chainMirror,
                                 AgentRegistryLookup registryLookup,
                                 Clock clock,
                                 ObjectMapper objectMapper) {
        this.registrationRepository = registrationRepository;
        this.eventRepository = eventRepository;
        this.reviewRepository = reviewRepository;
        this.sightingRepository = sightingRepository;
        this.queryService = queryService;
        this.chainMirror = chainMirror;
        this.registryLookup = registryLookup;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ M3 request

    /**
     * §5.1 M3 — promote/request: mode stays SHADOW; guard "mode = SHADOW; no
     * open request". The PROMOTION_REQUESTED payload is the shadow-window
     * summary the reviewer reads: {@code shadow_entered_at}, sighting count,
     * latest adherence sample (null when none exists — never fabricated).
     */
    @Ungoverned(reason = "only agent.deploy and agent.promote transit the gateway as governed"
            + " actions (§5.4/§8.4); requesting promotion records review INPUT — the gated act"
            + " is promote() (engine-spec agents.md §5.1 M3; ADR-003 D10)")
    @Transactional
    public AgentRegistration requestPromotion(UUID agentRegistrationId, String idempotencyKey,
                                              AgentActor actor, Integer expectedEventSeq) {
        AgentVerbMechanics.requireIdempotencyKey(idempotencyKey);
        AgentActor.requireHumanShaped(actor);
        AgentRegistration reg = AgentVerbMechanics.lock(registrationRepository, agentRegistrationId);
        if (AgentVerbMechanics.replay(eventRepository, reg, idempotencyKey,
                AgentEventType.PROMOTION_REQUESTED).isPresent()) {
            return reg;
        }
        AgentVerbMechanics.fence(reg, expectedEventSeq, () -> queryService.detail(reg));
        AgentVerbMechanics.requireNotTerminal(reg, () -> queryService.detail(reg), "promote/request");
        if (reg.getOperationalMode() != AgentOperationalMode.SHADOW) {
            throw new AgentConflictException(AgentConflictException.Code.AGENT_ILLEGAL_TRANSITION,
                    queryService.detail(reg),
                    "promote/request requires operational_mode=shadow (current: "
                            + reg.getOperationalMode().wireValue() + ") — M3 guard");
        }
        if (reg.getPromotionRequestedAt() != null) {
            throw new AgentConflictException(AgentConflictException.Code.AGENT_ILLEGAL_TRANSITION,
                    queryService.detail(reg),
                    "a promotion request is already open on agent '" + reg.getAgentKey()
                            + "' (requested " + reg.getPromotionRequestedAt() + ") — M3 guard");
        }

        Instant now = clock.instant();
        reg.setPromotionRequestedAt(now);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("shadowEnteredAt",
                reg.getShadowEnteredAt() == null ? null : reg.getShadowEnteredAt().toString());
        payload.put("sightingCount", sightingRepository.countByAgentRegistrationId(reg.getId()));
        Double adherence = queryService.adherenceFor(reg.getId());
        if (adherence == null) {
            payload.putNull("latestAdherence");
        } else {
            payload.put("latestAdherence", adherence);
        }
        AgentVerbMechanics.appendEvent(eventRepository, reg, AgentEventType.PROMOTION_REQUESTED,
                actor, null, payload, null, idempotencyKey, now);
        registrationRepository.save(reg);   // managed instance — same object, kept effectively final for the fence lambdas

        log.info("Promotion requested [key={}, domainId={}]", reg.getAgentKey(), reg.getDomainId());
        return reg;
    }

    // ------------------------------------------------------------------ reviews

    /**
     * Records a review request ({@code POST /agents/{idOrKey}/reviews}).
     * Reviews are gate INPUTS, not lifecycle events — no status event appends
     * until a PROMOTED event consumes one (or a REJECTED decision events M5).
     */
    @Ungoverned(reason = "review-request row insert — a promotion-gate INPUT, not a lifecycle"
            + " verb or ADR D2 PEP; the gated act is promote(), which is @Governed(agent.promote)"
            + " (engine-spec agents.md §5.1 M4; ADR-003 D10)")
    @Transactional
    public AgentComplianceReview recordReview(UUID agentRegistrationId, AgentReviewKind kind,
                                              String requestedByLabel) {
        if (requestedByLabel == null || requestedByLabel.isBlank()) {
            throw new IllegalArgumentException("requestedByLabel is required (spec §7.2)");
        }
        if (kind == null) {
            throw new IllegalArgumentException("reviewKind is required (spec §7.2)");
        }
        AgentComplianceReview review = new AgentComplianceReview();
        review.setAgentRegistrationId(agentRegistrationId);
        review.setReviewKind(kind);
        review.setRequestedAt(clock.instant());
        review.setRequestedByLabel(requestedByLabel);
        return reviewRepository.save(review);
    }

    /** 6a-signature convenience (DemoPack seed lane). */
    @Ungoverned(reason = "delegates to the full decideReview overload — see its reason"
            + " (engine-spec agents.md §5.1 M5; ADR-003 D10)")
    @Transactional
    public AgentComplianceReview decideReview(UUID reviewId, AgentReviewDecision decision,
                                              String reviewerLabel, String rationale) {
        return decideReview(reviewId, decision, reviewerLabel, null, rationale, null);
    }

    /**
     * Decides a review ({@code POST /agents/reviews/{id}/decide}). Rationale
     * is mandatory at decision; single-shot (an already-decided review cannot
     * be re-decided — record a new one). §5.1 M5: a REJECTED decision on a
     * PROMOTION review appends the info-toned PROMOTION_REJECTED event (mode
     * unchanged, re-requestable — the open request is closed);
     * NEEDS_MORE_TIME keeps the request open; APPROVED stays an unconsumed
     * gate input until a promote consumes it.
     */
    @Ungoverned(reason = "review-decision row update + the M5 PROMOTION_REJECTED event — a"
            + " promotion-gate INPUT, not a §8.4 governed action; the gated act is promote()"
            + " (engine-spec agents.md §5.1 M5; ADR-003 D10)")
    @Transactional
    public AgentComplianceReview decideReview(UUID reviewId, AgentReviewDecision decision,
                                              String reviewerLabel, UUID reviewerMemberId,
                                              String rationale, JsonNode evidenceRefs) {
        if (decision == null) {
            throw new IllegalArgumentException("decision is required (spec §7.2)");
        }
        if (rationale == null || rationale.isBlank()) {
            throw new IllegalArgumentException("rationale is mandatory at review decision (§4 table 5)");
        }
        AgentComplianceReview review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("review not found: " + reviewId));
        if (review.getDecision() != null) {
            throw new IllegalArgumentException("review " + reviewId + " is already decided ("
                    + review.getDecision() + ") — decisions are single-shot; record a new review");
        }
        Instant now = clock.instant();
        review.setDecision(decision);
        review.setReviewerLabel(reviewerLabel);
        review.setReviewerMemberId(reviewerMemberId);
        review.setRationale(rationale);
        review.setEvidenceRefs(evidenceRefs);
        review.setDecidedAt(now);
        review = reviewRepository.save(review);

        if (decision == AgentReviewDecision.REJECTED
                && review.getReviewKind() == AgentReviewKind.PROMOTION) {
            AgentRegistration reg = AgentVerbMechanics.lock(registrationRepository,
                    review.getAgentRegistrationId());
            reg.setPromotionRequestedAt(null);       // closed; re-requestable (M5)
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("reviewId", review.getId().toString());
            AgentVerbMechanics.appendEvent(eventRepository, reg, AgentEventType.PROMOTION_REJECTED,
                    AgentActor.human(reviewerMemberId,
                            reviewerLabel != null && !reviewerLabel.isBlank()
                                    ? reviewerLabel : review.getRequestedByLabel()),
                    null, payload, evidenceRefs, "review:" + review.getId() + ":rejected", now);
            registrationRepository.save(reg);
        }
        return review;
    }

    // ------------------------------------------------------------------ M4 promote

    /** 6a-signature convenience (DemoPack seed lane) — no fencing token. */
    @Governed(actionType = GovernedActions.AGENT_PROMOTE)
    @Transactional
    public AgentRegistration promote(UUID agentRegistrationId, String idempotencyKey,
                                     AgentActor actor) {
        return promote(agentRegistrationId, idempotencyKey, actor, null);
    }

    /**
     * §5.1 M4 — promote: SHADOW → LIVE, gated on an unconsumed APPROVED
     * PROMOTION review decided after the promote-request (when one exists);
     * the consumed review is single-use ({@code consumed_by_event_id} set in
     * the same transaction as the PROMOTED event whose payload carries the
     * review id). Transits the gateway as {@code agent.promote} (§8.4) —
     * agents never self-promote (I11 + the built-in AGENT-deny guard); the
     * PROMOTED event chain-mirrors after commit (§11).
     */
    @Governed(actionType = GovernedActions.AGENT_PROMOTE)
    @Transactional
    public AgentRegistration promote(UUID agentRegistrationId, String idempotencyKey,
                                     AgentActor actor, Integer expectedEventSeq) {
        AgentVerbMechanics.requireIdempotencyKey(idempotencyKey);
        AgentActor.requireHumanShaped(actor);
        AgentRegistration reg = AgentVerbMechanics.lock(registrationRepository, agentRegistrationId);
        if (AgentVerbMechanics.replay(eventRepository, reg, idempotencyKey,
                AgentEventType.PROMOTED).isPresent()) {
            return reg;                        // idempotent replay — no new rows (cascade D8)
        }
        AgentVerbMechanics.fence(reg, expectedEventSeq, () -> queryService.detail(reg));
        AgentVerbMechanics.requireNotTerminal(reg, () -> queryService.detail(reg), "promote");
        if (reg.getOperationalMode() != AgentOperationalMode.SHADOW) {
            throw new AgentConflictException(AgentConflictException.Code.AGENT_ILLEGAL_TRANSITION,
                    queryService.detail(reg),
                    "promote requires operational_mode=shadow (current: "
                            + reg.getOperationalMode().wireValue() + ") — M4 guard");
        }
        Instant requestFloor = reg.getPromotionRequestedAt() != null
                ? reg.getPromotionRequestedAt() : Instant.EPOCH;
        AgentComplianceReview review = reviewRepository
                .findFirstByAgentRegistrationIdAndReviewKindAndDecisionAndConsumedByEventIdIsNullAndDecidedAtGreaterThanEqualOrderByDecidedAtDesc(
                        reg.getId(), AgentReviewKind.PROMOTION, AgentReviewDecision.APPROVED, requestFloor)
                .orElseThrow(() -> new AgentConflictException(
                        AgentConflictException.Code.PROMOTION_REVIEW_REQUIRED,
                        queryService.detail(reg),
                        "promote requires an unconsumed APPROVED PROMOTION review newer than the"
                                + " request (ADR-003 D10) — record + decide a review first"));

        Instant now = clock.instant();
        reg.setOperationalMode(AgentOperationalMode.LIVE);
        reg.setPromotionRequestedAt(null);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("consumedReviewId", review.getId().toString());
        AgentStatusEvent event = AgentVerbMechanics.appendEvent(eventRepository, reg,
                AgentEventType.PROMOTED, actor, null, payload, null, idempotencyKey, now);

        review.setConsumedByEventId(event.getId());     // single-use, same tx (I9)
        reviewRepository.save(review);
        registrationRepository.save(reg);   // managed instance — same object, kept effectively final for the fence lambdas
        registryLookup.invalidate(reg.getId());   // SHADOW→LIVE is gate state (§8.3 #2)
        chainMirror.onPromoted(reg, event, review.getId());   // §11 — anchors after commit

        log.info("Promoted agent [key={}, domainId={}, reviewId={}]",
                reg.getAgentKey(), reg.getDomainId(), review.getId());
        return reg;
    }
}
