package com.meridian.substrate.common.ai;

/**
 * Token usage statistics from an LLM call.
 */
public record TokenUsage(
        int inputTokens,
        int outputTokens
) {
    /**
     * Total tokens consumed.
     */
    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}
