package com.meridian.substrate.engine.cascade.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.CascadeNotification;
import com.meridian.substrate.common.domain.enums.CascadeNotificationType;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code GET /cascades/notifications} row (engine-spec cascade.md §6.1) — the
 * in-app attention feed over the transactional outbox (ADR D10). {@code type}
 * serializes kebab-case; {@code payload} is the §8 JSON
 * {@code {cascadeId, depth, round, actionId?, headline, excerpt}} passed
 * through verbatim. Internal outbox mechanics ({@code dedupeKey},
 * {@code dispatchedAt}) are not part of the read surface.
 */
public record CascadeNotificationDto(
        UUID id,
        UUID cascadeId,
        UUID actionId,
        Integer depth,
        CascadeNotificationType type,
        UUID recipientMemberId,
        String recipientLabel,
        JsonNode payload,
        Instant readAt,
        Instant createdAt
) {

    public static CascadeNotificationDto from(CascadeNotification row) {
        return new CascadeNotificationDto(
                row.getId(),
                row.getCascadeId(),
                row.getActionId(),
                row.getDepth(),
                row.getNotificationType(),
                row.getRecipientMemberId(),
                row.getRecipientLabel(),
                row.getPayload(),
                row.getReadAt(),
                row.getCreatedAt());
    }
}
