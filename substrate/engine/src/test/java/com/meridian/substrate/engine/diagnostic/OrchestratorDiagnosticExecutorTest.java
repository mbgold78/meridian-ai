package com.meridian.substrate.engine.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.ai.CompletionChunk;
import com.meridian.substrate.common.ai.CompletionRequest;
import com.meridian.substrate.common.ai.CompletionResponse;
import com.meridian.substrate.common.ai.ContentBlock;
import com.meridian.substrate.common.ai.LlmClient;
import com.meridian.substrate.common.ai.TokenUsage;
import com.meridian.substrate.common.ai.ToolCallRequest;
import com.meridian.substrate.common.ai.ToolCallResponse;
import com.meridian.substrate.common.domain.DiagnosticRunBatch;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.enums.PrincipalType;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.engine.EngineTestApplication;
import com.meridian.substrate.engine.contract.gateway.ContractGateway;
import com.meridian.substrate.engine.contract.gateway.GovernedAction;
import com.meridian.substrate.engine.contract.gateway.GovernedActions;
import com.meridian.substrate.engine.diagnostic.read.DiagnosticRunDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Diagnostic test plan #11 (engine-spec diagnostic.md §8.2/§14; ADR-004 D11;
 * ADR-002 PEP #3), driven through the REAL run lifecycle with the orchestrated
 * lane's transport seam ({@link OrchestratorDispatchClient}) stubbed and the
 * {@link ContractGateway} mocked:
 *
 * <ul>
 *   <li>dispatched results transit the SAME {@link CandidateFilter} — a
 *       tampered ungrounded candidate from the "agent" is filtered, the valid
 *       one commits with {@code metadata.dispatchTaskId} + an Assertion;</li>
 *   <li>the apply is governed: {@code actionType=apply_dispatch_result},
 *       SERVICE principal, dispatch id stamped;</li>
 *   <li>scripted budget refusal → in-process fallback recorded
 *       {@code executor='in-process-fallback'} (and the gateway is NOT
 *       consulted — nothing dispatched was applied);</li>
 *   <li>{@code fallback=skip} → batch {@code SKIPPED_BUDGET}, neutral
 *       (error column null, run reason null) and non-blocking for
 *       COMPLETED;</li>
 *   <li>an explicit {@code executor=in-process} request bypasses the
 *       orchestrated lane even while the bean exists.</li>
 * </ul>
 */
@SpringBootTest(classes = EngineTestApplication.class, properties = {
        "substrate.diagnostic.sync=true",
        "substrate.orchestrator.enabled=true"
})
@ActiveProfiles("test")
@Import(OrchestratorDiagnosticExecutorTest.StubConfig.class)
@Transactional
class OrchestratorDiagnosticExecutorTest {

    private static final String VALID_PLUS_TAMPERED = """
            [{"type":"STRUCTURAL","severity":"MEDIUM","title":"Dispatched observation",
              "description":"Agent-proposed structural observation.","suggestedAction":"Review.","evidenceIds":[]},
             {"type":"CONTENT","severity":"HIGH","description":"Tampered ungrounded agent claim.",
              "suggestedAction":"Must be filtered.","evidenceIds":["00000000-0000-0000-0000-000000000000"]}]""";

    private static final String VALID_STRUCTURAL = """
            [{"type":"STRUCTURAL","severity":"MEDIUM","title":"In-process fallback observation",
              "description":"Generated on the in-process fallback lane.","suggestedAction":"Review.","evidenceIds":[]}]""";

    @Autowired DiagnosticRunService runService;
    @Autowired DiagnosticProperties properties;
    @Autowired DomainRepository domainRepository;
    @Autowired FindingRepository findingRepository;
    @Autowired StubDispatchClient dispatch;
    @Autowired ContractGateway gateway;
    @Autowired ScriptedLlmClient llmStub;
    @Autowired ObjectMapper mapper;

    private UUID domainId;

    @BeforeEach
    void fixture() {
        Mockito.reset(gateway);
        dispatch.reset();
        llmStub.respondWith("prose only");
        properties.getDispatch().setFallback(DiagnosticProperties.Dispatch.FALLBACK_IN_PROCESS);
        Domain domain = new Domain();
        domain.setName("orch-exec " + UUID.randomUUID());
        domain.setCreatedBy("test");
        domainId = domainRepository.save(domain).getId();
    }

    // -------------------------------------------- one chokepoint + governed apply

