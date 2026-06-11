package com.meridian.substrate.engine.diagnostic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.ai.config.LlmProperties;
import com.meridian.substrate.common.domain.DiagnosticRunBatch;
import com.meridian.substrate.common.domain.SynthesisRun;
import com.meridian.substrate.common.repo.DiagnosticRunBatchRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.SynthesisRunRepository;
import com.meridian.substrate.engine.contract.gateway.Governed;
import com.meridian.substrate.engine.diagnostic.exception.DiagnosticConflictException;
import com.meridian.substrate.engine.diagnostic.read.DiagnosticRunDetail;
import com.meridian.substrate.engine.diagnostic.read.DiagnosticRunPlan;
import com.meridian.substrate.engine.diagnostic.read.DiagnosticRunSummary;
import com.meridian.substrate.engine.observability.EngineMetrics;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SynthesisRun lifecycle for {@code runType='diagnostic'} (engine-spec
 * diagnostic.md §4; ADR-004 D2/D11): start → per-batch execution → resume /
 * cancel → finalization in ONE transaction. No new run table — the existing
 * {@code synthesis_runs} row IS the run record (severs S12 for this path);
 * the status column stays free-text, NEVER enum-tightened (spec §4.1).
 *
 * <p><b>One active run per domain:</b> domain-scoped service lock + status
 * check → 409 {@code DIAGNOSTIC_RUN_ACTIVE}; the V7_1 Postgres partial-unique
 * index is the CI-verified backstop (H2 dev is service-enforced only).
 *
 * <p><b>Zero-yield trichotomy (spec §10.2, binding):</b> zero parseable
 * candidates across all ran batches → {@code FAILED reason=NO_CANDIDATES}
 * (ALWAYS a hard fail); candidates produced but committed <
 * {@code requiredMinFindings} → {@code ZERO_YIELD} ({@code ALL_FILTERED} /
 * {@code BELOW_REQUIRED}) — the §10.2 lanes take precedence over the §4.1
 * COMPLETED_WITH_ERRORS row, so a yield shortfall is never masked by partial
 * batch failures; committed=0 with required=0 → {@code COMPLETED} with a
 * MANDATORY explanation + an explicit yield event (a clean domain is
 * legitimate; silence is not).
 *
 * <p><b>Supersession-at-finalization (spec §4.5; ADR-004 D3):</b> iff the
 * terminal status is {@code COMPLETED} or {@code COMPLETED_WITH_ERRORS}, the
 * finalization transaction marks prior same-{@code (domainId, scope-set)}
 * findings still {@code PENDING_REVIEW} and not created by this run as
 * {@code SUPERSEDED} — one append-only {@code ai_curation_events} row each,
 * actor {@code system:diagnostic} ({@link CurationService}); ACCEPTED /
 * REJECTED rows are never touched; ZERO_YIELD/FAILED/CANCELLED runs
 * supersede NOTHING (a deficient run must not wipe the prior pending set).
 * A mid-run crash leaves prior PENDING rows honestly co-present with the new
 * run's committed rows until resume finalizes — visible, accepted, tested.
 * EXTENSION POINT(diagnostic-11): supersession scope normalization (exact
 * scope-set match only; overlapping scopes can accumulate pendings — ADR-004
 * follow-up #7) is deliberately NOT designed. Flag, don't invent.
 *
 * <p>Cancel is an operator outcome, never error-colored: HTTP 200 always,
 * the in-flight batch finishes its current step, terminal-state cancel is an
 * idempotent no-op (spec §4.4, non-negotiable #3).
 */
@Service
public class DiagnosticRunService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticRunService.class);

    public static final String RUN_TYPE_DIAGNOSTIC = "diagnostic";

    public static final String STATUS_PLANNED = "PLANNED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_COMPLETED_WITH_ERRORS = "COMPLETED_WITH_ERRORS";
    public static final String STATUS_ZERO_YIELD = "ZERO_YIELD";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    public static final Set<String> TERMINAL_STATUSES = Set.of(
            STATUS_COMPLETED, STATUS_COMPLETED_WITH_ERRORS, STATUS_ZERO_YIELD,
            STATUS_FAILED, STATUS_CANCELLED);

    static final Set<String> ACTIVE_STATUSES = Set.of(STATUS_PLANNED, STATUS_RUNNING);

    public static final String REASON_NO_CANDIDATES = "NO_CANDIDATES";
    public static final String REASON_ALL_FILTERED = "ALL_FILTERED";
    public static final String REASON_BELOW_REQUIRED = "BELOW_REQUIRED";
    public static final String REASON_ALL_BATCHES_FAILED = "ALL_BATCHES_FAILED";

    /**
     * Recorded per run so prompt drift is attributable run-over-run (spec
     * §13): bump when the validative prompt template changes. v2 = the §7.1
     * find_evidence + title/findingType prompt extension.
     */
    static final String PROMPT_TEMPLATE_VERSION = "validative-v2-find-evidence";

    private static final Set<String> INITIAL_CLAIMABLE = Set.of(DiagnosticRunBatch.STATUS_PENDING);
    private static final Set<String> RESUME_CLAIMABLE = Set.of(
            DiagnosticRunBatch.STATUS_PENDING, DiagnosticRunBatch.STATUS_FAILED);

    private final SynthesisRunRepository runRepository;
    private final DiagnosticRunBatchRepository batchRepository;
    private final DomainRepository domainRepository;
    private final DiagnosticRunPlanner planner;
    private final DiagnosticBatchExecutor batchExecutor;
    private final DiagnosticAttestationHook attestationHook;
    private final CurationService curationService;
    private final DiagnosticProperties properties;
    private final LlmProperties llmProperties;
    private final List<DiagnosticExecutor> executors;
    private final ThreadPoolTaskExecutor runExecutor;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final EngineMetrics metrics;

    /** Domain-scoped start lock (ADR-004 D11 graft); PG partial-unique is the DB backstop. */
    private final ConcurrentHashMap<UUID, ReentrantLock> domainLocks = new ConcurrentHashMap<>();

    /** Spring constructor (Stage 3 observability — metrics optional so partial slices wire). */
    @Autowired
    public DiagnosticRunService(SynthesisRunRepository runRepository,
                                DiagnosticRunBatchRepository batchRepository,
                                DomainRepository domainRepository,
                                DiagnosticRunPlanner planner,
                                DiagnosticBatchExecutor batchExecutor,
                                DiagnosticAttestationHook attestationHook,
                                CurationService curationService,
                                DiagnosticProperties properties,
                                LlmProperties llmProperties,
                                List<DiagnosticExecutor> executors,
                                @Qualifier("diagnosticRunExecutor") ThreadPoolTaskExecutor runExecutor,
                                PlatformTransactionManager transactionManager,
                                ObjectMapper mapper,
                                Clock clock,
                                ObjectProvider<EngineMetrics> metrics) {
        this(runRepository, batchRepository, domainRepository, planner, batchExecutor,
                attestationHook, curationService, properties, llmProperties, executors,
                runExecutor, transactionManager, mapper, clock,
                metrics.getIfAvailable(EngineMetrics::noop));
    }

    public DiagnosticRunService(SynthesisRunRepository runRepository,
                                DiagnosticRunBatchRepository batchRepository,
                                DomainRepository domainRepository,
                                DiagnosticRunPlanner planner,
                                DiagnosticBatchExecutor batchExecutor,
                                DiagnosticAttestationHook attestationHook,
                                CurationService curationService,
                                DiagnosticProperties properties,
                                LlmProperties llmProperties,
                                List<DiagnosticExecutor> executors,
                                ThreadPoolTaskExecutor runExecutor,
                                PlatformTransactionManager transactionManager,
                                ObjectMapper mapper,
                                Clock clock,
                                EngineMetrics metrics) {
        this.runRepository = runRepository;
        this.batchRepository = batchRepository;
        this.domainRepository = domainRepository;
        this.planner = planner;
        this.batchExecutor = batchExecutor;
        this.attestationHook = attestationHook;
        this.curationService = curationService;
        this.properties = properties;
        this.llmProperties = llmProperties;
        this.executors = List.copyOf(executors);
        this.runExecutor = runExecutor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.mapper = mapper;
        this.clock = clock;
        this.metrics = metrics;
    }

    // ------------------------------------------------------------------- start

    /**
     * Start command (spec §4.2/§10.1). Null optionals fall back to
     * {@code substrate.diagnostic.*} defaults. {@code sync} overrides
     * {@code substrate.diagnostic.sync} (the CLI runner forces inline
     * execution); {@code actorLabel} is the trigger actor recorded in run
     * metadata (PersonaContext label — spoofable until Phase-5 OIDC, S9).
     */
    public record StartCommand(
            UUID domainId,
            List<String> scopes,
            List<UUID> targetEntityIds,
            Integer batchSize,
            Integer requiredMinFindings,
            String executor,
            boolean dryRun,
            String idempotencyKey,
            String actorLabel,
            Boolean sync
    ) {}

    /** {@code dryRun=true} → {@code plan} only; otherwise {@code runId} (+ replayed for idempotent re-POST). */
    public record StartResult(UUID runId, boolean dryRun, DiagnosticRunPlan plan, boolean replayed) {}

    @Governed(actionType = "diagnostic.start_run")
    public StartResult startRun(StartCommand command) {
        UUID domainId = command.domainId();
        domainRepository.findById(domainId)
                .orElseThrow(() -> new EntityNotFoundException("Domain not found with id: " + domainId));

        List<String> scopes = command.scopes() != null && !command.scopes().isEmpty()
                ? List.copyOf(command.scopes()) : List.copyOf(properties.getScopes());
        int batchSize = command.batchSize() != null ? command.batchSize() : properties.getBatchSize();
        int required = command.requiredMinFindings() != null
                ? command.requiredMinFindings() : properties.getRequiredMinFindings();
        if (required < 0) {
            throw new IllegalArgumentException("requiredMinFindings must be >= 0 (was " + required + ")");
        }
        String executor = resolveExecutor(command.executor());

        List<DiagnosticRunPlanner.PlannedBatch> plan =
                planner.plan(domainId, scopes, command.targetEntityIds(), batchSize);

        if (command.dryRun()) {
            return new StartResult(null, true,
                    toPlanDto(domainId, scopes, batchSize, required, executor, plan), false);
        }
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }

        ReentrantLock lock = domainLocks.computeIfAbsent(domainId, id -> new ReentrantLock());
        lock.lock();
        UUID runId;
        try {
            // Idempotent replay: same (domain, idempotencyKey) returns the
            // original run — bookkeeping idempotency only (ADR-004 D3 note).
            SynthesisRun replayed = findByIdempotencyKey(domainId, command.idempotencyKey());
            if (replayed != null) {
                return new StartResult(replayed.getId(), false, null, true);
            }
            // One active diagnostic run per domain (spec §4.2 step 1).
            List<SynthesisRun> active = runRepository.findByDomainIdAndRunTypeAndStatusIn(
                    domainId, RUN_TYPE_DIAGNOSTIC, List.copyOf(ACTIVE_STATUSES));
            if (!active.isEmpty()) {
                throw new DiagnosticConflictException(
                        DiagnosticConflictException.Code.DIAGNOSTIC_RUN_ACTIVE, domainId,
                        "A diagnostic run is already active for domain " + domainId
                                + " (run " + active.get(0).getId() + ", status "
                                + active.get(0).getStatus() + ")");
            }
            runId = transactionTemplate.execute(tx ->
                    persistPlannedRun(domainId, scopes, batchSize, required, executor,
                            command, plan));
        } finally {
            lock.unlock();
        }

        log.info("DIAG_EVENT kind=run_started runId={} domain={} scopes={} batches={} executor={} requiredMinFindings={}",
                runId, domainId, String.join(",", scopes), plan.size(), executor, required);

        boolean sync = command.sync() != null ? command.sync() : properties.isSync();
        UUID startedRunId = runId;
        if (sync) {
            runBatches(startedRunId, INITIAL_CLAIMABLE);
        } else {
            runExecutor.execute(() -> safeRunBatches(startedRunId, INITIAL_CLAIMABLE));
        }
        return new StartResult(runId, false, null, false);
    }

    /** Persists run (PLANNED→RUNNING) + PENDING ledger rows in one tx (spec §4.2 steps 2-4). */
    private UUID persistPlannedRun(UUID domainId, List<String> scopes, int batchSize,
                                   int required, String executor, StartCommand command,
                                   List<DiagnosticRunPlanner.PlannedBatch> plan) {
        Instant now = Instant.now(clock);
        SynthesisRun run = new SynthesisRun();
        run.setDomainId(domainId);
        run.setRunType(RUN_TYPE_DIAGNOSTIC);
        run.setStatus(STATUS_PLANNED);
        run.setStartedAt(now);
        run.setModel(llmProperties.getAnthropic().getModel());
        run.setCreatedBy("diagnostic-engine");

        ObjectNode metadata = mapper.createObjectNode();
        ArrayNode scopesNode = metadata.putArray("scopes");
        scopes.forEach(scopesNode::add);
        metadata.put("requiredMinFindings", required);
        metadata.put("batchSize", batchSize);
        metadata.put("executor", executor);
        metadata.put("triggerActor", command.actorLabel() == null ? "unknown" : command.actorLabel());
        metadata.put("promptTemplateVersion", PROMPT_TEMPLATE_VERSION);
        metadata.put("idempotencyKey", command.idempotencyKey());
        // Cost-rate snapshot at run time — rates drift; audits don't (spec §4.2).
        ObjectNode rates = metadata.putObject("costRateSnapshot");
        rates.put("inputRatePerMillion", llmProperties.getCost().getInputRatePerMillion());
        rates.put("outputRatePerMillion", llmProperties.getCost().getOutputRatePerMillion());
        run.setMetadata(metadata);
        run = runRepository.save(run);

        for (DiagnosticRunPlanner.PlannedBatch planned : plan) {
            DiagnosticRunBatch batch = new DiagnosticRunBatch();
            batch.setSynthesisRunId(run.getId());
            batch.setBatchNo(planned.batchNo());
            batch.setScope(planned.scope());
            if (!planned.targetEntityIds().isEmpty()) {
                ArrayNode targets = mapper.createArrayNode();
                planned.targetEntityIds().forEach(id -> targets.add(id.toString()));
                batch.setTargetEntityIds(targets);
            }
            batch.setStatus(DiagnosticRunBatch.STATUS_PENDING);
            batch.setCreatedBy("diagnostic-engine");
            batchRepository.save(batch);
        }

        run.setStatus(STATUS_RUNNING);
        runRepository.save(run);
        return run.getId();
    }

    // ---------------------------------------------------------------- resume

    /**
     * Re-executes PENDING / FAILED / stale-RUNNING batches (spec §4.4).
     * Resuming a terminal run is an idempotent no-op 200.
     */
    @Governed(actionType = "diagnostic.resume_run")
    public DiagnosticRunDetail resume(UUID runId, Boolean sync) {
        SynthesisRun run = loadRun(runId);
        if (TERMINAL_STATUSES.contains(run.getStatus())) {
            return getRun(runId);
        }
        transactionTemplate.executeWithoutResult(tx -> {
            failStaleBatches(runId);
            SynthesisRun fresh = loadRun(runId);
            if (!STATUS_RUNNING.equals(fresh.getStatus())) {
                fresh.setStatus(STATUS_RUNNING);
                runRepository.save(fresh);
            }
        });
        boolean inline = sync != null ? sync : properties.isSync();
        if (inline) {
            runBatches(runId, RESUME_CLAIMABLE);
        } else {
            runExecutor.execute(() -> safeRunBatches(runId, RESUME_CLAIMABLE));
        }
        return getRun(runId);
    }

    /** Stale-claim rule (spec §4.3): RUNNING older than stale-batch-minutes ⇒ FAILED, re-executable. */
    private void failStaleBatches(UUID runId) {
        Instant staleBefore = Instant.now(clock)
                .minus(Duration.ofMinutes(properties.getStaleBatchMinutes()));
        for (DiagnosticRunBatch batch : batchRepository.findBySynthesisRunIdAndStatus(
                runId, DiagnosticRunBatch.STATUS_RUNNING)) {
            if (batch.getClaimedAt() != null && batch.getClaimedAt().isBefore(staleBefore)) {
                batch.setStatus(DiagnosticRunBatch.STATUS_FAILED);
                batch.setError("stale claim (claimed_at " + batch.getClaimedAt()
                        + " older than " + properties.getStaleBatchMinutes()
                        + "m) — presumed crash between generation and commit; "
                        + "committed nothing, safe to re-execute");
                batch.setFinishedAt(Instant.now(clock));
                batchRepository.save(batch);
            }
        }
    }

    // ---------------------------------------------------------------- cancel

    /**
     * Operator cancel (spec §4.4): PENDING batches → CANCELLED; the in-flight
     * batch finishes its current step; run → CANCELLED. 200, neutral, never
     * error-colored; idempotent on terminal runs.
     */
    @Governed(actionType = "diagnostic.cancel_run")
    public DiagnosticRunDetail cancel(UUID runId) {
        SynthesisRun run = loadRun(runId);
        if (TERMINAL_STATUSES.contains(run.getStatus())) {
            return getRun(runId);
        }
        transactionTemplate.executeWithoutResult(tx -> {
            Instant now = Instant.now(clock);
            for (DiagnosticRunBatch batch : batchRepository.findBySynthesisRunIdAndStatus(
                    runId, DiagnosticRunBatch.STATUS_PENDING)) {
                batch.setStatus(DiagnosticRunBatch.STATUS_CANCELLED);
                batch.setFinishedAt(now);
                batchRepository.save(batch);
            }
            SynthesisRun fresh = loadRun(runId);
            fresh.setStatus(STATUS_CANCELLED);
            runRepository.save(fresh);
        });
        log.info("DIAG_EVENT kind=run_cancelled runId={}", runId);
        // No in-flight batch ⇒ finalize now; otherwise the run loop finishes
        // the current step, observes CANCELLED, and finalizes.
        finalizeIfQuiescent(runId);
        return getRun(runId);
    }

    // ------------------------------------------------------------- run loop

    private void safeRunBatches(UUID runId, Set<String> claimable) {
        try {
            runBatches(runId, claimable);
        } catch (RuntimeException unexpected) {
            // Batch-level failures are ledgered by the executor; this guards the
            // loop itself. The run stays resumable — nothing is lost but time.
            log.error("Diagnostic run loop failed unexpectedly [runId={}] — run is resumable",
                    runId, unexpected);
        }
    }

    /** Sequential within a run — rate-limit friendly (spec §4.2 step 4). */
    private void runBatches(UUID runId, Set<String> claimable) {
        List<DiagnosticRunBatch> ledger =
                batchRepository.findBySynthesisRunIdOrderByBatchNoAsc(runId);
        int total = ledger.size();
        for (DiagnosticRunBatch batch : ledger) {
            if (!claimable.contains(batch.getStatus())) {
                continue;
            }
            SynthesisRun current = loadRun(runId);
            if (!STATUS_RUNNING.equals(current.getStatus())) {
                break; // cancelled (or externally finalized) — stop claiming
            }
            batchExecutor.execute(current, batch.getId(), claimable, total);
        }
        finalizeIfQuiescent(runId);
    }

    // --------------------------------------------------------- finalization

    /**
     * Finalization in ONE tx when no PENDING/RUNNING batches remain (spec
     * §4.5): terminal status per §4.1 + the §10.2 trichotomy, supersession
     * (COMPLETED/COMPLETED_WITH_ERRORS only — ADR-004 D3), completedAt,
     * findingsGenerated, cost from the snapshotted rates, metadata totals,
     * then {@link DiagnosticAttestationHook#onRunFinalized}. DIAG_EVENT lines
     * emit AFTER the tx commits — events report durable state only.
     */
    void finalizeIfQuiescent(UUID runId) {
        FinalizationReport report = transactionTemplate.execute(tx -> finalizeInTx(runId));
        if (report == null) {
            return;
        }
        for (String line : report.eventLines()) {
            log.info(line);
        }
        // Stage 3 observability (plan §8): recorded AFTER the finalization tx
        // commits — like the DIAG_EVENT lines, metrics report durable state
        // only, and finalizeInTx's already-finalized null short-circuit makes
        // this exactly-once per run.
        metrics.diagnosticRun(report.status());
        metrics.diagnosticFindingsCommitted(report.committed());
        metrics.diagnosticCandidatesFiltered(report.filtered());
    }

    private record FinalizationReport(List<String> eventLines, String status,
                                      int committed, int filtered) {}

    private FinalizationReport finalizeInTx(UUID runId) {
        SynthesisRun run = loadRun(runId);
        if (run.getCompletedAt() != null) {
            return null; // already finalized
        }
        List<DiagnosticRunBatch> ledger =
                batchRepository.findBySynthesisRunIdOrderByBatchNoAsc(runId);
        boolean cancelled = STATUS_CANCELLED.equals(run.getStatus());
        long running = countByStatus(ledger, DiagnosticRunBatch.STATUS_RUNNING);
        long pending = countByStatus(ledger, DiagnosticRunBatch.STATUS_PENDING);
        if (running > 0 || (pending > 0 && !cancelled)) {
            return null; // not quiescent yet
        }

        long completed = countByStatus(ledger, DiagnosticRunBatch.STATUS_COMPLETED);
        long failed = countByStatus(ledger, DiagnosticRunBatch.STATUS_FAILED);
        int returned = sum(ledger, DiagnosticRunBatch::getCandidatesReturned);
        int filtered = sum(ledger, DiagnosticRunBatch::getCandidatesFiltered);
        int committed = sum(ledger, DiagnosticRunBatch::getFindingsCommitted);

        ObjectNode metadata = run.getMetadata() != null && run.getMetadata().isObject()
                ? ((ObjectNode) run.getMetadata()).deepCopy()
                : mapper.createObjectNode();
        int required = metadata.path("requiredMinFindings").asInt(0);
        int contentCommitted = metadata.path("contentCommitted").asInt(0);
        int ungrounded = metadata.path("filterHistogram").path("ungrounded").asInt(0);

        String status;
        String reason = null;
        String explanation = null;
        List<String> events = new ArrayList<>();

        if (cancelled) {
            status = STATUS_CANCELLED; // a cancelled run supersedes nothing and yields no verdict
        } else if (completed == 0 && failed > 0) {
            status = STATUS_FAILED;
            reason = REASON_ALL_BATCHES_FAILED;
            explanation = "Every batch failed after max-attempts; no batch produced candidates. "
                    + "Resume re-executes the failed batches.";
        } else if (completed > 0 && returned == 0) {
            // §10.2 lane 1 — ALWAYS a hard fail, distinct from all-filtered.
            status = STATUS_FAILED;
            reason = REASON_NO_CANDIDATES;
            explanation = "Zero parseable candidates across all batches — the model returned "
                    + "no structured findings anywhere. \"Command exited 0\" is never "
                    + "\"product worked\".";
        } else if (committed < required) {
            // §10.2 lanes 2+3 — take precedence over COMPLETED_WITH_ERRORS so a
            // yield shortfall is never silently absorbed by partial failures.
            status = STATUS_ZERO_YIELD;
            reason = committed == 0 ? REASON_ALL_FILTERED : REASON_BELOW_REQUIRED;
            explanation = committed == 0
                    ? returned + " candidate(s) produced, ALL filtered (allowlists, required "
                            + "fields, or grounding) with requiredMinFindings=" + required + "."
                    : "Committed " + committed + " finding(s), below requiredMinFindings="
                            + required + ".";
            events.add("DIAG_EVENT kind=zero_yield runId=" + runId + " reason=" + reason
                    + " required=" + required + " committed=" + committed);
        } else {
            status = failed > 0 ? STATUS_COMPLETED_WITH_ERRORS : STATUS_COMPLETED;
            if (committed == 0) {
                // requiredMinFindings=0 and nothing committed: legitimate clean
                // outcome, NEVER silent — mandatory explanation + yield event.
                explanation = returned > 0
                        ? returned + " candidate(s) produced and all filtered "
                                + "(per-batch filtered counts in the ledger); "
                                + "requiredMinFindings=0, so this run is COMPLETED."
                        : "No batch ran to candidate production (budget-skipped); "
                                + "requiredMinFindings=0, so this run is COMPLETED.";
                events.add("DIAG_EVENT kind=yield runId=" + runId
                        + " reason=ALL_FILTERED yield=0");
            }
        }

        if (!cancelled && ungrounded > 0 && contentCommitted == 0) {
            // The D6 grounding-gap alarm: CONTENT lane produced, all filtered.
            events.add("DIAG_EVENT kind=all_content_filtered runId=" + runId
                    + " ungroundedFiltered=" + ungrounded);
            metadata.put("allContentFiltered", true);
        }
        if (!cancelled && committed > 0 && contentCommitted == 0) {
            // EXTENSION POINT(diagnostic-16): STRUCTURAL-only yield passes the
            // gate but is loud — Mike ruling pending (ADR-004 follow-up #6).
            events.add("DIAG_EVENT kind=no_content_findings runId=" + runId
                    + " committed=" + committed);
            metadata.put("structuralOnlyYield", true);
        }

        // Supersession executes HERE, inside the finalization tx (spec §4.5;
        // ADR-004 D3): only a run that actually delivered may retire the prior
        // pending set; ZERO_YIELD/FAILED/CANCELLED supersede NOTHING.
        List<UUID> superseded = List.of();
        if (STATUS_COMPLETED.equals(status) || STATUS_COMPLETED_WITH_ERRORS.equals(status)) {
            superseded = curationService.supersedePriorPendingFindings(
                    run, CurationService.scopeSetOf(metadata));
        }
        ArrayNode supersededNode = metadata.putArray("supersededFindingIds");
        superseded.forEach(id -> supersededNode.add(id.toString()));

        Instant now = Instant.now(clock);
        int tokensIn = run.getTotalInputTokens() == null ? 0 : run.getTotalInputTokens();
        int tokensOut = run.getTotalOutputTokens() == null ? 0 : run.getTotalOutputTokens();
        double costEstimate = cost(metadata, tokensIn, tokensOut);

        run.setStatus(status);
        run.setCompletedAt(now);
        run.setFindingsGenerated(committed);
        run.setTotalCostEstimate(costEstimate);
        if (reason != null) {
            metadata.put("reason", reason);
        }
        if (explanation != null) {
            metadata.put("explanation", explanation);
        }
        metadata.put("candidatesReturned", returned);
        metadata.put("candidatesFiltered", filtered);
        ArrayNode eventsNode = metadata.withArray("events");
        events.forEach(line -> eventsNode.add(kindOf(line)));
        run.setMetadata(metadata);
        runRepository.save(run);

        attestationHook.onRunFinalized(run);

        List<String> lines = new ArrayList<>();
        lines.add("DIAG_EVENT kind=run_finalized runId=" + runId + " status=" + status
                + (reason != null ? " reason=" + reason : "")
                + " committed=" + committed + " filtered=" + filtered
                + " superseded=" + superseded.size()
                + " tokensIn=" + tokensIn + " tokensOut=" + tokensOut
                + " costEstimate=" + costEstimate);
        lines.addAll(events);
        return new FinalizationReport(lines, status, committed, filtered);
    }

    private double cost(ObjectNode metadata, int tokensIn, int tokensOut) {
        double inputRate = metadata.path("costRateSnapshot").path("inputRatePerMillion").asDouble(0.0);
        double outputRate = metadata.path("costRateSnapshot").path("outputRatePerMillion").asDouble(0.0);
        return (tokensIn / 1_000_000.0) * inputRate + (tokensOut / 1_000_000.0) * outputRate;
    }

    private static String kindOf(String eventLine) {
        int start = eventLine.indexOf("kind=") + 5;
        int end = eventLine.indexOf(' ', start);
        return end > start ? eventLine.substring(start, end) : eventLine.substring(start);
    }

    // ------------------------------------------------------------ read model

    @Transactional(readOnly = true)
    public DiagnosticRunDetail getRun(UUID runId) {
        SynthesisRun run = loadRun(runId);
        List<DiagnosticRunBatch> ledger =
                batchRepository.findBySynthesisRunIdOrderByBatchNoAsc(runId);
        JsonNode metadata = run.getMetadata() == null
                ? mapper.createObjectNode() : run.getMetadata();

        Map<String, Long> batchCounts = new LinkedHashMap<>();
        for (DiagnosticRunBatch batch : ledger) {
            batchCounts.merge(batch.getStatus(), 1L, Long::sum);
        }
        List<DiagnosticRunDetail.BatchView> batchViews = ledger.stream()
                .map(this::toBatchView)
                .toList();

        return new DiagnosticRunDetail(
                run.getId(),
                run.getDomainId(),
                run.getStatus(),
                textOrNull(metadata, "reason"),
                textOrNull(metadata, "explanation"),
                run.getStartedAt(),
                run.getCompletedAt(),
                run.getModel(),
                textOrNull(metadata, "executor"),
                metadata.path("requiredMinFindings").asInt(0),
                sum(ledger, DiagnosticRunBatch::getCandidatesReturned),
                sum(ledger, DiagnosticRunBatch::getCandidatesFiltered),
                sum(ledger, DiagnosticRunBatch::getFindingsCommitted),
                run.getTotalInputTokens(),
                run.getTotalOutputTokens(),
                run.getTotalCostEstimate(),
                metadata.hasNonNull("refusalDetected")
                        ? metadata.get("refusalDetected").asBoolean() : null,
                intMap(metadata.path("filterHistogram")),
                intMap(metadata.path("executorMix")),
                batchCounts,
                batchViews,
                stringList(metadata.path("events")),
                // Orchestrated lane only (get_dispatch_events digests, build
                // step 8) — null for in-process runs, absent-vs-zero honest.
                metadata.hasNonNull("dispatchProvenance")
                        ? metadata.get("dispatchProvenance") : null);
    }

    @Transactional(readOnly = true)
    public List<DiagnosticRunSummary> listRuns(UUID domainId) {
        return runRepository
                .findByDomainIdAndRunTypeOrderByCreatedAtDesc(domainId, RUN_TYPE_DIAGNOSTIC)
                .stream()
                .map(run -> new DiagnosticRunSummary(
                        run.getId(), run.getDomainId(), run.getStatus(),
                        run.getStartedAt(), run.getCompletedAt(),
                        run.getFindingsGenerated(),
                        batchRepository.findBySynthesisRunIdOrderByBatchNoAsc(run.getId()).size()))
                .toList();
    }

    private DiagnosticRunDetail.BatchView toBatchView(DiagnosticRunBatch batch) {
        int targetCount = batch.getTargetEntityIds() != null && batch.getTargetEntityIds().isArray()
                ? batch.getTargetEntityIds().size() : 0;
        return new DiagnosticRunDetail.BatchView(
                batch.getId(), batch.getBatchNo(), batch.getScope(), batch.getStatus(),
                batch.getAttempt(), batch.getExecutor(), batch.getCandidatesReturned(),
                batch.getCandidatesFiltered(), batch.getFindingsCommitted(),
                batch.getFilterHistogram(), batch.getError(), batch.getDispatchTaskId(),
                targetCount, batch.getClaimedAt(), batch.getStartedAt(), batch.getFinishedAt());
    }

    // ------------------------------------------------------------- helpers

    private SynthesisRun loadRun(UUID runId) {
        SynthesisRun run = runRepository.findById(runId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Diagnostic run not found with id: " + runId));
        if (!RUN_TYPE_DIAGNOSTIC.equals(run.getRunType())) {
            throw new EntityNotFoundException("Run " + runId + " is not a diagnostic run");
        }
        return run;
    }

    private SynthesisRun findByIdempotencyKey(UUID domainId, String idempotencyKey) {
        return runRepository.findByDomainIdAndRunTypeOrderByCreatedAtDesc(domainId, RUN_TYPE_DIAGNOSTIC)
                .stream()
                .filter(run -> run.getMetadata() != null
                        && idempotencyKey.equals(textOrNull(run.getMetadata(), "idempotencyKey")))
                .findFirst()
                .orElse(null);
    }

    /**
     * Lane resolution (spec §2/§8.2): {@code auto} and an explicit
     * {@code orchestrator} request resolve to the orchestrated lane WHEN the
     * step-8 {@code OrchestratorDiagnosticExecutor} bean exists
     * ({@code substrate.orchestrator.enabled=true} AND the
     * {@code substrate.diagnostic.executor} flag is not {@code in-process});
     * otherwise both degrade to in-process — whole-or-in-part: degradation is
     * exactly one missing bean, never a parallel code path. The resolved lane
     * is recorded in run metadata and selected per batch by name.
     */
    private String resolveExecutor(String requested) {
        String effective = requested != null && !requested.isBlank()
                ? requested : properties.getExecutor();
        return switch (effective) {
            case DiagnosticProperties.EXECUTOR_IN_PROCESS -> DiagnosticProperties.EXECUTOR_IN_PROCESS;
            case DiagnosticProperties.EXECUTOR_AUTO,
                 DiagnosticProperties.EXECUTOR_ORCHESTRATOR -> orchestratorLanePresent()
                    ? DiagnosticProperties.EXECUTOR_ORCHESTRATOR
                    : DiagnosticProperties.EXECUTOR_IN_PROCESS;
            default -> throw new IllegalArgumentException("Unknown diagnostic executor '"
                    + effective + "' — allowed: auto, in-process, orchestrator");
        };
    }

    private boolean orchestratorLanePresent() {
        return executors.stream()
                .anyMatch(e -> DiagnosticExecutor.EXECUTOR_ORCHESTRATOR.equals(e.name()));
    }

    private DiagnosticRunPlan toPlanDto(UUID domainId, List<String> scopes, int batchSize,
                                        int required, String executor,
                                        List<DiagnosticRunPlanner.PlannedBatch> plan) {
        return new DiagnosticRunPlan(domainId, scopes, batchSize, required, executor, plan.size(),
                plan.stream().map(p -> new DiagnosticRunPlan.PlannedBatchView(
                        p.batchNo(), p.scope(), p.targetEntityIds().size(), p.targetEntityIds()))
                        .toList());
    }

    private static long countByStatus(List<DiagnosticRunBatch> ledger, String status) {
        return ledger.stream().filter(b -> status.equals(b.getStatus())).count();
    }

    private static int sum(List<DiagnosticRunBatch> ledger,
                           java.util.function.Function<DiagnosticRunBatch, Integer> field) {
        return ledger.stream().map(field).filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue).sum();
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static Map<String, Integer> intMap(JsonNode node) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asInt()));
        }
        return map;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(entry -> list.add(entry.asText()));
        }
        return list;
    }
}
