package com.meridian.substrate.app.diagnostic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.DiagnosticRunBatch;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.SynthesisRun;
import com.meridian.substrate.common.repo.DiagnosticRunBatchRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.repo.SynthesisRunRepository;
import com.meridian.substrate.engine.ai.client.MockAnthropicClient;
import org.junit.jupiter.api.AfterEach;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The §10.1 run-lifecycle REST surface + the never-error-colored contract
 * (engine-spec diagnostic.md §10.1/§10.2; test plan #17 for the diagnostic
 * verbs landed in build step 4):
 *
 * <ul>
 *   <li>POST start → 202 {@code {runId}}; dryRun → 200 plan body; missing
 *       idempotencyKey → 400; second active run → 409
 *       {@code DIAGNOSTIC_RUN_ACTIVE};</li>
 *   <li>GET detail renders the batch ledger + filter histogram; a committed
 *       run's findings walk {@code chainComplete=true} through the step-2
 *       provenance read model;</li>
 *   <li>ZERO_YIELD renders as a 200 PRODUCT outcome (loud reason field,
 *       empty errors), never an exception envelope;</li>
 *   <li>cancel → 200 with empty errors, idempotent on terminal runs; resume
 *       of a terminal run → idempotent no-op 200.</li>
 * </ul>
 *
 * <p>Mock provider + {@code substrate.diagnostic.sync=true}: the run
 * completes inside the POST. H2 fast lane — Testcontainers-Postgres remains
 * the acceptance gate.
 */
