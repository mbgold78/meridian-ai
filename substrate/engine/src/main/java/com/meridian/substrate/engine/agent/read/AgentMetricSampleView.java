package com.meridian.substrate.engine.agent.read;

import com.meridian.substrate.common.domain.AgentMetricSample;

import java.time.Instant;
import java.util.UUID;

/**
 * One metric sample ({@code GET /agents/{idOrKey}/metrics?metricKey&since} —
 * engine-spec agents.md §7.1/§9.2): push-based, idempotently upserted on
 * {@code (agent, metric_key, period_key)}; the engine computes nothing it was
 * not told (EXTENSION POINT(agent-6): no adherence rubric exists).
 */
public record AgentMetricSampleView(
        UUID id,
        String metricKey,
        double metricValue,
        String periodKey,
        String reportedByPrincipal,
        Instant reportedAt) {

    public static AgentMetricSampleView from(AgentMetricSample s) {
        return new AgentMetricSampleView(s.getId(), s.getMetricKey(), s.getMetricValue(),
                s.getPeriodKey(), s.getReportedByPrincipal(), s.getReportedAt());
    }
}
