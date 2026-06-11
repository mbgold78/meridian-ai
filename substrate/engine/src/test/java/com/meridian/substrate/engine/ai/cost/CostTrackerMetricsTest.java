package com.meridian.substrate.engine.ai.cost;

import com.meridian.substrate.common.ai.config.LlmProperties;
import com.meridian.substrate.engine.observability.EngineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Stage 3: the S12 CostTracker → {@code substrate_llm_*} Micrometer bridge —
 * every {@code recordUsage} feeds the calls/tokens/cost counters with the
 * configured {@code substrate.llm.cost.*} rates.
 */
class CostTrackerMetricsTest {

    @Test
    void recordUsage_feedsLlmCounters_withConfiguredRates() {
        LlmProperties properties = new LlmProperties();
        properties.getCost().setInputRatePerMillion(3.0);
        properties.getCost().setOutputRatePerMillion(15.0);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CostTracker tracker = new CostTracker(properties, new EngineMetrics(registry));

        tracker.recordUsage("model-a", 1_000_000, 2_000_000);
        tracker.recordUsage("model-a", 500_000, 0);

        assertThat(registry.get(EngineMetrics.LLM_CALLS)
                .tag("model", "model-a").counter().count()).isEqualTo(2.0);
        assertThat(registry.get(EngineMetrics.LLM_TOKENS)
                .tag("model", "model-a").tag("direction", "input")
                .counter().count()).isEqualTo(1_500_000.0);
        assertThat(registry.get(EngineMetrics.LLM_TOKENS)
                .tag("model", "model-a").tag("direction", "output")
                .counter().count()).isEqualTo(2_000_000.0);
        // (1.5M / 1M) * $3 + (2M / 1M) * $15 = $34.50 — same math as getSummary()
        assertThat(registry.get(EngineMetrics.LLM_COST_ESTIMATED)
                .tag("model", "model-a").counter().count())
                .isCloseTo(34.5, within(1e-9));
    }

    @Test
    void legacyConstructor_recordsNothingButStillAccumulatesTotals() {
        LlmProperties properties = new LlmProperties();
        CostTracker tracker = new CostTracker(properties);   // pre-Stage-3 signature → sink
        tracker.recordUsage("model-a", 10, 5);
        assertThat(tracker.getSummary().totalCalls()).isEqualTo(1);
    }
}
