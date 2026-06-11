package com.meridian.substrate.app.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.app.cascade.CascadeApiTestSupport;
import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeLevelAction;
import com.meridian.substrate.common.domain.enums.CascadeActionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Engine-spec cascade.md §12 test plan <b>#5 — concurrency (Testcontainers)</b>:
 * two threads racing the SAME cascade through the production §6.2 endpoints
 * over real Postgres 16, where {@code PESSIMISTIC_WRITE} is a real
 * {@code SELECT … FOR UPDATE} (H2 PG-mode honors the lock but only Postgres is
 * the acceptance gate, port-map §4.4).
 *
 * <ul>
 *   <li>Simultaneous L2 decline + L2 cascade → exactly one action row wins;
 *       the loser 409s ({@code CASCADE_STALE_STATE} via the fencing token,
 *       evaluated under the lock AFTER the winner committed) with the current
 *       {@code CascadeDetail} in the envelope {@code data}. The action log
 *       stays gapless (I10) — lock serialization verified.</li>
 *   <li>Same-key double-submit race → exactly one action row; BOTH calls
 *       return 200 (the loser blocks on the lock, then replays the original
 *       result idempotently — ADR D8). A racing decline is never an error.</li>
 * </ul>
 *
 * <p>Deliberately NOT {@code @Transactional}: each HTTP command must commit
 * its own transaction for the race to be real (the
 * {@code BrandingActivateConcurrencyTest} latch idiom).
 *
 * <p><b>Docker-less machines (e.g. the Windows dev box):</b> self-disables via
 * {@code @Testcontainers(disabledWithoutDocker = true)}; CI (ubuntu-latest)
 * runs the real gate. H2-green is never acceptance (port-map §4.4).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class PostgresCascadeConcurrencyIT extends CascadeApiTestSupport {

    /** A few passes so a genuinely stochastic interleaving has room to surface. */
    private static final int ITERATIONS = 3;

    private static final String L3_OWNER = "Curasync Compliance Lead";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;

    @Test
    void simultaneousDeclineAndCascadeAtTheFrontier_exactlyOneWins_loserGets409WithCurrentDetail()
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ITERATIONS; i++) {
                // issue → L1 cascade ⇒ frontier L2 LOOKING, chain_seq 2.
                UUID domainId = createDomain("cascade-race-" + i);
                Cascade c = issue(openDraft(domainId, null, null), null, L1_OWNER, null);
                cascadeLevel(c.getId(), 1, L1_OWNER, L2_OWNER, null);

                String declineBody = json.writeValueAsString(Map.of(
                        "idempotencyKey", key(),
                        "expectedChainSeq", 2,
                        "declineReasoning", "Documented pushback — racing the cascade verb"));
                String cascadeBody = json.writeValueAsString(Map.of(
                        "idempotencyKey", key(),
                        "expectedChainSeq", 2,
                        "refinedCritique", "Refined deeper — racing the decline verb",
                        "nextOwnerLabel", L3_OWNER));

                CountDownLatch startGate = new CountDownLatch(1);
                CompletableFuture<MvcResult> decline = CompletableFuture.supplyAsync(
                        () -> postCommand(startGate, c.getId(), "decline", declineBody), pool);
                CompletableFuture<MvcResult> cascade = CompletableFuture.supplyAsync(
                        () -> postCommand(startGate, c.getId(), "cascade", cascadeBody), pool);
                startGate.countDown();
                CompletableFuture.allOf(decline, cascade).get(30, TimeUnit.SECONDS);

                int declineStatus = decline.get().getResponse().getStatus();
                int cascadeStatus = cascade.get().getResponse().getStatus();
                assertThat(List.of(declineStatus, cascadeStatus))
                        .as("iteration %d: exactly one verb wins (200) and the loser conflicts"
                                + " (409) — never both, never neither", i)
                        .containsExactlyInAnyOrder(200, 409);

                // Loser: 409 CASCADE_STALE_STATE with the CURRENT detail in data
                // (§6.2 — the client refreshes; a racing decline is never an error).
                MvcResult loser = declineStatus == 409 ? decline.get() : cascade.get();
                JsonNode loserEnvelope = json.readTree(loser.getResponse().getContentAsByteArray());
                assertThat(loserEnvelope.path("errors").path(0).path("code").asText())
                        .as("iteration %d: loser surfaces the fencing conflict code", i)
                        .isEqualTo("CASCADE_STALE_STATE");
                assertThat(loserEnvelope.path("data").path("id").asText())
                        .as("iteration %d: 409 envelope data carries the current CascadeDetail", i)
                        .isEqualTo(c.getId().toString());
                assertThat(loserEnvelope.path("data").path("chainSeq").asInt())
                        .as("iteration %d: current detail reflects the winner's commit", i)
                        .isEqualTo(3);

                // Lock serialization: exactly one new action row, log gapless (I10).
                List<CascadeLevelAction> actions =
                        actionRepository.findByCascadeIdOrderByChainSeqAsc(c.getId());
                assertThat(actions)
                        .as("iteration %d: exactly one racing action row won", i)
                        .hasSize(3);
                assertThat(actions).extracting(CascadeLevelAction::getChainSeq)
                        .as("iteration %d: chain_seq stays gapless under the race", i)
                        .containsExactly(1, 2, 3);
                assertThat(actions.get(2).getAction())
                        .as("iteration %d: the winning third action matches the 200 response", i)
                        .isEqualTo(declineStatus == 200
                                ? CascadeActionType.DECLINE
                                : CascadeActionType.CASCADE);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sameKeyDoubleSubmitRace_appendsOneRowAndReplaysTheOriginal200() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ITERATIONS; i++) {
                UUID domainId = createDomain("double-submit-" + i);
                Cascade c = issue(openDraft(domainId, null, null), null, L1_OWNER, null);
                cascadeLevel(c.getId(), 1, L1_OWNER, L2_OWNER, null);

                // SAME idempotency key on both submissions (client retry race).
                String body = json.writeValueAsString(Map.of(
                        "idempotencyKey", key(),
                        "declineReasoning", "Documented pushback — double-submitted"));

                CountDownLatch startGate = new CountDownLatch(1);
                CompletableFuture<MvcResult> first = CompletableFuture.supplyAsync(
                        () -> postCommand(startGate, c.getId(), "decline", body), pool);
                CompletableFuture<MvcResult> second = CompletableFuture.supplyAsync(
                        () -> postCommand(startGate, c.getId(), "decline", body), pool);
                startGate.countDown();
                CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);

                for (CompletableFuture<MvcResult> f : List.of(first, second)) {
                    MvcResult r = f.get();
                    assertThat(r.getResponse().getStatus())
                            .as("iteration %d: both same-key submissions return 200 —"
                                    + " one applied, one replayed (ADR D8)", i)
                            .isEqualTo(200);
                    JsonNode envelope = json.readTree(r.getResponse().getContentAsByteArray());
                    assertThat(envelope.path("errors"))
                            .as("iteration %d: decline race never surfaces an error envelope", i)
                            .isEmpty();
                    assertThat(envelope.path("data").path("chainSeq").asInt())
                            .as("iteration %d: both responses project the same applied state", i)
                            .isEqualTo(3);
                    assertThat(envelope.path("data").path("attentionSignal").asText())
                            .isEqualTo("decline-pending");
                }

                // Exactly ONE decline row exists — the uq_cla_idem backstop never
                // even had to fire; the lock + replay check resolved the race.
                List<CascadeLevelAction> actions =
                        actionRepository.findByCascadeIdOrderByChainSeqAsc(c.getId());
                assertThat(actions)
                        .as("iteration %d: same-key double submit appends exactly one row", i)
                        .hasSize(3);
                assertThat(actions.get(2).getAction()).isEqualTo(CascadeActionType.DECLINE);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** One §6.2 level command through the production endpoint, gated on the latch. */
    private MvcResult postCommand(CountDownLatch startGate, UUID cascadeId, String verb,
                                  String body) {
        try {
            startGate.await();
            return mvc.perform(post("/api/v1/cascades/{id}/levels/{depth}/" + verb, cascadeId, 2)
                            .header("X-Persona-Label", L2_OWNER)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn();
        } catch (Exception e) {
            throw new IllegalStateException("racing " + verb + " submission did not complete", e);
        }
    }
}
