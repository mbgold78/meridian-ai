package com.meridian.substrate.app.cascade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeDraft;
import com.meridian.substrate.common.domain.enums.ProducedBy;
import com.meridian.substrate.common.repo.CascadeRepository;
import com.meridian.substrate.engine.cascade.OrchestratorMcpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §12 test #11, enabled half (engine-spec cascade.md §7/§6.3; ADR-001 D14):
 * with {@code substrate.orchestrator.enabled=true} and the MCP client MOCKED,
 * {@code POST /cascade-drafts/{id}/suggest} → 202 {@code suggestionState=
 * pending}; the suggestion lands via on-read refresh into the
 * {@code suggested_*} columns with the dispatch-provenance digest
 * ({@code {taskId, model, eventsDigest, requestedAt, landedAt}}); a draft
 * issued over adopted suggestion text carries {@code produced_by=AI_ASSISTIVE}
 * (never AI_GENERATIVE — that value lives only inside the provenance JSON);
 * budget refusal / dispatch failure are NON-EVENTS for governance —
 * {@code suggestionState=error} is AI-assist plumbing, the human path
 * continues and issues HUMAN.
 *
 * <p>The disabled half (suggest route absent → 404, everything else green)
 * lives in {@code CascadeDraftApiTest} on the default flag.
 */
