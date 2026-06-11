package com.meridian.substrate.engine.contract.monitor;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractAttestation;
import com.meridian.substrate.common.domain.ContractBreach;
import com.meridian.substrate.common.domain.ContractCommitment;
import com.meridian.substrate.common.domain.ContractMetricSample;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.ContractMonitorRun;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.enums.BreachDetectedBy;
import com.meridian.substrate.common.domain.enums.ComplianceClaim;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.MonitorRunOutcome;
import com.meridian.substrate.common.domain.enums.MonitorStatus;
import com.meridian.substrate.common.domain.enums.Severity;
import com.meridian.substrate.engine.contract.registry.ContractActor;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §14.3 HARD INVARIANT, property-style (ADR D10): across ALL evaluators and
 * randomized world states, <b>no run with outcome INDETERMINATE / NOT_DUE /
 * ERROR creates or refreshes a breach</b> — asserted at the DATABASE level
 * after every randomized execution: the breach row-set, every row's
 * {@code last_confirmed_at}, and the BREACH_* event counts are byte-identical
 * unless the run's outcome was BREACH_DETECTED (and COMPLIANT runs never add
 * rows either — only BREACH_DETECTED may write breach state at all).
 *
 * <p>Seeded {@link Random} for reproducibility; scenarios cover all four
 * rule types, present/absent/misconfigured rule_specs, all four attestation
 * claims, in/past grace clocks, partial/complete metric periods,
 * reachable/unreachable/throwing probes (the throwing probe is the genuine
 * ERROR lane), and pre-existing open breaches whose refresh timestamps must
 * not move.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:contract-invariant;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MonitorNeverBreachesOnNonDetectionPropertyTest {

    private static final long SEED = 0xC0FFEE_2026L;
    private static final int ITERATIONS = 200;

    @Autowired private ApplicationContext context;
    @Autowired private EntityManager entityManager;

    @Test
    void noIndeterminateNotDueOrErrorRunEverCreatesOrRefreshesABreach() {
        MonitorHarness h = new MonitorHarness(context, entityManager,
                Instant.parse("2026-05-09T00:00:00Z"));
        Random random = new Random(SEED);
        int nonDetectionRuns = 0;
        int detectionRuns = 0;

        for (int i = 0; i < ITERATIONS; i++) {
            Scenario scenario = buildScenario(h, random, i);

            BreachWorldSnapshot before = BreachWorldSnapshot.take(h, scenario.monitor.getContractId());
            ContractMonitorRun run = h.inTx(() -> {
                ContractMonitor claimed =
                        h.monitors.findByIdForUpdate(scenario.monitor.getId()).orElseThrow();
                return h.runService.execute(claimed);
            });
            BreachWorldSnapshot after = BreachWorldSnapshot.take(h, scenario.monitor.getContractId());

            if (run.getOutcome().mayOpenOrRefreshBreach()) {
                detectionRuns++;
            } else {
                nonDetectionRuns++;
                assertThat(after.rowCount)
                        .as("iteration %d (%s, outcome %s): breach row-set must not grow",
                                i, scenario.description, run.getOutcome())
                        .isEqualTo(before.rowCount);
                assertThat(after.lastConfirmedAtById)
                        .as("iteration %d (%s, outcome %s): no last_confirmed_at refresh",
                                i, scenario.description, run.getOutcome())
                        .isEqualTo(before.lastConfirmedAtById);
                assertThat(after.statusById)
                        .as("iteration %d (%s, outcome %s): no breach status change",
                                i, scenario.description, run.getOutcome())
                        .isEqualTo(before.statusById);
                assertThat(after.breachDetectionEvents)
                        .as("iteration %d (%s, outcome %s): no BREACH_DETECTED/RECONFIRMED events",
                                i, scenario.description, run.getOutcome())
                        .isEqualTo(before.breachDetectionEvents);
            }
        }

        // The property is vacuous if the generator never produced both classes.
        assertThat(nonDetectionRuns).as("INDETERMINATE/NOT_DUE/ERROR coverage").isGreaterThan(50);
        assertThat(detectionRuns).as("BREACH_DETECTED coverage (sanity)").isGreaterThan(10);
    }

    // ----------------------------------------------------------- scenario gen

    private record Scenario(ContractMonitor monitor, String description) {
    }

    private Scenario buildScenario(MonitorHarness h, Random random, int iteration) {
        // Clock: in-grace vs past-grace for the April MONTHLY period (grace 7).
        boolean pastGrace = random.nextBoolean();
        h.clock.set(pastGrace ? Instant.parse("2026-05-09T00:00:00Z")
                : Instant.parse("2026-05-04T00:00:00Z"));

        Contract contract = h.activeContract();
        ContractParty partyA = h.party(contract, "Party A " + iteration);
        ContractParty partyB = h.party(contract, "Party B " + iteration);
        ContractCommitment commitment = h.commitment(contract, partyA,
                random.nextBoolean() ? "MONTHLY" : "QUARTERLY");

        MonitorRuleType ruleType =
                MonitorRuleType.values()[random.nextInt(MonitorRuleType.values().length)];
        StringBuilder description = new StringBuilder(ruleType.name())
                .append(pastGrace ? " past-grace" : " in-grace");

        ObjectNode spec = h.mapper.createObjectNode();
        switch (ruleType) {
            case ATTESTATION_CADENCE -> {
                if (random.nextBoolean()) {
                    ComplianceClaim claim = ComplianceClaim.values()[random.nextInt(4)];
                    attest(h, contract, partyA, commitment,
                            previousPeriod(commitment), claim);
                    description.append(" attested-").append(claim);
                } else {
                    description.append(" unattested");
                }
            }
            case METRIC_THRESHOLD -> {
                spec.put("metricKey", "utilization_share");
                if (random.nextBoolean()) {
                    spec.put("threshold", 0.30);
                } else {
                    description.append(" no-threshold");
                }
                var ids = spec.putArray("requiredPartyIds");
                ids.add(partyA.getId().toString());
                ids.add(partyB.getId().toString());
                int samples = random.nextInt(3);     // 0 | partial | complete
                description.append(" samples=").append(samples);
                if (samples >= 1) {
                    sample(h, contract, partyA, random.nextDouble());
                }
                if (samples >= 2) {
                    sample(h, contract, partyB, random.nextDouble());
                }
            }
            case MCP_PROBE -> {
                int probeMode = random.nextInt(3);
                description.append(" probe=").append(probeMode);
                switch (probeMode) {
                    case 0 -> h.probe.set(uri -> false);
                    case 1 -> h.probe.set(uri -> {
                        throw new IllegalStateException("probe blew up (ERROR lane)");
                    });
                    default -> h.probe.set(uri -> true);
                }
                if (random.nextBoolean()) {
                    // real MCP_CLIENT registration ⇒ the probe actually fires
                    // (incl. the throwing probe — the genuine ERROR lane)
                    var registration = new com.meridian.substrate.common.domain.ReaderRegistration();
                    registration.setDomainId(contract.getDomainId());
                    registration.setKind(com.meridian.substrate.common.domain.enums.ReaderKind.MCP_CLIENT);
                    registration.setName("party-endpoint-" + iteration);
                    registration.setUri("http://party.example/mcp");
                    registration = h.readers.save(registration);
                    spec.put("readerRegistrationId", registration.getId().toString());
                    description.append(" registered");
                } else {
                    spec.put("readerRegistrationId", UUID.randomUUID().toString());
                    description.append(" unregistered-id");
                }
            }
            case UNSTRUCTURED -> {
                // no spec — always INDETERMINATE
            }
        }

        ContractMonitor monitor = h.monitor(contract, ruleType,
                commitment.getCadence(), 7, spec.isEmpty() ? null : spec, MonitorStatus.ACTIVE);

        // Half the iterations carry a PRE-EXISTING open breach whose
        // last_confirmed_at must not move on a non-detection run.
        if (random.nextBoolean()) {
            h.inTx(() -> h.breachService.onBreachDetected(new BreachService.OpenBreachCommand(
                    contract.getId(), monitor.getId(), commitment.getId(),
                    previousPeriod(commitment), Severity.LOW, "pre-existing open breach",
                    BreachDetectedBy.MONITOR, ContractActor.monitor(monitor.getName()))));
            description.append(" +pre-open-breach");
        }
        return new Scenario(monitor, description.toString());
    }

    private String previousPeriod(ContractCommitment commitment) {
        return "MONTHLY".equals(commitment.getCadence()) ? "2026-04" : "2026-Q1";
    }

    private void attest(MonitorHarness h, Contract contract, ContractParty party,
                        ContractCommitment commitment, String periodKey, ComplianceClaim claim) {
        ContractAttestation attestation = new ContractAttestation();
        attestation.setCommitmentId(commitment.getId());
        attestation.setContractId(contract.getId());
        attestation.setPartyId(party.getId());
        attestation.setPeriodKey(periodKey);
        attestation.setAttestedBy("party");
        attestation.setAttestedAt(h.clock.instant());
        attestation.setComplianceClaim(claim);
        h.attestations.save(attestation);
    }

    private void sample(MonitorHarness h, Contract contract, ContractParty party, double value) {
        ContractMetricSample sample = new ContractMetricSample();
        sample.setContractId(contract.getId());
        sample.setPartyId(party.getId());
        sample.setMetricKey("utilization_share");
        sample.setMetricValue(value);
        sample.setPeriodKey("2026-04");              // closed at both scenario clocks
        sample.setReportedBy("agent");
        sample.setReportedAt(h.clock.instant());
        h.metricSamples.save(sample);
    }

    // ------------------------------------------------------------- snapshots

    private record BreachWorldSnapshot(int rowCount,
                                       Map<UUID, Instant> lastConfirmedAtById,
                                       Map<UUID, String> statusById,
                                       long breachDetectionEvents) {

        static BreachWorldSnapshot take(MonitorHarness h, UUID contractId) {
            List<ContractBreach> all = h.allBreaches();   // global row-set: rows never deleted
            Map<UUID, Instant> confirmed = new HashMap<>();
            Map<UUID, String> status = new HashMap<>();
            for (ContractBreach b : all) {
                confirmed.put(b.getId(), b.getLastConfirmedAt());
                status.put(b.getId(), b.getStatus().name());
            }
            // events are scoped to the scenario's contract — the only one a
            // run of its monitor could ever write to
            long detectionEvents = h.eventCount(contractId, "BREACH_DETECTED")
                    + h.eventCount(contractId, "BREACH_RECONFIRMED");
            return new BreachWorldSnapshot(all.size(), confirmed, status, detectionEvents);
        }
    }
}
