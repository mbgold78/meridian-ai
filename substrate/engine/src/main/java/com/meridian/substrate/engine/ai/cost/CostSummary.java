package com.meridian.substrate.engine.ai.cost;

/**
 * Summary of cumulative LLM usage costs and token statistics.
 *
 * @param totalInputTokens    total input (prompt) tokens consumed across all calls
 * @param totalOutputTokens   total output (completion) tokens consumed across all calls
 * @param estimatedInputCost  estimated cost for input tokens in USD
 * @param estimatedOutputCost estimated cost for output tokens in USD
 * @param totalEstimatedCost  total estimated cost in USD
 * @param totalCalls          total number of LLM calls recorded
 */
public record CostSummary(
    long totalInputTokens,
    long totalOutputTokens,
    double estimatedInputCost,
    double estimatedOutputCost,
    double totalEstimatedCost,
    int totalCalls
) {}
