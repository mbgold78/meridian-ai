package com.meridian.substrate.engine.ai.phase2a;

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
import com.meridian.substrate.engine.ai.orchestration.Surface;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.engine.ai.validative.FindingCandidate;
import com.meridian.substrate.engine.ai.validative.FindingCandidateType;
import com.meridian.substrate.engine.ai.validative.ValidateRequest;
import com.meridian.substrate.engine.ai.validative.ValidateResponse;
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
 * Phase-2A behavioral-contract tests for {@link com.meridian.substrate.engine.ai.validative.ValidativeModeService},
 * ported from homepoc's {@code phase2a.ValidativeModeIntegrationTest} (service
 * seam — the {@code POST /api/v1/ai/validative} MockMvc coverage is re-added in C5).
 *
 * <p>The two tests split the validative-mode contract along orthogonal seams so
 * a regression localizes:
 * <ol>
 *   <li>Differential grounding rule — STRUCTURAL findings need no evidence;
 *       CONTENT findings must cite ≥1 valid in-domain Evidence ID.</li>
 *   <li>Type and severity allowlist enforcement — the LLM cannot smuggle
 *       through unknown enum values (e.g. {@code LOW} from the legacy enum,
 *       or hallucinations like {@code "URGENT"} / {@code "ANALYTICAL"}).</li>
 * </ol>
 *
 * <p>The first test covers all four mandated cases explicitly:
 * STRUCTURAL with no evidence (accepted), STRUCTURAL with evidence (accepted),
 * CONTENT with valid evidence (accepted), CONTENT with claimed-but-nonexistent
 * evidence (filtered).
 */
@SpringBootTest(classes = EngineTestApplication.class)
@ActiveProfiles("test")
@Import(ValidativeModeIntegrationTest.StubConfig.class)
class ValidativeModeIntegrationTest {

    @Autowired com.meridian.substrate.engine.ai.validative.ValidativeModeService validativeModeService;
    @Autowired DomainRepository domainRepository;
    @Autowired EvidenceRepository evidenceRepository;
    @Autowired AssertionRepository assertionRepository;
    @Autowired StubValidativeLlmClient stub;

    @BeforeEach
    void resetStub() {
        stub.reset();
    }

