package com.meridian.substrate.engine.decision.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.DesignDecision;
import com.meridian.substrate.common.domain.enums.DecisionStatus;
import com.meridian.substrate.common.domain.enums.DecisionType;
import com.meridian.substrate.common.repo.DesignDecisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence-backed chain tests (chunk C4 acceptance):
 * <ul>
 *   <li><b>Concurrent-append no-fork:</b> parallel appends through the per-domain
 *       lock produce one linear chain — every {@code previousHash} unique, exactly
 *       one GENESIS, full chain verifies. This is the race homepoc left open.</li>
 *   <li><b>Restart safety:</b> a brand-new service instance (fresh JVM state — new
 *       lock map, no caches) re-derives the chain head from the database and appends
 *       without forking; the startup verifier confirms chain integrity on boot.</li>
 *   <li><b>Tamper evidence through the DB:</b> an out-of-band row update breaks
 *       verification with the row's id reported.</li>
 * </ul>
 *
 * <p>Runs on H2 in PostgreSQL compatibility mode (entities declare
 * {@code columnDefinition = "TEXT"}, which H2 2.x only accepts in PG mode — same
 * pattern as common's {@code RepositorySmokeTest}). Per port-map §4.4, H2-green is
 * not the Postgres gate; the C11 Testcontainers suite is.
 *
 * <p>The class disables the test-managed transaction so each
 * {@code appendDecision} commits for real inside its own {@link
 * org.springframework.transaction.support.TransactionTemplate} transaction —
 * required for cross-thread visibility. Tests isolate by unique domainId instead
 * of rollback.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:decision-chain;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DecisionChainPersistenceTest {

    @Autowired
    private DesignDecisionRepository repository;

    @Autowired
    private PlatformTransactionManager txManager;

    private DecisionLogService service;

    @BeforeEach
    void setUp() {
        service = newService();
    }

    /** Builds a service exactly as a fresh JVM would — no shared in-memory state. */
    private DecisionLogService newService() {
        return new DecisionLogService(repository, new ObjectMapper(), txManager);
    }

    private DesignDecision decision(UUID domainId, String title) {
        DesignDecision d = new DesignDecision();
        d.setDomainId(domainId);
        d.setDecisionType(DecisionType.CONSOLIDATE);
        d.setStatus(DecisionStatus.PROPOSED);
        d.setTitle(title);
        d.setDescription("Description for " + title);
        return d;
    }

    // ------------------------------------------------------------------------
    // Concurrent appends must not fork the chain
    // ------------------------------------------------------------------------

    @Test
    void concurrentAppends_doNotForkTheChain() throws Exception {
        UUID domainId = UUID.randomUUID();
        int threads = 6;
        int appendsPerThread = 5;
        int total = threads * appendsPerThread;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int thread = t;
                futures.add(pool.submit(() -> {
                    startGate.await();
                    for (int i = 0; i < appendsPerThread; i++) {
                        service.appendDecision(
                                decision(domainId, "t" + thread + "-d" + i));
                    }
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS); // propagates any worker failure
            }
        } finally {
            pool.shutdownNow();
        }

        List<DesignDecision> chain = service.getDecisionLog(domainId);
        assertThat(chain).hasSize(total);

        // One linear chain: exactly one GENESIS, all previousHash values distinct,
        // and every non-genesis previousHash matches some decision's currentHash.
        long genesisCount = chain.stream()
                .filter(d -> "GENESIS".equals(d.getPreviousHash())).count();
        assertThat(genesisCount).as("exactly one chain root").isEqualTo(1);

        Set<String> previousHashes = chain.stream()
                .map(DesignDecision::getPreviousHash)
                .collect(Collectors.toSet());
        assertThat(previousHashes)
                .as("no two decisions link to the same predecessor (no fork)")
                .hasSize(total);

        Set<String> currentHashes = chain.stream()
                .map(DesignDecision::getCurrentHash)
                .collect(Collectors.toCollection(HashSet::new));
        assertThat(currentHashes).as("all hashes distinct").hasSize(total);

        HashChainVerification verification = service.verifyChain(domainId);
        assertThat(verification.valid())
                .as("verification message: %s", verification.message())
                .isTrue();
        assertThat(verification.verifiedLinks()).isEqualTo(total);
    }

    // ------------------------------------------------------------------------
    // Restart safety: chain head re-derived from DB by a fresh service instance
    // ------------------------------------------------------------------------

    @Test
    void restartedService_rederivesChainHeadFromDatabase_andAppendsWithoutFork() {
        UUID domainId = UUID.randomUUID();

        service.appendDecision(decision(domainId, "before-restart-1"));
        service.appendDecision(decision(domainId, "before-restart-2"));
        DesignDecision lastBeforeRestart =
                service.appendDecision(decision(domainId, "before-restart-3"));

        // Simulate restart: brand-new service instance, zero in-memory carry-over.
        DecisionLogService restarted = newService();

        assertThat(restarted.getChainHead(domainId))
                .as("head re-derived from DB equals last persisted hash")
                .isEqualTo(lastBeforeRestart.getCurrentHash());

        DesignDecision afterRestart =
                restarted.appendDecision(decision(domainId, "after-restart"));
        assertThat(afterRestart.getPreviousHash())
                .isEqualTo(lastBeforeRestart.getCurrentHash());

        HashChainVerification verification = restarted.verifyChain(domainId);
        assertThat(verification.valid()).isTrue();
        assertThat(verification.totalDecisions()).isEqualTo(4);
        assertThat(verification.verifiedLinks()).isEqualTo(4);
    }

    @Test
    void startupVerifier_verifiesAllChainsOnBoot_andFlagsBrokenOnes() {
        UUID healthyDomain = UUID.randomUUID();
        UUID tamperedDomain = UUID.randomUUID();

        service.appendDecision(decision(healthyDomain, "h-1"));
        service.appendDecision(decision(healthyDomain, "h-2"));
        DesignDecision victim = service.appendDecision(decision(tamperedDomain, "t-1"));
        service.appendDecision(decision(tamperedDomain, "t-2"));

        // Out-of-band tamper after chaining
        DesignDecision row = repository.findById(victim.getId()).orElseThrow();
        row.setDescription("tampered after the fact");
        repository.save(row);

        DecisionChainStartupVerifier verifier =
                new DecisionChainStartupVerifier(newService(), repository, true);
        verifier.onApplicationReady(); // boot path

        Map<UUID, HashChainVerification> results = verifier.lastResults();
        assertThat(results.get(healthyDomain).valid()).isTrue();
        assertThat(results.get(tamperedDomain).valid()).isFalse();
        assertThat(results.get(tamperedDomain).firstBrokenAt())
                .isEqualTo(victim.getId().toString());
    }

    @Test
    void startupVerifier_disabledByProperty_doesNothingOnBoot() {
        UUID domainId = UUID.randomUUID();
        service.appendDecision(decision(domainId, "present"));

        DecisionChainStartupVerifier verifier =
                new DecisionChainStartupVerifier(newService(), repository, false);
        verifier.onApplicationReady();

        assertThat(verifier.lastResults()).isEmpty();
    }

    // ------------------------------------------------------------------------
    // Tamper evidence through the persistence layer
    // ------------------------------------------------------------------------

    @Test
    void outOfBandRowUpdate_breaksVerification_withRowIdReported() {
        UUID domainId = UUID.randomUUID();
        DesignDecision first = service.appendDecision(decision(domainId, "first"));
        service.appendDecision(decision(domainId, "second"));

        DesignDecision row = repository.findById(first.getId()).orElseThrow();
        row.setDescription("silently edited in the database");
        repository.save(row);

        HashChainVerification verification = service.verifyChain(domainId);
        assertThat(verification.valid()).isFalse();
        assertThat(verification.firstBrokenAt()).isEqualTo(first.getId().toString());
        assertThat(verification.verifiedLinks()).isZero();
        assertThat(verification.message()).contains("Hash mismatch");
    }
}
