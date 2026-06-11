package com.meridian.substrate.engine.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentIntervention;
import com.meridian.substrate.common.domain.AgentOversightLink;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.AgentStatusEvent;
import com.meridian.substrate.common.domain.enums.AgentEventType;
import com.meridian.substrate.common.domain.enums.AgentInterventionStatus;
import com.meridian.substrate.common.domain.enums.AgentInterventionTrigger;
import com.meridian.substrate.common.domain.enums.AgentNotificationType;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.domain.enums.AgentSightingChannel;
import com.meridian.substrate.common.domain.enums.ContractActorType;
import com.meridian.substrate.common.repo.AgentInterventionRepository;
import com.meridian.substrate.common.repo.AgentOversightLinkRepository;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.common.repo.AgentSightingRepository;
import com.meridian.substrate.common.repo.AgentStatusEventRepository;
import com.meridian.substrate.engine.contract.gateway.Ungoverned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Gateway-side telemetry + zombie-signal lane (engine-spec agents.md
 * §6.4/§8.3; ADR-003 D6 #3): the {@code AgentRegistryGateImpl} side effects.
 * Everything here commits in {@code REQUIRES_NEW} — like the gateway's
 * decision rows (§10.4), a sighting of a suspended agent and its violation
 * trail are facts about the TOUCH, and must survive the enforce-mode
 * rollback of the denied business transaction.
 *
 * <p>The zombie flow ("sightings after pause/kill are recorded AND
 * escalated, never rejected-and-forgotten"):
 * <ol>
 *   <li>GATEWAY_ACTION sighting — the touch is true, record it (I7 lane);</li>
 *   <li>{@code CONTROL_VIOLATION_OBSERVED} event, idempotent per sighting
 *       bucket (the audit trail says it happened, without per-call spam);</li>
 *   <li>raise/refresh the engine intervention — trigger GATEWAY_VIOLATION,
 *       {@code source_key} {@value #SOURCE_ACTIVE_WHILE_PAUSED} /
 *       {@value #SOURCE_SEEN_AFTER_KILL} — at most one OPEN row (I12);</li>
 *   <li>{@code CONTROL_VIOLATION} outbox fan-out to the FULL oversight
 *       chain, destructive tone (§13.4), deduped per bucket.</li>
 * </ol>
 *
 * <p><b>Terminal carve-out, deliberate:</b> the M11 terminal-absorption
 * guard does NOT apply to this lane — {@code SEEN_AFTER_KILL} exists
 * precisely to raise attention on a RETIRED registration (§6.4: the
 * highest-urgency signal class). This is an engine-raised observation of
 * runtime reality, not an operator verb.
 */
@Service
public class AgentGatewaySignalService {

    /** §6.4 source keys — also the §13.1 signals discriminators. */
    public static final String SOURCE_ACTIVE_WHILE_PAUSED = "active-while-paused";
    public static final String SOURCE_SEEN_AFTER_KILL = "seen-after-kill";

    /** Engine actor identity on zombie events (I11-safe: SYSTEM, never AGENT). */
    static final AgentActor GATEWAY_ACTOR =
            new AgentActor(null, "substrate-gateway", ContractActorType.SYSTEM);

    private static final Logger log = LoggerFactory.getLogger(AgentGatewaySignalService.class);

    private final AgentRegistrationRepository registrationRepository;
    private final AgentSightingRepository sightingRepository;
    private final AgentStatusEventRepository eventRepository;
    private final AgentInterventionRepository interventionRepository;
    private final AgentOversightLinkRepository oversightLinkRepository;
    private final AgentNotificationService notificationService;
    private final AgentLivenessService livenessService;
    private final AgentRegistryLookup registryLookup;
    private final AgentProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public AgentGatewaySignalService(AgentRegistrationRepository registrationRepository,
                                     AgentSightingRepository sightingRepository,
                                     AgentStatusEventRepository eventRepository,
                                     AgentInterventionRepository interventionRepository,
                                     AgentOversightLinkRepository oversightLinkRepository,
                                     AgentNotificationService notificationService,
                                     AgentLivenessService livenessService,
                                     AgentRegistryLookup registryLookup,
                                     AgentProperties properties,
                                     Clock clock,
                                     ObjectMapper objectMapper) {
        this.registrationRepository = registrationRepository;
        this.sightingRepository = sightingRepository;
        this.eventRepository = eventRepository;
        this.interventionRepository = interventionRepository;
        this.oversightLinkRepository = oversightLinkRepository;
        this.notificationService = notificationService;
        this.livenessService = livenessService;
        this.registryLookup = registryLookup;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * §8.3 #6 — GATEWAY_ACTION sighting for a registered principal's
     * governed touch (liveness for free; hourly-bucket deduped). The cheap
     * exists pre-check keeps the hot path off the registration lock once the
     * bucket is taken.
     */
    @Ungoverned(reason = "gateway-side telemetry recording (§8.3 #6) — the governed lane wraps"
            + " the CALLER; recording the sighting of a governed call must not recurse the PDP"
            + " (engine-spec agents.md §8.3/§10)")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordGatewayTouch(UUID agentRegistrationId, String actionType) {
        if (bucketTaken(agentRegistrationId)) {
            return;
        }
        livenessService.recordSighting(agentRegistrationId, AgentSightingChannel.GATEWAY_ACTION,
                actionType);
    }

    /**
     * §6.4/§8.3 #1 — the full zombie flow for a governed touch by a
     * PAUSED/RETIRED registration. Idempotent per sighting bucket; the
     * intervention dedupes on {@code source_key} (a second heartbeat
     * REFRESHES, never opens a second row — test plan #10).
     */
    @Ungoverned(reason = "engine-raised §6.4 zombie flow in REQUIRES_NEW — fired BY the gateway"
            + " deny path itself; governing it would recurse the PDP (engine-spec agents.md"
            + " §6.4/§8.3; ADR-003 D6)")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuspendedTouch(UUID agentRegistrationId, String actionType) {
        recordControlViolation(agentRegistrationId,
                "governed action '" + actionType + "' attempted at the gateway", true);
    }

    /**
     * The shared violation trail (also the §14 result-quarantine signal,
     * which passes {@code sightTouch=false} — a quarantined RESULT is not
     * liveness evidence of the agent itself; sighting absence flips nothing
     * and sighting fabrication is worse).
     */
    @Ungoverned(reason = "engine-raised control-violation recording (§6.4/§14) — engine-internal"
            + " signal lane, not a lifecycle verb or ADR D2 PEP (engine-spec agents.md §6.4)")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordControlViolation(UUID agentRegistrationId, String observation,
                                       boolean sightTouch) {
        AgentRegistration reg = registrationRepository.findByIdForUpdate(agentRegistrationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "agent registration not found: " + agentRegistrationId));
        boolean retired = reg.getOperationalMode() == AgentOperationalMode.RETIRED;
        if (!retired && reg.getOperationalMode() != AgentOperationalMode.PAUSED) {
            // Raced a resume: the agent is legitimately running — no violation.
            log.debug("Suspended-touch signal raced a mode flip [agent={}, mode={}] — skipping",
                    reg.getAgentKey(), reg.getOperationalMode());
            return;
        }
        String sourceKey = retired ? SOURCE_SEEN_AFTER_KILL : SOURCE_ACTIVE_WHILE_PAUSED;
        String violationKind = retired ? "SEEN_AFTER_KILL" : "ACTIVE_WHILE_PAUSED";
        Instant now = clock.instant();
        String bucket = bucketKey(now);

        if (sightTouch) {
            livenessService.recordSighting(reg.getId(), AgentSightingChannel.GATEWAY_ACTION,
                    observation);
        }

        // 2. CONTROL_VIOLATION_OBSERVED — bucket-idempotent audit fact.
        String violationIdem = "zombie:" + sourceKey + ":" + bucket;
        boolean newViolationEvent = AgentVerbMechanics
                .replay(eventRepository, reg, violationIdem, AgentEventType.CONTROL_VIOLATION_OBSERVED)
                .isEmpty();
        AgentStatusEvent violationEvent = null;
        if (newViolationEvent) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("kind", violationKind);
            payload.put("sourceKey", sourceKey);
            payload.put("observation", observation);
            violationEvent = AgentVerbMechanics.appendEvent(eventRepository, reg,
                    AgentEventType.CONTROL_VIOLATION_OBSERVED, GATEWAY_ACTOR,
                    violationKind + ": " + observation, payload, null, violationIdem, now);
        }

        // 3. Raise/refresh the engine intervention (I12: one OPEN row per
        //    (agent, source_key); deliberate terminal carve-out — see class doc).
        Optional<AgentIntervention> open = interventionRepository
                .findFirstByAgentRegistrationIdAndSourceKeyAndStatus(
                        reg.getId(), sourceKey, AgentInterventionStatus.OPEN);
        if (open.isEmpty()) {
            AgentIntervention intervention = new AgentIntervention();
            intervention.setAgentRegistrationId(reg.getId());
            intervention.setDomainId(reg.getDomainId());
            intervention.setStatus(AgentInterventionStatus.OPEN);
            intervention.setTriggerKind(AgentInterventionTrigger.GATEWAY_VIOLATION);
            intervention.setSourceKey(sourceKey);
            intervention.setReason(violationKind + " — " + observation
                    + " while the agent is " + reg.getOperationalMode().wireValue());
            intervention.setOpenedByLabel(GATEWAY_ACTOR.label());
            intervention.setOpenedAt(now);
            intervention = interventionRepository.save(intervention);

            reg.setOpenInterventionCount(reg.getOpenInterventionCount() + 1);
            ObjectNode openedPayload = objectMapper.createObjectNode();
            openedPayload.put("interventionId", intervention.getId().toString());
            openedPayload.put("triggerKind", AgentInterventionTrigger.GATEWAY_VIOLATION.name());
            openedPayload.put("sourceKey", sourceKey);
            AgentVerbMechanics.appendEvent(eventRepository, reg,
                    AgentEventType.INTERVENTION_OPENED, GATEWAY_ACTOR,
                    intervention.getReason(), openedPayload, null,
                    "zombie-open:" + sourceKey + ":" + bucket, now);
            log.warn("Engine intervention raised [agent={}, sourceKey={}, kind={}]",
                    reg.getAgentKey(), sourceKey, violationKind);
        }
        registrationRepository.save(reg);
        registryLookup.invalidate(reg.getId());   // open_intervention_count is gate state

        // 4. CONTROL_VIOLATION → FULL chain, destructive (§13.4), per-bucket dedupe.
        List<AgentOversightLink> chain =
                oversightLinkRepository.findByAgentRegistrationIdOrderByPositionAsc(reg.getId());
        ObjectNode notifyPayload = objectMapper.createObjectNode();
        notifyPayload.put("agentKey", reg.getAgentKey());
        notifyPayload.put("kind", violationKind);
        notifyPayload.put("observation", observation);
        notificationService.fanOut(reg.getDomainId(), reg.getId(),
                violationEvent == null ? null : violationEvent.getId(),
                AgentNotificationType.CONTROL_VIOLATION, "destructive", chain, notifyPayload,
                "violation:" + reg.getId() + ":" + sourceKey + ":" + bucket);
    }

    // ------------------------------------------------------------------ helpers

    /** Cheap indexed pre-check so the allowed hot path skips the lock once sighted. */
    private boolean bucketTaken(UUID agentRegistrationId) {
        return sightingRepository.existsByAgentRegistrationIdAndChannelAndBucketKey(
                agentRegistrationId, AgentSightingChannel.GATEWAY_ACTION, bucketKey(clock.instant()));
    }

    /** Same epoch-bucket arithmetic as the liveness lane (§1). */
    private String bucketKey(Instant at) {
        long bucketSeconds = Math.max(1, properties.getSightingBucketMinutes()) * 60L;
        return Long.toString(at.getEpochSecond() / bucketSeconds);
    }
}
