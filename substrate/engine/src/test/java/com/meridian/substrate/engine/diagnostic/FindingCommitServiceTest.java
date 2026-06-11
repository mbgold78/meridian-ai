package com.meridian.substrate.engine.diagnostic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.DiagnosticRunBatch;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.SynthesisRun;
import com.meridian.substrate.common.domain.SystemEntity;
import com.meridian.substrate.common.domain.enums.CurationStatus;
import com.meridian.substrate.common.domain.enums.FindingStatus;
import com.meridian.substrate.common.domain.enums.FindingType;
import com.meridian.substrate.common.domain.enums.Severity;
import com.meridian.substrate.common.repo.DiagnosticRunBatchRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.EvidenceRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.repo.SynthesisRunRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import com.meridian.substrate.engine.EngineTestApplication;
import com.meridian.substrate.engine.ai.validative.FindingCandidate;
import com.meridian.substrate.engine.ai.validative.FindingCandidateSeverity;
import com.meridian.substrate.engine.ai.validative.FindingCandidateType;
import com.meridian.substrate.engine.diagnostic.exception.StaleBatchClaimException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Diagnostic test plan #2 (the §5.2 mapping pins) + the commit-side half of
 * #3 (differential grounding pinned at commit time) — engine-spec
 * diagnostic.md §5.2/§14:
 *
 * <ul>
 *   <li>severity matrix incl. the documented-lossy INFO→LOW;</li>
 *   <li>findingType passthrough / differential default / invalid-kept
 *       ({@code metadata.findingTypeDefaulted=true} — an invalid LLM type
 *       NEVER discards a grounded candidate);</li>
 *   <li>title fallback (first sentence) + 200-char truncation;</li>
 *   <li>enriched evidence snapshot shape
 *       {@code [{evidenceId,label,sourceFile,documentRegistryId,confidence}]};</li>
 *   <li>STRUCTURAL evidence-less commits with {@code evidence=[]};</li>
 *   <li>unresolved affected entity STILL commits
 *       ({@code metadata.unresolvedAffectedEntity});</li>
 *   <li>evidence vanishing between filter and commit fails THE CANDIDATE
 *       ({@code filter_histogram.ungrounded+1}), never the batch;</li>
 *   <li>batch-ledger + run-counter accounting in the one commit tx.</li>
 * </ul>
 *
 * <p>H2 fast lane — Testcontainers-Postgres remains the acceptance gate.
 */
@SpringBootTest(classes = EngineTestApplication.class)
@ActiveProfiles("test")
@Transactional
class FindingCommitServiceTest {

    @Autowired FindingCommitService commitService;
    @Autowired DomainRepository domainRepository;
    @Autowired SystemRepository systemRepository;
    @Autowired EvidenceRepository evidenceRepository;
    @Autowired SynthesisRunRepository runRepository;
    @Autowired DiagnosticRunBatchRepository batchRepository;
    @Autowired FindingRepository findingRepository;
    @Autowired ObjectMapper mapper;

    private UUID domainId;
    private SynthesisRun run;
    private DiagnosticRunBatch batch;

    @BeforeEach
    void fixture() {
        Domain domain = new Domain();
        domain.setName("commit-pins " + UUID.randomUUID());
        domain.setCreatedBy("test");
        domainId = domainRepository.save(domain).getId();

        run = new SynthesisRun();
        run.setDomainId(domainId);
        run.setRunType("diagnostic");
        run.setStatus("RUNNING");
        run.setCreatedBy("test");
        run.setMetadata(mapper.createObjectNode().put("requiredMinFindings", 0));
        run = runRepository.save(run);

        batch = new DiagnosticRunBatch();
        batch.setSynthesisRunId(run.getId());
        batch.setBatchNo(1);
        batch.setScope("systems");
        batch.setStatus(DiagnosticRunBatch.STATUS_RUNNING);
        batch.setExecutor("in-process");
        batch.setCreatedBy("test");
        batch = batchRepository.save(batch);
    }

    // --------------------------------------------------- #2 severity matrix

