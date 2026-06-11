package com.meridian.substrate.app.postgres;

import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractAttestation;
import com.meridian.substrate.common.domain.ContractBreach;
import com.meridian.substrate.common.domain.ContractCommitment;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.enums.BreachDetectedBy;
import com.meridian.substrate.common.domain.enums.BreachStatus;
import com.meridian.substrate.common.domain.enums.ComplianceClaim;
import com.meridian.substrate.common.domain.enums.ContractPartyType;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.MonitorStatus;
import com.meridian.substrate.common.domain.enums.Severity;
import com.meridian.substrate.common.repo.ContractAttestationRepository;
import com.meridian.substrate.common.repo.ContractBreachRepository;
import com.meridian.substrate.common.repo.ContractCommitmentRepository;
import com.meridian.substrate.common.repo.ContractEventRepository;
import com.meridian.substrate.common.repo.ContractMonitorRepository;
import com.meridian.substrate.common.repo.ContractPartyRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.contract.monitor.AttestationService;
import com.meridian.substrate.engine.contract.monitor.BreachService;
import com.meridian.substrate.engine.contract.registry.ContractActor;
import com.meridian.substrate.engine.contract.registry.ContractEventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Engine-spec contracts.md §14.4 race rows on real Postgres 16:
 *
 * <ul>
 *   <li><b>Attestation upsert race</b> — concurrent {@code attest} for the
 *       same (commitment, period) ⇒ ONE row (ADR D13
 *       {@code uq_attestation_period}), TWO {@code COMMITMENT_ATTESTED}
 *       events (§5.5 history durability), no surfaced exception (the loser's
 *       §10.5 one-retry re-reads and updates).</li>
 *   <li><b>Breach dedup race</b> — concurrent {@code onBreachDetected} for
 *       the same (monitor, commitment, period) source ⇒ ONE open row: the
 *       V5_1 partial unique index backstop fires for a true double-insert and
 *       the caller retries as refresh (§10.6); {@code last_confirmed_at}
 *       refreshed, {@code BREACH_RECONFIRMED} appended — never a second
 *       row.</li>
 * </ul>
 *
 * <p>Deliberately NOT {@code @Transactional}: each contender commits its own
 * transaction so the race is real (cascade-concurrency latch idiom).
 *
 * <p><b>Docker-less machines (e.g. the Windows dev box):</b> self-disables via
 * {@code @Testcontainers(disabledWithoutDocker = true)}; CI (ubuntu-latest)
 * runs the real gate. H2-green is never acceptance (port-map §4.4).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        // keep the background sweep out of the race window
        "substrate.contracts.monitor.sweep-interval=PT1H"
})
class PostgresContractDetectionRaceIT {

    /** A few passes so a genuinely stochastic interleaving has room to surface. */
    private static final int ITERATIONS = 3;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private ContractRepository contracts;
    @Autowired private ContractPartyRepository parties;
    @Autowired private ContractCommitmentRepository commitments;
    @Autowired private ContractMonitorRepository monitors;
    @Autowired private ContractAttestationRepository attestations;
    @Autowired private ContractBreachRepository breaches;
    @Autowired private ContractEventRepository events;
    @Autowired private AttestationService attestationService;
    @Autowired private BreachService breachService;
    @Autowired private PlatformTransactionManager txManager;

    // ------------------------------------------------------- attestation race