// substrate.diagnostic.executor=in-process keeps the diagnostic engine's
// OrchestratorDiagnosticExecutor (engine-spec diagnostic.md §8.2, conditional
// on the SAME orchestrator flag) structurally absent here — this suite
// exercises the cascade suggest lane only and stubs only the cascade seam.
@SpringBootTest(properties = {
        "substrate.orchestrator.enabled=true",
        "substrate.diagnostic.executor=in-process"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class CascadeDraftSuggestApiTest extends CascadeApiTestSupport {

    private static final String SUGGESTED_CRITIQUE =
            "Vendor cost growth is 14% YoY against a 6% benchmark — identify obsoleted contracts.";
    private static final String SUGGESTED_CRITERIA =
            "A depopulation plan with named contracts, or a Decline with documented reasoning.";

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    CascadeRepository cascadeRepository;
    @Autowired
    StubMcpClient mcp;

    @BeforeEach
    void resetStub() {
        mcp.reset();
    }

    // ----------------------------------------------------- happy path (§7 1–4)

    @Test
    void suggestLandsViaOnReadRefresh_andAdoptedTextIssuesAiAssistive() throws Exception {
        UUID domainId = createDomain("Holding Co");
        CascadeDraft draft = openDraft(domainId, null, null);

        // §7 steps 1–2: budget preflight + submit_task → 202, plumbing PENDING.
        mvc.perform(post("/api/v1/cascade-drafts/{id}/suggest", draft.getId()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.suggestionState", is("pending")))
                .andExpect(jsonPath("$.data.orchestratorTaskId", is("task-001")));
        assertThat(mcp.submittedDescriptions).hasSize(1);
        assertThat(mcp.submittedDescriptions.get(0))
                .as("context pack references the read-only GET surface, not payload dumps")
                .contains("/api/v1/cascades?domainId=" + domainId);

        // Still in flight: the on-read refresh leaves it honestly PENDING.
        mvc.perform(get("/api/v1/cascade-drafts/{id}", draft.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suggestionState", is("pending")));

        // §7 steps 3–4: dispatch completes → next read lands the suggestion +
        // provenance digest (the engagement's audit trail of the AI assist).
        mcp.state = OrchestratorMcpClient.DispatchState.COMPLETED;
        mvc.perform(get("/api/v1/cascade-drafts/{id}", draft.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suggestionState", is("ready")))
                .andExpect(jsonPath("$.data.suggestedCritique", is(SUGGESTED_CRITIQUE)))
                .andExpect(jsonPath("$.data.suggestedAcceptanceCriteria", is(SUGGESTED_CRITERIA)))
                .andExpect(jsonPath("$.data.suggestionProvenance.taskId", is("task-001")))
                .andExpect(jsonPath("$.data.suggestionProvenance.model", is("mock-model")))
                .andExpect(jsonPath("$.data.suggestionProvenance.eventsDigest", notNullValue()))
                .andExpect(jsonPath("$.data.suggestionProvenance.requestedAt", notNullValue()))
                .andExpect(jsonPath("$.data.suggestionProvenance.landedAt", notNullValue()))
                // AI_GENERATIVE lives ONLY inside the provenance JSON (ADR D6).
                .andExpect(jsonPath("$.data.suggestionProvenance.producedBy", is("AI_GENERATIVE")));

        // Human adoption: the suggested text is promoted into the draft fields…
        mvc.perform(patch("/api/v1/cascade-drafts/{id}", draft.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "originalCritique", SUGGESTED_CRITIQUE,
                                "acceptanceCriteria", SUGGESTED_CRITERIA))))
                .andExpect(status().isOk());

        // …and ONLY human promotion issues (P6): produced_by = AI_ASSISTIVE.
        MvcResult issued = mvc.perform(post("/api/v1/cascade-drafts/{id}/issue", draft.getId())
                        .header("X-Persona-Label", ORIGINATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyKey", key(),
                                "level1OwnerLabel", L1_OWNER))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.currentState", is("in-flight")))
                .andReturn();
        UUID cascadeId = UUID.fromString(json.readTree(issued.getResponse().getContentAsString())
                .path("data").path("id").asText());
        Cascade cascade = cascadeRepository.findById(cascadeId).orElseThrow();
        assertThat(cascade.getProducedBy()).isEqualTo(ProducedBy.AI_ASSISTIVE);
    }

    // ------------------------------------------- plumbing failures, non-events

    @Test
    void budgetRefusalIsANonEvent_humanPathContinuesAndIssuesHuman() throws Exception {
        UUID domainId = createDomain("Holding Co");
        CascadeDraft draft = openDraft(domainId, null, null);
        mcp.approved = false;
        mcp.budgetNote = "engagement budget cap reached";

        // Refusal → 202 (not an error envelope): suggestionState=error is
        // AI-assist plumbing only, never governance state (§2, §6.3 DTO note).
        mvc.perform(post("/api/v1/cascade-drafts/{id}/suggest", draft.getId()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.suggestionState", is("error")))
                .andExpect(jsonPath("$.data.status", is("open")))
                .andExpect(jsonPath("$.data.suggestionProvenance.note",
                        containsString("budget refused")));
        assertThat(mcp.submittedDescriptions).as("no dispatch after refusal").isEmpty();

        // The human authoring path continues untouched and issues HUMAN.
        mvc.perform(post("/api/v1/cascade-drafts/{id}/issue", draft.getId())
                        .header("X-Persona-Label", ORIGINATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "idempotencyKey", key(),
                                "level1OwnerLabel", L1_OWNER))))
                .andExpect(status().isCreated());
        CascadeDraft issued = draftRepository.findById(draft.getId()).orElseThrow();
        Cascade cascade = cascadeRepository.findById(issued.getIssuedCascadeId()).orElseThrow();
        assertThat(cascade.getProducedBy()).isEqualTo(ProducedBy.HUMAN);
    }

    @Test
    void dispatchThatEndsWithoutResultGoesToPlumbingErrorOnRead() throws Exception {
        UUID domainId = createDomain("Holding Co");
        CascadeDraft draft = openDraft(domainId, null, null);

        mvc.perform(post("/api/v1/cascade-drafts/{id}/suggest", draft.getId()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.suggestionState", is("pending")));

        mcp.state = OrchestratorMcpClient.DispatchState.FAILED;
        mvc.perform(get("/api/v1/cascade-drafts/{id}", draft.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.suggestionState", is("error")))
                .andExpect(jsonPath("$.data.status", is("open")));
    }

    @Test
    void suggestOnANonOpenDraftIs409() throws Exception {
        UUID domainId = createDomain("Holding Co");
        CascadeDraft draft = openDraft(domainId, null, null);
        mvc.perform(post("/api/v1/cascade-drafts/{id}/discard", draft.getId()))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/cascade-drafts/{id}/suggest", draft.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("CASCADE_ILLEGAL_TRANSITION")));
    }

    // -------------------------------------------------------------- mock seam

    /**
     * §7 "mock the MCP client for tests": a programmable stub bound at the
     * {@link OrchestratorMcpClient} transport seam — exactly what the future
     * Streamable-HTTP implementation will replace.
     */
    @TestConfiguration
    static class MockOrchestratorConfig {

        @Bean
        StubMcpClient stubMcpClient() {
            return new StubMcpClient();
        }
    }

    static class StubMcpClient implements OrchestratorMcpClient {

        boolean approved;
        String budgetNote;
        DispatchState state;
        final List<String> submittedDescriptions = new java.util.ArrayList<>();

        void reset() {
            approved = true;
            budgetNote = "within budget";
            state = DispatchState.IN_FLIGHT;
            submittedDescriptions.clear();
        }

        @Override
        public BudgetDecision checkBudgetForTask(String taskType, String description) {
            assertThat(taskType).isEqualTo("cascade-critique-draft");
            return new BudgetDecision(approved, budgetNote);
        }

        @Override
        public String submitTask(String agentRole, String description, String taskType) {
            assertThat(agentRole).isEqualTo("engineer");
            assertThat(taskType).isEqualTo("cascade-critique-draft");
            submittedDescriptions.add(description);
            return "task-001";
        }

        @Override
        public DispatchState getDispatchStatus(String taskId) {
            return state;
        }

        @Override
        public DispatchResult getDispatchResult(String taskId) {
            return new DispatchResult(SUGGESTED_CRITIQUE, SUGGESTED_CRITERIA, "mock-model");
        }

        @Override
        public List<String> getDispatchEvents(String taskId) {
            return List.of("{\"event\":\"dispatched\"}", "{\"event\":\"completed\"}");
        }
    }
}
