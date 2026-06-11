package com.meridian.substrate.engine.contract.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.ContractClauseGuard;
import com.meridian.substrate.common.domain.enums.GuardEffect;

import java.util.UUID;

/**
 * Immutable snapshot of an ACTIVE {@code contract_clause_guards} row for the
 * {@link GuardCache} (engine-spec contracts.md §4.3): exactly the first-class
 * match columns + the optional §4.4 predicate. Cached OUTSIDE any JPA session
 * — a plain record carries no lazy navigation twins, and cached state can
 * never be mutated by an entity flush. The {@code predicate} JsonNode is
 * treated as read-only by every consumer.
 */
public record GuardView(UUID id, UUID contractId, UUID commitmentId, String actionType,
                        String entityType, GuardEffect effect, JsonNode predicate,
                        String description) {

    public static GuardView of(ContractClauseGuard guard) {
        return new GuardView(guard.getId(), guard.getContractId(), guard.getCommitmentId(),
                guard.getActionType(), guard.getEntityType(), guard.getEffect(),
                guard.getPredicate(), guard.getDescription());
    }
}
