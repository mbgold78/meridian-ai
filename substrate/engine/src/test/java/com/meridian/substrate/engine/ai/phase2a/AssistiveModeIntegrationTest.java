package com.meridian.substrate.engine.ai.phase2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.ai.CompletionChunk;
import com.meridian.substrate.common.ai.CompletionRequest;
import com.meridian.substrate.common.ai.CompletionResponse;
import com.meridian.substrate.common.ai.LlmClient;
import com.meridian.substrate.common.ai.TokenUsage;
import com.meridian.substrate.common.ai.ToolCallRequest;
import com.meridian.substrate.common.ai.ToolCallResponse;
import com.meridian.substrate.common.domain.Assertion;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.domain.enums.ProducedBy;
import com.meridian.substrate.common.repo.AssertionRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.EvidenceRepository;
import com.meridian.substrate.engine.EngineTestApplication;
import com.meridian.substrate.engine.ai.assistive.AssistiveModeService;
import com.meridian.substrate.engine.ai.assistive.AssistiveQueryType;
import com.meridian.substrate.engine.ai.assistive.SuggestRequest;
import com.meridian.substrate.engine.ai.assistive.SuggestResponse;
import com.meridian.substrate.engine.ai.assistive.Suggestion;
import com.meridian.substrate.engine.ai.client.MockAnthropicClient;
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
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase-2A behavioral-contract tests for {@link AssistiveModeService}, ported
 * from homepoc's {@code phase2a.AssistiveModeIntegrationTest}.
 *
 * <p><b>Port note:</b> homepoc exercised these contracts through MockMvc against
 * {@code POST /api/v1/ai/assistive}; the controllers land in chunk C5 (app
 * module), so this port pins the same contracts at the service seam. The
 * envelope/HTTP layer is re-covered in C5.
 *
 * <p>Three orthogonal seams covered:
 * <ol>
 *   <li>Grounding-required path with mixed candidates — proves the per-suggestion
 *       grounding filter (declared via {@link AssistiveQueryType#getGroundingRule()})
 *       AND the output-shape allowlist (unknown meta-fields like
 *       {@code reasoning} dropped).</li>
 *   <li>Empty-result path on a structural query — proves the service does not
 *       hallucinate suggestions when none exist, and returns a query-specific
 *       explanation rather than a generic "filtered" message.</li>
 *   <li>Unknown query type rejection — proves the {@link AssistiveQueryType}
 *       allowlist is enforced with a structured response, not an exception.</li>
 * </ol>
 *
 * <p>A fourth test (new in substrate) pins the SafetyHandler wiring: a refusal
 * from the single-shot path yields an empty result whose explanation is the
 * configured fallback message, with no Assertion persisted.
 */
@SpringBootTest(classes = EngineTestApplication.class)
@ActiveProfiles("test")
@Import(AssistiveModeIntegrationTest.StubConfig.class)
class AssistiveModeIntegrationTest {

    @Autowired AssistiveModeService assistiveModeService;
    @Autowired ObjectMapper json;
    @Autowired DomainRepository domainRepository;
    @Autowired EvidenceRepository evidenceRepository;
    @Autowired AssertionRepository assertionRepository;
    @Autowired StubAssistiveLlmClient stub;

    @BeforeEach
    void resetStub() {
        stub.reset();
    }

    @Test
    void suggest_groundingRequired_filtersUngroundedAndDropsUnknownFields() throws Exception {
        UUID domainId = createDomain("assistive-grounded");
        UUID inDomainEvidence = createEvidence(domainId);
        UUID otherDomainId = createDomain("assistive-other");
        UUID crossDomainEvidence = createEvidence(otherDomainId);
        UUID capabilityId = UUID.randomUUID();

        // Three candidates exercising the grounding rule + output allowlist:
        //   #1 GROUNDED (in-domain evidence) + carries unknown 'reasoning' meta
        //      → kept; reasoning silently dropped by output allowlist
        //   #2 UNGROUNDED (no evidenceIds)
        //      → filtered (REQUIRED grounding rule)
        //   #3 CROSS-DOMAIN (evidence from a different domain)
        //      → filtered (does not resolve to in-domain Evidence)
        String stubResponse = String.format("""
                [
                  {
                    "value": "Validates customer identity before account opening",
                    "confidence": 0.86,
                    "evidenceIds": ["%s"],
                    "reasoning": "this seems relevant to onboarding"
                  },
                  {
                    "value": "Unsupported description with no evidence",
                    "confidence": 0.4,
                    "evidenceIds": []
                  },
                  {
                    "value": "References evidence from another domain",
                    "confidence": 0.7,
                    "evidenceIds": ["%s"]
                  }
                ]
                """, inDomainEvidence, crossDomainEvidence);
        stub.setNextCompleteText(stubResponse);

        SuggestRequest request = new SuggestRequest(
                domainId, Surface.CAPABILITY_DESIGNER, "capability", capabilityId,
                AssistiveQueryType.SUGGEST_DESCRIPTION.name(), null);

        SuggestResponse response = assistiveModeService.suggest(request, new PersonaContext());

        assertThat(response.suggestions())
                .as("only the in-domain grounded suggestion may survive")
                .hasSize(1);
        assertThat(response.filteredCount())
                .as("ungrounded + cross-domain candidates must be filtered")
                .isEqualTo(2);
        assertThat(response.explanation())
                .as("explanation must be null when at least one suggestion survives")
                .isNull();

        Suggestion survivor = response.suggestions().get(0);
        assertThat(survivor.value())
                .isEqualTo("Validates customer identity before account opening");
        assertThat(survivor.dismissible())
                .as("every assistive suggestion must be dismissible — no exceptions")
                .isTrue();
        assertThat(survivor.evidenceIds()).containsExactly(inDomainEvidence);

        // Output-shape allowlist: unknown meta-fields (e.g. 'reasoning') must
        // be dropped by construction. The DTO has fixed fields, so a leak would
        // surface as the rendered JSON containing 'reasoning'.
        JsonNode rendered = json.valueToTree(survivor);
        assertThat(rendered.has("reasoning"))
                .as("unknown meta-field 'reasoning' must not leak into the suggestion DTO")
                .isFalse();

        // Provenance: one Assertion persisted with AI_ASSISTIVE.
        List<Assertion> persisted = assertionRepository
                .findByDomainIdAndProducedBy(domainId, ProducedBy.AI_ASSISTIVE);
        assertThat(persisted).hasSize(1);
        Assertion a = persisted.get(0);
        assertThat(a.getPredicate()).isEqualTo("assistive_suggestion:SUGGEST_DESCRIPTION");
        assertThat(a.getProducedByContext().path("mode").asText()).isEqualTo("ASSISTIVE");
        assertThat(a.getProducedByContext().path("surface").asText()).isEqualTo("CAPABILITY_DESIGNER");
        assertThat(a.getProducedByContext().path("queryType").asText()).isEqualTo("SUGGEST_DESCRIPTION");
        assertThat(a.getEvidenceIds().size()).isEqualTo(1);
        assertThat(a.getEvidenceIds().get(0).asText()).isEqualTo(inDomainEvidence.toString());

        // No assertions persisted referencing the cross-domain or absent evidence.
        for (Assertion any : assertionRepository.findAll()) {
            assertThat(any.getEvidenceIds().toString())
                    .doesNotContain(crossDomainEvidence.toString());
        }
    }

    @Test
    void suggest_structuralQueryWithEmptyResult_returnsExplanationNotHallucination() {
        UUID domainId = createDomain("assistive-empty");
        UUID capabilityId = UUID.randomUUID();

        // The LLM legitimately found nothing — must not invent collisions.
        // The service must distinguish "we filtered everything" from
        // "the answer is genuinely empty" via the explanation text.
        stub.setNextCompleteText("[]");

        SuggestRequest request = new SuggestRequest(
                domainId, Surface.CAPABILITY_DESIGNER, "capability", capabilityId,
                AssistiveQueryType.DETECT_NAME_COLLISION.name(), null);

        SuggestResponse response = assistiveModeService.suggest(request, new PersonaContext());

        assertThat(response.suggestions()).isEmpty();
        assertThat(response.filteredCount())
                .as("nothing was filtered — the LLM returned an empty list")
                .isEqualTo(0);
        assertThat(response.explanation())
                .as("explanation must be present so the UI can distinguish "
                        + "'nothing found' from 'all filtered', and must be "
                        + "query-specific, not a generic 'filtered' message")
                .isEqualTo("No name collisions detected.");

        assertThat(assertionRepository.findByDomainIdAndProducedBy(domainId, ProducedBy.AI_ASSISTIVE))
                .as("no assertion persisted when the LLM found nothing to suggest")
                .isEmpty();
    }

    @Test
    void suggest_unknownQueryType_returnsNotImplementedExplanation() {
        UUID domainId = createDomain("assistive-unknown");

        // The LLM stub should not even be called — service rejects upfront.
        stub.setNextCompleteText("[\"this should never be parsed\"]");

        SuggestRequest request = new SuggestRequest(
                domainId, Surface.CAPABILITY_DESIGNER, "capability", null,
                "SUGGEST_RELATED_PROCESSES", null);

        SuggestResponse response = assistiveModeService.suggest(request, new PersonaContext());

        assertThat(response.suggestions()).isEmpty();
        assertThat(response.explanation())
                .contains("not implemented in Phase 2A")
                .contains("SUGGEST_DESCRIPTION")
                .contains("DETECT_NAME_COLLISION")
                .contains("SUGGEST_PARENT_CAPABILITY");

        assertThat(assertionRepository.findByDomainIdAndProducedBy(domainId, ProducedBy.AI_ASSISTIVE))
                .as("unknown query type must not write any Assertion")
                .isEmpty();
    }

    @Test
    void suggest_refusal_returnsFallbackExplanationAndNeverParses() {
        UUID domainId = createDomain("assistive-refusal");
        createEvidence(domainId);

        // SafetyHandler wiring (dead code in homepoc — live path in substrate):
        // a refusal must never be parsed as suggestions.
        stub.setNextCompleteText("I cannot help with that request.");

        SuggestRequest request = new SuggestRequest(
                domainId, Surface.CAPABILITY_DESIGNER, "capability", UUID.randomUUID(),
                AssistiveQueryType.SUGGEST_DESCRIPTION.name(), null);

        SuggestResponse response = assistiveModeService.suggest(request, new PersonaContext());

        assertThat(response.suggestions()).isEmpty();
        assertThat(response.filteredCount()).isEqualTo(0);
        assertThat(response.explanation())
                .as("refusal must surface the configured fallback message")
                .isEqualTo("I cannot answer that question. Please rephrase or contact a Risk Architect.");

        assertThat(assertionRepository.findByDomainIdAndProducedBy(domainId, ProducedBy.AI_ASSISTIVE))
                .as("a refused completion must not write any Assertion")
                .isEmpty();
    }

    private UUID createDomain(String label) {
        Domain d = new Domain();
        d.setName("Phase2A " + label + " " + UUID.randomUUID());
        d.setCreatedBy("test");
        return domainRepository.save(d).getId();
    }

    private UUID createEvidence(UUID domainId) {
        Evidence e = new Evidence();
        e.setDomainId(domainId);
        e.setEntityId(UUID.randomUUID());
        e.setEntityType("capability");
        e.setLabel("Assistive seed evidence " + UUID.randomUUID());
        e.setCreatedBy("test");
        return evidenceRepository.save(e).getId();
    }

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        StubAssistiveLlmClient stubAssistiveLlmClient(MockAnthropicClient delegate) {
            return new StubAssistiveLlmClient(delegate);
        }
    }

    /**
     * Test-only LLM client that lets each test set the next single-shot
     * {@code complete()} response. Other paths (streaming, tool calling) are
     * delegated to the real mock so tests sharing this Spring context are
     * unaffected.
     */
    static class StubAssistiveLlmClient implements LlmClient {

        private final LlmClient delegate;
        private final AtomicReference<String> nextCompleteText = new AtomicReference<>();

        StubAssistiveLlmClient(LlmClient delegate) {
            this.delegate = delegate;
        }

        void setNextCompleteText(String text) {
            nextCompleteText.set(text);
        }

        void reset() {
            nextCompleteText.set(null);
        }

        @Override
        public CompletionResponse complete(CompletionRequest request) {
            String text = nextCompleteText.get();
            if (text == null) {
                return delegate.complete(request);
            }
            return new CompletionResponse(
                    "stub-" + UUID.randomUUID().toString().substring(0, 8),
                    text,
                    "stub-model",
                    "end_turn",
                    new TokenUsage(15, 15));
        }

        @Override
        public Flux<CompletionChunk> completeStream(CompletionRequest request) {
            return delegate.completeStream(request);
        }

        @Override
        public ToolCallResponse completeWithTools(ToolCallRequest request) {
            return delegate.completeWithTools(request);
        }
    }
}
