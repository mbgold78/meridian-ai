package com.meridian.substrate.app.postgres;

import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractCommitment;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.enums.ContractPartyType;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.MonitorStatus;
import com.meridian.substrate.common.repo.ContractCommitmentRepository;
import com.meridian.substrate.common.repo.ContractMonitorRepository;
import com.meridian.substrate.common.repo.ContractMonitorRunRepository;
import com.meridian.substrate.common.repo.ContractPartyRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.contract.monitor.ContractMonitorScheduler;
import com.meridian.substrate.engine.contract.monitor.MonitorSweepClaims;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Engine-spec contracts.md §14.4 scheduler rows on real Postgres 16:
 *
 * <ul>
 *   <li><b>ShedLock lease</b> — while another "instance" holds the
 *       {@code contract-monitor-sweep} lease (acquired through the same
 *       {@link LockProvider} bean over the V5 {@code shedlock} table), a
 *       sweep invocation is a NO-OP (the {@code @SchedulerLock} interceptor
 *       skips the method); after release the sweep claims and runs.</li>
 *   <li><b>FOR UPDATE SKIP LOCKED smoke</b> — the §10.3 native claim runs on
 *       the PG dialect: while one transaction holds a monitor's row lock, a
 *       concurrent claim returns EMPTY immediately (skips; never blocks).</li>
 * </ul>
 *
 * <p><b>Docker-less machines (e.g. the Windows dev box):</b> self-disables via
 * {@code @Testcontainers(disabledWithoutDocker = true)}; CI (ubuntu-latest)
 * runs the real gate. H2-green is never acceptance — H2 dev uses the
 * documented plain-ForUpdate fallback lane, which this suite is precisely the
 * acceptance for (port-map §4.4).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        // background fixed-delay sweep parked far away: the test drives sweeps
        "substrate.contracts.monitor.sweep-interval=PT1H"
})
class PostgresContractMonitorSweepIT {

    private static final String SWEEP_LOCK_NAME = "contract-monitor-sweep";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private ContractRepository contracts;
    @Autowired private ContractPartyRepository parties;
    @Autowired private ContractCommitmentRepository commitments;
    @Autowired private ContractMonitorRepository monitors;
    @Autowired private ContractMonitorRunRepository runs;
    @Autowired private ContractMonitorScheduler scheduler;
    @Autowired private MonitorSweepClaims claims;
    @Autowired private LockProvider lockProvider;
    @Autowired private PlatformTransactionManager txManager;

    // ------------------------------------------------------------ ShedLock lease

    @Test
    void secondSweepInstanceIsANoOpWhileLeased() {
        ContractMonitor monitor = dueMonitor("shedlock-lease");

        // Another instance holds the lease (same provider, same lock table).
        // Retry briefly: the context-startup sweep's lockAtLeastFor window
        // (PT2S) may still be holding the name right after boot.
        Optional<SimpleLock> foreignLease = Optional.empty();
        for (int attempt = 0; attempt < 20 && foreignLease.isEmpty(); attempt++) {
            foreignLease = lockProvider.lock(new LockConfiguration(
                    Instant.now(), SWEEP_LOCK_NAME, Duration.ofMinutes(5), Duration.ZERO));
            if (foreignLease.isEmpty()) {
                sleep(500);
            }
        }
        assertThat(foreignLease).as("test could acquire the lease up front").isPresent();
        try {
            scheduler.sweep();                       // @SchedulerLock skips — no-op
            assertThat(runs.findByMonitorIdOrderByStartedAtAsc(monitor.getId()))
                    .as("leased elsewhere ⇒ this instance must not run anything")
                    .isEmpty();
        } finally {
            foreignLease.get().unlock();
        }

        scheduler.sweep();                           // lease free ⇒ sweeps
        assertThat(runs.findByMonitorIdOrderByStartedAtAsc(monitor.getId()))
                .as("after release the sweep claims and records the run")
                .hasSize(1);
    }

    // ----------------------------------------------------- SKIP LOCKED smoke

    @Test
    void nativeClaimSkipsARowLockedByAConcurrentClaimer() throws Exception {
        assertThat(claims.isPostgres())
                .as("§10.3: the native SKIP LOCKED lane must be active on PG")
                .isTrue();
        ContractMonitor monitor = dueMonitor("skip-locked");

        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<Boolean> holder = CompletableFuture.supplyAsync(() -> tx.execute(s -> {
                Optional<ContractMonitor> first = claims.claimDue(monitor.getId(), Instant.now());
                claimed.countDown();
                try {
                    release.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return first.isPresent();
            }), pool);

            assertThat(claimed.await(20, TimeUnit.SECONDS)).isTrue();

            long start = System.nanoTime();
            Optional<ContractMonitor> second = tx.execute(s ->
                    claims.claimDue(monitor.getId(), Instant.now()));
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

            assertThat(second)
                    .as("SKIP LOCKED: a concurrently locked row is skipped, not waited on")
                    .isEmpty();
            assertThat(elapsedMillis)
                    .as("the claim returned immediately (no FOR UPDATE block)")
                    .isLessThan(5_000);

            release.countDown();
            assertThat(holder.get(20, TimeUnit.SECONDS))
                    .as("the first claimer held the row all along")
                    .isTrue();

            // lock released with the holder's commit ⇒ claimable again
            Optional<ContractMonitor> reclaimed =
                    tx.execute(s -> claims.claimDue(monitor.getId(), Instant.now()));
            assertThat(reclaimed).isPresent();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ support

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private ContractMonitor dueMonitor(String key) {
        Contract contract = new Contract();
        contract.setDomainId(UUID.randomUUID());
        contract.setContractKey("sweep-it-" + key);
        contract.setContractType("cross-portfolio-opportunity");
        contract.setTitle("Sweep IT contract " + key);
        contract.setEstimatedValueAmount(BigDecimal.ONE);
        contract.setStatus(ContractStatus.ACTIVE);
        contract.setProposedAt(Instant.now());
        contract.setProposedBy("sweep-it");
        contract.setActivatedAt(Instant.now());
        contract = contracts.save(contract);

        ContractParty party = new ContractParty();
        party.setContractId(contract.getId());
        party.setPartyLabel("Sweep Party");
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
        commitments.save(commitment);

        ContractMonitor monitor = new ContractMonitor();
        monitor.setContractId(contract.getId());
        monitor.setName("sweep-it-monitor-" + key);
        monitor.setRuleType(MonitorRuleType.ATTESTATION_CADENCE);
        monitor.setCadence("MONTHLY");
        monitor.setGraceDays(7);
        monitor.setStatus(MonitorStatus.ACTIVE);
        monitor.setNextRunAt(Instant.now().minusSeconds(120));
        return monitors.save(monitor);
    }
}
