package com.meridian.substrate.app.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.app.agent.AgentVerbTestSupport;
import com.meridian.substrate.common.domain.AgentIntervention;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.AgentRateCounter;
import com.meridian.substrate.common.domain.enums.AgentEventType;
import com.meridian.substrate.common.domain.enums.AgentInterventionStatus;
import com.meridian.substrate.common.domain.enums.AgentInterventionTrigger;
import com.meridian.substrate.common.repo.AgentInterventionRepository;
import com.meridian.substrate.common.repo.AgentRateCounterRepository;
import com.meridian.substrate.common.repo.AgentStatusEventRepository;
import com.meridian.substrate.engine.agent.AgentActor;
import com.meridian.substrate.engine.agent.AgentInterventionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Engine-spec agents.md §16.2 test plan <b>#5 — concurrency (Testcontainers)</b>
 * over real Postgres 16, where {@code PESSIMISTIC_WRITE} is a real
 * {@code SELECT … FOR UPDATE} and the V6_1 partial uniques exist:
 *
 * <ul>
 *   <li>Simultaneous pause + kill (both fenced at {@code expectedEventSeq=1})
 *       → the lock serializes; exactly one wins, the loser 409s
 *       {@code AGENT_STALE_STATE}; the event log stays gapless.</li>
 *   <li>Concurrent promote against ONE APPROVED review → exactly one PROMOTED
 *       event; the review is consumed once; the loser 409s.</li>
 *   <li>Concurrent engine-raised interventions on the same {@code source_key}
 *       → one OPEN row (I12; {@code uq_open_intervention_per_source}
 *       backstop).</li>
 *   <li>Concurrent rate-counter increments lose no counts (D12 — the S12
 *       lesson: DB atomics, never in-memory).</li>
 * </ul>
 *
 * <p>Deliberately NOT {@code @Transactional}: each command must commit for
 * the races to be real. <b>Docker-less machines (the Windows dev box):</b>
 * self-disables via {@code @Testcontainers(disabledWithoutDocker = true)};
 * CI (ubuntu-latest) runs the real gate — H2-green is never acceptance.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class PostgresAgentConcurrencyIT extends AgentVerbTestSupport {

    private static final int ITERATIONS = 3;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    AgentStatusEventRepository events;
    @Autowired
    AgentInterventionRepository interventions;
    @Autowired
    AgentInterventionService interventionService;
    @Autowired
    AgentRateCounterRepository rateCounters;
    @Autowired
    PlatformTransactionManager txManager;

    @Test
    void simultaneousPauseAndKill_lockSerializes_exactlyOneWinsOnTheFence() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ITERATIONS; i++) {
                AgentRegistration reg = deploy(UUID.randomUUID(), "a-race-" + i);
                var pauseBody = reasonedBody("Paused pending review");
                pauseBody.put("expectedEventSeq", 1);
                var killBody = reasonedBody("Emergency stop");
                killBody.put("expectedEventSeq", 1);

                CountDownLatch gate = new CountDownLatch(1);
                CompletableFuture<MvcResult> pause = CompletableFuture.supplyAsync(
                        () -> postRacing(gate, reg.getId(), "pause", pauseBody.toString()), pool);
                CompletableFuture<MvcResult> kill = CompletableFuture.supplyAsync(
                        () -> postRacing(gate, reg.getId(), "kill", killBody.toString()), pool);
                gate.countDown();
                CompletableFuture.allOf(pause, kill).get(30, TimeUnit.SECONDS);

                int pauseStatus = pause.get().getResponse().getStatus();
                int killStatus = kill.get().getResponse().getStatus();
                assertThat(List.of(pauseStatus, killStatus))
                        .as("iteration %d: one wins, the fenced loser conflicts", i)
                        .containsExactlyInAnyOrder(200, 409);
                MvcResult loser = pauseStatus == 409 ? pause.get() : kill.get();
                JsonNode envelope = json.readTree(loser.getResponse().getContentAsByteArray());
                assertThat(envelope.path("errors").path(0).path("code").asText())
                        .isEqualTo("AGENT_STALE_STATE");
                // gapless log: exactly two events (DEPLOYED + the winner)
                assertThat(events.countByAgentRegistrationId(reg.getId())).isEqualTo(2);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentPromoteConsumesTheSingleApprovedReviewExactlyOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ITERATIONS; i++) {
                AgentRegistration reg = deploy(UUID.randomUUID(), "a-promo-" + i);
                approvePromotionReview("a-promo-" + i);

                CountDownLatch gate = new CountDownLatch(1);
                CompletableFuture<MvcResult> a = CompletableFuture.supplyAsync(
                        () -> postRacing(gate, reg.getId(), "promote", verbBody().toString()), pool);
                CompletableFuture<MvcResult> b = CompletableFuture.supplyAsync(
                        () -> postRacing(gate, reg.getId(), "promote", verbBody().toString()), pool);
                gate.countDown();
                CompletableFuture.allOf(a, b).get(30, TimeUnit.SECONDS);

                assertThat(List.of(a.get().getResponse().getStatus(),
                        b.get().getResponse().getStatus()))
                        .as("iteration %d: exactly one PROMOTED", i)
                        .containsExactlyInAnyOrder(200, 409);
                long promoted = events.findByAgentRegistrationIdOrderByEventSeqAsc(reg.getId())
                        .stream().filter(e -> e.getEventType() == AgentEventType.PROMOTED).count();
                assertThat(promoted).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentEngineRaisedInterventionsDedupeOnSourceKey() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-zombie");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            Runnable raise = () -> {
                await(gate);
                interventionService.open(reg.getId(), "z:" + UUID.randomUUID(),
                        AgentActor.human(null, OPERATOR),
                        "Heartbeat observed while paused", null,
                        AgentInterventionTrigger.GATEWAY_VIOLATION, "active-while-paused");
            };
            CompletableFuture<Void> a = CompletableFuture.runAsync(raise, pool);
            CompletableFuture<Void> b = CompletableFuture.runAsync(raise, pool);
            gate.countDown();
            CompletableFuture.allOf(a, b).get(30, TimeUnit.SECONDS);

            List<AgentIntervention> open = interventions
                    .findByAgentRegistrationIdAndStatus(reg.getId(), AgentInterventionStatus.OPEN);
            assertThat(open).hasSize(1);                     // I12 — never a second OPEN row
            assertThat(registrations.findById(reg.getId()).orElseThrow()
                    .getOpenInterventionCount()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentRateCounterIncrementsLoseNoCounts() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-counter");
        Instant window = Instant.parse("2026-06-11T12:00:00Z");
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> {
            AgentRateCounter counter = new AgentRateCounter();
            counter.setAgentRegistrationId(reg.getId());
            counter.setWindowStart(window);
            counter.setActionCount(0);
            rateCounters.save(counter);
        });

        int threads = 8;
        int perThread = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            CompletableFuture<?>[] tasks = new CompletableFuture[threads];
            for (int t = 0; t < threads; t++) {
                tasks[t] = CompletableFuture.runAsync(() -> {
                    await(gate);
                    for (int n = 0; n < perThread; n++) {
                        tx.executeWithoutResult(s ->
                                rateCounters.incrementWindow(reg.getId(), window));
                    }
                }, pool);
            }
            gate.countDown();
            CompletableFuture.allOf(tasks).get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        AgentRateCounter counter = rateCounters
                .findByAgentRegistrationIdAndWindowStart(reg.getId(), window).orElseThrow();
        assertThat(counter.getActionCount()).isEqualTo(threads * perThread);
    }

    // ------------------------------------------------------------------ harness

    private MvcResult postRacing(CountDownLatch gate, UUID registrationId, String verb,
                                 String body) {
        await(gate);
        try {
            return mvc.perform(post("/api/v1/agents/" + registrationId + "/" + verb)
                            .header("X-Persona-Label", OPERATOR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch gate) {
        try {
            gate.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
