package com.meridian.substrate.engine.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentIntervention;
import com.meridian.substrate.common.domain.AgentOversightLink;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.AgentStatusEvent;
import com.meridian.substrate.common.domain.enums.AgentEventType;
import com.meridian.substrate.common.domain.enums.AgentInterventionStatus;
import com.meridian.substrate.common.domain.enums.AgentNotificationType;
import com.meridian.substrate.common.repo.AgentInterventionRepository;
import com.meridian.substrate.common.repo.AgentOversightLinkRepository;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.common.repo.AgentStatusEventRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Escalation sweeper (engine-spec agents.md §13.4; ADR-003 D13; cascade D5
 * idiom verbatim) — hourly {@code @Scheduled}, <b>notification-ONLY: the
 * sweeper NEVER transitions state</b> (the system never speaks for a human in
 * an accountability chain; no synthetic resume/confirm, ever).
 *
 * <p>Two dwell measurements, both windowed at
 * {@code deadline + n * escalation-renotify-days} for
 * {@code n = 0..escalation-renotify-limit}:
 * <ul>
 *   <li><b>Unresolved interventions</b> past
 *       {@code substrate.agents.intervention-escalation-days}: escalate to
 *       the NEXT oversight position, walking upward one position per elapsed
 *       renotify window (clamped at the chain tail; a 1-deep chain renotifies
 *       its only member). Dedupe key
 *       {@code escalate:{interventionId}:{position}:{n}} — exactly-once per
 *       window per the {@code uq_an_dedupe} UNIQUE backstop.</li>
 *   <li><b>Unconfirmed RECORDED_INTENT controls</b> past
 *       {@code substrate.agents.control-confirm-timeout-hours}: a
 *       CONTROL_UNCONFIRMED row to the FULL oversight chain per window —
 *       runtime acknowledgment alone never stops this clock (D6:
 *       acknowledgment ≠ confirmation). Dedupe key
 *       {@code unconfirmed:{agentId}:{eventId}:{n}} where {@code eventId} is
 *       the triggering control event.</li>
 * </ul>
 *
 * <p>Each candidate sweeps in its own transaction under the registration lock
 * (no torn reads against a concurrent verb); a lost dedupe race surfaces as
 * {@link DataIntegrityViolationException} and is swallowed — the next sweep
 * re-evaluates. EXTENSION POINT(agent-14): unification with the cascade
 * notification outbox + Phase-5 channel adapters is a flagged later refactor,
 * not v1 coupling (ADR-003 D13).
 */
@Service
public class AgentEscalationSweeper {

    private static final Logger log = LoggerFactory.getLogger(AgentEscalationSweeper.class);

