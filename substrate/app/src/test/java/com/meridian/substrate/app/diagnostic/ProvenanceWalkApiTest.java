package com.meridian.substrate.app.diagnostic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AiCurationEvent;
import com.meridian.substrate.common.domain.Assertion;
import com.meridian.substrate.common.domain.DocumentRegistry;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.IngestRun;
import com.meridian.substrate.common.domain.SystemEntity;
import com.meridian.substrate.common.domain.enums.CurationAction;
import com.meridian.substrate.common.domain.enums.CurationStatus;
import com.meridian.substrate.common.domain.enums.CurationSubjectKind;
import com.meridian.substrate.common.domain.enums.DocumentType;
import com.meridian.substrate.common.domain.enums.FindingStatus;
import com.meridian.substrate.common.domain.enums.FindingType;
import com.meridian.substrate.common.domain.enums.IngestionStatus;
import com.meridian.substrate.common.domain.enums.ProducedBy;
import com.meridian.substrate.common.domain.enums.Severity;
import com.meridian.substrate.common.repo.AiCurationEventRepository;
import com.meridian.substrate.common.repo.AssertionRepository;
import com.meridian.substrate.common.repo.DocumentRegistryRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.EvidenceRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.repo.IngestRunRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Diagnostic test plan #9 (engine-spec diagnostic.md §14; build-order step 2):
 * the provenance-walk read model, shipped before any generation exists.
 *
 * <ul>
 *   <li>chainComplete=true over a corpus-grounded CONTENT finding (full P6
 *       chain: finding → assertion → evidence → documentRegistry →
 *       ingestRun);</li>
 *   <li>dangling-evidence hop → chainComplete=false with the missing hop
 *       NAMED, never elided;</li>
 *   <li>STRUCTURAL termination at the Assertion is a COMPLETE chain
 *       (spec §5.2/§6.2);</li>
 *   <li>fixtureGrounded classification of {@code extractor='seed'} chains
 *       (DemoPack shape: operator/fixture finding, no assertion,
 *       entity-linked seed evidence);</li>
 *   <li>verify sweep aggregates — broken and fixture-grounded as distinct
 *       classes, fixture rows ({@code aiGenerated=false}) never in the
 *       population.</li>
 * </ul>
 *
 * <p>H2 fast lane — Testcontainers-Postgres remains the acceptance gate
 * (PostgresDiagnosticPersistenceIT; H2-green is never acceptance).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class ProvenanceWalkApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Autowired DomainRepository domainRepository;
    @Autowired SystemRepository systemRepository;
    @Autowired FindingRepository findingRepository;
    @Autowired AssertionRepository assertionRepository;
    @Autowired EvidenceRepository evidenceRepository;
    @Autowired DocumentRegistryRepository documentRegistryRepository;
    @Autowired IngestRunRepository ingestRunRepository;
    @Autowired AiCurationEventRepository curationEventRepository;

    // ------------------------------------------------- full chain, complete

    @Test
    void corpusGroundedContentFindingWalksChainComplete() throws Exception {
        UUID domainId = domain("Glacier Walk");
        Chain chain = corpusChain(domainId);
        Finding finding = aiFinding(domainId, chain.assertion().getId(), "CONTENT");

        mvc.perform(get("/api/v1/findings/{id}/provenance", finding.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.chainComplete", is(true)))
                .andExpect(jsonPath("$.data.missingHops", hasSize(0)))
                .andExpect(jsonPath("$.data.groundingMode", is("EXISTENCE_ONLY")))
                .andExpect(jsonPath("$.data.fixtureGrounded", is(false)))
                .andExpect(jsonPath("$.data.evidenceSource", is("ASSERTION")))
                .andExpect(jsonPath("$.data.finding.id", is(finding.getId().toString())))
                .andExpect(jsonPath("$.data.finding.curationStatus", is("PENDING_REVIEW")))
                .andExpect(jsonPath("$.data.finding.assertionId", is(chain.assertion().getId().toString())))
                .andExpect(jsonPath("$.data.assertion.producedBy", is("AI_VALIDATIVE")))
                .andExpect(jsonPath("$.data.evidence", hasSize(1)))
                .andExpect(jsonPath("$.data.evidence[0].extractor", is("ingestion")))
                .andExpect(jsonPath("$.data.evidence[0].sourceFile", is("glacier-systems.csv")))
                .andExpect(jsonPath("$.data.documentRegistry", hasSize(1)))
                .andExpect(jsonPath("$.data.documentRegistry[0].sha256", notNullValue()))
                .andExpect(jsonPath("$.data.documentRegistry[0].processedByConverterRunId",
                        is(chain.ingestRun().getId().toString())))
                .andExpect(jsonPath("$.data.ingestRuns", hasSize(1)))
                .andExpect(jsonPath("$.data.ingestRuns[0].id", is(chain.ingestRun().getId().toString())))
                .andExpect(jsonPath("$.data.curationEvents", hasSize(0)));
    }

    // ------------------------------------------------- dangling hop, named

    @Test
    void danglingEvidenceHopIsNamedNeverElided() throws Exception {
        UUID domainId = domain("Dangling Hop");
        Chain chain = corpusChain(domainId);
        UUID vanished = UUID.randomUUID();
        Assertion assertion = assertion(domainId,
                "[\"" + chain.evidence().getId() + "\",\"" + vanished + "\"]");
        Finding finding = aiFinding(domainId, assertion.getId(), "CONTENT");

        mvc.perform(get("/api/v1/findings/{id}/provenance", finding.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chainComplete", is(false)))
                .andExpect(jsonPath("$.data.missingHops", hasSize(1)))
                .andExpect(jsonPath("$.data.missingHops[0]",
                        containsString("evidence: evidence row " + vanished + " not found")))
                // the resolving hop is still surfaced alongside the named gap
                .andExpect(jsonPath("$.data.evidence", hasSize(1)));
    }

    @Test
    void danglingAssertionHopIsNamed() throws Exception {
        UUID domainId = domain("Dangling Assertion");
        UUID vanished = UUID.randomUUID();
        Finding finding = aiFinding(domainId, vanished, "CONTENT");

        mvc.perform(get("/api/v1/findings/{id}/provenance", finding.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chainComplete", is(false)))
                .andExpect(jsonPath("$.data.assertion", nullValue()))
                .andExpect(jsonPath("$.data.missingHops[0]",
                        containsString("assertion: assertions row " + vanished + " not found")));
    }

    // ------------------------------------- STRUCTURAL termination is complete

    @Test
    void structuralFindingTerminatesAtAssertionChainComplete() throws Exception {
        UUID domainId = domain("Structural");
        Assertion assertion = assertion(domainId, "[]");
        Finding finding = aiFinding(domainId, assertion.getId(), "STRUCTURAL");

        mvc.perform(get("/api/v1/findings/{id}/provenance", finding.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chainComplete", is(true)))
                .andExpect(jsonPath("$.data.missingHops", hasSize(0)))
                .andExpect(jsonPath("$.data.evidence", hasSize(0)))
                .andExpect(jsonPath("$.data.groundingMode", is("EXISTENCE_ONLY")));
    }

    @Test
    void contentFindingWithNoCitationsIsBrokenNotStructural() throws Exception {
        UUID domainId = domain("Empty Content");
        Assertion assertion = assertion(domainId, "[]");
        Finding finding = aiFinding(domainId, assertion.getId(), "CONTENT");

        mvc.perform(get("/api/v1/findings/{id}/provenance", finding.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chainComplete", is(false)))
                .andExpect(jsonPath("$.data.missingHops[0]",
                        containsString("cites no evidence ids")));
    }

    // ------------------------------------------- fixture (DemoPack) chains

    @Test
    void fixtureFindingSurfacesSeedChainClassedFixtureGrounded() throws Exception {
        UUID domainId = domain("DemoPack Shape");

        SystemEntity system = new SystemEntity();
        system.setDomainId(domainId);
        system.setName("Legacy Risk Store");
        systemRepository.save(system);

        DocumentRegistry seedDoc = new DocumentRegistry();
        seedDoc.setSourceFile("systems.json");
        seedDoc.setSourceFileSha256("seed-systems-sha256");
        seedDoc.setDocumentType(DocumentType.SAMPLE_DATA);
        seedDoc.setSourceSystem("seed");
        seedDoc.setIngestionStatus(IngestionStatus.LOCAL_ONLY);
        seedDoc.setCreatedBy("seed");
        documentRegistryRepository.save(seedDoc);

        Evidence seedEvidence = new Evidence();
        seedEvidence.setEntityId(system.getId());
        seedEvidence.setEntityType("system");
        seedEvidence.setDomainId(domainId);
        seedEvidence.setDocumentRegistryId(seedDoc.getId());
        seedEvidence.setSourceFile("systems.json");
        seedEvidence.setConfidence(1.0f);
        seedEvidence.setLabel("System inventory — Legacy Risk Store");
        seedEvidence.setExtractor("seed");
        seedEvidence.setCreatedBy("seed");
        evidenceRepository.save(seedEvidence);

        // DiagnosticSeeder shape: aiGenerated=false, no assertion, curation NULL
        Finding fixture = new Finding();
        fixture.setDomainId(domainId);
        fixture.setFindingType(FindingType.DUPLICATION);
        fixture.setSeverity(Severity.HIGH);
        fixture.setStatus(FindingStatus.OPEN);
        fixture.setTitle("Duplicate risk store");
        fixture.setDescription("Fixture finding over seeded landscape");
        fixture.setAiGenerated(false);
        fixture.setAffectedSystemIds(mapper.createArrayNode().add(system.getId().toString()));
        fixture.setCreatedBy("seed");
        findingRepository.save(fixture);

        mvc.perform(get("/api/v1/findings/{id}/provenance", fixture.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fixtureGrounded", is(true)))
                .andExpect(jsonPath("$.data.evidenceSource", is("ENTITY_LINKED")))
                .andExpect(jsonPath("$.data.evidence", hasSize(1)))
                .andExpect(jsonPath("$.data.evidence[0].extractor", is("seed")))
                .andExpect(jsonPath("$.data.documentRegistry[0].sha256", is("seed-systems-sha256")))
                // honest, by name: no assertion hop, no ingest-run provenance
                .andExpect(jsonPath("$.data.chainComplete", is(false)))
                .andExpect(jsonPath("$.data.missingHops",
                        hasItem(containsString("operator/fixture finding has no assertion_id"))))
                .andExpect(jsonPath("$.data.missingHops",
                        hasItem(containsString("has no processedByConverterRunId"))))
                // fixture rows are not subject to curation
                .andExpect(jsonPath("$.data.finding.curationStatus", nullValue()))
                .andExpect(jsonPath("$.data.groundingMode", nullValue()));
    }

    // ------------------------------------------------ curation event history

    @Test
    void curationEventsSurfaceInOrder() throws Exception {
        UUID domainId = domain("Curation History");
        Chain chain = corpusChain(domainId);
        Finding finding = aiFinding(domainId, chain.assertion().getId(), "CONTENT");

        curationEvent(domainId, finding.getId(), CurationAction.REJECT, "not supported by row 7", "k-1");
        curationEvent(domainId, finding.getId(), CurationAction.ACCEPT, "re-checked: supported", "k-2");

        mvc.perform(get("/api/v1/findings/{id}/provenance", finding.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.curationEvents", hasSize(2)))
                .andExpect(jsonPath("$.data.curationEvents[0].action", is("REJECT")))
                .andExpect(jsonPath("$.data.curationEvents[0].rationale", is("not supported by row 7")))
                .andExpect(jsonPath("$.data.curationEvents[1].action", is("ACCEPT")))
                .andExpect(jsonPath("$.data.curationEvents[1].actorLabel", is("Riley Operator")));
    }

    // -------------------------------------------------------- verify sweep

    @Test
    void verifySweepAggregatesBrokenAndFixtureGroundedAsDistinctClasses() throws Exception {
        UUID domainId = domain("Verify Sweep");
        Chain chain = corpusChain(domainId);

        Finding complete = aiFinding(domainId, chain.assertion().getId(), "CONTENT");

        UUID vanished = UUID.randomUUID();
        Assertion danglingAssertion = assertion(domainId, "[\"" + vanished + "\"]");
        Finding broken = aiFinding(domainId, danglingAssertion.getId(), "CONTENT");

        Assertion structural = assertion(domainId, "[]");
        Finding structuralFinding = aiFinding(domainId, structural.getId(), "STRUCTURAL");

        // fixture row — aiGenerated=false, NEVER in the sweep population
        Finding fixture = new Finding();
        fixture.setDomainId(domainId);
        fixture.setFindingType(FindingType.COVERAGE_GAP);
        fixture.setTitle("Fixture row");
        fixture.setDescription("excluded from verify");
        fixture.setAiGenerated(false);
        fixture.setCreatedBy("seed");
        findingRepository.save(fixture);

        mvc.perform(get("/api/v1/diagnostics/verify").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.domainId", is(domainId.toString())))
                .andExpect(jsonPath("$.data.total", is(3)))
                .andExpect(jsonPath("$.data.chainComplete", is(2)))
                .andExpect(jsonPath("$.data.brokenChains", hasSize(1)))
                .andExpect(jsonPath("$.data.brokenChains[0].findingId", is(broken.getId().toString())))
                .andExpect(jsonPath("$.data.brokenChains[0].missingHop",
                        containsString("evidence: evidence row " + vanished + " not found")))
                .andExpect(jsonPath("$.data.fixtureGrounded", hasSize(0)))
                .andExpect(jsonPath("$.data.groundingModes.EXISTENCE_ONLY", is(3)));
    }

    @Test
    void verifySweepClassesSeedGroundedAiFindingFixtureGrounded() throws Exception {
        UUID domainId = domain("Seed Grounded AI");

        DocumentRegistry seedDoc = new DocumentRegistry();
        seedDoc.setSourceFile("processes.json");
        seedDoc.setSourceFileSha256("seed-processes-sha256");
        seedDoc.setSourceSystem("seed");
        seedDoc.setIngestionStatus(IngestionStatus.LOCAL_ONLY);
        documentRegistryRepository.save(seedDoc);

        Evidence seedEvidence = new Evidence();
        seedEvidence.setEntityId(UUID.randomUUID());
        seedEvidence.setEntityType("process");
        seedEvidence.setDomainId(domainId);
        seedEvidence.setDocumentRegistryId(seedDoc.getId());
        seedEvidence.setSourceFile("processes.json");
        seedEvidence.setExtractor("seed");
        evidenceRepository.save(seedEvidence);

        Assertion assertion = assertion(domainId, "[\"" + seedEvidence.getId() + "\"]");
        Finding seedGrounded = aiFinding(domainId, assertion.getId(), "CONTENT");

        // distinct classes: fixture-grounded AND broken (seed docs carry no
        // ingest-run provenance) — membership is independent, nothing conflated
        mvc.perform(get("/api/v1/diagnostics/verify").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", is(1)))
                .andExpect(jsonPath("$.data.fixtureGrounded",
                        containsInAnyOrder(seedGrounded.getId().toString())))
                .andExpect(jsonPath("$.data.brokenChains[0].missingHop",
                        containsString("has no processedByConverterRunId")));
    }

    @Test
    void verifySweepOverEmptyPopulationIsHonestZero() throws Exception {
        UUID domainId = domain("Pre-Generation");

        mvc.perform(get("/api/v1/diagnostics/verify").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", is(0)))
                .andExpect(jsonPath("$.data.chainComplete", is(0)))
                .andExpect(jsonPath("$.data.brokenChains", hasSize(0)))
                .andExpect(jsonPath("$.data.fixtureGrounded", hasSize(0)));
    }

    // ----------------------------------------------------------- not found

    @Test
    void unknownFindingIs404InTheEnvelope() throws Exception {
        mvc.perform(get("/api/v1/findings/{id}/provenance", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code", is("NOT_FOUND")));
    }

    // ------------------------------------------------------------ fixtures

    private record Chain(IngestRun ingestRun, DocumentRegistry document,
                         Evidence evidence, Assertion assertion) {}

    private UUID domain(String name) {
        Domain domain = new Domain();
        domain.setName(name);
        domain.setDescription("provenance walk test domain");
        return domainRepository.save(domain).getId();
    }

    /** Real-corpus chain shape: ingest run → registry doc → evidence → assertion. */
    private Chain corpusChain(UUID domainId) {
        IngestRun run = new IngestRun();
        run.setDomainId(domainId);
        run.setCommand("commit");
        run.setStatus(IngestRun.STATUS_SUCCEEDED);
        run.setStartedAt(Instant.parse("2026-06-10T08:00:00Z"));
        run.setFinishedAt(Instant.parse("2026-06-10T08:01:00Z"));
        ingestRunRepository.save(run);

        DocumentRegistry doc = new DocumentRegistry();
        doc.setSourceFile("glacier-systems.csv");
        doc.setSourceFileSha256("walk-" + UUID.randomUUID().toString().replace("-", ""));
        doc.setSourceSystem("ingestion");
        doc.setIngestionStatus(IngestionStatus.LOCAL_ONLY);
        doc.setProcessedByConverterRunId(run.getId());
        documentRegistryRepository.save(doc);

        Evidence evidence = new Evidence();
        evidence.setEntityId(UUID.randomUUID());
        evidence.setEntityType("system");
        evidence.setDomainId(domainId);
        evidence.setDocumentRegistryId(doc.getId());
        evidence.setSourceFile("glacier-systems.csv");
        evidence.setConfidence(0.92f);
        evidence.setLabel("Committed from glacier-systems.csv");
        evidence.setExtractor("ingestion");
        evidenceRepository.save(evidence);

        Assertion assertion = assertion(domainId, "[\"" + evidence.getId() + "\"]");
        return new Chain(run, doc, evidence, assertion);
    }

    private Assertion assertion(UUID domainId, String evidenceIdsJson) {
        try {
            Assertion assertion = new Assertion();
            assertion.setDomainId(domainId);
            assertion.setSubject("system:legacy-risk-store");
            assertion.setPredicate("exhibits_defect");
            assertion.setObject("manual reconciliation volume exceeds policy");
            assertion.setConfidence(0.9f);
            assertion.setEvidenceIds(mapper.readTree(evidenceIdsJson));
            assertion.setProducedBy(ProducedBy.AI_VALIDATIVE);
            assertion.setProducedByContext(mapper.readTree("{\"mode\":\"validative\"}"));
            return assertionRepository.save(assertion);
        } catch (Exception e) {
            throw new IllegalStateException("bad test JSON", e);
        }
    }

    /** Committed-AI-finding shape per spec §5.2 (metadata incl. groundingMode + candidateType). */
    private Finding aiFinding(UUID domainId, UUID assertionId, String candidateType) {
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("assertionId", assertionId.toString());
        metadata.put("candidateType", candidateType);
        metadata.put("groundingMode", "EXISTENCE_ONLY");

        Finding finding = new Finding();
        finding.setDomainId(domainId);
        finding.setFindingType("STRUCTURAL".equals(candidateType)
                ? FindingType.COVERAGE_GAP : FindingType.DATA_QUALITY);
        finding.setSeverity(Severity.MEDIUM);
        finding.setStatus(FindingStatus.OPEN);
        finding.setTitle("AI finding (" + candidateType + ")");
        finding.setDescription("walk-test committed finding");
        finding.setAiGenerated(true);
        finding.setSynthesisRunId(UUID.randomUUID());
        finding.setAssertionId(assertionId);
        finding.setCurationStatus(CurationStatus.PENDING_REVIEW);
        finding.setMetadata(metadata);
        return findingRepository.save(finding);
    }

    private void curationEvent(UUID domainId, UUID findingId, CurationAction action,
                               String rationale, String idempotencyKey) {
        AiCurationEvent event = new AiCurationEvent();
        event.setDomainId(domainId);
        event.setSubjectKind(CurationSubjectKind.FINDING);
        event.setSubjectId(findingId);
        event.setAction(action);
        event.setRationale(rationale);
        event.setActorLabel("Riley Operator");
        event.setActorPersonaId("L3_ANALYST");
        event.setIdempotencyKey(idempotencyKey);
        curationEventRepository.save(event);
    }
}
