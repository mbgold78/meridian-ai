package com.meridian.substrate.engine.contract.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.ContractEnforcementDecision;
import com.meridian.substrate.common.domain.enums.GatewayDecision;
import com.meridian.substrate.common.domain.enums.PrincipalType;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code GET /contracts/{id}/decisions} row (engine-spec contracts.md §7.1) —
 * the §3.2 table-14 PDP log columns, camelCased. Rows are written by the 3c
 * gateway ({@code EnforcementDecisionWriter}); 3a serves the (empty until
 * then) read lane.
 */
public record EnforcementDecisionResponse(
        UUID id,
        UUID domainId,
        String actionType,
        PrincipalType actorType,
        String actorId,
        GatewayDecision decision,
        JsonNode contractIds,
        JsonNode clauseIds,
        String dispatchId,
        String reason,
        Integer latencyMs,
        String mode,
        Instant decidedAt
) {

    public static EnforcementDecisionResponse from(ContractEnforcementDecision d) {
        return new EnforcementDecisionResponse(d.getId(), d.getDomainId(), d.getActionType(),
                d.getActorType(), d.getActorId(), d.getDecision(), d.getContractIds(),
                d.getClauseIds(), d.getDispatchId(), d.getReason(), d.getLatencyMs(),
                d.getMode(), d.getDecidedAt());
    }
}
