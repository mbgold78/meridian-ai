package com.meridian.substrate.engine.contract.read;

import com.meridian.substrate.common.domain.ContractMetricSample;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code POST /contracts/{id}/metric-samples} response row (engine-spec
 * contracts.md §7.2/§5.5) — the §3.2 table-11 columns, camelCased. The row is
 * the ADR D13 4-part-unique upsert; submission history lives in
 * {@code contract_events} ({@code METRIC_REPORTED}).
 */
public record MetricSampleResponse(
        UUID id,
        UUID contractId,
        UUID partyId,
        String metricKey,
        double metricValue,
        String periodKey,
        String reportedBy,
        Instant reportedAt
) {

    public static MetricSampleResponse from(ContractMetricSample s) {
        return new MetricSampleResponse(s.getId(), s.getContractId(), s.getPartyId(),
                s.getMetricKey(), s.getMetricValue(), s.getPeriodKey(), s.getReportedBy(),
                s.getReportedAt());
    }
}
