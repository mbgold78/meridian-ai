package com.meridian.substrate.ingestion.e2e;

import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.repo.CanonicalEntityRepository;
import com.meridian.substrate.common.repo.DocumentRegistryRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.EvidenceRepository;
import com.meridian.substrate.common.repo.IngestRunRepository;
import com.meridian.substrate.common.repo.OrganizationMemberRepository;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.ProcessRoleRepository;
import com.meridian.substrate.common.repo.ProcessSystemLinkRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import com.meridian.substrate.ingestion.commit.CommitResult;
import com.meridian.substrate.ingestion.commit.CommitService;
import com.meridian.substrate.ingestion.orchestration.ConversionPipeline;
import com.meridian.substrate.ingestion.orchestration.ConversionResult;
import com.meridian.substrate.ingestion.staging.StagingResult;
import com.meridian.substrate.ingestion.staging.StagingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end acceptance pin for chunk C6 (port-map §2.4 / §7): the full
 * convert → stage → commit chain over the checked-in sample corpus
 * ({@code ingestion/samples/}, fictional Glacier Peak Credit Union data),
 * against real Flyway-migrated H2 with the mock LLM provider.
 *
 * <p>Binding norms pinned here:
 * <ul>
 *   <li><b>Idempotency</b> — re-running commit over the same staging dir
 *       yields identical row counts (sha256 file skip + natural-key upsert).</li>
 *   <li><b>name→UUID resolution</b> — links/roles/canonical-entities persist
 *       with resolved FK UUIDs, never name strings.</li>
 *   <li><b>Provenance</b> — IngestRun audit rows; DocumentRegistry rows for
 *       source AND staged files stamped with processedByConverterRunId.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class IngestionPipelineE2eTest {

    private static final Path SAMPLES = Path.of("samples");

    @Autowired private ConversionPipeline conversionPipeline;
    @Autowired private StagingService stagingService;
    @Autowired private CommitService commitService;

    @Autowired private DomainRepository domainRepository;
    @Autowired private SystemRepository systemRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private OrganizationMemberRepository organizationMemberRepository;
    @Autowired private CanonicalEntityRepository canonicalEntityRepository;
    @Autowired private ProcessSystemLinkRepository processSystemLinkRepository;
    @Autowired private ProcessRoleRepository processRoleRepository;
    @Autowired private DocumentRegistryRepository documentRegistryRepository;
    @Autowired private EvidenceRepository evidenceRepository;
    @Autowired private IngestRunRepository ingestRunRepository;

    @TempDir Path outputDir;
    @TempDir Path stagingDir;

    @Test
    void convertStageCommitOverSampleCorpus_isIdempotentOnRecommit() throws Exception {
        assertThat(Files.isDirectory(SAMPLES))
                .as("sample corpus at ingestion/samples (test runs in the module dir)")
                .isTrue();

        // ---- convert (mock provider — deterministic row-echo extraction) ----
        ConversionResult conversion = conversionPipeline.convertAll(SAMPLES, outputDir);
        assertThat(conversion.errorMessages()).isEmpty();
        assertThat(conversion.filesProcessed()).isEqualTo(4);
        assertThat(conversion.filesAlreadyIngested()).isZero();
        // 6 systems + (6 processes + 9 links + 10 roles) + 5 entities + 1 workflow process
        assertThat(conversion.entitiesExtracted()).isEqualTo(37);
        assertThat(outputDir.resolve("glacier-systems.systems.json")).exists();
        assertThat(outputDir.resolve("glacier-manual-processes.processes.json")).exists();
        assertThat(outputDir.resolve("glacier-manual-processes.process-system-links.json")).exists();
        assertThat(outputDir.resolve("glacier-manual-processes.process-roles.json")).exists();
        assertThat(outputDir.resolve("glacier-entities.canonical-entities.json")).exists();
        assertThat(outputDir.resolve("glacier-workflow-notes.processes.json")).exists();
        assertThat(outputDir.resolve("_manifest.json")).exists();

        // ---- stage ----
        StagingResult staging = stagingService.stage(outputDir, stagingDir);
        assertThat(staging.filesStaged()).isEqualTo(7); // 6 entity files + manifest

        // ---- commit ----
        CommitResult commit = commitService.commit(stagingDir, null);
        assertThat(commit.errorMessages()).isEmpty();
        assertThat(commit.filesCommitted()).isEqualTo(6);
        assertThat(commit.filesAlreadyCommitted()).isZero();
        assertThat(commit.entitiesUpdated()).isZero();
        // 6 systems + 7 processes + 5 skeleton members + 5 entities + 9 links + 10 roles
        assertThat(commit.entitiesCommitted()).isEqualTo(42);

        Domain domain = domainRepository.findFirstByName("Ingested").orElseThrow();

        assertThat(systemRepository.count()).isEqualTo(6);
        assertThat(processRepository.count()).isEqualTo(7);
        assertThat(organizationMemberRepository.count()).isEqualTo(5);
        assertThat(canonicalEntityRepository.count()).isEqualTo(5);
        assertThat(processSystemLinkRepository.count()).isEqualTo(9);
        assertThat(processRoleRepository.count()).isEqualTo(10);

        // name→UUID resolution: every link carries resolved FK UUIDs.
        processSystemLinkRepository.findAll().forEach(link -> {
            assertThat(link.getProcessId()).isNotNull();
            assertThat(link.getSystemId()).isNotNull();
        });
        processRoleRepository.findAll().forEach(role -> {
            assertThat(role.getProcessId()).isNotNull();
            assertThat(role.getMemberId()).isNotNull();
        });
        canonicalEntityRepository.findByDomainId(domain.getId()).forEach(entity ->
                assertThat(entity.getSystemId()).as("canonical entity %s", entity.getName()).isNotNull());

        // Provenance: 4 source rows + 6 staged rows, all stamped with a run id.
        assertThat(documentRegistryRepository.count()).isEqualTo(10);
        documentRegistryRepository.findAll().forEach(doc ->
                assertThat(doc.getProcessedByConverterRunId())
                        .as("registry row %s stamped", doc.getSourceFile()).isNotNull());

        // One evidence row per created entity.
        assertThat(evidenceRepository.count()).isEqualTo(42);

        // Audit: convert + commit runs, both SUCCEEDED.
        assertThat(ingestRunRepository.count()).isEqualTo(2);
        ingestRunRepository.findAll().forEach(run ->
                assertThat(run.getStatus()).isEqualTo("SUCCEEDED"));

        // ---- IDEMPOTENCY: re-run commit over the SAME staging dir ----
        CommitResult recommit = commitService.commit(stagingDir, null);
        assertThat(recommit.errorMessages()).isEmpty();
        assertThat(recommit.filesCommitted()).isZero();
        assertThat(recommit.filesAlreadyCommitted()).isEqualTo(6);
        assertThat(recommit.entitiesCommitted()).isZero();
        assertThat(recommit.entitiesUpdated()).isZero();

        assertThat(systemRepository.count()).isEqualTo(6);
        assertThat(processRepository.count()).isEqualTo(7);
        assertThat(organizationMemberRepository.count()).isEqualTo(5);
        assertThat(canonicalEntityRepository.count()).isEqualTo(5);
        assertThat(processSystemLinkRepository.count()).isEqualTo(9);
        assertThat(processRoleRepository.count()).isEqualTo(10);
        assertThat(evidenceRepository.count()).isEqualTo(42);
        assertThat(documentRegistryRepository.count()).isEqualTo(10);
        assertThat(domainRepository.count()).isEqualTo(1);

        // ---- IDEMPOTENCY: re-convert skips every already-committed source ----
        ConversionResult reconversion = conversionPipeline.convertAll(SAMPLES, outputDir);
        assertThat(reconversion.filesProcessed()).isZero();
        assertThat(reconversion.filesAlreadyIngested()).isEqualTo(4);
        assertThat(reconversion.errorMessages()).isEmpty();
    }
}
