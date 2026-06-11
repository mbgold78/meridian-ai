package com.meridian.substrate.engine.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.AgentStatusEvent;
import com.meridian.substrate.common.domain.enums.AgentEventType;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.common.repo.AgentStatusEventRepository;
import com.meridian.substrate.engine.agent.exception.AgentConflictException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The cascade-§5.5 verb chassis, shared by every agent write service
 * (engine-spec agents.md §5.1/§5.5; ADR-003 D1) so the mechanics stay
 * byte-identical across {@link AgentLifecycleService},
 * {@link AgentPromotionService}, {@link AgentInterventionService} and
 * {@link AgentControlConfirmationService}:
 *
 * <ol>
 *   <li>PESSIMISTIC_WRITE on the registration row ({@link #lock});</li>
 *   <li>idempotency check UNDER the lock ({@link #replay} — same key replays
 *       the original outcome, appends nothing; cascade D8);</li>
 *   <li>optional {@code expectedEventSeq} fencing ({@link #fence} → 409
 *       {@code AGENT_STALE_STATE} with the CURRENT detail in the envelope
 *       {@code data});</li>
 *   <li>terminal absorption ({@link #requireNotTerminal} — RETIRED answers
 *       409 {@code AGENT_TERMINAL} to every further verb; resolving paperwork
 *       on a dead agent stays legitimate and bypasses this guard);</li>
 *   <li>gapless event append + projection update + outbox in ONE transaction
 *       ({@link #appendEvent} increments the fencing watermark, invariants
 *       I3/I9/I10).</li>
 * </ol>
 */
final class AgentVerbMechanics {

    private AgentVerbMechanics() {
    }

    static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                    "idempotencyKey is required on every agent write body (spec §7.2)");
        }
    }

    static AgentRegistration lock(AgentRegistrationRepository registrations, UUID id) {
        return registrations.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "agent registration not found: " + id));
    }

    /** Idempotent-replay lookup under the lock; a hit means "answer the original, append nothing". */
    static Optional<AgentStatusEvent> replay(AgentStatusEventRepository events,
                                             AgentRegistration reg, String idempotencyKey,
                                             AgentEventType type) {
        return events.findByAgentRegistrationIdAndIdempotencyKey(reg.getId(), idempotencyKey)
                .filter(e -> e.getEventType() == type);
    }

    /** Optional fencing token (cascade D8): stale ⇒ 409 with the current detail in {@code data}. */
    static void fence(AgentRegistration reg, Integer expectedEventSeq,
                      Supplier<com.meridian.substrate.engine.agent.read.AgentDetail> currentDetail) {
        if (expectedEventSeq != null && expectedEventSeq != reg.getEventSeq()) {
            throw new AgentConflictException(AgentConflictException.Code.AGENT_STALE_STATE,
                    currentDetail.get(),
                    "expectedEventSeq " + expectedEventSeq + " is stale — agent '"
                            + reg.getAgentKey() + "' is at event_seq " + reg.getEventSeq()
                            + "; refresh from the envelope data and retry (spec §7.2)");
        }
    }

    /** §5.1: RETIRED is terminal-absorbing — any further verb 409s {@code AGENT_TERMINAL}. */
    static void requireNotTerminal(AgentRegistration reg,
                                   Supplier<com.meridian.substrate.engine.agent.read.AgentDetail> currentDetail,
                                   String verb) {
        if (reg.getOperationalMode() == AgentOperationalMode.RETIRED) {
            throw new AgentConflictException(AgentConflictException.Code.AGENT_TERMINAL,
                    currentDetail.get(),
                    "agent '" + reg.getAgentKey() + "' is retired — terminal-absorbing; '" + verb
                            + "' is no longer legal (re-deploy = new registration with"
                            + " predecessorRegistrationId lineage, ADR-003 D5)");
        }
    }

    /**
     * Appends the verb's event at the next gapless {@code event_seq} and
     * advances the registration watermark (I3/I10). Caller persists both rows
     * in its one transaction (I9).
     */
    static AgentStatusEvent appendEvent(AgentStatusEventRepository events,
                                        AgentRegistration reg,
                                        AgentEventType type,
                                        AgentActor actor,
                                        String reason,
                                        JsonNode payload,
                                        JsonNode evidenceRefs,
                                        String idempotencyKey,
                                        Instant occurredAt) {
        reg.setEventSeq(reg.getEventSeq() + 1);
        AgentStatusEvent event = new AgentStatusEvent();
        event.setAgentRegistrationId(reg.getId());
        event.setDomainId(reg.getDomainId());
        event.setEventSeq(reg.getEventSeq());
        event.setEventType(type);
        event.setActorType(actor.type());
        event.setActorMemberId(actor.memberId());
        event.setActorLabel(actor.label());
        event.setReason(reason);
        event.setPayload(payload);
        event.setEvidenceRefs(evidenceRefs);
        event.setIdempotencyKey(idempotencyKey);
        event.setOccurredAt(occurredAt);
        return events.save(event);
    }
}
