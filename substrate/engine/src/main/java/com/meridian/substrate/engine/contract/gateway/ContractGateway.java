package com.meridian.substrate.engine.contract.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.enums.GatewayDecision;
import com.meridian.substrate.common.domain.enums.PrincipalType;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.observability.EngineMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The enforcement gateway core — the single in-process PDP (engine-spec
 * contracts.md §4; ADR-002 D1/D4/D5/D9/D16/D17). Evaluated synchronously,
 * inside the caller's business transaction, before any governed state
 * mutation commits: in ENFORCE mode a DENY throws
 * {@link ContractDeniedException} → the transaction rolls back → enveloped
 * 409 {@code CONTRACT_DENIAL}; the decision row was already persisted in
 * REQUIRES_NEW and survives (§10.4).
 *
 * <p>§4.3 algorithm, verbatim posture:
 * <ul>
 *   <li>mode {@code off} — gateway skipped, {@code allow_unevaluated} counter
 *       only;</li>
 *   <li>guard match — per-domain {@link GuardCache} (1 indexed read on miss),
 *       filtered by action type and entity-type narrowing (null guard
 *       entity_type = wildcard);</li>
 *   <li>effect precedence DENY &gt; REQUIRE_ATTESTATION &gt; FLAG
 *       ({@code GuardEffect} declaration order);</li>
 *   <li>REQUIRE_ATTESTATION — pass iff a current-period attestation exists
 *       for the guard's commitment ({@link RequireAttestationGate}), else
 *       deny with reason {@code ATTESTATION_MISSING};</li>
 *   <li>§4.4 predicates via the shared {@link TinyDslEvaluator} over the
 *       shared {@link EvaluationDocuments} shape — an INDETERMINATE predicate
 *       never matches but is never silent (lifts the outcome to
 *       ALLOW_WITH_FLAG and is cited on the row);</li>
 *   <li>deny outcome — row in REQUIRES_NEW, then: shadow ⇒ {@code WOULD_DENY}
 *       + proceed flagged (ADR D4 — shadow never blocks); enforce ⇒
 *       {@code DENY} + throw;</li>
 *   <li>fail posture (ADR D5) — evaluation failure ⇒ deny path with reason
 *       {@code GATEWAY_ERROR} for AGENT/SERVICE (fail-closed), proceed
 *       flagged + {@code ALLOW_WITH_FLAG} row for HUMAN (fail-open).</li>
 * </ul>
 *
 * <p><b>ADR D16 built-in, non-removable guard:</b> {@code waive_breach} for
 * AGENT principals is denied by a hardcoded check ahead of the DB guard set —
 * it lives in code, not in {@code contract_clause_guards}, so no contract
 * authoring, decline, or cache state can remove it. It follows the §4.3 mode
 * ladder like every deny outcome (shadow rows {@code WOULD_DENY}); the
 * INVARIANT "agents may never waive" is mode-independent because
 * {@code BreachService.waive} hard-rejects every non-HUMAN actor regardless
 * of gateway mode ({@code WaiverHumanOnlyException}) — the gateway lane adds
 * the PDP audit trail and the §4.5 denial wire shape.
 *
 * <p>Decision-log level policy (ADR D17, §1 {@code matched-only} default)
 * lives here, mirroring the §4.3 pseudocode: FLAG/DENY/WOULD_DENY and any
 * ALLOW where ≥1 guard matched are always rowed; plain no-match ALLOWs are
 * rowed only at {@code level=all}, otherwise counted.
 *
 * <p>DECLINE is NEVER vocabulary here: a legitimate decline returns 200
 * upstream; this gateway's only decline involvement is the §6.2 review
 * boundary, whose non-null window arithmetic remains the named seam in
 * {@code DeclineAdvisoryFlags} (a follow-on 3c sub-chunk; flag-not-invent).
 */
@Service
public class ContractGateway {

    /**
     * Canonical governed action name for breach waiver (ADR D16). Follows the
     * {@code contract.invoke_decline} naming precedent; the MCP
     * {@code waive_breach} tool adapter and the REST admin endpoint PEP MUST
     * present waivers under this action type for the built-in guard to see
     * them.
     */
    public static final String ACTION_WAIVE_BREACH = "contract.waive_breach";

