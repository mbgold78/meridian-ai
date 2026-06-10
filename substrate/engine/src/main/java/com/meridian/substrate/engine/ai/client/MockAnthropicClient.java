package com.meridian.substrate.engine.ai.client;

import com.meridian.substrate.common.ai.CompletionChunk;
import com.meridian.substrate.common.ai.CompletionRequest;
import com.meridian.substrate.common.ai.CompletionResponse;
import com.meridian.substrate.common.ai.ContentBlock;
import com.meridian.substrate.common.ai.LlmClient;
import com.meridian.substrate.common.ai.TokenUsage;
import com.meridian.substrate.common.ai.ToolCallRequest;
import com.meridian.substrate.common.ai.ToolCallResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Mock implementation of {@link LlmClient} for testing and offline development.
 * Activated when {@code substrate.llm.provider=mock} (fixture seam S8 — kept as
 * the test seam; the real path is {@code SdkAnthropicClient} + ANTHROPIC_API_KEY).
 */
@Service
@ConditionalOnProperty(name = "substrate.llm.provider", havingValue = "mock")
public class MockAnthropicClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(MockAnthropicClient.class);

    private static final String MOCK_MODEL = "mock-model";
    private static final String MOCK_RESPONSE = "This is a mock response from MERIDIAN.";

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        log.debug("MockAnthropicClient.complete() [model={}, messages={}]",
                request.model(), request.messages().size());

        return new CompletionResponse(
                generateId(),
                MOCK_RESPONSE,
                MOCK_MODEL,
                "end_turn",
                new TokenUsage(25, 12)
        );
    }

    @Override
    public Flux<CompletionChunk> completeStream(CompletionRequest request) {
        log.debug("MockAnthropicClient.completeStream() [model={}, messages={}]",
                request.model(), request.messages().size());

        String id = generateId();

        return Flux.just(
                new CompletionChunk(id, "This is a mock ", null, null),
                new CompletionChunk(id, "streaming response ", null, null),
                new CompletionChunk(id, "from MERIDIAN.", "end_turn", new TokenUsage(25, 18))
        ).delayElements(Duration.ofMillis(100));
    }

    @Override
    public ToolCallResponse completeWithTools(ToolCallRequest request) {
        log.debug("MockAnthropicClient.completeWithTools() [model={}, tools={}, messages={}]",
                request.model(), request.tools().size(), request.messages().size());

        return new ToolCallResponse(
                generateId(),
                MOCK_MODEL,
                "end_turn",
                List.of(ContentBlock.text(
                        "Mock tool-calling response from MERIDIAN. " +
                        "No tools were actually invoked."
                )),
                new TokenUsage(40, 20)
        );
    }

    private static String generateId() {
        return "mock-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
