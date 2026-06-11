package com.meridian.substrate.engine.agent;

import com.meridian.substrate.common.domain.AgentCriteriaGate;
import com.meridian.substrate.common.domain.AgentIntervention;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.AgentSighting;
import com.meridian.substrate.common.domain.AgentStatusEvent;
import com.meridian.substrate.common.domain.enums.AgentControlConfirmationState;
import com.meridian.substrate.common.domain.enums.AgentGateRuleType;
import com.meridian.substrate.common.domain.enums.AgentGateStanding;
import com.meridian.substrate.common.domain.enums.AgentInterventionStatus;
import com.meridian.substrate.common.domain.enums.AgentObservability;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.domain.enums.AgentReviewKind;
import com.meridian.substrate.common.domain.enums.AgentStaleness;
import com.meridian.substrate.common.domain.enums.AgentWireStatus;
import com.meridian.substrate.common.repo.AgentComplianceReviewRepository;
import com.meridian.substrate.common.repo.AgentCriteriaGateRepository;
import com.meridian.substrate.common.repo.AgentInterventionRepository;
import com.meridian.substrate.common.repo.AgentMetricSampleRepository;
import com.meridian.substrate.common.repo.AgentOversightLinkRepository;
import com.meridian.substrate.common.repo.AgentRateCounterRepository;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.common.repo.AgentSightingRepository;
import com.meridian.substrate.common.repo.AgentStatusEventRepository;
import com.meridian.substrate.common.repo.AgentThrottleBudgetRepository;
import com.meridian.substrate.engine.agent.exception.AgentNotFoundException;
import com.meridian.substrate.engine.agent.read.AgentAttentionItem;
import com.meridian.substrate.engine.agent.read.AgentDetail;
import com.meridian.substrate.engine.agent.read.AgentBudgetView;
import com.meridian.substrate.engine.agent.read.AgentEventView;
import com.meridian.substrate.engine.agent.read.AgentFeedItem;
import com.meridian.substrate.engine.agent.read.AgentFleetRow;
import com.meridian.substrate.engine.agent.read.AgentInterventionView;
import com.meridian.substrate.engine.agent.read.AgentLivenessView;
import com.meridian.substrate.engine.agent.read.AgentMetricSampleView;
import com.meridian.substrate.engine.agent.read.AgentPromotionView;
import com.meridian.substrate.engine.agent.read.AgentReviewView;
import com.meridian.substrate.engine.agent.read.AgentSightingView;
import com.meridian.substrate.engine.agent.read.AgentSignals;
import com.meridian.substrate.engine.contract.gateway.AgentRegistryGate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Read model for the agent registry (engine-spec agents.md §7.1/§7.3/§13;
 * ADR-003 D13-shape: the read model ships FIRST). This service never writes;
 * every projection served here is rebuildable from the append-only
 * {@code agent_status_events} log + sightings + samples
 * ({@link AgentProjectionRebuilder} is the mechanism).
 *
 * <p>Binding render rules carried by the DTO split: {@link AgentFleetRow}
 * has NO {@code deploymentCriteria} (click-through only — [AGENT-PROVENANCE]);
 * {@code retired} rows are EXCLUDED from {@link #fleet} by default (the
 * swapped OperationsTab pill map crashes on unknown statuses — ADR-003 D4);
 * {@code recentActivity} derives latest-sighting-detail → latest-lifecycle-
 * event → "No activity observed" (never blank, never invented);
 * {@code adherence} is the latest {@code adherence} metric sample or null.
 * The 6b detail blocks (controlEffects / enforcement / liveness / budgets /
 * interventions / promotion / recentEvents) are assembled HERE — one mapping
 * path for deploy responses, 409 envelopes and reads.
 *
 * <p>Volumes are human-scale governance records — per-agent lookups stay
 * simple over batched joins by design (cascade read-model precedent).
 */
@Service
public class AgentQueryService {

