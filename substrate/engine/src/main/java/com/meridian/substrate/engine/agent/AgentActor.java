package com.meridian.substrate.engine.agent;

import com.meridian.substrate.common.domain.enums.ContractActorType;

import java.util.UUID;

/**
 * Acting party for an agent lifecycle command (engine-spec agents.md §5.5;
 * ADR-003 D1): soft member reference + denormalized label + reused
 * {@link ContractActorType} (spec §3). Mapped from PersonaContext headers at
 * the controller boundary in Phase 6 ({@code X-Persona-Id} → memberId when
 * parseable as a UUID, persona display → label; spoofable, accepted per S9);
 * OIDC replaces the source in Phase 5 with no schema change.
 *
 * <p>Invariant I11: lifecycle events never carry {@code actorType=AGENT} —
 * enforced in {@link AgentLifecycleService}, structurally backed by the
 * absence of MCP lifecycle tools (ADR-003 D16).
 *
 * @param memberId nullable soft ref → organization_members (app-enforced, no DB FK)
 * @param label    required render/identity fallback; recorded on every event (I3)
 * @param type     reused ContractActorType; REST callers are HUMAN pre-Phase-5
 */
public record AgentActor(UUID memberId, String label, ContractActorType type) {

    public AgentActor {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("actor label is required on every agent event (invariant I3)");
        }
        if (type == null) {
            throw new IllegalArgumentException("actor type is required on every agent event (invariant I3)");
        }
    }

    public static AgentActor human(UUID memberId, String label) {
        return new AgentActor(memberId, label, ContractActorType.HUMAN);
    }

    /**
     * Invariant I11 guard shared by every lifecycle verb: lifecycle events
     * never carry {@code actor_type=AGENT} (service-enforced; agents never
     * self-administer — ADR-003 D16).
     */
    public static void requireHumanShaped(AgentActor actor) {
        if (actor == null) {
            throw new IllegalArgumentException(
                    "an attributed actor is required on every lifecycle verb (I3)");
        }
        if (actor.type() == ContractActorType.AGENT) {
            throw new com.meridian.substrate.engine.agent.exception.AgentForbiddenException(
                    com.meridian.substrate.engine.agent.exception.AgentForbiddenException.Code
                            .AGENT_LIFECYCLE_HUMAN_ONLY,
                    "lifecycle verbs are REST-only and human-only: actor_type=AGENT is illegal on"
                            + " lifecycle events (invariant I11; agents never self-administer)");
        }
    }
}
