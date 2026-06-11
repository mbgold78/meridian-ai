package com.meridian.substrate.app.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.app.api.v1.controller.FindingCurationController;
import com.meridian.substrate.app.api.v1.controller.GenerativeCommitController;
import com.meridian.substrate.common.domain.Assertion;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.domain.enums.ProducedBy;
import com.meridian.substrate.common.repo.CapabilityRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.EvidenceRepository;
import com.meridian.substrate.engine.ai.assertion.AssertionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Diagnostic test plan #8, REST half (engine-spec diagnostic.md §5.3/§10.1):
 *
 * <ul>
 *   <li>{@code POST /ai/generative/commit} → 201 {@code Capability(DRAFT,
 *       aiGenerated=true)}; idempotent replay → 200 with the SAME
 *       capability;</li>
 *   <li>stale evidence → 409 {@code DRAFT_GROUNDING_LOST} (grounding
 *       re-verified at materialization);</li>
 *   <li>{@code POST /ai/generative/reject} → 200 never-error-colored, no
 *       entity row; missing rationale → 400 {@code RATIONALE_REQUIRED};
 *       reject-after-accept → 409 {@code CURATION_CONFLICT};</li>
 *   <li><b>no bulk verb exists</b> — API-surface assertion over the curation
 *       and generative-commit controllers (compile-time absence is pinned by
 *       the engine module's {@code DraftCommitServiceTest}).</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "substrate.llm.provider=mock",
        "substrate.diagnostic.sync=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class GenerativeCommitApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DomainRepository domainRepository;
    @Autowired EvidenceRepository evidenceRepository;
    @Autowired CapabilityRepository capabilityRepository;
    @Autowired AssertionService assertionService;

    private UUID domainId;
    private Evidence evidence;

    @BeforeEach
    void fixture() {
        Domain domain = new Domain();
        domain.setName("generative-commit-api " + UUID.randomUUID());
        domain.setCreatedBy("test");
        domainId = domainRepository.save(domain).getId();

        evidence = new Evidence();
        evidence.setDomainId(domainId);
        evidence.setEntityId(UUID.randomUUID());
        evidence.setEntityType("capability");
        evidence.setLabel("Committed from glacier-workflow-notes.md");
        evidence.setSourceFile("glacier-workflow-notes.md");
        evidence.setExtractor("ingestion");
        evidence.setConfidence(0.95f);
        evidence.setDocumentRegistryId(UUID.randomUUID());
        evidence.setCreatedBy("test");
        evidence = evidenceRepository.save(evidence);
    }

    // ------------------------------------------------------------- commit

    @Test
    void commit_201CapabilityDraft_thenIdempotentReplay200() throws Exception {
        Assertion draft = draft(List.of(evidence.getId()));

        MvcResult created = mvc.perform(post("/api/v1/ai/generative/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commitBody(draft.getId(), "k-1"))
                        .header("X-Persona-Label", "Avery Operator"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.status", is("DRAFT")))
                .andExpect(jsonPath("$.data.aiGenerated", is(true)))
                .andExpect(jsonPath("$.data.name", is("claims-intake")))
                .andReturn();
        String capabilityId = mapper.readTree(created.getResponse().getContentAsString())
                .at("/data/id").asText();

        // already-accepted → idempotent 200 with the EXISTING capability id
        mvc.perform(post("/api/v1/ai/generative/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commitBody(draft.getId(), "k-2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.id", is(capabilityId)));

        assertThat(capabilityRepository.findByDomainId(domainId)).hasSize(1);
    }

    @Test
    void commit_staleEvidence_is409DraftGroundingLost_andNothingMaterializes() throws Exception {
        Assertion stale = draft(List.of(evidence.getId(), UUID.randomUUID()));

        mvc.perform(post("/api/v1/ai/generative/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commitBody(stale.getId(), "k-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("DRAFT_GROUNDING_LOST")));

        assertThat(capabilityRepository.findByDomainId(domainId)).isEmpty();
    }

    // ------------------------------------------------------------- reject

    @Test
    void reject_200NeverErrorColored_noEntityRow_thenCommitConflicts() throws Exception {
        Assertion draft = draft(List.of(evidence.getId()));

        ObjectNode reject = mapper.createObjectNode()
                .put("assertionId", draft.getId().toString())
                .put("rationale", "duplicates an existing capability")
                .put("idempotencyKey", "k-1");
        mvc.perform(post("/api/v1/ai/generative/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reject.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.curationStatus", is("REJECTED")));

        assertThat(capabilityRepository.findByDomainId(domainId))
                .as("rejection creates no entity row — the Assertion is the draft store")
                .isEmpty();

        // commit of a rejected draft is a contradiction
        mvc.perform(post("/api/v1/ai/generative/commit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commitBody(draft.getId(), "k-2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("CURATION_CONFLICT")));
    }

    @Test
    void rejectWithoutRationale_is400RationaleRequired() throws Exception {
        Assertion draft = draft(List.of(evidence.getId()));
        ObjectNode body = mapper.createObjectNode()
                .put("assertionId", draft.getId().toString())
                .put("idempotencyKey", "k-1");

        mvc.perform(post("/api/v1/ai/generative/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code", is("RATIONALE_REQUIRED")));
    }

    // ------------------------------------------------- no-bulk API surface

    /**
     * Test plan #8: "no bulk verb exists (… + API surface assertion)". Pins
     * the §10.1 write surface structurally: every curation/commit mapping
     * addresses exactly ONE subject — no bulk/batch path tokens, no
     * collection/array of ids anywhere in a handler signature or its request
     * record. Per-item human acceptance is the P6 contract (spec §5.3;
     * ADR-004 D9; EXTENSION POINT(diagnostic-3) stays a Mike decision).
     */
    @Test
    void noBulkVerb_existsOnTheCurationOrGenerativeCommitSurfaces() {
        for (Class<?> controller : List.of(
                FindingCurationController.class, GenerativeCommitController.class)) {
            for (Method handler : controller.getDeclaredMethods()) {
                PostMapping mapping = handler.getAnnotation(PostMapping.class);
                if (mapping == null) {
                    continue;
                }
                for (String path : mapping.value()) {
                    String normalized = path.toLowerCase(Locale.ROOT);
                    assertThat(normalized)
                            .as("no bulk path on %s.%s", controller.getSimpleName(), handler.getName())
                            .doesNotContain("bulk")
                            .doesNotContain("batch")
                            .doesNotContain("accept-all")
                            .doesNotContain("reject-all");
                }
                for (Class<?> param : handler.getParameterTypes()) {
                    assertThat(Collection.class.isAssignableFrom(param) || param.isArray())
                            .as("%s.%s takes no collection parameter",
                                    controller.getSimpleName(), handler.getName())
                            .isFalse();
                    if (param.isRecord()) {
                        for (RecordComponent component : param.getRecordComponents()) {
                            assertThat(Collection.class.isAssignableFrom(component.getType())
                                    || component.getType().isArray())
                                    .as("request record %s.%s carries no id collection",
                                            param.getSimpleName(), component.getName())
                                    .isFalse();
                        }
                    }
                }
            }
        }
    }

    // --------------------------------------------------------------- helpers

    private Assertion draft(List<UUID> evidenceIds) {
        ObjectNode fields = mapper.createObjectNode()
                .put("name", "claims-intake")
                .put("displayName", "Claims Intake")
                .put("description", "Proposed from workflow notes.");
        return assertionService.record(domainId, "draft:capability", "proposes_creation",
                fields.toString(), 0.9f, evidenceIds, ProducedBy.AI_GENERATIVE, null);
    }

    private String commitBody(UUID assertionId, String key) {
        return mapper.createObjectNode()
                .put("assertionId", assertionId.toString())
                .put("idempotencyKey", key)
                .toString();
    }
}
