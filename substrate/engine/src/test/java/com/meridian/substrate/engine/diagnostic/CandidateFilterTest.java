package com.meridian.substrate.engine.diagnostic;

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
import com.meridian.substrate.engine.ai.orchestration.Surface;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.engine.ai.validative.FindingCandidate;
import com.meridian.substrate.engine.ai.validative.FindingCandidateType;
import com.meridian.substrate.engine.ai.validative.ValidateRequest;
import com.meridian.substrate.engine.ai.validative.ValidateResponse;
import com.meridian.substrate.engine.ai.validative.ValidativeModeService;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diagnostic test plan #1 (engine-spec diagnostic.md §14): the §7.2
 * {@link CandidateFilter} extraction lands with byte-identical filter
 * outcomes — a table test across ALL filter reasons
 * (KEPT / BAD_TYPE / BAD_SEVERITY / MISSING_FIELD / UNGROUNDED), the
 * pinned-order precedence of the checks, Assertion provenance for every
 * KEPT candidate, the §5.1 additive title/findingType raw passthrough, and a
 * delegation-equivalence proof that {@code ValidativeModeService} (the pinned
 * phase2a surface) produces exactly the filter's outcomes for the same
 * payload. The phase2a suites themselves stay green UNMODIFIED — this class
 * adds the extraction-specific coverage on top.
 */
@SpringBootTest(classes = EngineTestApplication.class)
@ActiveProfiles("test")
@Import(CandidateFilterTest.StubConfig.class)
class CandidateFilterTest {

    @Autowired CandidateFilter candidateFilter;
    @Autowired ValidativeModeService validativeModeService;
    @Autowired DomainRepository domainRepository;
    @Autowired EvidenceRepository evidenceRepository;
    @Autowired AssertionRepository assertionRepository;
    @Autowired ObjectMapper json;
    @Autowired StubLlmClient stub;

    @BeforeEach
    void resetStub() {
        stub.reset();
    }

    // ------------------------------------------------------------------
    // The filter-reason table (every outcome, every reason variant)
    // ------------------------------------------------------------------

