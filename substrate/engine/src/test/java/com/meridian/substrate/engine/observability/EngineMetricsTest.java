package com.meridian.substrate.engine.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 3 meter inventory pin (plan §8): every {@code substrate_*} meter the
 * engine exposes — name, tag shape, units, percentile publication — asserted
 * against a {@link SimpleMeterRegistry}. The exporter side (OTLP on/off) is
 * the app module's {@code ObservabilityExportToggleTest}.
 */
class EngineMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final EngineMetrics metrics = new EngineMetrics(registry);

    @Test
    void gatewayEvaluationTimer_tagsDecisionAndMode_publishesP50P99() {
        metrics.gatewayEvaluation("WOULD_DENY", "shadow", System.nanoTime() - 1_000_000);
        metrics.gatewayEvaluation("WOULD_DENY", "shadow", System.nanoTime() - 2_000_000);
        metrics.gatewayEvaluation("ALLOW", "enforce", System.nanoTime());

        Timer shadow = registry.get(EngineMetrics.GATEWAY_EVALUATIONS)
                .tag("decision", "would_deny")
                .tag("mode", "shadow")
                .timer();
        assertThat(shadow.count()).isEqualTo(2);
        // p50/p99 percentiles are PUBLISHED (plan §8 requirement), not just recorded
        assertThat(shadow.takeSnapshot().percentileValues())
                .extracting(p -> p.percentile())
                .containsExactly(0.5, 0.99);

        Timer enforceAllow = registry.get(EngineMetrics.GATEWAY_EVALUATIONS)
                .tag("decision", "allow")
                .tag("mode", "enforce")
                .timer();
        assertThat(enforceAllow.count()).isEqualTo(1);
    }

    @Test
    void modeOffEvaluations_useTheAllowUnevaluatedDecisionTag() {
        metrics.gatewayEvaluation(EngineMetrics.DECISION_ALLOW_UNEVALUATED, "off",
                System.nanoTime());
        assertThat(registry.get(EngineMetrics.GATEWAY_EVALUATIONS)
                .tag("decision", "allow_unevaluated")
                .tag("mode", "off")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void agentGatewayDenials_tagReasonAndMode() {
        metrics.agentGatewayDenial("AGENT_LIFECYCLE_HUMAN_ONLY", "enforce");
        metrics.agentGatewayDenial("AGENT_LIFECYCLE_HUMAN_ONLY", "enforce");
        Counter counter = registry.get(EngineMetrics.AGENT_GATEWAY_DENIALS)
                .tag("reason", "AGENT_LIFECYCLE_HUMAN_ONLY")
                .tag("mode", "enforce")
                .counter();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    void monitorRuns_tagOutcomeLowercase() {
        metrics.monitorRun("BREACH_DETECTED");
        assertThat(registry.get(EngineMetrics.MONITOR_RUNS)
                .tag("outcome", "breach_detected")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void breachOpens_tagDetectedBy_andNullSafe() {
        metrics.breachOpened("MONITOR");
        metrics.breachOpened(null);
        assertThat(registry.get(EngineMetrics.BREACHES_OPENED)
                .tag("detected_by", "monitor").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(EngineMetrics.BREACHES_OPENED)
                .tag("detected_by", "unknown").counter().count()).isEqualTo(1.0);
    }

    @Test
    void cascadeActions_tagActionWireValue() {
        metrics.cascadeAction("decline");
        metrics.cascadeAction("resolve-looks-good");
        assertThat(registry.get(EngineMetrics.CASCADE_ACTIONS)
                .tag("action", "decline").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(EngineMetrics.CASCADE_ACTIONS)
                .tag("action", "resolve-looks-good").counter().count()).isEqualTo(1.0);
    }

    @Test
    void agentVerbs_tagVerbWireValue() {
        metrics.agentVerb("deployed");
        metrics.agentVerb("paused");
        metrics.agentVerb("paused");
        assertThat(registry.get(EngineMetrics.AGENT_VERBS)
                .tag("verb", "deployed").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(EngineMetrics.AGENT_VERBS)
                .tag("verb", "paused").counter().count()).isEqualTo(2.0);
    }

    @Test
    void diagnosticRuns_tagTerminalStatus_committedAndFilteredAccumulate() {
        metrics.diagnosticRun("ZERO_YIELD");
        metrics.diagnosticRun("COMPLETED");
        metrics.diagnosticFindingsCommitted(4);
        metrics.diagnosticFindingsCommitted(3);
        metrics.diagnosticCandidatesFiltered(2);

        assertThat(registry.get(EngineMetrics.DIAGNOSTIC_RUNS)
                .tag("status", "zero_yield").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(EngineMetrics.DIAGNOSTIC_RUNS)
                .tag("status", "completed").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(EngineMetrics.DIAGNOSTIC_FINDINGS_COMMITTED)
                .counter().count()).isEqualTo(7.0);
        assertThat(registry.get(EngineMetrics.DIAGNOSTIC_CANDIDATES_FILTERED)
                .counter().count()).isEqualTo(2.0);
    }

    @Test
    void zeroCommittedOrFiltered_registersNoCounter() {
        metrics.diagnosticFindingsCommitted(0);
        metrics.diagnosticCandidatesFiltered(0);
        assertThat(registry.find(EngineMetrics.DIAGNOSTIC_FINDINGS_COMMITTED).counter()).isNull();
        assertThat(registry.find(EngineMetrics.DIAGNOSTIC_CANDIDATES_FILTERED).counter()).isNull();
    }

    @Test
    void llmUsage_recordsCallsTokensByDirectionAndCost() {
        metrics.llmUsage("claude-opus-4-8", 1_000, 500, 0.0525);
        metrics.llmUsage("claude-opus-4-8", 2_000, 1_000, 0.105);

        assertThat(registry.get(EngineMetrics.LLM_CALLS)
                .tag("model", "claude-opus-4-8").counter().count()).isEqualTo(2.0);
        assertThat(registry.get(EngineMetrics.LLM_TOKENS)
                .tag("model", "claude-opus-4-8").tag("direction", "input")
                .counter().count()).isEqualTo(3_000.0);
        assertThat(registry.get(EngineMetrics.LLM_TOKENS)
                .tag("model", "claude-opus-4-8").tag("direction", "output")
                .counter().count()).isEqualTo(1_500.0);
        Counter cost = registry.get(EngineMetrics.LLM_COST_ESTIMATED)
                .tag("model", "claude-opus-4-8").counter();
        assertThat(cost.count()).isCloseTo(0.1575, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(cost.getId().getBaseUnit()).isEqualTo("USD");
    }

    @Test
    void llmUsage_nullOrBlankModel_fallsBackToUnknownTag() {
        metrics.llmUsage(null, 10, 5, 0.0);
        assertThat(registry.get(EngineMetrics.LLM_CALLS)
                .tag("model", "unknown").counter().count()).isEqualTo(1.0);
        // zero cost ⇒ no cost counter registered
        assertThat(registry.find(EngineMetrics.LLM_COST_ESTIMATED).counter()).isNull();
    }
}
