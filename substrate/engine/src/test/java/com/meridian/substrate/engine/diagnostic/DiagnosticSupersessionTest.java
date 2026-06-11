package com.meridian.substrate.engine.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AiCurationEvent;
import com.meridian.substrate.common.domain.DiagnosticRunBatch;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.SynthesisRun;
import com.meridian.substrate.common.domain.SystemEntity;
import com.meridian.substrate.common.domain.enums.CurationAction;
import com.meridian.substrate.common.domain.enums.CurationStatus;
import com.meridian.substrate.common.domain.enums.CurationSubjectKind;
import com.meridian.substrate.common.repo.AiCurationEventRepository;
import com.meridian.substrate.common.repo.DiagnosticRunBatchRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.repo.SynthesisRunRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import com.meridian.substrate.engine.EngineTestApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Diagnostic test plan #6 — supersession-at-finalization (engine-spec
 * diagnostic.md §4.5; ADR-004 D3), driven through the REAL run lifecycle
 * ({@code sync=true}) with the scripted LLM stub:
 *
 * <ul>
 *   <li>finalization-tx only: a COMPLETED run supersedes prior
 *       same-{@code (domain, scope-set)} PENDING_REVIEW findings with one
 *       system-authored event each; ACCEPTED/REJECTED rows are never
 *       touched; the run's own findings are never superseded;</li>
 *   <li>run-twice stability: the pending set is always exactly the newest
 *       run's findings; already-superseded rows gain no second event;</li>
 *   <li>ZERO_YIELD / FAILED / CANCELLED runs supersede NOTHING;</li>
 *   <li>exact scope-set match only (EXTENSION POINT(diagnostic-11) —
 *       overlapping scopes deliberately accumulate);</li>
 *   <li>mid-run crash: prior PENDING rows stay honestly co-present until
 *       resume finalizes (pinned as documented behavior).</li>
 * </ul>
 */
@SpringBootTest(classes = EngineTestApplication.class,
        properties = "substrate.diagnostic.sync=true")
@ActiveProfiles("test")
@Import(DiagnosticRunLifecycleTest.StubConfig.class)
@Transactional
class DiagnosticSupersessionTest {

    private static final String VALID_STRUCTURAL = """
            [{"type":"STRUCTURAL","severity":"MEDIUM","title":"Stub observation",
              "description":"Stub structural observation.","suggestedAction":"Review.","evidenceIds":[]}]""";

    private static final String UNGROUNDED_CONTENT = """
            [{"type":"CONTENT","severity":"HIGH","description":"Ungrounded claim.",
              "suggestedAction":"Filter me.","evidenceIds":["00000000-0000-0000-0000-000000000000"]}]""";

    @Autowired DiagnosticRunService runService;
    @Autowired CurationService curationService;
    @Autowired DomainRepository domainRepository;
    @Autowired SystemRepository systemRepository;
    @Autowired SynthesisRunRepository runRepository;
    @Autowired DiagnosticRunBatchRepository batchRepository;
    @Autowired FindingRepository findingRepository;
    @Autowired AiCurationEventRepository eventRepository;
    @Autowired DiagnosticRunLifecycleTest.ScriptedLlmClient stub;
    @Autowired ObjectMapper mapper;

    private UUID domainId;

    @BeforeEach
    void fixture() {
        Domain domain = new Domain();
        domain.setName("supersession " + UUID.randomUUID());
        domain.setCreatedBy("test");
        domainId = domainRepository.save(domain).getId();

        SystemEntity system = new SystemEntity();
        system.setDomainId(domainId);
        system.setName("Glacier Core " + UUID.randomUUID());
        system.setCreatedBy("test");
        systemRepository.save(system);

        stub.respondWith(VALID_STRUCTURAL);
    }

    // ------------------------------------------------------- the main matrix

