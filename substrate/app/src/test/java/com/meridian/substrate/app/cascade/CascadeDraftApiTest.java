package com.meridian.substrate.app.cascade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeDraft;
import com.meridian.substrate.common.domain.enums.ProducedBy;
import com.meridian.substrate.common.repo.CascadeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §6.3 cascade-draft REST API (engine-spec cascade.md §6.3; ADR-001 D6) over
 * the production endpoints: create / patch / get / list / discard, issuance
 * (201, then idempotent re-issue 200 with the same cascade), the OPEN-only
 * 409s, per-field bean-validation 400s — plus the disabled half of §12 test
 * #11: with the default {@code substrate.orchestrator.enabled=false} the
 * {@code /suggest} route does not exist (404) while everything else in this
 * class runs green in the very same context (degraded mode is identical by
 * construction, ADR D14). The enabled half lives in
 * {@code CascadeDraftSuggestApiTest}.
 *
 * <p>H2 dev-profile lane; Testcontainers-Postgres in CI is the acceptance gate
 * ({@code PostgresCascadeDraftApiIT}), H2-green never is.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class CascadeDraftApiTest extends CascadeApiTestSupport {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    CascadeRepository cascadeRepository;

    // --------------------------------------------------------- CRUD lifecycle

    @Test
    void createPatchGetListDiscardLifecycle() throws Exception {
        UUID domainId = createDomain("Holding Co");

        MvcResult created = mvc.perform(post("/api/v1/cascade-drafts")
                        .header("X-Persona-Label", ORIGINATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "domainId", domainId.toString(),
                                "authoringMode", "free-form",
                                "originalCritique", "Vendor cost overrun is eroding the synergy case",
                                "acceptanceCriteria", "A remediation plan, or a documented Decline"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.status", is("open")))
                .andExpect(jsonPath("$.data.authoringMode", is("free-form")))
                .andExpect(jsonPath("$.data.suggestionState", is("none")))
                .andExpect(jsonPath("$.data.authorLabel", is(ORIGINATOR)))
                .andExpect(jsonPath("$.data.issuedCascadeId", nullValue()))
                .andReturn();
        UUID draftId = UUID.fromString(json.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText());

        // PATCH while OPEN: partial — untouched fields stay.
        mvc.perform(patch("/api/v1/cascade-drafts/{id}", draftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "summary", "Vendor cost cascade",
                                "responseSlaDays", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary", is("Vendor cost cascade")))
                .andExpect(jsonPath("$.data.responseSlaDays", is(0)))
                .andExpect(jsonPath("$.data.originalCritique",
                        is("Vendor cost overrun is eroding the synergy case")));

        mvc.perform(get("/api/v1/cascade-drafts/{id}", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is(draftId.toString())));

        mvc.perform(get("/api/v1/cascade-drafts")
                        .param("domainId", domainId.toString())
                        .param("status", "open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id", is(draftId.toString())));

        mvc.perform(post("/api/v1/cascade-drafts/{id}/discard", draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("discarded")));

        // Settled drafts refuse mutation: 409 with the spec conflict code.
        mvc.perform(patch("/api/v1/cascade-drafts/{id}", draftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("summary", "too late"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("CASCADE_ILLEGAL_TRANSITION")));

        mvc.perform(get("/api/v1/cascade-drafts")
                        .param("domainId", domainId.toString())
                        .param("status", "open"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    // ----------------------------------------------------- issue + re-issue

    @Test
    void issueIs201AndReissueIs200WithTheSameCascade() throws Exception {
        UUID domainId = createDomain("Holding Co");
        CascadeDraft draft = openDraft(domainId, null, null);

        MvcResult issued = mvc.perform(post("/api/v1/cascade-drafts/{id}/issue", draft.getId())
                        .header("X-Persona-Label", ORIGINATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyKey", key(),
                                "level1OwnerLabel", L1_OWNER))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.currentState", is("in-flight")))
                .andExpect(jsonPath("$.data.chainSeq", is(1)))
                .andExpect(jsonPath("$.data.frontierDepth", is(1)))
                .andExpect(jsonPath("$.data.frontierState", is("looking")))
                .andExpect(jsonPath("$.data.originatedBy", is(ORIGINATOR)))
                .andExpect(jsonPath("$.data.levels", hasSize(2)))
                .andExpect(jsonPath("$.data.levels[1].ownerLabel", is(L1_OWNER)))
                .andReturn();
        UUID cascadeId = UUID.fromString(json.readTree(issued.getResponse().getContentAsString())
                .path("data").path("id").asText());

        // Re-issue of an ISSUED draft → 200 with the EXISTING cascade (ADR D8,
        // draft-row anchored idempotency) — no duplicate aggregate.
        mvc.perform(post("/api/v1/cascade-drafts/{id}/issue", draft.getId())
                        .header("X-Persona-Label", ORIGINATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyKey", key(),
                                "level1OwnerLabel", L1_OWNER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.id", is(cascadeId.toString())));

        mvc.perform(get("/api/v1/cascade-drafts/{id}", draft.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("issued")))
                .andExpect(jsonPath("$.data.issuedCascadeId", is(cascadeId.toString())));

        // No landed suggestion → HUMAN provenance on the issued cascade (§6.3).
        Cascade cascade = cascadeRepository.findById(cascadeId).orElseThrow();
        assertThat(cascade.getProducedBy()).isEqualTo(ProducedBy.HUMAN);
        assertThat(cascade.getDraftId()).isEqualTo(draft.getId());
    }

    @Test
    void issueWithoutCritiqueOrCriteriaIs409IllegalTransition() throws Exception {
        UUID domainId = createDomain("Holding Co");
        MvcResult created = mvc.perform(post("/api/v1/cascade-drafts")
                        .header("X-Persona-Label", ORIGINATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "domainId", domainId.toString(),
                                "authoringMode", "imperative",
                                "originalCritique", "Critique without acceptance criteria"))))
                .andExpect(status().isCreated())
                .andReturn();
        String draftId = json.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        // A1 guard: issue requires non-blank critique + criteria on the draft.
        mvc.perform(post("/api/v1/cascade-drafts/{id}/issue", draftId)
                        .header("X-Persona-Label", ORIGINATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyKey", key(),
                                "level1OwnerLabel", L1_OWNER))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("CASCADE_ILLEGAL_TRANSITION")));
    }

    // ------------------------------------- §12 #11, disabled half (degraded)

    @Test
    void suggestRouteIsAbsent404WhenOrchestratorDisabled() throws Exception {
        UUID domainId = createDomain("Holding Co");
        CascadeDraft draft = openDraft(domainId, null, null);

        // Default substrate.orchestrator.enabled=false: the suggest controller
        // bean does not exist, the route resolves to nothing → 404 envelope
        // (hidden affordance, contract doc). Every other endpoint in this test
        // class is green in this exact same context — degraded mode by
        // construction, no parallel code path.
        mvc.perform(post("/api/v1/cascade-drafts/{id}/suggest", draft.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code", is("NOT_FOUND")));

        // The draft is untouched — no plumbing state was invented.
        mvc.perform(get("/api/v1/cascade-drafts/{id}", draft.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suggestionState", is("none")))
                .andExpect(jsonPath("$.data.orchestratorTaskId", nullValue()));
    }

    // ------------------------------------------------------ 404s + 400s

    @Test
    void unknownDraftIdIs404OnEveryRoute() throws Exception {
        UUID unknown = UUID.randomUUID();
        mvc.perform(get("/api/v1/cascade-drafts/{id}", unknown))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code", is("NOT_FOUND")));
        mvc.perform(patch("/api/v1/cascade-drafts/{id}", unknown)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("summary", "x"))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/cascade-drafts/{id}/discard", unknown))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/cascade-drafts/{id}/issue", unknown)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyKey", key(),
                                "level1OwnerLabel", L1_OWNER))))
                .andExpect(status().isNotFound());
    }

    @Test
    void beanValidation400sArePerField() throws Exception {
        // Missing domainId.
        Map<String, Object> body = new HashMap<>();
        body.put("authoringMode", "free-form");
        mvc.perform(post("/api/v1/cascade-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code", is("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.errors[0].field", is("domainId")));

        // Authoring mode outside the wire vocabulary.
        mvc.perform(post("/api/v1/cascade-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "domainId", UUID.randomUUID().toString(),
                                "authoringMode", "telepathic"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code", is("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.errors[0].field", is("authoringMode")));

        // Negative SLA.
        mvc.perform(post("/api/v1/cascade-drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "domainId", UUID.randomUUID().toString(),
                                "authoringMode", "free-form",
                                "responseSlaDays", -1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field", is("responseSlaDays")));

        // Issue without the mandatory idempotency key (ADR D8).
        UUID domainId = createDomain("Holding Co");
        CascadeDraft draft = openDraft(domainId, null, null);
        mvc.perform(post("/api/v1/cascade-drafts/{id}/issue", draft.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "level1OwnerLabel", L1_OWNER))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code", is("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.errors[0].field", is("idempotencyKey")));

        // Unknown status filter value → 400 with the wire vocabulary named.
        mvc.perform(get("/api/v1/cascade-drafts")
                        .param("domainId", domainId.toString())
                        .param("status", "archived"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code", is("BAD_REQUEST")));
    }

    @Test
    void draftDtoNeverLeaksThroughTheEnvelopeUnwrapped() throws Exception {
        UUID domainId = createDomain("Holding Co");
        CascadeDraft draft = openDraft(domainId, null, null);
        mvc.perform(get("/api/v1/cascade-drafts/{id}", draft.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", notNullValue()))
                .andExpect(jsonPath("$.meta", notNullValue()))
                .andExpect(jsonPath("$.errors", hasSize(0)));
    }
}
