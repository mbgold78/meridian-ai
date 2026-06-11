package com.meridian.substrate.engine.contract.registry;

import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractDeclineTrigger;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.ContractPartyResponse;
import com.meridian.substrate.common.domain.enums.BreachStatus;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.MonitorRunOutcome;
import com.meridian.substrate.common.domain.enums.MonitorStatus;
import com.meridian.substrate.common.repo.ContractBreachRepository;
import com.meridian.substrate.common.repo.ContractDeclineTriggerRepository;
import com.meridian.substrate.common.repo.ContractEventRepository;
import com.meridian.substrate.common.repo.ContractMonitorRepository;
import com.meridian.substrate.common.repo.ContractPartyRepository;
import com.meridian.substrate.common.repo.ContractPartyResponseRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.cascade.CascadeQueryService;
import com.meridian.substrate.engine.contract.read.SignalsResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * §11.2 {@code GET /contracts/signals?domainId} — computed at READ TIME from
 * existing tables, no signal table in v1 (engine-spec contracts.md §9
 * {@code ContractSignalService}). Severs fixture stat seam S6: the
 * PortfolioContextBand / Operating-Partner strip swaps its
 * {@code pendingFederationInvitations} and {@code coordinationPatternsThisWeek}
 * constants to this endpoint.
 *
 * <p>Volumes are human-scale governance records — per-contract loops over the
 * domain's contracts stay simple over batched joins by design (the cascade
 * read model's precedent).
 */
@Service
public class ContractSignalService {

    /**
     * Trailing window of the §11.2 coordination-pattern computation. Public
     * so the D5 portfolio surface (engine-spec diagnostic.md §12.2) can
     * REPORT the window it delegates to in its {@code computedFrom} block —
     * the window itself stays owned HERE ({@code substrate.stats.pattern-
     * window-days} is documentation of this value, never a portfolio-side
     * fork; a drift between the two is pinned by test).
     */
    public static final Duration COORDINATION_WINDOW = Duration.ofDays(7);

