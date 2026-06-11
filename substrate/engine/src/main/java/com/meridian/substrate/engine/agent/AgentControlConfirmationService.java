package com.meridian.substrate.engine.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.enums.AgentEventType;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
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
 * RECORDED_INTENT control-confirmation lane (engine-spec agents.md §5.1
 * M16/M17, §6; ADR-003 D6): runtime ACKNOWLEDGMENT and human-attested
 * CONFIRMATION are distinct, separately-evented facts —
 * <ul>
 *   <li><b>M17 acknowledge_control</b> ("the runtime has seen the order") —
 *       the ONE write a PAUSED/RETIRED agent principal retains, since
 *       acknowledging shutdown is the compliant act. The 6c MCP tool is the
 *       governed transport; this is its engine half. Deliberately EXEMPT from
 *       the I11 human-only rule — CONTROL_ACKNOWLEDGED is the one event class
 *       legitimately carrying an AGENT actor.</li>
 *   <li><b>M16 confirm-control</b> ("a human verified it stopped") — REST,
 *       human-only, evidence refs MANDATORY.</li>
 * </ul>
 * CONFIRMED does not require ACKNOWLEDGED; neither is EVER auto-derived from
 * sighting absence ("no activity observed since pause" may render as a
 * clearly-labeled weak positive, never as confirmation).
 */
@Service
public class AgentControlConfirmationService {

    private static final Logger log =
            LoggerFactory.getLogger(AgentControlConfirmationService.class);

    private final AgentRegistrationRepository registrationRepository;
    private final AgentStatusEventRepository eventRepository;
    private final AgentQueryService queryService;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public AgentControlConfirmationService(AgentRegistrationRepository registrationRepository,
                                           AgentStatusEventRepository eventRepository,
                                           AgentQueryService queryService,
                                           Clock clock,
                                           ObjectMapper objectMapper) {
        this.registrationRepository = registrationRepository;
        this.eventRepository = eventRepository;
        this.queryService = queryService;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------ M16 confirm-control

    /**
     * §5.1 M16 — confirm-control (REST, human): records CONTROL_CONFIRMED
     * with mandatory evidence refs; the registration's
     * {@code control_confirmed_*} projection flips and the "Paused —
     * unconfirmed at runtime" line clears. Guard: a control must be pending.
     */
    @Ungoverned(reason = "only agent.deploy and agent.promote transit the gateway as governed"
            + " actions (§5.4/§8.4); confirm-control is a human evidence act on the pending"
            + " RECORDED_INTENT control (engine-spec agents.md §5.1 M16; ADR-003 D6)")
    @Transactional
    public AgentRegistration confirmControl(UUID agentRegistrationId, String idempotencyKey,
                                            AgentActor actor, JsonNode evidenceRefs, String note,
                                            Integer expectedEventSeq) {
        AgentVerbMechanics.requireIdempotencyKey(idempotencyKey);
        AgentActor.requireHumanShaped(actor);
        if (evidenceRefs == null || evidenceRefs.isNull()
                || (evidenceRefs.isArray() && evidenceRefs.isEmpty())) {
            throw new IllegalArgumentException(
                    "evidenceRefs are mandatory on confirm-control (M16: human attestation"
                            + " carries evidence — ADR-003 D6)");
        }
        AgentRegistration reg = AgentVerbMechanics.lock(registrationRepository, agentRegistrationId);
        if (AgentVerbMechanics.replay(eventRepository, reg, idempotencyKey,
                AgentEventType.CONTROL_CONFIRMED).isPresent()) {
            return reg;
        }
        AgentVerbMechanics.fence(reg, expectedEventSeq, () -> queryService.detail(reg));
        requirePendingControl(reg, "confirm-control");

        Instant now = clock.instant();
        reg.setControlConfirmedAt(now);
        reg.setControlConfirmedByLabel(actor.label());

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("verb", reg.getControlPendingVerb());
        if (note != null && !note.isBlank()) {
            payload.put("note", note);
        }
        AgentVerbMechanics.appendEvent(eventRepository, reg, AgentEventType.CONTROL_CONFIRMED,
                actor, null, payload, evidenceRefs, idempotencyKey, now);
        registrationRepository.save(reg);   // managed instance — same object, kept effectively final for the fence lambdas

        log.info("Control confirmed [key={}, verb={}, by={}]",
                reg.getAgentKey(), reg.getControlPendingVerb(), actor.label());
        return reg;
    }

    // -------------------------------------------------------- M17 acknowledge_control

    /**
     * §5.1 M17 — acknowledge_control: the runtime saw the order — a WEAKER
     * fact than confirmation, recorded as CONTROL_ACKNOWLEDGED with the
     * caller's (agent-shaped) actor identity. The 6c MCP tool is the only
     * intended transport; mode is unchanged and a PAUSED/RETIRED principal is
     * explicitly permitted this one write (§8.3 check 1 exception).
     */
    @Governed(actionType = GovernedActions.AGENT_ACKNOWLEDGE_CONTROL)
    @Transactional
    public AgentRegistration acknowledgeControl(UUID agentRegistrationId, String idempotencyKey,
                                                AgentActor actor) {
        AgentVerbMechanics.requireIdempotencyKey(idempotencyKey);
        if (actor == null) {
            throw new IllegalArgumentException(
                    "an attributed actor is required on every agent event (I3)");
        }
        // I11 exemption, deliberate: CONTROL_ACKNOWLEDGED is the one event
        // class legitimately carrying an AGENT actor (the runtime itself).
        AgentRegistration reg = AgentVerbMechanics.lock(registrationRepository, agentRegistrationId);
        if (AgentVerbMechanics.replay(eventRepository, reg, idempotencyKey,
                AgentEventType.CONTROL_ACKNOWLEDGED).isPresent()) {
            return reg;
        }
        requirePendingControl(reg, "acknowledge_control");

        Instant now = clock.instant();
        if (reg.getControlAcknowledgedAt() == null) {
            reg.setControlAcknowledgedAt(now);
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("verb", reg.getControlPendingVerb());
        AgentVerbMechanics.appendEvent(eventRepository, reg, AgentEventType.CONTROL_ACKNOWLEDGED,
                actor, null, payload, null, idempotencyKey, now);
        registrationRepository.save(reg);   // managed instance — same object, kept effectively final for the fence lambdas

        log.info("Control acknowledged by runtime [key={}, verb={}]",
                reg.getAgentKey(), reg.getControlPendingVerb());
        return reg;
    }

    private void requirePendingControl(AgentRegistration reg, String verb) {
        if (reg.getControlPendingVerb() == null) {
            throw new AgentConflictException(AgentConflictException.Code.AGENT_ILLEGAL_TRANSITION,
                    queryService.detail(reg),
                    "no control is pending on agent '" + reg.getAgentKey() + "' — " + verb
                            + " requires an outstanding RECORDED_INTENT control (M16/M17 guard)");
        }
    }
}