    private final AgentRegistrationRepository registrationRepository;
    private final AgentInterventionRepository interventionRepository;
    private final AgentOversightLinkRepository oversightLinkRepository;
    private final AgentStatusEventRepository eventRepository;
    private final AgentNotificationService notificationService;
    private final AgentProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public AgentEscalationSweeper(AgentRegistrationRepository registrationRepository,
                                  AgentInterventionRepository interventionRepository,
                                  AgentOversightLinkRepository oversightLinkRepository,
                                  AgentStatusEventRepository eventRepository,
                                  AgentNotificationService notificationService,
                                  AgentProperties properties,
                                  Clock clock,
                                  ObjectMapper objectMapper,
                                  PlatformTransactionManager transactionManager) {
        this.registrationRepository = registrationRepository;
        this.interventionRepository = interventionRepository;
        this.oversightLinkRepository = oversightLinkRepository;
        this.eventRepository = eventRepository;
        this.notificationService = notificationService;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Hourly by default; {@code substrate.agents.sweep-cron} overrides (§1). */
    @Scheduled(cron = "${substrate.agents.sweep-cron:0 0 * * * *}")
    @SchedulerLock(name = "agent-escalation-sweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT2S")
    public void sweep() {
        sweepOnce();
    }

    /** Lease-free entry point for tests + the fixed-clock acceptance demo. */
    public int sweepOnce() {
        int notified = 0;
        for (AgentIntervention intervention : interventionRepository
                .findByStatus(AgentInterventionStatus.OPEN)) {
            try {
                notified += transactionTemplate.execute(status ->
                        sweepIntervention(intervention.getId()));
            } catch (DataIntegrityViolationException dedupeRace) {
                log.debug("Escalation sweep lost a dedupe race — skipping [interventionId={}]",
                        intervention.getId(), dedupeRace);
            }
        }
        for (AgentRegistration candidate : registrationRepository
                .findByControlPendingVerbIsNotNullAndControlConfirmedAtIsNull()) {
            try {
                notified += transactionTemplate.execute(status ->
                        sweepUnconfirmedControl(candidate.getId()));
            } catch (DataIntegrityViolationException dedupeRace) {
                log.debug("Unconfirmed-control sweep lost a dedupe race — skipping [agent={}]",
                        candidate.getId(), dedupeRace);
            }
        }
        log.debug("Agent escalation sweep complete [rowsInserted={}]", notified);
        return notified;
    }

    // -------------------------------------------------- intervention escalation

    private int sweepIntervention(UUID interventionId) {
        AgentIntervention intervention = interventionRepository.findById(interventionId).orElse(null);
        if (intervention == null || intervention.getStatus() != AgentInterventionStatus.OPEN) {
            return 0;   // resolved (or gone) since the candidate list was taken
        }
        AgentRegistration reg = registrationRepository
                .findByIdForUpdate(intervention.getAgentRegistrationId()).orElse(null);
        if (reg == null) {
            return 0;
        }
        Instant deadline = intervention.getOpenedAt()
                .plus(Duration.ofDays(properties.getInterventionEscalationDays()));
        List<AgentOversightLink> chain =
                oversightLinkRepository.findByAgentRegistrationIdOrderByPositionAsc(reg.getId());
        if (chain.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (int n : elapsedWindows(deadline, clock.instant())) {
            // Window n targets position 2+n (position 1 already received the
            // INTERVENTION_OPENED fan-out), clamped at the chain tail.
            AgentOversightLink target = chain.get(Math.min(n + 1, chain.size() - 1));
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("agentKey", reg.getAgentKey());
            payload.put("interventionId", intervention.getId().toString());
            payload.put("reason", intervention.getReason());
            payload.put("openedAt", intervention.getOpenedAt().toString());
            payload.put("escalationWindow", n);
            inserted += notificationService.fanOut(reg.getDomainId(), reg.getId(), null,
                    AgentNotificationType.INTERVENTION_ESCALATED, "destructive",
                    List.of(target), payload,
                    "escalate:" + intervention.getId() + ":" + target.getPosition() + ":" + n)
                    .size();
        }
        return inserted;
    }

    // ------------------------------------------------ unconfirmed-control dwell

    private int sweepUnconfirmedControl(UUID registrationId) {
        AgentRegistration reg = registrationRepository.findByIdForUpdate(registrationId).orElse(null);
        if (reg == null || reg.getControlPendingVerb() == null
                || reg.getControlConfirmedAt() != null || reg.getControlRequestedAt() == null) {
            return 0;   // confirmed/withdrawn since the candidate list was taken
        }
        Instant deadline = reg.getControlRequestedAt()
                .plus(Duration.ofHours(properties.getControlConfirmTimeoutHours()));
        List<AgentOversightLink> chain =
                oversightLinkRepository.findByAgentRegistrationIdOrderByPositionAsc(reg.getId());
        if (chain.isEmpty()) {
            return 0;
        }
        UUID triggeringEventId = triggeringControlEvent(reg)
                .map(AgentStatusEvent::getId)
                .orElse(reg.getId());   // defensive — the event always exists on the verb path
        int inserted = 0;
        for (int n : elapsedWindows(deadline, clock.instant())) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("agentKey", reg.getAgentKey());
            payload.put("verb", reg.getControlPendingVerb());
            payload.put("requestedAt", reg.getControlRequestedAt().toString());
            payload.put("acknowledgedAt", reg.getControlAcknowledgedAt() == null
                    ? null : reg.getControlAcknowledgedAt().toString());
            payload.put("escalationWindow", n);
            inserted += notificationService.fanOut(reg.getDomainId(), reg.getId(), null,
                    AgentNotificationType.CONTROL_UNCONFIRMED, "warn", chain, payload,
                    "unconfirmed:" + reg.getId() + ":" + triggeringEventId + ":" + n).size();
        }
        return inserted;
    }

    /** The control event whose intent is still unconfirmed (PAUSED / KILLED / RETIRED). */
    private java.util.Optional<AgentStatusEvent> triggeringControlEvent(AgentRegistration reg) {
        AgentEventType type = switch (reg.getControlPendingVerb()) {
            case "kill" -> AgentEventType.KILLED;
            case "retire" -> AgentEventType.RETIRED;
            case "throttle" -> AgentEventType.THROTTLE_SET;
            default -> AgentEventType.PAUSED;
        };
        return eventRepository.findFirstByAgentRegistrationIdAndEventTypeOrderByEventSeqDesc(
                reg.getId(), type);
    }

    // ----------------------------------------------------------------- shared

    /**
     * Elapsed renotify windows {@code n = 0..limit} at
     * {@code deadline + n * renotify-days} (cascade §9 idiom). A non-positive
     * renotify interval degrades to the single n=0 window.
     */
    private List<Integer> elapsedWindows(Instant deadline, Instant now) {
        List<Integer> elapsed = new ArrayList<>();
        int renotifyDays = properties.getEscalationRenotifyDays();
        for (int n = 0; n <= properties.getEscalationRenotifyLimit(); n++) {
            if (n > 0 && renotifyDays <= 0) {
                break;
            }
            Instant windowAt = deadline.plus(Duration.ofDays((long) n * renotifyDays));
            if (now.isBefore(windowAt)) {
                break;
            }
            elapsed.add(n);
        }
        return elapsed;
    }
}