    @Test
    void completedRun_supersedesSameScopePendings_curatedRowsUntouched_runTwiceStable() {
        UUID run1 = start(List.of("systems"));
        List<Finding> run1Findings = findingsOf(run1);
        assertThat(run1Findings).hasSize(2); // systems batch + domain-wide batch
        Finding accepted = run1Findings.get(0);
        Finding pending = run1Findings.get(1);
        curationService.accept(accepted.getId(), null, "k-accept", "Avery", null);

        // ---- run 2 (same scope-set) completes → run1's PENDING superseded
        UUID run2 = start(List.of("systems"));

        Finding supersededRow = findingRepository.findById(pending.getId()).orElseThrow();
        assertThat(supersededRow.getCurationStatus()).isEqualTo(CurationStatus.SUPERSEDED);
        assertThat(supersededRow.getCuratedByLabel()).isEqualTo(CurationService.SYSTEM_ACTOR);
        List<AiCurationEvent> events = eventsOf(pending.getId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getAction()).isEqualTo(CurationAction.SUPERSEDE);
        assertThat(events.get(0).getSynthesisRunId()).isEqualTo(run2);
        assertThat(events.get(0).getActorLabel()).isEqualTo(CurationService.SYSTEM_ACTOR);

        // ACCEPTED rows are NEVER touched
        assertThat(findingRepository.findById(accepted.getId()).orElseThrow().getCurationStatus())
                .isEqualTo(CurationStatus.ACCEPTED);
        // the superseding run's own findings are PENDING, untouched
        assertThat(findingsOf(run2)).hasSize(2).allSatisfy(f ->
                assertThat(f.getCurationStatus()).isEqualTo(CurationStatus.PENDING_REVIEW));
        // metadata records the superseded ids (spec §4.5)
        assertThat(supersededIdsOf(run2)).containsExactly(pending.getId().toString());

        // ---- run 3: REJECTED rows untouched; stability — pending set is run3's only
        List<Finding> run2Findings = findingsOf(run2);
        curationService.reject(run2Findings.get(0).getId(), "not actionable", "k-reject", "Avery", null);

        UUID run3 = start(List.of("systems"));

        assertThat(findingRepository.findById(run2Findings.get(0).getId()).orElseThrow()
                .getCurationStatus()).isEqualTo(CurationStatus.REJECTED);
        assertThat(findingRepository.findById(run2Findings.get(1).getId()).orElseThrow()
                .getCurationStatus()).isEqualTo(CurationStatus.SUPERSEDED);
        // an already-superseded row gains NO second event (one SUPERSEDE, ever)
        assertThat(eventsOf(pending.getId())).hasSize(1);
        // run-twice stable pending set: exactly the newest run's findings
        assertThat(findingRepository.findByDomainIdAndCurationStatus(
                domainId, CurationStatus.PENDING_REVIEW))
                .extracting(Finding::getSynthesisRunId)
                .containsOnly(run3);
        assertThat(supersededIdsOf(run3))
                .containsExactly(run2Findings.get(1).getId().toString());
    }

    // ------------------------------------------- deficient runs supersede nothing

    @Test
    void zeroYieldRun_supersedesNothing() {
        UUID run1 = start(List.of("systems"));
        assertThat(findingsOf(run1)).hasSize(2);

        stub.respondWith(UNGROUNDED_CONTENT);
        UUID run2 = startRequired(List.of("systems"), 1);

        assertThat(runRepository.findById(run2).orElseThrow().getStatus())
                .isEqualTo(DiagnosticRunService.STATUS_ZERO_YIELD);
        assertThat(pendingOf(run1)).as("a deficient run must not wipe the prior pending set")
                .hasSize(2);
        assertThat(supersededIdsOf(run2)).isEmpty();
    }

    @Test
    void failedRun_supersedesNothing() {
        UUID run1 = start(List.of("systems"));

        stub.respondWith("no structured findings — prose only");
        UUID run2 = start(List.of("systems"));

        assertThat(runRepository.findById(run2).orElseThrow().getStatus())
                .isEqualTo(DiagnosticRunService.STATUS_FAILED);
        assertThat(pendingOf(run1)).hasSize(2);
        assertThat(supersededIdsOf(run2)).isEmpty();
    }

    @Test
    void cancelledRun_supersedesNothing() {
        UUID run1 = start(List.of("systems"));

        SynthesisRun manual = manualRun(List.of("systems"));
        manualBatch(manual.getId(), DiagnosticRunBatch.STATUS_PENDING, null);
        runService.cancel(manual.getId());

        assertThat(runRepository.findById(manual.getId()).orElseThrow().getStatus())
                .isEqualTo(DiagnosticRunService.STATUS_CANCELLED);
        assertThat(pendingOf(run1)).hasSize(2);
        assertThat(supersededIdsOf(manual.getId())).isEmpty();
    }

    // --------------------------------------------------- scope-set exactness

