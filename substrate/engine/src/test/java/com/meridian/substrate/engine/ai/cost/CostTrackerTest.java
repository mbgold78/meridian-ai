package com.meridian.substrate.engine.ai.cost;

import com.meridian.substrate.common.ai.config.LlmProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CostTrackerTest {

    @Test
    void accumulatesUsageAndComputesCostsFromConfiguredRates() {
        LlmProperties properties = new LlmProperties();
        properties.getCost().setInputRatePerMillion(3.0);
        properties.getCost().setOutputRatePerMillion(15.0);
        CostTracker tracker = new CostTracker(properties);

        tracker.recordUsage("model-a", 1_000_000, 2_000_000);
        tracker.recordUsage("model-b", 500_000, 0);

        CostSummary summary = tracker.getSummary();
        assertThat(summary.totalCalls()).isEqualTo(2);
        assertThat(summary.totalInputTokens()).isEqualTo(1_500_000);
        assertThat(summary.totalOutputTokens()).isEqualTo(2_000_000);
        assertThat(summary.estimatedInputCost()).isCloseTo(4.5, within(1e-9));
        assertThat(summary.estimatedOutputCost()).isCloseTo(30.0, within(1e-9));
        assertThat(summary.totalEstimatedCost()).isCloseTo(34.5, within(1e-9));
    }

    @Test
    void missingRatesReadAsZeroCost_butTokensStillAccumulate() {
        // Externalized-rates contract: absent substrate.llm.cost.* keys must not
        // break anything — costs read 0.0, token counts remain truthful.
        CostTracker tracker = new CostTracker(new LlmProperties());
        tracker.recordUsage("m", 100, 50);

        CostSummary summary = tracker.getSummary();
        assertThat(summary.totalInputTokens()).isEqualTo(100);
        assertThat(summary.totalOutputTokens()).isEqualTo(50);
        assertThat(summary.totalEstimatedCost()).isEqualTo(0.0);
        assertThat(summary.totalCalls()).isEqualTo(1);
    }
}
