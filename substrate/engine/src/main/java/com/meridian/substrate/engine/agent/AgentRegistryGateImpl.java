package com.meridian.substrate.engine.agent;

import com.meridian.substrate.common.domain.AgentRateCounter;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.repo.AgentRateCounterRepository;
import com.meridian.substrate.common.repo.ContractPartyRepository;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.contract.gateway.AgentRegistryGate;
import com.meridian.substrate.engine.contract.gateway.ContractGateway;
import com.meridian.substrate.engine.contract.gateway.EnforcementMode;
import com.meridian.substrate.engine.contract.gateway.GovernedAction;
import com.meridian.substrate.engine.contract.gateway.GovernedActions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The real {@link AgentRegistryGate} (engine-spec agents.md §8; ADR-003 D7)
 * — {@code @Primary} over the contracts-owned no-op default, guarded
 * {@code @ConditionalOnClass(ContractGateway.class)} so {@code engine.agent}
 * compiles/deploys without the contracts module (whole-or-in-part).
 *
 * <p>§8.2 resolution order for an AGENT principal:
 * <ol>
 *   <li><b>Registered</b> ({@code agent_registrations.mcp_principal_id}
 *       match, 30s-TTL cached — {@link AgentRegistryLookup}) → the §8.3
 *       check ladder below;</li>
 *   <li><b>Contract-party carve-out</b>
 *       ({@code contract_parties.mcp_principal_id} match): party-scoped
 *       contract verbs allowed — a legitimate P19 counterparty system is
 *       never bricked; own-party authority stays the services' §5.5
 *       advisory check pre-Phase-5. Anything non-contract falls through to
 *       unregistered;</li>
 *   <li><b>Unregistered:</b> governed writes are mode-asymmetric BY SPEC —
 *       shadow ⇒ ALLOW_WITH_FLAG({@code AGENT_UNREGISTERED}), enforce ⇒
 *       DENY; every touch is rowed ("unknown agent knocking" is surfaced,
 *       never swallowed). Reads + {@code check_action} never transit the
 *       gateway at all (ungoverned by design).</li>
 * </ol>
 *
 * <p>§8.3 REGISTERED checks, in order — every deny reason is rowed into
 * {@code contract_enforcement_decisions} by the gateway (no parallel log):
 * suspended (zombie flow §6.4, {@code acknowledge_control} excepted) →
 * shadow scope → read-only → ACTIONS_PER_WINDOW budget (DB-backed atomic
 * counters, D12) → in-intervention flag → GATEWAY_ACTION sighting.
 * Sightings are recorded for every registered touch, allowed or denied —
 * a denied knock is still true liveness evidence (§6.4 "the sighting is
 * recorded (it is true)").
 *
 * <p>EXTENSION POINT(agent-10): TOKENS_PER_DAY / SAMPLING_PCT /
 * SCHEDULE_WINDOW budget kinds are schema-ready rows with NO evaluator here
 * (ADR-003 D12: only ACTIONS_PER_WINDOW gets a 6c evaluator) — published
 * obligations, never fabricated blocks; unblocks on first budget-of-kind
 * demand.
 *
 * <p>EXTENSION POINT(agent-13): enforcement rides the single
 * {@code substrate.contracts.enforcement.mode} ladder — never a second mode
 * key (ADR-003 D7); revisit on the first engagement needing independent
 * rollout cadence.
 */
@Primary
@Service
@ConditionalOnClass(ContractGateway.class)
public class AgentRegistryGateImpl implements AgentRegistryGate {

    /**
     * §8.3 #2 observation scope: the governed actions a SHADOW (and a
     * {@code read_only}) agent retains — observation/telemetry plus the M17
     * compliant act. P11's observation-only trial made structural.
     */
    static final Set<String> OBSERVATION_SCOPE = Set.of(
            GovernedActions.AGENT_HEARTBEAT,
            GovernedActions.AGENT_REPORT_ACTIVITY,
            GovernedActions.AGENT_ACKNOWLEDGE_CONTROL,
            GovernedActions.CONTRACT_REPORT_METRIC,
            GovernedActions.CONTRACT_ATTEST_COMMITMENT,
            GovernedActions.CONTRACT_RECORD_COMMITMENT_PROGRESS);

    /** §8.3 #4 default window when an ACTIONS_PER_WINDOW row omits one. */
    static final int DEFAULT_WINDOW_MINUTES = 60;

    private final AgentRegistryLookup registryLookup;
    private final ContractPartyRepository contractPartyRepository;
    private final AgentRateCounterRepository rateCounterRepository;
    private final AgentActionWindows actionWindows;
    private final AgentGatewaySignalService signalService;
    private final ContractProperties contractProperties;
    private final Clock clock;

    public AgentRegistryGateImpl(AgentRegistryLookup registryLookup,
                                 ContractPartyRepository contractPartyRepository,
                                 AgentRateCounterRepository rateCounterRepository,
                                 AgentActionWindows actionWindows,
                                 AgentGatewaySignalService signalService,
                                 ContractProperties contractProperties,
                                 Clock clock) {
        this.registryLookup = registryLookup;
        this.contractPartyRepository = contractPartyRepository;
        this.rateCounterRepository = rateCounterRepository;
        this.actionWindows = actionWindows;
        this.signalService = signalService;
        this.contractProperties = contractProperties;
        this.clock = clock;
    }

    /** The gateway-enforcement-live fact (closes the ADR-003 D17 6b→6c window). */
    @Override
    public boolean enforcesRegistry() {
        return true;
    }

    /**
     * Honest posture = bean-presence fact (D17) × the SINGLE shared ladder
     * (D7): only {@code enforce} yields {@link Posture#BLOCKING}. In
     * {@code shadow} this gate rows WOULD_DENY and proceeds; in {@code off}
     * {@link ContractGateway} short-circuits before this gate runs — a
     * GATEWAY_BLOCK ("next governed call is denied") claim in either mode
     * would be fabricated control (spec non-negotiable #1 / invariant I8).
     */
    @Override
    public Posture posture() {
        return switch (EnforcementMode.parse(
                contractProperties.getEnforcement().getMode())) {
            case OFF -> Posture.INERT;
            case SHADOW -> Posture.OBSERVE;
            case ENFORCE -> Posture.BLOCKING;
        };
    }

    @Override
    public Verdict evaluate(GovernedAction action, EnforcementMode mode) {
        String principalId = action.principal().id();
        Optional<AgentRegistryLookup.Snapshot> registered = registryLookup.resolve(principalId);
        if (registered.isPresent()) {
            return registeredPath(registered.get(), action);
        }
        // §8.2 step 2 — P19 carve-out: never brick a legitimate counterparty.
        if (action.actionType().startsWith("contract.")
                && !contractPartyRepository.findByMcpPrincipalId(principalId).isEmpty()) {
            return Verdict.Allow.clean();
        }
        // §8.2 step 3 — unregistered policy (mode-asymmetric by spec).
        String knock = "principal '" + principalId + "' has no agent registration"
                + " (agent_registrations.mcp_principal_id) and no contract-party binding —"
                + " unknown agent knocking (agents.md §8.2)";
        if (mode == EnforcementMode.ENFORCE) {
            return new Verdict.Deny(REASON_UNREGISTERED, knock);
        }
        return new Verdict.Allow(List.of(REASON_UNREGISTERED + ": " + knock));
    }

    // ------------------------------------------------------------ §8.3 ladder

    private Verdict registeredPath(AgentRegistryLookup.Snapshot agent, GovernedAction action) {
        String actionType = action.actionType();

        // 1. suspended — DENY + sighting + zombie flow; acknowledge_control
        //    is the one retained write (the compliant act).
        if (agent.mode() == AgentOperationalMode.PAUSED
                || agent.mode() == AgentOperationalMode.RETIRED) {
            if (GovernedActions.AGENT_ACKNOWLEDGE_CONTROL.equals(actionType)) {
                signalService.recordGatewayTouch(agent.registrationId(), actionType);
                return Verdict.Allow.clean();
            }
            signalService.recordSuspendedTouch(agent.registrationId(), actionType);
            return new Verdict.Deny(REASON_CONTROL_SUSPENDED,
                    "agent '" + agent.agentKey() + "' is " + agent.mode().wireValue()
                            + " — the registry flip is the control plane; the touch was sighted"
                            + " and the oversight chain signalled (agents.md §6.4)");
        }

        // 2. shadow scope — observation-only trial, structural (P11).
        if (agent.mode() == AgentOperationalMode.SHADOW
                && !OBSERVATION_SCOPE.contains(actionType)) {
            signalService.recordGatewayTouch(agent.registrationId(), actionType);
            return new Verdict.Deny(REASON_SHADOW_SCOPE,
                    "agent '" + agent.agentKey() + "' is in shadow mode — only observation-scope"
                            + " tools and reads are permitted until promotion (agents.md §8.3 #2)");
        }

        // 3. read-only safety default (P11) — as data, enforced here.
        if (agent.readOnly() && !OBSERVATION_SCOPE.contains(actionType)) {
            signalService.recordGatewayTouch(agent.registrationId(), actionType);
            return new Verdict.Deny(REASON_READ_ONLY,
                    "agent '" + agent.agentKey() + "' is registered read_only=true — mutating"
                            + " actions beyond the observation scope are denied (agents.md §8.3 #3)");
        }

        // 4. ACTIONS_PER_WINDOW budget — atomic DB counter in the check
        //    transaction (D12; the S12 lesson: never in-memory atomics).
        AgentRegistryLookup.ActionsBudget budget = agent.actionsBudget();
        if (budget != null && budget.limitValue() != null) {
            int count = incrementWindow(agent, budget);
            if (count > budget.limitValue()) {
                signalService.recordGatewayTouch(agent.registrationId(), actionType);
                return new Verdict.Deny(REASON_BUDGET_EXHAUSTED,
                        "agent '" + agent.agentKey() + "' exhausted its ACTIONS_PER_WINDOW budget"
                                + " (" + count + " > " + budget.limitValue().longValue()
                                + " per " + windowMinutes(budget) + "m window) — agents.md §8.3 #4");
            }
        }

        // 5. open intervention (LIVE) — ALLOW + flag: never brick the
        //    telemetry that diagnoses the problem.
        // 6. every allowed governed call records a GATEWAY_ACTION sighting.
        signalService.recordGatewayTouch(agent.registrationId(), actionType);
        if (agent.openInterventionCount() > 0) {
            return new Verdict.Allow(List.of(REASON_IN_INTERVENTION + ": agent '"
                    + agent.agentKey() + "' has " + agent.openInterventionCount()
                    + " open intervention(s) — proceeding flagged (agents.md §8.3 #5)"));
        }
        return Verdict.Allow.clean();
    }

    // -------------------------------------------------------------- counters

    /**
     * §5.5 counter discipline: atomic UPDATE in the CHECK transaction; zero
     * rows ⇒ the window row is provisioned at count 0 in REQUIRES_NEW
     * ({@link AgentActionWindows} — a racing insert's unique violation never
     * poisons the business transaction), then incremented. A
     * denied-and-rolled-back business write returns its increment; an
     * allowed one commits with it.
     */
    private int incrementWindow(AgentRegistryLookup.Snapshot agent,
                                AgentRegistryLookup.ActionsBudget budget) {
        Instant windowStart = currentWindowStart(budget);
        int updated = rateCounterRepository.incrementWindow(agent.registrationId(), windowStart);
        if (updated == 0) {
            actionWindows.ensureWindowRow(agent.registrationId(), windowStart);
            rateCounterRepository.incrementWindow(agent.registrationId(), windowStart);
        }
        return rateCounterRepository
                .findByAgentRegistrationIdAndWindowStart(agent.registrationId(), windowStart)
                .map(AgentRateCounter::getActionCount)
                .orElse(1);
    }

    private Instant currentWindowStart(AgentRegistryLookup.ActionsBudget budget) {
        long windowSeconds = Duration.ofMinutes(windowMinutes(budget)).toSeconds();
        long epoch = clock.instant().getEpochSecond();
        return Instant.ofEpochSecond((epoch / windowSeconds) * windowSeconds);
    }

    private static int windowMinutes(AgentRegistryLookup.ActionsBudget budget) {
        return budget.windowMinutes() != null && budget.windowMinutes() > 0
                ? budget.windowMinutes() : DEFAULT_WINDOW_MINUTES;
    }
}