    /** §4.3 deny reason — matched REQUIRE_ATTESTATION guard without a current-period attestation. */
    public static final String REASON_ATTESTATION_MISSING = "ATTESTATION_MISSING";
    /** §4.3/ADR D5 reason — the gateway itself failed to evaluate. */
    public static final String REASON_GATEWAY_ERROR = "GATEWAY_ERROR";
    /** ADR D16 reason — same vocabulary as the service backstop's 403 code. */
    public static final String REASON_WAIVER_HUMAN_ONLY = "WAIVER_HUMAN_ONLY";
    /** Reason code for a plain matched DENY guard (description cited in the message). */
    public static final String REASON_DENY_GUARD_MATCHED = "DENY_GUARD_MATCHED";
    /** Flag-note prefix for §4.4 unevaluable predicates (never silent). */
    public static final String FLAG_UNEVALUABLE_PREDICATE = "UNEVALUABLE_PREDICATE_SKIPPED";
    /**
     * agents.md §8.4 built-in, non-removable lifecycle guard reason: agents
     * never operate lifecycle, including their own — defense in depth atop
     * the REST-only/human-only verb surface (invariant I11).
     */
    public static final String REASON_AGENT_LIFECYCLE_HUMAN_ONLY = "AGENT_LIFECYCLE_HUMAN_ONLY";

    /** agents.md §8.4: governed lifecycle actions carrying the built-in AGENT deny. */
    private static final Set<String> AGENT_LIFECYCLE_ACTIONS =
            Set.of(GovernedActions.AGENT_DEPLOY, GovernedActions.AGENT_PROMOTE);

    private static final String LEVEL_ALL = "all";

    private static final Logger log = LoggerFactory.getLogger(ContractGateway.class);

    private final GuardCache guardCache;
    private final ContractRepository contractRepository;
    private final RequireAttestationGate attestationGate;
    private final ContractMetricLeafResolver metricLeafResolver;
    private final EnforcementDecisionWriter decisionWriter;
    private final ContractProperties properties;
    private final ObjectMapper objectMapper;
    private final AgentRegistryGate agentRegistryGate;
    private final EngineMetrics metrics;

    /** Spring constructor (Stage 3 observability — metrics optional so partial slices wire). */
    @Autowired
    public ContractGateway(GuardCache guardCache,
                           ContractRepository contractRepository,
                           RequireAttestationGate attestationGate,
                           ContractMetricLeafResolver metricLeafResolver,
                           EnforcementDecisionWriter decisionWriter,
                           ContractProperties properties,
                           ObjectMapper objectMapper,
                           AgentRegistryGate agentRegistryGate,
                           ObjectProvider<EngineMetrics> metrics) {
        this(guardCache, contractRepository, attestationGate, metricLeafResolver, decisionWriter,
                properties, objectMapper, agentRegistryGate,
                metrics.getIfAvailable(EngineMetrics::noop));
    }

    public ContractGateway(GuardCache guardCache,
                           ContractRepository contractRepository,
                           RequireAttestationGate attestationGate,
                           ContractMetricLeafResolver metricLeafResolver,
                           EnforcementDecisionWriter decisionWriter,
                           ContractProperties properties,
                           ObjectMapper objectMapper,
                           AgentRegistryGate agentRegistryGate,
                           EngineMetrics metrics) {
        this.guardCache = guardCache;
        this.contractRepository = contractRepository;
        this.attestationGate = attestationGate;
        this.metricLeafResolver = metricLeafResolver;
        this.decisionWriter = decisionWriter;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.agentRegistryGate = agentRegistryGate;
        this.metrics = metrics;
    }

    /** Pre-Stage-3 signature, kept for hand-constructed gateways (tests): metrics → sink. */
    public ContractGateway(GuardCache guardCache,
                           ContractRepository contractRepository,
                           RequireAttestationGate attestationGate,
                           ContractMetricLeafResolver metricLeafResolver,
                           EnforcementDecisionWriter decisionWriter,
                           ContractProperties properties,
                           ObjectMapper objectMapper,
                           AgentRegistryGate agentRegistryGate) {
        this(guardCache, contractRepository, attestationGate, metricLeafResolver, decisionWriter,
                properties, objectMapper, agentRegistryGate, EngineMetrics.noop());
    }

