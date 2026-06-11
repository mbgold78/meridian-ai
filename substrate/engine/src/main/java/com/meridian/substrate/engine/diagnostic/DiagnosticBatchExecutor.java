package com.meridian.substrate.engine.diagnostic;

import com.meridian.substrate.common.ai.LlmRateLimitException;
import com.meridian.substrate.common.domain.DiagnosticRunBatch;
import com.meridian.substrate.common.domain.SynthesisRun;
import com.meridian.substrate.common.repo.DiagnosticRunBatchRepository;
import com.meridian.substrate.engine.diagnostic.exception.DispatchFallbackException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Per-batch execution (engine-spec diagnostic.md §4.3; ADR-004 D2):
 * <ol>
 *   <li><b>Claim</b> — batch → RUNNING, {@code claimed_at=now},
 *       {@code attempt+1} (own short tx, programmatic — bookkeeping, not a
 *       PEP);</li>
 *   <li><b>Generate OUTSIDE any tx</b> — the selected
 *       {@link DiagnosticExecutor}; {@code LlmRateLimitException} → bounded
 *       exponential backoff up to {@code max-attempts}, then batch FAILED
 *       (run continues);</li>
 *   <li><b>Commit in ONE governed tx</b> —
 *       {@link FindingCommitService#commitBatch} (findings + ledger + run
 *       counters). A crash between 2 and 3 leaves RUNNING with a stale
 *       {@code claimed_at}; resume treats stale RUNNING as FAILED and
 *       re-executes — duplicates are impossible because a failed batch
 *       committed nothing. No cross-LLM-run semantic dedupe is attempted; the
 *       tx boundary is the dedupe.</li>
 * </ol>
 *
 * <p>Emits one {@code DIAG_EVENT} line per batch outcome (spec §10.3 — the
 * lines surface in the CLI run AND the async REST run alike).
 */
