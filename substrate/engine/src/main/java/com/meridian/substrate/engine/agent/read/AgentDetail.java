package com.meridian.substrate.engine.agent.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.AgentCriteriaGate;
import com.meridian.substrate.common.domain.AgentOversightLink;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.enums.AgentExecutionLocus;
import com.meridian.substrate.common.domain.enums.AgentGateRuleType;
import com.meridian.substrate.common.domain.enums.AgentGateStanding;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.domain.enums.AgentReadinessSource;
import com.meridian.substrate.common.domain.enums.AgentRetiredReason;
import com.meridian.substrate.common.domain.enums.AgentSupervisoryModel;
import com.meridian.substrate.common.domain.enums.AgentWireStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent detail DTO (engine-spec agents.md §7.3): the {@code BeaconAgent}
 * superset — fleet-row fields plus the click-through mandate plus the 6b
 * blocks: {@code controlEffects} (disclosure BEFORE the click, §6.1),
 * {@code enforcement} (RECORDED_INTENT confirmation tracking),
 * {@code liveness} (evidence-derived, §10), {@code budgets},
 * {@code interventions} (the open attention overlay — the spec's
 * {@code openInterventions[]}; named distinctly because the fleet-row
 * {@code openInterventions} COUNT is BeaconAgent-superset parity),
 * {@code promotion} and {@code recentEvents[5]}.
 *
 * <p>Render rules binding for the swap: {@code deploymentCriteria} is
 * CLICK-THROUGH ONLY (verbatim [AGENT-PROVENANCE] render rule — never the row
 * line); {@code status} is the §5.2 pinned-precedence projection in exact
 * fixture vocabulary; {@code adherence} is NULLABLE (null until metric data
 * exists — em-dash in the UI, never fabricated); {@code recentActivity} is
 * DERIVED (latest sighting detail, else latest lifecycle event, else "No
 * activity observed") — never blank, never invented; the liveness line is
 * separate from the status pill — never a fabricated green dot.
 */
public record AgentDetail(
        String id,                              // = agent_key (fixture parity); registrationId additive
        UUID registrationId,
        UUID domainId,
        String name,
        String role,                            // fixture field name; column role_description
        AgentWireStatus status,                 // §5.2 projection, fixture vocabulary
        AgentOperationalMode operationalMode,   // machine state — the OTHER dimension, additive
        String recentActivity,
        Double adherence,                       // NULLABLE — latest 'adherence' metric sample
        UUID capabilityId,
        String deployedBy,
        UUID deployedByMemberId,
        Instant deployedAt,
        String deploymentCriteria,              // click-through only
        String criteriaDigest,
        List<String> oversightChain,            // ordered labels, position asc
        AgentExecutionLocus executionLocus,
        AgentSupervisoryModel supervisoryModel,
        int openInterventions,
        int eventSeq,
        List<CriteriaGateView> criteriaGates,
        UUID targetProcessId,
        JsonNode targetStepIds,
        Integer agentReadinessScore,
        AgentReadinessSource readinessSource,
        JsonNode requiredSystemAccess,
        JsonNode requiredDataAccess,
        UUID initiativeId,
        String notes,
        boolean readOnly,
        JsonNode scheduleWindow,
        Integer samplingPct,
        String mcpPrincipalId,
        Instant shadowEnteredAt,
        Instant promotionRequestedAt,
        Retired retired,                        // null until kill/retire
        Lineage lineage,
        // ------------------------------------------------------ 6b blocks (§7.3)
        Map<String, AgentEffectDisclosure> controlEffects,  // pause / kill / throttle (§6.1)
        AgentControlTracking enforcement,       // pending verb + ack/confirm tracking (D6)
        AgentLivenessView liveness,             // §10 derivations — never row recency
        List<AgentBudgetView> budgets,          // active throttle budgets (D12)
        List<AgentInterventionView> interventions,  // OPEN overlay rows (spec: openInterventions[])
        AgentPromotionView promotion,           // request + latest PROMOTION review (D10)
        List<AgentEventView> recentEvents       // last 5, event_seq desc
) {

    /** Gate row + denormalized standing (spec §7.3 / §9). */
    public record CriteriaGateView(
            String gateText,
            AgentGateRuleType ruleType,
            JsonNode predicate,
            String cadence,
            AgentGateStanding standing,
            Instant lastEvaluatedAt) {
    }

    /** Terminal block (spec §7.3) — kill vs orderly retire is Mike-flagged vocabulary (ADR D4). */
    public record Retired(Instant at, AgentRetiredReason reason, String byLabel) {
    }

    /** Re-deploy lineage (spec §7.3; EXTENSION POINT(agent-18) — no navigation UI in v1). */
    public record Lineage(UUID predecessorRegistrationId) {
    }

    /**
     * Maps the persisted aggregate to the detail DTO. The derived/joined
     * blocks ({@code recentActivity}, {@code adherence}, control effects,
     * liveness, budgets, interventions, promotion, recent events) are
     * caller-assembled — {@code AgentQueryService.detail} is the ONE mapping
     * path (deploy responses, 409 envelopes and reads all flow through it).
     */
    public static AgentDetail from(AgentRegistration reg,
                                   List<AgentOversightLink> oversightLinks,
                                   List<AgentCriteriaGate> gates,
                                   String recentActivity,
                                   Double adherence,
                                   Map<String, AgentEffectDisclosure> controlEffects,
                                   AgentControlTracking enforcement,
                                   AgentLivenessView liveness,
                                   List<AgentBudgetView> budgets,
                                   List<AgentInterventionView> interventions,
                                   AgentPromotionView promotion,
                                   List<AgentEventView> recentEvents) {
        Retired retired = reg.getRetiredAt() == null ? null
                : new Retired(reg.getRetiredAt(), reg.getRetiredReason(), reg.getRetiredByLabel());
        return new AgentDetail(
                reg.getAgentKey(),
                reg.getId(),
                reg.getDomainId(),
                reg.getName(),
                reg.getRoleDescription(),
                AgentWireStatus.project(reg.getOperationalMode(), reg.getOpenInterventionCount()),
                reg.getOperationalMode(),
                recentActivity,
                adherence,
                reg.getCapabilityId(),
                reg.getDeployedByLabel(),
                reg.getDeployedByMemberId(),
                reg.getDeployedAt(),
                reg.getDeploymentCriteria(),
                reg.getCriteriaDigest(),
                oversightLinks.stream().map(AgentOversightLink::getLabel).toList(),
                reg.getExecutionLocus(),
                reg.getSupervisoryModel(),
                reg.getOpenInterventionCount(),
                reg.getEventSeq(),
                gates.stream()
                        .map(g -> new CriteriaGateView(g.getGateText(), g.getRuleType(), g.getPredicate(),
                                g.getCadence(), g.getStanding(), g.getLastEvaluatedAt()))
                        .toList(),
                reg.getTargetProcessId(),
                reg.getTargetStepIds(),
                reg.getAgentReadinessScore(),
                reg.getReadinessSource(),
                reg.getRequiredSystemAccess(),
                reg.getRequiredDataAccess(),
                reg.getInitiativeId(),
                reg.getNotes(),
                reg.isReadOnly(),
                reg.getScheduleWindow(),
                reg.getSamplingPct(),
                reg.getMcpPrincipalId(),
                reg.getShadowEnteredAt(),
                reg.getPromotionRequestedAt(),
                retired,
                new Lineage(reg.getPredecessorRegistrationId()),
                controlEffects,
                enforcement,
                liveness,
                budgets,
                interventions,
                promotion,
                recentEvents);
    }
}
