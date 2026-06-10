package com.meridian.substrate.engine.ai.phase2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.ai.CompletionChunk;
import com.meridian.substrate.common.ai.CompletionRequest;
import com.meridian.substrate.common.ai.CompletionResponse;
import com.meridian.substrate.common.ai.ContentBlock;
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
import com.meridian.substrate.engine.ai.client.MockAnthropicClient;
import com.meridian.substrate.engine.ai.generative.DraftEntity;
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
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase-2A behavioral-contract tests for {@link GenerativeModeService}, ported
 * from homepoc's {@code phase2a.GenerativeModeIntegrationTest} (service seam —
 * the {@code POST /api/v1/ai/generative} MockMvc coverage is re-added in C5).
 *
 * <p>The test deliberately exercises the grounding filter — principle P6 enforced
 * at runtime — by feeding the orchestrator a stub LLM response that contains
 * THREE candidates: one whose evidenceId resolves to a real {@code Evidence}
 * record in the active domain, one whose evidenceId resolves to an Evidence
 * record in a DIFFERENT domain (cross-domain leak attempt), and one with NO
 * evidenceIds at all. The first must survive; the other two must be filtered.
 *
 * <p>A second test asserts the all-ungrounded path — when nothing survives the
 * filter, the response carries an explanation rather than silently returning an
 * empty list as if the AI produced nothing.
 */
@SpringBootTest(classes = EngineTestApplication.class)
@ActiveProfiles("test")
@Import(GenerativeModeIntegrationTest.StubConfig.class)
class GenerativeModeIntegrationTest {

    @Autowired GenerativeModeService generativeModeService;
    @Autowired ObjectMapper json;
    @Autowired DomainRepository domainRepository;
    @Autowired EvidenceRepository evidenceRepository;
    @Autowired AssertionRepository assertionRepository;
    @Autowired StubGenerativeLlmClient stub;

    @BeforeEach
    void resetStub() {
        stub.reset();
    }

    @Test
    void generate_filtersUngroundedAndCrossDomainCandidates_persistsAssertionForSurvivor() {
        UUID domainId = createDomain("active");
        UUID otherDomainId = createDomain("other");
        UUID groundedEvidenceId = createEvidence(domainId);
        UUID crossDomainEvidenceId = createEvidence(otherDomainId);
        UUID nonexistentEvidenceId = UUID.randomUUID();

        // Three candidates: only the first should survive the grounding filter.
        // The grounded candidate also includes:
        //   - confidence (output-level metadata, not an entity attribute)
        //   - reasoning (an unknown meta-field — proves the allowlist drops
        //     arbitrary unknown fields, not just keys someone remembered to
        //     enumerate in a denylist)
        //   - level (a Capability schema field deliberately excluded from the
        //     allowlist per doc 04 — derived from parentId, not user-set)
        String stubResponse = String.format("""
                [
                  {
                    "name": "Customer Verification",
                    "description": "Validates customer identity at onboarding",
                    "evidenceIds": ["%s"],
                    "confidence": 0.84,
                    "reasoning": "this seems relevant to onboarding flow",
                    "level": 2
                  },
                  {
                    "name": "Cross-Domain Leak",
                    "description": "References evidence from a different domain",
                    "evidenceIds": ["%s"]
                  },
                  {
                    "name": "Hallucinated Capability",
                    "description": "Has no grounding evidence at all",
                    "evidenceIds": []
                  }
                ]
                """, groundedEvidenceId, crossDomainEvidenceId);
        stub.setNextFinalText(stubResponse);

        GenerateRequest request = new GenerateRequest(
                domainId, Surface.CAPABILITY_DESIGNER, "capability", List.of());

        GenerateResponse response = generativeModeService.generate(request, new PersonaContext());

        assertThat(response.drafts())
                .as("only the grounded candidate must survive (cross-domain and empty-evidence are filtered)")
                .hasSize(1);

        assertThat(response.filteredCount())
                .as("two candidates were filtered (cross-domain + empty-evidence)")
                .isEqualTo(2);

        assertThat(response.explanation())
                .as("explanation is null when at least one draft survives")
                .isNull();

        DraftEntity survivor = response.drafts().get(0);
        assertThat(survivor.entityType()).isEqualTo("capability");
        JsonNode proposedFields = survivor.proposedFields();
        assertThat(proposedFields.path("name").asText()).isEqualTo("Customer Verification");
        assertThat(proposedFields.path("description").asText())
                .isEqualTo("Validates customer identity at onboarding");

        // proposedFields must contain ONLY allowlisted Capability attributes
        // (per doc 04 schema). The candidate emitted: name, description (both
        // allowlisted) + evidenceIds, confidence, reasoning, level (none
        // allowlisted). Only the two allowlisted fields must survive — every
        // other field, KNOWN OR UNKNOWN, must be silently dropped.
        List<String> proposedKeys = new ArrayList<>();
        proposedFields.fieldNames().forEachRemaining(proposedKeys::add);
        assertThat(proposedKeys)
                .as("proposedFields must contain only allowlisted Capability attributes")
                .containsExactlyInAnyOrder("name", "description");
        assertThat(proposedFields.has("evidenceIds")).isFalse();
        assertThat(proposedFields.has("confidence")).isFalse();
        assertThat(proposedFields.has("reasoning"))
                .as("unknown meta-field 'reasoning' must be dropped — proves the allowlist "
                        + "approach catches arbitrary fields, not just enumerated ones")
                .isFalse();
        assertThat(proposedFields.has("level"))
                .as("'level' is a Capability schema field deliberately excluded from the "
                        + "AI-proposable allowlist (derived from parentId per doc 04)")
                .isFalse();

        assertThat(survivor.evidenceIds()).containsExactly(groundedEvidenceId);

        UUID assertionId = survivor.assertionId();
        Assertion persisted = assertionRepository.findById(assertionId).orElseThrow();
        assertThat(persisted.getDomainId()).isEqualTo(domainId);
        assertThat(persisted.getProducedBy()).isEqualTo(ProducedBy.AI_GENERATIVE);
        assertThat(persisted.getSubject()).isEqualTo("draft:capability");
        assertThat(persisted.getPredicate()).isEqualTo("proposes_creation");

        assertThat(persisted.getProducedByContext().path("mode").asText()).isEqualTo("GENERATIVE");
        assertThat(persisted.getProducedByContext().path("surface").asText()).isEqualTo("CAPABILITY_DESIGNER");
        assertThat(persisted.getProducedByContext().path("entityType").asText()).isEqualTo("capability");

        JsonNode persistedEvidenceIds = persisted.getEvidenceIds();
        assertThat(persistedEvidenceIds.isArray()).isTrue();
        assertThat(persistedEvidenceIds.size()).isEqualTo(1);
        assertThat(persistedEvidenceIds.get(0).asText()).isEqualTo(groundedEvidenceId.toString());

        // Negative side: nothing was persisted for the filtered candidates,
        // and the nonexistent evidence ID never leaked into any persisted Assertion.
        List<Assertion> allInDomain = assertionRepository.findByDomainId(domainId);
        assertThat(allInDomain).hasSize(1);
        assertThat(allInDomain.get(0).getId()).isEqualTo(assertionId);
        for (Assertion a : assertionRepository.findAll()) {
            assertThat(a.getEvidenceIds().toString()).doesNotContain(crossDomainEvidenceId.toString());
            assertThat(a.getEvidenceIds().toString()).doesNotContain(nonexistentEvidenceId.toString());
        }
    }

