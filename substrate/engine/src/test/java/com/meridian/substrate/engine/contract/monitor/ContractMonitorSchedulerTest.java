package com.meridian.substrate.engine.contract.monitor;

import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractBreach;
import com.meridian.substrate.common.domain.ContractCommitment;
import com.meridian.substrate.common.domain.ContractDeclineTrigger;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.ContractMonitorRun;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.enums.BreachStatus;
import com.meridian.substrate.common.domain.enums.ComplianceClaim;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.MonitorRunOutcome;
import com.meridian.substrate.common.domain.enums.MonitorStatus;
import com.meridian.substrate.engine.contract.events.DeclineTriggerArmedEvent;
import com.meridian.substrate.engine.contract.exception.ContractConflictException;
import com.meridian.substrate.engine.contract.registry.ContractActor;
import com.meridian.substrate.engine.contract.registry.ContractEventService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §5.2 sweep + run-now on the H2 fast lane (engine-spec contracts.md §14.3),
 * mirroring the §13 3b acceptance flow at service level: clock advanced past
 * 2026-05-08 (April period end + 7 grace days) ⇒ run ⇒ BREACH_DETECTED ⇒
 * DETECTED→SURFACED; attest 2026-05 ON_TRACK ⇒ run-now ⇒ COMPLIANT with
 * BREACH_COMPLIANCE_RESTORED (no auto-close); arming pass arms the metric
 * decline trigger (DECLINE_ELIGIBLE — never a breach); master switch.
 *
 * <p>ShedLock lease + PG SKIP LOCKED claims are §14.4 Testcontainers rows —
 * the scheduler here is hand-constructed (un-proxied), H2 fallback lane.
 *
 * <p>Fixture-derived constants (MONTHLY/grace 7; accuracy &lt; 0.90 arming
 * predicate) follow the §14.1 compiled monitors/triggers. Source of truth:
 * C:/Users/mbgol/dev/meridian-homepoc/frontend/src/demo/beacon/contracts.ts
 * (copied 2026-06-11).
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:contract-scheduler;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ContractMonitorSchedulerTest {

    /** Past 2026-05-08 per §13 3b (April end + 7 grace days). */
    private static final Instant PAST_GRACE = Instant.parse("2026-05-09T03:00:00Z");

    @Autowired private ApplicationContext context;
    @Autowired private EntityManager entityManager;

    private MonitorHarness h;
    private Contract contract;
    private ContractParty party;
    private ContractCommitment commitment;
    private ContractMonitor monitor;

    @BeforeEach
    void setUp() {
        h = new MonitorHarness(context, entityManager, PAST_GRACE);
        contract = h.activeContract();
        party = h.party(contract, "Curasync Clinical Systems");
        commitment = h.commitment(contract, party, "MONTHLY");
        monitor = h.monitor(contract, MonitorRuleType.ATTESTATION_CADENCE,
                "MONTHLY", 7, null, MonitorStatus.ACTIVE);
    }

    private ContractMonitor reload() {
        return h.monitors.findById(monitor.getId()).orElseThrow();
    }

    // ------------------------------------------------------------------- sweep

    @Test
    void sweepClaimsDueMonitorRecordsRunAndSurfacesBreach() {
        h.scheduler.sweep();

        // run row + monitor projection
        assertThat(h.runs.findByMonitorIdOrderByStartedAtAsc(monitor.getId()))
                .singleElement().satisfies(run -> {
                    assertThat(run.getOutcome()).isEqualTo(MonitorRunOutcome.BREACH_DETECTED);
                    assertThat(run.getPeriodKey()).isEqualTo("2026-04");
                    assertThat(run.getDetails()).isNotNull();
                });
        ContractMonitor swept = reload();
        assertThat(swept.getLastOutcome()).isEqualTo(MonitorRunOutcome.BREACH_DETECTED);
        assertThat(swept.getLastRunAt()).isEqualTo(PAST_GRACE);
        assertThat(swept.getNextRunAt())
                .as("next cadence boundary (jitter=none in tests)")
                .isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));

        // §5.2 step 4: DETECTED → SURFACED in the same sweep
        assertThat(h.breachesOf(contract)).singleElement().satisfies(breach -> {
            assertThat(breach.getStatus()).isEqualTo(BreachStatus.SURFACED);
            assertThat(breach.getPeriodKey()).isEqualTo("2026-04");
            assertThat(breach.getMonitorId()).isEqualTo(monitor.getId());
        });
    }

    @Test
    void sweepSkipsMonitorsNotYetDue() {
        monitor.setNextRunAt(PAST_GRACE.plusSeconds(3600));
        h.monitors.save(monitor);
        h.scheduler.sweep();
        assertThat(h.runs.findByMonitorIdOrderByStartedAtAsc(monitor.getId())).isEmpty();
    }

    @Test
    void sweepSkipsSuspendedMonitors() {
        monitor.setStatus(MonitorStatus.SUSPENDED);
        h.monitors.save(monitor);
        h.scheduler.sweep();
        assertThat(h.runs.findByMonitorIdOrderByStartedAtAsc(monitor.getId())).isEmpty();
    }

    @Test
    void masterSwitchDisablesTheSweep() {
        h.properties.getMonitor().setEnabled(false);
        h.scheduler.sweep();
        assertThat(h.runs.findByMonitorIdOrderByStartedAtAsc(monitor.getId())).isEmpty();
        assertThat(h.breachesOf(contract)).isEmpty();
    }

    @Test
    void repeatedSweepRefreshesTheSameBreachNeverDuplicates() {
        h.scheduler.sweep();
        // force re-due and sweep again
        monitor = reload();
        monitor.setNextRunAt(PAST_GRACE.minusSeconds(1));
        h.monitors.save(monitor);
        h.scheduler.sweep();

        assertThat(h.breachesOf(contract)).as("ADR D13 dedup across sweeps").hasSize(1);
        assertThat(h.eventCount(contract.getId(), ContractEventService.BREACH_RECONFIRMED)).isEqualTo(1);
    }

    // ----------------------------------------------------------------- run-now

    @Test
    void acceptanceFlow_breachThenAttestThenCompliantWithRestoreEvent() {
        // §13 3b step 1: run-now ⇒ BREACH_DETECTED, DETECTED→SURFACED.
        ContractMonitorRun first = h.scheduler.runNow(monitor.getId());
        assertThat(first.getOutcome()).isEqualTo(MonitorRunOutcome.BREACH_DETECTED);
        ContractBreach breach = h.breachesOf(contract).get(0);
        assertThat(breach.getStatus()).isEqualTo(BreachStatus.SURFACED);

        // §13 3b step 2: attest the CURRENT period ON_TRACK, run-now ⇒ COMPLIANT.
        h.attestationService.attest(new AttestationService.AttestCommand(
                commitment.getId(), "2026-05", ComplianceClaim.ON_TRACK,
                null, null, "coo@curasync", "API", ContractActor.human("coo@curasync")));
        ContractMonitorRun second = h.scheduler.runNow(monitor.getId());

        assertThat(second.getOutcome()).isEqualTo(MonitorRunOutcome.COMPLIANT);
        // §2.4: PASS over the open breach appends the restore event, NO auto-close.
        assertThat(h.eventCount(contract.getId(), ContractEventService.BREACH_COMPLIANCE_RESTORED))
                .isEqualTo(1);
        assertThat(h.breaches.findById(breach.getId()).orElseThrow().getStatus())
                .as("human REMEDIATED confirm still required")
                .isEqualTo(BreachStatus.SURFACED);
    }

    @Test
    void runNowBypassesNextRunAtButHonorsActiveStatus() {
        monitor.setNextRunAt(PAST_GRACE.plusSeconds(7200));      // not due
        h.monitors.save(monitor);

        ContractMonitorRun run = h.scheduler.runNow(monitor.getId());
        assertThat(run.getOutcome()).isEqualTo(MonitorRunOutcome.BREACH_DETECTED);

        monitor = reload();
        monitor.setStatus(MonitorStatus.SUSPENDED);              // e.g. after decline
        h.monitors.save(monitor);
        assertThatThrownBy(() -> h.scheduler.runNow(monitor.getId()))
                .isInstanceOf(ContractConflictException.class)
                .satisfies(ex -> assertThat(((ContractConflictException) ex).getCode())
                        .isEqualTo(ContractConflictException.Code.INVALID_CONTRACT_STATE));
    }

    // ------------------------------------------------------------ arming pass

    @Test
    void sweepArmsAndDisarmsPredicateBearingDeclineTriggers() {
        // fi-shared vector trigger: accuracy below 0.90 arms (§14.1).
        ContractDeclineTrigger trigger = h.trigger(contract,
                h.mapper.createObjectNode().put("metric", "accuracy").put("lt", 0.90));
        ContractDeclineTrigger humanJudgment = h.trigger(contract, null);

        // latest CLOSED period (April, MONTHLY) carries accuracy 0.87
        h.metricSampleService.report(new MetricSampleService.ReportMetricCommand(
                contract.getId(), party.getId(), "accuracy", 0.87, "2026-04",
                "polestar-agent", ContractActor.system("test")));

        h.scheduler.sweep();

        ContractDeclineTrigger armed = h.triggers.findById(trigger.getId()).orElseThrow();
        assertThat(armed.isArmed()).isTrue();
        assertThat(armed.getLastArmedAt()).isEqualTo(PAST_GRACE);
        assertThat(h.eventCount(contract.getId(), ContractEventService.DECLINE_TRIGGER_ARMED))
                .isEqualTo(1);
        assertThat(h.published).anySatisfy(event -> {
            assertThat(event).isInstanceOf(DeclineTriggerArmedEvent.class);
            assertThat(((DeclineTriggerArmedEvent) event).triggerId()).isEqualTo(trigger.getId());
        });
        // predicate-null = pure human judgment: never armed (§2.6)
        assertThat(h.triggers.findById(humanJudgment.getId()).orElseThrow().isArmed()).isFalse();
        // arming is NEVER a breach (the attestation breach is the only row)
        assertThat(h.breachesOf(contract)).allSatisfy(b ->
                assertThat(b.getMonitorId()).isEqualTo(monitor.getId()));

        // recovery: accuracy back above threshold ⇒ disarm + event
        h.metricSampleService.report(new MetricSampleService.ReportMetricCommand(
                contract.getId(), party.getId(), "accuracy", 0.95, "2026-04",
                "polestar-agent", ContractActor.system("test")));
        h.scheduler.sweep();
        assertThat(h.triggers.findById(trigger.getId()).orElseThrow().isArmed()).isFalse();
        assertThat(h.eventCount(contract.getId(), ContractEventService.DECLINE_TRIGGER_DISARMED))
                .isEqualTo(1);
    }
}
