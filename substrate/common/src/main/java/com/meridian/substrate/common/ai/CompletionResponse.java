package com.meridian.substrate.common.ai;

/**
 * Provider-agnostic completion response.
 */
public record CompletionResponse(
        String id,
        String content,
        String model,
        String stopReason,
        TokenUsage usage
) {}
