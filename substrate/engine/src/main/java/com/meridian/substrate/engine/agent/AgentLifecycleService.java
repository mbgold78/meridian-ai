package com.meridian.substrate.engine.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentCriteriaGate;
import com.meridian.substrate.common.domain.AgentOversightLink;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.AgentStatusEvent;
import com.meridian.substrate.common.domain.AgentThrottleBudget;
import com.meridian.substrate.common.domain.enums.AgentEventType;
import com.meridian.substrate.common.domain.enums.AgentExecutionLocus;
import com.meridian.substrate.common.domain.enums.AgentGateRuleType;
import com.meridian.substrate.common.domain.enums.AgentGateStanding;
import com.meridian.substrate.common.domain.enums.AgentNotificationType;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.domain.enums.AgentReadinessSource;
import com.meridian.substrate.common.domain.enums.AgentRetiredReason;
import com.meridian.substrate.common.domain.enums.AgentSupervisoryModel;
import com.meridian.substrate.common.repo.AgentCriteriaGateRepository;
import com.meridian.substrate.common.repo.AgentOversightLinkRepository;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.common.repo.AgentStatusEventRepository;
import com.meridian.substrate.common.repo.AgentThrottleBudgetRepository;
import com.meridian.substrate.engine.agent.exception.AgentConflictException;
import com.meridian.substrate.engine.agent.exception.AgentDeployValidationException;
import com.meridian.substrate.engine.agent.exception.AgentDeployValidationException.Code;
import com.meridian.substrate.engine.agent.read.AgentDetail;
import com.meridian.substrate.engine.contract.gateway.Governed;
import com.meridian.substrate.engine.contract.gateway.GovernedActions;
import com.meridian.substrate.engine.contract.gateway.Ungoverned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Agent lifecycle writes (engine-spec agents.md §5; ADR-003 D1): every verb
 * follows the cascade §5.5 mechanics VERBATIM — PESSIMISTIC_WRITE on the
 * registration row, idempotency check under the lock, optional
 * {@code expectedEventSeq} fencing (→ 409 {@code AGENT_STALE_STATE} with the
 * current detail in the envelope {@code data}), event append + projection
 * update + outbox rows in ONE transaction (invariant I9; the shared chassis
 * is {@link AgentVerbMechanics}).
 *
 * <p><b>Phase 6b surface:</b> M1/M2 deploy, M6 pause, M7 resume, M8
 * throttle/unthrottle, M9 kill, M10 retire, M14 oversight replace, M15
 * attest-liveness, M18 bind/revoke principal. M3–M5 live in
 * {@link AgentPromotionService}, M11–M13 in {@link AgentInterventionService},
 * M16/M17 in {@link AgentControlConfirmationService} — all on this chassis.
 * There is deliberately NO amend-criteria verb: the mandate is immutable
 * post-deploy; change = retire + redeploy with
 * {@code predecessorRegistrationId} lineage. EXTENSION POINT(agent-2):
 * mandate amendment without redeploy is a flagged Mike decision (ADR-003 D5)
 * — if ever granted, amendments must be chain-mirrored, never bare updates.
 *
 * <p>Lifecycle verbs are REST-only, HUMAN-only: {@code actor_type=AGENT} is
 * rejected here (invariant I11) and no MCP lifecycle tools exist (ADR D16).
 * Only deploy and promote transit the contract gateway as governed actions
 * (§5.4/§8.4); pause/kill/retire are the registry flips the 6c gateway READS
 * — during the 6b→6c window every verb is RECORDED_INTENT and discloses
 * {@code "enforcement pending gateway"} (ADR-003 D17, acceptance-gated).
 * DEPLOYED and KILLED/RETIRED events mirror into the decision-log hash chain
 * ({@link AgentGovernanceChainMirror}, §11).
 */
