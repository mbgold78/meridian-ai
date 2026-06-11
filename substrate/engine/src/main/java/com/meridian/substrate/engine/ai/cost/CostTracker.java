package com.meridian.substrate.engine.ai.cost;

import com.meridian.substrate.common.ai.config.LlmProperties;
import com.meridian.substrate.engine.observability.EngineMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe service tracking cumulative LLM token usage and estimated costs.
 * Uses atomic operations to safely accumulate totals from concurrent requests.
 * Cost rates come from {@code substrate.llm.cost.*} (externalized — absent keys
 * read as 0.0; token counts still accumulate).
 *
 * <p><b>Port note (port-map §2.3 / S12):</b> homepoc never called
 * {@link #recordUsage} — {@code /llm/cost-summary} always returned zeros. In
 * substrate it is wired at every engine LLM call site: the tool-calling
 * orchestrator (per iteration), {@code AssistiveModeService}'s single-shot path,
 * and the {@code PromptRegressionHarness}. The chat/streaming call sites land
 * with chunk C5 (ChatService), which also persists per-interaction usage onto
 * {@code chat_interactions} token columns; this tracker remains the in-memory
 * process-lifetime aggregate behind {@code GET /llm/cost-summary}.
 *
 * <p><b>Stage 3 observability bridge (plan §8):</b> every {@link #recordUsage}
 * call also feeds the {@code substrate_llm_*} Micrometer counters via
 * {@link EngineMetrics#llmUsage} — token/cost telemetry survives restarts in
 * the engagement's metrics backend even though these atomics do not.
 */
@Service
public class CostTracker {

    private static final Logger log = LoggerFactory.getLogger(CostTracker.class);

    private static final double TOKENS_PER_MILLION = 1_000_000.0;

    private final LlmProperties llmProperties;
    private final EngineMetrics metrics;

    private final AtomicLong totalInputTokens = new AtomicLong(0);
    private final AtomicLong totalOutputTokens = new AtomicLong(0);
    private final AtomicInteger totalCalls = new AtomicInteger(0);

    /** Spring constructor (Stage 3 observability — metrics optional so partial slices wire). */
    @Autowired
    public CostTracker(LlmProperties llmProperties, ObjectProvider<EngineMetrics> metrics) {
        this(llmProperties, metrics.getIfAvailable(EngineMetrics::noop));
    }

    public CostTracker(LlmProperties llmProperties, EngineMetrics metrics) {
        this.llmProperties = llmProperties;
        this.metrics = metrics;
    }

    /** Pre-Stage-3 signature, kept for hand-constructed trackers (tests): metrics → sink. */
    public CostTracker(LlmProperties llmProperties) {
        this(llmProperties, EngineMetrics.noop());
    }

    /**
     * Record token usage from a single LLM call, adding to running totals.
     *
     * @param model        the model identifier used for the call
     * @param inputTokens  number of input tokens consumed
     * @param outputTokens number of output tokens consumed
     */
    public void recordUsage(String model, int inputTokens, int outputTokens) {
        totalInputTokens.addAndGet(inputTokens);
        totalOutputTokens.addAndGet(outputTokens);
        totalCalls.incrementAndGet();

        metrics.llmUsage(model, inputTokens, outputTokens,
                estimatedCost(inputTokens, outputTokens));

        log.debug("Recorded LLM usage [model={}, input={}, output={}, cumulativeInput={}, cumulativeOutput={}]",
                model, inputTokens, outputTokens,
                totalInputTokens.get(), totalOutputTokens.get());
    }

    /**
     * Retrieve a summary of cumulative LLM usage costs.
     *
     * @return the current cost summary including token counts and estimated costs
     */
    public CostSummary getSummary() {
        long inputTokens = totalInputTokens.get();
        long outputTokens = totalOutputTokens.get();
        int calls = totalCalls.get();

        double inputRate = llmProperties.getCost().getInputRatePerMillion();
        double outputRate = llmProperties.getCost().getOutputRatePerMillion();

        double estimatedInputCost = (inputTokens / TOKENS_PER_MILLION) * inputRate;
        double estimatedOutputCost = (outputTokens / TOKENS_PER_MILLION) * outputRate;
        double totalEstimatedCost = estimatedInputCost + estimatedOutputCost;

        return new CostSummary(
                inputTokens,
                outputTokens,
                estimatedInputCost,
                estimatedOutputCost,
                totalEstimatedCost,
                calls
        );
    }

    /** Per-call estimate with the same configured rates {@link #getSummary} uses. */
    private double estimatedCost(int inputTokens, int outputTokens) {
        double inputRate = llmProperties.getCost().getInputRatePerMillion();
        double outputRate = llmProperties.getCost().getOutputRatePerMillion();
        return (inputTokens / TOKENS_PER_MILLION) * inputRate
                + (outputTokens / TOKENS_PER_MILLION) * outputRate;
    }
}