    @Test
    void validate_enforcesDifferentialGroundingRule_betweenStructuralAndContent() {
        UUID domainId = createDomain("validative-mixed");
        UUID inDomainEvidenceA = createEvidence(domainId);
        UUID inDomainEvidenceB = createEvidence(domainId);
        UUID nonexistentEvidenceId = UUID.randomUUID();
        UUID capabilityId = UUID.randomUUID();

        // Five candidates exercising every cell of the (type × evidence) matrix:
        //   #1 STRUCTURAL with no evidence       → ACCEPTED (structural needs none)
        //   #2 STRUCTURAL with valid evidence    → ACCEPTED (evidence allowed but not required)
        //   #3 CONTENT with valid evidence       → ACCEPTED
        //   #4 CONTENT with no evidence          → FILTERED (content requires evidence)
        //   #5 CONTENT with nonexistent evidence → FILTERED (claimed evidence does not resolve in domain)
        String stubResponse = String.format("""
                [
                  {
                    "type": "STRUCTURAL",
                    "severity": "MEDIUM",
                    "affectedEntityType": "capability",
                    "affectedEntityId": "%s",
                    "description": "Capability has no processes mapped",
                    "suggestedAction": "Map at least one process during the Map phase",
                    "evidenceIds": []
                  },
                  {
                    "type": "STRUCTURAL",
                    "severity": "INFO",
                    "description": "Capability tree has only one L1 node",
                    "suggestedAction": "Consider whether additional L1 capabilities exist",
                    "evidenceIds": ["%s"]
                  },
                  {
                    "type": "CONTENT",
                    "severity": "HIGH",
                    "affectedEntityType": "capability",
                    "affectedEntityId": "%s",
                    "description": "Description contradicts policy doc",
                    "suggestedAction": "Reconcile description with cited evidence",
                    "evidenceIds": ["%s"]
                  },
                  {
                    "type": "CONTENT",
                    "severity": "HIGH",
                    "description": "Capability described as automated but no system mapped",
                    "suggestedAction": "Either map the system or correct the description",
                    "evidenceIds": []
                  },
                  {
                    "type": "CONTENT",
                    "severity": "CRITICAL",
                    "description": "Description contradicts a regulatory document",
                    "suggestedAction": "Update or remove the conflicting capability",
                    "evidenceIds": ["%s"]
                  }
                ]
                """,
                capabilityId,
                inDomainEvidenceA,
                capabilityId,
                inDomainEvidenceB,
                nonexistentEvidenceId);
        stub.setNextFinalText(stubResponse);

        ValidateRequest request = new ValidateRequest(
                domainId, Surface.CAPABILITY_DESIGNER, "capability", List.of());

        ValidateResponse response = validativeModeService.validate(request, new PersonaContext());

        assertThat(response.findings())
                .as("three candidates must survive: STRUCTURAL-no-evidence, "
                        + "STRUCTURAL-with-evidence, CONTENT-with-valid-evidence")
                .hasSize(3);
        assertThat(response.filteredCount())
                .as("two CONTENT candidates must be filtered: one with no evidence, "
                        + "one with nonexistent evidence")
                .isEqualTo(2);
        assertThat(response.explanation())
                .as("explanation must be null when at least one finding survives")
                .isNull();

        // Find each surviving candidate by description
        FindingCandidate noProcessesFinding = findByDescriptionContains(response.findings(), "no processes mapped");
        FindingCandidate oneL1Finding = findByDescriptionContains(response.findings(), "only one L1 node");
        FindingCandidate contradictsFinding = findByDescriptionContains(response.findings(), "contradicts policy doc");

        assertThat(noProcessesFinding.type()).isEqualTo(FindingCandidateType.STRUCTURAL);
        assertThat(noProcessesFinding.evidenceIds())
                .as("STRUCTURAL with no evidence must survive AND retain empty evidenceIds")
                .isEmpty();

        assertThat(oneL1Finding.type()).isEqualTo(FindingCandidateType.STRUCTURAL);
        assertThat(oneL1Finding.evidenceIds())
                .as("STRUCTURAL with valid evidence must retain that evidence")
                .containsExactly(inDomainEvidenceA);

        assertThat(contradictsFinding.type()).isEqualTo(FindingCandidateType.CONTENT);
        assertThat(contradictsFinding.severity().name()).isEqualTo("HIGH");
        assertThat(contradictsFinding.evidenceIds()).containsExactly(inDomainEvidenceB);
        assertThat(contradictsFinding.affectedEntityType()).isEqualTo("capability");
        assertThat(contradictsFinding.affectedEntityId()).isEqualTo(capabilityId);

        // Negative side: no surviving candidate references the nonexistent evidence ID.
        for (FindingCandidate f : response.findings()) {
            assertThat(f.evidenceIds())
                    .as("nonexistent evidence ID must never appear in any surviving finding")
                    .doesNotContain(nonexistentEvidenceId);
        }

        // Assertion provenance: one Assertion per surviving finding, all AI_VALIDATIVE.
        List<Assertion> persistedInDomain = assertionRepository
                .findByDomainIdAndProducedBy(domainId, ProducedBy.AI_VALIDATIVE);
        assertThat(persistedInDomain).hasSize(3);
        for (Assertion a : persistedInDomain) {
            assertThat(a.getProducedBy()).isEqualTo(ProducedBy.AI_VALIDATIVE);
            assertThat(a.getProducedByContext().path("mode").asText()).isEqualTo("VALIDATIVE");
            assertThat(a.getProducedByContext().path("surface").asText()).isEqualTo("CAPABILITY_DESIGNER");
            assertThat(a.getProducedByContext().path("scope").asText()).isEqualTo("capability");
            // Predicate carries the type discriminator
            assertThat(a.getPredicate())
                    .startsWith("validative_finding:")
                    .containsAnyOf("STRUCTURAL", "CONTENT");
        }
    }

