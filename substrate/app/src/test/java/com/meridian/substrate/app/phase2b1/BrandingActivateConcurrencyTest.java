package com.meridian.substrate.app.phase2b1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.app.branding.BrandingConfigUpsertRequest;
import com.meridian.substrate.common.domain.BrandingConfig;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.domain.enums.BrandingConfigStatus;
import com.meridian.substrate.common.repo.BrandingConfigRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Regression guard for the single-ACTIVE invariant under concurrent activation
 * (ported homepoc phase2b1 suite; substrate dev profile / H2-PG-mode).
 *
 * <p><b>Seam under test.</b> Two threads call
 * {@code POST /api/v1/branding/configs/{id}/activate} in parallel on two
 * different DRAFT configs belonging to the same domain; a prior ACTIVE exists.
 * Without the {@code PESSIMISTIC_WRITE} locks in
 * {@code BrandingConfigService.demoteCurrentActive} (Domain-row outer lock +
 * ACTIVE-row inner lock), both transactions can read the same pre-demote state
 * and both commit ACTIVE updates — producing two ACTIVE rows for the domain
 * and breaking the invariant the service is supposed to enforce. With the
 * locks, one transaction blocks until the other commits, re-evaluates, and
 * produces exactly one ACTIVE row.
 *
 * <p><b>How to prove the test is a real regression guard:</b> drop
 * {@code @Lock(LockModeType.PESSIMISTIC_WRITE)} from
 * {@code findFirstByDomainIdAndStatusForUpdate} (or revert
 * {@code demoteCurrentActive} to call the unlocked
 * {@code findFirstByDomainIdAndStatus}). This test should then fail with
 * {@code activeCount > 1} on at least one iteration.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class BrandingActivateConcurrencyTest {

    /**
     * A handful of iterations so a genuinely stochastic race has room to
     * surface. One pass is enough when the fix is correct; multiple passes
     * give the regression case (without the lock) time to actually hit the
     * interleaving we care about.
     */
    private static final int ITERATIONS = 6;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DomainRepository domainRepository;
    @Autowired BrandingConfigRepository brandingRepository;

    @Test
    void concurrentActivateOnSameDomain_producesExactlyOneActive() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ITERATIONS; i++) {
                UUID domainId = createDomain("activate-race-" + i);

                // Pre-existing ACTIVE; two DRAFT candidates in the same domain.
                UUID initialActive = createConfigViaApi(domainId, BrandingConfigStatus.ACTIVE).getId();
                UUID candidateA   = createConfigViaApi(domainId, BrandingConfigStatus.DRAFT).getId();
                UUID candidateB   = createConfigViaApi(domainId, BrandingConfigStatus.DRAFT).getId();

                CountDownLatch startGate = new CountDownLatch(1);
                AtomicInteger successes = new AtomicInteger();

                CompletableFuture<Void> f1 = CompletableFuture.runAsync(
                        () -> activateAt(startGate, candidateA, successes), pool);
                CompletableFuture<Void> f2 = CompletableFuture.runAsync(
                        () -> activateAt(startGate, candidateB, successes), pool);

                // Release both threads simultaneously and wait for completion.
                startGate.countDown();
                CompletableFuture.allOf(f1, f2).get(10, TimeUnit.SECONDS);

                // Core invariant: exactly one ACTIVE row for this domain.
                List<BrandingConfig> all = brandingRepository.findByDomainId(domainId);
                long activeCount = all.stream()
                        .filter(c -> c.getStatus() == BrandingConfigStatus.ACTIVE)
                        .count();
                assertThat(activeCount)
                        .as("iteration %d: exactly one ACTIVE config must exist "
                                + "after two concurrent activate() calls on the same domain "
                                + "(initialActive=%s, candidates=[%s, %s])",
                                i, initialActive, candidateA, candidateB)
                        .isEqualTo(1);

                // The pre-existing ACTIVE must have been demoted to DRAFT (at least
                // one of the candidates won the race and did its demote step).
                BrandingConfig initial = brandingRepository.findById(initialActive).orElseThrow();
                assertThat(initial.getStatus())
                        .as("iteration %d: the pre-existing ACTIVE config must have been "
                                + "demoted by whichever candidate won the race", i)
                        .isEqualTo(BrandingConfigStatus.DRAFT);

                // Both HTTP calls should have succeeded (neither threw/was 5xx):
                // they serialize behind the pessimistic lock, they don't fail.
                assertThat(successes.get())
                        .as("iteration %d: both activate() calls must return 2xx under "
                                + "pessimistic locking (serialized, not failed)", i)
                        .isEqualTo(2);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void activateAt(CountDownLatch gate, UUID configId, AtomicInteger successes) {
        try {
            gate.await();
            MvcResult r = mvc.perform(post("/api/v1/branding/configs/{id}/activate", configId)
                            .header("X-Persona-Level", "ADMIN"))
                    .andReturn();
            int status = r.getResponse().getStatus();
            if (status >= 200 && status < 300) {
                successes.incrementAndGet();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private BrandingConfig createConfigViaApi(UUID domainId, BrandingConfigStatus status) throws Exception {
        BrandingConfigUpsertRequest req = sampleRequest(domainId, status);
        MvcResult r = mvc.perform(post("/api/v1/branding/configs")
                        .header("X-Persona-Level", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(req)))
                .andReturn();
        UUID id = UUID.fromString(json.readTree(r.getResponse().getContentAsByteArray())
                .path("data").path("id").asText());
        return brandingRepository.findById(id).orElseThrow();
    }

    private BrandingConfigUpsertRequest sampleRequest(UUID domainId, BrandingConfigStatus status) {
        JsonNode lightTokens = json.createObjectNode().put("accent", "#112233").put("background", "#ffffff");
        JsonNode darkTokens  = json.createObjectNode().put("accent", "#aabbcc").put("background", "#000000");
        JsonNode terminology = json.createObjectNode()
                .set("capability", json.createObjectNode().put("singular", "Capability").put("plural", "Capabilities"));
        return new BrandingConfigUpsertRequest(
                domainId,
                "Race Test Workspace",
                null,
                lightTokens, darkTokens,
                "Inter, sans-serif", "Inter, sans-serif", "JetBrains Mono, monospace",
                null, null, null,
                null, null, null, null, null, null,
                terminology,
                null, null, null, null, null, null,
                null, null, null,
                null,
                status
        );
    }

    private UUID createDomain(String label) {
        Domain d = new Domain();
        d.setName("Phase2B1 " + label + " " + UUID.randomUUID());
        d.setCreatedBy("test");
        return domainRepository.save(d).getId();
    }
}
