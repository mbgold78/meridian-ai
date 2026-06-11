package com.meridian.substrate.engine.contract.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractEnforcementDecision;
import com.meridian.substrate.common.domain.enums.BreachStatus;
import com.meridian.substrate.common.domain.enums.ContractActorType;
import com.meridian.substrate.common.domain.enums.GatewayDecision;
import com.meridian.substrate.common.domain.enums.PrincipalType;
import com.meridian.substrate.common.repo.ContractBreachRepository;
import com.meridian.substrate.common.repo.ContractEnforcementDecisionRepository;
import com.meridian.substrate.engine.contract.ContractProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The §6 decline-time advisory lanes of the enforcement gateway, shipped with
 * chunk 3b-2 (engine-spec contracts.md §6.1–§6.3; ADR-002 D6):
 *
 * <ul>
 *   <li><b>§6.2 review boundary, null lane:</b> for
 *       {@code contract_type=federation-invitation} with
 *       {@code review_boundary IS NULL} (the default — no homepoc doc defines
 *       boundary semantics, spec §15 #1) the invocation is ALLOWED and a
 *       decision row {@code ALLOW_WITH_FLAG / REVIEW_BOUNDARY_UNCONFIGURED}
 *       is appended — flag-not-invent at runtime, never silent.</li>
 *   <li><b>§6.3 open breaches:</b> open breaches NEVER block decline; the
 *       decision is rowed {@code ALLOW_WITH_FLAG / OPEN_BREACHES_AT_DECLINE}
 *       (the escape-hatch product question for Mike, spec §15 #5). Breaches
 *       survive decline and keep their human lifecycle.</li>
 * </ul>
 *
 * <p>GATEWAY SEAM (3c): the non-null review-boundary window arithmetic
 * (§6.2 — outside {@code windowDays} of a cadence boundary ⇒ gateway DENY in
 * enforce mode / WOULD_DENY+proceed in shadow) requires the
 * {@code ContractGateway}/{@code ContractDeniedException} machinery and lands
 * with the 3c gateway chunk, which absorbs this class into its
 * {@code EnforcementDecisionWriter}. Until then a non-null boundary is
 * surfaced with a loud WARN (never silent, never invented arithmetic) — both
 * DemoPack vectors seed {@code review_boundary = null}.
 *
 * <p>Rows are written in the SAME decline transaction: these are allow-path
 * rows — only DENY rows need the §10.4 {@code REQUIRES_NEW} posture (3c).
 * Per ADR D17, {@code ALLOW_WITH_FLAG} rows are always rowed regardless of
 * {@code decision-log.level}. {@code latency_ms} stays null: it is "measured
 * around evaluate only" (§10.4) and no gateway evaluation runs in 3b.
 */
@Service
public class DeclineAdvisoryFlags {

    private static final Logger log = LoggerFactory.getLogger(DeclineAdvisoryFlags.class);

    /** §6.1 governed action name for both decline lanes (the 3c PEP evaluates the same name). */
    public static final String ACTION_TYPE =
            com.meridian.substrate.engine.contract.gateway.GovernedActions.CONTRACT_INVOKE_DECLINE;
    /** §6.2 reason code — federation contract declined with no configured boundary. */
    public static final String REVIEW_BOUNDARY_UNCONFIGURED = "REVIEW_BOUNDARY_UNCONFIGURED";
    /** §6.3 reason code — decline proceeded over open breaches (they survive). */
    public static final String OPEN_BREACHES_AT_DECLINE = "OPEN_BREACHES_AT_DECLINE";

    /** §1 allowlist value with type-specific behavior (ADR D15). */
    private static final String FEDERATION_INVITATION = "federation-invitation";

    private static final Set<BreachStatus> OPEN_BREACH_STATUSES = EnumSet.allOf(BreachStatus.class)
            .stream().filter(BreachStatus::isOpen)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(BreachStatus.class)));

    private final ContractEnforcementDecisionRepository decisionRepository;
    private final ContractBreachRepository breachRepository;
    private final ContractProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public DeclineAdvisoryFlags(ContractEnforcementDecisionRepository decisionRepository,
                                ContractBreachRepository breachRepository,
                                ContractProperties properties,
                                Clock clock,
                                ObjectMapper objectMapper) {
        this.decisionRepository = decisionRepository;
        this.breachRepository = breachRepository;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /**
     * Evaluates both §6 advisory lanes for one {@code invoke_decline}; called
     * by {@code ContractRegistryService.invokeDecline} inside the decline
     * transaction, after trigger validation, before the transition (§6.1
     * ordering). Never throws on the governance path and never blocks the
     * decline — decline is a legitimate first-class terminal resolution.
     */
    public void onInvokeDecline(Contract contract, ContractActor actor) {
        if (FEDERATION_INVITATION.equalsIgnoreCase(contract.getContractType())) {
            if (contract.getReviewBoundary() == null || contract.getReviewBoundary().isNull()) {
                row(contract, actor, REVIEW_BOUNDARY_UNCONFIGURED);
            } else {
                // GATEWAY SEAM (3c): §6.2 window arithmetic is deliberately NOT
                // implemented here — surfacing without inventing enforcement.
                log.warn("invoke_decline on federation contract [key={}] with a NON-NULL"
                                + " review_boundary: the §6.2 window check lands with the 3c"
                                + " gateway — boundary NOT enforced in 3b (flag-not-invent)",
                        contract.getContractKey());
            }
        }

        var openBreaches = breachRepository.findByContractIdAndStatusIn(
                contract.getId(), OPEN_BREACH_STATUSES);
        if (!openBreaches.isEmpty()) {
            row(contract, actor, OPEN_BREACHES_AT_DECLINE);
            log.info("invoke_decline proceeding over {} open breach(es) [key={}] — flagged,"
                            + " never blocked; breaches survive decline (§6.3)",
                    openBreaches.size(), contract.getContractKey());
        }
    }

    private void row(Contract contract, ContractActor actor, String reason) {
        ContractEnforcementDecision decision = new ContractEnforcementDecision();
        decision.setDomainId(contract.getDomainId());
        decision.setActionType(ACTION_TYPE);
        decision.setActorType(principalType(actor.type()));
        decision.setActorId(actor.id());
        decision.setDecision(GatewayDecision.ALLOW_WITH_FLAG);
        ArrayNode contractIds = objectMapper.createArrayNode();
        contractIds.add(contract.getId().toString());
        decision.setContractIds(contractIds);
        decision.setReason(reason);
        decision.setMode(properties.getEnforcement().getMode());
        decision.setDecidedAt(clock.instant());
        decisionRepository.save(decision);
    }

    /**
     * §2.1 actor-type → §4.1 principal-type projection for the decision row:
     * engine-internal actors (MONITOR/SYSTEM) row as SERVICE — the §4.1
     * principal vocabulary has exactly three values and SERVICE is the
     * non-human, non-agent machine identity ({@code 'substrate-svc'} family).
     */
    private static PrincipalType principalType(ContractActorType actorType) {
        return switch (actorType) {
            case HUMAN -> PrincipalType.HUMAN;
            case AGENT -> PrincipalType.AGENT;
            case SERVICE, MONITOR, SYSTEM -> PrincipalType.SERVICE;
        };
    }
}