    @Test
    void dispatchedResults_transitCandidateFilter_andApplyIsGovernedService() {
        dispatch.candidatesJson = VALID_PLUS_TAMPERED;

        DiagnosticRunDetail detail = runService.getRun(startRun(0, null).runId());

        assertThat(detail.status()).isEqualTo(DiagnosticRunService.STATUS_COMPLETED);
        assertThat(detail.executor()).isEqualTo("orchestrator");
        assertThat(detail.findingsCommitted()).isEqualTo(1);
        assertThat(detail.candidatesReturned()).isEqualTo(2);
        assertThat(detail.filterHistogram().get("ungrounded"))
                .as("the tampered agent candidate is filtered by the SAME CandidateFilter")
                .isEqualTo(1);
        assertThat(detail.executorMix().get("orchestrator")).isEqualTo(1);
        assertThat(detail.batches()).singleElement().satisfies(batch -> {
            assertThat(batch.executor()).isEqualTo("orchestrator");
            assertThat(batch.status()).isEqualTo(DiagnosticRunBatch.STATUS_COMPLETED);
            assertThat(batch.dispatchTaskId()).isEqualTo(dispatch.lastTaskId());
        });
        assertThat(detail.dispatchProvenance())
                .as("get_dispatch_events digest lands in run metadata (spec §4.5/§8.2)")
                .isNotNull();
        assertThat(detail.dispatchProvenance().get(0).get("taskId").asText())
                .isEqualTo(dispatch.lastTaskId());
        assertThat(detail.dispatchProvenance().get(0).hasNonNull("eventsDigest")).isTrue();

        // The committed finding carries provenance: Assertion + dispatchTaskId.
        List<Finding> findings = findingRepository.findByDomainIdAndAiGeneratedTrue(domainId);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).getAssertionId()).isNotNull();
        assertThat(findings.get(0).getMetadata().path("dispatchTaskId").asText())
                .isEqualTo(dispatch.lastTaskId());
        assertThat(findings.get(0).getMetadata().path("executor").asText())
                .isEqualTo("orchestrator");

        // PEP 3: the apply is a governed SERVICE action carrying the dispatch id.
        ArgumentCaptor<GovernedAction> captor = ArgumentCaptor.forClass(GovernedAction.class);
        verify(gateway).evaluate(captor.capture());
        GovernedAction action = captor.getValue();
        assertThat(action.actionType()).isEqualTo(GovernedActions.APPLY_DISPATCH_RESULT);
        assertThat(action.principal().type()).isEqualTo(PrincipalType.SERVICE);
        assertThat(action.domainId()).isEqualTo(domainId);
        assertThat(action.dispatchId()).isEqualTo(dispatch.lastTaskId());

        // Context pack references the read-only GET surface, never payload dumps.
        assertThat(dispatch.submittedDescriptions).singleElement()
                .asString().contains("/api/v1/domains/" + domainId);
    }

    // ----------------------------------------------------- fallback semantics

    @Test
    void budgetRefusal_fallsBackInProcess_recordedAsFallbackLane() {
        dispatch.approved = false;
        llmStub.respondWith(VALID_STRUCTURAL);

        DiagnosticRunDetail detail = runService.getRun(startRun(0, null).runId());

        assertThat(detail.status()).isEqualTo(DiagnosticRunService.STATUS_COMPLETED);
        assertThat(detail.findingsCommitted()).isEqualTo(1);
        assertThat(detail.batches()).singleElement().satisfies(batch -> {
            assertThat(batch.executor()).isEqualTo("in-process-fallback");
            assertThat(batch.status()).isEqualTo(DiagnosticRunBatch.STATUS_COMPLETED);
            assertThat(batch.dispatchTaskId()).isNull();
        });
        assertThat(detail.executorMix().get("in-process-fallback")).isEqualTo(1);
        // Nothing dispatched was applied — the PEP was never in play.
        verifyNoInteractions(gateway);
    }

    @Test
    void dispatchFailedState_fallsBackInProcess() {
        dispatch.state = OrchestratorDispatchClient.DispatchState.FAILED;
        llmStub.respondWith(VALID_STRUCTURAL);

        DiagnosticRunDetail detail = runService.getRun(startRun(0, null).runId());

        assertThat(detail.status()).isEqualTo(DiagnosticRunService.STATUS_COMPLETED);
        assertThat(detail.batches()).singleElement().satisfies(batch ->
                assertThat(batch.executor()).isEqualTo("in-process-fallback"));
        verifyNoInteractions(gateway);
    }

    @Test
    void fallbackSkip_marksSkippedBudget_neutralAndNonBlocking() {
        dispatch.approved = false;
        properties.getDispatch().setFallback(DiagnosticProperties.Dispatch.FALLBACK_SKIP);

        DiagnosticRunDetail detail = runService.getRun(startRun(0, null).runId());

        assertThat(detail.status())
                .as("SKIPPED_BUDGET never blocks COMPLETED (spec §8.2)")
                .isEqualTo(DiagnosticRunService.STATUS_COMPLETED);
        assertThat(detail.reason()).isNull(); // neutral — no failure vocabulary
        assertThat(detail.explanation())
                .as("committed=0 COMPLETED keeps the mandatory explanation")
                .isNotBlank();
        assertThat(detail.batches()).singleElement().satisfies(batch -> {
            assertThat(batch.status()).isEqualTo(DiagnosticRunBatch.STATUS_SKIPPED_BUDGET);
            assertThat(batch.error()).as("budget refusal is never error-colored").isNull();
        });
        assertThat(findingRepository.findByDomainIdAndAiGeneratedTrue(domainId)).isEmpty();
        verifyNoInteractions(gateway);
    }

    // ------------------------------------------------------- lane resolution

    @Test
    void explicitInProcessRequest_bypassesTheOrchestratedLane() {
        llmStub.respondWith(VALID_STRUCTURAL);

        DiagnosticRunDetail detail = runService.getRun(
                startRun(0, DiagnosticProperties.EXECUTOR_IN_PROCESS).runId());

        assertThat(detail.executor()).isEqualTo("in-process");
        assertThat(detail.batches()).singleElement().satisfies(batch ->
                assertThat(batch.executor()).isEqualTo("in-process"));
        assertThat(dispatch.submittedDescriptions).isEmpty();
        verifyNoInteractions(gateway);
    }

    // --------------------------------------------------------------- helpers

    private DiagnosticRunService.StartResult startRun(int required, String executor) {
        return runService.startRun(new DiagnosticRunService.StartCommand(
                domainId, List.of("systems"), null, null, required, executor,
                false, "orch-" + UUID.randomUUID(), "test-actor", true));
    }

    // ------------------------------------------------------------------ stubs

    @TestConfiguration
    static class StubConfig {

        @Bean
        StubDispatchClient stubDispatchClient() {
            return new StubDispatchClient();
        }

        /** The PEP is pinned via a recording mock — engine tests carry no PDP context. */
        @Bean
        ContractGateway contractGateway() {
            return Mockito.mock(ContractGateway.class);
        }

        @Bean
        @Primary
        ScriptedLlmClient orchestratorTestLlmClient() {
            return new ScriptedLlmClient();
        }
    }

    static class StubDispatchClient implements OrchestratorDispatchClient {
        volatile boolean approved = true;
        volatile DispatchState state = DispatchState.COMPLETED;
        volatile String candidatesJson = "[]";
        final List<String> submittedDescriptions = new ArrayList<>();
        private final AtomicInteger seq = new AtomicInteger();

        void reset() {
            approved = true;
            state = DispatchState.COMPLETED;
            candidatesJson = "[]";
            submittedDescriptions.clear();
        }

        String lastTaskId() {
            return "task-" + seq.get();
        }

        @Override
        public BudgetDecision checkBudgetForTask(String taskType, String description) {
            return new BudgetDecision(approved, approved ? "ok" : "budget exhausted");
        }

        @Override
        public String submitTask(String agentRole, String description, String taskType) {
            submittedDescriptions.add(description);
            return "task-" + seq.incrementAndGet();
        }

        @Override
        public DispatchState getDispatchStatus(String taskId) {
            return state;
        }

        @Override
        public DispatchResult getDispatchResult(String taskId) {
            return new DispatchResult(candidatesJson, "agent-model", 50, 25);
        }

        @Override
        public List<String> getDispatchEvents(String taskId) {
            return List.of("dispatched", "completed");
        }
    }

    static class ScriptedLlmClient implements LlmClient {

        private volatile String finalText = "prose";

        void respondWith(String text) {
            this.finalText = text;
        }

        @Override
        public CompletionResponse complete(CompletionRequest request) {
            return new CompletionResponse("stub", finalText, "stub-model", "end_turn",
                    new TokenUsage(20, 10));
        }

        @Override
        public Flux<CompletionChunk> completeStream(CompletionRequest request) {
            return Flux.just(new CompletionChunk("stub", finalText, "end_turn",
                    new TokenUsage(20, 10)));
        }

        @Override
        public ToolCallResponse completeWithTools(ToolCallRequest request) {
            return new ToolCallResponse("stub", "stub-model", "end_turn",
                    List.of(ContentBlock.text(finalText)), new TokenUsage(30, 15));
        }
    }
}
