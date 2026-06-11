package com.meridian.substrate.engine.contract.monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.ContractMetricSample;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.MonitorRunOutcome;
import com.meridian.substrate.common.repo.ContractMetricSampleRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * §5.3 METRIC_THRESHOLD — "requires samples for {@code rule_spec.metricKey}
 * from ALL {@code rule_spec.requiredPartyIds} for the most recent closed
 * period; incomplete → INDETERMINATE + {@code ADHERENCE_STALE} signal (NEVER
 * computes on partial data — ADR D11); complete → {@code |a−b| > threshold} ⇒
 * BREACH_DETECTED else COMPLIANT."
 *
 * <p>The {@code ADHERENCE_STALE} needs-input signal materializes through the
 * monitor's {@code last_outcome=INDETERMINATE} projection, which
 * {@code ContractSignalService} maps to the signal class at read time (§11.2;
 * no signal table in v1) — never silent (the §13 3b zero-yield hard
 * requirement).
 *
 * <p>Flag-not-invent boundaries: the spec's imbalance formula {@code |a−b|}
 * is defined for exactly TWO required parties — more (or fewer) required
 * parties have no documented aggregation and evaluate INDETERMINATE with a
 * trace note (spec §15 #6/#8 extension-point territory). A missing/invalid
 * {@code metricKey}/{@code threshold}/{@code requiredPartyIds} rule_spec is a
 * misconfigured rule ⇒ INDETERMINATE ("unknown rule version" class, ADR D10).
 *
 * <p>Breach candidates carry the first {@code rule_spec.commitmentIds} entry
 * when present, else no commitment (the §3.2 breach column is nullable —
 * fixture vector 2 binds no commitment).
 */
@Component
public class MetricThresholdEvaluator implements MonitorEvaluator {

    private final ContractMetricSampleRepository sampleRepository;
    private final ObjectMapper objectMapper;

    public MetricThresholdEvaluator(ContractMetricSampleRepository sampleRepository,
                                    ObjectMapper objectMapper) {
        this.sampleRepository = sampleRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public MonitorRuleType ruleType() {
        return MonitorRuleType.METRIC_THRESHOLD;
    }

    @Override
    public MonitorEvaluation evaluate(ContractMonitor monitor, PeriodContext ctx) {
        ObjectNode trace = objectMapper.createObjectNode();
        trace.put("rule", MonitorRuleType.METRIC_THRESHOLD.name());

        JsonNode spec = monitor.getRuleSpec();
        String metricKey = spec != null && spec.hasNonNull("metricKey")
                ? spec.get("metricKey").asText() : null;
        Double threshold = spec != null && spec.hasNonNull("threshold") && spec.get("threshold").isNumber()
                ? spec.get("threshold").asDouble() : null;
        List<UUID> requiredParties = requiredPartyIds(spec);
        trace.put("metricKey", metricKey);
        if (threshold != null) {
            trace.put("threshold", threshold);
        }

        String period = PeriodKeys.previousPeriodKey(monitor.getCadence(), ctx.now(), ctx.zone());
        trace.put("periodKey", period);

        if (metricKey == null || metricKey.isBlank() || threshold == null
                || requiredParties.isEmpty() || period == null) {
            trace.put("note", "misconfigured rule_spec (metricKey/threshold/requiredPartyIds)"
                    + " or cadence without §5.1 period-key semantics — not evaluable (ADR D10)");
            return MonitorEvaluation.of(MonitorRunOutcome.INDETERMINATE, period, trace);
        }

        List<ContractMetricSample> samples = sampleRepository
                .findByContractIdAndMetricKeyAndPeriodKey(monitor.getContractId(), metricKey, period);
        Map<UUID, Double> byParty = new LinkedHashMap<>();
        for (ContractMetricSample s : samples) {
            byParty.put(s.getPartyId(), s.getMetricValue());
        }

        List<UUID> missing = requiredParties.stream()
                .filter(p -> !byParty.containsKey(p)).toList();
        if (!missing.isEmpty()) {
            missing.forEach(p -> trace.withArray("missingPartyIds").add(p.toString()));
            trace.put("note", "incomplete period data — NEVER computed on partial reports (ADR D11);"
                    + " needs-input signal ADHERENCE_STALE");
            return MonitorEvaluation.of(MonitorRunOutcome.INDETERMINATE, period, trace);
        }

        if (requiredParties.size() != 2) {
            trace.put("note", "imbalance |a-b| is defined for exactly two required parties (was "
                    + requiredParties.size() + ") — no documented aggregation; flag-not-invent (§15 #6/#8)");
            return MonitorEvaluation.of(MonitorRunOutcome.INDETERMINATE, period, trace);
        }

        double a = byParty.get(requiredParties.get(0));
        double b = byParty.get(requiredParties.get(1));
        double imbalance = Math.abs(a - b);
        trace.put("valueA", a);
        trace.put("valueB", b);
        trace.put("imbalance", imbalance);

        if (imbalance > threshold) {
            UUID commitmentId = MonitorBindings.boundCommitmentIds(monitor).stream()
                    .findFirst().orElse(null);
            return new MonitorEvaluation(MonitorRunOutcome.BREACH_DETECTED, period, trace,
                    List.of(new MonitorEvaluation.BreachCandidate(commitmentId, period,
                            "Metric '" + metricKey + "' imbalance " + imbalance + " exceeds threshold "
                                    + threshold + " for period " + period)),
                    List.of());
        }
        return MonitorEvaluation.of(MonitorRunOutcome.COMPLIANT, period, trace);
    }

    private static List<UUID> requiredPartyIds(JsonNode spec) {
        if (spec == null || !spec.has("requiredPartyIds") || !spec.get("requiredPartyIds").isArray()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (JsonNode id : spec.get("requiredPartyIds")) {
            try {
                ids.add(UUID.fromString(id.asText()));
            } catch (IllegalArgumentException malformed) {
                // counted as misconfiguration by the emptiness check upstream
            }
        }
        return ids;
    }
}
