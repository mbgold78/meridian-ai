package com.meridian.substrate.engine.contract.monitor;

import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.ContractMonitorRun;
import com.meridian.substrate.common.domain.enums.MonitorStatus;
import com.meridian.substrate.common.repo.ContractMonitorRepository;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.contract.exception.ContractConflictException;
import jakarta.persistence.EntityNotFoundException;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The §5.2 sweep — {@code @Scheduled} fixed-delay
 * ({@code substrate.contracts.monitor.sweep-interval}) leased by ShedLock
 * ({@code @SchedulerLock(name="contract-monitor-sweep")}, JDBC provider over
 * the V5 {@code shedlock} table) so exactly one instance sweeps (§10.3):
 *
 * <ol>
 *   <li>Claim due monitors ({@code status=ACTIVE AND next_run_at <= now}) —
 *       per-monitor, Postgres native {@code FOR UPDATE SKIP LOCKED} / H2
 *       {@code ForUpdate} JPQL via {@link MonitorSweepClaims}.</li>
 *   <li>Dispatch the {@link MonitorEvaluator} SPI, record the append-only run
 *       with bounded trace, advance {@code last_run_at/last_outcome/next_run_at}
 *       (next cadence boundary + jitter ≤ 60 s) — {@link MonitorRunService}.</li>
 *   <li>Arming pass over predicate-bearing decline triggers of ACTIVE
 *       contracts (§2.6) — reuses this sweep, {@link DeclineTriggerArmingService}.</li>
 *   <li>Open/refresh breaches per outcome (§5.4, inside step 2's per-monitor
 *       transaction) and surface DETECTED → SURFACED (separate transaction,
 *       {@link BreachService#surfaceAllDetected()}).</li>
 * </ol>
 *
 * <p>Each monitor runs in its OWN transaction (cascade-sweeper precedent) so
 * one failure cannot poison the sweep; {@code next_run_at} is persisted ⇒
 * restart-safe. Master switch {@code substrate.contracts.monitor.enabled}
 * gates the bean (plus a belt-and-braces runtime check for late property
 * flips in tests).
 *
 * <p>{@link #runNow} is the §5.2 operator/acceptance lever ("executes a
 * single monitor synchronously, bypassing {@code next_run_at} but honoring
 * locks") — the §7.2 {@code POST .../monitors/{mid}/run-now} endpoint chunk
 * calls it; it surfaces synchronously so the §13 3b acceptance sees
 * DETECTED→SURFACED right after the call.
 */
@Service
@ConditionalOnProperty(name = "substrate.contracts.monitor.enabled",
        havingValue = "true", matchIfMissing = true)
public class ContractMonitorScheduler {

    private static final Logger log = LoggerFactory.getLogger(ContractMonitorScheduler.class);

    private final ContractMonitorRepository monitorRepository;
    private final MonitorSweepClaims claims;
    private final MonitorRunService runService;
    private final DeclineTriggerArmingService armingService;
    private final BreachService breachService;
    private final ContractProperties properties;
    private final java.time.Clock clock;
    private final TransactionTemplate transactionTemplate;

    public ContractMonitorScheduler(ContractMonitorRepository monitorRepository,
                                    MonitorSweepClaims claims,
                                    MonitorRunService runService,
                                    DeclineTriggerArmingService armingService,
                                    BreachService breachService,
                                    ContractProperties properties,
                                    java.time.Clock clock,
                                    PlatformTransactionManager transactionManager) {
        this.monitorRepository = monitorRepository;
        this.claims = claims;
        this.runService = runService;
        this.armingService = armingService;
        this.breachService = breachService;
        this.properties = properties;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * §5.2: fixed-delay sweep; the ShedLock lease makes a second instance's
     * fire a no-op while leased (§14.4 lease row). {@code lockAtMostFor}
     * bounds a crashed holder; {@code lockAtLeastFor} guards against clock
     * skew double-fires on very short sweeps.
     */
    @Scheduled(fixedDelayString = "${substrate.contracts.monitor.sweep-interval:PT60S}")
    @SchedulerLock(name = "contract-monitor-sweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT2S")
    public void sweep() {
        if (!properties.getMonitor().isEnabled()) {
            return;                                  // master switch (§1)
        }
        Instant now = clock.instant();
        List<UUID> due = monitorRepository
                .findByStatusAndNextRunAtBefore(MonitorStatus.ACTIVE, now)
                .stream().map(ContractMonitor::getId).toList();
        int ran = 0;
        for (UUID monitorId : due) {
            try {
                ContractMonitorRun run = transactionTemplate.execute(status ->
                        claims.claimDue(monitorId, now).map(runService::execute).orElse(null));
                if (run != null) {
                    ran++;
                }
            } catch (RuntimeException ex) {
                // DB-level failure for ONE monitor (evaluator exceptions are
                // already absorbed as ERROR runs) — sweep continues.
                log.error("Monitor sweep transaction failed [monitorId={}] — continuing", monitorId, ex);
            }
        }

        armingService.armingPass();                  // step 3 (§2.6)
        breachService.surfaceAllDetected();          // step 4 surface (§5.4, separate tx)
        log.debug("Contract monitor sweep complete [due={}, ran={}]", due.size(), ran);
    }

    /**
     * §5.2 run-now: synchronous single-monitor execution, bypassing
     * {@code next_run_at} but honoring locks. Requires an ACTIVE monitor
     * (decline suspends monitors — running a suspended monitor would be a
     * state error per §2.2 invariants). Returns the appended run row (§7.2).
     */
    public ContractMonitorRun runNow(UUID monitorId) {
        ContractMonitorRun run = transactionTemplate.execute(status -> {
            ContractMonitor monitor = claims.claimForRunNow(monitorId)
                    .orElseThrow(() -> new EntityNotFoundException(
                            "ContractMonitor not found: " + monitorId));
            if (monitor.getStatus() != MonitorStatus.ACTIVE) {
                throw new ContractConflictException(
                        ContractConflictException.Code.INVALID_CONTRACT_STATE,
                        monitor.getContractId(),
                        "monitor " + monitorId + " is " + monitor.getStatus()
                                + " — run-now requires an ACTIVE monitor (§2.5)");
            }
            return runService.execute(monitor);
        });
        breachService.surfaceAllDetected();          // §13 3b: DETECTED→SURFACED visible immediately
        return run;
    }
}
