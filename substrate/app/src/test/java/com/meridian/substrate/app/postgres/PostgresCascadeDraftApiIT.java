package com.meridian.substrate.app.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.app.cascade.CascadeApiTestSupport;
import com.meridian.substrate.common.domain.CascadeDraft;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §6.3 draft-API Postgres gate (engine-spec cascade.md §6.3 over real Postgres
 * 16): create → patch → issue (201, the draft-row {@code PESSIMISTIC_WRITE}
 * issuance anchor is real {@code SELECT … FOR UPDATE} here) → idempotent
 * re-issue (200, same cascade) → patch-after-issue 409, plus the §12 test #11
 * disabled half: with the default {@code substrate.orchestrator.enabled=false}
 * the {@code /suggest} route is absent (404) while the whole authoring surface
 * above runs green in the same context.
 *
 * <p><b>Docker-less machines (e.g. the Windows dev box):</b> self-disables via
 * {@code @Testcontainers(disabledWithoutDocker = true)}; CI (ubuntu-latest)
 * runs the real gate. H2-green is never acceptance (port-map §4.4).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class PostgresCascadeDraftApiIT extends CascadeApiTestSupport {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @Test
    @Transactional
    void draftAuthoringLifecycleHoldsOnPostgres() throws Exception {
        UUID domainId = createDomain("Holding Co");

        // Create + patch.
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
                .andReturn();
        UUID draftId = UUID.fromString(json.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText());
        mvc.perform(patch("/api/v1/cascade-drafts/{id}", draftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("summary", "Vendor cost cascade"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary", is("Vendor cost cascade")));

        // Issue → 201; re-issue → 200 with the SAME cascade (draft-row anchored).
        MvcResult issued = mvc.perform(post("/api/v1/cascade-drafts/{id}/issue", draftId)
                        .header("X-Persona-Label", ORIGINATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyKey", key(),
                                "level1OwnerLabel", L1_OWNER))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.currentState", is("in-flight")))
                .andExpect(jsonPath("$.data.frontierState", is("looking")))
                .andReturn();
        String cascadeId = json.readTree(issued.getResponse().getContentAsString())
                .path("data").path("id").asText();
        mvc.perform(post("/api/v1/cascade-drafts/{id}/issue", draftId)
                        .header("X-Persona-Label", ORIGINATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyKey", key(),
                                "level1OwnerLabel", L1_OWNER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is(cascadeId)));

        // Settled draft refuses mutation: 409 with the issued cascade in data.
        mvc.perform(patch("/api/v1/cascade-drafts/{id}", draftId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("summary", "too late"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("CASCADE_ILLEGAL_TRANSITION")))
                .andExpect(jsonPath("$.data.id", is(cascadeId)));
    }

    @Test
    @Transactional
    void suggestRouteIsAbsent404WhenOrchestratorDisabledOnPostgres() throws Exception {
        UUID domainId = createDomain("Holding Co");
        CascadeDraft draft = openDraft(domainId, null, null);

        mvc.perform(post("/api/v1/cascade-drafts/{id}/suggest", draft.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code", is("NOT_FOUND")));

        mvc.perform(get("/api/v1/cascade-drafts/{id}", draft.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suggestionState", is("none")));
    }
}