    @Test
    void concurrentAttestationsForSamePeriod_oneRowTwoEvents() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ITERATIONS; i++) {
                Graph g = graph("att-race-" + i);

                CountDownLatch startGate = new CountDownLatch(1);
                CompletableFuture<ContractAttestation> a = attestAsync(pool, startGate, g, "first@party");
                CompletableFuture<ContractAttestation> b = attestAsync(pool, startGate, g, "second@party");
                startGate.countDown();
                CompletableFuture.allOf(a, b).get(30, TimeUnit.SECONDS);

                List<ContractAttestation> rows = attestations.findByCommitmentId(g.commitment.getId());
                assertThat(rows).as("iteration %d: ADR D13 upsert key — one row", i).hasSize(1);
                assertThat(rows.get(0).getId())
                        .isEqualTo(a.get().getId())
                        .isEqualTo(b.get().getId());
                assertThat(countEvents(g.contract.getId(), ContractEventService.COMMITMENT_ATTESTED))
                        .as("iteration %d: §5.5 — every submission appends an event", i)
                        .isEqualTo(2);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private CompletableFuture<ContractAttestation> attestAsync(ExecutorService pool,
                                                               CountDownLatch startGate,
                                                               Graph g, String attestedBy) {
        return CompletableFuture.supplyAsync(() -> {
            await(startGate);
            return attestationService.attest(new AttestationService.AttestCommand(
                    g.commitment.getId(), "2026-05", ComplianceClaim.ON_TRACK,
                    "racing submission", null, attestedBy, "API",
                    ContractActor.human(attestedBy)));
        }, pool);
    }

    // ------------------------------------------------------- breach dedup race

    @Test
    void concurrentBreachDetectionsForSameSource_oneOpenRowRefreshed() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            for (int i = 0; i < ITERATIONS; i++) {
                Graph g = graph("breach-race-" + i);
                BreachService.OpenBreachCommand source = new BreachService.OpenBreachCommand(
                        g.contract.getId(), g.monitor.getId(), g.commitment.getId(), "2026-04",
                        Severity.MEDIUM, "Missing attestation for period 2026-04",
                        BreachDetectedBy.MONITOR, ContractActor.monitor(g.monitor.getName()));

                CountDownLatch startGate = new CountDownLatch(1);
                CompletableFuture<ContractBreach> a = detectAsync(pool, tx, startGate, source);
                CompletableFuture<ContractBreach> b = detectAsync(pool, tx, startGate, source);
                startGate.countDown();
                CompletableFuture.allOf(a, b).get(30, TimeUnit.SECONDS);

                List<ContractBreach> open = breaches.findByContractIdAndStatusIn(
                        g.contract.getId(), List.of(BreachStatus.DETECTED));
                assertThat(open)
                        .as("iteration %d: at most one open breach per source (ADR D13;"
                                + " V5_1 partial-index backstop)", i)
                        .hasSize(1);
                assertThat(a.get().getId()).isEqualTo(b.get().getId());
                assertThat(countEvents(g.contract.getId(), ContractEventService.BREACH_DETECTED))
                        .isEqualTo(1);
                assertThat(countEvents(g.contract.getId(), ContractEventService.BREACH_RECONFIRMED))
                        .as("iteration %d: the loser refreshed — last_confirmed_at moved", i)
                        .isEqualTo(1);
                assertThat(open.get(0).getLastConfirmedAt()).isNotNull();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * One contender: own transaction; a lost double-insert (partial unique
     * index fires at commit/flush) is retried ONCE in a fresh transaction,
     * where the re-read finds the winner's committed row and refreshes —
     * exactly the §10.6 caller contract.
     */
    private CompletableFuture<ContractBreach> detectAsync(ExecutorService pool, TransactionTemplate tx,
                                                          CountDownLatch startGate,
                                                          BreachService.OpenBreachCommand source) {
        return CompletableFuture.supplyAsync(() -> {
            await(startGate);
            try {
                return tx.execute(status -> breachService.onBreachDetected(source));
            } catch (DataIntegrityViolationException lostInsertRace) {
                return tx.execute(status -> breachService.onBreachDetected(source));
            }
        }, pool);
    }

    // ------------------------------------------------------------------ support

    private record Graph(Contract contract, ContractCommitment commitment, ContractMonitor monitor) {
    }

    private Graph graph(String key) {
        Contract contract = new Contract();
        contract.setDomainId(UUID.randomUUID());
        contract.setContractKey(key);
        contract.setContractType("cross-portfolio-opportunity");
        contract.setTitle("Race IT contract " + key);
        contract.setEstimatedValueAmount(BigDecimal.ONE);
        contract.setStatus(ContractStatus.ACTIVE);
        contract.setProposedAt(Instant.now());
        contract.setProposedBy("race-it");
        contract.setActivatedAt(Instant.now());
        contract = contracts.save(contract);

        ContractParty party = new ContractParty();
        party.setContractId(contract.getId());
        party.setPartyLabel("Racing Party");
        party.setPartyType(ContractPartyType.ORG_UNIT);
        party.setResponseRequired(true);
        party.setSequenceOrder(0);
        party = parties.save(party);

        ContractCommitment commitment = new ContractCommitment();
        commitment.setContractId(contract.getId());
        commitment.setPartyId(party.getId());
        commitment.setText("Report monthly progress");
        commitment.setCadence("MONTHLY");
        commitment.setSequenceOrder(0);
        commitment = commitments.save(commitment);

        ContractMonitor monitor = new ContractMonitor();
        monitor.setContractId(contract.getId());
        monitor.setName("race-monitor-" + key);
        monitor.setRuleType(MonitorRuleType.ATTESTATION_CADENCE);
        monitor.setCadence("MONTHLY");
        monitor.setGraceDays(7);
        monitor.setStatus(MonitorStatus.SUSPENDED);  // never claimed by a background sweep
        monitor = monitors.save(monitor);

        return new Graph(contract, commitment, monitor);
    }

    private long countEvents(UUID contractId, String eventType) {
        return events.findByContractIdOrderByOccurredAtAsc(contractId).stream()
                .filter(e -> eventType.equals(e.getEventType()))
                .count();
    }

    private static void await(CountDownLatch gate) {
        try {
            gate.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
