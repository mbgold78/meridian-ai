package com.meridian.substrate.engine.contract.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.ContractAttestation;
import com.meridian.substrate.common.domain.enums.ComplianceClaim;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code GET /contracts/{id}/attestations} row (engine-spec contracts.md
 * §7.1) — the §3.2 table-10 columns, camelCased. The row is the ADR D13
 * latest-wins upsert; submission history lives in {@code contract_events}.
 */
public record AttestationResponse(
        UUID id,
        UUID commitmentId,
        UUID contractId,
        UUID partyId,
        String periodKey,
        String attestedBy,
        Instant attestedAt,
        ComplianceClaim complianceClaim,
        String statement,
        JsonNode evidenceRefs,
        String submittedVia
) {

    public static AttestationResponse from(ContractAttestation a) {
        return new AttestationResponse(a.getId(), a.getCommitmentId(), a.getContractId(),
                a.getPartyId(), a.getPeriodKey(), a.getAttestedBy(), a.getAttestedAt(),
                a.getComplianceClaim(), a.getStatement(), a.getEvidenceRefs(), a.getSubmittedVia());
    }
}
