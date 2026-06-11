package com.meridian.substrate.engine.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentCriteriaGate;
import com.meridian.substrate.common.domain.AgentOversightLink;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.enums.AgentEventType;
import com.meridian.substrate.common.domain.enums.AgentGateRuleType;
import com.meridian.substrate.common.domain.enums.AgentGateStanding;
import com.meridian.substrate.common.domain.enums.AgentNotificationType;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.domain.enums.ContractActorType;
import com.meridian.substrate.common.repo.AgentCriteriaGateRepository;
import com.meridian.substrate.common.repo.AgentMetricSampleRepository;
import com.meridian.substrate.common.repo.AgentOversightLinkRepository;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.common.repo.AgentStatusEventRepository;
import com.meridian.substrate.engine.contract.gateway.LatestClosedMetricValue;
import com.meridian.substrate.engine.contract.gateway.TinyDslEvaluator;
import com.meridian.substrate.engine.contract.gateway.Ungoverned;
import com.meridian.substrate.engine.contract.monitor.PeriodKeys;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * deploymentCriteria as a runtime gate (engine-spec agents.md §9; ADR-003
 * D9): gate CONTENT froze at deploy (invariant I2) — this service only ever
 * moves {@code standing}/{@code last_evaluated_at} and emits signals.
 *
 * <p><b>Signal-only, NEVER auto-suspend</b> (cascade D5 principle: the system
 * never speaks for a human in an accountability chain): a failing STRUCTURED
 * predicate sets AT_RISK + a bounded CRITERIA_STANDING_CHANGED event + a
 * CRITERIA_AT_RISK outbox row to oversight position 1 (deduped per
 * gate+period) SUGGESTING intervention — never pause, never kill.
 *
 * <p><b>The DSL is REUSED, never reimplemented:</b> STRUCTURED predicates are
 * the contracts TinyDsl verbatim, evaluated by
 * {@link TinyDslEvaluator} with metric leaves resolving the latest
 * complete-period value from {@code agent_metric_samples} via
 * {@link LatestClosedMetricValue} (one leaf-resolution semantic across both
 * engines — the ArchUnit no-second-DSL rule pins that {@code engine.agent}
 * contains no evaluator of its own). Because the DSL is shared, a future
 * contract guard can cite an agent gate — EXTENSION POINT(agent-5): the
 * designed contracts↔agents composition path, recorded NOT built (contracts
 * D9 SPI revisit).
 *
 * <p><b>INDETERMINATE never marks AT_RISK</b> (contracts D10 hard invariant
 * carried over): missing samples/unresolvable metrics return the standing to
 * UNEVALUATED — a needs-input state distinct from green, visible in the
 * §13.1 {@code gateCoverage} ratio (degradation is never silent).
 *
 * <p><b>UNSTRUCTURED gates are weak but never silently inert</b>
 * (no-silent-zero-yield): each emits a recurring HUMAN_VERIFY_DUE signal on
 * its cadence via this sweeper — independent of the structured pass and of
 * {@code substrate.agents.gate-eval.enabled}.
 */
@Service
public class AgentCriteriaGateService {

    private static final Logger log = LoggerFactory.getLogger(AgentCriteriaGateService.class);

    /** Sweep actor (I3): engine-raised standing changes are MONITOR-attributed, never human-spoofed. */
    static final String SWEEP_ACTOR_LABEL = "agent-criteria-gate-sweep";

