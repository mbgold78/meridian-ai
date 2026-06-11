package com.meridian.substrate.engine.contract.gateway;

import com.meridian.substrate.common.repo.ContractMetricSampleRepository;
import com.meridian.substrate.engine.contract.ContractProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.util.UUID;

/**
 * §4.4 metric-leaf resolution bound to one contract — delegates to the
 * single-sourced {@link LatestClosedMetricValue} (latest complete-period
 * value from {@code contract_metric_samples}; multi-party values resolve
 * empty ⇒ INDETERMINATE, flag-not-invent per spec §15 #6/#8).
 *
 * <p>Shared by the {@code check_action} advisory and the real PDP so all
 * metric leaves agree; the 3b decline-trigger arming pass resolves through
 * the same {@link LatestClosedMetricValue}.
 */
@Component
public class ContractMetricLeafResolver {

    private final ContractMetricSampleRepository metricSampleRepository;
    private final ContractProperties properties;
    private final Clock clock;

    public ContractMetricLeafResolver(ContractMetricSampleRepository metricSampleRepository,
                                      ContractProperties properties,
                                      Clock clock) {
        this.metricSampleRepository = metricSampleRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /** Resolver for metric leaves of guards belonging to {@code contractId}. */
    public TinyDslEvaluator.MetricResolver forContract(UUID contractId) {
        return metricKey -> LatestClosedMetricValue.resolve(
                metricSampleRepository.findByContractIdAndMetricKey(contractId, metricKey),
                clock.instant(), ZoneId.of(properties.getClock().getZone()));
    }
}
