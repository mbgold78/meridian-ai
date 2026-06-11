package com.meridian.substrate.engine.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.meridian.substrate.common.domain.SynthesisRun;
import com.meridian.substrate.common.domain.SystemEntity;
import com.meridian.substrate.common.repo.DiagnosticRunBatchRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.repo.SynthesisRunRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import com.meridian.substrate.engine.EngineTestApplication;
import com.meridian.substrate.engine.diagnostic.exception.DiagnosticConflictException;
import com.meridian.substrate.engine.diagnostic.read.DiagnosticRunDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Diagnostic test plan #4 (batch atomicity + resume) and #5 (zero-yield
 * trichotomy matrix) — engine-spec diagnostic.md §4/§10.2/§14, driven through
 * the REAL run lifecycle ({@code sync=true}) with a scripted LLM stub:
 *
 * <ul>
 *   <li>stale-RUNNING (crash between generation and commit) → resume
 *       re-executes, ZERO duplicate findings (the tx boundary is the
 *       dedupe);</li>
 *   <li>resume of a terminal run = idempotent no-op; cancel semantics
 *       (PENDING→CANCELLED, idempotent, neutral); SKIPPED_BUDGET neutral and
 *       non-blocking for COMPLETED;</li>
 *   <li>NO_CANDIDATES → FAILED (always hard fail); all-filtered with
 *       required>0 → ZERO_YIELD; all-filtered with required=0 → COMPLETED +
 *       MANDATORY explanation + yield event; committed&lt;required →
 *       ZERO_YIELD; all_content_filtered + no_content_findings events
 *       fire;</li>
 *   <li>one-active-run conflict (service-lock lane; the racing-starts PG
 *       partial-unique lane is PostgresDiagnosticRunConcurrencyIT);
 *       idempotency-key replay; dryRun persists nothing.</li>
 * </ul>
 *
 * <p>H2 fast lane — Testcontainers-Postgres remains the acceptance gate.
 */
@SpringBootTest(classes = EngineTestApplication.class,
        properties = "substrate.diagnostic.sync=true")
@ActiveProfiles("test")
@Import(DiagnosticRunLifecycleTest.StubConfig.class)
@Transactional
class DiagnosticRunLifecycleTest {

    private static final String VALID_STRUCTURAL = """
            [{"type":"STRUCTURAL","severity":"MEDIUM","title":"Stub observation",
              "description":"Stub structural observation.","suggestedAction":"Review.","evidenceIds":[]}]""";

    private static final String BAD_SEVERITY_MIX = """
            [{"type":"STRUCTURAL","severity":"MEDIUM","description":"Kept one.","suggestedAction":"Review.","evidenceIds":[]},
             {"type":"STRUCTURAL","severity":"URGENT","description":"Hallucinated severity.","suggestedAction":"Filtered.","evidenceIds":[]}]""";

    private static final String UNGROUNDED_CONTENT = """
            [{"type":"CONTENT","severity":"HIGH","description":"Ungrounded claim.",
              "suggestedAction":"Filter me.","evidenceIds":["00000000-0000-0000-0000-000000000000"]}]""";

    @Autowired DiagnosticRunService runService;
    @Autowired DomainRepository domainRepository;
    @Autowired SystemRepository systemRepository;
    @Autowired SynthesisRunRepository runRepository;
    @Autowired DiagnosticRunBatchRepository batchRepository;
    @Autowired FindingRepository findingRepository;
    @Autowired ScriptedLlmClient stub;
    @Autowired ObjectMapper mapper;

    private UUID domainId;

    @BeforeEach
    void fixture() {
        stub.respondWith("no structured findings here, just prose");
        Domain domain = new Domain();
        domain.setName("run-lifecycle " + UUID.randomUUID());
        domain.setCreatedBy("test");
        domainId = domainRepository.save(domain).getId();
    }

    // ------------------------------------------------------------ happy path

