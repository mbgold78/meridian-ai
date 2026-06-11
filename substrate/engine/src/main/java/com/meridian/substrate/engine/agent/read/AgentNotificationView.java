package com.meridian.substrate.engine.agent.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.AgentNotification;
import com.meridian.substrate.common.domain.enums.AgentNotificationType;

import java.time.Instant;
import java.util.UUID;

/**
 * One outbox row ({@code GET /agents/notifications?recipientId&unreadOnly} +
 * mark-read sibling — engine-spec agents.md §7.1/§13.4; ADR-003 D13).
 */
public record AgentNotificationView(
        UUID id,
        UUID domainId,
        UUID agentRegistrationId,
        UUID eventId,
        AgentNotificationType notificationType,
        UUID recipientMemberId,
        String recipientLabel,
        String tone,
        JsonNode payload,
        Instant createdAt,
        Instant readAt) {

    public static AgentNotificationView from(AgentNotification n) {
        return new AgentNotificationView(n.getId(), n.getDomainId(), n.getAgentRegistrationId(),
                n.getEventId(), n.getNotificationType(), n.getRecipientMemberId(),
                n.getRecipientLabel(), n.getTone(), n.getPayload(), n.getCreatedAt(),
                n.getReadAt());
    }
}
