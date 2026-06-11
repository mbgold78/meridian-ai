package com.meridian.substrate.engine.contract.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractAttestation;
import com.meridian.substrate.common.domain.ContractCommitment;
import com.meridian.substrate.common.domain.ContractDeclineTrigger;
import com.meridian.substrate.common.domain.ContractEntityLink;
import com.meridian.substrate.common.domain.ContractEnforcementDecision;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.ContractPartyResponse;
import com.meridian.substrate.common.domain.enums.BreachStatus;
import com.meridian.substrate.common.domain.enums.CommitmentComplianceState;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.domain.enums.GatewayDecision;
import com.meridian.substrate.common.repo.ContractAttestationRepository;
import com.meridian.substrate.common.repo.ContractBreachRepository;
import com.meridian.substrate.common.repo.ContractCommitmentRepository;
import com.meridian.substrate.common.repo.ContractDeclineTriggerRepository;
import com.meridian.substrate.common.repo.ContractEnforcementDecisionRepository;
import com.meridian.substrate.common.repo.ContractEntityLinkRepository;
import com.meridian.substrate.common.repo.ContractEventRepository;
import com.meridian.substrate.common.repo.ContractMonitorRepository;
import com.meridian.substrate.common.repo.ContractMonitorRunRepository;
import com.meridian.substrate.common.repo.ContractPartyRepository;
import com.meridian.substrate.common.repo.ContractPartyResponseRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.contract.monitor.PeriodKeys;
import com.meridian.substrate.engine.contract.read.AttestationResponse;
import com.meridian.substrate.engine.contract.read.BreachResponse;
import com.meridian.substrate.engine.contract.read.CommitmentRowResponse;
import com.meridian.substrate.engine.contract.read.ContractCardResponse;
import com.meridian.substrate.engine.contract.read.ContractDetailResponse;
import com.meridian.substrate.engine.contract.read.ContractEventResponse;
import com.meridian.substrate.engine.contract.read.EnforcementDecisionResponse;
import com.meridian.substrate.engine.contract.read.LinkedEntityRef;
import com.meridian.substrate.engine.contract.read.MonitorRunResponse;
import com.meridian.substrate.engine.contract.read.ObligationsResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read model for the contract engine (engine-spec contracts.md §7.1/§7.3;
 * §9 {@code ContractReadService}): card/detail projections in the fixture
 * superset shape, {@code {idOrKey}} resolution, and the per-contract
 * audit/observation lanes (events, breaches, decisions, monitor runs,
 * attestations). Ships FIRST per the binding read-model-first build order
 * (ADR D18) and never writes.
 *
 * <p>Also the engine home of the §8.2 MCP read-tool lanes (chunk 3a-3): the
 * {@code …ForMcp} variants relax the REST surface's mandatory
 * {@code domainId}/contract scoping (v1 deploys are per-engagement
 * single-domain, plan §8) and {@link #obligationsForPrincipal} is the
 * {@code get_obligations_for_principal} read model — caller identity itself
 * (the spoofable pre-Phase-5 {@code X-MCP-Principal} header) is resolved by
 * the app-module MCP adapter, never here.
 */
@Service
public class ContractReadService {

    /** Open = DETECTED|SURFACED|ACKNOWLEDGED — lockstep with BreachStatus.isOpen() and V5_1. */
    private static final Set<BreachStatus> OPEN_BREACH_STATUSES = EnumSet.allOf(BreachStatus.class)
            .stream().filter(BreachStatus::isOpen)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(BreachStatus.class)));

    private final ContractRepository contractRepository;
    private final ContractPartyRepository partyRepository;
    private final ContractPartyResponseRepository responseRepository;
    private final ContractCommitmentRepository commitmentRepository;
    private final ContractDeclineTriggerRepository triggerRepository;
    private final ContractEntityLinkRepository entityLinkRepository;
    private final ContractMonitorRepository monitorRepository;
    private final ContractBreachRepository breachRepository;
    private final ContractEventRepository eventRepository;
    private final ContractMonitorRunRepository monitorRunRepository;
    private final ContractAttestationRepository attestationRepository;
    private final ContractEnforcementDecisionRepository decisionRepository;
    private final ContractProperties properties;
    private final Clock clock;

    public ContractReadService(ContractRepository contractRepository,
                               ContractPartyRepository partyRepository,
                               ContractPartyResponseRepository responseRepository,
                               ContractCommitmentRepository commitmentRepository,
                               ContractDeclineTriggerRepository triggerRepository,
                               ContractEntityLinkRepository entityLinkRepository,
                               ContractMonitorRepository monitorRepository,
                               ContractBreachRepository breachRepository,
                               ContractEventRepository eventRepository,
                               ContractMonitorRunRepository monitorRunRepository,
                               ContractAttestationRepository attestationRepository,
                               ContractEnforcementDecisionRepository decisionRepository,
                               ContractProperties properties,
                               Clock clock) {
        this.contractRepository = contractRepository;
        this.partyRepository = partyRepository;
        this.responseRepository = responseRepository;
        this.commitmentRepository = commitmentRepository;
        this.triggerRepository = triggerRepository;
        this.entityLinkRepository = entityLinkRepository;
        this.monitorRepository = monitorRepository;
        this.breachRepository = breachRepository;
        this.eventRepository = eventRepository;
        this.monitorRunRepository = monitorRunRepository;
        this.attestationRepository = attestationRepository;
        this.decisionRepository = decisionRepository;
        this.properties = properties;
        this.clock = clock;
    }

    // -------------------------------------------------------------- resolution

    /**
     * §7.1 {@code {idOrKey}} resolution: UUID first, then {@code contract_key}.
     * v1 deploys are per-engagement single-domain, so a bare key resolves when
     * globally unambiguous; an ambiguous key (spec §15 #10 — cross-engagement
     * namespacing) must be addressed by UUID.
     */
    @Transactional(readOnly = true)
    public Optional<Contract> resolve(String idOrKey) {
        try {
            return contractRepository.findById(UUID.fromString(idOrKey.trim()));
        } catch (IllegalArgumentException notAUuid) {
            List<Contract> byKey = contractRepository.findByContractKey(idOrKey.trim());
            if (byKey.size() > 1) {
                throw new IllegalArgumentException("contract_key '" + idOrKey
                        + "' exists in multiple domains — address by UUID (spec §15 #10)");
            }
            return byKey.stream().findFirst();
        }
    }

    // -------------------------------------------------------------------- cards

    /** §7.1 {@code GET /contracts?domainId&status?&type?} card list. */
    @Transactional(readOnly = true)
    public List<ContractCardResponse> cards(UUID domainId, ContractStatus status, String type) {
        List<Contract> contracts = status != null
                ? contractRepository.findByDomainIdAndStatus(domainId, status)
                : contractRepository.findByDomainId(domainId);
        return contracts.stream()
                .filter(c -> type == null || type.equalsIgnoreCase(c.getContractType()))
                .sorted(Comparator.comparing(Contract::getProposedAt))
                .map(this::card)
                .toList();
    }

    private ContractCardResponse card(Contract c) {
        List<String> partyLabels = partyRepository
                .findByContractIdOrderBySequenceOrderAsc(c.getId()).stream()
                .map(ContractParty::getPartyLabel)
                .toList();
        return new ContractCardResponse(c.getId(), c.getContractKey(), c.getContractType(),
                c.getTitle(), c.getStatus(), c.getProposedAt(), c.getEstimatedValue(),
                partyLabels, linkedEntities(c.getId()), openBreachCount(c.getId()));
    }

    // ------------------------------------------------------------------- detail

    /** §7.3 detail — the BeaconContract superset (S5 swap adapter source). */
    @Transactional(readOnly = true)
    public ContractDetailResponse detail(Contract c) {
        Map<UUID, ContractPartyResponse> responsesByParty =
                responseRepository.findByContractId(c.getId()).stream()
                        .collect(Collectors.toMap(ContractPartyResponse::getPartyId, r -> r,
                                (a, b) -> a, LinkedHashMap::new));

        List<ContractDetailResponse.Party> parties = new ArrayList<>();
        for (ContractParty party : partyRepository.findByContractIdOrderBySequenceOrderAsc(c.getId())) {
            List<ContractDetailResponse.Commitment> commitments =
                    commitmentRepository.findByPartyIdOrderBySequenceOrderAsc(party.getId()).stream()
                            .map(ContractReadService::commitment)
                            .toList();
            ContractPartyResponse r = responsesByParty.get(party.getId());
            parties.add(new ContractDetailResponse.Party(
                    party.getId(), party.getPartyLabel(), party.getPartyType(),
                    boundEntities(party.getBoundEntityIds()), party.isResponseRequired(),
                    r == null ? null : new ContractDetailResponse.Response(
                            r.getResponse(), r.getRespondedBy(), r.getRespondedAt(),
                            r.getDeclineTriggerId(), r.getDeclineReasoning()),
                    commitments));
        }

        List<ContractDetailResponse.DeclineTrigger> triggers =
                triggerRepository.findByContractIdOrderBySequenceOrderAsc(c.getId()).stream()
                        .map(t -> new ContractDetailResponse.DeclineTrigger(
                                t.getId(), t.getText(), t.isArmed(), t.getInvocableByPartyId()))
                        .toList();

        List<ContractDetailResponse.Monitor> monitors =
                monitorRepository.findByContractId(c.getId()).stream()
                        .sorted(Comparator.comparing(ContractMonitor::getName))
                        .map(m -> new ContractDetailResponse.Monitor(
                                m.getId(), m.getName(),
                                m.getRuleType() == null ? null : m.getRuleType().name(),
                                m.getStatus(), m.getCadence(), m.getGraceDays(),
                                m.getLastRunAt(), m.getNextRunAt(), m.getLastOutcome()))
                        .toList();

        return new ContractDetailResponse(
                c.getId(), c.getContractKey(), c.getContractType(), c.getTitle(), c.getContext(),
                c.getStatus(), c.getProposedAt(), c.getProposedBy(), c.getActivatedAt(),
                c.getResolvedAt(),
                // Nullable display string — null must SERIALIZE as null
                // (vector 2 pin §14.1: the UI hides the value aside).
                c.getEstimatedValue(), c.getEstimatedValueAmount(),
                parties,
                new ContractDetailResponse.Enforcement(
                        c.getMonitorText(), c.getMonitorMechanism(), triggers),
                linkedEntities(c.getId()),
                monitors,
                breachSummary(c.getId()),
                c.getReviewBoundary());
    }

    // ------------------------------------------------------------ per-contract lanes

    /** §7.1 events — append-only audit timeline ascending; {@code after} optional. */
    @Transactional(readOnly = true)
    public List<ContractEventResponse> events(UUID contractId, Instant after) {
        var rows = after != null
                ? eventRepository.findByContractIdAndOccurredAtAfterOrderByOccurredAtAsc(contractId, after)
                : eventRepository.findByContractIdOrderByOccurredAtAsc(contractId);
        return rows.stream().map(ContractEventResponse::from).toList();
    }

    /** §7.1 breaches — all table-12 columns camelCased; {@code status} optional. */
    @Transactional(readOnly = true)
    public List<BreachResponse> breaches(UUID contractId, BreachStatus status) {
        Set<BreachStatus> statuses = status != null
                ? EnumSet.of(status)
                : EnumSet.allOf(BreachStatus.class);
        return breachRepository.findByContractIdAndStatusIn(contractId, statuses).stream()
                .sorted(Comparator.comparing(b -> b.getDetectedAt()))
                .map(BreachResponse::from)
                .toList();
    }

    /**
     * §7.1 decisions — the PDP log is domain-scoped with a no-FK
     * {@code contract_ids} JSON array (ADR D17); per-contract filtering
     * happens app-side over that array (id or contractKey elements — the 3c
     * writer records both currencies; nothing rows before 3c).
     */
    @Transactional(readOnly = true)
    public List<EnforcementDecisionResponse> decisions(Contract contract, Instant since,
                                                       GatewayDecision decision) {
        Instant cutoff = since != null ? since : Instant.EPOCH;
        List<ContractEnforcementDecision> rows = decision != null
                ? decisionRepository.findByDomainIdAndDecisionAndDecidedAtAfter(
                        contract.getDomainId(), decision, cutoff)
                : decisionRepository.findByDomainIdAndDecidedAtAfter(contract.getDomainId(), cutoff);
        String idString = contract.getId().toString();
        return rows.stream()
                .filter(d -> referencesContract(d.getContractIds(), idString, contract.getContractKey()))
                .sorted(Comparator.comparing(ContractEnforcementDecision::getDecidedAt))
                .map(EnforcementDecisionResponse::from)
                .toList();
    }

    /** §7.1 monitor-runs — merged across the contract's monitors, startedAt asc. */
    @Transactional(readOnly = true)
    public List<MonitorRunResponse> monitorRuns(UUID contractId, UUID monitorId, Instant since) {
        List<ContractMonitor> monitors = monitorRepository.findByContractId(contractId).stream()
                .filter(m -> monitorId == null || monitorId.equals(m.getId()))
                .toList();
        List<MonitorRunResponse> runs = new ArrayList<>();
        for (ContractMonitor monitor : monitors) {
            var rows = since != null
                    ? monitorRunRepository.findByMonitorIdAndStartedAtAfterOrderByStartedAtAsc(
                            monitor.getId(), since)
                    : monitorRunRepository.findByMonitorIdOrderByStartedAtAsc(monitor.getId());
            rows.forEach(r -> runs.add(MonitorRunResponse.from(r)));
        }
        runs.sort(Comparator.comparing(MonitorRunResponse::startedAt));
        return runs;
    }

    /** §7.1 attestations — {@code commitmentId} optional narrowing. */
    @Transactional(readOnly = true)
    public List<AttestationResponse> attestations(UUID contractId, UUID commitmentId) {
        return attestationRepository.findByContractId(contractId).stream()
                .filter(a -> commitmentId == null || commitmentId.equals(a.getCommitmentId()))
                .sorted(Comparator.comparing(ContractAttestation::getAttestedAt))
                .map(AttestationResponse::from)
                .toList();
    }

    // --------------------------------------------------- §8.2 MCP read lanes

    /**
     * MCP {@code list_contracts(domain_id?, status?, party?)} — same card
     * pipeline as {@link #cards} (REST-parity by construction) with the
     * §8.2 relaxations: nullable {@code domain_id} (v1 per-engagement
     * single-domain) and a case-insensitive substring {@code party} filter
     * over party labels.
     */
    @Transactional(readOnly = true)
    public List<ContractCardResponse> cardsForMcp(UUID domainId, ContractStatus status,
                                                  String party) {
        List<Contract> contracts;
        if (domainId != null) {
            contracts = status != null
                    ? contractRepository.findByDomainIdAndStatus(domainId, status)
                    : contractRepository.findByDomainId(domainId);
        } else {
            contracts = contractRepository.findAll().stream()
                    .filter(c -> status == null || c.getStatus() == status)
                    .toList();
        }
        String partyNeedle = party == null ? null : party.trim().toLowerCase(Locale.ROOT);
        return contracts.stream()
                .sorted(Comparator.comparing(Contract::getProposedAt))
                .map(this::card)
                .filter(card -> partyNeedle == null || partyNeedle.isEmpty()
                        || card.partyLabels().stream()
                                .anyMatch(l -> l.toLowerCase(Locale.ROOT).contains(partyNeedle)))
                .toList();
    }

    /**
     * MCP {@code get_commitments(contract_key?, party?, compliance_state?)} —
     * commitment rows with state (§8.2). Deterministic order: contractKey asc,
     * then party sequence, then commitment sequence.
     */
    @Transactional(readOnly = true)
    public List<CommitmentRowResponse> commitmentRowsForMcp(Contract contract, String party,
                                                            CommitmentComplianceState state) {
        List<Contract> contracts = contract != null
                ? List.of(contract)
                : contractRepository.findAll().stream()
                        .sorted(Comparator.comparing(Contract::getContractKey))
                        .toList();
        String partyNeedle = party == null ? null : party.trim().toLowerCase(Locale.ROOT);
        List<CommitmentRowResponse> rows = new ArrayList<>();
        for (Contract c : contracts) {
            for (ContractParty p : partyRepository.findByContractIdOrderBySequenceOrderAsc(c.getId())) {
                if (partyNeedle != null && !partyNeedle.isEmpty()
                        && !p.getPartyLabel().toLowerCase(Locale.ROOT).contains(partyNeedle)) {
                    continue;
                }
                for (ContractCommitment cm
                        : commitmentRepository.findByPartyIdOrderBySequenceOrderAsc(p.getId())) {
                    if (state != null && cm.getComplianceState() != state) {
                        continue;
                    }
                    rows.add(new CommitmentRowResponse(cm.getId(), c.getId(), c.getContractKey(),
                            p.getId(), p.getPartyLabel(), cm.getText(), cm.getDueBy(),
                            cm.getCadence(), cm.getComplianceState(), cm.getLastEvaluatedAt(),
                            cm.getSequenceOrder()));
                }
            }
        }
        return rows;
    }

    /** MCP {@code get_breaches(contract_key?, status?)} — nullable contract scope (§8.2). */
    @Transactional(readOnly = true)
    public List<BreachResponse> breachesForMcp(Contract contract, BreachStatus status) {
        if (contract != null) {
            return breaches(contract.getId(), status);
        }
        return contractRepository.findAll().stream()
                .sorted(Comparator.comparing(Contract::getContractKey))
                .flatMap(c -> breaches(c.getId(), status).stream())
                .toList();
    }

    /**
     * MCP {@code get_enforcement_decisions(contract_key?, actor?, since?)} —
     * §8.2; actor filters on the verbatim recorded {@code actorId} (spoofable
     * pre-Phase-5, spec §15 #12).
     */
    @Transactional(readOnly = true)
    public List<EnforcementDecisionResponse> decisionsForMcp(Contract contract, String actor,
                                                             Instant since) {
        Instant cutoff = since != null ? since : Instant.EPOCH;
        List<EnforcementDecisionResponse> rows = contract != null
                ? decisions(contract, since, null)
                : decisionRepository.findByDecidedAtAfter(cutoff).stream()
                        .sorted(Comparator.comparing(ContractEnforcementDecision::getDecidedAt))
                        .map(EnforcementDecisionResponse::from)
                        .toList();
        return actor == null || actor.isBlank()
                ? rows
                : rows.stream().filter(r -> actor.trim().equals(r.actorId())).toList();
    }

    /**
     * MCP {@code get_obligations_for_principal} read model (§8.2 — the
     * agent-ergonomics keystone): every party bound to the caller via
     * {@code mcp_principal_id}, on ACTIVE contracts only (§2.3 — nothing is
     * "owed" under PROPOSED/DECLINED), with WAIVED commitments excluded.
     * Period arithmetic per §5.1 from the engine clock in the configured
     * zone; see {@link ObligationsResponse.Obligation} for field semantics.
     */
    @Transactional(readOnly = true)
    public ObligationsResponse obligationsForPrincipal(String principalId) {
        Instant now = clock.instant();
        ZoneId zone = ZoneId.of(properties.getClock().getZone());
        List<ObligationsResponse.PartyObligations> parties = new ArrayList<>();
        for (ContractParty party : partyRepository.findByMcpPrincipalId(principalId)) {
            Contract contract = contractRepository.findById(party.getContractId()).orElse(null);
            if (contract == null || contract.getStatus() != ContractStatus.ACTIVE) {
                continue;
            }
            List<ObligationsResponse.Obligation> obligations =
                    commitmentRepository.findByPartyIdOrderBySequenceOrderAsc(party.getId()).stream()
                            .filter(c -> c.getComplianceState() != CommitmentComplianceState.WAIVED)
                            .map(c -> obligation(c, now, zone))
                            .toList();
            parties.add(new ObligationsResponse.PartyObligations(
                    contract.getId(), contract.getContractKey(), contract.getTitle(),
                    party.getId(), party.getPartyLabel(), obligations));
        }
        parties.sort(Comparator
                .comparing(ObligationsResponse.PartyObligations::contractKey)
                .thenComparing(ObligationsResponse.PartyObligations::partyLabel));
        return new ObligationsResponse(principalId, parties);
    }

    /**
     * §5.1 readouts for one owed commitment. Mirrors the §5.3
     * AttestationCadence question literally — "most recent closed period P;
     * attestation exists for (commitment, P)?" — with NO activation carve-out
     * or grace arithmetic (those are evaluator semantics, 3b).
     */
    private ObligationsResponse.Obligation obligation(ContractCommitment c, Instant now,
                                                      ZoneId zone) {
        String nextPeriodKey = PeriodKeys.periodKey(c.getCadence(), now, zone);
        String lastClosedPeriodKey = PeriodKeys.previousPeriodKey(c.getCadence(), now, zone);
        boolean missingAttestation;
        if (lastClosedPeriodKey != null) {
            missingAttestation = attestationRepository
                    .findByCommitmentIdAndPeriodKey(c.getId(), lastClosedPeriodKey).isEmpty();
        } else if (PeriodKeys.ONE_TIME.equals(nextPeriodKey)) {
            missingAttestation = attestationRepository
                    .findByCommitmentIdAndPeriodKey(c.getId(), PeriodKeys.ONE_TIME).isEmpty();
        } else {
            missingAttestation = false;   // unsupported cadence — surfaced by the null keys
        }
        return new ObligationsResponse.Obligation(c.getId(), c.getText(), c.getDueBy(),
                c.getCadence(), c.getComplianceState(), nextPeriodKey, lastClosedPeriodKey,
                missingAttestation);
    }

    // ------------------------------------------------------------------ helpers

    private List<LinkedEntityRef> linkedEntities(UUID contractId) {
        return entityLinkRepository.findByContractId(contractId).stream()
                .sorted(Comparator.comparing(ContractEntityLink::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(l -> new LinkedEntityRef(l.getLinkedEntityType(), l.getLinkedEntityId(),
                        l.getSlug(), l.getLabel()))
                .toList();
    }

    /**
     * {@code bound_entity_ids} JSON array of {@code {entityType, entityId,
     * slug?, label?}} soft refs (§3.2 table 2) → typed refs; null/absent →
     * empty list.
     */
    private static List<LinkedEntityRef> boundEntities(JsonNode boundEntityIds) {
        if (boundEntityIds == null || !boundEntityIds.isArray()) {
            return List.of();
        }
        List<LinkedEntityRef> refs = new ArrayList<>();
        for (JsonNode n : boundEntityIds) {
            refs.add(new LinkedEntityRef(
                    text(n, "entityType"),
                    n.hasNonNull("entityId") ? UUID.fromString(n.get("entityId").asText()) : null,
                    text(n, "slug"),
                    text(n, "label")));
        }
        return refs;
    }

    private static String text(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asText() : null;
    }

    private static ContractDetailResponse.Commitment commitment(ContractCommitment c) {
        return new ContractDetailResponse.Commitment(c.getId(), c.getText(), c.getDueBy(),
                c.getCadence(), c.getComplianceState(), c.getLastEvaluatedAt());
    }

    private long openBreachCount(UUID contractId) {
        return breachRepository.findByContractIdAndStatusIn(contractId, OPEN_BREACH_STATUSES).size();
    }

    private ContractDetailResponse.BreachSummary breachSummary(UUID contractId) {
        var open = breachRepository.findByContractIdAndStatusIn(contractId, OPEN_BREACH_STATUSES);
        Map<String, Long> bySeverity = open.stream()
                .filter(b -> b.getSeverity() != null)
                .collect(Collectors.groupingBy(b -> b.getSeverity().name(),
                        LinkedHashMap::new, Collectors.counting()));
        return new ContractDetailResponse.BreachSummary(open.size(), bySeverity);
    }

    private static boolean referencesContract(JsonNode contractIds, String idString, String key) {
        if (contractIds == null || !contractIds.isArray()) {
            return false;
        }
        for (JsonNode n : contractIds) {
            String v = n.asText();
            if (idString.equals(v) || key.equals(v)) {
                return true;
            }
        }
        return false;
    }
}
