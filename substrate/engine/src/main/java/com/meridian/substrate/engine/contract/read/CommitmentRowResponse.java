package com.meridian.substrate.engine.contract.read;

import com.meridian.substrate.common.domain.enums.CommitmentComplianceState;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code get_commitments} row (engine-spec contracts.md §8.2): commitment
 * rows with state, joined to their contract/party identity so an agent can
 * filter by {@code contract_key}, {@code party}, or {@code compliance_state}
 * without a second lookup.
 */
public record CommitmentRowResponse(
        UUID id,
        UUID contractId,
        String contractKey,
        UUID partyId,
        String partyLabel,
        String text,
        Instant dueBy,
        String cadence,
        CommitmentComplianceState complianceState,
        Instant lastEvaluatedAt,
        int sequenceOrder
) {
}