    private final AgentRegistrationRepository registrationRepository;
    private final AgentCriteriaGateRepository criteriaGateRepository;
    private final AgentStatusEventRepository eventRepository;
    private final AgentMetricSampleRepository metricSampleRepository;
    private final AgentOversightLinkRepository oversightLinkRepository;
    private final AgentNotificationService notificationService;
    private final AgentProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public AgentCriteriaGateService(AgentRegistrationRepository registrationRepository,
                                    AgentCriteriaGateRepository criteriaGateRepository,
                                    AgentStatusEventRepository eventRepository,
                                    AgentMetricSampleRepository metricSampleRepository,
                                    AgentOversightLinkRepository oversightLinkRepository,
                                    AgentNotificationService notificationService,
                                    AgentProperties properties,
                                    Clock clock,
                                    ObjectMapper objectMapper,
                                    PlatformTransactionManager transactionManager) {
        this.registrationRepository = registrationRepository;
        this.criteriaGateRepository = criteriaGateRepository;
        this.eventRepository = eventRepository;
        this.metricSampleRepository = metricSampleRepository;
        this.oversightLinkRepository = oversightLinkRepository;
        this.notificationService = notificationService;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Hourly by default ({@code substrate.agents.sweep-cron}); ShedLock-leased
     * so exactly one instance sweeps. Each agent evaluates in its own
     * transaction under the registration lock (cascade-sweeper precedent —
     * one failure cannot poison the sweep; a lost notification-dedupe race is
     * swallowed, next sweep re-evaluates).
     */
    @Scheduled(cron = "${substrate.agents.sweep-cron:0 0 * * * *}")
    @SchedulerLock(name = "agent-criteria-gate-sweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT2S")
    public void sweep() {
        List<UUID> candidates = registrationRepository
                .findByOperationalModeNot(AgentOperationalMode.RETIRED)
                .stream().map(AgentRegistration::getId).toList();
        int swept = 0;
        for (UUID registrationId : candidates) {
            try {
                transactionTemplate.executeWithoutResult(status -> evaluateAgent(registrationId));
                swept++;
            } catch (DataIntegrityViolationException dedupeRace) {
                log.debug("Gate sweep lost a dedupe race — skipping [agent={}]",
                        registrationId, dedupeRace);
            } catch (RuntimeException ex) {
                log.error("Gate sweep failed for one agent — continuing [agent={}]",
                        registrationId, ex);
            }
        }
        log.debug("Agent criteria-gate sweep complete [candidates={}, swept={}]",
                candidates.size(), swept);
    }

    /** One agent, one transaction, under the registration lock. Public for tests + operator tooling. */
    @Ungoverned(reason = "engine-raised, MONITOR-attributed standing evaluation — signal-only,"
            + " never a state suspension; not one of the three ADR D2 PEPs (engine-spec"
            + " agents.md §9.2; ADR-003 D9)")
    @Transactional
    public void evaluateAgent(UUID agentRegistrationId) {
        AgentRegistration reg = AgentVerbMechanics.lock(registrationRepository, agentRegistrationId);
        if (reg.getOperationalMode() == AgentOperationalMode.RETIRED) {
            return;                            // raced a kill/retire since the candidate list
        }
        Instant now = clock.instant();
        boolean structuredEnabled = properties.getGateEval().isEnabled();
        for (AgentCriteriaGate gate : criteriaGateRepository
                .findByAgentRegistrationIdOrderBySequenceOrderAsc(reg.getId())) {
            if (gate.getRuleType() == AgentGateRuleType.STRUCTURED) {
                if (structuredEnabled) {
                    evaluateStructured(reg, gate, now);
                }
                // disabled ⇒ standing stays as-is; coverage ratio keeps the
                // degradation visible (§9.2 — never silent).
            } else {
                sweepUnstructured(reg, gate, now);
            }
        }
    }

    // ------------------------------------------------------------- STRUCTURED

    private void evaluateStructured(AgentRegistration reg, AgentCriteriaGate gate, Instant now) {
        TinyDslEvaluator.Result result = TinyDslEvaluator.evaluate(
                gate.getPredicate(), objectMapper.createObjectNode(),
                metricKey -> LatestClosedMetricValue.resolveValues(
                        metricSampleRepository
                                .findByAgentRegistrationIdAndMetricKeyOrderByReportedAtDesc(
                                        reg.getId(), metricKey)
                                .stream()
                                .map(s -> new LatestClosedMetricValue.PeriodValue(
                                        s.getPeriodKey(), s.getMetricValue()))
                                .toList(),
                        now, ZoneOffset.UTC));
        AgentGateStanding target = switch (result) {
            case MATCH -> AgentGateStanding.MET;
            case NO_MATCH -> AgentGateStanding.AT_RISK;
            // §9.2: INDETERMINATE NEVER marks AT_RISK — needs-input, distinct from green.
            case INDETERMINATE -> AgentGateStanding.UNEVALUATED;
        };
        AgentGateStanding before = gate.getStanding();
        gate.setLastEvaluatedAt(now);
        if (before != target) {
            gate.setStanding(target);
            appendStandingChanged(reg, gate, before, target, now);
        }
        criteriaGateRepository.save(gate);
        if (target == AgentGateStanding.AT_RISK) {
            // Deduped per gate+period (§9.2) — recurring while the gate stays red,
            // once per cadence window, SUGGESTING intervention (never acting).
            notifyPositionOne(reg, AgentNotificationType.CRITERIA_AT_RISK, "warn", gate,
                    "criteria-at-risk:" + gate.getId() + ":" + windowKey(gate.getCadence(), now));
        }
    }

    // ----------------------------------------------------------- UNSTRUCTURED

    private void sweepUnstructured(AgentRegistration reg, AgentCriteriaGate gate, Instant now) {
        String window = windowKey(gate.getCadence(), now);
        AgentGateStanding before = gate.getStanding();
        gate.setLastEvaluatedAt(now);
        if (before != AgentGateStanding.HUMAN_VERIFY_DUE) {
            gate.setStanding(AgentGateStanding.HUMAN_VERIFY_DUE);
            appendStandingChanged(reg, gate, before, AgentGateStanding.HUMAN_VERIFY_DUE, now);
        }
        criteriaGateRepository.save(gate);
        // Recurring per cadence window; the outbox dedupe key is the
        // exactly-once mechanism (UNIQUE backstop — trap #4).
        notifyPositionOne(reg, AgentNotificationType.HUMAN_VERIFY_DUE, "info", gate,
                "human-verify:" + gate.getId() + ":" + window);
    }

    // ----------------------------------------------------------------- shared

    /** Bounded CRITERIA_STANDING_CHANGED event — once per standing CHANGE, MONITOR-attributed. */
    private void appendStandingChanged(AgentRegistration reg, AgentCriteriaGate gate,
                                       AgentGateStanding before, AgentGateStanding after,
                                       Instant now) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("gateId", gate.getId().toString());
        payload.put("sequenceOrder", gate.getSequenceOrder());
        payload.put("from", before.name());
        payload.put("to", after.name());
        AgentVerbMechanics.appendEvent(eventRepository, reg,
                AgentEventType.CRITERIA_STANDING_CHANGED,
                new AgentActor(null, SWEEP_ACTOR_LABEL, ContractActorType.MONITOR),
                null, payload, null,
                "gate:" + gate.getId() + ":" + after.name() + ":" + now.toEpochMilli(), now);
        registrationRepository.save(reg);
    }