    @Test
    void filter_tableAcrossAllFilterReasons_outcomesAndHistogramPinned() throws Exception {
        UUID domainId = createDomain("filter-table");
        UUID otherDomainId = createDomain("filter-table-other");
        UUID evidenceA = createEvidence(domainId);
        UUID evidenceB = createEvidence(domainId);
        UUID crossDomainEvidence = createEvidence(otherDomainId);
        UUID nonexistentEvidence = UUID.randomUUID();

        // One row per (outcome × reason-variant) cell. Precedence rows prove
        // the pinned check order: type → severity → required fields → grounding.
        String payload = String.format("""
                [
                  {"type":"STRUCTURAL","severity":"MEDIUM","description":"structural no evidence","suggestedAction":"act","evidenceIds":[]},
                  {"type":"STRUCTURAL","severity":"INFO","description":"structural with evidence","suggestedAction":"act","evidenceIds":["%s"]},
                  {"type":"CONTENT","severity":"HIGH","description":"content grounded","suggestedAction":"act","evidenceIds":["%s"]},
                  {"type":"CONTENT","severity":"MEDIUM","description":"content cross-domain dropped but survives","suggestedAction":"act","evidenceIds":["%s","%s"]},
                  {"type":"ANALYTICAL","severity":"HIGH","description":"unknown type","suggestedAction":"act","evidenceIds":["%s"]},
                  {"severity":"HIGH","description":"missing type","suggestedAction":"act","evidenceIds":[]},
                  {"type":"STRUCTURAL","severity":"LOW","description":"legacy severity","suggestedAction":"act","evidenceIds":[]},
                  {"type":"STRUCTURAL","severity":"URGENT","description":"hallucinated severity","suggestedAction":"act","evidenceIds":[]},
                  {"type":"STRUCTURAL","description":"missing severity","suggestedAction":"act","evidenceIds":[]},
                  {"type":"STRUCTURAL","severity":"MEDIUM","suggestedAction":"missing description","evidenceIds":[]},
                  {"type":"STRUCTURAL","severity":"MEDIUM","description":"blank suggestedAction","suggestedAction":"   ","evidenceIds":[]},
                  {"type":"CONTENT","severity":"HIGH","description":"content no evidence","suggestedAction":"act","evidenceIds":[]},
                  {"type":"CONTENT","severity":"CRITICAL","description":"content nonexistent evidence","suggestedAction":"act","evidenceIds":["%s"]},
                  {"type":"CONTENT","severity":"MEDIUM","description":"content cross-domain only","suggestedAction":"act","evidenceIds":["%s"]},
                  {"type":"ANALYTICAL","severity":"URGENT","suggestedAction":"type checked before severity and fields","evidenceIds":[]},
                  {"type":"STRUCTURAL","severity":"URGENT","suggestedAction":"severity checked before fields","evidenceIds":[]}
                ]
                """,
                evidenceA, evidenceB,
                crossDomainEvidence, evidenceB,
                evidenceA,
                nonexistentEvidence,
                crossDomainEvidence);

        List<JsonNode> raw = toList(payload);
        CandidateFilter.Result result = candidateFilter.filter(
                raw, new CandidateFilter.Context(domainId, Surface.CAPABILITY_DESIGNER, "capability"));

        assertThat(result.outcomes()).containsExactly(
                CandidateFilter.Outcome.KEPT,           // structural no evidence
                CandidateFilter.Outcome.KEPT,           // structural with evidence
                CandidateFilter.Outcome.KEPT,           // content grounded
                CandidateFilter.Outcome.KEPT,           // content, cross-domain id dropped, in-domain id retained
                CandidateFilter.Outcome.BAD_TYPE,       // ANALYTICAL
                CandidateFilter.Outcome.BAD_TYPE,       // missing type
                CandidateFilter.Outcome.BAD_SEVERITY,   // legacy LOW
                CandidateFilter.Outcome.BAD_SEVERITY,   // hallucinated URGENT
                CandidateFilter.Outcome.BAD_SEVERITY,   // missing severity
                CandidateFilter.Outcome.MISSING_FIELD,  // missing description
                CandidateFilter.Outcome.MISSING_FIELD,  // blank suggestedAction
                CandidateFilter.Outcome.UNGROUNDED,     // content no evidence
                CandidateFilter.Outcome.UNGROUNDED,     // content nonexistent evidence
                CandidateFilter.Outcome.UNGROUNDED,     // content cross-domain only
                CandidateFilter.Outcome.BAD_TYPE,       // precedence: type before severity/fields
                CandidateFilter.Outcome.BAD_SEVERITY);  // precedence: severity before fields

        assertThat(result.kept()).hasSize(4);
        assertThat(result.filteredCount()).isEqualTo(12);
        assertThat(result.histogram()).containsExactlyInAnyOrderEntriesOf(Map.of(
                CandidateFilter.Outcome.BAD_TYPE, 3,
                CandidateFilter.Outcome.BAD_SEVERITY, 4,
                CandidateFilter.Outcome.MISSING_FIELD, 2,
                CandidateFilter.Outcome.UNGROUNDED, 3));

        // Differential-grounding detail: cross-domain id dropped from the
        // surviving candidate, in-domain id retained.
        FindingCandidate crossDomainSurvivor = byDescription(result.kept(),
                "content cross-domain dropped but survives");
        assertThat(crossDomainSurvivor.evidenceIds()).containsExactly(evidenceB);
        assertThat(crossDomainSurvivor.evidenceIds()).doesNotContain(crossDomainEvidence);

        // STRUCTURAL keeps empty / valid evidence exactly as claimed.
        assertThat(byDescription(result.kept(), "structural no evidence").evidenceIds()).isEmpty();
        assertThat(byDescription(result.kept(), "structural with evidence").evidenceIds())
                .containsExactly(evidenceA);

        // Assertion provenance: exactly one AI_VALIDATIVE assertion per KEPT
        // candidate, none for filtered ones.
        List<Assertion> persisted = assertionRepository
                .findByDomainIdAndProducedBy(domainId, ProducedBy.AI_VALIDATIVE);
        assertThat(persisted).hasSize(4);
        assertThat(result.kept()).allSatisfy(c -> assertThat(c.assertionId()).isNotNull());
        for (Assertion a : persisted) {
            assertThat(a.getProducedByContext().path("mode").asText()).isEqualTo("VALIDATIVE");
            assertThat(a.getProducedByContext().path("surface").asText()).isEqualTo("CAPABILITY_DESIGNER");
            assertThat(a.getProducedByContext().path("scope").asText()).isEqualTo("capability");
            assertThat(a.getPredicate()).startsWith("validative_finding:");
        }
    }

