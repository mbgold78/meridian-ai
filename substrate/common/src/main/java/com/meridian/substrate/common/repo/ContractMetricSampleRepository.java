package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.ContractMetricSample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Push-based metric samples (engine-spec contracts.md §3.4; ADR D11).
 * Idempotent upsert on
 * {@code UNIQUE(contract_id, party_id, metric_key, period_key)} (§10.5:
 * catch unique-violation → re-read → update, one optimistic retry).
 */
@Repository
public interface ContractMetricSampleRepository extends JpaRepository<ContractMetricSample, UUID> {

    /**
     * All parties' samples for one metric+period — the METRIC_THRESHOLD
     * evaluator's completeness check input (§5.3).
     */
    List<ContractMetricSample> findByContractIdAndMetricKeyAndPeriodKey(
            UUID contractId, String metricKey, String periodKey);

    /**
     * All periods' samples for one metric — input to the §4.4 metric-leaf
     * resolution ("latest complete-period value"), shared by the gateway
     * advisory path and the 3b decline-trigger arming pass.
     */
    List<ContractMetricSample> findByContractIdAndMetricKey(UUID contractId, String metricKey);
}
