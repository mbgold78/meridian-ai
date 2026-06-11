package com.meridian.substrate.engine.contract.monitor;

import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractBreach;
import com.meridian.substrate.common.domain.ContractCommitment;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.enums.BreachDetectedBy;
import com.meridian.substrate.common.domain.enums.BreachResolution;
import com.meridian.substrate.common.domain.enums.BreachStatus;
import com.meridian.substrate.common.domain.enums.CommitmentComplianceState;
import com.meridian.substrate.common.domain.enums.ContractActorType;
import com.meridian.substrate.common.domain.enums.Severity;
import com.meridian.substrate.engine.contract.events.BreachSurfacedEvent;
import com.meridian.substrate.engine.contract.exception.ContractConflictException;
import com.meridian.substrate.engine.contract.exception.WaiverHumanOnlyException;
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

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §5.4 BreachService on the H2 fast lane (engine-spec contracts.md §14.3):
 * open/refresh dedup under the row lock (ADR D13 — sequential lane; the
 * concurrent partial-index race is the §14.4 Testcontainers row), surfacing,
 * the §2.4 human verbs with mandatory-field validation, the ADR D16
 * human-only waiver guard, and the ADR D14 waiver mirror anchor.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:contract-breach-svc;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BreachServiceTest {

    private static final Instant T0 = Instant.parse("2026-05-08T12:00:00Z");
    private static final ContractActor MONITOR_ACTOR = ContractActor.monitor("missing-monthly-report");
    private static final ContractActor OPERATOR = ContractActor.human("Operating Partner");

    @Autowired private ApplicationContext context;
    @Autowired private EntityManager entityManager;

    private MonitorHarness h;
    private Contract contract;
    private ContractCommitment commitment;
    private UUID monitorId;

    @BeforeEach
    void setUp() {
        h = new MonitorHarness(context, entityManager, T0);
        contract = h.activeContract();
        ContractParty party = h.party(contract, "Curasync Clinical Systems");
        commitment = h.commitment(contract, party, "MONTHLY");
        // Real monitor row: the H2 create-drop schema generates FK constraints
        // off the @ManyToOne navigation twins (the production V5 DDL has none
        // — soft-ref posture), so synthetic ids would violate them here.
        monitorId = h.monitor(contract,
                com.meridian.substrate.common.domain.enums.MonitorRuleType.ATTESTATION_CADENCE,
                "MONTHLY", 7, null,
                com.meridian.substrate.common.domain.enums.MonitorStatus.ACTIVE).getId();
    }

    private BreachService.OpenBreachCommand detection(String periodKey) {
        return new BreachService.OpenBreachCommand(contract.getId(), monitorId,
                commitment.getId(), periodKey, Severity.MEDIUM,
                "Missing attestation for period " + periodKey,
                BreachDetectedBy.MONITOR, MONITOR_ACTOR);
    }

    private ContractBreach detect(String periodKey) {
        return h.inTx(() -> h.breachService.onBreachDetected(detection(periodKey)));
    }

    // -------------------------------------------------------- open/refresh dedup

    @Test
    void firstDetectionOpensDetectedRowAndBreachesCommitment() {
        ContractBreach breach = detect("2026-04");

        assertThat(breach.getStatus()).isEqualTo(BreachStatus.DETECTED);
        assertThat(breach.getDetectedAt()).isEqualTo(T0);
        assertThat(breach.getLastConfirmedAt()).isEqualTo(T0);
        assertThat(breach.getDetectedBy()).isEqualTo(BreachDetectedBy.MONITOR);
        assertThat(h.commitments.findById(commitment.getId()).orElseThrow().getComplianceState())
                .isEqualTo(CommitmentComplianceState.BREACHED);
        assertThat(h.eventCount(contract.getId(), ContractEventService.BREACH_DETECTED)).isEqualTo(1);
    }

    @Test
    void reDetectionRefreshesNeverDuplicates() {
        ContractBreach first = detect("2026-04");
        h.clock.advance(Duration.ofHours(6));
        ContractBreach second = detect("2026-04");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getLastConfirmedAt()).isEqualTo(T0.plus(Duration.ofHours(6)));
        assertThat(h.breachesOf(contract)).hasSize(1);
        assertThat(h.eventCount(contract.getId(), ContractEventService.BREACH_DETECTED)).isEqualTo(1);
        assertThat(h.eventCount(contract.getId(), ContractEventService.BREACH_RECONFIRMED)).isEqualTo(1);
    }

    @Test
    void differentPeriodKeyIsADifferentBreachSource() {
        detect("2026-04");
        detect("2026-05");
        assertThat(h.breachesOf(contract)).hasSize(2);
    }

    @Test
    void nullSourceComponentsMatchNullOnRefresh() {
        // Metric-style source: no commitment binding (nullable column).
        BreachService.OpenBreachCommand noCommitment = new BreachService.OpenBreachCommand(
                contract.getId(), monitorId, null, "2026-Q2", Severity.HIGH,
                "imbalance over threshold", BreachDetectedBy.MONITOR, MONITOR_ACTOR);
        ContractBreach first = h.inTx(() -> h.breachService.onBreachDetected(noCommitment));
        ContractBreach second = h.inTx(() -> h.breachService.onBreachDetected(noCommitment));
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(h.breachesOf(contract)).hasSize(1);
    }

    @Test
    void detectionOutsideATransactionIsRejected() {
        assertThatThrownBy(() -> h.breachService.onBreachDetected(detection("2026-04")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active transaction");
    }

    @Test
    void aClosedBreachDoesNotBlockANewDetectionForTheSameSource() {
        ContractBreach first = detect("2026-04");
        h.breachService.surfaceAllDetected();
        h.breachService.acknowledge(first.getId(), null, OPERATOR);
        h.breachService.waive(first.getId(), "accepted risk", OPERATOR);

        ContractBreach second = detect("2026-04");
        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(h.breachesOf(contract)).hasSize(2);
    }

    // ----------------------------------------------------------------- surfacing

    @Test
    void surfacingMovesDetectedToSurfacedOnceAndPublishes() {
        ContractBreach breach = detect("2026-04");

        // returns ≥1: the pass is global and may also pick up DETECTED rows
        // left by sibling test methods on the shared class-level H2 context.
        assertThat(h.breachService.surfaceAllDetected()).isGreaterThanOrEqualTo(1);
        ContractBreach surfaced = h.breaches.findById(breach.getId()).orElseThrow();
        assertThat(surfaced.getStatus()).isEqualTo(BreachStatus.SURFACED);
        assertThat(surfaced.getSurfacedAt()).isEqualTo(T0);
        assertThat(h.eventCount(contract.getId(), ContractEventService.BREACH_SURFACED)).isEqualTo(1);
        assertThat(h.published).anySatisfy(event -> {
            assertThat(event).isInstanceOf(BreachSurfacedEvent.class);
            assertThat(((BreachSurfacedEvent) event).breachId()).isEqualTo(breach.getId());
            assertThat(((BreachSurfacedEvent) event).contractKey()).isEqualTo(contract.getContractKey());
        });

        // idempotent: a second pass has nothing left to surface
        assertThat(h.breachService.surfaceAllDetected()).isZero();
        assertThat(h.eventCount(contract.getId(), ContractEventService.BREACH_SURFACED)).isEqualTo(1);
    }

    // --------------------------------------------------------------- human verbs

    @Test
    void acknowledgeRecordsNamedActor() {
        ContractBreach breach = detect("2026-04");
        h.breachService.surfaceAllDetected();

        ContractBreach acknowledged = h.breachService.acknowledge(breach.getId(), "on it", OPERATOR);

        assertThat(acknowledged.getStatus()).isEqualTo(BreachStatus.ACKNOWLEDGED);
        assertThat(acknowledged.getAcknowledgedBy()).isEqualTo("Operating Partner");
        assertThat(acknowledged.getAcknowledgedAt()).isEqualTo(T0);
        assertThat(h.eventCount(contract.getId(), ContractEventService.BREACH_ACKNOWLEDGED)).isEqualTo(1);
    }

    @Test
    void acknowledgeBeforeSurfacingIsAStateConflict() {
        ContractBreach breach = detect("2026-04");
        assertThatThrownBy(() -> h.breachService.acknowledge(breach.getId(), null, OPERATOR))
                .isInstanceOf(ContractConflictException.class)
                .satisfies(ex -> assertThat(((ContractConflictException) ex).getCode())
                        .isEqualTo(ContractConflictException.Code.INVALID_CONTRACT_STATE));
    }

    @Test
    void remediateRequiresEvidenceAndRestoresCommitment() {
        ContractBreach breach = detect("2026-04");
        h.breachService.surfaceAllDetected();
        h.breachService.acknowledge(breach.getId(), null, OPERATOR);

        assertThatThrownBy(() -> h.breachService.remediate(breach.getId(), null, OPERATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidenceRefs");
        assertThatThrownBy(() -> h.breachService.remediate(
                breach.getId(), h.mapper.createArrayNode(), OPERATOR))
                .isInstanceOf(IllegalArgumentException.class);

        var refs = h.mapper.createArrayNode().add(UUID.randomUUID().toString());
        ContractBreach remediated = h.breachService.remediate(breach.getId(), refs, OPERATOR);

        assertThat(remediated.getStatus()).isEqualTo(BreachStatus.REMEDIATED);
        assertThat(remediated.getResolution()).isEqualTo(BreachResolution.REMEDIATED);
        assertThat(remediated.getResolutionActor()).isEqualTo("Operating Partner");
        assertThat(remediated.getRemediationEvidenceRefs()).isEqualTo(refs);
        assertThat(h.commitments.findById(commitment.getId()).orElseThrow().getComplianceState())
                .isEqualTo(CommitmentComplianceState.COMPLIANT);
        assertThat(h.eventCount(contract.getId(), ContractEventService.BREACH_REMEDIATED)).isEqualTo(1);
    }

    @Test
    void waiverIsHumanOnlyWithMandatoryRationaleAndMirrored() {
        ContractBreach breach = detect("2026-04");
        h.breachService.surfaceAllDetected();
        h.breachService.acknowledge(breach.getId(), null, OPERATOR);

        // ADR D16: agents may never waive — built-in, non-removable.
        assertThatThrownBy(() -> h.breachService.waive(breach.getId(), "agent says fine",
                new ContractActor(ContractActorType.AGENT, "agent-7")))
                .isInstanceOf(WaiverHumanOnlyException.class);
        assertThatThrownBy(() -> h.breachService.waive(breach.getId(), " ", OPERATOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rationale");

        ContractBreach waived = h.breachService.waive(breach.getId(), "regulatory exemption", OPERATOR);

        assertThat(waived.getStatus()).isEqualTo(BreachStatus.WAIVED);
        assertThat(waived.getResolution()).isEqualTo(BreachResolution.WAIVED);
        assertThat(waived.getResolutionRationale()).isEqualTo("regulatory exemption");
        assertThat(h.commitments.findById(commitment.getId()).orElseThrow().getComplianceState())
                .isEqualTo(CommitmentComplianceState.WAIVED);
        assertThat(h.eventCount(contract.getId(), ContractEventService.BREACH_WAIVED)).isEqualTo(1);
        assertThat(h.mirror.waived).singleElement()                 // ADR D14 anchor
                .satisfies(b -> assertThat(b.getId()).isEqualTo(breach.getId()));
    }

    @Test
    void lifecycleIsMonotonicForwardOnly() {
        ContractBreach breach = detect("2026-04");
        h.breachService.surfaceAllDetected();
        h.breachService.acknowledge(breach.getId(), null, OPERATOR);
        var refs = h.mapper.createArrayNode().add(UUID.randomUUID().toString());
        h.breachService.remediate(breach.getId(), refs, OPERATOR);

        // terminal: no further verbs
        assertThatThrownBy(() -> h.breachService.acknowledge(breach.getId(), null, OPERATOR))
                .isInstanceOf(ContractConflictException.class);
        assertThatThrownBy(() -> h.breachService.waive(breach.getId(), "late waiver", OPERATOR))
                .isInstanceOf(ContractConflictException.class);
        // rows are never deleted
        assertThat(h.breachesOf(contract)).hasSize(1);
    }
}