    /**
     * §4.1: evaluates one governed action; returns {@link Decision.Allow} or
     * throws {@link ContractDeniedException} (enforce mode deny). Latency is
     * measured around this method only (§10.4) and stamped on rowed
     * decisions.
     */
    public Decision evaluate(GovernedAction action) {
        long started = System.nanoTime();
        EnforcementMode mode = EnforcementMode.parse(properties.getEnforcement().getMode());
        if (mode == EnforcementMode.OFF) {
            decisionWriter.countAllowUnevaluated();
            metrics.gatewayEvaluation(EngineMetrics.DECISION_ALLOW_UNEVALUATED, mode.wire(), started);
            return Decision.Allow.unflagged();
        }
        try {
            return evaluateGuards(action, mode, started);
        } catch (ContractDeniedException denial) {
            throw denial;                            // a governance outcome, not an evaluator fault
        } catch (RuntimeException evaluationFailure) {
            return failPosture(action, mode, started, evaluationFailure);
        }
    }

    // ------------------------------------------------------------- evaluation

    private Decision evaluateGuards(GovernedAction action, EnforcementMode mode, long started) {
        // ADR D16 built-in non-removable guard — ahead of (and independent of)
        // the DB guard set: holds even if the guard store is unreachable.
        if (ACTION_WAIVE_BREACH.equals(action.actionType())
                && action.principal().type() == PrincipalType.AGENT) {
            return denyOutcome(action, mode, started, List.of(), List.of(), List.of(),
                    REASON_WAIVER_HUMAN_ONLY,
                    "Action '" + ACTION_WAIVE_BREACH + "' denied for AGENT principal '"
                            + action.principal().id()
                            + "': breach waiver is human-only (ADR D16 built-in non-removable"
                            + " guard; agents may attest, acknowledge and propose remediation,"
                            + " never waive)");
        }

        // agents.md §8 — the AGENT-principal lane, BEFORE clause-guard
        // evaluation; both checks are built-in and non-removable (D16 idiom).
        List<String> gateFlags = List.of();
        if (action.principal().type() == PrincipalType.AGENT) {
            // §8.4: agents never operate lifecycle, including their own.
            if (AGENT_LIFECYCLE_ACTIONS.contains(action.actionType())) {
                return agentGateDenyOutcome(action, mode, started,
                        REASON_AGENT_LIFECYCLE_HUMAN_ONLY,
                        "Action '" + action.actionType() + "' denied for AGENT principal '"
                                + action.principal().id() + "': agent lifecycle is REST-only and"
                                + " human-only (agents.md §8.4 built-in non-removable guard;"
                                + " invariant I11 — agents never self-administer)");
            }
            // §8.1/§8.2: registry-first → contract-party carve-out →
            // unregistered policy. A gate fault propagates to the ADR-002 D5
            // fail posture (AGENT = fail-closed).
            switch (agentRegistryGate.evaluate(action, mode)) {
                case AgentRegistryGate.Verdict.Deny deny -> {
                    return agentGateDenyOutcome(action, mode, started, deny.reason(),
                            "Action '" + action.actionType() + "' denied for AGENT principal '"
                                    + action.principal().id() + "' (reason " + deny.reason()
                                    + "): " + deny.detail());
                }
                case AgentRegistryGate.Verdict.Allow allow -> gateFlags = allow.flagNotes();
            }
        }

        List<GuardView> guards = guardCache.activeGuards(action.domainId(), action.actionType())
                .stream()
                .filter(g -> matchesEntityType(g, action.target()))
                .toList();
        if (guards.isEmpty()) {
            if (!gateFlags.isEmpty()) {
                // Registry-gate flags are ALWAYS rowed (ALLOW_WITH_FLAG, ADR
                // D17) even with an empty guard set — "unknown agent
                // knocking" / in-intervention telemetry is never silent.
                String flagReason = String.join("; ", gateFlags);
                decisionWriter.row(action, GatewayDecision.ALLOW_WITH_FLAG, List.of(),
                        List.of(), flagReason, mode, latencyMs(started));
                metrics.gatewayEvaluation(GatewayDecision.ALLOW_WITH_FLAG.name(), mode.wire(), started);
                return new Decision.Allow(true, List.of(), flagReason);
            }
            decisionWriter.countAllowNoGuard();
            if (rowEverything()) {
                decisionWriter.row(action, GatewayDecision.ALLOW, List.of(), List.of(),
                        null, mode, latencyMs(started));
            }
            metrics.gatewayEvaluation(GatewayDecision.ALLOW.name(), mode.wire(), started);
            return Decision.Allow.unflagged();
        }

        JsonNode document = EvaluationDocuments.of(objectMapper, action.payloadSummary(),
                action.target() == null ? null : action.target().entityType(),
                action.target() == null ? null : action.target().entityId());

        List<GuardView> matched = new ArrayList<>();
        List<GuardView> indeterminate = new ArrayList<>();
        for (GuardView guard : guards) {
            if (guard.predicate() == null || guard.predicate().isNull()) {
                matched.add(guard);                  // predicate-null guard always matches (§4.3)
                continue;
            }
            switch (TinyDslEvaluator.evaluate(guard.predicate(), document,
                    metricLeafResolver.forContract(guard.contractId()))) {
                case MATCH -> matched.add(guard);
                case INDETERMINATE -> indeterminate.add(guard);  // skipped, flagged below (§4.4)
                case NO_MATCH -> { /* no contribution */ }
            }
        }
        // Effect precedence DENY > REQUIRE_ATTESTATION > FLAG (§4.3 — enum order).
        matched.sort(Comparator.comparingInt(g -> g.effect().ordinal()));

        List<GuardView> denying = new ArrayList<>();
        String denyReason = null;
        List<String> flagNotes = new ArrayList<>(gateFlags);   // §8.3 gate flags cited first
        for (GuardView guard : matched) {
            switch (guard.effect()) {
                case DENY -> {
                    denying.add(guard);
                    if (denyReason == null) {
                        denyReason = REASON_DENY_GUARD_MATCHED;
                    }
                }
                case REQUIRE_ATTESTATION -> {
                    Optional<String> missing = attestationGate.missingReason(guard.commitmentId());
                    if (missing.isPresent()) {
                        denying.add(guard);
                        if (denyReason == null) {
                            denyReason = REASON_ATTESTATION_MISSING;
                        }
                    }
                    // satisfied ⇒ pass: still a MATCHED guard (rowed as ALLOW below)
                }
                case FLAG -> flagNotes.add("FLAG: " + guard.description());
            }
        }
        for (GuardView guard : indeterminate) {
            flagNotes.add(FLAG_UNEVALUABLE_PREDICATE + ": '" + guard.description() + "'");
        }

        if (!denying.isEmpty()) {
            List<UUID> clauseIds = denying.stream().map(GuardView::id).toList();
            List<UUID> contractIds = denying.stream().map(GuardView::contractId).distinct().toList();
            List<String> contractKeys = contractKeys(contractIds);
            return denyOutcome(action, mode, started, clauseIds, contractIds, contractKeys,
                    denyReason, denyMessage(action, denying, contractKeys, denyReason));
        }

        List<UUID> matchedClauseIds = matched.stream().map(GuardView::id).toList();
        if (!flagNotes.isEmpty()) {
            // ALLOW_WITH_FLAG — always rowed (ADR D17); indeterminate guards are
            // cited on the row so the §4.4 skip is auditable, never silent.
            String flagReason = String.join("; ", flagNotes);
            decisionWriter.row(action, GatewayDecision.ALLOW_WITH_FLAG,
                    citedClauseIds(matched, indeterminate),
                    citedContractIds(matched, indeterminate),
                    flagReason, mode, latencyMs(started));
            metrics.gatewayEvaluation(GatewayDecision.ALLOW_WITH_FLAG.name(), mode.wire(), started);
            return new Decision.Allow(true, matchedClauseIds, flagReason);
        }
        if (!matched.isEmpty()) {
            // Plain ALLOW where ≥1 guard matched (satisfied REQUIRE_ATTESTATION)
            // — rowed at BOTH levels (D17 matched-only includes it).
            decisionWriter.row(action, GatewayDecision.ALLOW, matchedClauseIds,
                    matched.stream().map(GuardView::contractId).distinct().toList(),
                    null, mode, latencyMs(started));
            metrics.gatewayEvaluation(GatewayDecision.ALLOW.name(), mode.wire(), started);
            return new Decision.Allow(false, matchedClauseIds, null);
        }
        // Guards exist but none matched: plain ALLOW — row only at level=all.
        if (rowEverything()) {
            decisionWriter.row(action, GatewayDecision.ALLOW, List.of(), List.of(),
                    null, mode, latencyMs(started));
        } else {
            decisionWriter.countAllowUnrowed();
        }
        metrics.gatewayEvaluation(GatewayDecision.ALLOW.name(), mode.wire(), started);
        return Decision.Allow.unflagged();
    }

