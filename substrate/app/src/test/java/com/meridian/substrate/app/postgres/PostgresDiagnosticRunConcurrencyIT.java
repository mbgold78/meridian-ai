package com.meridian.substrate.app.postgres;

import com.meridian.substrate.common.ai.CompletionChunk;
import com.meridian.substrate.common.ai.CompletionRequest;
import com.meridian.substrate.common.ai.CompletionResponse;
import com.meridian.substrate.common.ai.ContentBlock;
import com.meridian.substrate.common.ai.LlmClient;
import com.meridian.substrate.common.ai.TokenUsage;
import com.meridian.substrate.common.ai.ToolCallRequest;
import com.meridian.substrate.common.ai.ToolCallResponse;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.SynthesisRun;
import com.meridian.substrate.common.repo.DiagnosticRunBatchRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.common.repo.SynthesisRunRepository;
import com.meridian.substrate.engine.diagnostic.DiagnosticRunService;
import com.meridian.substrate.engine.diagnostic.exception.DiagnosticConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Diagnostic test plan #10 — one-active-run concurrency over real Postgres 16
 * (engine-spec diagnostic.md §3.2/§4.2/§14; ADR-004 D11):
 *
 * <ul>
 *   <li>racing {@code startRun}s on one domain → the domain-scoped service
 *       lock serializes; exactly ONE run is created, the loser gets 409
 *       {@code DIAGNOSTIC_RUN_ACTIVE} (a latch-blocked LLM keeps the winner
 *       genuinely RUNNING through the race window);</li>
 *   <li>the V7_1 partial-unique backstop
 *       ({@code uq_active_diagnostic_run_per_domain}) fires for a second
 *       PLANNED/RUNNING diagnostic row inserted BEHIND the service (raw
 *       JDBC), and ONLY then — terminal rows and other run types
 *       coexist.</li>
 * </ul>
 *
 * <p>Deliberately NOT {@code @Transactional}: the race must commit to be
 * real. Self-disables without Docker ({@code disabledWithoutDocker=true});
 * compiles locally on the Docker-less dev machine, RUNS in CI — claiming
 * local verification of the PG-only backstop would be a false-PASS.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "substrate.llm.provider=mock")
@Import(PostgresDiagnosticRunConcurrencyIT.BlockingLlmConfig.class)
class PostgresDiagnosticRunConcurrencyIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    /** Armed per racing test: generation blocks here so the winner stays RUNNING. */
    static volatile CountDownLatch generationGate = new CountDownLatch(0);

    @Autowired DiagnosticRunService runService;
    @Autowired DomainRepository domainRepository;
    @Autowired SynthesisRunRepository runRepository;
    @Autowired DiagnosticRunBatchRepository batchRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------- racing starts

    @Test
    void racingStarts_exactlyOneRunWins_loserGets409() throws Exception {
        UUID domainId = domain("diag-race");
        generationGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger wins = new AtomicInteger();
            AtomicInteger conflicts = new AtomicInteger();

            List<CompletableFuture<Void>> racers = List.of(start(pool, ready, go, domainId, wins, conflicts),
                    start(pool, ready, go, domainId, wins, conflicts));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (CompletableFuture<Void> racer : racers) {
                racer.get(30, TimeUnit.SECONDS);
            }

            assertThat(wins.get()).as("exactly one start wins").isEqualTo(1);
            assertThat(conflicts.get()).as("the loser conflicts (DIAGNOSTIC_RUN_ACTIVE)").isEqualTo(1);
            assertThat(runRepository.findByDomainIdAndRunTypeAndStatusIn(
                    domainId, "diagnostic", List.of("PLANNED", "RUNNING")))
                    .as("one active run row").hasSize(1);
        } finally {
            generationGate.countDown();
            pool.shutdownNow();
        }
        awaitTerminal(domainId);
    }

    private CompletableFuture<Void> start(ExecutorService pool, CountDownLatch ready,
                                          CountDownLatch go, UUID domainId,
                                          AtomicInteger wins, AtomicInteger conflicts) {
        return CompletableFuture.runAsync(() -> {
            ready.countDown();
            try {
                go.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                runService.startRun(new DiagnosticRunService.StartCommand(
                        domainId, null, null, null, 0, null, false,
                        "race-" + UUID.randomUUID(), "it", null));
                wins.incrementAndGet();
            } catch (DiagnosticConflictException expected) {
                conflicts.incrementAndGet();
            }
        }, pool);
    }

    // --------------------------------------------- V7_1 partial-unique backstop

    @Test
    void partialUniqueBackstop_blocksSecondActiveRow_terminalRowsCoexist() {
        UUID domainId = domain("diag-backstop");

        insertRun(domainId, "diagnostic", "COMPLETED"); // terminal — coexists
        insertRun(domainId, "eval", "RUNNING");          // other run type — coexists
        insertRun(domainId, "diagnostic", "PLANNED");    // the one active run

        assertThatThrownBy(() -> insertRun(domainId, "diagnostic", "RUNNING"))
                .as("uq_active_diagnostic_run_per_domain fires for the second active row")
                .isInstanceOf(DataAccessException.class);
    }

    // --------------------------------------------------------------- helpers

    private UUID domain(String prefix) {
        Domain domain = new Domain();
        domain.setName(prefix + " " + UUID.randomUUID());
        domain.setCreatedBy("it");
        return domainRepository.save(domain).getId();
    }

    private void insertRun(UUID domainId, String runType, String status) {
        jdbcTemplate.update("""
                INSERT INTO synthesis_runs
                    (id, version, created_at, updated_at, created_by, domain_id, run_type, status)
                VALUES (?, 0, NOW(), NOW(), 'it', ?, ?, ?)""",
                UUID.randomUUID(), domainId, runType, status);
    }

    private void awaitTerminal(UUID domainId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            boolean activeLeft = !runRepository.findByDomainIdAndRunTypeAndStatusIn(
                    domainId, "diagnostic", List.of("PLANNED", "RUNNING")).isEmpty();
            if (!activeLeft) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("diagnostic run did not reach a terminal status within 60s");
    }

    // ----------------------------------------------------- latch-blocked LLM

    @TestConfiguration
    static class BlockingLlmConfig {
        @Bean
        @Primary
        LlmClient blockingLlmClient() {
            return new LlmClient() {
                @Override
                public CompletionResponse complete(CompletionRequest request) {
                    return new CompletionResponse("it", "prose", "it-model", "end_turn",
                            new TokenUsage(1, 1));
                }

                @Override
                public Flux<CompletionChunk> completeStream(CompletionRequest request) {
                    return Flux.empty();
                }

                @Override
                public ToolCallResponse completeWithTools(ToolCallRequest request) {
                    try {
                        generationGate.await(60, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return new ToolCallResponse("it", "it-model", "end_turn",
                            List.of(ContentBlock.text("no structured findings")),
                            new TokenUsage(1, 1));
                }
            };
        }
    }
}