    private static final Set<BreachStatus> OPEN_BREACH_STATUSES = EnumSet.allOf(BreachStatus.class)
            .stream().filter(BreachStatus::isOpen)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(BreachStatus.class)));

    /** §1 allowlist value with type-specific behavior (federation decline boundary, ADR D15). */
    private static final String FEDERATION_INVITATION = "federation-invitation";

    private final ContractRepository contractRepository;
    private final ContractPartyRepository partyRepository;
    private final ContractPartyResponseRepository responseRepository;
    private final ContractDeclineTriggerRepository triggerRepository;
    private final ContractMonitorRepository monitorRepository;
    private final ContractBreachRepository breachRepository;
    private final ContractEventRepository eventRepository;
    private final CascadeQueryService cascadeQueryService;
    private final Clock clock;

    public ContractSignalService(ContractRepository contractRepository,
                                 ContractPartyRepository partyRepository,
                                 ContractPartyResponseRepository responseRepository,
                                 ContractDeclineTriggerRepository triggerRepository,
                                 ContractMonitorRepository monitorRepository,
                                 ContractBreachRepository breachRepository,
                                 ContractEventRepository eventRepository,
                                 CascadeQueryService cascadeQueryService,
                                 Clock clock) {
        this.contractRepository = contractRepository;
        this.partyRepository = partyRepository;
        this.responseRepository = responseRepository;
        this.triggerRepository = triggerRepository;
        this.monitorRepository = monitorRepository;
        this.breachRepository = breachRepository;
        this.eventRepository = eventRepository;
        this.cascadeQueryService = cascadeQueryService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SignalsResponse signals(UUID domainId) {
        List<Contract> contracts = contractRepository.findByDomainId(domainId);

        long pendingFederationInvitations = 0;
        long openBreachTotal = 0;
        Map<String, Long> openBySeverity = new LinkedHashMap<>();
        List<SignalsResponse.DeclineEligibleItem> declineEligible = new ArrayList<>();
        List<SignalsResponse.NeedsInputItem> needsInput = new ArrayList<>();
        long structured = 0;
        long unstructured = 0;

        for (Contract c : contracts) {
            // pendingFederationInvitations: PROPOSED federation-invitation
            // contracts where a required party has not responded. Which party
            // IS "this domain's party" is unresolvable before party→domain
            // binding hardens (D5 subsidiary modeling / spec §15 #12 identity)
            // — v1 counts any required-party gap on the domain's contracts.
            if (c.getStatus() == ContractStatus.PROPOSED
                    && FEDERATION_INVITATION.equalsIgnoreCase(c.getContractType())
                    && hasUnrespondedRequiredParty(c.getId())) {
                pendingFederationInvitations++;
            }

            var open = breachRepository.findByContractIdAndStatusIn(c.getId(), OPEN_BREACH_STATUSES);
            openBreachTotal += open.size();
            open.stream().filter(b -> b.getSeverity() != null).forEach(b ->
                    openBySeverity.merge(b.getSeverity().name(), 1L, Long::sum));

            // declineEligible: ARMED standing is meaningful only while the
            // contract is declinable, i.e. ACTIVE (§2.6 — the arming pass
            // evaluates ACTIVE contracts; subtle-destructive, NEVER a breach).
            if (c.getStatus() == ContractStatus.ACTIVE) {
                for (ContractDeclineTrigger t
                        : triggerRepository.findByContractIdOrderBySequenceOrderAsc(c.getId())) {
                    if (t.isArmed()) {
                        declineEligible.add(new SignalsResponse.DeclineEligibleItem(
                                c.getContractKey(), t.getId(), t.getText()));
                    }
                }
            }

            for (ContractMonitor m : monitorRepository.findByContractId(c.getId())) {
                if (m.getRuleType() == MonitorRuleType.UNSTRUCTURED) {
                    unstructured++;
                } else {
                    structured++;
                }
                // needs-input (ADR D10): INDETERMINATE last outcome on a live
                // monitor — NEVER a breach; rendered distinctly from compliant.
                if (m.getStatus() == MonitorStatus.ACTIVE
                        && m.getLastOutcome() == MonitorRunOutcome.INDETERMINATE) {
                    String kind = needsInputKind(m.getRuleType());
                    if (kind != null) {
                        needsInput.add(new SignalsResponse.NeedsInputItem(
                                c.getContractKey(), m.getId(), kind));
                    }
                }
            }
        }

        // coordinationPatternsThisWeek: contract_events + cascade events,
        // trailing 7 days — computed, never stamped (§11.2; fixture seam S6).
        // Sub-counts ride along (diagnostic.md §12.2): pre-D2-3b the contract
        // term contributes 0 VISIBLY on the D5 portfolio surface.
        Instant since = clock.instant().minus(COORDINATION_WINDOW);
        long contractEvents = eventRepository.countByDomainIdAndOccurredAtAfter(domainId, since);
        long cascadeActions = cascadeQueryService.stats(domainId).actionsLast7Days();
        long coordinationPatterns = contractEvents + cascadeActions;

        return new SignalsResponse(
                pendingFederationInvitations,
                new SignalsResponse.OpenBreaches(openBreachTotal, openBySeverity),
                declineEligible,
                needsInput,
                new SignalsResponse.MonitorCoverage(structured, unstructured),
                coordinationPatterns,
                // EXTENSION POINT (spec §15 #1): review-boundary window
                // arithmetic (§6.2) lands with the 3c gateway; until then
                // computed empty — both DemoPack vectors seed null boundaries.
                List.of(),
                new SignalsResponse.CoordinationBreakdown(contractEvents, cascadeActions));
    }

    private boolean hasUnrespondedRequiredParty(UUID contractId) {
        Set<UUID> responded = responseRepository.findByContractId(contractId).stream()
                .map(ContractPartyResponse::getPartyId)
                .collect(Collectors.toSet());
        return partyRepository.findByContractIdOrderBySequenceOrderAsc(contractId).stream()
                .filter(ContractParty::isResponseRequired)
                .anyMatch(p -> !responded.contains(p.getId()));
    }

    /**
     * §2.5/§5.3 needs-input signal classes by rule type. ATTESTATION_CADENCE
     * never yields INDETERMINATE per the §5.3 matrix — no mapping is invented
     * for it (flag-not-invent); an unexpected occurrence is simply not
     * surfaced as needs-input.
     */
    private static String needsInputKind(MonitorRuleType ruleType) {
        return switch (ruleType) {
            case METRIC_THRESHOLD -> "ADHERENCE_STALE";
            case MCP_PROBE -> "ADHERENCE_UNOBSERVABLE";
            case UNSTRUCTURED -> "HUMAN_VERIFY_DUE";
            case ATTESTATION_CADENCE -> null;
        };
    }
}
