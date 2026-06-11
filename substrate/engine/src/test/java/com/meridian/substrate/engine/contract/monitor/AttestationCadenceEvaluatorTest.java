package com.meridian.substrate.engine.contract.monitor;

import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractAttestation;
import com.meridian.substrate.common.domain.ContractCommitment;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.enums.CommitmentComplianceState;
import com.meridian.substrate.common.domain.enums.ComplianceClaim;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.MonitorRunOutcome;
import com.meridian.substrate.common.domain.enums.MonitorStatus;
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
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §5.3 ATTESTATION_CADENCE matrix (engine-spec contracts.md §14.3): attested
 * ON_TRACK/COMPLETE/AT_RISK/MISSED × in-grace/past-grace/no-attestation, plus
 * grace-boundary arithmetic, zone config, explicit rule_spec bindings, and
 * the later-attestation rule the §13 3b acceptance flow depends on.
 *
 * <p>Fixture-derived constants (MONTHLY cadence, graceDays=7) follow the
 * compiled co-tribal monitor; vector strings live in the DemoPack suite.
 * Source of truth: C:/Users/mbgol/dev/meridian-homepoc/frontend/src/demo/beacon/contracts.ts
 * (copied 2026-06-11).
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:contract-att-eval;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AttestationCadenceEvaluatorTest {

    /** Past April's grace window (Apr 30 + 7d = May 7): first breach-eligible day is May 8. */
    private static final Instant PAST_GRACE = Instant.parse("2026-05-08T12:00:00Z");
    private static final Instant IN_GRACE = Instant.parse("2026-05-05T12:00:00Z");
    private static final ZoneId UTC = ZoneOffset.UTC;

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

    private MonitorEvaluation evaluate(Instant now) {
        return h.attestationCadenceEvaluator.evaluate(monitor, new PeriodContext(now, UTC));
    }

    private void attest(String periodKey, ComplianceClaim claim) {
        ContractAttestation attestation = new ContractAttestation();
        attestation.setCommitmentId(commitment.getId());
        attestation.setContractId(contract.getId());
        attestation.setPartyId(party.getId());
        attestation.setPeriodKey(periodKey);
        attestation.setAttestedBy("coo@curasync");
        attestation.setAttestedAt(PAST_GRACE);
        attestation.setComplianceClaim(claim);
        h.attestations.save(attestation);
    }

    // ----------------------------------------------------- attested × claims

    @Test
    void onTrackAttestationIsCompliant() {
        attest("2026-04", ComplianceClaim.ON_TRACK);
        MonitorEvaluation e = evaluate(PAST_GRACE);
        assertThat(e.outcome()).isEqualTo(MonitorRunOutcome.COMPLIANT);
        assertThat(e.periodKey()).isEqualTo("2026-04");
        assertThat(e.breaches()).isEmpty();
        assertThat(e.commitmentStates()).containsExactly(new MonitorEvaluation.CommitmentState(
                commitment.getId(), CommitmentComplianceState.COMPLIANT));
    }

    @Test
    void completeAttestationIsCompliant() {
        attest("2026-04", ComplianceClaim.COMPLETE);
        MonitorEvaluation e = evaluate(PAST_GRACE);
        assertThat(e.outcome()).isEqualTo(MonitorRunOutcome.COMPLIANT);
        assertThat(e.breaches()).isEmpty();
    }

    @Test
    void atRiskAttestationIsCompliantRunWithAtRiskCommitment() {
        attest("2026-04", ComplianceClaim.AT_RISK);
        MonitorEvaluation e = evaluate(PAST_GRACE);
        assertThat(e.outcome()).isEqualTo(MonitorRunOutcome.COMPLIANT);
        assertThat(e.breaches()).isEmpty();
        assertThat(e.commitmentStates()).containsExactly(new MonitorEvaluation.CommitmentState(
                commitment.getId(), CommitmentComplianceState.AT_RISK));
    }

    @Test
    void missedAttestationDetectsBreach() {
        attest("2026-04", ComplianceClaim.MISSED);
        MonitorEvaluation e = evaluate(PAST_GRACE);
        assertThat(e.outcome()).isEqualTo(MonitorRunOutcome.BREACH_DETECTED);
        assertThat(e.breaches()).singleElement().satisfies(b -> {
            assertThat(b.commitmentId()).isEqualTo(commitment.getId());
            assertThat(b.periodKey()).isEqualTo("2026-04");
        });
    }

    // ------------------------------------------------ no attestation × grace

    @Test
    void noAttestationInGraceIsNotDue() {
        assertThat(evaluate(IN_GRACE).outcome()).isEqualTo(MonitorRunOutcome.NOT_DUE);
    }

    @Test
    void noAttestationOnTheGraceDeadlineDayIsStillNotDue() {
        // §5.3: "now ≤ periodEnd+graceDays → NOT_DUE" — May 7 IS the deadline.
        assertThat(evaluate(Instant.parse("2026-05-07T23:00:00Z")).outcome())
                .isEqualTo(MonitorRunOutcome.NOT_DUE);
    }

    @Test
    void noAttestationPastGraceDetectsBreach() {
        MonitorEvaluation e = evaluate(PAST_GRACE);
        assertThat(e.outcome()).isEqualTo(MonitorRunOutcome.BREACH_DETECTED);
        assertThat(e.breaches()).singleElement().satisfies(b ->
                assertThat(b.periodKey()).isEqualTo("2026-04"));
    }

    @Test
    void quarterlyCadenceUsesQuarterPeriodAndGrace() {
        ContractCommitment quarterly = h.commitment(contract, party, "QUARTERLY");
        monitor = h.monitor(contract, MonitorRuleType.ATTESTATION_CADENCE,
                "QUARTERLY", 7, null, MonitorStatus.ACTIVE);
        // Both MONTHLY + QUARTERLY commitments are cadence-bearing defaults;
        // bind explicitly to isolate the quarterly one.
        monitor.setRuleSpec(h.mapper.createObjectNode()
                .set("commitmentIds", h.mapper.createArrayNode().add(quarterly.getId().toString())));
        h.monitors.save(monitor);

        // Q1 ended Mar 31; grace to Apr 7 — past grace on May 8.
        MonitorEvaluation e = evaluate(PAST_GRACE);
        assertThat(e.outcome()).isEqualTo(MonitorRunOutcome.BREACH_DETECTED);
        assertThat(e.breaches()).singleElement().satisfies(b ->
                assertThat(b.periodKey()).isEqualTo("2026-Q1"));
    }

    // --------------------------------------------------- later-attestation rule

    @Test
    void laterPeriodAttestationGovernsWhenGapPeriodUnattested() {
        // §13 3b acceptance: April missed (breach already open in real flow),
        // May attested ON_TRACK ⇒ run-now is COMPLIANT, not a re-breach.
        attest("2026-05", ComplianceClaim.ON_TRACK);
        MonitorEvaluation e = evaluate(PAST_GRACE);
        assertThat(e.outcome()).isEqualTo(MonitorRunOutcome.COMPLIANT);
        assertThat(e.breaches()).isEmpty();
    }

    @Test
    void laterPeriodMissedClaimStillDetectsBreachAtThatPeriod() {
        attest("2026-05", ComplianceClaim.MISSED);
        MonitorEvaluation e = evaluate(PAST_GRACE);
        assertThat(e.outcome()).isEqualTo(MonitorRunOutcome.BREACH_DETECTED);
        assertThat(e.breaches()).singleElement().satisfies(b ->
                assertThat(b.periodKey()).isEqualTo("2026-05"));
    }

    // -------------------------------------------------------- edges and zone

    @Test
    void unsupportedCadenceIsIndeterminateNeverABreach() {
        commitment.setCadence(null);
        h.commitments.save(commitment);
        monitor.setCadence("DAILY");                 // no §5.1 period-key semantics
        monitor.setRuleSpec(h.mapper.createObjectNode()
                .set("commitmentIds", h.mapper.createArrayNode().add(commitment.getId().toString())));
        h.monitors.save(monitor);

        MonitorEvaluation e = evaluate(PAST_GRACE);
        assertThat(e.outcome()).isEqualTo(MonitorRunOutcome.INDETERMINATE);
        assertThat(e.breaches()).isEmpty();
    }

    @Test
    void noEvaluableCommitmentsIsIndeterminate() {
        commitment.setCadence(null);                 // no cadence-bearing commitments remain
        h.commitments.save(commitment);
        MonitorEvaluation e = evaluate(PAST_GRACE);
        assertThat(e.outcome()).isEqualTo(MonitorRunOutcome.INDETERMINATE);
        assertThat(e.breaches()).isEmpty();
    }

    @Test
    void zoneConfigShiftsThePeriodBoundary() {
        // 2026-04-30T20:00Z: UTC date Apr 30 ⇒ P = 2026-03;
        // Pacific/Kiritimati (UTC+14) date May 1 ⇒ P = 2026-04.
        Instant now = Instant.parse("2026-04-30T20:00:00Z");
        MonitorEvaluation utc = h.attestationCadenceEvaluator.evaluate(
                monitor, new PeriodContext(now, ZoneOffset.UTC));
        MonitorEvaluation kiritimati = h.attestationCadenceEvaluator.evaluate(
                monitor, new PeriodContext(now, ZoneId.of("Pacific/Kiritimati")));
        assertThat(utc.periodKey()).isEqualTo("2026-03");
        assertThat(kiritimati.periodKey()).isEqualTo("2026-04");
    }
}