@Service
public class AgentLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(AgentLifecycleService.class);

    /** §9.1 cadence allowlist (validated string, contracts D15 precedent). */
    private static final Set<String> GATE_CADENCES = Set.of("MONTHLY", "QUARTERLY", "WEEKLY", "DAILY");

    /** §3 budget-kind allowlist (validated string, NOT an enum — contracts D15 precedent). */
    static final Set<String> BUDGET_KINDS =
            Set.of("ACTIONS_PER_WINDOW", "TOKENS_PER_DAY", "SCHEDULE_WINDOW", "SAMPLING_PCT");

    private final AgentRegistrationRepository registrationRepository;
    private final AgentOversightLinkRepository oversightLinkRepository;
    private final AgentStatusEventRepository eventRepository;
    private final AgentCriteriaGateRepository criteriaGateRepository;
    private final AgentThrottleBudgetRepository throttleBudgetRepository;
    private final AgentNotificationService notificationService;
    private final AgentQueryService queryService;
    private final AgentLivenessService livenessService;
    private final AgentGovernanceChainMirror chainMirror;
    private final AgentRegistryLookup registryLookup;
    private final java.time.Clock clock;
    private final ObjectMapper objectMapper;

    public AgentLifecycleService(AgentRegistrationRepository registrationRepository,
                                 AgentOversightLinkRepository oversightLinkRepository,
                                 AgentStatusEventRepository eventRepository,
                                 AgentCriteriaGateRepository criteriaGateRepository,
                                 AgentThrottleBudgetRepository throttleBudgetRepository,
                                 AgentNotificationService notificationService,
                                 AgentQueryService queryService,
                                 AgentLivenessService livenessService,
                                 AgentGovernanceChainMirror chainMirror,
                                 AgentRegistryLookup registryLookup,
                                 java.time.Clock clock,
                                 ObjectMapper objectMapper) {
        this.registrationRepository = registrationRepository;
        this.oversightLinkRepository = oversightLinkRepository;
        this.eventRepository = eventRepository;
        this.criteriaGateRepository = criteriaGateRepository;
        this.throttleBudgetRepository = throttleBudgetRepository;
        this.notificationService = notificationService;
        this.queryService = queryService;
        this.livenessService = livenessService;
        this.chainMirror = chainMirror;
        this.registryLookup = registryLookup;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------------ commands

    /** One ordered mandate clause authored with the deploy (spec §7.2/§9.1). */
    public record CriteriaGateCommand(String gateText, AgentGateRuleType ruleType,
                                      JsonNode predicate, String cadence) {
    }

    /** One ordered oversight-chain entry (spec §7.2). */
    public record OversightEntry(String label, UUID memberId) {
    }

    /** §7.2 {@code POST /agents} body (engine-internal form). */
    public record DeployCommand(
            String idempotencyKey,
            AgentActor actor,
            UUID domainId,
            String agentKey,
            String name,
            String roleDescription,
            AgentExecutionLocus executionLocus,
            UUID capabilityId,
            String deployedByLabel,
            UUID deployedByMemberId,
            String deploymentCriteria,
            List<CriteriaGateCommand> criteriaGates,
            List<OversightEntry> oversightChain,
            AgentSupervisoryModel supervisoryModel,
            UUID targetProcessId,
            JsonNode targetStepIds,
            Integer agentReadinessScore,
            AgentReadinessSource readinessSource,
            JsonNode requiredSystemAccess,
            JsonNode requiredDataAccess,
            UUID initiativeId,
            String notes,
            Boolean readOnly,
            JsonNode scheduleWindow,
            Integer samplingPct,
            String mcpPrincipalId,
            String liveJustification,
            UUID predecessorRegistrationId) {
    }

    /** Deploy result: {@code replayed=true} ⇒ idempotent replay (HTTP 200, no new rows). */
    public record DeployOutcome(AgentDetail detail, boolean replayed) {
    }

    // ------------------------------------------------------------------ M1/M2 deploy

    /**
     * §5.1 M1/M2 — deploy: the registration is BORN at this act with the four
     * [AGENT-PROVENANCE] facts + ≥1 oversight link (invariant I1), the frozen
     * mandate + ordered criteria gates + {@code criteria_digest} (invariant
     * I2), and the DEPLOYED event (event_seq 1) whose payload is the full
     * frozen spec snapshot — all in ONE transaction (invariant I9). Defaults
     * applied as data: born SHADOW (P11 observation-only trial),
     * {@code read_only=true}; deploy-to-LIVE requires a non-blank
     * {@code liveJustification}, recorded in the snapshot (M2). The DEPLOYED
     * event mirrors into the decision-log chain WITH the criteria digest
     * (§11) after this transaction commits.
     */
    @Governed(actionType = GovernedActions.AGENT_DEPLOY)
    @Transactional
    public DeployOutcome deploy(DeployCommand cmd) {
        AgentVerbMechanics.requireIdempotencyKey(cmd.idempotencyKey());
        AgentActor.requireHumanShaped(cmd.actor());
        validateDeployGate(cmd);

        Optional<AgentRegistration> existing =
                registrationRepository.findByDomainIdAndAgentKey(cmd.domainId(), cmd.agentKey());
        if (existing.isPresent()) {
            AgentRegistration reg = existing.get();
            Optional<AgentStatusEvent> replayed = eventRepository
                    .findByAgentRegistrationIdAndIdempotencyKey(reg.getId(), cmd.idempotencyKey());
            if (replayed.isPresent() && replayed.get().getEventType() == AgentEventType.DEPLOYED) {
                // Idempotent replay — the original result re-projected, no new rows (cascade D8).
                return new DeployOutcome(queryService.detail(reg), true);
            }
            throw new AgentConflictException(AgentConflictException.Code.AGENT_KEY_CONFLICT,
                    queryService.detail(reg),
                    "agent_key '" + cmd.agentKey() + "' already exists in domain " + cmd.domainId()
                            + " (uq_agent_domain_key); re-deploy = new key or retire + redeploy with"
                            + " predecessorRegistrationId lineage (ADR-003 D5)");
        }

        Instant now = clock.instant();
        boolean live = cmd.liveJustification() != null && !cmd.liveJustification().isBlank();

        List<AgentCriteriaDigest.GateInput> digestInputs = cmd.criteriaGates().stream()
                .map(g -> new AgentCriteriaDigest.GateInput(
                        g.gateText(), g.ruleType().name(), g.predicate(), normalizeCadence(g.cadence())))
                .toList();
        String criteriaDigest = AgentCriteriaDigest.compute(cmd.deploymentCriteria(), digestInputs);

        AgentRegistration reg = new AgentRegistration();
        reg.setDomainId(cmd.domainId());
        reg.setAgentKey(cmd.agentKey());
        reg.setName(cmd.name());
        reg.setRoleDescription(cmd.roleDescription());
        reg.setCapabilityId(cmd.capabilityId());
        reg.setExecutionLocus(cmd.executionLocus());
        reg.setMcpPrincipalId(cmd.mcpPrincipalId());
        reg.setDeployedByMemberId(cmd.deployedByMemberId());
        reg.setDeployedByLabel(cmd.deployedByLabel());
        reg.setDeployedAt(now);                              // server clock = the act (§5.4)
        reg.setDeploymentCriteria(cmd.deploymentCriteria()); // VERBATIM; immutable post-deploy (I2)
        reg.setCriteriaDigest(criteriaDigest);
        reg.setPredecessorRegistrationId(cmd.predecessorRegistrationId());
        reg.setTargetProcessId(cmd.targetProcessId());
        reg.setTargetStepIds(cmd.targetStepIds());
        reg.setAgentReadinessScore(cmd.agentReadinessScore());
        reg.setReadinessSource(cmd.readinessSource());
        reg.setRequiredSystemAccess(cmd.requiredSystemAccess());
        reg.setRequiredDataAccess(cmd.requiredDataAccess());
        reg.setSupervisoryModel(cmd.supervisoryModel());
        reg.setInitiativeId(cmd.initiativeId());
        reg.setNotes(cmd.notes());
        reg.setReadOnly(cmd.readOnly() == null || cmd.readOnly());   // P11 default true, as data
        reg.setScheduleWindow(cmd.scheduleWindow());
        reg.setSamplingPct(cmd.samplingPct());
        reg.setOperationalMode(live ? AgentOperationalMode.LIVE : AgentOperationalMode.SHADOW);
        reg.setShadowEnteredAt(live ? null : now);
        reg.setEventSeq(1);                                  // DEPLOYED is event 1 (I10)
        registrationRepository.save(reg);   // managed instance — same object, kept effectively final for the fence lambdas

        List<AgentOversightLink> links = new ArrayList<>();
        int position = 1;
        for (OversightEntry entry : cmd.oversightChain()) {
            AgentOversightLink link = new AgentOversightLink();
            link.setAgentRegistrationId(reg.getId());
            link.setPosition(position++);
            link.setMemberId(entry.memberId());
            link.setLabel(entry.label());
            links.add(link);
        }
        oversightLinkRepository.saveAll(links);

        List<AgentCriteriaGate> gates = new ArrayList<>();
        int order = 0;
        for (CriteriaGateCommand g : cmd.criteriaGates()) {
            AgentCriteriaGate gate = new AgentCriteriaGate();
            gate.setAgentRegistrationId(reg.getId());
            gate.setSequenceOrder(order++);
            gate.setGateText(g.gateText());
            gate.setRuleType(g.ruleType());
            gate.setPredicate(g.predicate());
            gate.setCadence(normalizeCadence(g.cadence()));
            gate.setStanding(AgentGateStanding.UNEVALUATED);
            gates.add(gate);
        }
        criteriaGateRepository.saveAll(gates);

        AgentStatusEvent event = new AgentStatusEvent();
        event.setAgentRegistrationId(reg.getId());
        event.setDomainId(reg.getDomainId());
        event.setEventSeq(1);
        event.setEventType(AgentEventType.DEPLOYED);
        event.setActorType(cmd.actor().type());
        event.setActorMemberId(cmd.actor().memberId());
        event.setActorLabel(cmd.actor().label());
        event.setPayload(frozenSpecSnapshot(cmd, criteriaDigest, live));
        event.setIdempotencyKey(cmd.idempotencyKey());
        event.setOccurredAt(now);
        event = eventRepository.save(event);

        chainMirror.onDeployed(reg, event);                  // §11 — anchors after commit

        log.info("Deployed agent [key={}, domainId={}, mode={}, gates={}, oversight={}]",
                reg.getAgentKey(), reg.getDomainId(), reg.getOperationalMode(),
                gates.size(), links.size());
        return new DeployOutcome(queryService.detail(reg), false);
    }

    // ------------------------------------------------------------------ M6 pause

    /** 6a-signature convenience (DemoPack seed lane) — no fencing token. */
    @Ungoverned(reason = "delegates to the fenced pause overload — see its reason"
            + " (engine-spec agents.md §5.1 M6, §5.3; ADR-003 D6)")
    @Transactional
    public AgentRegistration pause(UUID agentRegistrationId, String idempotencyKey,
                                   AgentActor actor, String reason) {
        return pause(agentRegistrationId, idempotencyKey, actor, reason, null);
    }

    /**
     * §5.1 M6 — pause: LIVE|SHADOW → PAUSED, reason MANDATORY,
     * {@code mode_before_pause} recorded (resume returns there, M7) — warn
     * semantics, HTTP 200 at the controller, NEVER error-colored (spec
     * non-negotiable #2 / invariant I5).
     *
     * <p>Honest control (ADR-003 D6/D17): in the 6b→6c window EVERY locus is
     * {@code RECORDED_INTENT} — no gateway enforcement bean exists yet — so
     * the control-confirmation projection opens as PENDING
     * ({@code control_pending_verb='pause'}); the oversight chain's position
     * 1 is notified via the same-tx outbox (CONTROL_REQUESTED, §13.4).
     * Substrate never claims to have stopped what it cannot reach.
     */
    @Ungoverned(reason = "only agent.deploy and agent.promote transit the gateway as governed"
            + " actions per spec (§5.4/§8.4) — pause is a human suspension act whose 6c"
            + " enforcement is the registry flip read by the gateway, not a guarded action"
            + " itself (engine-spec agents.md §5.3; ADR-003 D6)")
    @Transactional
    public AgentRegistration pause(UUID agentRegistrationId, String idempotencyKey,
                                   AgentActor actor, String reason, Integer expectedEventSeq) {
        AgentVerbMechanics.requireIdempotencyKey(idempotencyKey);
        AgentActor.requireHumanShaped(actor);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is mandatory on pause (M6; ck_ase_reason)");
        }
        AgentRegistration reg = AgentVerbMechanics.lock(registrationRepository, agentRegistrationId);
        if (AgentVerbMechanics.replay(eventRepository, reg, idempotencyKey,
                AgentEventType.PAUSED).isPresent()) {
            return reg;                        // idempotent replay — no new rows (cascade D8)
        }
        AgentVerbMechanics.fence(reg, expectedEventSeq, () -> queryService.detail(reg));
        AgentVerbMechanics.requireNotTerminal(reg, () -> queryService.detail(reg), "pause");
        if (reg.getOperationalMode() == AgentOperationalMode.PAUSED) {
            throw new AgentConflictException(AgentConflictException.Code.AGENT_ILLEGAL_TRANSITION,
                    queryService.detail(reg),
                    "agent '" + reg.getAgentKey() + "' is already paused (M6: LIVE|SHADOW → PAUSED)");
        }

        Instant now = clock.instant();
        reg.setModeBeforePause(reg.getOperationalMode());
        reg.setOperationalMode(AgentOperationalMode.PAUSED);
        openControlTracking(reg, "pause", now);

        AgentStatusEvent event = AgentVerbMechanics.appendEvent(eventRepository, reg,
                AgentEventType.PAUSED, actor, reason, null, null, idempotencyKey, now);
        registrationRepository.save(reg);   // managed instance — same object, kept effectively final for the fence lambdas
        notifyControlRequested(reg, event, "pause", reason);
        registryLookup.invalidate(reg.getId());   // §8.3: gate cache flips with the verb (30s TTL net)

        log.info("Paused agent [key={}, domainId={}, modeBefore={}]",
                reg.getAgentKey(), reg.getDomainId(), reg.getModeBeforePause());
        return reg;
    }

    // ------------------------------------------------------------------ M7 resume

    /**
     * §5.1 M7 — resume: PAUSED → {@code mode_before_pause}. The pending pause
     * control is withdrawn (tracking cleared — there is no longer an order
     * for the runtime to acknowledge); {@code shadow_entered_at} is NOT reset
     * (only DEMOTED_TO_SHADOW resets it, M13).
     */
    @Ungoverned(reason = "only agent.deploy and agent.promote transit the gateway as governed"
            + " actions (§5.4/§8.4); resume is the registry flip back — substrate-held state IS"
            + " the control plane (engine-spec agents.md §5.3 row 3)")
    @Transactional
    public AgentRegistration resume(UUID agentRegistrationId, String idempotencyKey,
                                    AgentActor actor, Integer expectedEventSeq) {
        AgentVerbMechanics.requireIdempotencyKey(idempotencyKey);
        AgentActor.requireHumanShaped(actor);
        AgentRegistration reg = AgentVerbMechanics.lock(registrationRepository, agentRegistrationId);
        if (AgentVerbMechanics.replay(eventRepository, reg, idempotencyKey,
                AgentEventType.RESUMED).isPresent()) {
            return reg;
        }
        AgentVerbMechanics.fence(reg, expectedEventSeq, () -> queryService.detail(reg));
        AgentVerbMechanics.requireNotTerminal(reg, () -> queryService.detail(reg), "resume");
        if (reg.getOperationalMode() != AgentOperationalMode.PAUSED) {
            throw new AgentConflictException(AgentConflictException.Code.AGENT_ILLEGAL_TRANSITION,
                    queryService.detail(reg),
                    "resume requires operational_mode=paused (current: "
                            + reg.getOperationalMode().wireValue() + ") — M7 guard");
        }

        Instant now = clock.instant();
        AgentOperationalMode target = reg.getModeBeforePause() != null
                ? reg.getModeBeforePause() : AgentOperationalMode.SHADOW;
        reg.setOperationalMode(target);
        reg.setModeBeforePause(null);
        clearControlTracking(reg);

        AgentVerbMechanics.appendEvent(eventRepository, reg, AgentEventType.RESUMED, actor,
                null, null, null, idempotencyKey, now);
        registrationRepository.save(reg);   // managed instance — same object, kept effectively final for the fence lambdas
        registryLookup.invalidate(reg.getId());   // §8.3: gate cache flips with the verb (30s TTL net)

        log.info("Resumed agent [key={}, domainId={}, mode={}]",
                reg.getAgentKey(), reg.getDomainId(), reg.getOperationalMode());
        return reg;
    }

    // -------------------------------------------------------- M8 throttle/unthrottle

    /**
     * §5.1 M8 — throttle: any non-RETIRED mode, mode UNCHANGED; the validated
     * budget lands as a first-class row (never JSON on the hot path — ADR-003
     * D12), one ACTIVE per (agent, kind) (service-enforced; V6_1 partial
     * unique is the PG backstop — re-throttling a kind replaces the active
     * row in the same transaction). Enforcement honesty: ACTIONS_PER_WINDOW
     * gets a 6c gateway evaluator; the rest are schema-ready published
     * obligations (EXTENSION POINT(agent-10) — no evaluator in v1; a budget
     * for a non-substrate locus is never a fabricated block).
     */
    @Ungoverned(reason = "only agent.deploy and agent.promote transit the gateway as governed"
            + " actions (§5.4/§8.4); throttle writes the budget rows the 6c gateway evaluates"
            + " (engine-spec agents.md §5.1 M8; ADR-003 D12)")
    @Transactional
    public AgentRegistration throttle(UUID agentRegistrationId, String idempotencyKey,
                                      AgentActor actor, String budgetKind, Double limitValue,
                                      Integer windowMinutes, String note, Integer expectedEventSeq) {
        AgentVerbMechanics.requireIdempotencyKey(idempotencyKey);
        AgentActor.requireHumanShaped(actor);
        String kind = normalizeBudgetKind(budgetKind);
        AgentRegistration reg = AgentVerbMechanics.lock(registrationRepository, agentRegistrationId);
        if (AgentVerbMechanics.replay(eventRepository, reg, idempotencyKey,
                AgentEventType.THROTTLE_SET).isPresent()) {
            return reg;
        }
        AgentVerbMechanics.fence(reg, expectedEventSeq, () -> queryService.detail(reg));
        AgentVerbMechanics.requireNotTerminal(reg, () -> queryService.detail(reg), "throttle");

        Instant now = clock.instant();
        // Replace-in-place: deactivate the current active row of this kind
        // (uq_active_budget_per_kind tolerates exactly one), then insert.
        throttleBudgetRepository.findByAgentRegistrationIdAndBudgetKindAndActiveTrue(
                reg.getId(), kind).ifPresent(existing -> {
                    existing.setActive(false);
                    throttleBudgetRepository.save(existing);
                });
        AgentThrottleBudget budget = new AgentThrottleBudget();
        budget.setAgentRegistrationId(reg.getId());
        budget.setBudgetKind(kind);
        budget.setLimitValue(limitValue);
        budget.setWindowMinutes(windowMinutes);
        budget.setActive(true);
        budget.setSetByLabel(actor.label());
        budget.setSetAt(now);
        budget.setNote(note);
        throttleBudgetRepository.save(budget);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("budgetKind", kind);
        if (limitValue != null) {
            payload.put("limitValue", limitValue);
        }
        if (windowMinutes != null) {
            payload.put("windowMinutes", windowMinutes);
        }
        if (note != null) {
            payload.put("note", note);
        }
        AgentStatusEvent event = AgentVerbMechanics.appendEvent(eventRepository, reg,
                AgentEventType.THROTTLE_SET, actor, null, payload, null, idempotencyKey, now);
        registrationRepository.save(reg);   // managed instance — same object, kept effectively final for the fence lambdas
        notifyControlRequested(reg, event, "throttle", kind);
        registryLookup.invalidate(reg.getId());   // budget rows are gate state (§8.3 #4)

        log.info("Throttled agent [key={}, kind={}, limit={}, window={}]",
                reg.getAgentKey(), kind, limitValue, windowMinutes);
        return reg;
    }

    /** §5.1 M8 — unthrottle ({@code DELETE …/throttle/{kind}}): clears the active budget of a kind. */
    @Ungoverned(reason = "only agent.deploy and agent.promote transit the gateway as governed"
            + " actions (§5.4/§8.4); unthrottle clears a budget row (engine-spec agents.md §5.1 M8)")
    @Transactional
    public AgentRegistration unthrottle(UUID agentRegistrationId, String idempotencyKey,
                                        AgentActor actor, String budgetKind, Integer expectedEventSeq) {
        AgentVerbMechanics.requireIdempotencyKey(idempotencyKey);
        AgentActor.requireHumanShaped(actor);
        String kind = normalizeBudgetKind(budgetKind);
        AgentRegistration reg = AgentVerbMechanics.lock(registrationRepository, agentRegistrationId);
        if (AgentVerbMechanics.replay(eventRepository, reg, idempotencyKey,
                AgentEventType.THROTTLE_CLEARED).isPresent()) {
            return reg;
        }
        AgentVerbMechanics.fence(reg, expectedEventSeq, () -> queryService.detail(reg));
        AgentVerbMechanics.requireNotTerminal(reg, () -> queryService.detail(reg), "unthrottle");
        AgentThrottleBudget active = throttleBudgetRepository
                .findByAgentRegistrationIdAndBudgetKindAndActiveTrue(reg.getId(), kind)
                .orElseThrow(() -> new AgentConflictException(
                        AgentConflictException.Code.AGENT_ILLEGAL_TRANSITION,
                        queryService.detail(reg),
                        "no active '" + kind + "' budget exists on agent '" + reg.getAgentKey()
                                + "' (M8: unthrottle clears an active budget)"));

        Instant now = clock.instant();
        active.setActive(false);
        throttleBudgetRepository.save(active);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("budgetKind", kind);
        payload.put("clearedBudgetId", active.getId().toString());
        AgentVerbMechanics.appendEvent(eventRepository, reg, AgentEventType.THROTTLE_CLEARED,
                actor, null, payload, null, idempotencyKey, now);
        registrationRepository.save(reg);   // managed instance — same object, kept effectively final for the fence lambdas
        registryLookup.invalidate(reg.getId());   // budget rows are gate state (§8.3 #4)

        log.info("Throttle cleared [key={}, kind={}]", reg.getAgentKey(), kind);
        return reg;
    }

    // -------------------------------------------------------------- M9/M10 kill/retire

    /**
     * §5.1 M9 — kill (emergency): any non-RETIRED → RETIRED with
     * {@code retired_reason=KILLED}; reason mandatory; HTTP 200, warn tone —
     * a legitimate human governance act, NEVER error-colored. The KILLED
     * event chain-mirrors with the reason discriminator (§11).
     * EXTENSION POINT(agent-1): the fifth wire value ({@code retired}), the
     * kill-vs-retire vocabulary and the §5.2 projection precedence are
     * Mike-ratification items (ADR-003 D4) — all projector-only changes if
     * overruled.
     */
    @Ungoverned(reason = "only agent.deploy and agent.promote transit the gateway as governed"
            + " actions (§5.4/§8.4) — kill is the terminal registry flip the 6c gateway reads"
            + " (engine-spec agents.md §5.1 M9, §5.3; ADR-003 D4/D6)")
    @Transactional
    public AgentRegistration kill(UUID agentRegistrationId, String idempotencyKey,
                                  AgentActor actor, String reason, Integer expectedEventSeq) {
        return terminate(agentRegistrationId, idempotencyKey, actor, reason, expectedEventSeq,
                AgentEventType.KILLED, AgentRetiredReason.KILLED, "kill");
    }

    /** §5.1 M10 — retire (orderly): same terminal absorption, {@code retired_reason=RETIRED}. */
    @Ungoverned(reason = "only agent.deploy and agent.promote transit the gateway as governed"
            + " actions (§5.4/§8.4) — retire is the terminal registry flip the 6c gateway reads"
            + " (engine-spec agents.md §5.1 M10, §5.3; ADR-003 D4/D6)")
    @Transactional
    public AgentRegistration retire(UUID agentRegistrationId, String idempotencyKey,
                                    AgentActor actor, String reason, Integer expectedEventSeq) {
        return terminate(agentRegistrationId, idempotencyKey, actor, reason, expectedEventSeq,
                AgentEventType.RETIRED, AgentRetiredReason.RETIRED, "retire");
    }

    private AgentRegistration terminate(UUID agentRegistrationId, String idempotencyKey,
                                        AgentActor actor, String reason, Integer expectedEventSeq,
                                        AgentEventType eventType, AgentRetiredReason retiredReason,
                                        String verb) {
        AgentVerbMechanics.requireIdempotencyKey(idempotencyKey);
        AgentActor.requireHumanShaped(actor);
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "reason is mandatory on " + verb + " (M9/M10; ck_ase_reason)");
        }
        AgentRegistration reg = AgentVerbMechanics.lock(registrationRepository, agentRegistrationId);
        if (AgentVerbMechanics.replay(eventRepository, reg, idempotencyKey, eventType).isPresent()) {
            return reg;
        }
        AgentVerbMechanics.fence(reg, expectedEventSeq, () -> queryService.detail(reg));
        AgentVerbMechanics.requireNotTerminal(reg, () -> queryService.detail(reg), verb);

        Instant now = clock.instant();
        reg.setOperationalMode(AgentOperationalMode.RETIRED);
        reg.setModeBeforePause(null);
        reg.setRetiredAt(now);
        reg.setRetiredReason(retiredReason);
        reg.setRetiredByLabel(actor.label());
        openControlTracking(reg, verb, now);   // RECORDED_INTENT suspension tracking (D6)

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("retiredReason", retiredReason.name());
        AgentStatusEvent event = AgentVerbMechanics.appendEvent(eventRepository, reg, eventType,
                actor, reason, payload, null, idempotencyKey, now);
        registrationRepository.save(reg);   // managed instance — same object, kept effectively final for the fence lambdas
        notifyControlRequested(reg, event, verb, reason);
        registryLookup.invalidate(reg.getId());   // §8.3: gate cache flips with the verb (30s TTL net)
        chainMirror.onRetired(reg, event);     // §11 — termination is chain-anchored either way

        log.info("Agent reached terminal state [key={}, verb={}, reason={}]",
                reg.getAgentKey(), verb, retiredReason);
        return reg;
    }

    // ------------------------------------------------------------ M14 oversight replace

    /**
     * §5.1 M14 — oversight replace (full replacement, ≥1 link): accountability
     * routing evolves; the mandate does not (ADR-003 D5). Before/after chains
     * land in the OVERSIGHT_CHANGED payload; NOT chain-mirrored (§11 bounds).
     * EXTENSION POINT(agent-16): chains are labels + soft member refs —
     * validation against the org chart is org-model hardening; mis-specified
     * chains are silently legitimate in v1 (cascade §13.11 parity).
     */
    @Ungoverned(reason = "only agent.deploy and agent.promote transit the gateway as governed"
            + " actions (§5.4/§8.4); oversight replacement is an evented accountability-routing"
            + " act (engine-spec agents.md §5.1 M14)")
    @Transactional
    public AgentRegistration replaceOversight(UUID agentRegistrationId, String idempotencyKey,
                                              AgentActor actor, List<OversightEntry> chain,
                                              Integer expectedEventSeq) {
        AgentVerbMechanics.requireIdempotencyKey(idempotencyKey);
        AgentActor.requireHumanShaped(actor);
        if (chain == null || chain.isEmpty()
                || chain.stream().anyMatch(e -> e == null || e.label() == null || e.label().isBlank())) {
            throw new IllegalArgumentException(
                    "oversight replacement needs >=1 ordered entry with a non-blank label (M14; I1)");
        }
        AgentRegistration reg = AgentVerbMechanics.lock(registrationRepository, agentRegistrationId);
        if (AgentVerbMechanics.replay(eventRepository, reg, idempotencyKey,
                AgentEventType.OVERSIGHT_CHANGED).isPresent()) {
            return reg;
        }
        AgentVerbMechanics.fence(reg, expectedEventSeq, () -> queryService.detail(reg));
        AgentVerbMechanics.requireNotTerminal(reg, () -> queryService.detail(reg), "oversight");

        Instant now = clock.instant();
        List<AgentOversightLink> before =
                oversightLinkRepository.findByAgentRegistrationIdOrderByPositionAsc(reg.getId());
        ObjectNode payload = objectMapper.createObjectNode();
        ArrayNode beforeNode = payload.putArray("before");
        for (AgentOversightLink link : before) {
            ObjectNode n = beforeNode.addObject();
            n.put("label", link.getLabel());
            n.put("memberId", link.getMemberId() == null ? null : link.getMemberId().toString());
        }
        ArrayNode afterNode = payload.putArray("after");
        oversightLinkRepository.deleteAll(before);
        oversightLinkRepository.flush();      // uq_aol_position frees before re-insert
        int position = 1;
        List<AgentOversightLink> replacement = new ArrayList<>();
        for (OversightEntry entry : chain) {
            AgentOversightLink link = new AgentOversightLink();
            link.setAgentRegistrationId(reg.getId());
            link.setPosition(position++);
            link.setMemberId(entry.memberId());
            link.setLabel(entry.label());
            replacement.add(link);
            ObjectNode n = afterNode.addObject();
            n.put("label", entry.label());
            n.put("memberId", entry.memberId() == null ? null : entry.memberId().toString());
        }
        oversightLinkRepository.saveAll(replacement);

        AgentVerbMechanics.appendEvent(eventRepository, reg, AgentEventType.OVERSIGHT_CHANGED,
                actor, null, payload, null, idempotencyKey, now);
        registrationRepository.save(reg);   // managed instance — same object, kept effectively final for the fence lambdas

        log.info("Oversight chain replaced [key={}, links {}→{}]",
                reg.getAgentKey(), before.size(), replacement.size());
        return reg;
    }

    // ------------------------------------------------------------ M15 attest-liveness

    /**
     * §5.1 M15 — attest-liveness: a HUMAN "I verified this agent is running"
     * — LIVENESS_ATTESTED event (mode unchanged) + ATTESTATION sighting +
     * {@code last_attested_*} projections, one transaction. The honest-
     * liveness keystone for ATTEST_ONLY agents (ADR-003 D11): attestation is
     * evidence, registration recency never is (I7).
     */
    @Ungoverned(reason = "only agent.deploy and agent.promote transit the gateway as governed"
            + " actions (§5.4/§8.4); attestation is a human evidence act feeding the §10"
            + " liveness derivations (engine-spec agents.md §5.1 M15)")
    @Transactional
    public AgentRegistration attestLiveness(UUID agentRegistrationId, String idempotencyKey,
                                            AgentActor actor, String statement,
                                            JsonNode evidenceRefs, Integer expectedEventSeq) {
        AgentVerbMechanics.requireIdempotencyKey(idempotencyKey);
        AgentActor.requireHumanShaped(actor);
        AgentRegistration reg = AgentVerbMechanics.lock(registrationRepository, agentRegistrationId);
        if (AgentVerbMechanics.replay(eventRepository, reg, idempotencyKey,
                AgentEventType.LIVENESS_ATTESTED).isPresent()) {
            return reg;
        }
        AgentVerbMechanics.fence(reg, expectedEventSeq, () -> queryService.detail(reg));
        AgentVerbMechanics.requireNotTerminal(reg, () -> queryService.detail(reg), "attest-liveness");

        Instant now = clock.instant();
        ObjectNode payload = null;
        if (statement != null && !statement.isBlank()) {
            payload = objectMapper.createObjectNode();
            payload.put("statement", statement);
        }
        AgentVerbMechanics.appendEvent(eventRepository, reg, AgentEventType.LIVENESS_ATTESTED,
                actor, null, payload, evidenceRefs, idempotencyKey, now);
        registrationRepository.save(reg);
        // The sighting + last_attested projections ride the telemetry lane —
        // hourly-bucket deduped; same lock, same transaction (I7/I9).
        livenessService.recordAttestation(reg.getId(), actor.label(),
                statement == null || statement.isBlank() ? null : statement);

        log.info("Liveness attested [key={}, by={}]", reg.getAgentKey(), actor.label());
        return reg;     // same persistence-context instance the telemetry lane just updated
    }

    // ----------------------------------------------------- M18 bind/revoke principal

    /**
     * §5.1 M18 — bind principal (operator REST): binds
     * {@code Principal(AGENT, …)} to this registration; unique when present
     * (uq_agent_principal partial unique; service-enforced on H2).
     * EXTENSION POINT(agent-17): PersonaContext / X-MCP-Principal identities
     * are spoofable and recorded verbatim until Phase-5 OIDC (S9 accepted) —
     * the column is ready, no schema change.
     */
    @Ungoverned(reason = "only agent.deploy and agent.promote transit the gateway as governed"
            + " actions (§5.4/§8.4); principal binding is an evented operator act on the"
            + " registration row (engine-spec agents.md §5.1 M18)")
    @Transactional
    public AgentRegistration bindPrincipal(UUID agentRegistrationId, String idempotencyKey,
                                           AgentActor actor, String mcpPrincipalId,
                                           Integer expectedEventSeq) {
        AgentVerbMechanics.requireIdempotencyKey(idempotencyKey);
        AgentActor.requireHumanShaped(actor);
        if (mcpPrincipalId == null || mcpPrincipalId.isBlank()) {
            throw new IllegalArgumentException("mcpPrincipalId is required on bind (M18)");
        }
        AgentRegistration reg = AgentVerbMechanics.lock(registrationRepository, agentRegistrationId);
        if (AgentVerbMechanics.replay(eventRepository, reg, idempotencyKey,
                AgentEventType.PRINCIPAL_BOUND).isPresent()) {
            return reg;
        }
        AgentVerbMechanics.fence(reg, expectedEventSeq, () -> queryService.detail(reg));
        AgentVerbMechanics.requireNotTerminal(reg, () -> queryService.detail(reg), "bind-principal");
        Optional<AgentRegistration> holder = registrationRepository
                .findByMcpPrincipalId(mcpPrincipalId.trim());
        if (holder.isPresent() && !holder.get().getId().equals(reg.getId())) {
            throw new IllegalArgumentException("mcp principal '" + mcpPrincipalId
                    + "' is already bound to agent '" + holder.get().getAgentKey()
                    + "' (uq_agent_principal — gateway resolution must stay unique)");
        }

        Instant now = clock.instant();
        String previous = reg.getMcpPrincipalId();
        reg.setMcpPrincipalId(mcpPrincipalId.trim());
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("mcpPrincipalId", reg.getMcpPrincipalId());
        payload.put("previous", previous);
        AgentVerbMechanics.appendEvent(eventRepository, reg, AgentEventType.PRINCIPAL_BOUND,
                actor, null, payload, null, idempotencyKey, now);
        registrationRepository.save(reg);   // managed instance — same object, kept effectively final for the fence lambdas
        registryLookup.invalidate(reg.getId());           // resolution key changed (M18)
        registryLookup.invalidatePrincipal(reg.getMcpPrincipalId());
        registryLookup.invalidatePrincipal(previous);

        log.info("Principal bound [key={}, principal={}]", reg.getAgentKey(), reg.getMcpPrincipalId());
        return reg;
    }

    /** §5.1 M18 — revoke principal ({@code DELETE …/principal}). */
    @Ungoverned(reason = "only agent.deploy and agent.promote transit the gateway as governed"
            + " actions (§5.4/§8.4); principal revocation is an evented operator act on the"
            + " registration row (engine-spec agents.md §5.1 M18)")
    @Transactional
    public AgentRegistration revokePrincipal(UUID agentRegistrationId, String idempotencyKey,
                                             AgentActor actor, Integer expectedEventSeq) {
        AgentVerbMechanics.requireIdempotencyKey(idempotencyKey);
        AgentActor.requireHumanShaped(actor);
        AgentRegistration reg = AgentVerbMechanics.lock(registrationRepository, agentRegistrationId);
        if (AgentVerbMechanics.replay(eventRepository, reg, idempotencyKey,
                AgentEventType.PRINCIPAL_REVOKED).isPresent()) {
            return reg;
        }
        AgentVerbMechanics.fence(reg, expectedEventSeq, () -> queryService.detail(reg));
        AgentVerbMechanics.requireNotTerminal(reg, () -> queryService.detail(reg), "revoke-principal");
        if (reg.getMcpPrincipalId() == null) {
            throw new IllegalArgumentException("no mcp principal is bound to agent '"
                    + reg.getAgentKey() + "' (M18: revoke clears an existing binding)");
        }

        Instant now = clock.instant();
        ObjectNode payload = objectMapper.createObjectNode();
        String revoked = reg.getMcpPrincipalId();
        payload.put("previous", revoked);
        reg.setMcpPrincipalId(null);
        AgentVerbMechanics.appendEvent(eventRepository, reg, AgentEventType.PRINCIPAL_REVOKED,
                actor, null, payload, null, idempotencyKey, now);
        registrationRepository.save(reg);   // managed instance — same object, kept effectively final for the fence lambdas
        registryLookup.invalidate(reg.getId());           // resolution key changed (M18)
        registryLookup.invalidatePrincipal(revoked);

        log.info("Principal revoked [key={}]", reg.getAgentKey());
        return reg;
    }

    // ------------------------------------------------------------------ deploy gate (§5.4)

    private void validateDeployGate(DeployCommand cmd) {
        if (isBlank(cmd.deployedByLabel())) {
            throw new AgentDeployValidationException(Code.AGENT_PROVENANCE_MISSING_DEPLOYED_BY,
                    "deployedByLabel",
                    "provenance is REQUIRED at deploy ([AGENT-PROVENANCE]): deployedByLabel must be non-blank");
        }
        if (isBlank(cmd.deploymentCriteria())) {
            throw new AgentDeployValidationException(Code.AGENT_PROVENANCE_MISSING_CRITERIA,
                    "deploymentCriteria",
                    "provenance is REQUIRED at deploy ([AGENT-PROVENANCE]): deploymentCriteria must be non-blank");
        }
        if (cmd.oversightChain() == null || cmd.oversightChain().isEmpty()
                || cmd.oversightChain().stream().anyMatch(e -> e == null || isBlank(e.label()))) {
            throw new AgentDeployValidationException(Code.AGENT_PROVENANCE_MISSING_OVERSIGHT,
                    "oversightChain",
                    "provenance is REQUIRED at deploy ([AGENT-PROVENANCE]): oversightChain needs >=1 ordered"
                            + " entry with a non-blank label");
        }
        if (cmd.supervisoryModel() == null) {
            throw new AgentDeployValidationException(Code.AGENT_SPEC_MISSING_SUPERVISORY_MODEL,
                    "supervisoryModel",
                    "supervisoryModel is API-required ([AGENT-SPEC-UNIFICATION] posture, ADR-003 D3)");
        }
        if (cmd.executionLocus() == null) {
            throw new AgentDeployValidationException(Code.AGENT_SPEC_MISSING_EXECUTION_LOCUS,
                    "executionLocus",
                    "executionLocus is required — it drives the honest control-effect derivation (§5.3)");
        }
        if (cmd.agentReadinessScore() != null && cmd.readinessSource() == null) {
            throw new AgentDeployValidationException(Code.AGENT_SPEC_MISSING_READINESS_SOURCE,
                    "readinessSource",
                    "readinessSource is required when agentReadinessScore is present — a caller-asserted"
                            + " score is never laundered as a lens computation (P7)");
        }
        if (cmd.agentReadinessScore() == null && cmd.readinessSource() != null) {
            throw new AgentDeployValidationException(Code.AGENT_SPEC_READINESS_SOURCE_WITHOUT_SCORE,
                    "readinessSource",
                    "readinessSource without agentReadinessScore — provenance for a score that does not exist");
        }
        List<CriteriaGateCommand> gates = cmd.criteriaGates() == null ? List.of() : cmd.criteriaGates();
        for (int i = 0; i < gates.size(); i++) {
            CriteriaGateCommand g = gates.get(i);
            boolean hasPredicate = g.predicate() != null && !g.predicate().isNull();
            if (g.ruleType() == AgentGateRuleType.STRUCTURED && !hasPredicate) {
                throw new AgentDeployValidationException(Code.AGENT_SPEC_GATE_PREDICATE_MISMATCH,
                        "criteriaGates[" + i + "].predicate",
                        "STRUCTURED gates carry a TinyDsl predicate; un-compilable prose ships UNSTRUCTURED"
                                + " with predicate null (§9.1 — flagged, not faked)");
            }
            if (g.ruleType() == AgentGateRuleType.UNSTRUCTURED && hasPredicate) {
                throw new AgentDeployValidationException(Code.AGENT_SPEC_GATE_PREDICATE_MISMATCH,
                        "criteriaGates[" + i + "].predicate",
                        "UNSTRUCTURED gates have predicate NULL (§9.1: predicate NULL iff UNSTRUCTURED)");
            }
            String cadence = normalizeCadence(g.cadence());
            if (cadence != null && !GATE_CADENCES.contains(cadence)) {
                throw new IllegalArgumentException("criteriaGates[" + i + "].cadence must be one of "
                        + GATE_CADENCES + " (got '" + g.cadence() + "')");
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** D6: a RECORDED_INTENT suspension opens PENDING tracking; sighting absence flips nothing. */
    private static void openControlTracking(AgentRegistration reg, String verb, Instant now) {
        reg.setControlPendingVerb(verb);
        reg.setControlRequestedAt(now);
        reg.setControlAcknowledgedAt(null);
        reg.setControlConfirmedAt(null);
        reg.setControlConfirmedByLabel(null);
    }

    /** M7: a withdrawn suspension clears its tracking — nothing is pending anymore. */
    private static void clearControlTracking(AgentRegistration reg) {
        reg.setControlPendingVerb(null);
        reg.setControlRequestedAt(null);
        reg.setControlAcknowledgedAt(null);
        reg.setControlConfirmedAt(null);
        reg.setControlConfirmedByLabel(null);
    }

    /** §13.4: CONTROL_REQUESTED (recorded-intent verbs) → chain position 1, same tx. */
    private void notifyControlRequested(AgentRegistration reg, AgentStatusEvent event,
                                        String verb, String reason) {
        List<AgentOversightLink> chain =
                oversightLinkRepository.findByAgentRegistrationIdOrderByPositionAsc(reg.getId());
        if (chain.isEmpty()) {
            return;
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("agentKey", reg.getAgentKey());
        payload.put("verb", verb);
        payload.put("reason", reason);
        notificationService.fanOut(reg.getDomainId(), reg.getId(), event.getId(),
                AgentNotificationType.CONTROL_REQUESTED, "warn",
                List.of(chain.get(0)), payload, null);
    }

    private static String normalizeBudgetKind(String budgetKind) {
        if (budgetKind == null || budgetKind.isBlank()) {
            throw new IllegalArgumentException("budgetKind is required (M8; allowlist "
                    + BUDGET_KINDS + ")");
        }
        String kind = budgetKind.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (!BUDGET_KINDS.contains(kind)) {
            throw new IllegalArgumentException("budgetKind must be one of " + BUDGET_KINDS
                    + " (got '" + budgetKind + "') — validated string, not an enum (spec §3)");
        }
        return kind;
    }

    /**
     * DEPLOYED payload: the full frozen spec snapshot (spec §4 payload table)
     * — the rebuilder asserts immutable fields against THIS, never rewrites
     * (invariant I2).
     */
    private JsonNode frozenSpecSnapshot(DeployCommand cmd, String criteriaDigest, boolean live) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        snapshot.put("agentKey", cmd.agentKey());
        snapshot.put("name", cmd.name());
        snapshot.put("roleDescription", cmd.roleDescription());
        snapshot.put("executionLocus", cmd.executionLocus().wireValue());
        snapshot.put("capabilityId", cmd.capabilityId() == null ? null : cmd.capabilityId().toString());
        snapshot.put("deployedByLabel", cmd.deployedByLabel());
        snapshot.put("deployedByMemberId",
                cmd.deployedByMemberId() == null ? null : cmd.deployedByMemberId().toString());
        snapshot.put("deploymentCriteria", cmd.deploymentCriteria());
        snapshot.put("criteriaDigest", criteriaDigest);
        ArrayNode gateArray = snapshot.putArray("criteriaGates");
        for (CriteriaGateCommand g : cmd.criteriaGates()) {
            ObjectNode gate = gateArray.addObject();
            gate.put("gateText", g.gateText());
            gate.put("ruleType", g.ruleType().wireValue());
            gate.set("predicate", g.predicate());
            gate.put("cadence", normalizeCadence(g.cadence()));
        }
        ArrayNode chain = snapshot.putArray("oversightChain");
        for (OversightEntry entry : cmd.oversightChain()) {
            ObjectNode link = chain.addObject();
            link.put("label", entry.label());
            link.put("memberId", entry.memberId() == null ? null : entry.memberId().toString());
        }
        snapshot.put("supervisoryModel", cmd.supervisoryModel().wireValue());
        snapshot.put("targetProcessId",
                cmd.targetProcessId() == null ? null : cmd.targetProcessId().toString());
        snapshot.set("targetStepIds", cmd.targetStepIds());
        if (cmd.agentReadinessScore() == null) {
            snapshot.putNull("agentReadinessScore");
        } else {
            snapshot.put("agentReadinessScore", cmd.agentReadinessScore());
        }
        snapshot.put("readinessSource",
                cmd.readinessSource() == null ? null : cmd.readinessSource().wireValue());
        snapshot.set("requiredSystemAccess", cmd.requiredSystemAccess());
        snapshot.set("requiredDataAccess", cmd.requiredDataAccess());
        snapshot.put("initiativeId", cmd.initiativeId() == null ? null : cmd.initiativeId().toString());
        snapshot.put("notes", cmd.notes());
        snapshot.put("readOnly", cmd.readOnly() == null || cmd.readOnly());
        snapshot.set("scheduleWindow", cmd.scheduleWindow());
        if (cmd.samplingPct() == null) {
            snapshot.putNull("samplingPct");
        } else {
            snapshot.put("samplingPct", cmd.samplingPct());
        }
        snapshot.put("mcpPrincipalId", cmd.mcpPrincipalId());
        snapshot.put("operationalMode", live ? "live" : "shadow");
        snapshot.put("liveJustification", live ? cmd.liveJustification() : null);
        snapshot.put("predecessorRegistrationId",
                cmd.predecessorRegistrationId() == null ? null : cmd.predecessorRegistrationId().toString());
        return snapshot;
    }

    private static String normalizeCadence(String cadence) {
        return cadence == null || cadence.isBlank() ? null : cadence.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
