package com.meridian.substrate.engine.agent.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.AgentIntervention;
import com.meridian.substrate.common.domain.enums.AgentInterventionResolution;
import com.meridian.substrate.common.domain.enums.AgentInterventionStatus;
import com.meridian.substrate.common.domain.enums.AgentInterventionTrigger;

import java.time.Instant;
import java.util.UUID;

/**
 * One intervention row (detail DTO {@code interventions[]} + the M11/M12/M13
 * REST responses — engine-spec agents.md §5.1/§7.3; ADR-003 D4): the
 * attention OVERLAY dimension — the agent typically keeps operating while one
 * is open; resolution is a separate, attributed human act. Never
 * error-colored (spec non-negotiable #2).
 */
public record AgentInterventionView(
        UUID id,
        AgentInterventionStatus status,
        AgentInterventionTrigger triggerKind,
        String sourceKey,
        String reason,
        JsonNode evidenceRefs,
        String openedBy,
        Instant openedAt,
        String acknowledgedBy,
        Instant acknowledgedAt,
        String resolvedBy,
        Instant resolvedAt,
        AgentInterventionResolution resolution,
        String resolutionNote) {

    public static AgentInterventionView from(AgentIntervention i) {
        return new AgentInterventionView(i.getId(), i.getStatus(), i.getTriggerKind(),
                i.getSourceKey(), i.getReason(), i.getEvidenceRefs(), i.getOpenedByLabel(),
                i.getOpenedAt(), i.getAcknowledgedByLabel(), i.getAcknowledgedAt(),
                i.getResolvedByLabel(), i.getResolvedAt(), i.getResolution(),
                i.getResolutionNote());
    }
}
