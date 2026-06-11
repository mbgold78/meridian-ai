package com.meridian.substrate.engine.contract.read;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.ContractBreach;
import com.meridian.substrate.common.domain.enums.BreachDetectedBy;
import com.meridian.substrate.common.domain.enums.BreachResolution;
import com.meridian.substrate.common.domain.enums.BreachStatus;
import com.meridian.substrate.common.domain.enums.Severity;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code GET /contracts/{id}/breaches} row (engine-spec contracts.md §7.1):
 * all columns of the §3.2 table-12 sketch, camelCased (audit base columns
 * included as {@code createdAt}/{@code updatedAt}).
 */
public record BreachResponse(
        UUID id,
        UUID contractId,
        UUID commitmentId,
        UUID monitorId,
        String periodKey,
        BreachStatus status,
        Severity severity,
        String summary,
        Instant detectedAt,
        BreachDetectedBy detectedBy,
        Instant lastConfirmedAt,
        Instant surfacedAt,
        String acknowledgedBy,
        Instant acknowledgedAt,
        BreachResolution resolution,
        String resolutionActor,
        String resolutionRationale,
        JsonNode remediationEvidenceRefs,
        Instant createdAt,
        Instant updatedAt
) {

    public static BreachResponse from(ContractBreach b) {
        return new BreachResponse(b.getId(), b.getContractId(), b.getCommitmentId(),
                b.getMonitorId(), b.getPeriodKey(), b.getStatus(), b.getSeverity(),
                b.getSummary(), b.getDetectedAt(), b.getDetectedBy(), b.getLastConfirmedAt(),
                b.getSurfacedAt(), b.getAcknowledgedBy(), b.getAcknowledgedAt(),
                b.getResolution(), b.getResolutionActor(), b.getResolutionRationale(),
                b.getRemediationEvidenceRefs(), b.getCreatedAt(), b.getUpdatedAt());
    }
}
