package com.meridian.substrate.engine.diagnostic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.DiagnosticRunBatch;
import com.meridian.substrate.common.domain.SynthesisRun;
import com.meridian.substrate.engine.ai.orchestration.Surface;
import com.meridian.substrate.engine.contract.gateway.ContractGateway;
import com.meridian.substrate.engine.contract.gateway.GovernedAction;
import com.meridian.substrate.engine.contract.gateway.GovernedActions;
import com.meridian.substrate.engine.contract.gateway.Principal;
import com.meridian.substrate.engine.contract.gateway.TargetRef;
import com.meridian.substrate.engine.diagnostic.exception.DispatchFallbackException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The orchestrated generation lane (engine-spec diagnostic.md §8.2; ADR-004
 * D11; build-order step 8) — the diagnostic engine's ONLY orchestrator
 * touchpoint, mirroring the cascade D14 idiom.
 *
 * <p><b>Bean condition (spec §8.2 heading):</b> {@code
 * substrate.orchestrator.enabled=true} AND {@code substrate.diagnostic.executor
 * ∈ {auto, orchestrator}} — with {@code executor=in-process} the lane is
 * structurally absent even when the orchestrator is reachable. Degraded mode
 * loses exactly this one bean (spec non-negotiable #6); enabling the flags
 * without an {@link OrchestratorDispatchClient} transport bean fails boot
 * loudly rather than degrading silently (the MCP Java SDK client transport is
 * the named Phase-3 dependency — this lane can slip without blocking the
 * product path).
 *
 * <p><b>Per batch (spec §8.2):</b> {@code check_budget_for_task} preflight →
 * {@code submit_task(agent_role='diagnostic-analyst',
 * task_type='diagnostic-generation', description=}context pack referencing
 * public read-API GET URLs, never payload dumps{@code )} → non-blocking
 * {@code get_dispatch_status} polling → {@code get_dispatch_result} candidate
 * JSON → <b>re-validated through the same {@link CandidateFilter}</b> (agent
 * output enters substrate only via substrate's own write path; one grounding
 * chokepoint) — and the apply is a {@code GovernedAction(actionType=
 * apply_dispatch_result, principal=SERVICE 'substrate-svc', dispatchId)}
 * evaluated at the {@link ContractGateway} BEFORE any candidate transits the
 * filter (ADR-002 PEP #3; the dispatch id is stamped on the decision row).
 * {@code get_dispatch_events} is SHA-256-digested into the
 * {@link DiagnosticExecutor.BatchGeneration} for run-metadata
 * {@code dispatchProvenance}.
 *
 * <p><b>Fallback semantics:</b> budget refusal / transport failure / dispatch
 * FAILED / poll timeout → {@link DispatchFallbackException}; the
 * {@link DiagnosticBatchExecutor} applies
 * {@code substrate.diagnostic.dispatch.fallback} ({@code in-process} default
 * re-executes the batch recorded {@code executor='in-process-fallback'};
 * {@code skip} ⇒ {@code SKIPPED_BUDGET}, neutral and non-blocking for
 * COMPLETED). A contract-gateway DENY is deliberately NOT a fallback — it
 * fails the batch with the denial cited (a governance decision is never
 * silently re-routed around the PDP).
 *
 * <p>EXTENSION POINT(diagnostic-8): agent-proposed findings as AGENT
 * principals. v1 has none — this lane is SERVICE-applied only; AGENT-principal
 * proposals ride the D3 registry + governed MCP tools when that lands, never
 * a bypass. Flag, don't invent.
 */
@Service
@ConditionalOnExpression(
        "${substrate.orchestrator.enabled:false} "
                + "&& !'in-process'.equals('${substrate.diagnostic.executor:auto}')")
public class OrchestratorDiagnosticExecutor implements DiagnosticExecutor {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorDiagnosticExecutor.class);

    /** Contract-doc task vocabulary for the diagnostic generation lane (spec §8.2). */
    static final String TASK_TYPE = "diagnostic-generation";
    static final String AGENT_ROLE = "diagnostic-analyst";

    /** Poll pacing: non-blocking status reads, paced; the ceiling is the stale-claim budget. */
    private static final long POLL_INTERVAL_MS = 500;

    private final OrchestratorDispatchClient mcp;
    private final CandidateFilter candidateFilter;
    private final ContractGateway gateway;
    private final DiagnosticProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;

    public OrchestratorDiagnosticExecutor(OrchestratorDispatchClient mcp,
                                          CandidateFilter candidateFilter,
                                          ContractGateway gateway,
                                          DiagnosticProperties properties,
                                          ObjectMapper mapper,
                                          Clock clock) {
        this.mcp = mcp;
        this.candidateFilter = candidateFilter;
        this.gateway = gateway;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public String name() {
        return EXECUTOR_ORCHESTRATOR;
    }

    @Override
    public BatchGeneration executeBatch(SynthesisRun run, DiagnosticRunBatch batch) {
        String contextPack = buildContextPack(run, batch);

        // ---- 1. budget preflight (refusal = governance non-event)
        OrchestratorDispatchClient.BudgetDecision budget;
        try {
            budget = mcp.checkBudgetForTask(TASK_TYPE, contextPack);
        } catch (RuntimeException transport) {
            throw DispatchFallbackException.dispatchFailed(
                    "budget preflight failed: " + transport.getMessage());
        }
        if (!budget.approved()) {
            throw DispatchFallbackException.budgetRefused(budget.note());
        }

        // ---- 2. submit
        String taskId;
        try {
            taskId = mcp.submitTask(AGENT_ROLE, contextPack, TASK_TYPE);
        } catch (RuntimeException transport) {
            throw DispatchFallbackException.dispatchFailed(
                    "submit_task failed: " + transport.getMessage());
        }
        log.info("DIAG_EVENT kind=batch_dispatched runId={} batch={} taskId={}",
                run.getId(), batch.getBatchNo(), taskId);

        // ---- 3. poll (non-blocking status reads, paced; bounded by the stale-claim budget)
        awaitCompletion(taskId);

        // ---- 4. result + events
        OrchestratorDispatchClient.DispatchResult result;
        List<String> events;
        try {
            result = mcp.getDispatchResult(taskId);
            events = mcp.getDispatchEvents(taskId);
        } catch (RuntimeException transport) {
            throw DispatchFallbackException.dispatchFailed(
                    "get_dispatch_result failed for task " + taskId + ": " + transport.getMessage());
        }

        List<JsonNode> rawCandidates = parseCandidates(
                result == null ? null : result.candidatesJson());

        // ---- 5. PEP 3 (ADR-002): applying an accepted get_dispatch_result IS a
        // governed action — evaluated BEFORE any candidate transits the filter
        // (the filter persists Assertions; nothing of the agent's output may
        // touch the database past a DENY). ContractDeniedException propagates:
        // the batch goes FAILED with the denial cited — a governance DENY is
        // not a budget/transport fallback.
        ObjectNode applySummary = mapper.createObjectNode();
        applySummary.put("taskId", taskId);
        applySummary.put("batchNo", batch.getBatchNo());
        applySummary.put("candidatesReturned", rawCandidates.size());
        if (result != null && result.model() != null) {
            applySummary.put("model", result.model());
        }
        gateway.evaluate(new GovernedAction(GovernedActions.APPLY_DISPATCH_RESULT,
                Principal.service(), run.getDomainId(),
                new TargetRef(DiagnosticGovernedResolvers.TARGET_DIAGNOSTIC_RUN,
                        run.getId().toString()),
                applySummary, taskId));

        // ---- 6. ONE grounding chokepoint: dispatched output re-enters through
        // the exact same CandidateFilter as in-process output (spec §7.2).
        CandidateFilter.Result filtered = candidateFilter.filter(rawCandidates,
                new CandidateFilter.Context(run.getDomainId(), Surface.ANALYST_QUERY,
                        batch.getScope()));

        String explanation = !rawCandidates.isEmpty() && filtered.kept().isEmpty()
                ? "Dispatched agent returned " + rawCandidates.size()
                        + " candidate(s); all were filtered (type/severity allowlist, missing "
                        + "required fields, or unmet grounding requirement)."
                : null;

        return new BatchGeneration(
                filtered.kept(),
                rawCandidates.size(),
                filtered.histogram(),
                result == null || result.inputTokens() == null ? 0 : result.inputTokens(),
                result == null || result.outputTokens() == null ? 0 : result.outputTokens(),
                result == null ? null : result.model(),
                false,
                explanation,
                taskId,
                sha256(events == null ? List.of() : events));
    }

    // ------------------------------------------------------------------ polling

    private void awaitCompletion(String taskId) {
        Instant deadline = clock.instant()
                .plus(Duration.ofMinutes(properties.getStaleBatchMinutes()));
        while (true) {
            OrchestratorDispatchClient.DispatchState state;
            try {
                state = mcp.getDispatchStatus(taskId);
            } catch (RuntimeException transport) {
                throw DispatchFallbackException.dispatchFailed(
                        "get_dispatch_status failed for task " + taskId + ": "
                                + transport.getMessage());
            }
            switch (state) {
                case COMPLETED -> {
                    return;
                }
                case FAILED -> throw DispatchFallbackException.dispatchFailed(
                        "orchestrator dispatch ended FAILED (task " + taskId + ")");
                case IN_FLIGHT -> {
                    if (clock.instant().isAfter(deadline)) {
                        throw DispatchFallbackException.dispatchFailed(
                                "dispatch poll timed out after "
                                        + properties.getStaleBatchMinutes()
                                        + "m (task " + taskId + ")");
                    }
                    pause();
                }
            }
        }
    }

    private void pause() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw DispatchFallbackException.dispatchFailed("dispatch poll interrupted");
        }
    }

    // ------------------------------------------------------------- context pack

    /**
     * Context pack (spec §8.2): references the public read-API GET surface —
     * never payload dumps (agents read engagement context via the public
     * surface; contract doc) — and restates the candidate wire contract the
     * filter will enforce on the way back in.
     */
    private String buildContextPack(SynthesisRun run, DiagnosticRunBatch batch) {
        StringBuilder pack = new StringBuilder();
        pack.append("Task: audit the '").append(batch.getScope())
                .append("' scope of domain ").append(run.getDomainId())
                .append(" for gaps, contradictions, drift, or quality issues ")
                .append("(diagnostic batch ").append(batch.getBatchNo()).append(").\n");
        if (batch.getTargetEntityIds() != null && batch.getTargetEntityIds().isArray()
                && !batch.getTargetEntityIds().isEmpty()) {
            pack.append("Restrict to these entity ids: ")
                    .append(batch.getTargetEntityIds().toString()).append('\n');
        }
        pack.append("Read-only context (GET, public read API):\n");
        pack.append("  /api/v1/domains/").append(run.getDomainId()).append('\n');
        pack.append("  /api/v1/ecosystem/findings?domainId=").append(run.getDomainId()).append('\n');
        pack.append("Respond with ONLY a JSON array of finding objects: ")
                .append("{type: STRUCTURAL|CONTENT, severity: CRITICAL|HIGH|MEDIUM|INFO, ")
                .append("description, suggestedAction, optional title/findingType/")
                .append("affectedEntityType/affectedEntityId, evidenceIds: [UUID] — ")
                .append("REQUIRED for CONTENT, ids must be real in-domain Evidence rows ")
                .append("(use find_evidence); ungrounded or off-allowlist candidates ")
                .append("will be filtered by substrate's CandidateFilter}.\n");
        return pack.toString();
    }

    /** Tolerant array extraction — same posture as the mode services (prose-wrapped arrays parse). */
    private List<JsonNode> parseCandidates(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        try {
            JsonNode parsed = mapper.readTree(text.substring(start, end + 1));
            if (!parsed.isArray()) {
                return List.of();
            }
            List<JsonNode> out = new ArrayList<>();
            parsed.forEach(out::add);
            return out;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse dispatched candidate JSON: {}", e.getMessage());
            return List.of();
        }
    }

    /** Events digest for dispatchProvenance: SHA-256 hex over the event lines. */
    static String sha256(List<String> events) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String event : events) {
                digest.update(event.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
