package com.meridian.substrate.common.ai;

import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic completion request.
 */
public record CompletionRequest(
        String model,
        String systemPrompt,
        List<Message> messages,
        int maxTokens,
        double temperature,
        Map<String, Object> metadata
) {

    /**
     * A single conversation message. Carries either plain text ({@code content})
     * or a list of {@link ContentBlock}s ({@code blocks}) — block-shaped messages
     * enable the native tool-calling protocol (assistant {@code tool_use} blocks +
     * user {@code tool_result} blocks). When {@code blocks} is non-empty it takes
     * precedence over {@code content}.
     */
    public record Message(String role, String content, List<ContentBlock> blocks) {

        /** Plain-text message (the original two-arg shape; {@code blocks} is null). */
        public Message(String role, String content) {
            this(role, content, null);
        }

        public boolean hasBlocks() {
            return blocks != null && !blocks.isEmpty();
        }

        /** Plain-text user message. */
        public static Message user(String content) {
            return new Message("user", content);
        }

        /** Plain-text assistant message. */
        public static Message assistant(String content) {
            return new Message("assistant", content);
        }

        /** Block-shaped user message (e.g. {@code tool_result} blocks). */
        public static Message user(List<ContentBlock> blocks) {
            return new Message("user", null, blocks);
        }

        /** Block-shaped assistant message (e.g. echoed {@code text} + {@code tool_use} blocks). */
        public static Message assistant(List<ContentBlock> blocks) {
            return new Message("assistant", null, blocks);
        }
    }

    public static CompletionRequest of(String model, String systemPrompt,
                                        List<Message> messages, int maxTokens) {
        return new CompletionRequest(model, systemPrompt, messages, maxTokens, 0.7, Map.of());
    }
}