    @Test
    void happyPath_completesWithLedgerCountersAndHistogram() {
        addSystem();
        stub.respondWith(BAD_SEVERITY_MIX);

        DiagnosticRunService.StartResult started = startRun(1, List.of("systems"));
        DiagnosticRunDetail detail = runService.getRun(started.runId());

        // 1 systems batch + the always-appended domain-wide batch
        assertThat(detail.batches()).hasSize(2);
        assertThat(detail.status()).isEqualTo(DiagnosticRunService.STATUS_COMPLETED);
        assertThat(detail.findingsCommitted()).isEqualTo(2);   // 1 kept per batch
        assertThat(detail.candidatesReturned()).isEqualTo(4);  // 2 raw per batch
        assertThat(detail.candidatesFiltered()).isEqualTo(2);  // 1 bad severity per batch
        assertThat(detail.filterHistogram().get("badSeverity")).isEqualTo(2);
        assertThat(detail.executorMix().get("in-process")).isEqualTo(2);
        assertThat(detail.completedAt()).isNotNull();
        assertThat(detail.totalInputTokens()).isPositive();
        assertThat(detail.batches()).allSatisfy(batch -> {
            assertThat(batch.status()).isEqualTo(DiagnosticRunBatch.STATUS_COMPLETED);
            assertThat(batch.executor()).isEqualTo("in-process");
        });
        assertThat(findingRepository.findByDomainIdAndAiGeneratedTrue(domainId)).hasSize(2);
        // run row is the run record (no new table)
        SynthesisRun run = runRepository.findById(started.runId()).orElseThrow();
        assertThat(run.getRunType()).isEqualTo("diagnostic");
        assertThat(run.getFindingsGenerated()).isEqualTo(2);
        assertThat(run.getTotalCostEstimate()).isNotNull();
    }

    // --------------------------------------------------- #5 trichotomy matrix

    @Test
    void zeroParseableCandidates_isAlwaysFailedNoCandidates() {
        stub.respondWith("prose only — no JSON array anywhere");

        DiagnosticRunDetail detail = runService.getRun(startRun(0, List.of("systems")).runId());

        assertThat(detail.status()).isEqualTo(DiagnosticRunService.STATUS_FAILED);
        assertThat(detail.reason()).isEqualTo(DiagnosticRunService.REASON_NO_CANDIDATES);
        assertThat(detail.explanation()).isNotBlank();
    }

    @Test
    void allFilteredWithRequired_isZeroYield_withLoudEvents() {
        stub.respondWith(UNGROUNDED_CONTENT);

        DiagnosticRunDetail detail = runService.getRun(startRun(1, List.of("systems")).runId());

        assertThat(detail.status()).isEqualTo(DiagnosticRunService.STATUS_ZERO_YIELD);
        assertThat(detail.reason()).isEqualTo(DiagnosticRunService.REASON_ALL_FILTERED);
        assertThat(detail.findingsCommitted()).isZero();
        assertThat(detail.candidatesReturned()).isPositive();
        assertThat(detail.events()).contains("zero_yield", "all_content_filtered");
    }

    @Test
    void allFilteredWithRequiredZero_isCompletedWithMandatoryExplanationAndYieldEvent() {
        stub.respondWith(UNGROUNDED_CONTENT);

        DiagnosticRunDetail detail = runService.getRun(startRun(0, List.of("systems")).runId());

        assertThat(detail.status()).isEqualTo(DiagnosticRunService.STATUS_COMPLETED);
        assertThat(detail.findingsCommitted()).isZero();
        assertThat(detail.explanation())
                .as("committed=0 COMPLETED requires the mandatory explanation (spec §10.2 lane 2)")
                .isNotBlank();
        assertThat(detail.events()).contains("yield", "all_content_filtered");
    }

    @Test
    void committedBelowRequired_isZeroYieldBelowRequired_andStructuralOnlyIsLoud() {
        stub.respondWith(VALID_STRUCTURAL);

        DiagnosticRunDetail detail = runService.getRun(startRun(5, List.of("systems")).runId());

        assertThat(detail.status()).isEqualTo(DiagnosticRunService.STATUS_ZERO_YIELD);
        assertThat(detail.reason()).isEqualTo(DiagnosticRunService.REASON_BELOW_REQUIRED);
        assertThat(detail.findingsCommitted()).isEqualTo(1);
        assertThat(detail.events()).contains("zero_yield", "no_content_findings");
    }

    // ------------------------------------------------ #4 atomicity + resume