@SpringBootTest(properties = {
        "substrate.llm.provider=mock",
        "substrate.diagnostic.sync=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class DiagnosticRunApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DomainRepository domainRepository;
    @Autowired SynthesisRunRepository runRepository;
    @Autowired DiagnosticRunBatchRepository batchRepository;
    @Autowired FindingRepository findingRepository;
    @Autowired MockAnthropicClient mockClient;

    private UUID domainId;

    @BeforeEach
    void fixture() {
        Domain domain = new Domain();
        domain.setName("diag-api " + UUID.randomUUID());
        domain.setCreatedBy("test");
        domainId = domainRepository.save(domain).getId();
    }

    @AfterEach
    void disarmScript() {
        mockClient.setDiagnosticScript("");
    }

    // ----------------------------------------------------- start + detail

    @Test
    void startRun202_committedRun_rendersLedgerAndWalksProvenance() throws Exception {
        mockClient.setDiagnosticScript(MockAnthropicClient.SCRIPT_STRUCTURAL);

        MvcResult started = mvc.perform(post("/api/v1/diagnostics/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody(1, null)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.runId", notNullValue()))
                .andReturn();
        UUID runId = UUID.fromString(mapper.readTree(
                started.getResponse().getContentAsString()).at("/data/runId").asText());

        mvc.perform(get("/api/v1/diagnostics/runs/{id}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.status", is("COMPLETED")))
                .andExpect(jsonPath("$.data.findingsCommitted", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.executor", is("in-process")))
                .andExpect(jsonPath("$.data.batches", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.data.batches[0].status", is("COMPLETED")))
                .andExpect(jsonPath("$.data.totalInputTokens", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.totalCostEstimate", notNullValue()));

        // run list, newest first
        mvc.perform(get("/api/v1/diagnostics/runs").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].runId", is(runId.toString())));

        // the committed finding carries the full P6 chain shape: STRUCTURAL
        // terminates at the Assertion — a COMPLETE chain (spec §5.2/§6.2)
        List<Finding> findings = findingRepository.findByDomainIdAndAiGeneratedTrue(domainId);
        assertThat(findings).isNotEmpty();
        Finding finding = findings.get(0);
        assertThat(finding.getAssertionId()).isNotNull();
        mvc.perform(get("/api/v1/findings/{id}/provenance", finding.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chainComplete", is(true)))
                .andExpect(jsonPath("$.data.groundingMode", is("EXISTENCE_ONLY")))
                .andExpect(jsonPath("$.data.finding.curationStatus", is("PENDING_REVIEW")));
    }

    @Test
    void dryRun_returns200PlanBody_andPersistsNothing() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("domainId", domainId.toString());
        body.put("idempotencyKey", "dry-1");
        body.put("dryRun", true);

        mvc.perform(post("/api/v1/diagnostics/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalBatches", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.batches", hasSize(greaterThanOrEqualTo(1))));

        assertThat(runRepository.findByDomainIdAndRunTypeOrderByCreatedAtDesc(
                domainId, "diagnostic")).isEmpty();
    }

    @Test
    void missingIdempotencyKey_is400Validation() throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("domainId", domainId.toString());

        mvc.perform(post("/api/v1/diagnostics/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void secondActiveRun_is409DiagnosticRunActive() throws Exception {
        activeRun();

        mvc.perform(post("/api/v1/diagnostics/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody(0, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("DIAGNOSTIC_RUN_ACTIVE")));
    }

    // --------------------------------------- #17 never-error-colored contract

    @Test
    void zeroYield_rendersAs200ProductOutcome_neverAnErrorEnvelope() throws Exception {
        mockClient.setDiagnosticScript(MockAnthropicClient.SCRIPT_UNGROUNDED_CONTENT);

        MvcResult started = mvc.perform(post("/api/v1/diagnostics/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody(1, null)))
                .andExpect(status().isAccepted())
                .andReturn();
        String runId = mapper.readTree(started.getResponse().getContentAsString())
                .at("/data/runId").asText();

        mvc.perform(get("/api/v1/diagnostics/runs/{id}", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.status", is("ZERO_YIELD")))
                .andExpect(jsonPath("$.data.reason", is("ALL_FILTERED")))
                .andExpect(jsonPath("$.data.findingsCommitted", is(0)))
                .andExpect(jsonPath("$.data.explanation", notNullValue()));
    }

    @Test
    void cancel_is200NeutralAndIdempotent() throws Exception {
        SynthesisRun run = activeRun();
        DiagnosticRunBatch pending = new DiagnosticRunBatch();
        pending.setSynthesisRunId(run.getId());
        pending.setBatchNo(1);
        pending.setScope("domain");
        pending.setStatus(DiagnosticRunBatch.STATUS_PENDING);
        pending.setCreatedBy("test");
        batchRepository.save(pending);

        mvc.perform(post("/api/v1/diagnostics/runs/{id}/cancel", run.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.status", is("CANCELLED")))
                .andExpect(jsonPath("$.data.reason", nullValue()));

        // idempotent — and STILL never error-colored
        mvc.perform(post("/api/v1/diagnostics/runs/{id}/cancel", run.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.status", is("CANCELLED")));
    }

    @Test
    void resumeOfTerminalRun_isIdempotentNoOp200() throws Exception {
        mockClient.setDiagnosticScript(MockAnthropicClient.SCRIPT_STRUCTURAL);
        MvcResult started = mvc.perform(post("/api/v1/diagnostics/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(startBody(0, null)))
                .andExpect(status().isAccepted())
                .andReturn();
        String runId = mapper.readTree(started.getResponse().getContentAsString())
                .at("/data/runId").asText();

        mvc.perform(post("/api/v1/diagnostics/runs/{id}/resume", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.status", is("COMPLETED")));
    }

    // --------------------------------------------------------------- helpers

    private String startBody(int requiredMinFindings, String idempotencyKey) {
        ObjectNode body = mapper.createObjectNode();
        body.put("domainId", domainId.toString());
        body.put("requiredMinFindings", requiredMinFindings);
        body.put("idempotencyKey", idempotencyKey != null ? idempotencyKey
                : "api-" + UUID.randomUUID());
        return body.toString();
    }

    private SynthesisRun activeRun() {
        SynthesisRun run = new SynthesisRun();
        run.setDomainId(domainId);
        run.setRunType("diagnostic");
        run.setStatus("RUNNING");
        run.setStartedAt(Instant.now());
        run.setCreatedBy("test");
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("requiredMinFindings", 0);
        metadata.put("executor", "in-process");
        run.setMetadata(metadata);
        return runRepository.save(run);
    }
}
