package com.meridian.substrate.engine.contract;

import com.meridian.substrate.engine.cascade.OrchestratorMcpClient;
import com.meridian.substrate.engine.contract.events.BreachSurfacedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The OPTIONAL §11.1 orchestrator remediation hook (engine-spec contracts.md
 * §1 {@code substrate.contracts.orchestrator-remediation}, default
 * {@code false}): when enabled AND {@code substrate.orchestrator.enabled},
 * a breach SURFACED may submit a remediation task via the orchestrator's
 * {@code submit_task} (budget-preflighted, {@code OrchestratorSuggestClient}
 * idiom). Consumes the after-commit {@link BreachSurfacedEvent} — one of the
 * two §11.1 leader-attention signal classes.
 *
 * <p>Strictly one-directional (ADR D2): substrate calls orchestrator tools;
 * the dispatched agent is an ADVISOR whose output re-enters substrate only
 * through the governed {@code apply_dispatch_result} path (3c PEP #3) — this
 * hook never applies anything itself.
 *
 * <p>Degrades loudly, never destructively: a refused budget, missing
 * transport bean ({@link OrchestratorMcpClient} has no production
 * implementation until the Phase-3 MCP client lands), or submit failure is
 * WARN-logged and governance state is untouched — the dashboard signal
 * ({@code ContractSignalService}) remains the source of truth either way.
 * Email/webhook channels stay an EXTENSION POINT (spec §15 #13, not built).
 */
@Component
@ConditionalOnProperty(name = "substrate.contracts.orchestrator-remediation", havingValue = "true")
public class BreachRemediationHook {

    private static final Logger log = LoggerFactory.getLogger(BreachRemediationHook.class);

    /** OrchestratorSuggestClient vocabulary idiom: role + task type constants. */
    static final String AGENT_ROLE = "engineer";
    static final String TASK_TYPE = "contract-breach-remediation";

    private final ObjectProvider<OrchestratorMcpClient> orchestrator;
    private final boolean orchestratorEnabled;

    public BreachRemediationHook(ObjectProvider<OrchestratorMcpClient> orchestrator,
                                 @Value("${substrate.orchestrator.enabled:false}")
                                 boolean orchestratorEnabled) {
        this.orchestrator = orchestrator;
        this.orchestratorEnabled = orchestratorEnabled;
        if (!orchestratorEnabled) {
            log.warn("substrate.contracts.orchestrator-remediation=true but"
                    + " substrate.orchestrator.enabled=false — remediation hook is INERT"
                    + " (degraded mode, ADR D12)");
        }
    }

    /** Fires after the surfacing transaction commits (BreachService posture). */
    @EventListener
    public void onBreachSurfaced(BreachSurfacedEvent event) {
        if (!orchestratorEnabled) {
            return;                                    // degraded mode — dashboard signal only
        }
        OrchestratorMcpClient client = orchestrator.getIfAvailable();
        if (client == null) {
            log.warn("Remediation hook enabled but no OrchestratorMcpClient transport bean is"
                            + " bound (Phase-3 MCP client) — breach {} surfaced without a"
                            + " remediation task", event.breachId());
            return;
        }
        // Context pack REFERENCES substrate state by id (contract doc posture);
        // operator prose stays in substrate.
        String description = "Contract breach surfaced [contractKey=" + event.contractKey()
                + ", breachId=" + event.breachId()
                + (event.severity() != null ? ", severity=" + event.severity() : "")
                + "]: " + event.summary()
                + ". Propose remediation steps; evidence re-enters substrate only via the"
                + " governed apply path.";
        try {
            OrchestratorMcpClient.BudgetDecision budget =
                    client.checkBudgetForTask(TASK_TYPE, description);
            if (!budget.approved()) {
                log.warn("Remediation task budget REFUSED for breach {} — non-event for"
                        + " governance ({})", event.breachId(), budget.note());
                return;
            }
            String taskId = client.submitTask(AGENT_ROLE, description, TASK_TYPE);
            log.info("Remediation task submitted [breachId={}, contractKey={}, taskId={}]",
                    event.breachId(), event.contractKey(), taskId);
        } catch (RuntimeException ex) {
            log.warn("Remediation task submission failed [breachId={}] — governance state"
                    + " untouched", event.breachId(), ex);
        }
    }
}
