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
 * <p>Also the resolution rule for AGENT criteria-gate metric leaves
 * (engine-spec agents.md §9.2; ADR-003 D9 — "metric leaves resolve the latest
 * complete-period value from {@code agent_metric_samples}"): the agent engine
 * reuses {@link #resolve(List, Instant, ZoneId)} over {@link PeriodValue}
 * projections of its own samples — one DSL, one leaf-resolution semantic,
 * never forked.
 *
 * <p>Flag-not-invent boundary (spec §15 #6/#8): when SEVERAL parties reported
 * the metric for the latest closed period, no aggregation rule is documented —
 * the leaf resolves empty (predicate INDETERMINATE) rather than inventing an
 * average. Period keys of different cadences are not lexicographically
 * comparable; their end dates are (see {@link PeriodKeys#periodEnd}).
 */
public final class LatestClosedMetricValue {

    /** Source-agnostic (periodKey, value) projection of one metric sample. */
    public record PeriodValue(String periodKey, double value) {
    }

    private LatestClosedMetricValue() {
    }

    /** Latest closed-period single-party value; empty ⇒ INDETERMINATE leaf. */
    public static Optional<Double> resolve(List<ContractMetricSample> samples,
                                           Instant now, ZoneId zone) {
        return resolveValues(samples.stream()
                .map(s -> new PeriodValue(s.getPeriodKey(), s.getMetricValue()))
                .toList(), now, zone);
    }

    /**
     * Core selection rule over source-agnostic samples (contract AND agent
     * metric leaves; distinct name — the {@code List} overloads would erase
     * identically): latest closed-period end wins; several samples sharing
     * that end ⇒ empty (undocumented aggregation semantics — INDETERMINATE).
     */
    public static Optional<Double> resolveValues(List<PeriodValue> samples, Instant now, ZoneId zone) {
        Optional<LocalDate> latestClosedEnd = samples.stream()
                .map(PeriodValue::periodKey)
                .filter(pk -> PeriodKeys.isClosed(pk, now, zone))
                .map(pk -> PeriodKeys.periodEnd(pk).orElse(null))
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder());
        if (latestClosedEnd.isEmpty()) {
            return Optional.empty();
        }
        List<PeriodValue> latest = samples.stream()
                .filter(s -> PeriodKeys.periodEnd(s.periodKey())
                        .map(latestClosedEnd.get()::equals).orElse(false))
                .toList();
        if (latest.size() != 1) {
            return Optional.empty();                 // multi-party value: undocumented semantics
        }
        return Optional.of(latest.get(0).value());
    }
}
