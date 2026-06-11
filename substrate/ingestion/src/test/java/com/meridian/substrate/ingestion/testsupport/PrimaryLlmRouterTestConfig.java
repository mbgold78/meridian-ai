package com.meridian.substrate.ingestion.testsupport;

import com.meridian.substrate.common.ai.CompletionChunk;
import com.meridian.substrate.common.ai.CompletionRequest;
import com.meridian.substrate.common.ai.CompletionResponse;
import com.meridian.substrate.common.ai.LlmClient;
import com.meridian.substrate.common.ai.ToolCallRequest;
import com.meridian.substrate.common.ai.ToolCallResponse;
import com.meridian.substrate.engine.ai.client.MockAnthropicClient;
import com.meridian.substrate.ingestion.client.MockExtractionClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;

/**
 * Module-wide TEST wiring for the single-JVM ingestion+engine classpath
 * (engine-spec diagnostic.md §11.3). The {@code :engine} test dependency puts
 * the engine's {@link MockAnthropicClient} next to ingestion's
 * {@link MockExtractionClient} — both conditional on
 * {@code substrate.llm.provider=mock}, a combination main code never creates
 * (ingestion does not depend on engine; the §14.6 ArchUnit module rule still
 * binds ingestion's MAIN classes). Without a primary, every
 * {@code LlmClient}-injecting bean fails with two candidates.
 *
 * <p>This is deliberately a plain {@code @Configuration} (NOT
 * {@code @TestConfiguration}, which component scanning skips) in the test
 * sources: {@code IngestionApplication} scans {@code com.meridian.substrate},
 * so EVERY ingestion test context — including the pre-existing C6 pipeline
 * suite, byte-identically asserted — gets the {@code @Primary} router below:
 * validative prompts (the engine marker line) route to the engine's scripted
 * mock, everything else (conversion extraction) to the row-echo extractor.
 */
@Configuration
public class PrimaryLlmRouterTestConfig {

    @Bean
    @Primary
    public RoutingTestLlmClient routingTestLlmClient() {
        return new RoutingTestLlmClient();
    }

    /**
     * Routes per request: validative prompts → {@link MockAnthropicClient}
     * (scripted via {@link #engineMock()}); everything else →
     * {@link MockExtractionClient}.
     */
    public static class RoutingTestLlmClient implements LlmClient {

        private static final String VALIDATIVE_MARKER = "JSON array of finding objects";

        private final MockAnthropicClient engineMock = new MockAnthropicClient("");
        private final MockExtractionClient extractionMock = new MockExtractionClient();

        public MockAnthropicClient engineMock() {
            return engineMock;
        }

        @Override
        public CompletionResponse complete(CompletionRequest request) {
            return extractionMock.complete(request);
        }

        @Override
        public Flux<CompletionChunk> completeStream(CompletionRequest request) {
            return extractionMock.completeStream(request);
        }

        @Override
        public ToolCallResponse completeWithTools(ToolCallRequest request) {
            return isValidative(request)
                    ? engineMock.completeWithTools(request)
                    : extractionMock.completeWithTools(request);
        }

        private static boolean isValidative(ToolCallRequest request) {
            return request.messages() != null && request.messages().stream()
                    .anyMatch(m -> "user".equals(m.role()) && m.content() != null
                            && m.content().contains(VALIDATIVE_MARKER));
        }
    }
}
