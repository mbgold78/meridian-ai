package com.meridian.substrate.engine.contract.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.ContractEvent;
import com.meridian.substrate.common.domain.enums.ContractActorType;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code GET /contracts/{id}/events} row (engine-spec contracts.md §7.1):
 * append-only audit timeline, ascending {@code occurredAt}.
 */
public record ContractEventResponse(
        UUID id,
        String eventType,
        UUID refId,
        ContractActorType actorType,
        String actorId,
        JsonNode payload,
        Instant occurredAt
) {

    public static ContractEventResponse from(ContractEvent e) {
        return new ContractEventResponse(e.getId(), e.getEventType(), e.getRefId(),
                e.getActorType(), e.getActorId(), e.getPayload(), e.getOccurredAt());
    }
}
