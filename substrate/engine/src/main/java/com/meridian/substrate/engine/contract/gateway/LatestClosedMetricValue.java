package com.meridian.substrate.engine.contract.gateway;

import com.meridian.substrate.common.domain.ContractMetricSample;
import com.meridian.substrate.engine.contract.monitor.PeriodKeys;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * §4.4 metric-leaf resolution, single-sourced: "resolves the latest
 * complete-period value from {@code contract_metric_samples}". Shared by the
 * {@code check_action} advisory PDP dry-run (3a), the 3b decline-trigger
 * arming pass, and the 3c gateway — all three must agree byte-for-byte on
 * what a metric leaf sees.
 *
 * <p>Flag-not-invent boundary (spec §15 #6/#8): when SEVERAL parties reported
 * the metric for the latest closed period, no aggregation rule is documented —
 * the leaf resolves empty (predicate INDETERMINATE) rather than inventing an
 * average. Period keys of different cadences are not lexicographically
 * comparable; their end dates are (see {@link PeriodKeys#periodEnd}).
 */
public final class LatestClosedMetricValue {

    private LatestClosedMetricValue() {
    }

    /** Latest closed-period single-party value; empty ⇒ INDETERMINATE leaf. */
    public static Optional<Double> resolve(List<ContractMetricSample> samples,
                                           Instant now, ZoneId zone) {
        Optional<LocalDate> latestClosedEnd = samples.stream()
                .map(ContractMetricSample::getPeriodKey)
                .filter(pk -> PeriodKeys.isClosed(pk, now, zone))
                .map(pk -> PeriodKeys.periodEnd(pk).orElse(null))
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder());
        if (latestClosedEnd.isEmpty()) {
            return Optional.empty();
        }
        List<ContractMetricSample> latest = samples.stream()
                .filter(s -> PeriodKeys.periodEnd(s.getPeriodKey())
                        .map(latestClosedEnd.get()::equals).orElse(false))
                .toList();
        if (latest.size() != 1) {
            return Optional.empty();                 // multi-party value: undocumented semantics
        }
        return Optional.of(latest.get(0).getMetricValue());
    }
}
