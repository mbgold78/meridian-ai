package com.meridian.substrate.engine.contract.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.ContractBreach;
import com.meridian.substrate.common.domain.ContractCommitment;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.ContractMonitorRun;
import com.meridian.substrate.common.domain.enums.BreachDetectedBy;
import com.meridian.substrate.common.domain.enums.BreachStatus;
import com.meridian.substrate.common.domain.enums.CommitmentComplianceState;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.MonitorRunOutcome;
import com.meridian.substrate.common.repo.ContractBreachRepository;
import com.meridian.substrate.common.repo.ContractCommitmentRepository;
import com.meridian.substrate.common.repo.ContractMonitorRepository;
import com.meridian.substrate.common.repo.ContractMonitorRunRepository;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.contract.events.AfterCommit;
import com.meridian.substrate.engine.contract.events.MonitorErrorEvent;
import com.meridian.substrate.engine.contract.registry.ContractActor;
import com.meridian.substrate.engine.contract.registry.ContractEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Executes ONE claimed monitor (engine-spec contracts.md §5.2 steps 2+4):
 * dispatches the {@link MonitorEvaluator} SPI by rule type, records the
 * APPEND-ONLY {@code contract_monitor_runs} row with its BOUNDED trace
 * (§1 {@code substrate.contracts.trace.max-bytes}), advances
 * {@code last_run_at / last_outcome / next_run_at} (next cadence boundary +
 * jitter ≤ 60 s), and applies outcome side effects.
 *
 * <p><b>ADR D10 HARD INVARIANT, enforced structurally here:</b> breach
 * candidates are forwarded to {@link BreachService} ONLY when
 * {@link MonitorRunOutcome#mayOpenOrRefreshBreach()} — an INDETERMINATE /
 * NOT_DUE / ERROR run can NEVER open or refresh a breach, whatever an
 * evaluator returns (a candidate on a non-breach outcome is a defect: logged
 * loudly, never applied). Pinned by the §14.3 property-style suite.
 *
 * <p>Evaluator exception ⇒ run outcome ERROR + {@link MonitorErrorEvent}
 * (admin signal, after commit); breach state untouched (§5.3).
 *
 * <p>A COMPLIANT pass over open breaches of this monitor appends
 * {@code BREACH_COMPLIANCE_RESTORED} but does NOT auto-close — human
 * REMEDIATED confirm required (§2.4; auto-close is extension point §15 #7).
 *
 * <p>Caller contract: the monitor row is CLAIMED (row lock) inside the
 * caller's transaction — {@code MonitorSweepClaims} per §10.3.
 */
@Service
public class MonitorRunService {

    private static final Logger log = LoggerFactory.getLogger(MonitorRunService.class);

    private static final Set<BreachStatus> OPEN_STATUSES = EnumSet.allOf(BreachStatus.class)
            .stream().filter(BreachStatus::isOpen)
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(BreachStatus.class)));

    private final Map<MonitorRuleType, MonitorEvaluator> evaluators;
    private final ContractMonitorRepository monitorRepository;
    private final ContractMonitorRunRepository runRepository;
    private final ContractCommitmentRepository commitmentRepository;
    private final ContractBreachRepository breachRepository;
    private final BreachService breachService;
    private final ContractEventService eventService;
    private final ContractProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final SweepJitter jitter;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public MonitorRunService(List<MonitorEvaluator> evaluators,
                             ContractMonitorRepository monitorRepository,
                             ContractMonitorRunRepository runRepository,
                             ContractCommitmentRepository commitmentRepository,
                             ContractBreachRepository breachRepository,
                             BreachService breachService,
                             ContractEventService eventService,
                             ContractProperties properties,
                             ApplicationEventPublisher eventPublisher,
                             SweepJitter jitter,
                             Clock clock,
                             ObjectMapper objectMapper) {
        this.evaluators = evaluators.stream().collect(Collectors.toMap(
                MonitorEvaluator::ruleType, e -> e,
                (a, b) -> {
                    throw new IllegalStateException("duplicate evaluator for " + a.ruleType());
                },
                () -> new EnumMap<>(MonitorRuleType.class)));
        this.monitorRepository = monitorRepository;
        this.runRepository = runRepository;
        this.commitmentRepository = commitmentRepository;
        this.breachRepository = breachRepository;
        this.breachService = breachService;
        this.eventService = eventService;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.jitter = jitter;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    /** Runs one CLAIMED monitor inside the caller's transaction; returns the appended run row. */
    public ContractMonitorRun execute(ContractMonitor monitor) {
        Instant startedAt = clock.instant();
        PeriodContext ctx = new PeriodContext(startedAt, zone());

        MonitorEvaluation evaluation = evaluateSafely(monitor, ctx);

        // ---- §5.2 step 2: append-only run row with bounded trace
        ContractMonitorRun run = new ContractMonitorRun();
        run.setMonitorId(monitor.getId());
        run.setStartedAt(startedAt);
        run.setFinishedAt(clock.instant());
        run.setOutcome(evaluation.outcome());
        run.setPeriodKey(evaluation.periodKey());
        run.setDetails(BoundedTrace.bound(objectMapper, evaluation.trace(),
                properties.getTrace().getMaxBytes()));
        run = runRepository.save(run);

        // ---- monitor projection + restart-safe schedule (§10.3)
        long jitterMillis = Math.min(60_000L, Math.max(0L, jitter.nextJitterMillis()));
        monitor.setLastRunAt(startedAt);
        monitor.setLastOutcome(evaluation.outcome());
        monitor.setNextRunAt(NextRunTimes.nextBoundary(monitor.getCadence(), startedAt, ctx.zone())
                .plusMillis(jitterMillis));
        monitorRepository.save(monitor);

        // ---- §5.2 step 4 / ADR D10 hard invariant: breaches ONLY on BREACH_DETECTED
        if (evaluation.outcome().mayOpenOrRefreshBreach()) {
            ContractActor actor = ContractActor.monitor(monitor.getName());
            for (MonitorEvaluation.BreachCandidate candidate : evaluation.breaches()) {
                breachService.onBreachDetected(new BreachService.OpenBreachCommand(
                        monitor.getContractId(), monitor.getId(), candidate.commitmentId(),
                        candidate.periodKey(), monitor.getSeverity(), candidate.summary(),
                        BreachDetectedBy.MONITOR, actor));
            }
        } else if (!evaluation.breaches().isEmpty()) {
            // Defensive — never expected: an evaluator violating the D10
            // contract. Loud, never applied (the §14.3 invariant suite pins
            // the DB-level consequence: zero breach writes on this path).
            log.error("Evaluator for monitor {} returned {} breach candidate(s) with outcome {}"
                            + " — IGNORED (ADR D10: INDETERMINATE/NOT_DUE/ERROR never breach)",
                    monitor.getId(), evaluation.breaches().size(), evaluation.outcome());
        }

        // ---- §2.3 monitor-driven compliance projections (COMPLIANT/AT_RISK only)
        for (MonitorEvaluation.CommitmentState state : evaluation.commitmentStates()) {
            applyCommitmentState(state, startedAt);
        }

        // ---- §2.4 PASS over an open breach: restore event, NO auto-close
        if (evaluation.outcome() == MonitorRunOutcome.COMPLIANT) {
            appendComplianceRestored(monitor);
        }
        return run;
    }

    // ------------------------------------------------------------------ helpers

    private MonitorEvaluation evaluateSafely(ContractMonitor monitor, PeriodContext ctx) {
        MonitorEvaluator evaluator = evaluators.get(monitor.getRuleType());
        if (evaluator == null) {
            // "unknown rule version" class (ADR D10) — INDETERMINATE, never an error storm
            ObjectNode trace = objectMapper.createObjectNode();
            trace.put("note", "no evaluator registered for rule_type " + monitor.getRuleType());
            return MonitorEvaluation.of(MonitorRunOutcome.INDETERMINATE, null, trace);
        }
        try {
            return evaluator.evaluate(monitor, ctx);
        } catch (RuntimeException ex) {
            log.error("Monitor evaluator threw [monitorId={}, ruleType={}]",
                    monitor.getId(), monitor.getRuleType(), ex);
            ObjectNode trace = objectMapper.createObjectNode();
            trace.put("rule", monitor.getRuleType().name());
            trace.put("error", String.valueOf(ex.getMessage()));
            trace.put("exception", ex.getClass().getName());
            AfterCommit.publish(eventPublisher, new MonitorErrorEvent(
                    monitor.getId(), monitor.getContractId(), monitor.getName(),
                    String.valueOf(ex.getMessage())));
            return MonitorEvaluation.of(MonitorRunOutcome.ERROR, null, trace);
        }
    }

    /**
     * §2.3 guard: monitor/attestation-driven projections never move a
     * BREACHED/WAIVED commitment — those states are owned by the breach
     * lifecycle (BREACHED ⇔ an open breach row; exit only via
     * REMEDIATED/WAIVED). {@code last_evaluated_at} advances regardless.
     */
    private void applyCommitmentState(MonitorEvaluation.CommitmentState state, Instant now) {
        ContractCommitment commitment = commitmentRepository.findById(state.commitmentId()).orElse(null);
        if (commitment == null) {
            log.warn("Evaluator referenced unknown commitment {} — skipped", state.commitmentId());
            return;
        }
        CommitmentComplianceState current = commitment.getComplianceState();
        if (current != state.state()
                && current != CommitmentComplianceState.BREACHED
                && current != CommitmentComplianceState.WAIVED
                && current.canTransitionTo(state.state())) {
            commitment.setComplianceState(state.state());
        }
        commitment.setLastEvaluatedAt(now);
        commitmentRepository.save(commitment);
    }

    /** §2.4: append {@code BREACH_COMPLIANCE_RESTORED} on each of this monitor's open breaches. */
    private void appendComplianceRestored(ContractMonitor monitor) {
        List<ContractBreach> open = breachRepository
                .findByContractIdAndStatusIn(monitor.getContractId(), OPEN_STATUSES).stream()
                .filter(b -> monitor.getId().equals(b.getMonitorId()))
                .toList();
        ContractActor actor = ContractActor.monitor(monitor.getName());
        for (ContractBreach breach : open) {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("note", "monitor PASS over open breach — human REMEDIATED confirm still"
                    + " required (§2.4; auto-close is extension point §15 #7)");
            eventService.append(breach.getContractId(),
                    ContractEventService.BREACH_COMPLIANCE_RESTORED, breach.getId(), actor, payload);
        }
    }

    private ZoneId zone() {
        return ZoneId.of(properties.getClock().getZone());
    }
}
