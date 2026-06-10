package com.meridian.substrate.common.ai;

import reactor.core.publisher.Flux;

/**
 * Provider-agnostic LLM client. Implementations wrap a specific vendor SDK
 * (Anthropic, OpenAI, Gemini, Azure OpenAI, Bedrock, Ollama, …) and are
 * selected at runtime via {@code substrate.llm.provider}.
 */
public interface LlmClient {

    /** Synchronous completion request. */
    CompletionResponse complete(CompletionRequest request);

    /** Streaming completion returning chunks via Reactor Flux. */
    Flux<CompletionChunk> completeStream(CompletionRequest request);

    /** Completion with tool calling. Returns tool-use blocks alongside text. */
    ToolCallResponse completeWithTools(ToolCallRequest request);

    /**
     * Whether this client is operational. Returns {@code false} in degraded mode
     * (e.g. no API key configured) — calls will then fail with
     * {@link LlmUnavailableException} instead of preventing application boot.
     */
    default boolean isAvailable() {
        return true;
    }
}