    /** "Deployed by Curasync RCM team, Jun 11" — §7.3 lifecycle-event rendering. */
    private static final DateTimeFormatter ACTIVITY_DATE =
            DateTimeFormatter.ofPattern("MMM d", Locale.US).withZone(ZoneOffset.UTC);

    private final AgentRegistrationRepository registrationRepository;
    private final AgentOversightLinkRepository oversightLinkRepository;
    private final AgentCriteriaGateRepository criteriaGateRepository;
    private final AgentStatusEventRepository eventRepository;
    private final AgentSightingRepository sightingRepository;
    private final AgentMetricSampleRepository metricSampleRepository;
    private final AgentInterventionRepository interventionRepository;
    private final AgentThrottleBudgetRepository throttleBudgetRepository;
    private final AgentComplianceReviewRepository reviewRepository;
    private final AgentLivenessService livenessService;
    private final AgentRateCounterRepository rateCounterRepository;
    private final AgentRegistryGate registryGate;
    private final Clock clock;

    public AgentQueryService(AgentRegistrationRepository registrationRepository,
                             AgentOversightLinkRepository oversightLinkRepository,
                             AgentCriteriaGateRepository criteriaGateRepository,
                             AgentStatusEventRepository eventRepository,
                             AgentSightingRepository sightingRepository,
                             AgentMetricSampleRepository metricSampleRepository,
                             AgentInterventionRepository interventionRepository,
                             AgentThrottleBudgetRepository throttleBudgetRepository,
                             AgentComplianceReviewRepository reviewRepository,
                             AgentLivenessService livenessService,
                             AgentRateCounterRepository rateCounterRepository,
                             AgentRegistryGate registryGate,
                             Clock clock) {
        this.registrationRepository = registrationRepository;
        this.oversightLinkRepository = oversightLinkRepository;
        this.criteriaGateRepository = criteriaGateRepository;
        this.eventRepository = eventRepository;
        this.sightingRepository = sightingRepository;
        this.metricSampleRepository = metricSampleRepository;
        this.interventionRepository = interventionRepository;
        this.throttleBudgetRepository = throttleBudgetRepository;
        this.reviewRepository = reviewRepository;
        this.livenessService = livenessService;
        this.rateCounterRepository = rateCounterRepository;
        this.registryGate = registryGate;
        this.clock = clock;
    }

    // ------------------------------------------------------------ §7.1 fleet

    /**
     * {@code GET /agents?domainId&status?&includeRetired?=false}. RETIRED
     * rows are excluded unless {@code includeRetired=true} OR the caller
     * explicitly filters {@code status=retired} (the explicit ask IS the
     * opt-in — a silent empty list would hide the terminal fleet). Rows come
     * back in registration-creation order (= fixture declaration order for
     * the DemoPack seed).
     */
    @Transactional(readOnly = true)
    public List<AgentFleetRow> fleet(UUID domainId, AgentWireStatus status, boolean includeRetired) {
        boolean withRetired = includeRetired || status == AgentWireStatus.RETIRED;
        List<AgentRegistration> regs = withRetired
                ? registrationRepository.findByDomainId(domainId)
                : registrationRepository.findByDomainIdAndOperationalModeNot(
                        domainId, AgentOperationalMode.RETIRED);
        return regs.stream()
                .sorted(Comparator.comparing(AgentRegistration::getCreatedAt,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AgentRegistration::getAgentKey))
                .filter(reg -> status == null || AgentWireStatus.project(
                        reg.getOperationalMode(), reg.getOpenInterventionCount()) == status)
                .map(reg -> AgentFleetRow.from(reg,
                        oversightLinkRepository.findByAgentRegistrationIdOrderByPositionAsc(reg.getId()),
                        recentActivityFor(reg.getId()),
                        adherenceFor(reg.getId())))
                .toList();
    }

    // ----------------------------------------------------------- §7.1 detail