    @Test
    void differentScopeSet_neverSupersedes_exactMatchOnly() {
        UUID run1 = start(List.of("systems"));

        UUID run2 = start(List.of("processes")); // disjoint scope-set, completes fine

        assertThat(runRepository.findById(run2).orElseThrow().getStatus())
                .isEqualTo(DiagnosticRunService.STATUS_COMPLETED);
        // EXTENSION POINT(diagnostic-11): exact scope-set match only —
        // overlapping/different scopes deliberately accumulate pendings
        assertThat(pendingOf(run1)).hasSize(2);
        assertThat(supersededIdsOf(run2)).isEmpty();
    }

    // ------------------------------------------------- mid-run crash window

    @Test
    void midRunCrash_leavesPriorPendingsCoPresent_untilResumeFinalizesAndSupersedes() {
        UUID run1 = start(List.of("systems"));
        assertThat(pendingOf(run1)).hasSize(2);

        // Crash shape: run2 died between generation and commit — run RUNNING,
        // batch RUNNING with a stale claim, nothing committed, NOT finalized.
        SynthesisRun run2 = manualRun(List.of("systems"));
        manualBatch(run2.getId(), DiagnosticRunBatch.STATUS_RUNNING,
                Instant.now().minus(Duration.ofMinutes(31)));

        // The transient window is VISIBLE and honest: both runs' rows co-present.
        assertThat(pendingOf(run1)).as("prior pendings co-present until resume finalizes")
                .hasSize(2);

        runService.resume(run2.getId(), true);

        assertThat(runRepository.findById(run2.getId()).orElseThrow().getStatus())
                .isEqualTo(DiagnosticRunService.STATUS_COMPLETED);
        assertThat(pendingOf(run1)).isEmpty();
        assertThat(findingsOf(run1)).allSatisfy(f ->
                assertThat(f.getCurationStatus()).isEqualTo(CurationStatus.SUPERSEDED));
        assertThat(supersededIdsOf(run2.getId())).hasSize(2);
    }

    // --------------------------------------------------------------- helpers

    private UUID start(List<String> scopes) {
        return startRequired(scopes, 0);
    }

    private UUID startRequired(List<String> scopes, int required) {
        return runService.startRun(new DiagnosticRunService.StartCommand(
                domainId, scopes, null, null, required, null, false,
                "key-" + UUID.randomUUID(), "test-actor", true)).runId();
    }

    private List<Finding> findingsOf(UUID runId) {
        return findingRepository.findByDomainIdAndAiGeneratedTrue(domainId).stream()
                .filter(f -> runId.equals(f.getSynthesisRunId()))
                .toList();
    }

    private List<Finding> pendingOf(UUID runId) {
        return findingsOf(runId).stream()
                .filter(f -> f.getCurationStatus() == CurationStatus.PENDING_REVIEW)
                .toList();
    }

    private List<AiCurationEvent> eventsOf(UUID findingId) {
        return eventRepository.findBySubjectKindAndSubjectIdOrderByCreatedAtAsc(
                CurationSubjectKind.FINDING, findingId);
    }

    private List<String> supersededIdsOf(UUID runId) {
        SynthesisRun run = runRepository.findById(runId).orElseThrow();
        List<String> ids = new java.util.ArrayList<>();
        if (run.getMetadata() != null && run.getMetadata().path("supersededFindingIds").isArray()) {
            run.getMetadata().path("supersededFindingIds").forEach(id -> ids.add(id.asText()));
        }
        return ids;
    }

    private SynthesisRun manualRun(List<String> scopes) {
        SynthesisRun run = new SynthesisRun();
        run.setDomainId(domainId);
        run.setRunType("diagnostic");
        run.setStatus(DiagnosticRunService.STATUS_RUNNING);
        run.setStartedAt(Instant.now());
        run.setCreatedBy("test");
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("requiredMinFindings", 0);
        metadata.put("executor", "in-process");
        var scopesNode = metadata.putArray("scopes");
        scopes.forEach(scopesNode::add);
        ObjectNode rates = metadata.putObject("costRateSnapshot");
        rates.put("inputRatePerMillion", 3.0);
        rates.put("outputRatePerMillion", 15.0);
        run.setMetadata(metadata);
        return runRepository.save(run);
    }

    private DiagnosticRunBatch manualBatch(UUID runId, String status, Instant claimedAt) {
        DiagnosticRunBatch batch = new DiagnosticRunBatch();
        batch.setSynthesisRunId(runId);
        batch.setBatchNo(1);
        batch.setScope("systems");
        batch.setStatus(status);
        batch.setClaimedAt(claimedAt);
        if (DiagnosticRunBatch.STATUS_RUNNING.equals(status)) {
            batch.setAttempt(1);
        }
        batch.setCreatedBy("test");
        return batchRepository.save(batch);
    }
}
