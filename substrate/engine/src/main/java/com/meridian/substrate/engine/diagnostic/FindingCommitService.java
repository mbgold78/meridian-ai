package com.meridian.substrate.engine.diagnostic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.DiagnosticRunBatch;
import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.SynthesisRun;
import com.meridian.substrate.common.domain.enums.CurationStatus;
import com.meridian.substrate.common.domain.enums.FindingStatus;
import com.meridian.substrate.common.domain.enums.FindingType;
import com.meridian.substrate.common.domain.enums.Severity;
import com.meridian.substrate.common.repo.DiagnosticRunBatchRepository;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.repo.SynthesisRunRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import com.meridian.substrate.engine.ai.validative.FindingCandidate;
import com.meridian.substrate.engine.ai.validative.FindingCandidateSeverity;
import com.meridian.substrate.engine.ai.validative.FindingCandidateType;
import com.meridian.substrate.engine.contract.gateway.Governed;
import com.meridian.substrate.engine.diagnostic.exception.StaleBatchClaimException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The commit step — severs fixture seam S2 (engine-spec diagnostic.md §5.2;
 * ADR-004 D8). One governed transaction per batch (spec §4.3 step 3):
 * surviving findings + batch-ledger update + run-counter increments commit
 * atomically; a crash before this transaction leaves the batch RUNNING with a
 * stale claim and ZERO persisted findings — resume re-executes without
 * duplicates because the tx boundary IS the dedupe (ADR-004 D2).
 *
 * <p><b>Grounding-chain requirement (P6, exact — spec §5.2):</b> the filter
 * guaranteed the Assertion + Evidence hops at validation time; this service
 * RE-ASSERTS evidence resolution while building the enriched snapshot. A
 * CONTENT candidate whose evidence vanished between filter and commit fails
 * THAT candidate ({@code filter_histogram.ungrounded+1}), never the batch.
 * STRUCTURAL findings legitimately terminate at the Assertion and commit with
 * {@code evidence=[]} — rendered distinctly, never error-colored. No
 * duplicate Evidence rows are ever minted by this engine.
 *
 * <p>EXTENSION POINT(diagnostic-13): INFO severity rendering / findingType
 * taxonomy. The INFO→LOW mapping is documented-lossy and the differential
 * findingType defaults (STRUCTURAL→COVERAGE_GAP, CONTENT→DATA_QUALITY,
 * {@code metadata.findingTypeDefaulted=true}) shape dashboard severity
 * distributions before Mike has reviewed them — first-real-corpus review item
 * (ADR-004 follow-up #3). Pinned by test; REVISIT with Mike. Flag, don't
 * invent.
 */
@Service
public class FindingCommitService {

    private static final Logger log = LoggerFactory.getLogger(FindingCommitService.class);

    private static final int TITLE_MAX = 200;

    private final SynthesisRunRepository runRepository;
    private final DiagnosticRunBatchRepository batchRepository;
    private final FindingRepository findingRepository;
    private final SystemRepository systemRepository;
    private final ProcessRepository processRepository;
    private final GroundingVerifier groundingVerifier;
    private final ObjectMapper mapper;
    private final Clock clock;

    public FindingCommitService(SynthesisRunRepository runRepository,
                                DiagnosticRunBatchRepository batchRepository,
                                FindingRepository findingRepository,
                                SystemRepository systemRepository,
                                ProcessRepository processRepository,
                                GroundingVerifier groundingVerifier,
                                ObjectMapper mapper,
                                Clock clock) {
        this.runRepository = runRepository;
        this.batchRepository = batchRepository;
        this.findingRepository = findingRepository;
        this.systemRepository = systemRepository;
        this.processRepository = processRepository;
        this.groundingVerifier = groundingVerifier;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** Per-batch commit outcome feeding the run loop + DIAG_EVENT lines. */
    public record BatchCommitResult(int candidatesReturned, int candidatesFiltered, int findingsCommitted) {}

    /**
     * THE one commit transaction of a batch (spec §4.3 step 3; governed verb
     * {@code diagnostic.commit_findings}, SERVICE principal — ADR-004 D12; the
     * diagnostic CLI gets NO exemption, it transits this same PEP).
     *
     * <p><b>Claim fence:</b> {@code claimedAttempt} is the {@code attempt}
     * value of the claim that produced {@code generation}. The commit refuses
     * ({@link StaleBatchClaimException}) unless the batch row is still
     * RUNNING at exactly that attempt — a slow-but-alive executor whose claim
     * was declared stale ({@code failStaleBatches}) and re-claimed by a
     * resume must never commit a second time (duplicate findings, double
     * token/cost accounting). The original §4.3 doctrine ("a failed batch
     * committed nothing") only covered true crashes; this fence covers the
     * stale-while-alive case.
     */
    @Governed(actionType = "diagnostic.commit_findings")
    @Transactional
    public BatchCommitResult commitBatch(UUID runId, UUID batchId, int claimedAttempt,
                                         DiagnosticExecutor.BatchGeneration generation) {
        SynthesisRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("synthesis run vanished mid-batch: " + runId));
        DiagnosticRunBatch batch = batchRepository.findById(batchId)
                .orElseThrow(() -> new IllegalStateException("batch ledger row vanished mid-batch: " + batchId));
        if (!DiagnosticRunBatch.STATUS_RUNNING.equals(batch.getStatus())
                || batch.getAttempt() != claimedAttempt) {
            throw new StaleBatchClaimException(batchId, "batch " + batchId
                    + " is no longer held by this executor's claim (row status="
                    + batch.getStatus() + ", attempt=" + batch.getAttempt()
                    + ", commit presented attempt=" + claimedAttempt
                    + ") — refusing the commit; the batch was re-claimed or"
                    + " terminally resolved elsewhere (spec §4.3/§4.4 stale-claim rule)");
        }

        Map<CandidateFilter.Outcome, Integer> histogram =
                new EnumMap<>(CandidateFilter.Outcome.class);
        generation.filterHistogram().forEach(histogram::put);

        int committed = 0;
        int contentCommitted = 0;
        int structuralCommitted = 0;
        for (FindingCandidate candidate : generation.kept()) {
            Finding finding = commitOne(run, batch, candidate, generation);
            if (finding == null) {
                // evidence vanished between filter and commit — fails THAT
                // candidate as ungrounded, never the batch (spec §5.2)
                histogram.merge(CandidateFilter.Outcome.UNGROUNDED, 1, Integer::sum);
            } else {
                committed++;
                if (candidate.type() == FindingCandidateType.CONTENT) {
                    contentCommitted++;
                } else {
                    structuralCommitted++;
                }
            }
        }
        int filtered = generation.candidatesReturned() - committed;

        // ---- batch ledger update (same tx)
        batch.setStatus(DiagnosticRunBatch.STATUS_COMPLETED);
        batch.setCandidatesReturned(generation.candidatesReturned());
        batch.setCandidatesFiltered(filtered);
        batch.setFindingsCommitted(committed);
        batch.setFilterHistogram(histogramJson(histogram));
        batch.setError(null);
        if (generation.dispatchTaskId() != null) {
            batch.setDispatchTaskId(generation.dispatchTaskId());
        }
        batch.setFinishedAt(Instant.now(clock));
        batchRepository.save(batch);

        // ---- run-counter increments (same tx)
        run.setTotalInputTokens(nullSafe(run.getTotalInputTokens()) + generation.inputTokens());
        run.setTotalOutputTokens(nullSafe(run.getTotalOutputTokens()) + generation.outputTokens());
        if (run.getModel() == null && generation.model() != null) {
            run.setModel(generation.model());
        }
        accumulateRunMetadata(run, batch, histogram, contentCommitted, structuralCommitted,
                generation);
        runRepository.save(run);

        return new BatchCommitResult(generation.candidatesReturned(), filtered, committed);
    }

    // ------------------------------------------------------------ per-candidate

    /**
     * The §5.2 exact mapping, {@code FindingCandidate → Finding}. Returns null
     * ONLY for the vanished-evidence case (CONTENT candidate that no longer
     * grounds at commit time).
     */
    private Finding commitOne(SynthesisRun run, DiagnosticRunBatch batch,
                              FindingCandidate candidate,
                              DiagnosticExecutor.BatchGeneration generation) {
        if (candidate.assertionId() == null) {
            // Filter-emitted candidates ALWAYS carry the Assertion id; a committed
            // AI finding without one is structurally impossible (service-enforced
            // NOT NULL — spec §5.2 assertion_id row).
            throw new IllegalStateException(
                    "FindingCandidate without assertionId reached the commit step — "
                            + "candidates must transit CandidateFilter (spec §7.2)");
        }

        // Re-assert evidence resolution while building the enriched snapshot
        // (EXISTENCE_ONLY — GroundingVerifier SPI, spec §6.2).
        Map<UUID, Evidence> resolved =
                groundingVerifier.resolveInDomain(candidate.evidenceIds(), run.getDomainId());
        if (candidate.type() == FindingCandidateType.CONTENT && resolved.isEmpty()) {
            log.warn("CONTENT candidate lost all grounding between filter and commit "
                            + "[run={}, batch={}, assertion={}] — failing the candidate, not the batch",
                    run.getId(), batch.getBatchNo(), candidate.assertionId());
            return null;
        }

        Finding finding = new Finding();
        finding.setDomainId(run.getDomainId());
        finding.setAiGenerated(true);
        finding.setSynthesisRunId(run.getId());
        finding.setAssertionId(candidate.assertionId());
        finding.setCurationStatus(CurationStatus.PENDING_REVIEW);
        finding.setStatus(FindingStatus.OPEN);
        finding.setTitle(title(candidate));
        finding.setDescription(candidate.description());
        finding.setRecommendation(candidate.suggestedAction());
        finding.setSeverity(mapSeverity(candidate.severity()));

        boolean findingTypeDefaulted;
        FindingType findingType = parseFindingType(candidate.findingType());
        if (findingType == null) {
            // Differential default — an invalid LLM-supplied type NEVER
            // discards a grounded candidate (spec §5.2; ADR-004 D8).
            findingType = candidate.type() == FindingCandidateType.STRUCTURAL
                    ? FindingType.COVERAGE_GAP
                    : FindingType.DATA_QUALITY;
            findingTypeDefaulted = true;
        } else {
            findingTypeDefaulted = false;
        }
        finding.setFindingType(findingType);

        boolean unresolvedAffectedEntity =
                applyAffectedEntity(finding, candidate, run.getDomainId());

        finding.setEvidence(evidenceSnapshot(resolved.values()));
        finding.setMetadata(findingMetadata(run, batch, candidate, generation,
                findingTypeDefaulted, unresolvedAffectedEntity));
        finding.setCreatedBy("diagnostic-engine");
        return findingRepository.save(finding);
    }

    /** Severity matrix (spec §5.2): CRITICAL→CRITICAL, HIGH→HIGH, MEDIUM→MEDIUM, INFO→LOW (documented lossy). */
    static Severity mapSeverity(FindingCandidateSeverity severity) {
        return switch (severity) {
            case CRITICAL -> Severity.CRITICAL;
            case HIGH -> Severity.HIGH;
            case MEDIUM -> Severity.MEDIUM;
            case INFO -> Severity.LOW;
        };
    }

    /**
     * Title rule (spec §5.2): candidate title when present, else the first
     * sentence of the description; both truncated to 200 ({@code findings.title}
     * is NOT NULL, column VARCHAR(255)).
     */
    static String title(FindingCandidate candidate) {
        String title = candidate.title();
        if (title == null || title.isBlank()) {
            title = firstSentence(candidate.description());
        }
        title = title.trim();
        return title.length() <= TITLE_MAX ? title : title.substring(0, TITLE_MAX);
    }

    private static String firstSentence(String description) {
        int dot = description.indexOf('.');
        return dot > 0 ? description.substring(0, dot + 1) : description;
    }

    /** Raw LLM string → canonical 12-value enum; trim + case-insensitive name match, else null. */
    static FindingType parseFindingType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim();
        for (FindingType type : FindingType.values()) {
            if (type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        return null;
    }

    /**
     * {@code affectedEntityType+affectedEntityId} → {@code affected_system_ids}
     * / {@code affected_process_ids} after an in-domain existence check
     * (spec §5.2). Returns true when the candidate NAMED an affected entity
     * that could not be resolved — the finding still commits, marked
     * {@code metadata.unresolvedAffectedEntity} (no silent drop). Entity types
     * outside the two Finding columns (capability, canonical-entity, …) are
     * unresolvable by construction in v1.
     */
    private boolean applyAffectedEntity(Finding finding, FindingCandidate candidate, UUID domainId) {
        UUID entityId = candidate.affectedEntityId();
        String entityType = candidate.affectedEntityType();
        if (entityId == null) {
            return false;
        }
        String normalized = entityType == null ? "" : entityType.trim().toLowerCase();
        if (normalized.contains("system")) {
            boolean inDomain = systemRepository.findById(entityId)
                    .map(s -> domainId.equals(s.getDomainId()))
                    .orElse(false);
            if (inDomain) {
                finding.setAffectedSystemIds(uuidArray(entityId));
                return false;
            }
            return true;
        }
        if (normalized.contains("process")) {
            boolean inDomain = processRepository.findById(entityId)
                    .map(p -> domainId.equals(p.getDomainId()))
                    .orElse(false);
            if (inDomain) {
                finding.setAffectedProcessIds(uuidArray(entityId));
                return false;
            }
            return true;
        }
        return true;
    }

    /**
     * The enriched, render-ready evidence snapshot (spec §5.2 evidence row):
     * {@code [{evidenceId, label, sourceFile, documentRegistryId, confidence}]}
     * joined from the grounding-validated Evidence rows. Empty array for
     * evidence-less STRUCTURAL findings; snapshot-vs-live drift stays
     * detectable via the §6.1 {@code chainComplete} walk.
     */
    private JsonNode evidenceSnapshot(Iterable<Evidence> resolved) {
        ArrayNode snapshot = mapper.createArrayNode();
        for (Evidence evidence : resolved) {
            ObjectNode entry = snapshot.addObject();
            entry.put("evidenceId", evidence.getId().toString());
            entry.put("label", evidence.getLabel());
            entry.put("sourceFile", evidence.getSourceFile());
            entry.put("documentRegistryId", evidence.getDocumentRegistryId() == null
                    ? null : evidence.getDocumentRegistryId().toString());
            if (evidence.getConfidence() != null) {
                entry.put("confidence", evidence.getConfidence());
            } else {
                entry.putNull("confidence");
            }
        }
        return snapshot;
    }

    private JsonNode findingMetadata(SynthesisRun run, DiagnosticRunBatch batch,
                                     FindingCandidate candidate,
                                     DiagnosticExecutor.BatchGeneration generation,
                                     boolean findingTypeDefaulted,
                                     boolean unresolvedAffectedEntity) {
        ObjectNode metadata = mapper.createObjectNode();
        metadata.put("assertionId", candidate.assertionId().toString());
        metadata.put("candidateType", candidate.type().name());
        metadata.put("groundingMode", groundingVerifier.mode());
        metadata.put("batchNo", batch.getBatchNo());
        metadata.put("executor", batch.getExecutor());
        metadata.put("model", generation.model());
        if (generation.dispatchTaskId() != null) {
            metadata.put("dispatchTaskId", generation.dispatchTaskId());
        }
        if (findingTypeDefaulted) {
            metadata.put("findingTypeDefaulted", true);
        }
        if (unresolvedAffectedEntity) {
            metadata.put("unresolvedAffectedEntity", true);
        }
        return metadata;
    }

    // ------------------------------------------------------------ run metadata

    /**
     * Per-batch accumulation into run metadata (read back by finalization,
     * spec §4.5): per-reason filter-histogram totals, content/structural
     * committed counts (the §10.2 all_content_filtered / no_content_findings
     * events), refusal flag, executor mix, and — orchestrated lane only —
     * the per-batch {@code dispatchProvenance} digests
     * ({@code get_dispatch_events}, spec §8.2).
     */
    private void accumulateRunMetadata(SynthesisRun run, DiagnosticRunBatch batch,
                                       Map<CandidateFilter.Outcome, Integer> histogram,
                                       int contentCommitted, int structuralCommitted,
                                       DiagnosticExecutor.BatchGeneration generation) {
        ObjectNode metadata = run.getMetadata() != null && run.getMetadata().isObject()
                ? ((ObjectNode) run.getMetadata()).deepCopy()
                : mapper.createObjectNode();

        ObjectNode totals = metadata.withObject("filterHistogram");
        histogram.forEach((outcome, count) -> {
            String key = histogramKey(outcome);
            totals.put(key, totals.path(key).asInt(0) + count);
        });

        metadata.put("contentCommitted", metadata.path("contentCommitted").asInt(0) + contentCommitted);
        metadata.put("structuralCommitted", metadata.path("structuralCommitted").asInt(0) + structuralCommitted);
        if (generation.refusalDetected()) {
            metadata.put("refusalDetected", true);
        }
        ObjectNode executorMix = metadata.withObject("executorMix");
        String executor = batch.getExecutor() == null ? "unknown" : batch.getExecutor();
        executorMix.put(executor, executorMix.path(executor).asInt(0) + 1);

        // Orchestrated lane only (spec §4.5/§8.2): one dispatchProvenance entry
        // per dispatched batch — taskId + get_dispatch_events digest + model.
        if (generation.dispatchTaskId() != null) {
            ObjectNode entry = metadata.withArray("dispatchProvenance").addObject();
            entry.put("batchNo", batch.getBatchNo());
            entry.put("taskId", generation.dispatchTaskId());
            if (generation.dispatchEventsDigest() != null) {
                entry.put("eventsDigest", generation.dispatchEventsDigest());
            }
            if (generation.model() != null) {
                entry.put("model", generation.model());
            }
        }

        run.setMetadata(metadata);
    }

    /** Spec §3.1 filter_histogram key vocabulary: {badType, badSeverity, missingField, ungrounded}. */
    static String histogramKey(CandidateFilter.Outcome outcome) {
        return switch (outcome) {
            case BAD_TYPE -> "badType";
            case BAD_SEVERITY -> "badSeverity";
            case MISSING_FIELD -> "missingField";
            case UNGROUNDED -> "ungrounded";
            case KEPT -> throw new IllegalArgumentException("KEPT is not a histogram reason");
        };
    }

    private JsonNode histogramJson(Map<CandidateFilter.Outcome, Integer> histogram) {
        ObjectNode node = mapper.createObjectNode();
        histogram.forEach((outcome, count) -> node.put(histogramKey(outcome), count));
        return node;
    }

    private static int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    private JsonNode uuidArray(UUID id) {
        ArrayNode array = mapper.createArrayNode();
        array.add(id.toString());
        return array;
    }
}