    @Test
    void generate_returnsExplanation_whenAllCandidatesAreUngrounded() {
        UUID domainId = createDomain("only-ungrounded");

        // All three candidates lack any valid grounding. Service must filter
        // every one and return drafts=[] with a non-null explanation rather
        // than silently returning empty as if the AI produced nothing.
        String stubResponse = """
                [
                  { "name": "A", "description": "no evidence", "evidenceIds": [] },
                  { "name": "B", "description": "bad uuid",     "evidenceIds": ["not-a-uuid"] },
                  { "name": "C", "description": "missing field" }
                ]
                """;
        stub.setNextFinalText(stubResponse);

        GenerateRequest request = new GenerateRequest(
                domainId, Surface.CAPABILITY_DESIGNER, "capability", List.of());

        GenerateResponse response = generativeModeService.generate(request, new PersonaContext());

        assertThat(response.drafts()).isEmpty();
        assertThat(response.filteredCount()).isEqualTo(3);
        assertThat(response.explanation())
                .as("explanation must be present when all candidates are filtered")
                .isNotNull()
                .contains("filtered");

        // No Assertions persisted for any of the rejected candidates.
        assertThat(assertionRepository.findByDomainId(domainId)).isEmpty();
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
        e.setLabel("Seeded evidence " + UUID.randomUUID());
        e.setCreatedBy("test");
        return evidenceRepository.save(e).getId();
    }

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        StubGenerativeLlmClient stubGenerativeLlmClient(MockAnthropicClient delegate) {
            return new StubGenerativeLlmClient(delegate);
        }
    }

    /**
     * Test-only LLM client that lets each test set the next {@code finalText}
     * the orchestrator will see. Delegates non-tool calls to the real mock.
     * Returns a single text content block (no tool_use), which causes the
     * orchestrator to terminate after one iteration.
     */
    static class StubGenerativeLlmClient implements LlmClient {

        private final LlmClient delegate;
        private final AtomicReference<String> nextFinalText = new AtomicReference<>();

        StubGenerativeLlmClient(LlmClient delegate) {
            this.delegate = delegate;
        }

        void setNextFinalText(String text) {
            nextFinalText.set(text);
        }

        void reset() {
            nextFinalText.set(null);
        }

        @Override
        public CompletionResponse complete(CompletionRequest request) {
            return delegate.complete(request);
        }

        @Override
        public Flux<CompletionChunk> completeStream(CompletionRequest request) {
            return delegate.completeStream(request);
        }

        @Override
        public ToolCallResponse completeWithTools(ToolCallRequest request) {
            String text = nextFinalText.get();
            if (text == null) {
                return delegate.completeWithTools(request);
            }
            return new ToolCallResponse(
                    "stub-" + UUID.randomUUID().toString().substring(0, 8),
                    "stub-model",
                    "end_turn",
                    List.of(ContentBlock.text(text)),
                    new TokenUsage(10, 10));
        }
    }
}
