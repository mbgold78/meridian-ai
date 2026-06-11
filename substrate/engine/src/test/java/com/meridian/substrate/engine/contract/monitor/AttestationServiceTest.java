package com.meridian.substrate.engine.contract.monitor;

import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractAttestation;
import com.meridian.substrate.common.domain.ContractBreach;
import com.meridian.substrate.common.domain.ContractCommitment;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.enums.BreachDetectedBy;
import com.meridian.substrate.common.domain.enums.BreachStatus;
import com.meridian.substrate.common.domain.enums.CommitmentComplianceState;
import com.meridian.substrate.common.domain.enums.ComplianceClaim;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.MonitorRunOutcome;
import com.meridian.substrate.common.domain.enums.MonitorStatus;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * §5.5 AttestationService (engine-spec contracts.md §14.3): idempotent upsert
 * on (commitment_id, period_key) with durable per-submission events, the §5.3
 * claim side effects, the §2.3 ACTIVE-only guard, and the ADR D13 dedup
 * coherence between a MISSED-attestation breach and the sweep's re-detection.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:contract-attest-svc;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AttestationServiceTest {

    private static final Instant T0 = Instant.parse("2026-05-08T12:00:00Z");
    private static final ContractActor COO = ContractActor.human("coo@curasync");

    @Autowired private ApplicationContext context;
    @Autowired private EntityManager entityManager;

    private MonitorHarness h;
    private Contract contract;
    private ContractParty party;
    private ContractCommitment commitment;
    private ContractMonitor monitor;

    @BeforeEach
    void setUp() {
        h = new MonitorHarness(context, entityManager, T0);
        contract = h.activeContract();
        party = h.party(contract, "Curasync Clinical Systems");
        commitment = h.commitment(contract, party, "MONTHLY");
        monitor = h.monitor(contract, MonitorRuleType.ATTESTATION_CADENCE,
                "MONTHLY", 7, null, MonitorStatus.ACTIVE);
    }

    private AttestationService.AttestCommand cmd(String periodKey, ComplianceClaim claim) {
        return new AttestationService.AttestCommand(commitment.getId(), periodKey, claim,
                null, null, "coo@curasync", "API", COO);
    }

    // --------------------------------------------------------------- upsert

    @Test
    void resubmissionUpsertsOneRowButAppendsEveryEvent() {
        ContractAttestation first = h.attestationService.attest(cmd("2026-05", ComplianceClaim.ON_TRACK));
        ContractAttestation second = h.attestationService.attest(cmd("2026-05", ComplianceClaim.AT_RISK));

        assertThat(second.getId()).isEqualTo(first.getId());          // latest-wins row
        assertThat(second.getComplianceClaim()).isEqualTo(ComplianceClaim.AT_RISK);
        assertThat(h.attestations.findByCommitmentId(commitment.getId())).hasSize(1);
        assertThat(h.eventCount(contract.getId(), ContractEventService.COMMITMENT_ATTESTED))
                .as("§5.5: every submission appends an event — history durable")
                .isEqualTo(2);
    }

    @Test
    void distinctPeriodsAreDistinctRows() {
        h.attestationService.attest(cmd("2026-04", ComplianceClaim.COMPLETE));
        h.attestationService.attest(cmd("2026-05", ComplianceClaim.ON_TRACK));
        assertThat(h.attestations.findByCommitmentId(commitment.getId())).hasSize(2);
    }

    // ---------------------------------------------------------- claim effects

    @Test
    void onTrackSetsCommitmentCompliant() {
        h.attestationService.attest(cmd("2026-05", ComplianceClaim.ON_TRACK));
        assertThat(h.commitments.findById(commitment.getId()).orElseThrow().getComplianceState())
                .isEqualTo(CommitmentComplianceState.COMPLIANT);
    }

    @Test
    void atRiskSetsCommitmentAtRiskWithoutBreach() {
        h.attestationService.attest(cmd("2026-05", ComplianceClaim.AT_RISK));
        assertThat(h.commitments.findById(commitment.getId()).orElseThrow().getComplianceState())
                .isEqualTo(CommitmentComplianceState.AT_RISK);
        assertThat(h.breachesOf(contract)).isEmpty();
    }

    @Test
    void missedAutoOpensBreachAttributedToTheBoundCadenceMonitor() {
        h.attestationService.attest(cmd("2026-04", ComplianceClaim.MISSED));

        List<ContractBreach> breaches = h.breachesOf(contract);
        assertThat(breaches).singleElement().satisfies(b -> {
            assertThat(b.getStatus()).isEqualTo(BreachStatus.DETECTED);
            assertThat(b.getDetectedBy()).isEqualTo(BreachDetectedBy.HUMAN_ATTESTATION);
            assertThat(b.getCommitmentId()).isEqualTo(commitment.getId());
            assertThat(b.getPeriodKey()).isEqualTo("2026-04");
            assertThat(b.getMonitorId())
                    .as("attributed to the bound cadence monitor — keeps the ADR D13 dedup key"
                            + " coherent with the sweep's re-detection")
                    .isEqualTo(monitor.getId());
        });
        assertThat(h.commitments.findById(commitment.getId()).orElseThrow().getComplianceState())
                .isEqualTo(CommitmentComplianceState.BREACHED);
    }

    @Test
    void sweepReDetectionRefreshesTheAttestationOpenedBreach() {
        h.attestationService.attest(cmd("2026-04", ComplianceClaim.MISSED));
        assertThat(h.breachesOf(contract)).hasSize(1);

        // The sweep finds the MISSED attestation for the same period and
        // re-detects: SAME (monitor, commitment, period) source ⇒ refresh.
        h.inTx(() -> {
            ContractMonitor claimed = h.monitors.findByIdForUpdate(monitor.getId()).orElseThrow();
            return h.runService.execute(claimed);
        });

        assertThat(h.breachesOf(contract)).as("one open row, never a duplicate (ADR D13)").hasSize(1);
        assertThat(h.eventCount(contract.getId(), ContractEventService.BREACH_RECONFIRMED)).isEqualTo(1);
        assertThat(h.monitors.findById(monitor.getId()).orElseThrow().getLastOutcome())
                .isEqualTo(MonitorRunOutcome.BREACH_DETECTED);
    }

    @Test
    void laterOnTrackAttestationDoesNotUnBreachTheCommitment() {
        h.attestationService.attest(cmd("2026-04", ComplianceClaim.MISSED));
        h.attestationService.attest(cmd("2026-05", ComplianceClaim.ON_TRACK));
        assertThat(h.commitments.findById(commitment.getId()).orElseThrow().getComplianceState())
                .as("BREACHED is owned by the breach lifecycle — exit only via REMEDIATED/WAIVED (§2.3)")
                .isEqualTo(CommitmentComplianceState.BREACHED);
    }

    // -------------------------------------------------------------- guards

    @Test
    void attestingANonActiveContractIsAStateConflict() {
        contract.setStatus(ContractStatus.PROPOSED);
        h.contracts.save(contract);
        assertThatThrownBy(() -> h.attestationService.attest(cmd("2026-05", ComplianceClaim.ON_TRACK)))
                .isInstanceOf(ContractConflictException.class)
                .satisfies(ex -> assertThat(((ContractConflictException) ex).getCode())
                        .isEqualTo(ContractConflictException.Code.INVALID_CONTRACT_STATE));
    }

    @Test
    void mandatoryFieldsAreServiceEnforced() {
        assertThatThrownBy(() -> h.attestationService.attest(
                new AttestationService.AttestCommand(commitment.getId(), " ", ComplianceClaim.ON_TRACK,
                        null, null, "coo", "API", COO)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("periodKey");
        assertThatThrownBy(() -> h.attestationService.attest(
                new AttestationService.AttestCommand(commitment.getId(), "2026-05", null,
                        null, null, "coo", "API", COO)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("complianceClaim");
        assertThatThrownBy(() -> h.attestationService.attest(
                new AttestationService.AttestCommand(commitment.getId(), "2026-05",
                        ComplianceClaim.ON_TRACK, null, null, null, "API", COO)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("attestedBy");
    }

    @Test
    void missingEvidenceRefsWarnButNeverFail() {
        var refs = h.mapper.createArrayNode().add(java.util.UUID.randomUUID().toString());
        ContractAttestation attestation = h.attestationService.attest(
                new AttestationService.AttestCommand(commitment.getId(), "2026-05",
                        ComplianceClaim.COMPLETE, "done", refs, "coo@curasync", "MCP", COO));
        assertThat(attestation.getEvidenceRefs()).isEqualTo(refs);   // recorded verbatim (§5.5)
        assertThat(attestation.getSubmittedVia()).isEqualTo("MCP");
    }
}
