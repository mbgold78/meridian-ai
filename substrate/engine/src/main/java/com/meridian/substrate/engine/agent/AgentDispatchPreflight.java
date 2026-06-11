package com.meridian.substrate.engine.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.enums.AgentEventType;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.domain.enums.AgentSightingChannel;
import com.meridian.substrate.common.domain.enums.ContractActorType;
import com.meridian.substrate.common.domain.enums.GatewayDecision;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.engine.agent.AgentRegistryLookup.ActionsBudget;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.contract.gateway.AgentRegistryGate;
import com.meridian.substrate.engine.contract.gateway.EnforcementDecisionWriter;
import com.meridian.substrate.engine.contract.gateway.EnforcementMode;
import com.meridian.substrate.engine.contract.gateway.GovernedAction;
import com.meridian.substrate.engine.contract.gateway.GovernedActions;
import com.meridian.substrate.engine.contract.gateway.Principal;
import com.meridian.substrate.engine.contract.gateway.TargetRef;
import com.meridian.substrate.engine.contract.gateway.Ungoverned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * ORCHESTRATOR-locus integration hooks (engine-spec agents.md §14; ADR-003
 * D2/D8) — the registry-side halves of the two seam-legal controls for
 * mandate-bearing dispatches. <b>Zero orchestrator touchpoints live in this
 * engine</b>: the components that own dispatching (Phase 4: cascade-level
 * execution, finding curation as agent X) call these hooks; the orchestrator
 * never learns the registry exists, and with
 * {@code substrate.orchestrator.enabled=false} nothing here ever fires
 * (degraded posture, test plan #16).
 *
 * <ol>
 *   <li><b>{@link #beforeSubmit} — dispatch preflight:</b> PAUSED/RETIRED
 *       registrations get NO new submissions (a governance outcome carried
 *       as a refusal result, never an error shape); ACTIONS_PER_WINDOW
 *       budgets pace submissions (composing with
 *       {@code check_budget_for_task}). {@link #onSubmitted} rows the
 *       DISPATCH event with the standard provenance digest
 *       ({@code {taskId, model, eventsDigest, requestedAt, landedAt}} — the
 *       {@code cascade_drafts.suggestion_provenance} shape) plus a
 *       DISPATCH_EVENT sighting.</li>
 *   <li><b>{@link #guardResultApply} — result quarantine:</b> the
 *       {@code apply_dispatch_result} PEP consults the registry and REFUSES
 *       results attributable to a killed/paused registration — DENY-shaped
 *       row in {@code contract_enforcement_decisions} (no parallel log,
 *       {@code dispatch_id} stamped) + a CONTROL_VIOLATION-class signal to
 *       the oversight chain. In-flight dispatches CANNOT be aborted (the
 *       five-tool seam has no cancel — disclosed plainly); kill = no new
 *       submissions + no re-entry of in-flight output.</li>
 * </ol>
 *
 * <p>The quarantine is a substrate-side dispatching discipline, NOT a
 * gateway mode outcome: it refuses in every enforcement mode (ADR-003 D8 —
 * this is the enforcement an ORCHESTRATOR-locus pause/kill actually has;
 * making it shadow-soft would fabricate even less control than disclosed).
 *
 * <p>EXTENSION POINT(agent-7): a cancel/pause consumption tool would revise
 * the ratified orchestrator seam doc — flagged Mike follow-up, never wired
 * unilaterally (ADR-003 D8).
 */
@Service
public class AgentDispatchPreflight {

    /** Quarantine decision-row reason (a CONTROL_VIOLATION-class refusal). */
    public static final String REASON_RESULT_QUARANTINED = "AGENT_RESULT_QUARANTINED";

    private static final Logger log = LoggerFactory.getLogger(AgentDispatchPreflight.class);

    private final AgentRegistrationRepository registrationRepository;
    private final AgentActionWindows actionWindows;
    private final com.meridian.substrate.common.repo.AgentRateCounterRepository rateCounterRepository;
    private final AgentLivenessService livenessService;
    private final AgentGatewaySignalService signalService;
    private final EnforcementDecisionWriter decisionWriter;
    private final ContractProperties contractProperties;
    private final com.meridian.substrate.common.repo.AgentStatusEventRepository eventRepository;
    private final com.meridian.substrate.common.repo.AgentThrottleBudgetRepository throttleBudgetRepository;
    private final Clock clock;

    public AgentDispatchPreflight(
            AgentRegistrationRepository registrationRepository,
            AgentActionWindows actionWindows,
            com.meridian.substrate.common.repo.AgentRateCounterRepository rateCounterRepository,
            AgentLivenessService livenessService,
            AgentGatewaySignalService signalService,
            EnforcementDecisionWriter decisionWriter,
            ContractProperties contractProperties,
            com.meridian.substrate.common.repo.AgentStatusEventRepository eventRepository,
            com.meridian.substrate.common.repo.AgentThrottleBudgetRepository throttleBudgetRepository,
            Clock clock) {
        this.registrationRepository = registrationRepository;
        this.actionWindows = actionWindows;
        this.rateCounterRepository = rateCounterRepository;
        this.livenessService = livenessService;
        this.signalService = signalService;
        this.decisionWriter = decisionWriter;
        this.contractProperties = contractProperties;
        this.eventRepository = eventRepository;
        this.throttleBudgetRepository = throttleBudgetRepository;
        this.clock = clock;
    }

    /** Preflight outcome — a governance result, never an exception shape. */
    public record Preflight(boolean allowed, String reasonCode, String disclosure) {

        static Preflight allow() {
            return new Preflight(true, null,
                    "registry preflight passed — submission may proceed");
        }

        static Preflight refuse(String reasonCode, String disclosure) {
            return new Preflight(false, reasonCode, disclosure);
        }
    }

    /** Quarantine outcome for the {@code apply_dispatch_result} PEP. */
    public record Quarantine(boolean applyAllowed, String reasonCode, UUID decisionId) {
    }

    // ------------------------------------------------------------- preflight

    /**
     * §14.1 — called by any substrate component BEFORE {@code submit_task}
     * on a registered agent's mandate. The context pack the caller builds
     * must include {@code agent_key} + the instruction to present
     * {@code X-MCP-Principal: <mcp_principal_id>} on substrate calls
     * (identity binding is 100% substrate-side, ADR-003 D2).
     */
    @Ungoverned(reason = "registry preflight for the §14 dispatch hooks — consulted BY the"
            + " dispatching component before submit_task; counter pacing is gateway-class"
            + " bookkeeping, not a lifecycle verb or ADR D2 PEP (engine-spec agents.md §14)")
    @Transactional
    public Preflight beforeSubmit(UUID domainId, String agentKey) {
        AgentRegistration reg = registrationRepository
                .findByDomainIdAndAgentKey(domainId, agentKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no agent registration '" + agentKey + "' in domain " + domainId
                                + " — mandate-bearing dispatches require one (ADR-003 D2)"));
        if (reg.getOperationalMode() == AgentOperationalMode.PAUSED
                || reg.getOperationalMode() == AgentOperationalMode.RETIRED) {
            return Preflight.refuse(AgentRegistryGate.REASON_CONTROL_SUSPENDED,
                    "agent '" + agentKey + "' is " + reg.getOperationalMode().wireValue()
                            + " — no new dispatches are submitted on this mandate; in-flight"
                            + " dispatches cannot be aborted (five-tool seam, no cancel —"
                            + " agents.md §14)");
        }
        Optional<ActionsBudget> budget = throttleBudgetRepository
                .findByAgentRegistrationIdAndBudgetKindAndActiveTrue(
                        reg.getId(), AgentRegistryLookup.BUDGET_ACTIONS_PER_WINDOW)
                .map(b -> new ActionsBudget(b.getLimitValue(), b.getWindowMinutes()));
        if (budget.isPresent() && budget.get().limitValue() != null) {
            int count = paceWindow(reg.getId(), budget.get());
            if (count > budget.get().limitValue()) {
                return Preflight.refuse(AgentRegistryGate.REASON_BUDGET_EXHAUSTED,
                        "agent '" + agentKey + "' exhausted its ACTIONS_PER_WINDOW budget —"
                                + " submissions are paced by the same DB-backed counters as"
                                + " gateway actions (agents.md §14/§8.3 #4)");
            }
        }
        return Preflight.allow();
    }

    /**
     * §14.1 — after a successful {@code submit_task}: a DISPATCH event rows
     * onto the agent's log with the provenance digest, plus a DISPATCH_EVENT
     * sighting. Idempotent per {@code taskId}.
     */
    @Ungoverned(reason = "registry-side recording of a dispatch the orchestrator seam owns"
            + " (engine-spec agents.md §14 #1; ADR-003 D2) — the DISPATCH event is the audit"
            + " digest of an act that already passed its own governance at submit")
    @Transactional
    public void onSubmitted(UUID agentRegistrationId, JsonNode provenanceDigest) {
        if (provenanceDigest == null || provenanceDigest.path("taskId").isMissingNode()) {
            throw new IllegalArgumentException("provenanceDigest with a taskId is required —"
                    + " {taskId, model, eventsDigest, requestedAt, landedAt} (agents.md §14)");
        }
        AgentRegistration reg = AgentVerbMechanics.lock(registrationRepository, agentRegistrationId);
        String idempotencyKey = "dispatch:" + provenanceDigest.path("taskId").asText();
        if (AgentVerbMechanics.replay(eventRepository, reg, idempotencyKey,
                AgentEventType.DISPATCH).isPresent()) {
            return;
        }
        AgentVerbMechanics.appendEvent(eventRepository, reg, AgentEventType.DISPATCH,
                new AgentActor(null, Principal.SUBSTRATE_SERVICE_ID, ContractActorType.SERVICE),
                null, provenanceDigest, null, idempotencyKey, clock.instant());
        registrationRepository.save(reg);
        livenessService.recordSighting(reg.getId(), AgentSightingChannel.DISPATCH_EVENT,
                "dispatched task " + provenanceDigest.path("taskId").asText());
        log.info("Dispatch rowed on agent mandate [key={}, taskId={}]",
                reg.getAgentKey(), provenanceDigest.path("taskId").asText());
    }

    // ------------------------------------------------------------ quarantine

    /**
     * §14.2 — consulted by the {@code apply_dispatch_result} PEP (ADR-002
     * D2 #3) before applying a result attributable to a registered agent.
     * Killed/paused ⇒ refused: DENY-shaped decision row (dispatch id
     * stamped) + CONTROL_VIOLATION-class signal. The signal deliberately
     * records NO sighting — a quarantined result is not liveness evidence
     * of the agent itself.
     */
    @Ungoverned(reason = "the §14 #2 result-quarantine consult INSIDE the apply_dispatch_result"
            + " PEP (ADR-002 D2 #3) — the PEP itself builds its GovernedAction explicitly;"
            + " this hook rows the refusal and signals the chain (engine-spec agents.md §14)")
    @Transactional
    public Quarantine guardResultApply(UUID domainId, String agentKey, String dispatchId) {
        AgentRegistration reg = registrationRepository
                .findByDomainIdAndAgentKey(domainId, agentKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "no agent registration '" + agentKey + "' in domain " + domainId));
        if (reg.getOperationalMode() != AgentOperationalMode.PAUSED
                && reg.getOperationalMode() != AgentOperationalMode.RETIRED) {
            return new Quarantine(true, null, null);
        }
        EnforcementMode mode = EnforcementMode.parse(
                contractProperties.getEnforcement().getMode());
        UUID decisionId = decisionWriter.row(
                new GovernedAction(GovernedActions.APPLY_DISPATCH_RESULT, Principal.service(),
                        reg.getDomainId(),
                        new TargetRef(AgentGovernedResolvers.TARGET_AGENT_REGISTRATION, agentKey),
                        null, dispatchId),
                GatewayDecision.DENY, java.util.List.of(), java.util.List.of(),
                REASON_RESULT_QUARANTINED, mode, null);
        signalService.recordControlViolation(reg.getId(),
                "dispatch result '" + dispatchId + "' refused at apply — attributable to a "
                        + reg.getOperationalMode().wireValue() + " registration (agents.md §14 #2)",
                false);
        log.warn("Dispatch result quarantined [key={}, dispatchId={}, mode={}]",
                agentKey, dispatchId, reg.getOperationalMode());
        return new Quarantine(false, REASON_RESULT_QUARANTINED, decisionId);
    }

    // ------------------------------------------------------------------ helpers

    /** Same window arithmetic as the gateway evaluator (§8.3 #4). */
    private int paceWindow(UUID agentRegistrationId, ActionsBudget budget) {
        int windowMinutes = budget.windowMinutes() != null && budget.windowMinutes() > 0
                ? budget.windowMinutes() : AgentRegistryGateImpl.DEFAULT_WINDOW_MINUTES;
        long windowSeconds = windowMinutes * 60L;
        long epoch = clock.instant().getEpochSecond();
        java.time.Instant windowStart =
                java.time.Instant.ofEpochSecond((epoch / windowSeconds) * windowSeconds);
        int updated = rateCounterRepository.incrementWindow(agentRegistrationId, windowStart);
        if (updated == 0) {
            actionWindows.ensureWindowRow(agentRegistrationId, windowStart);
            rateCounterRepository.incrementWindow(agentRegistrationId, windowStart);
        }
        return rateCounterRepository
                .findByAgentRegistrationIdAndWindowStart(agentRegistrationId, windowStart)
                .map(com.meridian.substrate.common.domain.AgentRateCounter::getActionCount)
                .orElse(1);
    }
}
