package com.meridian.substrate.engine.contract.read;

import com.meridian.substrate.common.domain.enums.ContractStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code GET /contracts} list row (engine-spec contracts.md §7.1):
 * {@code {id, contractKey, type, title, status, proposedAt, estimatedValue,
 * partyLabels[], linkedEntities[], openBreachCount}}.
 *
 * <p>{@code estimatedValue} is the display string and is nullable — null must
 * serialize as {@code null} (vector 2 has no estimatedValue; the UI hides the
 * value aside — §14.1).
 */
public record ContractCardResponse(
        UUID id,
        String contractKey,
        String type,
        String title,
        ContractStatus status,
        Instant proposedAt,
        String estimatedValue,
        List<String> partyLabels,
        List<LinkedEntityRef> linkedEntities,
        long openBreachCount
) {
}