    /**
     * The §4.3 deny tail: row in REQUIRES_NEW first (the row must exist before
     * any throw — §10.4), then shadow ⇒ proceed flagged, enforce ⇒ throw.
     */
    private Decision denyOutcome(GovernedAction action, EnforcementMode mode, long started,
                                 List<UUID> clauseIds, List<UUID> contractIds,
                                 List<String> contractKeys, String reason, String humanSummary) {
        GatewayDecision rowed = mode == EnforcementMode.ENFORCE
                ? GatewayDecision.DENY : GatewayDecision.WOULD_DENY;
        UUID decisionId = decisionWriter.row(action, rowed, clauseIds, contractIds,
                reason, mode, latencyMs(started));
        metrics.gatewayEvaluation(rowed.name(), mode.wire(), started);
        if (action.principal().type() == PrincipalType.AGENT) {
            metrics.agentGatewayDenial(reason, mode.wire());
        }
        if (mode == EnforcementMode.SHADOW) {
            log.info("WOULD_DENY (shadow, proceeding) [action={}, principal={}/{}, reason={},"
                            + " decisionId={}]", action.actionType(),
                    action.principal().type(), action.principal().id(), reason, decisionId);
            return new Decision.Allow(true, clauseIds, reason);
        }
        throw new ContractDeniedException(
                new Decision.Deny(clauseIds, contractKeys, reason, decisionId),
                humanSummary + " [decisionId " + decisionId + "]");
    }

