package com.meridian.substrate.app.phase2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C5 acceptance — decision-log / design controllers over the C4 hash-chain
 * engine: append → chain linkage → {@code /decision-log/verify} green;
 * approve/reject workflow; impact analysis against the mock provider.
 *
 * <p>{@code appendDecision} is invoked directly from the controller (no
 * controller-layer {@code @Transactional}) per the C4 transaction-shape
 * contract. Under MockMvc the call joins this test's transaction on the same
 * thread, so all rows roll back at test end.
 */
@SpringBootTest(properties = "substrate.llm.provider=mock")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class C5DecisionLogEndpointsTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode postDecision(UUID domainId, String title) throws Exception {
        String body = mvc.perform(post("/api/v1/design/decisions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "domainId": "%s",
                                  "decisionType": "CONSOLIDATE",
                                  "status": "PROPOSED",
                                  "title": "%s",
                                  "description": "Collapse the duplicate GRC stack",
                                  "rationale": "Two systems, one job"
                                }
                                """.formatted(domainId, title)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentHash", notNullValue()))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    @Test
    void appendChainsDecisionsAndVerifyReportsGreen() throws Exception {
        UUID domainId = UUID.randomUUID();

        JsonNode first = postDecision(domainId, "Consolidate GRC platforms");
        assertThat(first.path("previousHash").asText()).isEqualTo("GENESIS");

        JsonNode second = postDecision(domainId, "Retire the legacy issues portal");
        assertThat(second.path("previousHash").asText())
                .isEqualTo(first.path("currentHash").asText());

        mvc.perform(get("/api/v1/decision-log").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].title", is("Consolidate GRC platforms")))
                .andExpect(jsonPath("$.data[1].title", is("Retire the legacy issues portal")));

        mvc.perform(get("/api/v1/decision-log/verify").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid", is(true)))
                .andExpect(jsonPath("$.data.totalDecisions", is(2)));

        // /design/decisions mirrors the chronological log
        mvc.perform(get("/api/v1/design/decisions").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)));

        // Single-decision read
        mvc.perform(get("/api/v1/design/decisions/{id}", first.path("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is(first.path("id").asText())));
    }

    @Test
    void unknownDecisionYieldsEnvelopedNotFound() throws Exception {
        mvc.perform(get("/api/v1/design/decisions/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code", is("NOT_FOUND")));
    }

    @Test
    void approveAndRejectUpdateStatus_breakingChainVerifyAsInHomepoc() throws Exception {
        UUID domainId = UUID.randomUUID();
        JsonNode decision = postDecision(domainId, "Decision pending approval");
        String id = decision.path("id").asText();

        mvc.perform(post("/api/v1/design/decisions/{id}/approve", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("APPROVED")));

        // Latent homepoc behavior preserved deliberately (see DesignController
        // javadoc): status is part of the frozen canonical hash input, so the
        // status mutation makes verification flag this decision. Pinned here so
        // a future fix is a conscious contract change, not an accident.
        mvc.perform(get("/api/v1/decision-log/verify").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid", is(false)));

        mvc.perform(post("/api/v1/design/decisions/{id}/reject", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("REJECTED")));
    }

    @Test
    void impactAnalysisRoundTripsAgainstMockWithParseFallback() throws Exception {
        // The mock provider returns prose, not JSON → the service's documented
        // fallback wraps it as the summary instead of failing.
        mvc.perform(post("/api/v1/design/impact-analysis")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "domainId": "%s",
                                  "systemIds": ["%s"],
                                  "consolidationDescription": "Merge GRC platforms"
                                }
                                """.formatted(UUID.randomUUID(), UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary", containsString("mock response")))
                .andExpect(jsonPath("$.data.recommendation",
                        containsString("Unable to parse structured response")));
    }
}