    @Test
    void staleRunningBatch_resumeReExecutes_zeroDuplicateFindings() {
        // Crash shape: process died between generation and commit — run still
        // RUNNING, batch RUNNING with a stale claim, NOTHING committed.
        SynthesisRun run = manualRun(0);
        manualBatch(run.getId(), 1, DiagnosticRunBatch.STATUS_RUNNING,
                Instant.now().minus(Duration.ofMinutes(31)));
        manualBatch(run.getId(), 2, DiagnosticRunBatch.STATUS_PENDING, null);
        stub.respondWith(VALID_STRUCTURAL);

        DiagnosticRunDetail detail = runService.resume(run.getId(), true);

        assertThat(detail.status()).isEqualTo(DiagnosticRunService.STATUS_COMPLETED);
        assertThat(detail.findingsCommitted()).isEqualTo(2);
        assertThat(findingRepository.findByDomainIdAndAiGeneratedTrue(domainId))
                .as("a failed batch committed nothing — re-execution can never duplicate")
                .hasSize(2);

        // Resume of a terminal run is an idempotent no-op (spec §4.4).
        stub.respondWith(BAD_SEVERITY_MIX);
        DiagnosticRunDetail resumedAgain = runService.resume(run.getId(), true);
        assertThat(resumedAgain.status()).isEqualTo(DiagnosticRunService.STATUS_COMPLETED);
        assertThat(findingRepository.findByDomainIdAndAiGeneratedTrue(domainId)).hasSize(2);
    }

    @Test
    void freshRunningClaim_isNotStale_resumeDoesNotReExecuteIt() {
        SynthesisRun run = manualRun(0);
        manualBatch(run.getId(), 1, DiagnosticRunBatch.STATUS_RUNNING, Instant.now());
        stub.respondWith(VALID_STRUCTURAL);

        DiagnosticRunDetail detail = runService.resume(run.getId(), true);

        // The in-flight batch stays claimed; the run is NOT finalized under it.
        assertThat(detail.status()).isEqualTo(DiagnosticRunService.STATUS_RUNNING);
        assertThat(detail.batches().get(0).status()).isEqualTo(DiagnosticRunBatch.STATUS_RUNNING);
        assertThat(findingRepository.findByDomainIdAndAiGeneratedTrue(domainId)).isEmpty();
    }

    @Test
    void cancel_flipsPendingBatches_isIdempotent_andNeverErrorColored() {
        SynthesisRun run = manualRun(0);
        manualBatch(run.getId(), 1, DiagnosticRunBatch.STATUS_PENDING, null);
        manualBatch(run.getId(), 2, DiagnosticRunBatch.STATUS_PENDING, null);

        DiagnosticRunDetail cancelled = runService.cancel(run.getId());

        assertThat(cancelled.status()).isEqualTo(DiagnosticRunService.STATUS_CANCELLED);
        assertThat(cancelled.completedAt()).isNotNull();
        assertThat(cancelled.batches()).allSatisfy(batch ->
                assertThat(batch.status()).isEqualTo(DiagnosticRunBatch.STATUS_CANCELLED));
        assertThat(cancelled.reason()).isNull(); // neutral — no failure vocabulary

        // Idempotent on terminal; resume after cancel is equally a no-op.
        assertThat(runService.cancel(run.getId()).status())
                .isEqualTo(DiagnosticRunService.STATUS_CANCELLED);
        assertThat(runService.resume(run.getId(), true).status())
                .isEqualTo(DiagnosticRunService.STATUS_CANCELLED);
    }

    @Test
    void skippedBudgetBatches_areNeutralAndNeverBlockCompleted() {
        SynthesisRun run = manualRun(1);
        DiagnosticRunBatch done = manualBatch(run.getId(), 1,
                DiagnosticRunBatch.STATUS_COMPLETED, null);
        done.setCandidatesReturned(2);
        done.setCandidatesFiltered(1);
        done.setFindingsCommitted(1);
        batchRepository.save(done);
        manualBatch(run.getId(), 2, DiagnosticRunBatch.STATUS_SKIPPED_BUDGET, null);

        DiagnosticRunDetail detail = runService.resume(run.getId(), true);

        assertThat(detail.status())
                .as("SKIPPED_BUDGET is a governance non-event — never blocks COMPLETED (spec §8.2)")
                .isEqualTo(DiagnosticRunService.STATUS_COMPLETED);
        assertThat(detail.batchCounts().get(DiagnosticRunBatch.STATUS_SKIPPED_BUDGET)).isEqualTo(1L);
    }