    @Test
    void severityMatrixPinned_includingLossyInfoToLow() {
        commitService.commitBatch(run.getId(), batch.getId(), batch.getAttempt(), generation(List.of(
                structural(FindingCandidateSeverity.CRITICAL, "Critical one.", null, null),
                structural(FindingCandidateSeverity.HIGH, "High one.", null, null),
                structural(FindingCandidateSeverity.MEDIUM, "Medium one.", null, null),
                structural(FindingCandidateSeverity.INFO, "Info one.", null, null))));

        List<Finding> findings = findingRepository.findByDomainIdAndAiGeneratedTrue(domainId);
        assertThat(findings).hasSize(4);
        assertThat(findings).extracting(Finding::getSeverity)
                .containsExactlyInAnyOrder(
                        Severity.CRITICAL, Severity.HIGH, Severity.MEDIUM,
                        // INFO→LOW: documented lossy mapping (spec §5.2; ADR-004 D8;
                        // EXTENSION POINT(diagnostic-13) — REVISIT with Mike)
                        Severity.LOW);
    }

    // ------------------------------------------- #2 findingType pass/default

    @Test
    void findingTypePassthrough_validValueIsNotDefaulted() {
        commitService.commitBatch(run.getId(), batch.getId(), batch.getAttempt(), generation(List.of(
                structural(FindingCandidateSeverity.MEDIUM, "Typed.", null, "DUPLICATION"))));

        Finding finding = soleFinding();
        assertThat(finding.getFindingType()).isEqualTo(FindingType.DUPLICATION);
        assertThat(finding.getMetadata().has("findingTypeDefaulted")).isFalse();
    }

    @Test
    void findingTypeDifferentialDefaults_structuralCoverageGap_contentDataQuality() {
        UUID evidenceId = evidence("glacier-systems.csv").getId();
        commitService.commitBatch(run.getId(), batch.getId(), batch.getAttempt(), generation(List.of(
                structural(FindingCandidateSeverity.MEDIUM, "Structural untyped.", null, null),
                content(FindingCandidateSeverity.HIGH, "Content untyped.", List.of(evidenceId), null))));

        List<Finding> findings = findingRepository.findByDomainIdAndAiGeneratedTrue(domainId);
        assertThat(findings).hasSize(2);
        Finding structural = byDescription(findings, "Structural untyped.");
        Finding content = byDescription(findings, "Content untyped.");
        assertThat(structural.getFindingType()).isEqualTo(FindingType.COVERAGE_GAP);
        assertThat(structural.getMetadata().get("findingTypeDefaulted").asBoolean()).isTrue();
        assertThat(content.getFindingType()).isEqualTo(FindingType.DATA_QUALITY);
        assertThat(content.getMetadata().get("findingTypeDefaulted").asBoolean()).isTrue();
    }

    @Test
    void invalidFindingTypeNeverDiscardsAGroundedCandidate() {
        UUID evidenceId = evidence("glacier-processes.csv").getId();
        commitService.commitBatch(run.getId(), batch.getId(), batch.getAttempt(), generation(List.of(
                content(FindingCandidateSeverity.HIGH, "Invalid typed.", List.of(evidenceId),
                        "TOTALLY_MADE_UP_TYPE"))));

        Finding finding = soleFinding();
        assertThat(finding.getFindingType()).isEqualTo(FindingType.DATA_QUALITY);
        assertThat(finding.getMetadata().get("findingTypeDefaulted").asBoolean()).isTrue();
    }

    // ----------------------------------------------------- #2 title fallback

    @Test
    void titleRules_candidateTitle_firstSentenceFallback_truncation200() {
        String longTail = "x".repeat(300);
        commitService.commitBatch(run.getId(), batch.getId(), batch.getAttempt(), generation(List.of(
                candidate(FindingCandidateType.STRUCTURAL, FindingCandidateSeverity.MEDIUM,
                        "Description here. Second sentence.", List.of(), "Explicit title", null),
                candidate(FindingCandidateType.STRUCTURAL, FindingCandidateSeverity.MEDIUM,
                        "First sentence wins. Second sentence loses.", List.of(), null, null),
                candidate(FindingCandidateType.STRUCTURAL, FindingCandidateSeverity.MEDIUM,
                        "No sentence boundary " + longTail, List.of(), null, null))));

        List<Finding> findings = findingRepository.findByDomainIdAndAiGeneratedTrue(domainId);
        assertThat(findings).extracting(Finding::getTitle)
                .contains("Explicit title", "First sentence wins.");
        Finding truncated = byDescription(findings, "No sentence boundary");
        assertThat(truncated.getTitle()).hasSize(200);
    }