@Service
public class DiagnosticBatchExecutor {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticBatchExecutor.class);

    private static final long BACKOFF_BASE_MS = 500;
    private static final long BACKOFF_CAP_MS = 8_000;

    private final DiagnosticRunBatchRepository batchRepository;
    private final FindingCommitService commitService;
    private final List<DiagnosticExecutor> executors;
    private final DiagnosticProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public DiagnosticBatchExecutor(DiagnosticRunBatchRepository batchRepository,
                                   FindingCommitService commitService,
                                   List<DiagnosticExecutor> executors,
                                   DiagnosticProperties properties,
                                   PlatformTransactionManager transactionManager,
                                   Clock clock) {
        this.batchRepository = batchRepository;
        this.commitService = commitService;
        this.executors = List.copyOf(executors);
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Outcome marker for the run loop. */
    public enum BatchOutcome { COMPLETED, FAILED, SKIPPED, SKIPPED_BUDGET }

    /**
     * Executes one batch end-to-end. {@code claimableStatuses} is
     * {@code {PENDING}} on first execution and {@code {PENDING, FAILED}} on
     * resume (stale RUNNING rows are flipped to FAILED by the run service
     * BEFORE resuming — spec §4.4).
     */
    public BatchOutcome execute(SynthesisRun run, UUID batchId, Set<String> claimableStatuses,
                                int totalBatches) {
        DiagnosticExecutor executor = selectExecutor(run);
        DiagnosticRunBatch claimed = claim(batchId, claimableStatuses, executor.name());
        if (claimed == null) {
            return BatchOutcome.SKIPPED;
        }

        DiagnosticExecutor.BatchGeneration generation = null;
        while (true) {
            try {
                // Step 2 — generation, deliberately OUTSIDE any transaction.
                generation = executor.executeBatch(run, claimed);
                break;
            } catch (LlmRateLimitException rateLimited) {
                int attempt = claimed.getAttempt();
                if (attempt >= properties.getMaxAttempts()) {
                    return fail(run, claimed, totalBatches, "rate-limited after "
                            + attempt + " attempt(s): " + rateLimited.getMessage());
                }
                backoff(attempt);
                claimed = reclaim(batchId);
            } catch (DispatchFallbackException fallback) {
                // §8.2 fallback semantics (ADR-004 D11 graft): budget refusal /
                // dispatch failure → per substrate.diagnostic.dispatch.fallback.
                if (DiagnosticProperties.Dispatch.FALLBACK_SKIP
                        .equals(properties.getDispatch().getFallback())) {
                    return skipBudget(run, claimed, totalBatches, fallback);
                }
                // Default: re-execute THIS batch on the in-process executor,
                // recorded as executor='in-process-fallback' (spec §3.1 lane
                // vocabulary). The rate-limit retry budget keeps applying.
                executor = inProcess();
                claimed = recordFallbackLane(batchId);
                log.info("DIAG_EVENT kind=batch_fallback runId={} batch={}/{} reason={} detail='{}'",
                        run.getId(), claimed.getBatchNo(), totalBatches,
                        fallback.reason(), fallback.getMessage());
            } catch (RuntimeException generationFailure) {
                return fail(run, claimed, totalBatches,
                        String.valueOf(generationFailure.getMessage()));
            }
        }

        try {
            // Step 3 — the ONE commit tx (governed; spec §4.3).
            FindingCommitService.BatchCommitResult result =
                    commitService.commitBatch(run.getId(), batchId, generation);
            log.info("DIAG_EVENT kind=batch_completed runId={} batch={}/{} scope={} returned={} filtered={} committed={}",
                    run.getId(), claimed.getBatchNo(), totalBatches, claimed.getScope(),
                    result.candidatesReturned(), result.candidatesFiltered(),
                    result.findingsCommitted());
            return BatchOutcome.COMPLETED;
        } catch (RuntimeException commitFailure) {
            // The commit tx rolled back atomically — zero findings persisted.
            return fail(run, claimed, totalBatches,
                    "commit failed: " + commitFailure.getMessage());
        }
    }

    // ---------------------------------------------------------------- claiming

    private DiagnosticRunBatch claim(UUID batchId, Set<String> claimableStatuses,
                                     String executorName) {
        return transactionTemplate.execute(tx -> {
            DiagnosticRunBatch batch = batchRepository.findById(batchId).orElse(null);
            if (batch == null || !claimableStatuses.contains(batch.getStatus())) {
                return null;
            }
            Instant now = Instant.now(clock);
            batch.setStatus(DiagnosticRunBatch.STATUS_RUNNING);
            batch.setClaimedAt(now);
            if (batch.getStartedAt() == null) {
                batch.setStartedAt(now);
            }
            batch.setAttempt(batch.getAttempt() + 1);
            batch.setExecutor(executorName);
            return batchRepository.save(batch);
        });
    }

    /** Rate-limit retry path: refresh the claim + bump the attempt counter. */
    private DiagnosticRunBatch reclaim(UUID batchId) {
        return transactionTemplate.execute(tx -> {
            DiagnosticRunBatch batch = batchRepository.findById(batchId).orElseThrow();
            batch.setClaimedAt(Instant.now(clock));
            batch.setAttempt(batch.getAttempt() + 1);
            return batchRepository.save(batch);
        });
    }

    /**
     * §8.2 in-process fallback bookkeeping: the batch stays RUNNING/claimed,
     * the lane flips to {@code in-process-fallback} (visible in the ledger and
     * the run's executor mix), and the claim is refreshed.
     */
    private DiagnosticRunBatch recordFallbackLane(UUID batchId) {
        return transactionTemplate.execute(tx -> {
            DiagnosticRunBatch batch = batchRepository.findById(batchId).orElseThrow();
            batch.setExecutor(DiagnosticExecutor.EXECUTOR_IN_PROCESS_FALLBACK);
            batch.setClaimedAt(Instant.now(clock));
            return batchRepository.save(batch);
        });
    }

    /**
     * §8.2 {@code fallback=skip}: the batch is marked {@code SKIPPED_BUDGET} —
     * NEUTRAL, never error-colored (the {@code error} column stays null;
     * budget refusal is a governance non-event per the contract doc), and it
     * never blocks run COMPLETED (finalization treats it as a non-failure).
     */
    private BatchOutcome skipBudget(SynthesisRun run, DiagnosticRunBatch batch,
                                    int totalBatches, DispatchFallbackException fallback) {
        transactionTemplate.executeWithoutResult(tx -> {
            DiagnosticRunBatch row = batchRepository.findById(batch.getId()).orElseThrow();
            row.setStatus(DiagnosticRunBatch.STATUS_SKIPPED_BUDGET);
            row.setError(null);
            row.setFinishedAt(Instant.now(clock));
            batchRepository.save(row);
        });
        log.info("DIAG_EVENT kind=batch_skipped_budget runId={} batch={}/{} reason={} detail='{}'",
                run.getId(), batch.getBatchNo(), totalBatches,
                fallback.reason(), fallback.getMessage());
        return BatchOutcome.SKIPPED_BUDGET;
    }

    private BatchOutcome fail(SynthesisRun run, DiagnosticRunBatch batch, int totalBatches,
                              String error) {
        transactionTemplate.executeWithoutResult(tx -> {
            DiagnosticRunBatch row = batchRepository.findById(batch.getId()).orElseThrow();
            row.setStatus(DiagnosticRunBatch.STATUS_FAILED);
            row.setError(error);
            row.setFinishedAt(Instant.now(clock));
            batchRepository.save(row);
        });
        log.warn("DIAG_EVENT kind=batch_failed runId={} batch={}/{} scope={} attempt={} error='{}'",
                run.getId(), batch.getBatchNo(), totalBatches, batch.getScope(),
                batch.getAttempt(), error);
        return BatchOutcome.FAILED;
    }

    // ------------------------------------------------------------ lane selection

    /**
     * Executor selection (spec §2/§8): the resolved lane is recorded in run
     * metadata at start. Until build-order step 8 lands the
     * {@code OrchestratorDiagnosticExecutor} bean, every resolution degrades
     * to in-process — whole-or-in-part: degradation is a missing bean, never
     * a parallel code path.
     */
    private DiagnosticExecutor selectExecutor(SynthesisRun run) {
        String requested = run.getMetadata() != null
                && run.getMetadata().hasNonNull("executor")
                ? run.getMetadata().get("executor").asText()
                : DiagnosticProperties.EXECUTOR_IN_PROCESS;
        return executors.stream()
                .filter(e -> e.name().equals(requested))
                .findFirst()
                .orElseGet(this::inProcess);
    }

    private DiagnosticExecutor inProcess() {
        return executors.stream()
                .filter(e -> DiagnosticExecutor.EXECUTOR_IN_PROCESS.equals(e.name()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "InProcessDiagnosticExecutor bean missing — it is the unconditional base case"));
    }

    private void backoff(int attempt) {
        long delay = Math.min(BACKOFF_BASE_MS * (1L << Math.max(0, attempt - 1)), BACKOFF_CAP_MS);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
