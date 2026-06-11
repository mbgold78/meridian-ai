package com.meridian.substrate.ingestion.e2e;

import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.enums.CurationAction;
import com.meridian.substrate.common.domain.enums.CurationStatus;
import com.meridian.substrate.common.domain.enums.CurationSubjectKind;
import com.meridian.substrate.common.repo.AiCurationEventRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.EvidenceRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.engine.ai.client.MockAnthropicClient;
import com.meridian.substrate.engine.diagnostic.CurationService;
import com.meridian.substrate.engine.diagnostic.DiagnosticRunService;
import com.meridian.substrate.engine.diagnostic.ProvenanceWalkService;
import com.meridian.substrate.engine.diagnostic.read.DiagnosticRunDetail;
import com.meridian.substrate.engine.diagnostic.read.DiagnosticVerifyReport;
import com.meridian.substrate.engine.diagnostic.read.FindingProvenance;
import com.meridian.substrate.ingestion.IngestionApplication;
import com.meridian.substrate.ingestion.commit.CommitResult;
import com.meridian.substrate.ingestion.commit.CommitService;
import com.meridian.substrate.ingestion.orchestration.ConversionPipeline;
import com.meridian.substrate.ingestion.orchestration.ConversionResult;
import com.meridian.substrate.ingestion.staging.StagingService;
import com.meridian.substrate.ingestion.testsupport.PrimaryLlmRouterTestConfig.RoutingTestLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Engine-spec diagnostic.md §11.3 — the single-JVM ingest→diagnose e2e in the
 * {@link IngestionPipelineE2eTest} pattern: ingestion services convert+commit
 * the Glacier corpus in-process, then a diagnostic run executes with the
 * scripted {@code MockAnthropicClient} responder ({@code grounded-content}):
 * (a) a REAL {@code find_evidence} tool call, (b) one grounded CONTENT
 * candidate citing Evidence ids read from the tool result, (c) one ungrounded
 * candidate that MUST be filtered, (d) one invalid-severity candidate that
 * MUST be filtered.
 *
 * <p>Asserts (spec §11.3): committed/filtered counts; the enriched evidence
 * snapshot; {@code chainComplete=true} on the committed CONTENT finding;
 * resume idempotency; supersession on re-run (ACCEPTED/REJECTED untouched);
 * and the zero-yield path.
 *
 * <p>Two lanes share this flow: {@code DiagnosticGlacierE2eIT}
 * (Testcontainers-Postgres — THE acceptance gate, runs in CI, self-disables
 * without Docker) and {@code DiagnosticGlacierE2eH2SmokeTest} (local fast
 * lane proving the combined ingestion+engine context wiring on the
 * Docker-less dev machine — H2-green is never acceptance, spec
 * non-negotiable #7).
 *
 * <p><b>Single-JVM LlmClient seam:</b> the module-wide
 * {@code PrimaryLlmRouterTestConfig} (scanned test support) routes validative
 * prompts to the engine's scripted mock and conversion prompts to the
 * row-echo extractor — see its javadoc for why the combined classpath needs a
 * primary.
 */
@SpringBootTest(classes = IngestionApplication.class, properties = {
        "substrate.diagnostic.sync=true",
        "substrate.ingestion.cli.enabled=false"
})
@ActiveProfiles("test")
public abstract class AbstractDiagnosticGlacierE2e {

    private static final Path SAMPLES = Path.of("samples");
    private static final String DOMAIN_NAME = "Glacier";
    /** Default scopes (systems, processes, canonical-entities) + the domain-wide batch. */
    private static final int EXPECTED_BATCHES = 4;

    @Autowired ConversionPipeline conversionPipeline;
    @Autowired StagingService stagingService;
    @Autowired CommitService commitService;

    @Autowired DiagnosticRunService runService;
    @Autowired CurationService curationService;
    @Autowired ProvenanceWalkService provenanceWalkService;

    @Autowired DomainRepository domainRepository;
    @Autowired FindingRepository findingRepository;
    @Autowired EvidenceRepository evidenceRepository;
    @Autowired AiCurationEventRepository curationEventRepository;
    @Autowired RoutingTestLlmClient llm;

    @TempDir Path outputDir;
    @TempDir Path stagingDir;

    @Test
    void glacierIngestThenDiagnose_groundedCommit_supersession_zeroYield() throws Exception {
        // ------------------------------------------------ 1. ingest the corpus
        assertThat(Files.isDirectory(SAMPLES))
                .as("sample corpus at ingestion/samples (test runs in the module dir)")
                .isTrue();
        ConversionResult conversion = conversionPipeline.convertAll(SAMPLES, outputDir);
        assertThat(conversion.errorMessages()).isEmpty();
        assertThat(conversion.filesProcessed()).isEqualTo(4);
        stagingService.stage(outputDir, stagingDir);
        CommitResult commit = commitService.commit(stagingDir, DOMAIN_NAME);
        assertThat(commit.errorMessages()).isEmpty();
        assertThat(commit.entitiesCommitted()).isPositive();

        Domain glacier = domainRepository.findFirstByName(DOMAIN_NAME).orElseThrow();
        UUID domainId = glacier.getId();
        assertThat(evidenceRepository.findByDomainId(domainId))
                .as("ingestion wrote the REAL evidence chain (extractor='ingestion')")
                .isNotEmpty()
                .allSatisfy(ev -> assertThat(ev.getExtractor()).isEqualTo("ingestion"));

        // ------------------------------ 2. diagnostic run (scripted responder)
        llm.engineMock().setDiagnosticScript(MockAnthropicClient.SCRIPT_GROUNDED_CONTENT);
        DiagnosticRunDetail run1 = runService.getRun(startRun(domainId, 1, "e2e-run-1").runId());

        assertThat(run1.status()).isEqualTo(DiagnosticRunService.STATUS_COMPLETED);
        assertThat(run1.batches()).hasSize(EXPECTED_BATCHES);
        // Per batch: grounded CONTENT commits; ungrounded + URGENT are filtered.
        assertThat(run1.findingsCommitted()).isEqualTo(EXPECTED_BATCHES);
        assertThat(run1.candidatesReturned()).isEqualTo(EXPECTED_BATCHES * 3);
        assertThat(run1.candidatesFiltered()).isEqualTo(EXPECTED_BATCHES * 2);
        assertThat(run1.filterHistogram().get("ungrounded")).isEqualTo(EXPECTED_BATCHES);
        assertThat(run1.filterHistogram().get("badSeverity")).isEqualTo(EXPECTED_BATCHES);
        assertThat(run1.totalInputTokens()).isPositive();

        // --------------------------- 3. enriched evidence snapshot (spec §5.2)
        List<Finding> findings = findingRepository.findByDomainIdAndAiGeneratedTrue(domainId);
        assertThat(findings).hasSize(EXPECTED_BATCHES);
        for (Finding finding : findings) {
            assertThat(finding.getAssertionId()).isNotNull();
            assertThat(finding.getCurationStatus()).isEqualTo(CurationStatus.PENDING_REVIEW);
            assertThat(finding.getSynthesisRunId()).isEqualTo(run1.runId());
            assertThat(finding.getMetadata().path("candidateType").asText()).isEqualTo("CONTENT");
            assertThat(finding.getMetadata().path("groundingMode").asText())
                    .isEqualTo("EXISTENCE_ONLY");
            assertThat(finding.getEvidence().isArray()).isTrue();
            assertThat(finding.getEvidence().size()).isPositive();
            finding.getEvidence().forEach(entry -> {
                assertThat(entry.hasNonNull("evidenceId")).isTrue();
                assertThat(entry.path("sourceFile").asText()).contains("glacier");
                assertThat(entry.hasNonNull("documentRegistryId")).isTrue();
                assertThat(entry.hasNonNull("label")).isTrue();
            });
        }

        // ------------------ 4. provenance walk: chainComplete=true (spec §6.1)
        Finding walked = findings.get(0);
        FindingProvenance provenance = provenanceWalkService.walk(walked.getId());
        assertThat(provenance.chainComplete())
                .as("full P6 chain: finding→assertion→evidence→registry→ingest run")
                .isTrue();
        assertThat(provenance.missingHops()).isEmpty();
        assertThat(provenance.groundingMode()).isEqualTo("EXISTENCE_ONLY");
        assertThat(provenance.fixtureGrounded())
                .as("Glacier evidence is ingestion-grounded, never fixture-grounded")
                .isFalse();
        assertThat(provenance.evidence()).isNotEmpty();
        assertThat(provenance.documentRegistry()).isNotEmpty();
        assertThat(provenance.ingestRuns()).isNotEmpty();

        DiagnosticVerifyReport verify = provenanceWalkService.verify(domainId);
        assertThat(verify.total()).isEqualTo(EXPECTED_BATCHES);
        assertThat(verify.brokenChains()).isEmpty();
        assertThat(verify.fixtureGrounded()).isEmpty();

        // ------------------------------------ 5. resume idempotency (spec §4.4)
        DiagnosticRunDetail resumed = runService.resume(run1.runId(), true);
        assertThat(resumed.status()).isEqualTo(DiagnosticRunService.STATUS_COMPLETED);
        assertThat(findingRepository.findByDomainIdAndAiGeneratedTrue(domainId))
                .as("resume of a terminal run is a no-op — zero duplicates")
                .hasSize(EXPECTED_BATCHES);

        // ----------------------- 6. curate, then supersession on re-run (D3)
        Finding accepted = findings.get(0);
        Finding rejected = findings.get(1);
        curationService.accept(accepted.getId(), "verified against glacier-systems.csv",
                "e2e-accept-1", "Riley Operator", "L3_ANALYST");
        curationService.reject(rejected.getId(), "evidence does not support the claim",
                "e2e-reject-1", "Riley Operator", "L3_ANALYST");

        DiagnosticRunDetail run2 = runService.getRun(startRun(domainId, 1, "e2e-run-2").runId());
        assertThat(run2.status()).isEqualTo(DiagnosticRunService.STATUS_COMPLETED);
        assertThat(run2.findingsCommitted()).isEqualTo(EXPECTED_BATCHES);

        assertThat(statusOf(accepted.getId()))
                .as("ACCEPTED is never touched by supersession").isEqualTo(CurationStatus.ACCEPTED);
        assertThat(statusOf(rejected.getId()))
                .as("REJECTED is never touched by supersession").isEqualTo(CurationStatus.REJECTED);

        List<Finding> run1Pending = findings.stream()
                .filter(f -> !f.getId().equals(accepted.getId()) && !f.getId().equals(rejected.getId()))
                .toList();
        for (Finding prior : run1Pending) {
            assertThat(statusOf(prior.getId())).isEqualTo(CurationStatus.SUPERSEDED);
            var events = curationEventRepository.findBySubjectKindAndSubjectIdOrderByCreatedAtAsc(
                    CurationSubjectKind.FINDING, prior.getId());
            assertThat(events).hasSize(1);
            assertThat(events.get(0).getAction()).isEqualTo(CurationAction.SUPERSEDE);
            assertThat(events.get(0).getActorLabel()).isEqualTo("system:diagnostic");
            assertThat(events.get(0).getSynthesisRunId()).isEqualTo(run2.runId());
        }
        assertThat(findingRepository.findByDomainIdAndCurationStatus(
                domainId, CurationStatus.PENDING_REVIEW))
                .as("run2's findings are the new pending set")
                .hasSize(EXPECTED_BATCHES)
                .allSatisfy(f -> assertThat(f.getSynthesisRunId()).isEqualTo(run2.runId()));

        // ------------------------------------- 7. zero-yield path (spec §10.2)
        llm.engineMock().setDiagnosticScript(MockAnthropicClient.SCRIPT_UNGROUNDED_CONTENT);
        DiagnosticRunDetail zeroYield = runService.getRun(
                startRun(domainId, 1, "e2e-zero-yield").runId());
        assertThat(zeroYield.status()).isEqualTo(DiagnosticRunService.STATUS_ZERO_YIELD);
        assertThat(zeroYield.reason()).isEqualTo(DiagnosticRunService.REASON_ALL_FILTERED);
        assertThat(zeroYield.findingsCommitted()).isZero();
        assertThat(zeroYield.events()).contains("zero_yield");
        // A deficient run supersedes NOTHING — run2's pending set survives.
        assertThat(findingRepository.findByDomainIdAndCurationStatus(
                domainId, CurationStatus.PENDING_REVIEW)).hasSize(EXPECTED_BATCHES);
    }

    // ------------------------------------------------------------------ helpers

    private DiagnosticRunService.StartResult startRun(UUID domainId, int required, String key) {
        return runService.startRun(new DiagnosticRunService.StartCommand(
                domainId, null, null, null, required, null, false, key, "e2e-test", true));
    }

    private CurationStatus statusOf(UUID findingId) {
        return findingRepository.findById(findingId).orElseThrow().getCurationStatus();
    }
}