    @Test
    void filter_additiveTitleAndFindingType_rawPassthroughNullableUnvalidated() throws Exception {
        UUID domainId = createDomain("filter-additive");

        String payload = """
                [
                  {"type":"STRUCTURAL","severity":"MEDIUM","description":"with additive fields","suggestedAction":"act",
                   "evidenceIds":[],"title":"Short title","findingType":"COVERAGE_GAP"},
                  {"type":"STRUCTURAL","severity":"MEDIUM","description":"with INVALID findingType kept raw","suggestedAction":"act",
                   "evidenceIds":[],"findingType":"NOT_A_REAL_TYPE"},
                  {"type":"STRUCTURAL","severity":"MEDIUM","description":"without additive fields","suggestedAction":"act","evidenceIds":[]}
                ]
                """;

        CandidateFilter.Result result = candidateFilter.filter(
                toList(payload),
                new CandidateFilter.Context(domainId, Surface.CAPABILITY_DESIGNER, "capability"));

        assertThat(result.kept()).hasSize(3);

        FindingCandidate withFields = byDescription(result.kept(), "with additive fields");
        assertThat(withFields.title()).isEqualTo("Short title");
        assertThat(withFields.findingType()).isEqualTo("COVERAGE_GAP");

        // An invalid LLM-supplied findingType NEVER discards a grounded
        // candidate — the raw string rides along for the commit step to
        // default differentially (spec §5.1/§5.2).
        FindingCandidate invalidType = byDescription(result.kept(), "with INVALID findingType kept raw");
        assertThat(invalidType.findingType()).isEqualTo("NOT_A_REAL_TYPE");

        FindingCandidate without = byDescription(result.kept(), "without additive fields");
        assertThat(without.title()).isNull();
        assertThat(without.findingType()).isNull();
    }

    @Test
    void filter_evidenceIdParsing_dedupesAndSkipsGarbage() throws Exception {
        UUID domainId = createDomain("filter-evidence-parse");
        UUID evidenceA = createEvidence(domainId);

        String payload = String.format("""
                [
                  {"type":"CONTENT","severity":"HIGH","description":"dupes and garbage","suggestedAction":"act",
                   "evidenceIds":["%s","not-a-uuid","%s",42,null]}
                ]
                """, evidenceA, evidenceA);

        CandidateFilter.Result result = candidateFilter.filter(
                toList(payload),
                new CandidateFilter.Context(domainId, Surface.CAPABILITY_DESIGNER, "capability"));

        assertThat(result.kept()).hasSize(1);
        assertThat(result.kept().get(0).evidenceIds())
                .as("duplicates deduped, non-UUID and non-textual entries skipped")
                .containsExactly(evidenceA);
    }

    // ------------------------------------------------------------------
    // Delegation equivalence: the pinned mode service == the filter
    // ------------------------------------------------------------------

