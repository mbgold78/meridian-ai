package com.meridian.substrate.engine.diagnostic;

import com.meridian.substrate.common.domain.DiagnosticRunBatch;
import com.meridian.substrate.common.domain.SynthesisRun;
import com.meridian.substrate.engine.ai.validative.FindingCandidate;

import java.util.List;
import java.util.Map;

/**
 * Generation-executor SPI (engine-spec diagnostic.md §1/§8; ADR-004 D11):
 * produce filtered, Assertion-backed candidates for one batch. Two
 * implementations are designed — {@link InProcessDiagnosticExecutor} (the
 * default bean; degraded mode IS the base case) and
 * {@code OrchestratorDiagnosticExecutor} (build-order step 8, conditional on
 * {@code substrate.orchestrator.enabled}); degradation is exactly one missing
 * bean, never a parallel code path (spec non-negotiable #6).
 *
 * <p>One grounding chokepoint: whatever the lane, every candidate in
 * {@link BatchGeneration#kept()} has ALREADY transited {@link CandidateFilter}
 * (and so carries a persisted {@code Assertion(AI_VALIDATIVE)} id). The
 * orchestrated lane re-validates dispatched output through the SAME filter —
 * agents are advisors, never mutators.
 *
 * <p>Generation runs OUTSIDE any transaction (spec §4.3 step 2); the commit
 * transaction belongs to {@link FindingCommitService}.
 *
 * <p>EXTENSION POINT(diagnostic-8): agent-proposed findings as AGENT
 * principals. v1 has none — the orchestrated lane is SERVICE-applied only
 * ({@code apply_dispatch_result}, ADR-002 PEP #3); AGENT-principal proposals
 * ride the D3 registry + governed MCP tools when that lands, never a bypass.
 * Flag, don't invent.
 */
public interface DiagnosticExecutor {

    String EXECUTOR_IN_PROCESS = "in-process";
    String EXECUTOR_ORCHESTRATOR = "orchestrator";
    String EXECUTOR_IN_PROCESS_FALLBACK = "in-process-fallback";

    /** Ledger name of this lane: in-process | orchestrator (spec §3.1 executor column). */
    String name();

    /**
     * Generate-and-filter one batch. Throws {@code LlmRateLimitException} to
     * request the batch executor's bounded backoff-retry; any other exception
     * fails the batch (run continues — ADR-004 D2).
     */
    BatchGeneration executeBatch(SynthesisRun run, DiagnosticRunBatch batch);

    /**
     * The per-batch generation result feeding the ledger + run accounting
     * (spec §3.1/§4.5).
     *
     * @param kept                    filter survivors (Assertion already persisted), input order
     * @param candidatesReturned      parseable raw candidates BEFORE filtering
     * @param filterHistogram         non-KEPT {@link CandidateFilter.Outcome} counts
     * @param inputTokens             prompt tokens of this generation
     * @param outputTokens            completion tokens of this generation
     * @param model                   model id used
     * @param refusalDetected         the model's final text was classified a refusal
     * @param allFilteredExplanation  the mode service's human-readable reason when
     *                                everything was filtered (else null)
     * @param dispatchTaskId          orchestrated lane only (else null)
     * @param dispatchEventsDigest    SHA-256 digest of get_dispatch_events
     *                                (orchestrated lane only, else null) —
     *                                accumulated into run-metadata
     *                                {@code dispatchProvenance} (spec §4.5/§8.2)
     */
    record BatchGeneration(
            List<FindingCandidate> kept,
            int candidatesReturned,
            Map<CandidateFilter.Outcome, Integer> filterHistogram,
            int inputTokens,
            int outputTokens,
            String model,
            boolean refusalDetected,
            String allFilteredExplanation,
            String dispatchTaskId,
            String dispatchEventsDigest
    ) {

        /** Compatibility constructor (pre-step-8 arity) — no dispatch provenance. */
        public BatchGeneration(List<FindingCandidate> kept,
                               int candidatesReturned,
                               Map<CandidateFilter.Outcome, Integer> filterHistogram,
                               int inputTokens,
                               int outputTokens,
                               String model,
                               boolean refusalDetected,
                               String allFilteredExplanation,
                               String dispatchTaskId) {
            this(kept, candidatesReturned, filterHistogram, inputTokens, outputTokens,
                    model, refusalDetected, allFilteredExplanation, dispatchTaskId, null);
        }
    }
}
