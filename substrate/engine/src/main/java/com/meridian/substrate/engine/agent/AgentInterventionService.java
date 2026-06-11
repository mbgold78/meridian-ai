package com.meridian.substrate.engine.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentIntervention;
import com.meridian.substrate.common.domain.AgentOversightLink;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.AgentStatusEvent;
import com.meridian.substrate.common.domain.enums.AgentEventType;
import com.meridian.substrate.common.domain.enums.AgentInterventionResolution;
import com.meridian.substrate.common.domain.enums.AgentInterventionStatus;
import com.meridian.substrate.common.domain.enums.AgentInterventionTrigger;
import com.meridian.substrate.common.domain.enums.AgentNotificationType;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.repo.AgentInterventionRepository;
import com.meridian.substrate.common.repo.AgentOversightLinkRepository;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.common.repo.AgentStatusEventRepository;
import com.meridian.substrate.engine.agent.exception.AgentConflictException;
import com.meridian.substrate.engine.contract.gateway.Ungoverned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Intervention attention overlay (engine-spec agents.md §5.1 M11–M13; ADR-003
 * D4): an open intervention is the SECOND dimension of agent state — the
 * agent typically KEEPS operating (verified fixture truth: the No-Show
 * Predictor is {@code intervention-required} and still LIVE underneath);
 * pausing it is a separate, separately-evented operator act. Never
 * error-colored (spec non-negotiable #2).
 *
 * <p>Mechanics are the cascade §5.5 idiom verbatim ({@link AgentVerbMechanics}):
 * PESSIMISTIC_WRITE, idempotency under the lock, fencing, event append +
 * projection update + outbox in ONE tx (I9). INTERVENTION_OPENED fans out to
 * the FULL oversight chain (§13.4). RESOLUTION is human-only with a mandatory
 * note; {@code DEMOTED_TO_SHADOW} additionally flips LIVE→SHADOW and resets
 * {@code shadow_entered_at}; {@code resolution=RETIRED} merely RECORDS the
 * intent — the kill/retire verb is a separate, separately-attributed event
 * (two events, both attributed — §5.1 M13). Acknowledging/resolving stays
 * legal on a RETIRED agent: closing the paperwork on a dead agent is
 * legitimate (§5.1 terminal-absorption carve-out).
 */
@Service
public class AgentInterventionService {

    private static final Logger log = LoggerFactory.getLogger(AgentInterventionService.class);

    private final AgentRegistrationRepository registrationRepository;
    private final AgentInterventionRepository interventionRepository;
    private final AgentStatusEventRepository eventRepository;
    private final AgentOversightLinkRepository oversightLinkRepository;
    private final AgentNotificationService notificationService;
    private final AgentQueryService queryService;
    private final AgentRegistryLookup registryLookup;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public AgentInterventionService(AgentRegistrationRepository registrationRepository,
                                    AgentInterventionRepository interventionRepository,
                                    AgentStatusEventRepository eventRepository,
                                    AgentOversightLinkRepository oversightLinkRepository,
                                    AgentNotificationService notificationService,
                                    AgentQueryService queryService,
                                    AgentRegistryLookup registryLookup,
                                    Clock clock,
                                    ObjectMapper objectMapper) {
        this.registrationRepository = registrationRepository;
        this.interventionRepository = interventionRepository;
        this.eventRepository = eventRepository;
        this.oversightLinkRepository = oversightLinkRepository;
        this.notificationService = notificationService;
        this.queryService = queryService;
        this.registryLookup = registryLookup;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ M11 open

    /** 6a-signature convenience (DemoPack seed lane) — no fencing token. */
    @Ungoverned(reason = "delegates to the fenced open overload — see its reason"
            + " (engine-spec agents.md §5.1 M11, §8.4)")
    @Transactional
    public AgentIntervention open(UUID agentRegistrationId, String idempotencyKey,
                                  AgentActor actor, String reason, JsonNode evidenceRefs,
                                  AgentInterventionTrigger triggerKind, String sourceKey) {
        return open(agentRegistrationId, idempotencyKey, actor, reason, evidenceRefs,
                triggerKind, sourceKey, null);
    }

    /**
     * §5.1 M11 — open: mode UNCHANGED, {@code open_intervention_count}+1,
     * INTERVENTION_OPENED event, outbox to the full chain — one transaction.
     * Engine-raised rows (6c zombie flows) dedupe on {@code source_key}
     * (invariant I12: at most one OPEN row per (agent, source_key); the V6_1
     * partial unique is the PG backstop) — a dedupe hit returns the existing
     * row, appends nothing.
     */
    @Ungoverned(reason = "human/engine attention-overlay act, not a §8.4 governed action — only"
            + " agent.deploy and agent.promote transit the gateway per spec; opening an"
            + " intervention never changes operational_mode and is REST/engine-raised only"
            + " (engine-spec agents.md §5.1 M11, §8.4)")
    @Transactional
    public AgentIntervention open(UUID agentRegistrationId, String idempotencyKey,
                                  AgentActor actor, String reason, JsonNode evidenceRefs,
                                  AgentInterventionTrigger triggerKind, String sourceKey,
                                  Integer expectedEventSeq) {
        AgentVerbMechanics.requireIdempotencyKey(idempotencyKey);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is mandatory on intervention open (M11)");
        }
        AgentActor.requireHumanShaped(actor);
        AgentRegistration reg = AgentVerbMechanics.lock(registrationRepository, agentRegistrationId);
        if (AgentVerbMechanics.replay(eventRepository, reg, idempotencyKey,
                AgentEventType.INTERVENTION_OPENED).isPresent()) {
            return interventionRepository.findByAgentRegistrationIdOrderByOpenedAtDesc(reg.getId())
                    .stream().findFirst().orElseThrow();
        }
        AgentVerbMechanics.fence(reg, expectedEventSeq, () -> queryService.detail(reg));
        AgentVerbMechanics.requireNotTerminal(reg, () -> queryService.detail(reg),
                "intervention open");
        if (sourceKey != null) {
            Optional<AgentIntervention> openForSource = interventionRepository
                    .findFirstByAgentRegistrationIdAndSourceKeyAndStatus(
                            reg.getId(), sourceKey, AgentInterventionStatus.OPEN);
            if (openForSource.isPresent()) {
                return openForSource.get();    // I12 dedupe — refresh, never a second OPEN row
            }
        }

        Instant now = clock.instant();
        AgentIntervention intervention = new AgentIntervention();
        intervention.setAgentRegistrationId(reg.getId());
        intervention.setDomainId(reg.getDomainId());
        intervention.setStatus(AgentInterventionStatus.OPEN);
        intervention.setTriggerKind(triggerKind);
        intervention.setSourceKey(sourceKey);
        intervention.setReason(reason);
        intervention.setEvidenceRefs(evidenceRefs);
        intervention.setOpenedByMemberId(actor.memberId());
        intervention.setOpenedByLabel(actor.label());
        intervention.setOpenedAt(now);
        intervention = interventionRepository.save(intervention);

        reg.setOpenInterventionCount(reg.getOpenInterventionCount() + 1);
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("interventionId", intervention.getId().toString());
        payload.put("triggerKind", triggerKind.name());
        AgentStatusEvent event = AgentVerbMechanics.appendEvent(eventRepository, reg,
                AgentEventType.INTERVENTION_OPENED, actor, reason, payload, evidenceRefs,
                idempotencyKey, now);
        registrationRepository.save(reg);

        // §13.4: INTERVENTION_OPENED fans out to the FULL oversight chain,
        // destructive tone (leader-attention semantics, never failure).
        List<AgentOversightLink> chain =
                oversightLinkRepository.findByAgentRegistrationIdOrderByPositionAsc(reg.getId());
        ObjectNode notifyPayload = objectMapper.createObjectNode();
        notifyPayload.put("agentKey", reg.getAgentKey());
        notifyPayload.put("reason", reason);
        notificationService.fanOut(reg.getDomainId(), reg.getId(), event.getId(),
                AgentNotificationType.INTERVENTION_OPENED, "destructive", chain,
                notifyPayload, null);
        registryLookup.invalidate(reg.getId());   // open_intervention_count is gate state (§8.3 #5)

        log.info("Intervention opened [agent={}, trigger={}, sourceKey={}, openCount={}]",
                reg.getAgentKey(), triggerKind, sourceKey, reg.getOpenInterventionCount());
        return intervention;
    }

    // ------------------------------------------------------------ M12 acknowledge

    /**
     * §5.1 M12 — acknowledge: a NAMED human marks the intervention seen; mode
     * and count unchanged; INTERVENTION_ACKNOWLEDGED event. Legal on retired
     * agents (paperwork carve-out).
     */
    @Ungoverned(reason = "human attention-overlay act, not a §8.4 governed action — mode never"
            + " changes; acknowledging is paperwork on the overlay row"
            + " (engine-spec agents.md §5.1 M12)")
    @Transactional
    public AgentIntervention acknowledge(UUID interventionId, String idempotencyKey,
                                         AgentActor actor) {
        AgentVerbMechanics.requireIdempotencyKey(idempotencyKey);
        AgentActor.requireHumanShaped(actor);
        AgentIntervention intervention = interventionRepository.findById(interventionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "intervention not found: " + interventionId));
        AgentRegistration reg = AgentVerbMechanics.lock(registrationRepository,
                intervention.getAgentRegistrationId());
        if (AgentVerbMechanics.replay(eventRepository, reg, idempotencyKey,
                AgentEventType.INTERVENTION_ACKNOWLEDGED).isPresent()) {
            return intervention;
        }
        if (intervention.getStatus() != AgentInterventionStatus.OPEN) {
            throw new AgentConflictException(AgentConflictException.Code.AGENT_ILLEGAL_TRANSITION,
                    queryService.detail(reg),
                    "intervention " + interventionId + " is already resolved (M12 guard)");
        }

        Instant now = clock.instant();
        intervention.setAcknowledgedByLabel(actor.label());
        intervention.setAcknowledgedAt(now);
        intervention = interventionRepository.save(intervention);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("interventionId", intervention.getId().toString());
        AgentVerbMechanics.appendEvent(eventRepository, reg,
                AgentEventType.INTERVENTION_ACKNOWLEDGED, actor, null, payload, null,
                idempotencyKey, now);
        registrationRepository.save(reg);

        log.info("Intervention acknowledged [agent={}, interventionId={}]",
                reg.getAgentKey(), interventionId);
        return intervention;
    }

    // ---------------------------------------------------------------- M13 resolve

    /**
     * §5.1 M13 — resolve: human-only, resolution + note MANDATORY;
     * {@code open_intervention_count}−1; {@code DEMOTED_TO_SHADOW}
     * additionally flips LIVE→SHADOW and RESETS {@code shadow_entered_at};
     * {@code RETIRED} resolution records intent only — the kill/retire verb
     * is a separate attributed event. Legal on retired agents (closing the
     * paperwork on a dead agent is legitimate).
     */
    @Ungoverned(reason = "human attention-overlay act, not a §8.4 governed action — resolution"
            + " never suspends; DEMOTED_TO_SHADOW is the one mode edge and resolution=RETIRED"
            + " requires the separate kill/retire verb (engine-spec agents.md §5.1 M13)")
    @Transactional
    public AgentIntervention resolve(UUID interventionId, String idempotencyKey, AgentActor actor,
                                     AgentInterventionResolution resolution, String resolutionNote) {
        AgentVerbMechanics.requireIdempotencyKey(idempotencyKey);
        AgentActor.requireHumanShaped(actor);
        if (resolution == null) {
            throw new IllegalArgumentException("resolution is mandatory on resolve (M13)");
        }
        if (resolutionNote == null || resolutionNote.isBlank()) {
            throw new IllegalArgumentException("resolutionNote is mandatory on resolve (M13)");
        }
        AgentIntervention intervention = interventionRepository.findById(interventionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "intervention not found: " + interventionId));
        AgentRegistration reg = AgentVerbMechanics.lock(registrationRepository,
                intervention.getAgentRegistrationId());
        if (AgentVerbMechanics.replay(eventRepository, reg, idempotencyKey,
                AgentEventType.INTERVENTION_RESOLVED).isPresent()) {
            return intervention;
        }
        if (intervention.getStatus() != AgentInterventionStatus.OPEN) {
            throw new AgentConflictException(AgentConflictException.Code.AGENT_ILLEGAL_TRANSITION,
                    queryService.detail(reg),
                    "intervention " + interventionId + " is already resolved (M13 guard)");
        }
        if (resolution == AgentInterventionResolution.DEMOTED_TO_SHADOW
                && reg.getOperationalMode() != AgentOperationalMode.LIVE) {
            throw new AgentConflictException(AgentConflictException.Code.AGENT_ILLEGAL_TRANSITION,
                    queryService.detail(reg),
                    "DEMOTED_TO_SHADOW resolves a LIVE agent back to shadow (current: "
                            + reg.getOperationalMode().wireValue() + ") — M13 guard");
        }

        Instant now = clock.instant();
        intervention.setStatus(AgentInterventionStatus.RESOLVED);
        intervention.setResolvedByLabel(actor.label());
        intervention.setResolvedAt(now);
        intervention.setResolution(resolution);
        intervention.setResolutionNote(resolutionNote);
        intervention = interventionRepository.save(intervention);

        reg.setOpenInterventionCount(Math.max(0, reg.getOpenInterventionCount() - 1));
        if (resolution == AgentInterventionResolution.DEMOTED_TO_SHADOW) {
            reg.setOperationalMode(AgentOperationalMode.SHADOW);
            reg.setShadowEnteredAt(now);       // reset on DEMOTED_TO_SHADOW (§4 ⟲ column note)
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("interventionId", intervention.getId().toString());
        payload.put("resolution", resolution.name());
        AgentVerbMechanics.appendEvent(eventRepository, reg, AgentEventType.INTERVENTION_RESOLVED,
                actor, resolutionNote, payload, null, idempotencyKey, now);
        registrationRepository.save(reg);
        registryLookup.invalidate(reg.getId());   // count/mode are gate state (§8.3 #2/#5)

        log.info("Intervention resolved [agent={}, interventionId={}, resolution={}, openCount={}]",
                reg.getAgentKey(), interventionId, resolution, reg.getOpenInterventionCount());
        return intervention;
    }
}
