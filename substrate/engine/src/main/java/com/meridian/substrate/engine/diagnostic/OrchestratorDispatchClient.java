package com.meridian.substrate.engine.diagnostic;

import java.util.List;

/**
 * The orchestrator MCP tool surface the DIAGNOSTIC engine consumes
 * (engine-spec diagnostic.md §8.2; ADR-004 D11; orchestrator-substrate
 * contract doc) — exactly the five permitted tools, typed to what the
 * diagnostic dispatch lane needs (candidate JSON, not the cascade lane's
 * {@code suggested_*} columns — same five-tool surface as
 * {@code engine.cascade.OrchestratorMcpClient}, lane-typed like that seam).
 * Substrate never calls PM-loop or role-hierarchy tools
 * ({@code dispatch_implementer}, {@code set_pm_mode}, …).
 *
 * <p>This interface is the engine's transport seam: consumed only by
 * {@link OrchestratorDiagnosticExecutor} (itself gated on
 * {@code substrate.orchestrator.enabled} + the
 * {@code substrate.diagnostic.executor} flag) and mocked in tests
 * (diagnostic.md test plan #11). The production transport — the orchestrator
 * serves MCP over Streamable HTTP at
 * {@code {substrate.orchestrator.base-url}/mcp} — binds an implementation
 * when the MCP Java SDK client work lands (shared dependency with the cascade
 * suggest seam; spec §8.2 "known dependency"); until then, enabling the flags
 * without a transport bean fails boot loudly rather than degrading silently.
 *
 * <p>Vocabulary note: {@link DispatchState} is orchestrator-dispatch
 * <em>plumbing</em> status — it describes the AI-generation task, never run
 * governance state; decline-never-error applies to the run/batch vocabulary
 * only ({@code SKIPPED_BUDGET} renders neutral, spec §3.1/§8.2).
 */
public interface OrchestratorDispatchClient {

    /** Plumbing status of a submitted dispatch, as substrate consumes it. */
    enum DispatchState {
        /** Queued or running — keep polling. */
        IN_FLIGHT,
        /** Terminal success — result + events are retrievable. */
        COMPLETED,
        /** Terminal non-success (failed/cancelled) — fallback semantics apply (spec §8.2). */
        FAILED
    }

    /**
     * {@code check_budget_for_task} preflight outcome. A refusal is a
     * NON-EVENT for governance (contract doc): per
     * {@code substrate.diagnostic.dispatch.fallback} the batch re-executes
     * in-process (recorded {@code executor='in-process-fallback'}) or is
     * marked {@code SKIPPED_BUDGET} — neutral, never error-colored.
     */
    record BudgetDecision(boolean approved, String note) {
    }

    /**
     * {@code get_dispatch_result} payload: the agent's candidate JSON (the
     * same wire contract the validative prompt names — a JSON array of
     * finding objects) plus model/token accounting. Agent output enters
     * substrate ONLY by re-transiting {@link CandidateFilter} — agents are
     * advisors, never mutators (spec non-negotiable #2).
     *
     * @param candidatesJson raw agent output text containing the candidate array
     * @param model          model id the agent reports (ledger/run accounting)
     * @param inputTokens    prompt tokens (nullable — absent ⇒ 0 in accounting)
     * @param outputTokens   completion tokens (nullable — absent ⇒ 0)
     */
    record DispatchResult(String candidatesJson, String model,
                          Integer inputTokens, Integer outputTokens) {
    }

    /** {@code check_budget_for_task(...)} — preflight before every submission. */
    BudgetDecision checkBudgetForTask(String taskType, String description);

    /**
     * {@code submit_task(agent_role, description, task_type)} — the single
     * dispatch verb; returns the orchestrator task id.
     */
    String submitTask(String agentRole, String description, String taskType);

    /** {@code get_dispatch_status(task_id)} — non-blocking poll. */
    DispatchState getDispatchStatus(String taskId);

    /** {@code get_dispatch_result(task_id)} — retrieves the agent's candidate output. */
    DispatchResult getDispatchResult(String taskId);

    /**
     * {@code get_dispatch_events(task_id)} — event stream (string/JSON
     * forms), digested (SHA-256) into run metadata
     * {@code dispatchProvenance} as the engagement's audit trail of the
     * dispatched generation (spec §4.5/§8.2).
     */
    List<String> getDispatchEvents(String taskId);
}