    @Test
    void validate_rejectsFindingsWithUnknownTypeOrSeverity() {
        UUID domainId = createDomain("validative-allowlist");
        UUID inDomainEvidence = createEvidence(domainId);

        // Five candidates probing the type and severity allowlists.
        // Only #1 (a fully valid STRUCTURAL with allowed severity) should survive.
        String stubResponse = String.format("""
                [
                  {
                    "type": "STRUCTURAL",
                    "severity": "CRITICAL",
                    "description": "Valid baseline finding",
                    "suggestedAction": "Acknowledge",
                    "evidenceIds": []
                  },
                  {
                    "type": "STRUCTURAL",
                    "severity": "LOW",
                    "description": "Severity LOW is from legacy enum, not in allowlist",
                    "suggestedAction": "Reject",
                    "evidenceIds": []
                  },
                  {
                    "type": "STRUCTURAL",
                    "severity": "URGENT",
                    "description": "Severity URGENT is hallucinated",
                    "suggestedAction": "Reject",
                    "evidenceIds": []
                  },
                  {
                    "type": "ANALYTICAL",
                    "severity": "HIGH",
                    "description": "Type ANALYTICAL is not in {STRUCTURAL, CONTENT}",
                    "suggestedAction": "Reject",
                    "evidenceIds": ["%s"]
                  },
                  {
                    "type": "CONTENT",
                    "severity": "MEDIUM",
                    "suggestedAction": "Description is missing — must be filtered",
                    "evidenceIds": ["%s"]
                  }
                ]
                """, inDomainEvidence, inDomainEvidence);
        stub.setNextFinalText(stubResponse);

        ValidateRequest request = new ValidateRequest(
                domainId, Surface.CAPABILITY_DESIGNER, "capability", List.of());

        ValidateResponse response = validativeModeService.validate(request, new PersonaContext());

        assertThat(response.findings())
                .as("only the fully-valid baseline finding must survive")
                .hasSize(1);
        assertThat(response.filteredCount())
                .as("legacy LOW, hallucinated URGENT, unknown type ANALYTICAL, "
                        + "and missing-description finding must all be filtered")
                .isEqualTo(4);

        FindingCandidate survivor = response.findings().get(0);
        assertThat(survivor.type()).isEqualTo(FindingCandidateType.STRUCTURAL);
        assertThat(survivor.severity().name()).isEqualTo("CRITICAL");
        assertThat(survivor.description()).isEqualTo("Valid baseline finding");

        // Only one Assertion persisted (the survivor); the four rejected
        // candidates left no provenance.
        List<Assertion> persisted = assertionRepository
                .findByDomainIdAndProducedBy(domainId, ProducedBy.AI_VALIDATIVE);
        assertThat(persisted).hasSize(1);
    }

    private FindingCandidate findByDescriptionContains(List<FindingCandidate> findings, String fragment) {
        for (FindingCandidate f : findings) {
            if (f.description().contains(fragment)) {
                return f;
            }
        }
        throw new AssertionError("No finding with description containing '" + fragment + "'");
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
        e.setLabel("Validative seed evidence " + UUID.randomUUID());
        e.setCreatedBy("test");
        return evidenceRepository.save(e).getId();
    }

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        StubValidativeLlmClient stubValidativeLlmClient(MockAnthropicClient delegate) {
            return new StubValidativeLlmClient(delegate);
        }
    }

    static class StubValidativeLlmClient implements LlmClient {

        private final LlmClient delegate;
        private final AtomicReference<String> nextFinalText = new AtomicReference<>();

        StubValidativeLlmClient(LlmClient delegate) {
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