    @Test
    void validativeModeService_delegation_producesByteIdenticalOutcomes() throws Exception {
        UUID domainId = createDomain("delegation-equivalence");
        UUID otherDomainId = createDomain("delegation-equivalence-other");
        UUID inDomain = createEvidence(domainId);
        UUID crossDomain = createEvidence(otherDomainId);

        String payload = String.format("""
                [
                  {"type":"STRUCTURAL","severity":"MEDIUM","description":"kept structural","suggestedAction":"act","evidenceIds":[]},
                  {"type":"CONTENT","severity":"HIGH","description":"kept content","suggestedAction":"act","evidenceIds":["%s"],
                   "title":"Equivalence title","findingType":"DATA_QUALITY"},
                  {"type":"CONTENT","severity":"HIGH","description":"ungrounded content","suggestedAction":"act","evidenceIds":["%s"]},
                  {"type":"BOGUS","severity":"HIGH","description":"bad type","suggestedAction":"act","evidenceIds":[]},
                  {"type":"STRUCTURAL","severity":"URGENT","description":"bad severity","suggestedAction":"act","evidenceIds":[]},
                  {"type":"STRUCTURAL","severity":"MEDIUM","suggestedAction":"missing description","evidenceIds":[]}
                ]
                """, inDomain, crossDomain);

        // Lane 1: through the pinned phase2a surface (stubbed LLM final text).
        stub.setNextFinalText(payload);
        ValidateResponse serviceResponse = validativeModeService.validate(
                new ValidateRequest(domainId, Surface.CAPABILITY_DESIGNER, "capability", List.of()),
                new PersonaContext());

        // Lane 2: directly through the extracted chokepoint.
        CandidateFilter.Result filterResult = candidateFilter.filter(
                toList(payload),
                new CandidateFilter.Context(domainId, Surface.CAPABILITY_DESIGNER, "capability"));

        // Identical keeps (field-by-field; assertionIds differ per persisted row).
        assertThat(serviceResponse.findings()).hasSameSizeAs(filterResult.kept());
        assertThat(serviceResponse.filteredCount()).isEqualTo(filterResult.filteredCount());
        for (int i = 0; i < filterResult.kept().size(); i++) {
            FindingCandidate fromService = serviceResponse.findings().get(i);
            FindingCandidate fromFilter = filterResult.kept().get(i);
            assertThat(fromService.type()).isEqualTo(fromFilter.type());
            assertThat(fromService.severity()).isEqualTo(fromFilter.severity());
            assertThat(fromService.affectedEntityType()).isEqualTo(fromFilter.affectedEntityType());
            assertThat(fromService.affectedEntityId()).isEqualTo(fromFilter.affectedEntityId());
            assertThat(fromService.description()).isEqualTo(fromFilter.description());
            assertThat(fromService.suggestedAction()).isEqualTo(fromFilter.suggestedAction());
            assertThat(fromService.evidenceIds()).isEqualTo(fromFilter.evidenceIds());
            assertThat(fromService.title()).isEqualTo(fromFilter.title());
            assertThat(fromService.findingType()).isEqualTo(fromFilter.findingType());
            assertThat(fromService.assertionId()).isNotNull();
        }

        // Both lanes persisted one Assertion per kept candidate (2 each).
        List<Assertion> persisted = assertionRepository
                .findByDomainIdAndProducedBy(domainId, ProducedBy.AI_VALIDATIVE);
        assertThat(persisted).hasSize(2 * filterResult.kept().size());
    }

    @Test
    void validativeModeService_allFiltered_explanationContractUnchanged() {
        UUID domainId = createDomain("delegation-all-filtered");

        stub.setNextFinalText("""
                [
                  {"type":"CONTENT","severity":"HIGH","description":"ungrounded","suggestedAction":"act","evidenceIds":[]}
                ]
                """);
        ValidateResponse response = validativeModeService.validate(
                new ValidateRequest(domainId, Surface.CAPABILITY_DESIGNER, "capability", List.of()),
                new PersonaContext());

        assertThat(response.findings()).isEmpty();
        assertThat(response.filteredCount()).isEqualTo(1);
        assertThat(response.explanation())
                .as("the pinned all-filtered explanation survives the extraction verbatim")
                .isEqualTo("AI returned 1 finding(s); all were filtered (type/severity allowlist, "
                        + "missing required fields, or unmet grounding requirement).");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private List<JsonNode> toList(String jsonArray) throws Exception {
        JsonNode parsed = json.readTree(jsonArray);
        List<JsonNode> out = new ArrayList<>();
        parsed.forEach(out::add);
        return out;
    }

    private FindingCandidate byDescription(List<FindingCandidate> candidates, String description) {
        return candidates.stream()
                .filter(c -> description.equals(c.description()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No kept candidate with description '" + description + "'"));
    }

    private UUID createDomain(String label) {
        Domain d = new Domain();
        d.setName("CandidateFilter " + label + " " + UUID.randomUUID());
        d.setCreatedBy("test");
        return domainRepository.save(d).getId();
    }

    private UUID createEvidence(UUID domainId) {
        Evidence e = new Evidence();
        e.setDomainId(domainId);
        e.setEntityId(UUID.randomUUID());
        e.setEntityType("capability");
        e.setLabel("CandidateFilter seed evidence " + UUID.randomUUID());
        e.setCreatedBy("test");
        return evidenceRepository.save(e).getId();
    }

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        StubLlmClient candidateFilterStubLlmClient(MockAnthropicClient delegate) {
            return new StubLlmClient(delegate);
        }
    }

    /**
     * Same shape as the phase2a stub clients: settable next final text for the
     * tool-calling path, mock-delegating otherwise.
     */
    static class StubLlmClient implements LlmClient {

        private final LlmClient delegate;
        private final AtomicReference<String> nextFinalText = new AtomicReference<>();

        StubLlmClient(LlmClient delegate) {
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