    // ------------------------------------- #2/#3 evidence snapshot + empties

    @Test
    void enrichedEvidenceSnapshotShape_isPinned() {
        Evidence evidence = evidence("glacier-systems.csv");
        commitService.commitBatch(run.getId(), batch.getId(), batch.getAttempt(), generation(List.of(
                content(FindingCandidateSeverity.HIGH, "Snapshot shape.",
                        List.of(evidence.getId()), null))));

        Finding finding = soleFinding();
        JsonNode snapshot = finding.getEvidence();
        assertThat(snapshot.isArray()).isTrue();
        assertThat(snapshot).hasSize(1);
        JsonNode entry = snapshot.get(0);
        assertThat(entry.get("evidenceId").asText()).isEqualTo(evidence.getId().toString());
        assertThat(entry.get("label").asText()).isEqualTo(evidence.getLabel());
        assertThat(entry.get("sourceFile").asText()).isEqualTo("glacier-systems.csv");
        assertThat(entry.get("documentRegistryId").asText())
                .isEqualTo(evidence.getDocumentRegistryId().toString());
        assertThat(entry.get("confidence").floatValue()).isEqualTo(evidence.getConfidence());
        // full §5.2 metadata block
        JsonNode metadata = finding.getMetadata();
        assertThat(metadata.get("assertionId").asText()).isEqualTo(finding.getAssertionId().toString());
        assertThat(metadata.get("candidateType").asText()).isEqualTo("CONTENT");
        assertThat(metadata.get("groundingMode").asText()).isEqualTo("EXISTENCE_ONLY");
        assertThat(metadata.get("batchNo").asInt()).isEqualTo(1);
        assertThat(metadata.get("executor").asText()).isEqualTo("in-process");
        assertThat(finding.getCurationStatus()).isEqualTo(CurationStatus.PENDING_REVIEW);
        assertThat(finding.getStatus()).isEqualTo(FindingStatus.OPEN);
        assertThat(finding.getAiGenerated()).isTrue();
        assertThat(finding.getSynthesisRunId()).isEqualTo(run.getId());
    }

    @Test
    void structuralEvidenceLessCommitsWithEmptyEvidenceArray() {
        commitService.commitBatch(run.getId(), batch.getId(), batch.getAttempt(), generation(List.of(
                structural(FindingCandidateSeverity.MEDIUM, "Evidence-less structural.", null, null))));

        Finding finding = soleFinding();
        assertThat(finding.getEvidence().isArray()).isTrue();
        assertThat(finding.getEvidence()).isEmpty();
        assertThat(finding.getAssertionId()).isNotNull();
    }

    // -------------------------------------------- #3 vanish-between-filter-and-commit

