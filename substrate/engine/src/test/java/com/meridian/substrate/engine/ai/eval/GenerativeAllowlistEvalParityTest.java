package com.meridian.substrate.engine.ai.eval;

import com.meridian.substrate.common.ai.CompletionChunk;
import com.meridian.substrate.common.ai.CompletionRequest;
import com.meridian.substrate.common.ai.CompletionResponse;
import com.meridian.substrate.common.ai.ContentBlock;
import com.meridian.substrate.common.ai.LlmClient;
import com.meridian.substrate.common.ai.TokenUsage;
import com.meridian.substrate.common.ai.ToolCallRequest;
import com.meridian.substrate.common.ai.ToolCallResponse;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.EvidenceRepository;
import com.meridian.substrate.engine.EngineTestApplication;
import com.meridian.substrate.engine.ai.generative.GenerateRequest;
import com.meridian.substrate.engine.ai.generative.GenerateResponse;
import com.meridian.substrate.engine.ai.generative.GenerativeModeService;
import com.meridian.substrate.engine.ai.orchestration.Surface;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §13 mirror-parity pin: {@link DiagnosticEvalEvaluator}'s generative outcome
 * mirror (grounding → allowlist, allowlist read from
 * {@link GenerativeModeService#allowedFieldsForEntityType}) must agree with
 * the REAL pinned {@code GenerativeModeService.generate} over the exact
 * payload semantics the eval cases assert:
 *
 * <ol>
 *   <li>grounded proposal with an extra {@code reasoning} field → ONE draft,
 *       {@code reasoning} dropped, allowlisted fields survive;</li>
 *   <li>grounded proposal with only non-allowlisted fields → filtered;</li>
 *   <li>ungrounded proposal → filtered.</li>
 * </ol>
 *
 * Drives the service through a scripted LlmClient (the payload IS the model
 * output) against real seeded Evidence — if the service's allowlist or
 * grounding order ever shifts, this test and the eval cases shift together
 * or fail loudly.
 */
@SpringBootTest(classes = EngineTestApplication.class)
@ActiveProfiles("test")
@Import(GenerativeAllowlistEvalParityTest.StubConfig.class)
@Transactional
class GenerativeAllowlistEvalParityTest {

    @Autowired GenerativeModeService generativeModeService;
    @Autowired DomainRepository domainRepository;
    @Autowired EvidenceRepository evidenceRepository;
    @Autowired ScriptedLlmClient stub;

    private UUID domainId;
    private UUID evidenceId;

    @BeforeEach
    void fixture() {
        Domain domain = new Domain();
        domain.setName("gen-parity " + UUID.randomUUID());
        domain.setCreatedBy("test");
        domainId = domainRepository.save(domain).getId();

        Evidence evidence = new Evidence();
        evidence.setDomainId(domainId);
        evidence.setEntityId(UUID.randomUUID());
        evidence.setEntityType("system");
        evidence.setLabel("parity evidence");
        evidence.setSourceFile("parity.csv");
        evidence.setExtractor("test");
        evidence.setCreatedBy("test");
        evidenceId = evidenceRepository.save(evidence).getId();
    }

    @Test
    void groundedProposal_extraReasoningFieldDropped_allowlistedFieldsSurvive() {
        stub.respondWith("[{\"name\":\"Customer Onboarding\",\"description\":\"From notes.\","
                + "\"reasoning\":\"must be dropped\",\"evidenceIds\":[\"" + evidenceId + "\"]}]");

        GenerateResponse response = generate();

        assertThat(response.drafts()).hasSize(1);
        assertThat(response.filteredCount()).isZero();
        assertThat(response.drafts().get(0).proposedFields().has("name")).isTrue();
        assertThat(response.drafts().get(0).proposedFields().has("description")).isTrue();
        assertThat(response.drafts().get(0).proposedFields().has("reasoning"))
                .as("non-allowlisted field is silently dropped — eval case diag-gen-001 parity")
                .isFalse();
    }

    @Test
    void groundedProposal_withNoAllowlistedFields_isFiltered() {
        stub.respondWith("[{\"reasoning\":\"nothing allowlisted\",\"category\":\"ops\","
                + "\"evidenceIds\":[\"" + evidenceId + "\"]}]");

        GenerateResponse response = generate();

        assertThat(response.drafts()).isEmpty();
        assertThat(response.filteredCount())
                .as("eval case diag-gen-002 parity")
                .isEqualTo(1);
        assertThat(response.explanation()).isNotBlank();
    }

    @Test
    void ungroundedProposal_isFiltered() {
        stub.respondWith("[{\"name\":\"Ungrounded\",\"evidenceIds\":"
                + "[\"33333333-3333-3333-3333-333333333333\"]}]");

        GenerateResponse response = generate();

        assertThat(response.drafts()).isEmpty();
        assertThat(response.filteredCount())
                .as("eval case diag-gen-003 parity")
                .isEqualTo(1);
    }

    private GenerateResponse generate() {
        return generativeModeService.generate(
                new GenerateRequest(domainId, Surface.ANALYST_QUERY, "capability", List.of()),
                PersonaContext.ofLevel("L3_ANALYST"));
    }

    // ------------------------------------------------------------------ stub

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        ScriptedLlmClient parityScriptedLlmClient() {
            return new ScriptedLlmClient();
        }
    }

    static class ScriptedLlmClient implements LlmClient {

        private volatile String finalText = "prose";

        void respondWith(String text) {
            this.finalText = text;
        }

        @Override
        public CompletionResponse complete(CompletionRequest request) {
            return new CompletionResponse("stub", finalText, "stub-model", "end_turn",
                    new TokenUsage(20, 10));
        }

        @Override
        public Flux<CompletionChunk> completeStream(CompletionRequest request) {
            return Flux.just(new CompletionChunk("stub", finalText, "end_turn",
                    new TokenUsage(20, 10)));
        }

        @Override
        public ToolCallResponse completeWithTools(ToolCallRequest request) {
            return new ToolCallResponse("stub", "stub-model", "end_turn",
                    List.of(ContentBlock.text(finalText)), new TokenUsage(30, 15));
        }
    }
}
