package com.meridian.substrate.engine.contract.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractClauseGuard;
import com.meridian.substrate.common.domain.enums.GuardEffect;
import com.meridian.substrate.common.repo.ContractClauseGuardRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code check_action} ADVISORY dry-run of the PDP (engine-spec contracts.md
 * §8.2): evaluates the active-guard set for an action exactly like the §4.3
 * gateway WOULD, and returns {@code {verdict, matchedClauses[], explanation}}
 * — without enforcement, without mode handling, and crucially WITHOUT rowing
 * a decision: this service has no dependency on
 * {@code ContractEnforcementDecisionRepository} by construction; calls are a
 * metrics counter only ({@link #advisoryCheckCount()}).
 *
 * <p>Cooperative preflight — the real check still happens at write time (the
 * {@link ContractGateway}). §14.5 verdict parity is structural: both sides
 * share {@link TinyDslEvaluator}, the {@link EvaluationDocuments} shape, the
 * {@link RequireAttestationGate} REQUIRE_ATTESTATION semantics and the
 * {@link ContractMetricLeafResolver} metric leaf — single-sourced so they can
 * never drift. Verdict vocabulary per §13 (3c acceptance):
 * {@code ALLOW | ALLOW_WITH_FLAG | BLOCK} — advisory reports the would-be
 * enforce-mode outcome regardless of the configured enforcement mode, which
 * is exactly what makes it useful in shadow.
 *
 * <p>§4.4 never-silent rule: a guard whose predicate is INDETERMINATE
 * (unknown node, unresolvable field, missing metric) is skipped for matching
 * but surfaces in the explanation and lifts the verdict to at least
 * {@code ALLOW_WITH_FLAG}.
 *
 * <p>v1 scoping note: {@code check_action} carries no {@code domain_id}
 * (§8.2) and guards are queried by {@code (action_type, active)} — v1 deploys
 * are per-engagement single-domain (plan §8), so the active-guard set IS the
 * domain's. Cross-engagement scoping is spec §15 #10 territory.
 */
@Service
public class AdvisoryCheckService {

    /** §8.2 verdicts; BLOCK is the §13 3c acceptance spelling of a would-be DENY. */
    public static final String VERDICT_ALLOW = "ALLOW";
    public static final String VERDICT_ALLOW_WITH_FLAG = "ALLOW_WITH_FLAG";
    public static final String VERDICT_BLOCK = "BLOCK";

    /** One matched guard, cited by immutable ids + description (§4.5 citation posture). */
    public record MatchedClause(UUID clauseId, UUID contractId, String contractKey,
                                GuardEffect effect, String description) {
    }

    /** §8.2 result shape: {@code {verdict, matchedClauses[], explanation}}. */
    public record CheckActionResult(String verdict, List<MatchedClause> matchedClauses,
                                    String explanation) {
    }

    private final ContractClauseGuardRepository guardRepository;
    private final ContractRepository contractRepository;
    private final RequireAttestationGate attestationGate;
    private final ContractMetricLeafResolver metricLeafResolver;
    private final ObjectMapper objectMapper;

    /** §8.2 "never rows a decision (metrics counter only)" — the v1 counter. */
    private final AtomicLong advisoryCheckCount = new AtomicLong();

    public AdvisoryCheckService(ContractClauseGuardRepository guardRepository,
                                ContractRepository contractRepository,
                                RequireAttestationGate attestationGate,
                                ContractMetricLeafResolver metricLeafResolver,
                                ObjectMapper objectMapper) {
        this.guardRepository = guardRepository;
        this.contractRepository = contractRepository;
        this.attestationGate = attestationGate;
        this.metricLeafResolver = metricLeafResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * Advisory evaluation of {@code action_type} against the active-guard set
     * (§4.3 algorithm, effect precedence DENY &gt; REQUIRE_ATTESTATION &gt;
     * FLAG), with {@code entity_type} narrowing (null guard entity_type =
     * wildcard) and §4.4 predicate semantics.
     */
    @Transactional(readOnly = true)
    public CheckActionResult check(String actionType, String targetEntityType,
                                   String targetEntityId, JsonNode payload) {
        advisoryCheckCount.incrementAndGet();
        if (actionType == null || actionType.isBlank()) {
            throw new IllegalArgumentException("action_type is required");
        }

        List<ContractClauseGuard> guards = guardRepository
                .findByActionTypeAndActiveTrue(actionType.trim()).stream()
                .filter(g -> g.getEntityType() == null
                        || g.getEntityType().equalsIgnoreCase(targetEntityType))
                .toList();
        if (guards.isEmpty()) {
            return new CheckActionResult(VERDICT_ALLOW, List.of(),
                    "No active clause guards govern action '" + actionType + "'"
                            + (targetEntityType != null ? " on entity type '" + targetEntityType + "'" : "")
                            + " — the gateway would ALLOW without matching any clause.");
        }

        JsonNode document = EvaluationDocuments.of(
                objectMapper, payload, targetEntityType, targetEntityId);
        List<ContractClauseGuard> matched = new ArrayList<>();
        List<ContractClauseGuard> indeterminate = new ArrayList<>();
        for (ContractClauseGuard guard : guards) {
            if (guard.getPredicate() == null || guard.getPredicate().isNull()) {
                matched.add(guard);                  // predicate-null guard always matches (§4.3)
                continue;
            }
            TinyDslEvaluator.Result r = TinyDslEvaluator.evaluate(
                    guard.getPredicate(), document,
                    metricLeafResolver.forContract(guard.getContractId()));
            switch (r) {
                case MATCH -> matched.add(guard);
                case INDETERMINATE -> indeterminate.add(guard);  // skipped, surfaced below — never silent
                case NO_MATCH -> { /* no contribution */ }
            }
        }

        // Effect precedence DENY > REQUIRE_ATTESTATION > FLAG (§4.3).
        matched.sort(Comparator.comparingInt(g -> precedence(g.getEffect())));
        List<MatchedClause> clauses = matched.stream().map(this::cite).toList();
        StringBuilder explanation = new StringBuilder();

        String verdict = VERDICT_ALLOW;
        for (ContractClauseGuard guard : matched) {
            switch (guard.getEffect()) {
                case DENY -> {
                    verdict = VERDICT_BLOCK;
                    appendSentence(explanation, "DENY guard matched: " + describe(guard)
                            + " — the gateway would deny (enforce) / row WOULD_DENY (shadow).");
                }
                case REQUIRE_ATTESTATION -> {
                    Optional<String> missing = attestationGate.missingReason(guard.getCommitmentId());
                    if (missing.isPresent()) {
                        if (!VERDICT_BLOCK.equals(verdict)) {
                            verdict = VERDICT_BLOCK;
                        }
                        appendSentence(explanation, "REQUIRE_ATTESTATION guard "
                                + describe(guard) + " is unsatisfied (" + missing.get()
                                + ") — reason ATTESTATION_MISSING.");
                    } else {
                        appendSentence(explanation, "REQUIRE_ATTESTATION guard "
                                + describe(guard) + " is satisfied by a current-period attestation.");
                    }
                }
                case FLAG -> {
                    if (VERDICT_ALLOW.equals(verdict)) {
                        verdict = VERDICT_ALLOW_WITH_FLAG;
                    }
                    appendSentence(explanation, "FLAG guard matched: " + describe(guard) + ".");
                }
            }
        }
        if (!indeterminate.isEmpty()) {
            if (VERDICT_ALLOW.equals(verdict)) {
                verdict = VERDICT_ALLOW_WITH_FLAG;   // §4.4 — an unevaluable predicate is never silent
            }
            for (ContractClauseGuard guard : indeterminate) {
                appendSentence(explanation, "Predicate of guard " + describe(guard)
                        + " is INDETERMINATE (unknown node or unresolvable field/metric)"
                        + " — skipped for matching, flagged per §4.4.");
            }
        }
        if (explanation.isEmpty()) {
            appendSentence(explanation, "Active guards exist for action '" + actionType
                    + "' but none matched this payload/target.");
        }
        return new CheckActionResult(verdict, clauses, explanation.toString().trim());
    }

    /** Total advisory checks served (the §8.2 metrics-counter-only contract). */
    public long advisoryCheckCount() {
        return advisoryCheckCount.get();
    }

    // ------------------------------------------------------------------ helpers

    private MatchedClause cite(ContractClauseGuard guard) {
        String contractKey = contractRepository.findById(guard.getContractId())
                .map(Contract::getContractKey).orElse(null);
        return new MatchedClause(guard.getId(), guard.getContractId(), contractKey,
                guard.getEffect(), guard.getDescription());
    }

    private String describe(ContractClauseGuard guard) {
        String contractKey = contractRepository.findById(guard.getContractId())
                .map(Contract::getContractKey).orElse(String.valueOf(guard.getContractId()));
        return "'" + guard.getDescription() + "' (contract " + contractKey + ")";
    }

    private static int precedence(GuardEffect effect) {
        return switch (effect) {
            case DENY -> 0;
            case REQUIRE_ATTESTATION -> 1;
            case FLAG -> 2;
        };
    }

    private static void appendSentence(StringBuilder sb, String sentence) {
        if (!sb.isEmpty()) {
            sb.append(' ');
        }
        sb.append(sentence);
    }
}
