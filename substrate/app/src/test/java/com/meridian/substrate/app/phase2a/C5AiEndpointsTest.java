package com.meridian.substrate.app.phase2a;

import com.meridian.substrate.common.domain.ChatInteraction;
import com.meridian.substrate.common.repo.ChatInteractionRepository;
import com.meridian.substrate.engine.ai.cost.CostSummary;
import com.meridian.substrate.engine.ai.cost.CostTracker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C5 acceptance — AI-facing controllers over the C3 engine services, exercised
 * end-to-end through the HTTP layer against the mock LLM provider
 * ({@code substrate.llm.provider=mock}, fixture seam S8):
 *
 * <ul>
 *   <li>POST /api/v1/chat round-trips against the mock and persists a truthful
 *       {@code ChatInteraction} (refusalDetected, token totals, cost estimate).</li>
 *   <li>GET /api/v1/llm/cost-summary is non-zero after a mock call (S12 — the
 *       homepoc endpoint always returned zeros).</li>
 *   <li>The three AI mode endpoints (assistive/generative/validative) respond
 *       with their structured DTO contracts inside the standard envelope.</li>
 *   <li>GET /api/v1/dna and /api/v1/dna/preamble serve the engine DnaService.</li>
 * </ul>
 *
 * <p>Cost assertions are delta-based: {@link CostTracker} is a process-lifetime
 * singleton shared across test classes in the cached context.
 */
@SpringBootTest(properties = "substrate.llm.provider=mock")
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class C5AiEndpointsTest {

    @Autowired MockMvc mvc;
    @Autowired ChatInteractionRepository chatInteractionRepository;
    @Autowired CostTracker costTracker;

    @Test
    void chatRoundTripsAgainstMockAndPersistsTruthfulInteraction() throws Exception {
        UUID domainId = UUID.randomUUID();
        CostSummary before = costTracker.getSummary();

        mvc.perform(post("/api/v1/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"domainId": "%s", "message": "What systems exist in this domain?"}
                                """.formatted(domainId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message", containsString("Mock tool-calling response")))
                .andExpect(jsonPath("$.data.toolCalls", hasSize(0)))
                .andExpect(jsonPath("$.data.inputTokens", is(40)))
                .andExpect(jsonPath("$.data.outputTokens", is(20)))
                .andExpect(jsonPath("$.meta.requestId", notNullValue()));

        // Persisted interaction carries truthful refusal flag + token totals (C5 wiring note b)
        List<ChatInteraction> interactions =
                chatInteractionRepository.findByDomainIdOrderByCreatedAtDesc(domainId);
        assertThat(interactions).hasSize(1);
        ChatInteraction interaction = interactions.get(0);
        assertThat(interaction.getUserMessage()).contains("What systems exist");
        assertThat(interaction.getAssistantMessage()).contains("Mock tool-calling response");
        assertThat(interaction.getRefusalDetected()).isFalse();
        assertThat(interaction.getInputTokens()).isEqualTo(40);
        assertThat(interaction.getOutputTokens()).isEqualTo(20);
        assertThat(interaction.getLatencyMs()).isNotNull();
        assertThat(interaction.getModel()).isNotNull();
        // dev yml carries non-zero substrate.llm.cost.* rates → estimate is positive
        assertThat(interaction.getCostEstimate()).isGreaterThan(0.0);

        // S12: the orchestrator recorded usage — /llm/cost-summary is non-zero and grew
        CostSummary after = costTracker.getSummary();
        assertThat(after.totalCalls()).isGreaterThan(before.totalCalls());
        assertThat(after.totalInputTokens()).isEqualTo(before.totalInputTokens() + 40);
        assertThat(after.totalOutputTokens()).isEqualTo(before.totalOutputTokens() + 20);

        mvc.perform(get("/api/v1/llm/cost-summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalInputTokens", greaterThan(0)))
                .andExpect(jsonPath("$.data.totalOutputTokens", greaterThan(0)))
                .andExpect(jsonPath("$.data.totalCalls", greaterThan(0)))
                // value(..., Double.class) coerces — JsonPath may surface the
                // JSON double as BigDecimal, which breaks a raw Matcher<Double>
                .andExpect(jsonPath("$.data.totalEstimatedCost")
                        .value(greaterThan(0.0), Double.class));
    }

    @Test
    void assistiveEndpointRespondsWithStructuredContract() throws Exception {
        UUID domainId = UUID.randomUUID();

        // Mock provider returns unstructured text → no parseable suggestions →
        // structured empty result with explanation (never a 5xx).
        mvc.perform(post("/api/v1/ai/assistive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "domainId": "%s",
                                  "surface": "CAPABILITY_DESIGNER",
                                  "entityType": "capability",
                                  "entityId": "%s",
                                  "queryType": "SUGGEST_DESCRIPTION",
                                  "queryContext": {"currentText": "draft description"}
                                }
                                """.formatted(domainId, UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suggestions", hasSize(0)))
                .andExpect(jsonPath("$.data.explanation", notNullValue()));
    }

    @Test
    void assistiveEndpointRejectsUnknownQueryTypeStructurally() throws Exception {
        mvc.perform(post("/api/v1/ai/assistive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "domainId": "%s",
                                  "surface": "CAPABILITY_DESIGNER",
                                  "entityType": "capability",
                                  "queryType": "NOT_A_REAL_QUERY_TYPE",
                                  "queryContext": {}
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suggestions", hasSize(0)))
                .andExpect(jsonPath("$.data.explanation",
                        containsString("not implemented")));
    }

    @Test
    void generativeEndpointRespondsWithStructuredContract() throws Exception {
        mvc.perform(post("/api/v1/ai/generative")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "domainId": "%s",
                                  "surface": "CAPABILITY_DESIGNER",
                                  "entityType": "capability"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.drafts", hasSize(0)))
                .andExpect(jsonPath("$.data.explanation", notNullValue()));
    }

    @Test
    void validativeEndpointRespondsWithStructuredContract() throws Exception {
        mvc.perform(post("/api/v1/ai/validative")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "domainId": "%s",
                                  "surface": "CAPABILITY_DESIGNER",
                                  "scope": "domain"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.findings", hasSize(0)))
                .andExpect(jsonPath("$.data.explanation", notNullValue()));
    }

    @Test
    void dnaEndpointsServeEngineConfigAndPreamble() throws Exception {
        mvc.perform(get("/api/v1/dna"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectName", notNullValue()))
                .andExpect(jsonPath("$.data.reductionMode", notNullValue()));

        mvc.perform(get("/api/v1/dna/preamble"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.preamble", containsString("Project DNA")));
    }
}