    /**
     * The agents.md §8.5 deny tail: identical mechanics to
     * {@link #denyOutcome} (row in REQUIRES_NEW, shadow proceeds flagged,
     * enforce throws) but raising {@link AgentGateDeniedException} so the
     * wire code is {@code AGENT_GATE_DENIAL} — same 409/isError shapes,
     * additive code (no guard citations: the registry gate is built-in, not
     * a clause).
     */
    private Decision agentGateDenyOutcome(GovernedAction action, EnforcementMode mode,
                                          long started, String reason, String humanSummary) {
        GatewayDecision rowed = mode == EnforcementMode.ENFORCE
                ? GatewayDecision.DENY : GatewayDecision.WOULD_DENY;
        UUID decisionId = decisionWriter.row(action, rowed, List.of(), List.of(),
                reason, mode, latencyMs(started));
        metrics.gatewayEvaluation(rowed.name(), mode.wire(), started);
        metrics.agentGatewayDenial(reason, mode.wire());   // §8.5 lane is AGENT by construction
        if (mode == EnforcementMode.SHADOW) {
            log.info("WOULD_DENY (agent gate, shadow, proceeding) [action={}, principal={},"
                            + " reason={}, decisionId={}]", action.actionType(),
                    action.principal().id(), reason, decisionId);
            return new Decision.Allow(true, List.of(), reason);
        }
        throw new AgentGateDeniedException(
                new Decision.Deny(List.of(), List.of(), reason, decisionId),
                humanSummary + " [decisionId " + decisionId + "]");
    }

