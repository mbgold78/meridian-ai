package com.meridian.substrate.engine.agent.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.AgentStatusEvent;
import com.meridian.substrate.common.domain.enums.AgentEventType;
import com.meridian.substrate.common.domain.enums.ContractActorType;

import java.time.Instant;
import java.util.UUID;

/**
 * One audit-timeline row ({@code GET /agents/{idOrKey}/events}, event_seq
 * asc — engine-spec agents.md §7.1) and the detail DTO's
 * {@code recentEvents[5]} shape (§7.3). The append-only
 * {@code agent_status_events} log is the audit trail; this is its read
 * projection — every event carries its attributed actor (invariant I3) and,
 * where mirrored (§11), the decision-log hash for cross-navigation.
 */
public record AgentEventView(
        UUID id,
        int eventSeq,
        AgentEventType eventType,
        ContractActorType actorType,
        UUID actorMemberId,
        String actorLabel,
        String reason,
        JsonNode payload,
        JsonNode evidenceRefs,
        Instant occurredAt,
        String decisionLogHash) {

    public static AgentEventView from(AgentStatusEvent e) {
        return new AgentEventView(e.getId(), e.getEventSeq(), e.getEventType(), e.getActorType(),
                e.getActorMemberId(), e.getActorLabel(), e.getReason(), e.getPayload(),
                e.getEvidenceRefs(), e.getOccurredAt(), e.getDecisionLogHash());
    }
}
