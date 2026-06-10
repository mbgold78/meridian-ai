package com.meridian.substrate.engine.ai.cost;

import com.meridian.substrate.common.ai.config.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
@Service
public class CostTracker {

    private static final Logger log = LoggerFactory.getLogger(CostTracker.class);

    private static final double TOKENS_PER_MILLION = 1_000_000.0;

    private final LlmProperties llmProperties;

    private final AtomicLong totalInputTokens = new AtomicLong(0);
    private final AtomicLong totalOutputTokens = new AtomicLong(0);
    private final AtomicInteger totalCalls = new AtomicInteger(0);

    public CostTracker(LlmProperties llmProperties) {
        this.llmProperties = llmProperties;
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
}