    /** {@code GET /agents/{idOrKey}} — full {@link AgentDetail} (criteria + ordered chain). */
    @Transactional(readOnly = true)
    public AgentDetail detailByIdOrKey(String idOrKey, UUID domainId) {
        return detail(resolve(idOrKey, domainId));
    }

    /**
     * Single mapping path for the detail shape — deploy responses, verb
     * responses, 409 envelopes and the read endpoint all produce identical
     * projections, including the 6b honest-control blocks (§6.1
     * controlEffects, §7.3 enforcement/liveness/budgets/interventions/
     * promotion/recentEvents).
     */
    @Transactional(readOnly = true)
    public AgentDetail detail(AgentRegistration reg) {
        List<AgentInterventionView> openInterventions = interventionRepository
                .findByAgentRegistrationIdAndStatus(reg.getId(), AgentInterventionStatus.OPEN)
                .stream()
                .sorted(Comparator.comparing(AgentIntervention::getOpenedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(AgentInterventionView::from)
                .toList();
        AgentReviewView latestReview = reviewRepository
                .findByAgentRegistrationIdOrderByRequestedAtDesc(reg.getId()).stream()
                .filter(r -> r.getReviewKind() == AgentReviewKind.PROMOTION)
                .findFirst()
                .map(AgentReviewView::from)
                .orElse(null);
        return AgentDetail.from(reg,
                oversightLinkRepository.findByAgentRegistrationIdOrderByPositionAsc(reg.getId()),
                criteriaGateRepository.findByAgentRegistrationIdOrderBySequenceOrderAsc(reg.getId()),
                recentActivityFor(reg.getId()),
                adherenceFor(reg.getId()),
                AgentControlEffects.controlEffectsOf(reg, registryGate.posture()),
                AgentControlEffects.controlTrackingOf(reg),
                livenessOf(reg),
                throttleBudgetRepository.findByAgentRegistrationIdAndActiveTrue(reg.getId())
                        .stream().map(AgentBudgetView::from).toList(),
                openInterventions,
                new AgentPromotionView(reg.getPromotionRequestedAt(), latestReview),
                eventRepository.findTop5ByAgentRegistrationIdOrderByEventSeqDesc(reg.getId())
                        .stream().map(AgentEventView::from).toList());
    }

    /** §10 derived liveness block — evidence only, never row recency (I7). */
    @Transactional(readOnly = true)
    public AgentLivenessView livenessOf(AgentRegistration reg) {
        return new AgentLivenessView(
                reg.getLastSeenAt(),
                reg.getLastSeenVia(),
                reg.getLastAttestedAt(),
                reg.getLastAttestedByLabel(),
                livenessService.observabilityOf(reg),
                livenessService.stalenessOf(reg),
                reg.getHeartbeatExpectedMinutes());
    }

    /**
     * §7 {@code {idOrKey}} resolution: UUID first, then {@code agent_key} —
     * scoped to {@code domainId} when supplied; an unscoped key matching
     * registrations in several domains is a 400 asking for the scope (keys
     * are unique per domain, not globally — uq_agent_domain_key).
     */
    @Transactional(readOnly = true)
    public AgentRegistration resolve(String idOrKey, UUID domainId) {
        Optional<AgentRegistration> byId = parseUuid(idOrKey)
                .flatMap(registrationRepository::findById);
        if (byId.isPresent()) {
            return byId.get();
        }
        if (domainId != null) {
            return registrationRepository.findByDomainIdAndAgentKey(domainId, idOrKey)
                    .orElseThrow(() -> notFound(idOrKey, domainId));
        }
        List<AgentRegistration> byKey = registrationRepository.findByAgentKey(idOrKey);
        if (byKey.size() > 1) {
            throw new IllegalArgumentException("agent_key '" + idOrKey + "' exists in "
                    + byKey.size() + " domains — pass ?domainId to disambiguate"
                    + " (keys are unique per domain: uq_agent_domain_key)");
        }
        return byKey.stream().findFirst().orElseThrow(() -> notFound(idOrKey, null));
    }

    private static AgentNotFoundException notFound(String idOrKey, UUID domainId) {
        return new AgentNotFoundException("no agent registration matches '" + idOrKey + "'"
                + (domainId != null ? " in domain " + domainId : "")
                + " (resolution: UUID first, then agent_key — spec §7)");
    }

    // ----------------------------------------------- §7.1 remaining read lanes

    /** {@code GET /agents/{idOrKey}/events} — the audit timeline, event_seq asc, paged. */
    @Transactional(readOnly = true)
    public List<AgentEventView> events(AgentRegistration reg, int page, int size) {
        if (page < 0 || size <= 0) {
            throw new IllegalArgumentException("page must be >= 0 and size > 0 (spec §7.1 /events)");
        }
        return eventRepository.findByAgentRegistrationIdOrderByEventSeqAsc(reg.getId()).stream()
                .skip((long) page * size)
                .limit(size)
                .map(AgentEventView::from)
                .toList();
    }

    /** {@code GET /agents/{idOrKey}/sightings?since} — telemetry, newest first. */
    @Transactional(readOnly = true)
    public List<AgentSightingView> sightings(AgentRegistration reg, Instant since) {
        List<AgentSighting> rows = since != null
                ? sightingRepository.findByAgentRegistrationIdAndSeenAtAfterOrderBySeenAtDesc(
                        reg.getId(), since)
                : sightingRepository.findByAgentRegistrationIdOrderBySeenAtDesc(reg.getId());
        return rows.stream().map(AgentSightingView::from).toList();
    }

    /** {@code GET /agents/{idOrKey}/gates} — gate rows + denormalized standing. */
    @Transactional(readOnly = true)
    public List<AgentDetail.CriteriaGateView> gates(AgentRegistration reg) {
        return criteriaGateRepository.findByAgentRegistrationIdOrderBySequenceOrderAsc(reg.getId())
                .stream()
                .map(g -> new AgentDetail.CriteriaGateView(g.getGateText(), g.getRuleType(),
                        g.getPredicate(), g.getCadence(), g.getStanding(), g.getLastEvaluatedAt()))
                .toList();
    }

    /** {@code GET /agents/{idOrKey}/metrics?metricKey&since} — sample rows, newest first. */
    @Transactional(readOnly = true)
    public List<AgentMetricSampleView> metrics(AgentRegistration reg, String metricKey, Instant since) {
        List<com.meridian.substrate.common.domain.AgentMetricSample> rows = metricKey != null
                ? metricSampleRepository.findByAgentRegistrationIdAndMetricKeyOrderByReportedAtDesc(
                        reg.getId(), metricKey)
                : metricSampleRepository.findByAgentRegistrationIdAndReportedAtAfterOrderByReportedAtDesc(
                        reg.getId(), since != null ? since : Instant.EPOCH);
        return rows.stream()
                .filter(s -> since == null || s.getReportedAt().isAfter(since))
                .map(AgentMetricSampleView::from)
                .toList();
    }

    /**
     * {@code GET /agents/feed?domainId&limit} — cross-fleet recent events +
     * sightings merged at read (Ops Center live panel; spec §7.1). Read-side
     * composition only; no feed table exists.
     */
    @Transactional(readOnly = true)
    public List<AgentFeedItem> feed(UUID domainId, int limit) {
        List<AgentRegistration> fleet = registrationRepository.findByDomainId(domainId);
        List<AgentFeedItem> items = new ArrayList<>();
        for (AgentStatusEvent event : eventRepository
                .findTop50ByDomainIdOrderByOccurredAtDescEventSeqDesc(domainId)) {
            String agentKey = agentKeyOf(fleet, event.getAgentRegistrationId());
            items.add(AgentFeedItem.ofEvent(agentKey, event.getAgentRegistrationId(),
                    renderActivity(event), AgentEventView.from(event)));
        }
        for (AgentRegistration reg : fleet) {
            for (AgentSighting sighting : sightingRepository
                    .findByAgentRegistrationIdOrderBySeenAtDesc(reg.getId()).stream()
                    .limit(Math.max(1, limit)).toList()) {
                items.add(AgentFeedItem.ofSighting(reg.getAgentKey(), reg.getId(),
                        sighting.getDetail() != null
                                ? sighting.getDetail()
                                : "Seen via " + sighting.getChannel().wireValue(),
                        AgentSightingView.from(sighting)));
            }
        }
        return items.stream()
                .sorted(Comparator.comparing(AgentFeedItem::at,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(1, limit))
                .toList();
    }

    // ---------------------------------------------------------- §13.1 signals

    /**
     * {@code GET /agents/signals?domainId} — computed at read from existing
     * tables; EXPLICIT ZEROS always present (no-silent-zero-yield): an empty
     * domain answers all-zero counts and empty arrays, never absent fields.
     */
    @Transactional(readOnly = true)
    public AgentSignals signals(UUID domainId) {
        List<AgentRegistration> fleet = registrationRepository
                .findByDomainIdAndOperationalModeNot(domainId, AgentOperationalMode.RETIRED);

        int live = 0;
        int shadow = 0;
        int paused = 0;
        int interventionRequired = 0;
        int stale = 0;
        int neverSeen = 0;
        int attestOnly = 0;
        int structured = 0;
        int unstructured = 0;
        int unevaluated = 0;
        List<AgentSignals.UnconfirmedControl> unconfirmed = new ArrayList<>();
        List<AgentSignals.PromotionPending> promotionsPending = new ArrayList<>();

        for (AgentRegistration reg : fleet) {
            switch (AgentWireStatus.project(reg.getOperationalMode(), reg.getOpenInterventionCount())) {
                case LIVE -> live++;
                case SHADOW -> shadow++;
                case PAUSED -> paused++;
                case INTERVENTION_REQUIRED -> interventionRequired++;
                case RETIRED -> { /* excluded above */ }
            }
            if (livenessService.observabilityOf(reg) == AgentObservability.ATTEST_ONLY) {
                attestOnly++;
            }
            AgentStaleness staleness = livenessService.stalenessOf(reg);
            if (staleness == AgentStaleness.STALE) {
                stale++;
            } else if (staleness == AgentStaleness.NEVER_SEEN) {
                neverSeen++;
            }
            if (reg.getControlPendingVerb() != null && reg.getControlConfirmedAt() == null) {
                unconfirmed.add(new AgentSignals.UnconfirmedControl(
                        reg.getAgentKey(), reg.getControlPendingVerb(), reg.getControlRequestedAt(),
                        reg.getControlAcknowledgedAt() != null
                                ? AgentControlConfirmationState.ACKNOWLEDGED
                                : AgentControlConfirmationState.PENDING));
            }
            if (reg.getPromotionRequestedAt() != null
                    && reg.getOperationalMode() == AgentOperationalMode.SHADOW) {
                promotionsPending.add(new AgentSignals.PromotionPending(
                        reg.getAgentKey(), reg.getPromotionRequestedAt()));
            }
            for (AgentCriteriaGate gate : criteriaGateRepository
                    .findByAgentRegistrationIdOrderBySequenceOrderAsc(reg.getId())) {
                if (gate.getRuleType() == AgentGateRuleType.STRUCTURED) {
                    structured++;
                } else {
                    unstructured++;
                }
                if (gate.getStanding() == AgentGateStanding.UNEVALUATED) {
                    unevaluated++;
                }
            }
        }

        List<AgentIntervention> openInterventions =
                interventionRepository.findByDomainIdAndStatus(domainId, AgentInterventionStatus.OPEN);
        Instant oldestOpenedAt = openInterventions.stream()
                .map(AgentIntervention::getOpenedAt)
                .min(Comparator.naturalOrder())
                .orElse(null);

        // Zombie classes (§6.4), raised by the 6c gateway flows. A
        // SEEN_AFTER_KILL row belongs to a RETIRED registration — outside
        // the default fleet — so violations resolve their agent by id, not
        // through the non-retired list (the kill-then-heartbeat acceptance
        // beat must SURFACE, never fall back to a UUID string).
        List<AgentSignals.ControlViolation> violations = openInterventions.stream()
                .filter(i -> AgentGatewaySignalService.SOURCE_ACTIVE_WHILE_PAUSED
                        .equals(i.getSourceKey())
                        || AgentGatewaySignalService.SOURCE_SEEN_AFTER_KILL
                        .equals(i.getSourceKey()))
                .map(i -> {
                    AgentRegistration reg = registrationOf(fleet, i.getAgentRegistrationId());
                    return new AgentSignals.ControlViolation(
                            reg != null ? reg.getAgentKey()
                                    : i.getAgentRegistrationId().toString(),
                            AgentGatewaySignalService.SOURCE_ACTIVE_WHILE_PAUSED
                                    .equals(i.getSourceKey())
                                    ? "ACTIVE_WHILE_PAUSED" : "SEEN_AFTER_KILL",
                            reg != null ? reg.getLastSeenAt() : null);
                })
                .toList();

        // §8.3 #4 / §13.1 budgetsExhausted: agents whose active
        // ACTIONS_PER_WINDOW budget is at/over its limit in the CURRENT
        // window — computed at read from the DB-backed counters (D12).
        List<AgentSignals.BudgetExhausted> budgetsExhausted = new ArrayList<>();
        for (AgentRegistration reg : fleet) {
            throttleBudgetRepository.findByAgentRegistrationIdAndBudgetKindAndActiveTrue(
                            reg.getId(), AgentRegistryLookup.BUDGET_ACTIONS_PER_WINDOW)
                    .filter(b -> b.getLimitValue() != null)
                    .ifPresent(b -> {
                        int windowMinutes = b.getWindowMinutes() != null && b.getWindowMinutes() > 0
                                ? b.getWindowMinutes()
                                : AgentRegistryGateImpl.DEFAULT_WINDOW_MINUTES;
                        long windowSeconds = windowMinutes * 60L;
                        long epoch = clock.instant().getEpochSecond();
                        Instant windowStart =
                                Instant.ofEpochSecond((epoch / windowSeconds) * windowSeconds);
                        rateCounterRepository
                                .findByAgentRegistrationIdAndWindowStart(reg.getId(), windowStart)
                                .filter(c -> c.getActionCount() >= b.getLimitValue())
                                .ifPresent(c -> budgetsExhausted.add(
                                        new AgentSignals.BudgetExhausted(reg.getAgentKey(),
                                                b.getBudgetKind())));
                    });
        }

        return new AgentSignals(
                fleet.size(), live, shadow, paused, interventionRequired,
                new AgentSignals.Interventions(openInterventions.size(), oldestOpenedAt),
                List.copyOf(unconfirmed),
                violations,
                new AgentSignals.Liveness(stale, neverSeen, attestOnly),
                List.copyOf(promotionsPending),
                List.copyOf(budgetsExhausted),
                new AgentSignals.GateCoverage(structured, unstructured, unevaluated));
    }

    // -------------------------------------------------------- §13.2 attention

    /**
     * {@code GET /agents/attention?domainId} — AttentionItem-shaped rows
     * (§13.2 tone mapping is binding). Order: destructive, warn, info; within
     * tone by {@code since} desc — exactly the cascade attention order so the
     * §13.3 composed feed merges without re-ranking surprises.
     */
    @Transactional(readOnly = true)
    public List<AgentAttentionItem> attention(UUID domainId) {
        List<AgentRegistration> fleet = registrationRepository
                .findByDomainIdAndOperationalModeNot(domainId, AgentOperationalMode.RETIRED);
        List<AgentAttentionItem> items = new ArrayList<>();

        for (AgentIntervention i : interventionRepository
                .findByDomainIdAndStatus(domainId, AgentInterventionStatus.OPEN)) {
            String agentKey = agentKeyOf(fleet, i.getAgentRegistrationId());
            boolean violation = "active-while-paused".equals(i.getSourceKey())
                    || "seen-after-kill".equals(i.getSourceKey());
            items.add(new AgentAttentionItem(agentKey, i.getAgentRegistrationId(),
                    violation ? AgentAttentionItem.Kind.CONTROL_VIOLATION
                            : AgentAttentionItem.Kind.AGENT_IN_INTERVENTION,
                    AgentAttentionItem.Tone.DESTRUCTIVE,
                    (violation ? "Control violation — " : "Intervention required — ")
                            + nameOf(fleet, i.getAgentRegistrationId(), agentKey)
                            + ": " + i.getReason(),
                    i.getOpenedAt()));
        }

        for (AgentRegistration reg : fleet) {
            if (reg.getOperationalMode() == AgentOperationalMode.PAUSED) {
                items.add(new AgentAttentionItem(reg.getAgentKey(), reg.getId(),
                        AgentAttentionItem.Kind.AGENT_PAUSED, AgentAttentionItem.Tone.WARN,
                        "Paused — " + reg.getName(),
                        reg.getControlRequestedAt() != null
                                ? reg.getControlRequestedAt() : reg.getUpdatedAt()));
            }
            if (reg.getControlPendingVerb() != null && reg.getControlConfirmedAt() == null) {
                items.add(new AgentAttentionItem(reg.getAgentKey(), reg.getId(),
                        AgentAttentionItem.Kind.CONTROL_UNCONFIRMED, AgentAttentionItem.Tone.WARN,
                        capitalize(reg.getControlPendingVerb()) + " unconfirmed at runtime — "
                                + reg.getName(),
                        reg.getControlRequestedAt()));
            }
            for (AgentCriteriaGate gate : criteriaGateRepository
                    .findByAgentRegistrationIdOrderBySequenceOrderAsc(reg.getId())) {
                if (gate.getStanding() == AgentGateStanding.AT_RISK) {
                    items.add(new AgentAttentionItem(reg.getAgentKey(), reg.getId(),
                            AgentAttentionItem.Kind.CRITERIA_AT_RISK, AgentAttentionItem.Tone.WARN,
                            "Criteria at risk — " + reg.getName() + ": " + gate.getGateText(),
                            gate.getLastEvaluatedAt()));
                } else if (gate.getStanding() == AgentGateStanding.HUMAN_VERIFY_DUE) {
                    items.add(new AgentAttentionItem(reg.getAgentKey(), reg.getId(),
                            AgentAttentionItem.Kind.HUMAN_VERIFY_DUE, AgentAttentionItem.Tone.INFO,
                            "Human verification due — " + reg.getName() + ": " + gate.getGateText(),
                            gate.getLastEvaluatedAt()));
                }
            }
        }

        return items.stream()
                .sorted(Comparator
                        .comparingInt(AgentQueryService::toneRank)
                        .thenComparing(AgentAttentionItem::since,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private static int toneRank(AgentAttentionItem item) {
        return switch (item.tone()) {
            case DESTRUCTIVE -> 0;
            case WARN -> 1;
            case INFO -> 2;
        };
    }

    // ------------------------------------------------------- §7.3 derivations

    /**
     * §7.3 {@code recentActivity} derivation: latest sighting WITH a detail,
     * else latest lifecycle event ("Paused by Compliance Officer, May 30"),
     * else "No activity observed" — never blank, never invented.
     */
    @Transactional(readOnly = true)
    public String recentActivityFor(UUID agentRegistrationId) {
        Optional<String> sighting = sightingRepository
                .findFirstByAgentRegistrationIdAndDetailIsNotNullOrderBySeenAtDesc(agentRegistrationId)
                .map(AgentSighting::getDetail);
        if (sighting.isPresent()) {
            return sighting.get();
        }
        return eventRepository.findFirstByAgentRegistrationIdOrderByEventSeqDesc(agentRegistrationId)
                .map(AgentQueryService::renderActivity)
                .orElse("No activity observed");
    }

    /** §7.3 nullable {@code adherence}: latest 'adherence' sample, else null — never fabricated. */
    @Transactional(readOnly = true)
    public Double adherenceFor(UUID agentRegistrationId) {
        return metricSampleRepository
                .findFirstByAgentRegistrationIdAndMetricKeyOrderByReportedAtDesc(
                        agentRegistrationId, AgentMetricService.METRIC_ADHERENCE)
                .map(s -> (Double) s.getMetricValue())
                .orElse(null);
    }

    /** §7.3 lifecycle-event rendering for the {@code recentActivity} fallback chain. */
    public static String renderActivity(AgentStatusEvent event) {
        String verb = switch (event.getEventType()) {
            case DEPLOYED -> "Deployed";
            case PROMOTION_REQUESTED -> "Promotion requested";
            case PROMOTED -> "Promoted";
            case PROMOTION_REJECTED -> "Promotion review rejected";
            case PAUSED -> "Paused";
            case RESUMED -> "Resumed";
            case THROTTLE_SET -> "Throttled";
            case THROTTLE_CLEARED -> "Throttle cleared";
            case KILLED -> "Killed";
            case RETIRED -> "Retired";
            case INTERVENTION_OPENED -> "Intervention opened";
            case INTERVENTION_ACKNOWLEDGED -> "Intervention acknowledged";
            case INTERVENTION_RESOLVED -> "Intervention resolved";
            case OVERSIGHT_CHANGED -> "Oversight chain changed";
            case CONTROL_ACKNOWLEDGED -> "Control acknowledged";
            case CONTROL_CONFIRMED -> "Control confirmed";
            case CONTROL_VIOLATION_OBSERVED -> "Control violation observed";
            case LIVENESS_ATTESTED -> "Liveness attested";
            case PRINCIPAL_BOUND -> "Principal bound";
            case PRINCIPAL_REVOKED -> "Principal revoked";
            case CRITERIA_STANDING_CHANGED -> "Criteria standing changed";
            case DISPATCH -> "Dispatched";
        };
        return verb + " by " + event.getActorLabel() + ", " + ACTIVITY_DATE.format(event.getOccurredAt());
    }

    // ----------------------------------------------------------------- helpers

    private static Optional<UUID> parseUuid(String raw) {
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    /** Fleet-first resolution falling back to the repo (retired agents — §6.4 violations). */
    private AgentRegistration registrationOf(List<AgentRegistration> fleet, UUID registrationId) {
        return fleet.stream().filter(r -> r.getId().equals(registrationId)).findFirst()
                .orElseGet(() -> registrationRepository.findById(registrationId).orElse(null));
    }

    private static String agentKeyOf(List<AgentRegistration> fleet, UUID registrationId) {
        return fleet.stream().filter(r -> r.getId().equals(registrationId))
                .map(AgentRegistration::getAgentKey).findFirst().orElse(registrationId.toString());
    }

    private static String nameOf(List<AgentRegistration> fleet, UUID registrationId, String fallback) {
        return fleet.stream().filter(r -> r.getId().equals(registrationId))
                .map(AgentRegistration::getName).findFirst().orElse(fallback);
    }

    private static String capitalize(String s) {
        return s == null || s.isEmpty() ? s
                : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
