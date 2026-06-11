package com.meridian.substrate.engine.contract.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractClauseGuard;
import com.meridian.substrate.common.domain.ContractCommitment;
import com.meridian.substrate.common.domain.ContractDeclineTrigger;
import com.meridian.substrate.common.domain.ContractEntityLink;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.ContractPartyResponse;
import com.meridian.substrate.common.domain.enums.ContractPartyType;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.domain.enums.GuardEffect;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.MonitorStatus;
import com.meridian.substrate.common.domain.enums.PartyResponse;
import com.meridian.substrate.common.domain.enums.Severity;
import com.meridian.substrate.common.repo.ContractClauseGuardRepository;
import com.meridian.substrate.common.repo.ContractCommitmentRepository;
import com.meridian.substrate.common.repo.ContractDeclineTriggerRepository;
import com.meridian.substrate.common.repo.ContractEntityLinkRepository;
import com.meridian.substrate.common.repo.ContractMonitorRepository;
import com.meridian.substrate.common.repo.ContractPartyRepository;
import com.meridian.substrate.common.repo.ContractPartyResponseRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.contract.ContractAccountabilityMirror;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.contract.events.ContractActivatedEvent;
import com.meridian.substrate.engine.contract.events.ContractDeclinedEvent;
import com.meridian.substrate.engine.contract.exception.ContractConflictException;
import com.meridian.substrate.engine.contract.gateway.Governed;
import com.meridian.substrate.engine.contract.gateway.GovernedActions;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Contract lifecycle writes (engine-spec contracts.md §9
 * {@code ContractRegistryService}): authoring create/patch (ADR D7 freeze
 * rule), respond, the §2.2 transitions, and {@code invoke_decline} (§6.1).
 * Chunk 3a ships the SKELETON the DemoPack seeder and the §14.3 state-machine
 * tests need; the §7.2 REST write endpoints and request-record mapping land
 * with the authoring chunk, and the 3c gateway PEP wraps these methods then.
 *
 * <p>Every transition runs under {@code findByIdForUpdate}
 * (PESSIMISTIC_WRITE, §10.1 — the last-required-acceptance check happens
 * under the lock so two concurrent accepts cannot double-activate;
 * CapabilityRepository precedent, H2/PG both {@code SELECT … FOR UPDATE}).
 *
 * <p><b>DECLINE is a legitimate first-class terminal outcome</b> (port-map
 * trap #8; ADR D6): both decline paths return normally — never an exception,
 * never error vocabulary; HTTP mapping is 200 with the full detail. 409s
 * exist only for frozen content, conflicting responses, and post-terminal /
 * wrong-state commands ({@link ContractConflictException}).
 *
 * <p>{@code WITHDRAWN}/{@code CONCLUDED} are schema-reserved and
 * transition-UNREACHABLE (ADR D7; spec §15 #2): no method on this service
 * accepts a target status, and every transition goes through
 * {@link ContractStatus#canTransitionTo} whose table has no edge to them.
 *
 * <p>EXTENSION POINT (spec §15 #14, port-map {@code [LIFECYCLE-MACHINE]}):
 * contracts do NOT gate lifecycle phases — no transition here reads or writes
 * {@code PhaseState}, and none may until the doc-02 Monitor/Automate entry
 * criteria land. Reviewers must reject any such coupling.
 */
@Service
public class ContractRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ContractRegistryService.class);

    private final ContractRepository contractRepository;
    private final ContractPartyRepository partyRepository;
    private final ContractPartyResponseRepository responseRepository;
    private final ContractCommitmentRepository commitmentRepository;
    private final ContractDeclineTriggerRepository triggerRepository;
    private final ContractEntityLinkRepository entityLinkRepository;
    private final ContractMonitorRepository monitorRepository;
    private final ContractClauseGuardRepository guardRepository;
    private final ContractEventService eventService;
    private final ContractAccountabilityMirror mirror;
    private final DeclineAdvisoryFlags declineAdvisoryFlags;
    private final ContractProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public ContractRegistryService(ContractRepository contractRepository,
                                   ContractPartyRepository partyRepository,
                                   ContractPartyResponseRepository responseRepository,
                                   ContractCommitmentRepository commitmentRepository,
                                   ContractDeclineTriggerRepository triggerRepository,
                                   ContractEntityLinkRepository entityLinkRepository,
                                   ContractMonitorRepository monitorRepository,
                                   ContractClauseGuardRepository guardRepository,
                                   ContractEventService eventService,
                                   ContractAccountabilityMirror mirror,
                                   DeclineAdvisoryFlags declineAdvisoryFlags,
                                   ContractProperties properties,
                                   ApplicationEventPublisher eventPublisher,
                                   Clock clock,
                                   ObjectMapper objectMapper) {
        this.contractRepository = contractRepository;
        this.partyRepository = partyRepository;
        this.responseRepository = responseRepository;
        this.commitmentRepository = commitmentRepository;
        this.triggerRepository = triggerRepository;
        this.entityLinkRepository = entityLinkRepository;
        this.monitorRepository = monitorRepository;
        this.guardRepository = guardRepository;
        this.eventService = eventService;
        this.mirror = mirror;
        this.declineAdvisoryFlags = declineAdvisoryFlags;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    // ----------------------------------------------------------------- commands

    /**
     * Engine-internal create form (§7.2 create shape, minus the wire
     * request-record/validation layer the authoring chunk owns). Monitor
     * commitment/party bindings are expressed as sequence indexes
     * ({@code boundCommitments} / {@code requiredPartyIndexes}) because the
     * row UUIDs do not exist until this method persists them; they are
     * resolved into {@code rule_spec.commitmentIds} / {@code requiredPartyIds}
     * here.
     */
    public record CreateContractCommand(
            UUID domainId, String contractKey, String type, String title, String context,
            String monitorText, String monitorMechanism, String estimatedValue,
            BigDecimal estimatedValueAmount, JsonNode reviewBoundary,
            Instant proposedAt, String proposedBy,
            List<PartyCommand> parties, List<DeclineTriggerCommand> declineTriggers,
            List<MonitorCommand> monitors, List<GuardCommand> guards,
            List<EntityLinkCommand> linkedEntities) {
    }

    public record PartyCommand(String partyLabel, ContractPartyType partyType,
                               JsonNode boundEntityIds, boolean responseRequired,
                               List<CommitmentCommand> commitments) {
    }

    public record CommitmentCommand(String text, Instant dueBy, String cadence) {
    }

    /** {@code invocableByPartyLabel} resolves against the command's party labels (null = any party). */
    public record DeclineTriggerCommand(String text, JsonNode predicate, String invocableByPartyLabel) {
    }

    /** {@code (partyIndex, commitmentIndex)} into the create command's lists. */
    public record CommitmentRef(int partyIndex, int commitmentIndex) {
    }

    public record MonitorCommand(String name, MonitorRuleType ruleType, JsonNode ruleSpec,
                                 String cadence, int graceDays, String surfaceTo, Severity severity,
                                 List<CommitmentRef> boundCommitments, List<Integer> requiredPartyIndexes) {
    }

    /**
     * Guards are created {@code active=false} (§7.2). {@code commitmentRef}
     * binding is deferred to the authoring chunk — both fixture contracts
     * compile to ZERO guards (spec §16; do not let demo pressure invent any).
     */
    public record GuardCommand(String actionType, String entityType, GuardEffect effect,
                               JsonNode predicate, String description) {
    }

    public record EntityLinkCommand(String linkedEntityType, UUID linkedEntityId,
                                    String slug, String label) {
    }

    public record RespondCommand(UUID partyId, PartyResponse response, UUID declineTriggerId,
                                 String declineReasoning, String respondedBy, ContractActor actor) {
    }

    public record InvokeDeclineCommand(UUID declineTriggerId, String reasoning,
                                       UUID invokingPartyId, ContractActor actor) {
    }

    /** Scalar contract-content patch (ADR D7 freeze window); null = leave unchanged. */
    public record PatchContractCommand(String title, String context, String monitorText,
                                       String monitorMechanism, String estimatedValue,
                                       BigDecimal estimatedValueAmount, JsonNode reviewBoundary) {
    }

    // ------------------------------------------------------------------- create

    /**
     * §7.2 POST /contracts behavior: PROPOSED contract; type validated against
     * the ADR D15 allowlist; ≥2 parties, ≥1 commitment per party; monitors
     * created SUSPENDED; guards created {@code active=false}.
     *
     * <p>No event is appended: the spec names no proposal event (§2.2 names
     * events for transitions only) and the {@code contracts} row itself is the
     * durable proposal record — flag-not-invent on event vocabulary.
     */
    @Governed(actionType = GovernedActions.CONTRACT_CREATE)
    @Transactional
    public Contract create(CreateContractCommand cmd) {
        requireNonBlank(cmd.contractKey(), "contractKey");
        requireNonBlank(cmd.title(), "title");
        requireNonBlank(cmd.proposedBy(), "proposedBy");
        if (cmd.domainId() == null) {
            throw new IllegalArgumentException("create requires domainId");
        }
        List<String> allowlist = properties.getTypes().getAllowlist().stream()
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).toList();
        if (cmd.type() == null || !allowlist.contains(cmd.type().trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("contract_type '" + cmd.type()
                    + "' is not in substrate.contracts.types.allowlist " + allowlist + " (ADR D15)");
        }
        if (cmd.parties() == null || cmd.parties().size() < 2) {
            throw new IllegalArgumentException(
                    "a coordination contract requires 2..N parties ([CONTRACT-CONTENT])");
        }
        for (PartyCommand p : cmd.parties()) {
            requireNonBlank(p.partyLabel(), "partyLabel");
            if (p.commitments() == null || p.commitments().isEmpty()) {
                throw new IllegalArgumentException("party '" + p.partyLabel()
                        + "' requires >=1 commitment ([CONTRACT-CONTENT])");
            }
        }
        if (contractRepository.findByDomainIdAndContractKey(cmd.domainId(), cmd.contractKey()).isPresent()) {
            // uq_contracts_domain_key; the authoring chunk owns the wire mapping.
            throw new IllegalArgumentException("contract_key '" + cmd.contractKey()
                    + "' already exists in domain " + cmd.domainId());
        }

        Contract contract = new Contract();
        contract.setDomainId(cmd.domainId());
        contract.setContractKey(cmd.contractKey());
        contract.setContractType(cmd.type());
        contract.setTitle(cmd.title());
        contract.setContext(cmd.context());
        contract.setMonitorText(cmd.monitorText());
        contract.setMonitorMechanism(cmd.monitorMechanism());
        contract.setEstimatedValue(cmd.estimatedValue());
        contract.setEstimatedValueAmount(cmd.estimatedValueAmount());
        contract.setReviewBoundary(cmd.reviewBoundary());
        contract.setStatus(ContractStatus.PROPOSED);
        contract.setProposedAt(cmd.proposedAt() != null ? cmd.proposedAt() : clock.instant());
        contract.setProposedBy(cmd.proposedBy());
        contract = contractRepository.save(contract);

        // Parties + per-party commitments, fixture order = sequence_order.
        List<ContractParty> parties = new ArrayList<>();
        for (int pi = 0; pi < cmd.parties().size(); pi++) {
            PartyCommand pc = cmd.parties().get(pi);
            ContractParty party = new ContractParty();
            party.setContractId(contract.getId());
            party.setPartyLabel(pc.partyLabel());
            party.setPartyType(pc.partyType());
            party.setBoundEntityIds(pc.boundEntityIds());
            party.setResponseRequired(pc.responseRequired());
            party.setSequenceOrder(pi);
            parties.add(partyRepository.save(party));

            for (int ci = 0; ci < pc.commitments().size(); ci++) {
                CommitmentCommand cc = pc.commitments().get(ci);
                requireNonBlank(cc.text(), "commitment text");
                ContractCommitment commitment = new ContractCommitment();
                commitment.setContractId(contract.getId());
                commitment.setPartyId(party.getId());
                commitment.setText(cc.text());
                commitment.setDueBy(cc.dueBy());
                commitment.setCadence(cc.cadence());
                commitment.setSequenceOrder(ci);
                commitmentRepository.save(commitment);
            }
        }

        if (cmd.declineTriggers() != null) {
            for (int ti = 0; ti < cmd.declineTriggers().size(); ti++) {
                DeclineTriggerCommand tc = cmd.declineTriggers().get(ti);
                requireNonBlank(tc.text(), "decline trigger text");
                ContractDeclineTrigger trigger = new ContractDeclineTrigger();
                trigger.setContractId(contract.getId());
                trigger.setText(tc.text());
                trigger.setPredicate(tc.predicate());
                trigger.setInvocableByPartyId(resolvePartyByLabel(parties, tc.invocableByPartyLabel()));
                trigger.setSequenceOrder(ti);
                triggerRepository.save(trigger);
            }
        }

        if (cmd.linkedEntities() != null) {
            for (EntityLinkCommand lc : cmd.linkedEntities()) {
                ContractEntityLink link = new ContractEntityLink();
                link.setContractId(contract.getId());
                link.setLinkedEntityType(lc.linkedEntityType());
                link.setLinkedEntityId(lc.linkedEntityId());
                link.setSlug(lc.slug());
                link.setLabel(lc.label());
                entityLinkRepository.save(link);
            }
        }

        if (cmd.monitors() != null) {
            for (MonitorCommand mc : cmd.monitors()) {
                ContractMonitor monitor = new ContractMonitor();
                monitor.setContractId(contract.getId());
                monitor.setName(mc.name());
                monitor.setRuleType(mc.ruleType());
                monitor.setRuleSpec(resolveRuleSpec(mc, cmd, parties, contract.getId()));
                monitor.setCadence(mc.cadence());
                monitor.setGraceDays(mc.graceDays());
                monitor.setSurfaceTo(mc.surfaceTo());
                monitor.setSeverity(mc.severity());
                monitor.setStatus(MonitorStatus.SUSPENDED); // §7.2: monitors are born SUSPENDED
                monitorRepository.save(monitor);
            }
        }

        if (cmd.guards() != null) {
            for (GuardCommand gc : cmd.guards()) {
                ContractClauseGuard guard = new ContractClauseGuard();
                guard.setContractId(contract.getId());
                guard.setActionType(gc.actionType());
                guard.setEntityType(gc.entityType());
                guard.setEffect(gc.effect());
                guard.setPredicate(gc.predicate());
                guard.setDescription(gc.description());
                guard.setActive(false); // §7.2: live only while contract ACTIVE
                guardRepository.save(guard);
            }
        }

        log.info("Created contract [id={}, key={}, domainId={}, status=PROPOSED, parties={}]",
                contract.getId(), contract.getContractKey(), contract.getDomainId(), parties.size());
        return contract;
    }

    // -------------------------------------------------------------------- patch

    /**
     * §7.2 PATCH semantics for scalar content: legal ONLY while
     * {@code status=PROPOSED} AND zero responses exist (ADR D7
     * freeze-on-first-response) — otherwise 409 {@code CONTRACT_CONTENT_FROZEN}.
     * Collection patches (parties/commitments/triggers) belong to the
     * authoring chunk; the freeze gate below is the single shared guard every
     * content path must pass.
     */
    @Governed(actionType = GovernedActions.CONTRACT_PATCH)
    @Transactional
    public Contract patch(UUID contractId, PatchContractCommand cmd) {
        Contract contract = lockedContract(contractId);
        requireContentMutable(contract);
        if (cmd.title() != null) {
            contract.setTitle(cmd.title());
        }
        if (cmd.context() != null) {
            contract.setContext(cmd.context());
        }
        if (cmd.monitorText() != null) {
            contract.setMonitorText(cmd.monitorText());
        }
        if (cmd.monitorMechanism() != null) {
            contract.setMonitorMechanism(cmd.monitorMechanism());
        }
        if (cmd.estimatedValue() != null) {
            contract.setEstimatedValue(cmd.estimatedValue());
        }
        if (cmd.estimatedValueAmount() != null) {
            contract.setEstimatedValueAmount(cmd.estimatedValueAmount());
        }
        if (cmd.reviewBoundary() != null) {
            contract.setReviewBoundary(cmd.reviewBoundary());
        }
        return contractRepository.save(contract);
    }

    // ------------------------------------------------------------------ respond

    /**
     * §7.2 POST /contracts/{id}/respond semantics under the §10.1 row lock:
     * immutable one-per-party response; duplicate IDENTICAL response is an
     * idempotent no-op return; conflicting response is 409
     * {@code RESPONSE_CONFLICT}; {@code declineReasoning} mandatory iff
     * DECLINED. May transition per §2.2 — the LAST required acceptance
     * activates; ANY required decline resolves DECLINED (legitimate, returns
     * normally — wire 200).
     */
    @Governed(actionType = GovernedActions.CONTRACT_RESPOND)
    @Transactional
    public Contract respond(UUID contractId, RespondCommand cmd) {
        Contract contract = lockedContract(contractId);
        ContractParty party = partyRepository.findById(cmd.partyId())
                .filter(p -> contract.getId().equals(p.getContractId()))
                .orElseThrow(() -> new EntityNotFoundException(
                        "ContractParty " + cmd.partyId() + " not found on contract " + contractId));

        // §10.2 idempotency/conflict — checked under the contract lock; the
        // UNIQUE(party_id) constraint is the backstop for out-of-band writers.
        var existing = responseRepository.findByPartyId(party.getId());
        if (existing.isPresent()) {
            if (existing.get().getResponse() == cmd.response()) {
                return contract; // duplicate identical -> idempotent 200
            }
            throw new ContractConflictException(
                    ContractConflictException.Code.RESPONSE_CONFLICT, contractId,
                    "party '" + party.getPartyLabel() + "' already responded "
                            + existing.get().getResponse() + " — responses are immutable (§2.2)");
        }

        if (contract.getStatus() != ContractStatus.PROPOSED) {
            // Terminal or ACTIVE: a state error, never decline vocabulary.
            throw new ContractConflictException(
                    ContractConflictException.Code.INVALID_CONTRACT_STATE, contractId,
                    "contract is " + contract.getStatus()
                            + " — party responses are recorded at proposal stage only (§2.2)");
        }

        UUID citedTriggerId = null;
        if (cmd.response() == PartyResponse.DECLINED) {
            if (isBlank(cmd.declineReasoning())) {
                throw new IllegalArgumentException(
                        "declineReasoning is required when response=DECLINED (§2.2)");
            }
            if (cmd.declineTriggerId() != null) {
                citedTriggerId = triggerRepository.findById(cmd.declineTriggerId())
                        .filter(t -> contract.getId().equals(t.getContractId()))
                        .orElseThrow(() -> new IllegalArgumentException("declineTriggerId "
                                + cmd.declineTriggerId() + " does not belong to contract " + contractId))
                        .getId();
            }
        }

        ContractPartyResponse response = new ContractPartyResponse();
        response.setPartyId(party.getId());
        response.setContractId(contract.getId());
        response.setResponse(cmd.response());
        response.setRespondedBy(cmd.respondedBy());
        response.setRespondedAt(clock.instant());
        response.setDeclineTriggerId(citedTriggerId);
        response.setDeclineReasoning(cmd.response() == PartyResponse.DECLINED
                ? cmd.declineReasoning() : null);
        responseRepository.save(response);

        if (cmd.response() == PartyResponse.DECLINED && party.isResponseRequired()) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("partyLabel", party.getPartyLabel());
            payload.put("declineReasoning", cmd.declineReasoning());
            if (citedTriggerId != null) {
                payload.put("declineTriggerId", citedTriggerId.toString());
            }
            declineUnderLock(contract, party.getId(), payload, cmd.actor());
        } else if (cmd.response() == PartyResponse.ACCEPTED && allRequiredPartiesAccepted(contract)) {
            activateUnderLock(contract, cmd.actor());
        }
        return contract;
    }

    // ------------------------------------------------------------ invoke_decline

    /**
     * §6.1 {@code invoke_decline} flow — ACTIVE → DECLINED citing a pre-agreed
     * trigger, reasoning mandatory. Returns normally: decline is a LEGITIMATE
     * terminal action (wire 200 + full detail, NEVER an error code).
     *
     * <p>The §6 advisory lanes run at the §6.1 gateway position via
     * {@link DeclineAdvisoryFlags} (3b-2): federation + null
     * {@code review_boundary} ⇒ ALLOW_WITH_FLAG
     * {@code REVIEW_BOUNDARY_UNCONFIGURED} (§6.2 null lane); open breaches ⇒
     * ALLOW_WITH_FLAG {@code OPEN_BREACHES_AT_DECLINE} (§6.3; spec §15 #5) —
     * open breaches never block decline and survive it (§2.2 invariants).
     * GATEWAY SEAM (3c): the full {@code gateway.evaluate(actionType=
     * 'contract.invoke_decline', …)} call — including the non-null
     * review-boundary window check (DENY/WOULD_DENY) — replaces the advisory
     * component with the gateway chunk.
     *
     * <p>3c PEP note: the {@code @Governed} aspect evaluates guard-store
     * guards for {@link GovernedActions#CONTRACT_INVOKE_DECLINE} at the §6.1
     * gateway position; the §6 ALLOW_WITH_FLAG advisory lanes below remain in
     * {@link DeclineAdvisoryFlags} until the §6.2 window-arithmetic sub-chunk
     * absorbs them. DECLINE itself stays a legitimate 200 — a deny here can
     * only come from an operator-authored guard on the decline action.
     */
    @Governed(actionType = GovernedActions.CONTRACT_INVOKE_DECLINE)
    @Transactional
    public Contract invokeDecline(UUID contractId, InvokeDeclineCommand cmd) {
        Contract contract = lockedContract(contractId);
        if (contract.getStatus() != ContractStatus.ACTIVE) {
            // PROPOSED declines go through respond (§6.1); terminal contracts
            // reject further mutating actions (§2.2) — a state error, not a
            // "decline error": decline itself returns 200.
            throw new ContractConflictException(
                    ContractConflictException.Code.INVALID_CONTRACT_STATE, contractId,
                    "invoke_decline requires status=ACTIVE (was " + contract.getStatus()
                            + "; PROPOSED declines go through respond)");
        }
        if (isBlank(cmd.reasoning())) {
            throw new IllegalArgumentException("invoke_decline requires non-blank reasoning (§6.1)");
        }
        ContractDeclineTrigger trigger = triggerRepository.findById(cmd.declineTriggerId())
                .filter(t -> contract.getId().equals(t.getContractId()))
                .orElseThrow(() -> new IllegalArgumentException("declineTriggerId "
                        + cmd.declineTriggerId() + " does not belong to contract " + contractId));
        if (trigger.getInvocableByPartyId() != null
                && !trigger.getInvocableByPartyId().equals(cmd.invokingPartyId())) {
            // Wire mapping refined by the write-endpoint chunk; pre-Phase-5
            // party identity is advisory (spec §15 #12).
            throw new IllegalArgumentException("decline trigger " + trigger.getId()
                    + " is invocable only by party " + trigger.getInvocableByPartyId() + " (§6.1)");
        }

        // §6.1 gateway position: advisory ALLOW_WITH_FLAG lanes (§6.2 null
        // boundary, §6.3 open breaches) — same transaction, never blocking.
        declineAdvisoryFlags.onInvokeDecline(contract, cmd.actor());

        ObjectNode triggerPayload = objectMapper.createObjectNode();
        triggerPayload.put("triggerText", trigger.getText());
        triggerPayload.put("reasoning", cmd.reasoning());
        eventService.append(contract.getId(), ContractEventService.DECLINE_TRIGGER_INVOKED,
                trigger.getId(), cmd.actor(), triggerPayload);

        ObjectNode declinePayload = objectMapper.createObjectNode();
        declinePayload.put("declineTriggerId", trigger.getId().toString());
        declinePayload.put("reasoning", cmd.reasoning());
        declineUnderLock(contract, trigger.getId(), declinePayload, cmd.actor());
        return contract;
    }

    // -------------------------------------------------------------- transitions

    /**
     * PROPOSED → ACTIVE side effects, one transaction (§2.2): activated_at;
     * ALL monitors ACTIVE with {@code next_run_at} initialized; ALL guards
     * live; CONTRACT_ACTIVATED event; ADR D14 hash anchor (mirror seam);
     * {@code ContractActivatedEvent} after commit (3c guard-cache
     * invalidation consumes it, §10.8).
     */
    private void activateUnderLock(Contract contract, ContractActor actor) {
        transition(contract, ContractStatus.ACTIVE);
        Instant now = clock.instant();
        contract.setActivatedAt(now);
        contractRepository.save(contract);

        for (ContractMonitor monitor : monitorRepository.findByContractId(contract.getId())) {
            monitor.setStatus(MonitorStatus.ACTIVE);
            // next_run_at initialized DUE NOW: the 3b sweep computes the real
            // next-cadence-boundary (+ jitter) schedule on its first claim
            // (§5.1/§5.2 period arithmetic is scheduler scope).
            monitor.setNextRunAt(now);
            monitorRepository.save(monitor);
        }
        for (ContractClauseGuard guard : guardRepository.findByContractId(contract.getId())) {
            guard.setActive(true);
            guardRepository.save(guard);
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("contractKey", contract.getContractKey());
        eventService.append(contract.getId(), ContractEventService.CONTRACT_ACTIVATED,
                null, actor, payload);
        mirror.onActivated(contract);
        publishAfterCommit(new ContractActivatedEvent(
                contract.getId(), contract.getDomainId(), contract.getContractKey()));
        log.info("Contract ACTIVATED [id={}, key={}]", contract.getId(), contract.getContractKey());
    }

    /**
     * Either decline transition's side effects, one transaction (§2.2):
     * resolved_at; ALL monitors SUSPENDED + ALL guards deactivated
     * atomically; CONTRACT_DECLINED event; ADR D14 hash anchor;
     * {@code ContractDeclinedEvent} after commit. Open breaches (none can
     * exist in 3a) survive decline and keep their human lifecycle (§2.2).
     */
    private void declineUnderLock(Contract contract, UUID refId, ObjectNode payload,
                                  ContractActor actor) {
        ContractStatus from = contract.getStatus();
        transition(contract, ContractStatus.DECLINED);
        contract.setResolvedAt(clock.instant());
        contractRepository.save(contract);

        for (ContractMonitor monitor : monitorRepository.findByContractId(contract.getId())) {
            if (monitor.getStatus() != MonitorStatus.SUSPENDED) {
                monitor.setStatus(MonitorStatus.SUSPENDED);
                monitorRepository.save(monitor);
            }
        }
        for (ContractClauseGuard guard : guardRepository.findByContractId(contract.getId())) {
            if (guard.isActive()) {
                guard.setActive(false);
                guardRepository.save(guard);
            }
        }

        payload.put("fromStatus", from.name());
        eventService.append(contract.getId(), ContractEventService.CONTRACT_DECLINED,
                refId, actor, payload);
        mirror.onDeclined(contract);
        publishAfterCommit(new ContractDeclinedEvent(
                contract.getId(), contract.getDomainId(), contract.getContractKey(), from));
        log.info("Contract DECLINED [id={}, key={}, from={}] — legitimate terminal outcome",
                contract.getId(), contract.getContractKey(), from);
    }

    /** Every status write goes through the §2.2 table-as-data (ADR D7). */
    private void transition(Contract contract, ContractStatus target) {
        if (!contract.getStatus().canTransitionTo(target)) {
            throw new ContractConflictException(
                    ContractConflictException.Code.INVALID_CONTRACT_STATE, contract.getId(),
                    "illegal transition " + contract.getStatus() + " -> " + target + " (§2.2)");
        }
        contract.setStatus(target);
    }

    // ------------------------------------------------------------------ helpers

    private Contract lockedContract(UUID contractId) {
        return contractRepository.findByIdForUpdate(contractId)
                .orElseThrow(() -> new EntityNotFoundException("Contract not found: " + contractId));
    }

    /**
     * ADR D7 freeze gate: content mutable only while PROPOSED AND zero
     * responses — otherwise 409 {@code CONTRACT_CONTENT_FROZEN} (even while
     * still PROPOSED: a recorded acceptance must never have text shift under
     * it).
     */
    private void requireContentMutable(Contract contract) {
        if (contract.getStatus() != ContractStatus.PROPOSED
                || !responseRepository.findByContractId(contract.getId()).isEmpty()) {
            throw new ContractConflictException(
                    ContractConflictException.Code.CONTRACT_CONTENT_FROZEN, contract.getId(),
                    "contract content is frozen once any party has responded or the contract has "
                            + "left PROPOSED (ADR D7)");
        }
    }

    /**
     * The §2.2 activation predicate, evaluated under the row lock (§10.1).
     *
     * <p>EXTENSION POINT (spec §15 #9): v1 activation is UNANIMITY of
     * {@code response_required} parties only — quorum/weighted acceptance for
     * many-party contracts has no documented semantics (flag-not-invent;
     * unblocks at the first many-party engagement).
     */
    private boolean allRequiredPartiesAccepted(Contract contract) {
        List<ContractParty> parties =
                partyRepository.findByContractIdOrderBySequenceOrderAsc(contract.getId());
        var responsesByParty = responseRepository.findByContractId(contract.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(
                        ContractPartyResponse::getPartyId, r -> r));
        return parties.stream()
                .filter(ContractParty::isResponseRequired)
                .allMatch(p -> {
                    ContractPartyResponse r = responsesByParty.get(p.getId());
                    return r != null && r.getResponse() == PartyResponse.ACCEPTED;
                });
    }

    private static UUID resolvePartyByLabel(List<ContractParty> parties, String label) {
        if (label == null || label.isBlank()) {
            return null; // null = any party may invoke (§3.2 table 5)
        }
        return parties.stream()
                .filter(p -> label.equals(p.getPartyLabel()))
                .findFirst()
                .map(ContractParty::getId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "invocableByPartyLabel '" + label + "' matches no party"));
    }

    /**
     * Resolves index-based monitor bindings into the persisted UUIDs:
     * {@code boundCommitments} → {@code rule_spec.commitmentIds},
     * {@code requiredPartyIndexes} → {@code rule_spec.requiredPartyIds}
     * (§3.2 table 8 rule_spec shape).
     */
    private JsonNode resolveRuleSpec(MonitorCommand mc, CreateContractCommand cmd,
                                     List<ContractParty> parties, UUID contractId) {
        ObjectNode spec = mc.ruleSpec() != null && mc.ruleSpec().isObject()
                ? ((ObjectNode) mc.ruleSpec()).deepCopy()
                : objectMapper.createObjectNode();
        if (mc.boundCommitments() != null && !mc.boundCommitments().isEmpty()) {
            ArrayNode ids = spec.putArray("commitmentIds");
            for (CommitmentRef ref : mc.boundCommitments()) {
                ContractParty party = parties.get(ref.partyIndex());
                List<ContractCommitment> commitments =
                        commitmentRepository.findByPartyIdOrderBySequenceOrderAsc(party.getId());
                ids.add(commitments.get(ref.commitmentIndex()).getId().toString());
            }
        }
        if (mc.requiredPartyIndexes() != null && !mc.requiredPartyIndexes().isEmpty()) {
            ArrayNode ids = spec.putArray("requiredPartyIds");
            for (Integer pi : mc.requiredPartyIndexes()) {
                ids.add(parties.get(pi).getId().toString());
            }
        }
        return spec.isEmpty() && mc.ruleSpec() == null ? null : spec;
    }

    /**
     * Spec §9: engine.contract.events are published AFTER COMMIT
     * (TransactionSynchronization) so listeners never observe an uncommitted
     * transition. Outside a transaction (not a supported call path —
     * every public verb is {@code @Transactional}) publish immediately.
     */
    private void publishAfterCommit(Object event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    eventPublisher.publishEvent(event);
                }
            });
        } else {
            eventPublisher.publishEvent(event);
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (isBlank(value)) {
            throw new IllegalArgumentException("create requires non-blank " + field);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