    /**
     * ADR D5 fail posture: HUMAN proceeds flagged ({@code ALLOW_WITH_FLAG},
     * reason GATEWAY_ERROR — humans are never locked out by an engine fault);
     * AGENT/SERVICE take the deny path with reason GATEWAY_ERROR
     * (fail-closed; shadow still proceeds — shadow never blocks, ADR D4).
     * A failure of the decision-row write itself is logged, never allowed to
     * mask the posture.
     */
    private Decision failPosture(GovernedAction action, EnforcementMode mode, long started,
                                 RuntimeException failure) {
        log.error("Gateway evaluation failed [action={}, principal={}/{}] — applying ADR D5"
                        + " fail posture", action.actionType(), action.principal().type(),
                action.principal().id(), failure);
        if (action.principal().type() == PrincipalType.HUMAN) {
            try {
                decisionWriter.row(action, GatewayDecision.ALLOW_WITH_FLAG, List.of(),
                        List.of(), REASON_GATEWAY_ERROR, mode, latencyMs(started));
            } catch (RuntimeException rowFailure) {
                log.error("Could not row the HUMAN fail-open ALLOW_WITH_FLAG decision", rowFailure);
            }
            metrics.gatewayEvaluation(GatewayDecision.ALLOW_WITH_FLAG.name(), mode.wire(), started);
            return new Decision.Allow(true, List.of(), REASON_GATEWAY_ERROR);
        }
        GatewayDecision rowed = mode == EnforcementMode.ENFORCE
                ? GatewayDecision.DENY : GatewayDecision.WOULD_DENY;
        UUID decisionId = null;
        try {
            decisionId = decisionWriter.row(action, rowed, List.of(), List.of(),
                    REASON_GATEWAY_ERROR, mode, latencyMs(started));
        } catch (RuntimeException rowFailure) {
            log.error("Could not row the fail-closed {} decision — denying without a row id",
                    rowed, rowFailure);
        }
        metrics.gatewayEvaluation(rowed.name(), mode.wire(), started);
        if (action.principal().type() == PrincipalType.AGENT) {
            metrics.agentGatewayDenial(REASON_GATEWAY_ERROR, mode.wire());
        }
        if (mode == EnforcementMode.SHADOW) {
            return new Decision.Allow(true, List.of(), REASON_GATEWAY_ERROR);
        }
        throw new ContractDeniedException(
                new Decision.Deny(List.of(), List.of(), REASON_GATEWAY_ERROR, decisionId),
                "Action '" + action.actionType() + "' denied: gateway evaluation failed and"
                        + " principal type " + action.principal().type()
                        + " is fail-closed (ADR D5)"
                        + (decisionId != null ? " [decisionId " + decisionId + "]" : ""),
                failure);
    }

    // ------------------------------------------------------------------ helpers

    /** §4.3 narrowing: null guard entity_type = wildcard; else case-insensitive equality. */
    private static boolean matchesEntityType(GuardView guard, TargetRef target) {
        if (guard.entityType() == null) {
            return true;
        }
        return target != null && guard.entityType().equalsIgnoreCase(target.entityType());
    }

    /** ADR D17 level: {@code all} rows everything; default matched-only. */
    private boolean rowEverything() {
        String level = properties.getDecisionLog().getLevel();
        return level != null && LEVEL_ALL.equalsIgnoreCase(level.trim());
    }

    private List<String> contractKeys(List<UUID> contractIds) {
        return contractIds.stream()
                .map(id -> contractRepository.findById(id)
                        .map(Contract::getContractKey)
                        .orElse(id.toString()))
                .toList();
    }

    /** §4.5 human summary: contract key(s) + clause descriptions (+ decisionId appended later). */
    private static String denyMessage(GovernedAction action, List<GuardView> denying,
                                      List<String> contractKeys, String reason) {
        String clauses = denying.stream()
                .map(g -> "'" + g.description() + "'")
                .reduce((a, b) -> a + ", " + b).orElse("");
        return "Action '" + action.actionType() + "' denied by contract "
                + contractKeys + " clause(s) " + clauses + " (reason " + reason + ")";
    }

    private static List<UUID> citedClauseIds(List<GuardView> matched, List<GuardView> indeterminate) {
        Set<UUID> ids = new LinkedHashSet<>();
        Stream.concat(matched.stream(), indeterminate.stream()).map(GuardView::id).forEach(ids::add);
        return List.copyOf(ids);
    }

    private static List<UUID> citedContractIds(List<GuardView> matched, List<GuardView> indeterminate) {
        Set<UUID> ids = new LinkedHashSet<>();
        Stream.concat(matched.stream(), indeterminate.stream())
                .map(GuardView::contractId).forEach(ids::add);
        return List.copyOf(ids);
    }

    private static int latencyMs(long startedNanos) {
        return (int) ((System.nanoTime() - startedNanos) / 1_000_000);
    }
}
