package com.meridian.substrate.app.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Assertion;
import com.meridian.substrate.common.domain.BrandingConfig;
import com.meridian.substrate.common.domain.CanonicalEntity;
import com.meridian.substrate.common.domain.Capability;
import com.meridian.substrate.common.domain.CapabilityNameHistory;
import com.meridian.substrate.common.domain.CapabilityProcessLink;
import com.meridian.substrate.common.domain.CapabilitySystemLink;
import com.meridian.substrate.common.domain.ChatInteraction;
import com.meridian.substrate.common.domain.DesignDecision;
import com.meridian.substrate.common.domain.DocumentRegistry;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.EntityOverlap;
import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.IngestRun;
import com.meridian.substrate.common.domain.Integration;
import com.meridian.substrate.common.domain.OrganizationMember;
import com.meridian.substrate.common.domain.PhaseState;
import com.meridian.substrate.common.domain.Process;
import com.meridian.substrate.common.domain.ProcessRole;
import com.meridian.substrate.common.domain.ProcessStep;
import com.meridian.substrate.common.domain.ProcessSystemLink;
import com.meridian.substrate.common.domain.ReaderRegistration;
import com.meridian.substrate.common.domain.RoleOverlap;
import com.meridian.substrate.common.domain.SynthesisRun;
import com.meridian.substrate.common.domain.SystemEntity;
import com.meridian.substrate.common.domain.SystemUpload;
import com.meridian.substrate.common.domain.enums.BrandingConfigStatus;
import com.meridian.substrate.common.domain.enums.CapabilityStatus;
import com.meridian.substrate.common.domain.enums.DecisionStatus;
import com.meridian.substrate.common.domain.enums.DecisionType;
import com.meridian.substrate.common.domain.enums.DefenseLine;
import com.meridian.substrate.common.domain.enums.DocumentType;
import com.meridian.substrate.common.domain.enums.DocumentationQuality;
import com.meridian.substrate.common.domain.enums.ExecutionMode;
import com.meridian.substrate.common.domain.enums.FindingStatus;
import com.meridian.substrate.common.domain.enums.FindingType;
import com.meridian.substrate.common.domain.enums.IngestionStatus;
import com.meridian.substrate.common.domain.enums.IntegrationStatus;
import com.meridian.substrate.common.domain.enums.IntegrationType;
import com.meridian.substrate.common.domain.enums.LifecyclePhase;
import com.meridian.substrate.common.domain.enums.LinkStatus;
import com.meridian.substrate.common.domain.enums.LinkType;
import com.meridian.substrate.common.domain.enums.ManualProcessClassification;
import com.meridian.substrate.common.domain.enums.Participation;
import com.meridian.substrate.common.domain.enums.PhaseStatus;
import com.meridian.substrate.common.domain.enums.ProcessStepExecutionMode;
import com.meridian.substrate.common.domain.enums.ProducedBy;
import com.meridian.substrate.common.domain.enums.ReaderKind;
import com.meridian.substrate.common.domain.enums.ReaderStatus;
import com.meridian.substrate.common.domain.enums.RiskRating;
import com.meridian.substrate.common.domain.enums.Severity;
import com.meridian.substrate.common.domain.enums.StepType;
import com.meridian.substrate.common.repo.AssertionRepository;
import com.meridian.substrate.common.repo.BrandingConfigRepository;
import com.meridian.substrate.common.repo.CapabilityRepository;
import com.meridian.substrate.common.repo.ChatInteractionRepository;
import com.meridian.substrate.common.repo.DocumentRegistryRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.EvidenceRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.repo.IngestRunRepository;
import com.meridian.substrate.common.repo.PhaseStateRepository;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.ProcessStepRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C11 Phase-1 close gate — repository round-trips for ALL 27 canonical
 * entities against real Postgres 16 under {@code ddl-auto=validate}
 * (port-map §2.1 hard rule: "Verify with a Postgres Testcontainers
 * round-trip test: all entities persist + read … JSON columns + optimistic
 * locking included. H2-green is not the gate.").
 *
 * <p>Every entity is persisted, flushed to Postgres, evicted from the
 * persistence context, and re-read — so every column type (UUID, TIMESTAMPTZ,
 * TEXT-backed {@code JsonNodeConverter} columns, FLOAT/DOUBLE PRECISION,
 * STRING-mapped enums) is exercised through the real PG dialect. The second
 * test runs the dialect-sensitive repository queries, including the
 * {@code PESSIMISTIC_WRITE} variants that map to {@code SELECT … FOR UPDATE}.
 *
 * <p>Self-disables without Docker ({@code disabledWithoutDocker = true});
 * CI (ubuntu-latest) is the gate — port-map Appendix trap #4.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class PostgresRepositoryRoundTripIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private EntityManager em;

    @Autowired private DomainRepository domainRepository;
    @Autowired private SystemRepository systemRepository;
    @Autowired private ProcessRepository processRepository;
    @Autowired private ProcessStepRepository processStepRepository;
    @Autowired private FindingRepository findingRepository;
    @Autowired private AssertionRepository assertionRepository;
    @Autowired private EvidenceRepository evidenceRepository;
    @Autowired private DocumentRegistryRepository documentRegistryRepository;
    @Autowired private ChatInteractionRepository chatInteractionRepository;
    @Autowired private PhaseStateRepository phaseStateRepository;
    @Autowired private CapabilityRepository capabilityRepository;
    @Autowired private BrandingConfigRepository brandingConfigRepository;
    @Autowired private IngestRunRepository ingestRunRepository;

    // -----------------------------------------------------------------------
    // 1. All 27 entities: persist → flush → clear → reload on Postgres
    // -----------------------------------------------------------------------

    @Test
    @Transactional
    void allTwentySevenEntitiesRoundTripOnPostgres() {
        // --- parents first (real FK constraints exist in V2, unlike soft refs)
        Domain domain = newDomain();
        em.persist(domain);

        SystemEntity system = newSystem(domain.getId());
        em.persist(system);

        OrganizationMember member = newMember(domain.getId());
        em.persist(member);

        Process process = newProcess(domain.getId());
        em.persist(process);

        ProcessStep step = new ProcessStep();
        step.setProcessId(process.getId());
        step.setSequenceOrder(1);
        step.setName("Reconcile ledger extract");
        step.setDescription("Manual tie-out of GL vs. subledger");
        step.setStepType(StepType.HUMAN);
        step.setExecutionMode(ProcessStepExecutionMode.MANUAL);
        step.setAutomationCandidate(true);
        step.setAutomationRationale("Deterministic rule set");
        step.setAgentReadinessScore(72);
        step.setLinkedSystemId(system.getId());
        step.setAssignedPersonId(member.getId());
        step.setEstimatedMinutes(45);
        em.persist(step);

        ProcessRole role = new ProcessRole();
        role.setProcessId(process.getId());
        role.setMemberId(member.getId());
        role.setParticipation(Participation.OWNER);
        role.setTimeCommitment("4h/week");
        role.setAutomationCandidate(Boolean.TRUE);
        role.setAutomationRationale("Routine sign-off");
        em.persist(role);

        ProcessSystemLink psLink = new ProcessSystemLink();
        psLink.setProcessId(process.getId());
        psLink.setSystemId(system.getId());
        psLink.setLinkType(LinkType.FEEDS_INTO);
        psLink.setDataElements(json("[\"trade_id\",\"settle_date\"]"));
        psLink.setFrequency("daily");
        psLink.setStatus(LinkStatus.ACTIVE);
        psLink.setAiInferred(Boolean.FALSE);
        em.persist(psLink);

        CanonicalEntity canonicalA = newCanonicalEntity(domain.getId(), system.getId(), process.getId(), "Trade");
        em.persist(canonicalA);
        CanonicalEntity canonicalB = newCanonicalEntity(domain.getId(), system.getId(), process.getId(), "Position");
        em.persist(canonicalB);

        EntityOverlap overlap = new EntityOverlap();
        overlap.setDomainId(domain.getId());
        overlap.setSourceEntityId(canonicalA.getId());
        overlap.setTargetEntityId(canonicalB.getId());
        overlap.setOverlapType("SEMANTIC_DUPLICATE");
        overlap.setConfidence(0.83);
        overlap.setDetails(json("{\"sharedAttributes\":[\"isin\",\"quantity\"]}"));
        overlap.setAiInferred(Boolean.TRUE);
        em.persist(overlap);

        Integration integration = new Integration();
        integration.setDomainId(domain.getId());
        integration.setSourceSystemId(system.getId());
        integration.setTargetSystemId(system.getId());
        integration.setName("EOD batch feed");
        integration.setIntegrationType(IntegrationType.BATCH);
        integration.setIntegrationStatus(IntegrationStatus.ACTIVE);
        integration.setDataElements(json("[\"positions\"]"));
        integration.setFrequency("nightly");
        integration.setDescription("Nightly position sync");
        integration.setMetadata(json("{\"sla\":\"06:00\"}"));
        em.persist(integration);

        RoleOverlap roleOverlap = new RoleOverlap();
        roleOverlap.setDomainId(domain.getId());
        roleOverlap.setSourceMemberId(member.getId());
        roleOverlap.setTargetMemberId(member.getId());
        roleOverlap.setOverlapType("DUAL_HATTING");
        roleOverlap.setDetails(json("{\"sharedDuties\":[\"sign-off\"]}"));
        roleOverlap.setAiInferred(Boolean.FALSE);
        em.persist(roleOverlap);

        SynthesisRun synthesisRun = new SynthesisRun();
        synthesisRun.setDomainId(domain.getId());
        synthesisRun.setRunType("validative");
        synthesisRun.setStatus("COMPLETED");
        synthesisRun.setStartedAt(Instant.parse("2026-06-09T10:00:00Z"));
        synthesisRun.setCompletedAt(Instant.parse("2026-06-09T10:05:00Z"));
        synthesisRun.setFindingsGenerated(3);
        synthesisRun.setModel("mock");
        synthesisRun.setTotalInputTokens(1200);
        synthesisRun.setTotalOutputTokens(800);
        synthesisRun.setTotalCostEstimate(0.078);
        em.persist(synthesisRun);

        Finding finding = new Finding();
        finding.setDomainId(domain.getId());
        finding.setFindingType(FindingType.DUPLICATION);
        finding.setSeverity(Severity.HIGH);
        finding.setStatus(FindingStatus.OPEN);
        finding.setTitle("Duplicate trade store");
        finding.setDescription("Two systems persist the same canonical Trade entity");
        finding.setAffectedSystemIds(json("[\"" + system.getId() + "\"]"));
        finding.setAffectedProcessIds(json("[\"" + process.getId() + "\"]"));
        finding.setEvidence(json("{\"assertionCount\":2}"));
        finding.setRecommendation("Consolidate onto the strategic platform");
        finding.setAiGenerated(Boolean.TRUE);
        finding.setSynthesisRunId(synthesisRun.getId());
        em.persist(finding);

        DesignDecision decision = new DesignDecision();
        decision.setDomainId(domain.getId());
        decision.setDecisionType(DecisionType.CONSOLIDATE);
        decision.setStatus(DecisionStatus.PROPOSED);
        decision.setTitle("Consolidate trade stores");
        decision.setDescription("Retire the legacy trade store after migration");
        decision.setRationale("Duplication finding F-1");
        decision.setImpactSummary(json("{\"systems\":1,\"processes\":1}"));
        decision.setAffectedSystemIds(json("[\"" + system.getId() + "\"]"));
        decision.setAffectedProcessIds(json("[]"));
        decision.setPreviousHash("GENESIS");
        decision.setCurrentHash("c11-test-hash");
        em.persist(decision);

        ChatInteraction chat = new ChatInteraction();
        chat.setDomainId(domain.getId());
        chat.setPersonaId("L3_ANALYST");
        chat.setUserMessage("Which systems duplicate the Trade entity?");
        chat.setAssistantMessage("Two systems persist Trade …");
        chat.setToolCalls(json("[{\"tool\":\"find_canonical_entities\"}]"));
        chat.setModel("mock");
        chat.setPromptTemplateId("analyst-architect");
        chat.setPromptTemplateVersion("1");
        chat.setInputTokens(420);
        chat.setOutputTokens(180);
        chat.setLatencyMs(950L);
        chat.setRefusalDetected(Boolean.FALSE);
        chat.setCostEstimate(0.0021);
        em.persist(chat);

        DocumentRegistry doc = new DocumentRegistry();
        doc.setSourceFile("samples/systems.csv");
        doc.setSourceFileSha256("c11" + UUID.randomUUID().toString().replace("-", ""));
        doc.setSourceFileSize(2048L);
        doc.setDocumentType(DocumentType.SAMPLE_DATA);
        doc.setSourceSystem("manual-upload");
        doc.setIngestionStatus(IngestionStatus.LOCAL_ONLY);
        doc.setIngestedAt(Instant.parse("2026-06-09T09:00:00Z"));
        doc.setExtractedTextBytes(1024L);
        doc.setMetadata(json("{\"rows\":12}"));
        em.persist(doc);

        Evidence evidence = new Evidence();
        evidence.setEntityId(finding.getId());
        evidence.setEntityType("Finding");
        evidence.setDomainId(domain.getId());
        evidence.setDocumentRegistryId(doc.getId());
        evidence.setSourceFile("samples/systems.csv");
        evidence.setConfidence(0.91f);
        evidence.setLabel("Row 7: duplicate system-of-record claim");
        evidence.setExtractor("c11-it");
        em.persist(evidence);

        Assertion assertion = new Assertion();
        assertion.setDomainId(domain.getId());
        assertion.setSubject("system:" + system.getId());
        assertion.setPredicate("persists");
        assertion.setObject("canonical-entity:Trade");
        assertion.setConfidence(0.88f);
        assertion.setEvidenceIds(json("[\"" + evidence.getId() + "\"]"));
        assertion.setProducedBy(ProducedBy.AI_VALIDATIVE);
        assertion.setProducedByContext(json("{\"mode\":\"validative\",\"run\":\"c11\"}"));
        em.persist(assertion);

        Capability capability = newCapability(domain.getId(), null, "trade-lifecycle", 0, 0);
        em.persist(capability);

        CapabilityNameHistory nameHistory = new CapabilityNameHistory();
        nameHistory.setCapabilityId(capability.getId());
        nameHistory.setFromDisplayName("Trade Lifecycle");
        nameHistory.setToDisplayName("Trade Lifecycle Management");
        nameHistory.setRationale("Customer terminology alignment");
        em.persist(nameHistory);

        CapabilityProcessLink capProcLink = new CapabilityProcessLink();
        capProcLink.setCapabilityId(capability.getId());
        capProcLink.setProcessId(process.getId());
        capProcLink.setLinkType("REALIZES");
        capProcLink.setAiInferred(false);
        em.persist(capProcLink);

        CapabilitySystemLink capSysLink = new CapabilitySystemLink();
        capSysLink.setCapabilityId(capability.getId());
        capSysLink.setSystemId(system.getId());
        capSysLink.setLinkType("SUPPORTS");
        capSysLink.setAiInferred(true);
        em.persist(capSysLink);

        PhaseState phaseState = new PhaseState();
        phaseState.setDomainId(domain.getId());
        phaseState.setPhase(LifecyclePhase.DESIGN);
        phaseState.setStatus(PhaseStatus.IN_PROGRESS);
        phaseState.setEnteredAt(Instant.parse("2026-06-01T00:00:00Z"));
        phaseState.setBlockers(json("[]"));
        phaseState.setCheckpoints(json("{\"capability-tree-active\":false}"));
        em.persist(phaseState);

        ReaderRegistration reader = new ReaderRegistration();
        reader.setDomainId(domain.getId());
        reader.setKind(ReaderKind.STRUCTURED);
        reader.setName("Core CSV drop");
        reader.setUri("file:///srv/drop/systems.csv");
        reader.setAuthContext(json("{\"mode\":\"none\"}"));
        reader.setRegisteredAt(Instant.parse("2026-06-02T00:00:00Z"));
        reader.setLastReadAt(Instant.parse("2026-06-08T00:00:00Z"));
        reader.setLastFingerprintHash("abc123");
        reader.setStatus(ReaderStatus.ACTIVE);
        reader.setCoverageScore(0.5);
        reader.setMetadata(json("{\"format\":\"csv\"}"));
        em.persist(reader);

        BrandingConfig branding = newBrandingConfig(domain.getId(), BrandingConfigStatus.DRAFT);
        em.persist(branding);

        SystemUpload upload = new SystemUpload();
        upload.setSystemId(system.getId());
        upload.setFileName("schema.json");
        upload.setFileSize(512L);
        upload.setContentType("application/json");
        upload.setStoragePath("uploads/schema.json");
        upload.setMetadata(json("{\"virusScan\":\"clean\"}"));
        em.persist(upload);

        IngestRun ingestRun = new IngestRun();
        ingestRun.setDomainId(domain.getId());
        ingestRun.setCommand("commit");
        ingestRun.setSourceDir("samples/");
        ingestRun.setStatus(IngestRun.STATUS_SUCCEEDED);
        ingestRun.setStartedAt(Instant.parse("2026-06-09T08:00:00Z"));
        ingestRun.setFinishedAt(Instant.parse("2026-06-09T08:01:00Z"));
        ingestRun.setFilesProcessed(3);
        ingestRun.setFilesSkipped(0);
        ingestRun.setEntitiesCommitted(12);
        ingestRun.setEntitiesUpdated(0);
        ingestRun.setErrorCount(0);
        ingestRun.setEntityCounts(json("{\"systems\":5,\"processes\":7}"));
        ingestRun.setErrors(json("[]"));
        em.persist(ingestRun);

        // --- write everything to Postgres, evict, and re-read from the DB
        em.flush();
        em.clear();

        Domain dbDomain = em.find(Domain.class, domain.getId());
        assertThat(dbDomain.getName()).isEqualTo("C11 Roundtrip Domain");
        assertThat(dbDomain.isDemo()).isTrue();
        assertThat(dbDomain.getTaxonomyConfig()).isEqualTo(json("{\"tiers\":[\"core\",\"ancillary\"]}"));
        assertThat(dbDomain.getVersion()).isZero();
        assertThat(dbDomain.getCreatedAt()).isNotNull();
        assertThat(dbDomain.getUpdatedAt()).isNotNull();
        assertThat(dbDomain.getCreatedBy()).isEqualTo("system");

        SystemEntity dbSystem = em.find(SystemEntity.class, system.getId());
        assertThat(dbSystem.getDomainId()).isEqualTo(domain.getId());
        assertThat(dbSystem.getName()).isEqualTo("Legacy Trade Store");
        assertThat(dbSystem.getDeployedYear()).isEqualTo(2009);
        assertThat(dbSystem.getMetadata()).isEqualTo(json("{\"hosting\":\"on-prem\"}"));

        OrganizationMember dbMember = em.find(OrganizationMember.class, member.getId());
        assertThat(dbMember.getName()).isEqualTo("Dana Ops");
        assertThat(dbMember.getReportsTo()).isEqualTo("COO");

        Process dbProcess = em.find(Process.class, process.getId());
        assertThat(dbProcess.getClassification()).isEqualTo(ManualProcessClassification.RECONCILIATION);
        assertThat(dbProcess.getDefenseLine()).isEqualTo(DefenseLine.FIRST_LINE);
        assertThat(dbProcess.getDocumentationQuality()).isEqualTo(DocumentationQuality.PARTIALLY_DOCUMENTED);
        assertThat(dbProcess.getRiskRating()).isEqualTo(RiskRating.MEDIUM);
        assertThat(dbProcess.getEstimatedFte()).isEqualTo(1.5);
        assertThat(dbProcess.getSupportingArtifacts()).isEqualTo(json("[\"runbook.docx\"]"));

        ProcessStep dbStep = em.find(ProcessStep.class, step.getId());
        assertThat(dbStep.getSequenceOrder()).isEqualTo(1);
        assertThat(dbStep.getExecutionMode()).isEqualTo(ProcessStepExecutionMode.MANUAL);
        assertThat(dbStep.getLinkedSystemId()).isEqualTo(system.getId());
        assertThat(dbStep.getAssignedPersonId()).isEqualTo(member.getId());

        ProcessRole dbRole = em.find(ProcessRole.class, role.getId());
        assertThat(dbRole.getParticipation()).isEqualTo(Participation.OWNER);

        ProcessSystemLink dbPsLink = em.find(ProcessSystemLink.class, psLink.getId());
        assertThat(dbPsLink.getLinkType()).isEqualTo(LinkType.FEEDS_INTO);
        assertThat(dbPsLink.getDataElements()).isEqualTo(json("[\"trade_id\",\"settle_date\"]"));

        CanonicalEntity dbCanonical = em.find(CanonicalEntity.class, canonicalA.getId());
        assertThat(dbCanonical.getEntityType()).isEqualTo("BUSINESS_OBJECT");
        assertThat(dbCanonical.getExecutionMode()).isEqualTo(ExecutionMode.SYSTEM);
        assertThat(dbCanonical.getAttributes()).isEqualTo(json("{\"keys\":[\"isin\"]}"));

        EntityOverlap dbOverlap = em.find(EntityOverlap.class, overlap.getId());
        assertThat(dbOverlap.getConfidence()).isEqualTo(0.83);
        assertThat(dbOverlap.getSourceEntityId()).isEqualTo(canonicalA.getId());

        Integration dbIntegration = em.find(Integration.class, integration.getId());
        assertThat(dbIntegration.getIntegrationType()).isEqualTo(IntegrationType.BATCH);
        assertThat(dbIntegration.getIntegrationStatus()).isEqualTo(IntegrationStatus.ACTIVE);

        RoleOverlap dbRoleOverlap = em.find(RoleOverlap.class, roleOverlap.getId());
        assertThat(dbRoleOverlap.getOverlapType()).isEqualTo("DUAL_HATTING");

        SynthesisRun dbSynthesis = em.find(SynthesisRun.class, synthesisRun.getId());
        assertThat(dbSynthesis.getTotalInputTokens()).isEqualTo(1200);
        assertThat(dbSynthesis.getTotalCostEstimate()).isEqualTo(0.078);
        assertThat(dbSynthesis.getStartedAt()).isEqualTo(Instant.parse("2026-06-09T10:00:00Z"));

        Finding dbFinding = em.find(Finding.class, finding.getId());
        assertThat(dbFinding.getFindingType()).isEqualTo(FindingType.DUPLICATION);
        assertThat(dbFinding.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(dbFinding.getAiGenerated()).isTrue();
        assertThat(dbFinding.getSynthesisRunId()).isEqualTo(synthesisRun.getId());

        DesignDecision dbDecision = em.find(DesignDecision.class, decision.getId());
        assertThat(dbDecision.getDecisionType()).isEqualTo(DecisionType.CONSOLIDATE);
        assertThat(dbDecision.getPreviousHash()).isEqualTo("GENESIS");

        ChatInteraction dbChat = em.find(ChatInteraction.class, chat.getId());
        assertThat(dbChat.getToolCalls()).isEqualTo(json("[{\"tool\":\"find_canonical_entities\"}]"));
        assertThat(dbChat.getRefusalDetected()).isFalse();
        assertThat(dbChat.getLatencyMs()).isEqualTo(950L);

        DocumentRegistry dbDoc = em.find(DocumentRegistry.class, doc.getId());
        assertThat(dbDoc.getIngestionStatus()).isEqualTo(IngestionStatus.LOCAL_ONLY);
        assertThat(dbDoc.getIngestedToTachyon()).isFalse();
        assertThat(dbDoc.getDocumentType()).isEqualTo(DocumentType.SAMPLE_DATA);

        Evidence dbEvidence = em.find(Evidence.class, evidence.getId());
        assertThat(dbEvidence.getEntityType()).isEqualTo("Finding");
        assertThat(dbEvidence.getConfidence()).isEqualTo(0.91f);
        assertThat(dbEvidence.getDocumentRegistryId()).isEqualTo(doc.getId());

        Assertion dbAssertion = em.find(Assertion.class, assertion.getId());
        assertThat(dbAssertion.getProducedBy()).isEqualTo(ProducedBy.AI_VALIDATIVE);
        assertThat(dbAssertion.getConfidence()).isEqualTo(0.88f);
        assertThat(dbAssertion.getProducedByContext()).isEqualTo(json("{\"mode\":\"validative\",\"run\":\"c11\"}"));

        Capability dbCapability = em.find(Capability.class, capability.getId());
        assertThat(dbCapability.getStatus()).isEqualTo(CapabilityStatus.DRAFT);
        assertThat(dbCapability.getLevel()).isZero();
        assertThat(dbCapability.isAiGenerated()).isFalse();

        CapabilityNameHistory dbNameHistory = em.find(CapabilityNameHistory.class, nameHistory.getId());
        assertThat(dbNameHistory.getToDisplayName()).isEqualTo("Trade Lifecycle Management");

        CapabilityProcessLink dbCapProcLink = em.find(CapabilityProcessLink.class, capProcLink.getId());
        assertThat(dbCapProcLink.getProcessId()).isEqualTo(process.getId());

        CapabilitySystemLink dbCapSysLink = em.find(CapabilitySystemLink.class, capSysLink.getId());
        assertThat(dbCapSysLink.isAiInferred()).isTrue();

        PhaseState dbPhase = em.find(PhaseState.class, phaseState.getId());
        assertThat(dbPhase.getPhase()).isEqualTo(LifecyclePhase.DESIGN);
        assertThat(dbPhase.getStatus()).isEqualTo(PhaseStatus.IN_PROGRESS);
        assertThat(dbPhase.getCheckpoints()).isEqualTo(json("{\"capability-tree-active\":false}"));

        ReaderRegistration dbReader = em.find(ReaderRegistration.class, reader.getId());
        assertThat(dbReader.getKind()).isEqualTo(ReaderKind.STRUCTURED);
        assertThat(dbReader.getStatus()).isEqualTo(ReaderStatus.ACTIVE);
        assertThat(dbReader.getCoverageScore()).isEqualTo(0.5);

        BrandingConfig dbBranding = em.find(BrandingConfig.class, branding.getId());
        assertThat(dbBranding.getStatus()).isEqualTo(BrandingConfigStatus.DRAFT);
        assertThat(dbBranding.getColorTokensLight()).isEqualTo(json("{\"--primary\":\"222 47% 11%\"}"));
        assertThat(dbBranding.getTerminologyOverrides()).isEqualTo(json("{\"domain\":\"workspace\"}"));

        SystemUpload dbUpload = em.find(SystemUpload.class, upload.getId());
        assertThat(dbUpload.getFileName()).isEqualTo("schema.json");

        IngestRun dbIngestRun = em.find(IngestRun.class, ingestRun.getId());
        assertThat(dbIngestRun.getStatus()).isEqualTo(IngestRun.STATUS_SUCCEEDED);
        assertThat(dbIngestRun.getEntityCounts()).isEqualTo(json("{\"systems\":5,\"processes\":7}"));
        assertThat(dbIngestRun.getStartedAt()).isEqualTo(Instant.parse("2026-06-09T08:00:00Z"));
    }

    // -----------------------------------------------------------------------
    // 2. Repository queries — derived, JPQL, and PESSIMISTIC_WRITE on PG
    // -----------------------------------------------------------------------

    @Test
    @Transactional
    void repositoryQueriesAndPessimisticLocksExecuteOnPostgres() {
        Domain domain = domainRepository.save(newDomain());
        SystemEntity system = systemRepository.save(newSystem(domain.getId()));
        Process process = processRepository.save(newProcess(domain.getId()));

        ProcessStep step2 = new ProcessStep();
        step2.setProcessId(process.getId());
        step2.setSequenceOrder(2);
        step2.setName("Second step");
        step2.setAutomationCandidate(false);
        processStepRepository.save(step2);
        ProcessStep step1 = new ProcessStep();
        step1.setProcessId(process.getId());
        step1.setSequenceOrder(1);
        step1.setName("First step");
        step1.setAutomationCandidate(false);
        processStepRepository.save(step1);

        Finding finding = new Finding();
        finding.setDomainId(domain.getId());
        finding.setFindingType(FindingType.DUPLICATION);
        finding.setTitle("Dup");
        finding.setDescription("Duplicate store");
        findingRepository.save(finding);

        Assertion assertion = new Assertion();
        assertion.setDomainId(domain.getId());
        assertion.setSubject("s");
        assertion.setPredicate("p");
        assertion.setObject("o");
        assertion.setProducedBy(ProducedBy.HUMAN);
        assertionRepository.save(assertion);

        Evidence evidence = new Evidence();
        evidence.setEntityId(finding.getId());
        evidence.setEntityType("Finding");
        evidence.setDomainId(domain.getId());
        evidenceRepository.save(evidence);

        DocumentRegistry doc = new DocumentRegistry();
        doc.setSourceFile("a.csv");
        doc.setSourceFileSha256("c11-query-sha256");
        documentRegistryRepository.save(doc);

        ChatInteraction chat = new ChatInteraction();
        chat.setDomainId(domain.getId());
        chat.setUserMessage("hi");
        chatInteractionRepository.save(chat);

        PhaseState phase = new PhaseState();
        phase.setDomainId(domain.getId());
        phase.setPhase(LifecyclePhase.PLUG_IN);
        phaseStateRepository.save(phase);

        Capability root = capabilityRepository.save(newCapability(domain.getId(), null, "root", 0, 0));
        capabilityRepository.save(newCapability(domain.getId(), root.getId(), "child-b", 1, 1));
        capabilityRepository.save(newCapability(domain.getId(), root.getId(), "child-a", 1, 0));

        brandingConfigRepository.save(newBrandingConfig(domain.getId(), BrandingConfigStatus.ACTIVE));

        IngestRun run = new IngestRun();
        run.setDomainId(domain.getId());
        run.setStartedAt(Instant.now());
        ingestRunRepository.save(run);

        em.flush();
        em.clear();

        // Derived queries
        assertThat(systemRepository.findByDomainId(domain.getId())).hasSize(1);
        assertThat(processRepository.findByClassification(ManualProcessClassification.RECONCILIATION))
                .extracting(Process::getId).contains(process.getId());
        assertThat(processStepRepository.findByProcessIdOrderBySequenceOrderAsc(process.getId()))
                .extracting(ProcessStep::getSequenceOrder).containsExactly(1, 2);
        assertThat(findingRepository.findByDomainIdAndFindingType(domain.getId(), FindingType.DUPLICATION)).hasSize(1);
        assertThat(assertionRepository.findByDomainIdAndProducedBy(domain.getId(), ProducedBy.HUMAN)).hasSize(1);
        assertThat(evidenceRepository.findByEntityIdAndEntityType(finding.getId(), "Finding")).hasSize(1);
        assertThat(documentRegistryRepository.findBySourceFileSha256("c11-query-sha256")).isPresent();
        assertThat(chatInteractionRepository.findByDomainIdOrderByCreatedAtDesc(domain.getId())).hasSize(1);
        assertThat(phaseStateRepository.findByDomainIdAndPhase(domain.getId(), LifecyclePhase.PLUG_IN)).isPresent();
        assertThat(ingestRunRepository.findByStatus(IngestRun.STATUS_RUNNING))
                .extracting(IngestRun::getId).contains(run.getId());

        // JPQL sibling queries + PESSIMISTIC_WRITE variants (SELECT … FOR UPDATE on PG)
        assertThat(capabilityRepository.findRootSiblings(domain.getId()))
                .extracting(Capability::getName).containsExactly("root");
        assertThat(capabilityRepository.findSiblingsUnderParent(domain.getId(), root.getId()))
                .extracting(Capability::getName).containsExactly("child-a", "child-b");
        assertThat(capabilityRepository.findRootSiblingsForUpdate(domain.getId())).hasSize(1);
        assertThat(capabilityRepository.findSiblingsUnderParentForUpdate(domain.getId(), root.getId())).hasSize(2);
        assertThat(brandingConfigRepository
                .findFirstByDomainIdAndStatusForUpdate(domain.getId(), BrandingConfigStatus.ACTIVE)).isPresent();
    }

    // -----------------------------------------------------------------------
    // 3. Optimistic locking (@Version) on PG
    // -----------------------------------------------------------------------

    @Test
    @Transactional
    void optimisticLockVersionIncrementsOnUpdate() {
        Domain domain = domainRepository.save(newDomain());
        em.flush();
        assertThat(domain.getVersion()).isZero();

        domain.setDescription("updated by c11 IT");
        em.flush();
        em.clear();

        Domain reloaded = em.find(Domain.class, domain.getId());
        assertThat(reloaded.getVersion()).isEqualTo(1L);
        assertThat(reloaded.getDescription()).isEqualTo("updated by c11 IT");
    }

    // -----------------------------------------------------------------------
    // entity factories
    // -----------------------------------------------------------------------

    private static Domain newDomain() {
        Domain domain = new Domain();
        domain.setName("C11 Roundtrip Domain");
        domain.setDescription("Postgres 16 round-trip gate");
        domain.setTaxonomyConfig(json("{\"tiers\":[\"core\",\"ancillary\"]}"));
        domain.setRegulatoryContext(json("[\"SR 11-7\"]"));
        domain.setMetadata(json("{\"source\":\"c11-it\"}"));
        domain.setDemo(true);
        return domain;
    }

    private static SystemEntity newSystem(UUID domainId) {
        SystemEntity system = new SystemEntity();
        system.setDomainId(domainId);
        system.setName("Legacy Trade Store");
        system.setVendor("HomeGrown");
        system.setDescription("Mainframe-era trade persistence");
        system.setSystemType("system-of-record");
        system.setDeployedYear(2009);
        system.setStatus("active"); // free-text status — ports AS-IS (§2.1)
        system.setBusinessLine("Markets");
        system.setMetadata(json("{\"hosting\":\"on-prem\"}"));
        return system;
    }

    private static OrganizationMember newMember(UUID domainId) {
        OrganizationMember member = new OrganizationMember();
        member.setDomainId(domainId);
        member.setName("Dana Ops");
        member.setTitle("Operations Lead");
        member.setDepartment("Trade Ops");
        member.setBusinessLine("Markets");
        member.setReportsTo("COO"); // name string, deliberately not an FK (§2.1)
        member.setMetadata(json("{\"location\":\"NYC\"}"));
        return member;
    }

    private static Process newProcess(UUID domainId) {
        Process process = new Process();
        process.setDomainId(domainId);
        process.setName("Daily cash reconciliation");
        process.setDescription("Manual tie-out between GL and custodian feed");
        process.setClassification(ManualProcessClassification.RECONCILIATION);
        process.setFrequency("daily");
        process.setPrimaryOwner("Dana Ops");
        process.setBusinessLine("Markets");
        process.setDefenseLine(DefenseLine.FIRST_LINE);
        process.setDocumentationQuality(DocumentationQuality.PARTIALLY_DOCUMENTED);
        process.setEstimatedFte(1.5);
        process.setRiskRating(RiskRating.MEDIUM);
        process.setSupportingArtifacts(json("[\"runbook.docx\"]"));
        process.setMetadata(json("{\"sox\":true}"));
        return process;
    }

    private static CanonicalEntity newCanonicalEntity(UUID domainId, UUID systemId, UUID processId, String name) {
        CanonicalEntity entity = new CanonicalEntity();
        entity.setDomainId(domainId);
        entity.setSystemId(systemId);
        entity.setName(name);
        entity.setEntityType("BUSINESS_OBJECT");
        entity.setDescription("Canonical " + name);
        entity.setAttributes(json("{\"keys\":[\"isin\"]}"));
        entity.setExecutionMode(ExecutionMode.SYSTEM);
        entity.setProcessId(processId);
        entity.setMetadata(json("{\"steward\":\"Dana Ops\"}"));
        return entity;
    }

    private static Capability newCapability(UUID domainId, UUID parentId, String name, int level, int sequenceOrder) {
        Capability capability = new Capability();
        capability.setDomainId(domainId);
        capability.setParentCapabilityId(parentId);
        capability.setName(name);
        capability.setDisplayName(name);
        capability.setDescription("C11 capability " + name);
        capability.setLevel(level);
        capability.setStatus(CapabilityStatus.DRAFT);
        capability.setAiGenerated(false);
        capability.setMetadata(json("{\"origin\":\"c11-it\"}"));
        capability.setSequenceOrder(sequenceOrder);
        return capability;
    }

    private static BrandingConfig newBrandingConfig(UUID domainId, BrandingConfigStatus status) {
        BrandingConfig branding = new BrandingConfig();
        branding.setDomainId(domainId);
        branding.setAppDisplayName("Substrate C11");
        branding.setTagline("Phase 1 close gate");
        branding.setColorTokensLight(json("{\"--primary\":\"222 47% 11%\"}"));
        branding.setColorTokensDark(json("{\"--primary\":\"210 40% 98%\"}"));
        branding.setFontBody("Inter");
        branding.setFontHeading("Inter");
        branding.setFontMono("JetBrains Mono");
        branding.setTerminologyOverrides(json("{\"domain\":\"workspace\"}"));
        branding.setStatus(status);
        return branding;
    }

    private static JsonNode json(String content) {
        try {
            return MAPPER.readTree(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Bad test JSON literal: " + content, e);
        }
    }
}
