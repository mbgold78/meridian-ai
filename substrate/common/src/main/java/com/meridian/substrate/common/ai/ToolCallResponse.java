package com.meridian.substrate.common.ai;

import java.util.List;

/**
 * Response from a tool-calling completion. Contains text and tool-use blocks.
 *
 * <p>Content blocks are the shared top-level {@link ContentBlock} type so the
 * orchestrator can echo an assistant turn's blocks straight back into
 * {@link CompletionRequest.Message#blocks()} for the native tool-result protocol.
 */
public record ToolCallResponse(
        String id,
        String model,
        String stopReason,
        List<ContentBlock> content,
        TokenUsage usage
) {}