    @Test
    void evidenceVanishingBetweenFilterAndCommit_failsTheCandidateNotTheBatch() {
        Evidence solid = evidence("glacier-systems.csv");
        UUID vanished = UUID.randomUUID(); // was grounded at filter time; row gone now
        FindingCommitService.BatchCommitResult result = commitService.commitBatch(
                run.getId(), batch.getId(), batch.getAttempt(), generation(List.of(
                        content(FindingCandidateSeverity.HIGH, "Still grounded.",
                                List.of(solid.getId()), null),
                        content(FindingCandidateSeverity.HIGH, "Lost grounding.",
                                List.of(vanished), null))));

        assertThat(result.findingsCommitted()).isEqualTo(1);
        assertThat(result.candidatesFiltered()).isEqualTo(1);
        List<Finding> findings = findingRepository.findByDomainIdAndAiGeneratedTrue(domainId);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).getDescription()).isEqualTo("Still grounded.");

        DiagnosticRunBatch committedBatch = batchRepository.findById(batch.getId()).orElseThrow();
        assertThat(committedBatch.getStatus()).isEqualTo(DiagnosticRunBatch.STATUS_COMPLETED);
        assertThat(committedBatch.getFilterHistogram().get("ungrounded").asInt()).isEqualTo(1);
    }

    // -------------------------------------------- #2 affected-entity resolution

    @Test
    void unresolvedAffectedEntityStillCommits_resolvedOnePopulatesColumn() {
        SystemEntity system = new SystemEntity();
        system.setDomainId(domainId);
        system.setName("Glacier Core");
        system.setCreatedBy("test");
        UUID systemId = systemRepository.save(system).getId();

        commitService.commitBatch(run.getId(), batch.getId(), batch.getAttempt(), generation(List.of(
                candidate(FindingCandidateType.STRUCTURAL, FindingCandidateSeverity.MEDIUM,
                        "Resolved system.", List.of(), null, null, "system", systemId),
                candidate(FindingCandidateType.STRUCTURAL, FindingCandidateSeverity.MEDIUM,
                        "Unresolvable entity.", List.of(), null, null, "system", UUID.randomUUID()))));

        List<Finding> findings = findingRepository.findByDomainIdAndAiGeneratedTrue(domainId);
        Finding resolved = byDescription(findings, "Resolved system.");
        Finding unresolved = byDescription(findings, "Unresolvable entity.");
        assertThat(resolved.getAffectedSystemIds().get(0).asText()).isEqualTo(systemId.toString());
        assertThat(resolved.getMetadata().has("unresolvedAffectedEntity")).isFalse();
        assertThat(unresolved.getAffectedSystemIds()).isNull();
        assertThat(unresolved.getMetadata().get("unresolvedAffectedEntity").asBoolean()).isTrue();
    }

    // -------------------------------------------------------- run accounting

    @Test
    void commitTxUpdatesLedgerCountersAndRunAccumulators() {
        UUID evidenceId = evidence("glacier-systems.csv").getId();
        DiagnosticExecutor.BatchGeneration generation = new DiagnosticExecutor.BatchGeneration(
                List.of(structural(FindingCandidateSeverity.MEDIUM, "Kept structural.", null, null),
                        content(FindingCandidateSeverity.HIGH, "Kept content.",
                                List.of(evidenceId), null)),
                5, // 3 were filtered upstream
                Map.of(CandidateFilter.Outcome.BAD_SEVERITY, 2,
                        CandidateFilter.Outcome.UNGROUNDED, 1),
                111, 222, "mock-model", false, null, null);

        FindingCommitService.BatchCommitResult result =
                commitService.commitBatch(run.getId(), batch.getId(), batch.getAttempt(), generation);

        assertThat(result.candidatesReturned()).isEqualTo(5);
        assertThat(result.findingsCommitted()).isEqualTo(2);
        assertThat(result.candidatesFiltered()).isEqualTo(3);

        DiagnosticRunBatch committedBatch = batchRepository.findById(batch.getId()).orElseThrow();
        assertThat(committedBatch.getCandidatesReturned()).isEqualTo(5);
        assertThat(committedBatch.getCandidatesFiltered()).isEqualTo(3);
        assertThat(committedBatch.getFindingsCommitted()).isEqualTo(2);
        assertThat(committedBatch.getFilterHistogram().get("badSeverity").asInt()).isEqualTo(2);
        assertThat(committedBatch.getFinishedAt()).isNotNull();

        SynthesisRun updated = runRepository.findById(run.getId()).orElseThrow();
        assertThat(updated.getTotalInputTokens()).isEqualTo(111);
        assertThat(updated.getTotalOutputTokens()).isEqualTo(222);
        assertThat(updated.getModel()).isEqualTo("mock-model");
        JsonNode metadata = updated.getMetadata();
        assertThat(metadata.get("contentCommitted").asInt()).isEqualTo(1);
        assertThat(metadata.get("structuralCommitted").asInt()).isEqualTo(1);
        assertThat(metadata.get("filterHistogram").get("badSeverity").asInt()).isEqualTo(2);
        assertThat(metadata.get("executorMix").get("in-process").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------- stale-claim commit fence

    @Test
    void staleClaimCommit_isRefused_andTheRowIsLeftUntouched() {
        // Simulate failStaleBatches + a resume re-claim: the row moved on to a
        // newer attempt while THIS executor was still generating.
        int staleAttempt = batch.getAttempt();
        batch.setAttempt(staleAttempt + 1);
        batch = batchRepository.save(batch);

        assertThatThrownBy(() -> commitService.commitBatch(run.getId(), batch.getId(),
                staleAttempt, generation(List.of(
                        structural(FindingCandidateSeverity.MEDIUM, "Stale-claim finding.", null, null)))))
                .isInstanceOf(StaleBatchClaimException.class)
                .hasMessageContaining("re-claimed");

        // Nothing committed, row untouched: the new claimant still owns it.
        assertThat(findingRepository.findByDomainIdAndAiGeneratedTrue(domainId)).isEmpty();
        DiagnosticRunBatch row = batchRepository.findById(batch.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(DiagnosticRunBatch.STATUS_RUNNING);
        assertThat(row.getAttempt()).isEqualTo(staleAttempt + 1);
    }

    @Test
    void terminallyResolvedBatch_refusesALateCommit() {
        batch.setStatus(DiagnosticRunBatch.STATUS_FAILED);
        batch = batchRepository.save(batch);

        assertThatThrownBy(() -> commitService.commitBatch(run.getId(), batch.getId(),
                batch.getAttempt(), generation(List.of(
                        structural(FindingCandidateSeverity.MEDIUM, "Late commit.", null, null)))))
                .isInstanceOf(StaleBatchClaimException.class);
        assertThat(findingRepository.findByDomainIdAndAiGeneratedTrue(domainId)).isEmpty();
    }

    // --------------------------------------------------------------- helpers

    private Finding soleFinding() {
        List<Finding> findings = findingRepository.findByDomainIdAndAiGeneratedTrue(domainId);
        assertThat(findings).hasSize(1);
        return findings.get(0);
    }

    private static Finding byDescription(List<Finding> findings, String prefix) {
        return findings.stream()
                .filter(f -> f.getDescription().startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no finding with description " + prefix));
    }

    private DiagnosticExecutor.BatchGeneration generation(List<FindingCandidate> kept) {
        return new DiagnosticExecutor.BatchGeneration(
                kept, kept.size(), Map.of(), 10, 5, "mock-model", false, null, null);
    }

    private FindingCandidate structural(FindingCandidateSeverity severity, String description,
                                        String title, String findingType) {
        return candidate(FindingCandidateType.STRUCTURAL, severity, description,
                List.of(), title, findingType);
    }

    private FindingCandidate content(FindingCandidateSeverity severity, String description,
                                     List<UUID> evidenceIds, String findingType) {
        return candidate(FindingCandidateType.CONTENT, severity, description,
                evidenceIds, null, findingType);
    }

    private FindingCandidate candidate(FindingCandidateType type, FindingCandidateSeverity severity,
                                       String description, List<UUID> evidenceIds,
                                       String title, String findingType) {
        return candidate(type, severity, description, evidenceIds, title, findingType, null, null);
    }

    private FindingCandidate candidate(FindingCandidateType type, FindingCandidateSeverity severity,
                                       String description, List<UUID> evidenceIds,
                                       String title, String findingType,
                                       String affectedEntityType, UUID affectedEntityId) {
        return new FindingCandidate(type, severity, affectedEntityType, affectedEntityId,
                description, "Do something about it.", evidenceIds, UUID.randomUUID(),
                title, findingType);
    }

    private Evidence evidence(String sourceFile) {
        Evidence evidence = new Evidence();
        evidence.setDomainId(domainId);
        evidence.setEntityId(UUID.randomUUID());
        evidence.setEntityType("system");
        evidence.setLabel("Committed from " + sourceFile);
        evidence.setSourceFile(sourceFile);
        evidence.setExtractor("ingestion");
        evidence.setConfidence(0.97f);
        evidence.setDocumentRegistryId(UUID.randomUUID());
        evidence.setCreatedBy("test");
        return evidenceRepository.save(evidence);
    }
}