    /** §13.4: CRITERIA_AT_RISK / HUMAN_VERIFY_DUE → oversight position 1, deduped. */
    private void notifyPositionOne(AgentRegistration reg, AgentNotificationType type, String tone,
                                   AgentCriteriaGate gate, String dedupeKey) {
        List<AgentOversightLink> chain =
                oversightLinkRepository.findByAgentRegistrationIdOrderByPositionAsc(reg.getId());
        if (chain.isEmpty()) {
            return;
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("agentKey", reg.getAgentKey());
        payload.put("gateText", gate.getGateText());
        payload.put("standing", gate.getStanding().name());
        notificationService.fanOut(reg.getDomainId(), reg.getId(), null, type, tone,
                List.of(chain.get(0)), payload, dedupeKey);
    }

    /**
     * Dedupe-window key per gate cadence. MONTHLY/QUARTERLY ride the §5.1
     * period keys; WEEKLY/DAILY have no §5.1 period-key semantics (PeriodKeys
     * returns null — flag-not-invent), so their NOTIFICATION windows key on
     * the ISO week/date — a dedupe horizon only, never a metric period.
     * Cadence-less gates default to a daily window.
     */
    static String windowKey(String cadence, Instant now) {
        String normalized = cadence == null ? "" : cadence.trim().toUpperCase(Locale.ROOT);
        LocalDate date = now.atZone(ZoneOffset.UTC).toLocalDate();
        return switch (normalized) {
            case "MONTHLY", "QUARTERLY" -> PeriodKeys.periodKey(normalized, now, ZoneOffset.UTC);
            case "WEEKLY" -> date.get(IsoFields.WEEK_BASED_YEAR) + "-W"
                    + String.format(Locale.ROOT, "%02d", date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            default -> date.toString();
        };
    }
}