    // ------------------------------------------- start guards + idempotency

    @Test
    void oneActiveDiagnosticRunPerDomain_secondStartConflicts() {
        manualRun(0); // PLANNED/RUNNING row already active for the domain

        assertThatThrownBy(() -> startRun(0, List.of("systems")))
                .isInstanceOf(DiagnosticConflictException.class)
                .satisfies(ex -> assertThat(((DiagnosticConflictException) ex).getCode())
                        .isEqualTo(DiagnosticConflictException.Code.DIAGNOSTIC_RUN_ACTIVE));
    }

    @Test
    void idempotencyKeyReplay_returnsTheOriginalRun() {
        stub.respondWith(VALID_STRUCTURAL);
        DiagnosticRunService.StartResult first = runService.startRun(command(0, null, "replay-key-1"));
        DiagnosticRunService.StartResult replay = runService.startRun(command(0, null, "replay-key-1"));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.runId()).isEqualTo(first.runId());
        assertThat(runRepository.findByDomainIdAndRunTypeOrderByCreatedAtDesc(domainId, "diagnostic"))
                .hasSize(1);
    }

    @Test
    void dryRun_returnsThePlanAndPersistsNothing() {
        addSystem();
        DiagnosticRunService.StartResult result = runService.startRun(
                new DiagnosticRunService.StartCommand(domainId, List.of("systems"), null,
                        null, 0, null, true, "dry-1", "test", true));

        assertThat(result.dryRun()).isTrue();
        assertThat(result.plan().totalBatches()).isEqualTo(2); // 1 systems + 1 domain-wide
        assertThat(result.plan().batches().get(1).scope()).isEqualTo("domain");
        assertThat(runRepository.findByDomainIdAndRunTypeOrderByCreatedAtDesc(domainId, "diagnostic"))
                .isEmpty();
        assertThat(batchRepository.count()).isZero();
    }

    // --------------------------------------------------------------- helpers

    private DiagnosticRunService.StartResult startRun(int required, List<String> scopes) {
        return runService.startRun(command(required, scopes, "key-" + UUID.randomUUID()));
    }

    private DiagnosticRunService.StartCommand command(int required, List<String> scopes, String key) {
        return new DiagnosticRunService.StartCommand(domainId, scopes, null, null,
                required, null, false, key, "test-actor", true);
    }

    private void addSystem() {
        SystemEntity system = new SystemEntity();
        system.setDomainId(domainId);
        system.setName("Glacier Core " + UUID.randomUUID());
        system.setCreatedBy("test");
        systemRepository.save(system);
    }

    private SynthesisRun manualRun(int requiredMinFindings) {
        SynthesisRun run = new SynthesisRun();
        run.setDomainId(domainId);
        run.setRunType("diagnostic");
        run.setStatus(DiagnosticRunService.STATUS_RUNNING);
        run.setStartedAt(Instant.now());
        run.setCreatedBy("test");
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("requiredMinFindings", requiredMinFindings);
        metadata.put("executor", "in-process");
        ObjectNode rates = metadata.putObject("costRateSnapshot");
        rates.put("inputRatePerMillion", 3.0);
        rates.put("outputRatePerMillion", 15.0);
        run.setMetadata(metadata);
        return runRepository.save(run);
    }

    private DiagnosticRunBatch manualBatch(UUID runId, int batchNo, String status, Instant claimedAt) {
        DiagnosticRunBatch batch = new DiagnosticRunBatch();
        batch.setSynthesisRunId(runId);
        batch.setBatchNo(batchNo);
        batch.setScope("domain");
        batch.setStatus(status);
        batch.setClaimedAt(claimedAt);
        if (DiagnosticRunBatch.STATUS_RUNNING.equals(status)) {
            batch.setAttempt(1);
        }
        batch.setCreatedBy("test");
        return batchRepository.save(batch);
    }

    // ------------------------------------------------------------- LLM stub

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        ScriptedLlmClient scriptedLlmClient() {
            return new ScriptedLlmClient();
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
