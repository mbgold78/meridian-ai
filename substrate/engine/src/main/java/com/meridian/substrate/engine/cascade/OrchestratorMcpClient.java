package com.meridian.substrate.engine.cascade;

import java.util.List;

/**
 * The orchestrator MCP tool surface substrate consumes (engine-spec cascade.md
 * §7; orchestrator-substrate-contract.md) — exactly the five permitted tools,
 * typed to what the cascade draft AI-suggest lane needs. Substrate never calls
 * PM-loop or role-hierarchy tools ({@code dispatch_implementer},
 * {@code dispatch_tester}, {@code set_pm_mode}, …).
 *
 * <p>This interface is the engine's transport seam: it is consumed only by
 * {@link OrchestratorSuggestClient} (itself gated on
 * {@code substrate.orchestrator.enabled}) and is mocked in tests (§12 test
 * #11). The production transport — the orchestrator serves MCP over Streamable
 * HTTP at {@code {substrate.orchestrator.base-url}/mcp} — binds an
 * implementation of this interface when the official MCP Java SDK lands with
 * the Phase-3 {@code substrate-contracts} server work (contract doc §2); until
 * then, enabling the flag without a transport bean fails boot loudly rather
 * than degrading silently.
 *
 * <p>Vocabulary note: {@link DispatchState} is orchestrator-dispatch
 * <em>plumbing</em> status (like {@code CascadeSuggestionState.ERROR}, spec
 * §2) — it describes the AI-assist task, never cascade governance state;
 * decline-never-error applies to the governance enums only.
 */
public interface OrchestratorMcpClient {

    /** Plumbing status of a submitted dispatch, as substrate consumes it. */
    enum DispatchState {
        /** Queued or running — keep the draft's suggestion PENDING. */
        IN_FLIGHT,
        /** Terminal success — result + events are retrievable. */
        COMPLETED,
        /** Terminal non-success (failed/cancelled) — suggestion lane goes ERROR. */
        FAILED
    }

    /**
     * {@code check_budget_for_task} preflight outcome. A refusal is a
     * NON-EVENT for governance: the suggestion lane records
     * {@code suggestion_state=ERROR} with a provenance note and the human
     * authoring path continues (contract doc).
     */
    record BudgetDecision(boolean approved, String note) {
    }

    /**
     * {@code get_dispatch_result} payload, typed to the §7 landing columns:
     * agent output enters substrate ONLY as draft suggestion text a human may
     * promote (P6 — ungrounded AI output never auto-issues).
     */
    record DispatchResult(String suggestedCritique, String suggestedAcceptanceCriteria,
                          String model) {
    }

    /** {@code check_budget_for_task(...)} — preflight before every submission. */
    BudgetDecision checkBudgetForTask(String taskType, String description);

    /**
     * {@code submit_task(agent_role, description, task_type)} — the single
     * dispatch verb; returns the orchestrator task id.
     */
    String submitTask(String agentRole, String description, String taskType);

    /** {@code get_dispatch_status(task_id)} — non-blocking poll (on-read refresh here). */
    DispatchState getDispatchStatus(String taskId);

    /** {@code get_dispatch_result(task_id)} — retrieves the agent's suggestion output. */
    DispatchResult getDispatchResult(String taskId);

    /**
     * {@code get_dispatch_events(task_id)} — event stream (string/JSON forms),
     * digested into the draft's {@code suggestion_provenance} as the
     * engagement's audit trail of the AI assist.
     */
    List<String> getDispatchEvents(String taskId);
}
