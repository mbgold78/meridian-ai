package com.meridian.substrate.common.ai;

import java.util.List;
import java.util.Map;

/**
 * Completion request that includes tool definitions for tool-calling.
 */
public record ToolCallRequest(
        String model,
        String systemPrompt,
        List<CompletionRequest.Message> messages,
        List<ToolDefinition> tools,
        int maxTokens,
        double temperature,
        Map<String, Object> metadata
) {

    public record ToolDefinition(
            String name,
            String description,
            Map<String, Object> inputSchema
    ) {}

    public static ToolCallRequest of(String model, String systemPrompt,
                                      List<CompletionRequest.Message> messages,
                                      List<ToolDefinition> tools, int maxTokens) {
        return new ToolCallRequest(model, systemPrompt, messages, tools, maxTokens, 0.7, Map.of());
    }
}
