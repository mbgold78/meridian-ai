package com.meridian.substrate.engine.diagnostic;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.Assertion;
import com.meridian.substrate.common.domain.DocumentRegistry;
import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.IngestRun;
import com.meridian.substrate.common.domain.ReaderRegistration;
import com.meridian.substrate.common.domain.enums.CurationSubjectKind;
import com.meridian.substrate.common.repo.AiCurationEventRepository;
import com.meridian.substrate.common.repo.AssertionRepository;
import com.meridian.substrate.common.repo.DocumentRegistryRepository;
import com.meridian.substrate.common.repo.EvidenceRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.repo.IngestRunRepository;
import com.meridian.substrate.common.repo.ReaderRegistrationRepository;
import com.meridian.substrate.engine.diagnostic.read.DiagnosticVerifyReport;
import com.meridian.substrate.engine.diagnostic.read.FindingProvenance;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The audit-defensibility read model, shipped FIRST — before any generation
 * exists (engine-spec diagnostic.md §6.1; ADR-004 D5; read-model-first
 * build-order step 2).
 *
 * <p>{@link #walk(UUID)} materializes the full P6 chain for one finding:
 * {@code Finding.assertion_id → Assertion → Assertion.evidenceIds → Evidence
 * (in-domain) → Evidence.documentRegistryId → DocumentRegistry
 * (sha256, processedByConverterRunId) → ingest_runs}, plus the reader
 * registration when a registry row traces to one, and the append-only
 * curation-event history in order. ANY dangling hop sets
 * {@code chainComplete=false} with the missing hop NAMED — surfaced, never
 * elided. STRUCTURAL findings legitimately terminate at the Assertion
 * (self-justifying model-state observations per the pinned phase2a contract,
 * spec §5.2/§6.2): an assertion citing no evidence is a COMPLETE chain when
 * {@code metadata.candidateType=STRUCTURAL}, and is a named missing hop
 * otherwise.
 *
 * <p>Operator/fixture findings (no {@code assertion_id}) carry no AI
 * grounding chain — the walk says so by name, and still surfaces the
 * entity-linked provenance that exists (Evidence attached to the finding or
 * its affected entities, e.g. the DemoPack {@code extractor='seed'} pool —
 * port-map §5 S3), labeled {@code evidenceSource=ENTITY_LINKED} so it is
 * never conflated with an assertion-grounded chain.
 *
 * <p>S3 honesty posture (spec §6.2): {@code groundingMode} is persisted data
 * surfaced verbatim from finding metadata — {@code EXISTENCE_ONLY} for all
 * v1 AI findings. Evidence citations are existence-verified; semantic support
 * is human-verified at acceptance — this walk renders the chain the curation
 * UI must show inline, and never claims "machine-verified grounding".
 *
 * <p>EXTENSION POINT(diagnostic-1): semantic grounding verification. v1
 * surfaces existence-only grounding honestly; a future SEMANTIC verifier
 * (LLM-judge / embedding score, via the GroundingVerifier SPI) is
 * advisory-only by contract — it may lower confidence, never auto-pass (P7:
 * an LLM opinion about grounding is itself an AI claim). Flag, don't invent.
 *
 * <p>EXTENSION POINT(diagnostic-2): passage-level evidence. Evidence rows are
 * document-level ("Committed from systems.csv"), so human semantic
 * verification is coarse — a stated limit in any compliance conversation.
 * Passage-level provenance rides the existing {@code EvidenceChunk} retriever
 * seam (port-map §2.3). Flag, don't invent.
 */
@Service
public class ProvenanceWalkService {

    /** groundingMode bucket for AI findings persisted without one (pre-V7 rows) — never silently dropped. */
    static final String GROUNDING_MODE_UNSPECIFIED = "UNSPECIFIED";

    private static final String CANDIDATE_TYPE_STRUCTURAL = "STRUCTURAL";

    private final FindingRepository findingRepository;
    private final AssertionRepository assertionRepository;
    private final EvidenceRepository evidenceRepository;
    private final DocumentRegistryRepository documentRegistryRepository;
    private final IngestRunRepository ingestRunRepository;
    private final ReaderRegistrationRepository readerRegistrationRepository;
    private final AiCurationEventRepository curationEventRepository;

    public ProvenanceWalkService(FindingRepository findingRepository,
                                 AssertionRepository assertionRepository,
                                 EvidenceRepository evidenceRepository,
                                 DocumentRegistryRepository documentRegistryRepository,
                                 IngestRunRepository ingestRunRepository,
                                 ReaderRegistrationRepository readerRegistrationRepository,
                                 AiCurationEventRepository curationEventRepository) {
        this.findingRepository = findingRepository;
        this.assertionRepository = assertionRepository;
        this.evidenceRepository = evidenceRepository;
        this.documentRegistryRepository = documentRegistryRepository;
        this.ingestRunRepository = ingestRunRepository;
        this.readerRegistrationRepository = readerRegistrationRepository;
        this.curationEventRepository = curationEventRepository;
    }

    /** Full provenance walk for one finding; 404 (via the app envelope) when the finding is unknown. */
    @Transactional(readOnly = true)
    public FindingProvenance walk(UUID findingId) {
        Finding finding = findingRepository.findById(findingId)
                .orElseThrow(() -> new EntityNotFoundException("Finding not found with id: " + findingId));
        return walk(finding);
    }

    /**
     * Chain-integrity sweep over every {@code aiGenerated=true} finding of the
     * domain (the {@code /diagnostics/verify} read model). Computed per
     * request — human-paced curation volume; REVISIT with run/batch retention
     * (EXTENSION POINT(diagnostic-14) lives on the batch ledger).
     */
    @Transactional(readOnly = true)
    public DiagnosticVerifyReport verify(UUID domainId) {
        List<Finding> population = findingRepository.findByDomainIdAndAiGeneratedTrue(domainId);

        int complete = 0;
        List<DiagnosticVerifyReport.BrokenChain> broken = new ArrayList<>();
        List<UUID> fixtureGrounded = new ArrayList<>();
        Map<String, Integer> groundingModes = new TreeMap<>();

        for (Finding finding : population) {
            FindingProvenance walked = walk(finding);
            if (walked.chainComplete()) {
                complete++;
            } else {
                broken.add(new DiagnosticVerifyReport.BrokenChain(
                        finding.getId(), walked.missingHops().get(0)));
            }
            if (walked.fixtureGrounded()) {
                fixtureGrounded.add(finding.getId());
            }
            String mode = walked.groundingMode() != null
                    ? walked.groundingMode() : GROUNDING_MODE_UNSPECIFIED;
            groundingModes.merge(mode, 1, Integer::sum);
        }

        return new DiagnosticVerifyReport(domainId, population.size(), complete,
                List.copyOf(broken), List.copyOf(fixtureGrounded), Map.copyOf(groundingModes));
    }

    // ------------------------------------------------------------------ walk

    private FindingProvenance walk(Finding finding) {
        List<String> missingHops = new ArrayList<>();
        String groundingMode = metadataText(finding, "groundingMode");
        String candidateType = metadataText(finding, "candidateType");

        FindingProvenance.AssertionView assertionView = null;
        List<Evidence> evidenceRows = new ArrayList<>();
        String evidenceSource;

        if (finding.getAssertionId() != null) {
            evidenceSource = FindingProvenance.SOURCE_ASSERTION;
            Optional<Assertion> assertion = assertionRepository.findById(finding.getAssertionId());
            if (assertion.isEmpty()) {
                missingHops.add("assertion: assertions row " + finding.getAssertionId()
                        + " not found (dangling findings.assertion_id)");
            } else {
                assertionView = FindingProvenance.AssertionView.of(assertion.get());
                walkAssertionEvidence(assertion.get(), finding.getDomainId(), candidateType,
                        evidenceRows, missingHops);
            }
        } else {
            // No assertion hop. Committed AI findings carry one (service-enforced
            // NOT NULL — spec §5.2); operator/fixture findings have no AI
            // grounding chain, surfaced by name, never elided.
            if (Boolean.TRUE.equals(finding.getAiGenerated())) {
                missingHops.add("assertion: AI finding has no assertion_id (grounding chain absent)");
            } else {
                missingHops.add("assertion: operator/fixture finding has no assertion_id"
                        + " (not subject to AI grounding)");
            }
            evidenceRows.addAll(entityLinkedEvidence(finding));
            evidenceSource = evidenceRows.isEmpty()
                    ? FindingProvenance.SOURCE_NONE
                    : FindingProvenance.SOURCE_ENTITY_LINKED;
        }

        // documentRegistry + ingestRun + readerRegistration hops over the walked evidence
        Map<UUID, DocumentRegistry> documents = new LinkedHashMap<>();
        for (Evidence evidence : evidenceRows) {
            if (evidence.getDocumentRegistryId() == null) {
                missingHops.add("documentRegistry: evidence " + evidence.getId()
                        + " has no documentRegistryId");
                continue;
            }
            if (documents.containsKey(evidence.getDocumentRegistryId())) {
                continue;
            }
            documentRegistryRepository.findById(evidence.getDocumentRegistryId()).ifPresentOrElse(
                    doc -> documents.put(doc.getId(), doc),
                    () -> missingHops.add("documentRegistry: document_registry row "
                            + evidence.getDocumentRegistryId()
                            + " not found (dangling evidence.document_registry_id)"));
        }

        Map<UUID, IngestRun> ingestRuns = new LinkedHashMap<>();
        List<ReaderRegistration> readers = new ArrayList<>();
        for (DocumentRegistry doc : documents.values()) {
            if (doc.getProcessedByConverterRunId() == null) {
                missingHops.add("ingestRun: documentRegistry " + doc.getId()
                        + " (" + doc.getSourceFile() + ") has no processedByConverterRunId"
                        + " (no ingest-run provenance)");
            } else if (!ingestRuns.containsKey(doc.getProcessedByConverterRunId())) {
                ingestRunRepository.findById(doc.getProcessedByConverterRunId()).ifPresentOrElse(
                        run -> ingestRuns.put(run.getId(), run),
                        () -> missingHops.add("ingestRun: ingest_runs row "
                                + doc.getProcessedByConverterRunId()
                                + " not found (dangling document_registry.processed_by_converter_run_id)"));
            }
            // Reader hop is conditional by spec ("when a registry row traces to
            // one") — traced via metadata.readerRegistrationId when a Reader
            // stamped it; absence is NOT a dangling hop (the converter ETL is
            // retrofitted as a Structured reader in Phase 5).
            readerFor(doc).ifPresent(readers::add);
        }

        List<FindingProvenance.CurationEventView> curationEvents = curationEventRepository
                .findBySubjectKindAndSubjectIdOrderByCreatedAtAsc(CurationSubjectKind.FINDING, finding.getId())
                .stream().map(FindingProvenance.CurationEventView::of).toList();

        boolean fixtureGrounded = evidenceRows.stream()
                .anyMatch(ev -> "seed".equals(ev.getExtractor()));

        return new FindingProvenance(
                FindingProvenance.FindingView.of(finding),
                assertionView,
                evidenceRows.stream().map(FindingProvenance.EvidenceView::of).toList(),
                documents.values().stream().map(FindingProvenance.DocumentRegistryView::of).toList(),
                ingestRuns.values().stream().map(FindingProvenance.IngestRunView::of).toList(),
                readers.stream().map(FindingProvenance.ReaderRegistrationView::of).toList(),
                curationEvents,
                missingHops.isEmpty(),
                List.copyOf(missingHops),
                groundingMode,
                fixtureGrounded,
                evidenceSource);
    }

    /**
     * The assertion→evidence hop. CONTENT findings require ≥1 resolving
     * IN-DOMAIN Evidence row (spec §5.2); STRUCTURAL findings legitimately
     * terminate at the Assertion with no citation — a complete chain, never
     * error-colored. Malformed citation entries are named, never silently
     * skipped.
     */
    private void walkAssertionEvidence(Assertion assertion, UUID domainId, String candidateType,
                                       List<Evidence> evidenceRows, List<String> missingHops) {
        JsonNode cited = assertion.getEvidenceIds();
        if (cited == null || !cited.isArray() || cited.isEmpty()) {
            if (!CANDIDATE_TYPE_STRUCTURAL.equals(candidateType)) {
                missingHops.add("evidence: assertion " + assertion.getId()
                        + " cites no evidence ids (CONTENT findings require >=1; STRUCTURAL"
                        + " terminate at the assertion — spec §5.2/§6.2)");
            }
            return;
        }
        for (JsonNode entry : cited) {
            UUID evidenceId;
            try {
                evidenceId = UUID.fromString(entry.asText());
            } catch (IllegalArgumentException notAUuid) {
                missingHops.add("evidence: assertion " + assertion.getId()
                        + " cites malformed evidence id '" + entry.asText() + "'");
                continue;
            }
            Optional<Evidence> evidence = evidenceRepository.findById(evidenceId);
            if (evidence.isEmpty()) {
                missingHops.add("evidence: evidence row " + evidenceId
                        + " not found (dangling assertion.evidence_ids entry)");
            } else if (domainId != null && evidence.get().getDomainId() != null
                    && !domainId.equals(evidence.get().getDomainId())) {
                missingHops.add("evidence: evidence row " + evidenceId
                        + " is out-of-domain (P6 chain requires in-domain evidence — spec §5.2)");
                evidenceRows.add(evidence.get()); // surfaced, never elided
            } else {
                evidenceRows.add(evidence.get());
            }
        }
    }

    /**
     * Fallback provenance for assertion-less findings: Evidence attached to
     * the finding itself plus its affected systems/processes — the DemoPack
     * seed pool surfaces here (fixture chains visible and labeled, spec §6.1).
     */
    private List<Evidence> entityLinkedEvidence(Finding finding) {
        List<Evidence> rows = new ArrayList<>(evidenceRepository.findByEntityId(finding.getId()));
        for (UUID systemId : uuidList(finding.getAffectedSystemIds())) {
            rows.addAll(evidenceRepository.findByEntityId(systemId));
        }
        for (UUID processId : uuidList(finding.getAffectedProcessIds())) {
            rows.addAll(evidenceRepository.findByEntityId(processId));
        }
        return rows;
    }

    private Optional<ReaderRegistration> readerFor(DocumentRegistry doc) {
        JsonNode metadata = doc.getMetadata();
        if (metadata == null || !metadata.hasNonNull("readerRegistrationId")) {
            return Optional.empty();
        }
        try {
            return readerRegistrationRepository.findById(
                    UUID.fromString(metadata.get("readerRegistrationId").asText()));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    private static String metadataText(Finding finding, String field) {
        JsonNode metadata = finding.getMetadata();
        return metadata != null && metadata.hasNonNull(field) ? metadata.get(field).asText() : null;
    }

    private static List<UUID> uuidList(JsonNode array) {
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>(array.size());
        for (JsonNode node : array) {
            try {
                ids.add(UUID.fromString(node.asText()));
            } catch (IllegalArgumentException notAUuid) {
                // affected-entity id arrays only (assertion citations are walked
                // raw, with malformed entries NAMED in missingHops): a non-UUID
                // entry here can never resolve to entity-linked Evidence
            }
        }
        return ids;
    }
}
