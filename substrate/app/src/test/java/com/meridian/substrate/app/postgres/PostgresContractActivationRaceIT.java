package com.meridian.substrate.app.postgres;

import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.DesignDecision;
import com.meridian.substrate.common.domain.enums.ContractPartyType;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.domain.enums.DecisionType;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.MonitorStatus;
import com.meridian.substrate.common.domain.enums.PartyResponse;
import com.meridian.substrate.common.repo.ContractEventRepository;
import com.meridian.substrate.common.repo.ContractMonitorRepository;
import com.meridian.substrate.common.repo.ContractPartyRepository;
import com.meridian.substrate.common.repo.ContractPartyResponseRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.common.repo.DesignDecisionRepository;
import com.meridian.substrate.engine.contract.registry.ContractActor;
import com.meridian.substrate.engine.contract.registry.ContractEventService;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService;
import com.meridian.substrate.engine.decision.log.DecisionLogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

/**
 * Engine-spec contracts.md §14.4 activation race on real Postgres 16:
 * <b>two concurrent final-party accepts ⇒ exactly ONE ACTIVE transition, one
 * activation event, one hash anchor</b> (§10.1: the last-required-acceptance
 * check runs under the {@code findByIdForUpdate} PESSIMISTIC_WRITE lock, so
 * concurrent accepts serialize and cannot double-activate).
 *
 * <p>Two lanes:
 * <ul>
 *   <li><b>Distinct final parties</b> — the two remaining required parties
 *       accept simultaneously; whichever commits second observes the complete
 *       acceptance set and performs the single activation.</li>
 *   <li><b>Same party duplicate accept</b> — one response row
 *       ({@code UNIQUE(party_id)} + §10.2 idempotent re-read under the lock),
 *       one activation.</li>
 * </ul>
 *
 * <p>The hash anchor is the REAL ADR D14 mirror (default
 * {@code ContractEngineConfig} bean): the activation anchor lands once,
 * after commit, and the domain chain verifies — proving the §14.4
 * "concurrent contract-event + design-decision appends do not fork" posture
 * for the activation lane on real Postgres.
 *
 * <p>Deliberately NOT {@code @Transactional}: each contender commits its own
 * transaction through the {@code @Transactional} service proxy so the race
 * and the row lock are real (cascade-concurrency latch idiom).
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
class PostgresContractActivationRaceIT {

    /** A few passes so a genuinely stochastic interleaving has room to surface. */
    private static final int ITERATIONS = 3;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private ContractRegistryService registryService;
    @Autowired private ContractRepository contracts;
    @Autowired private ContractPartyRepository parties;
    @Autowired private ContractPartyResponseRepository responses;
    @Autowired private ContractMonitorRepository monitors;
    @Autowired private ContractEventRepository events;
    @Autowired private DesignDecisionRepository designDecisions;
    @Autowired private DecisionLogService decisionLogService;

    @Test
    void twoConcurrentFinalPartyAccepts_exactlyOneActivation_oneEvent_oneAnchor() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ITERATIONS; i++) {
                Graph g = graph("act-race-" + i);
                // First of three required parties accepts ahead of the race.
                registryService.respond(g.contractId, accept(g.partyIds.get(0), "first@party"));

                CountDownLatch startGate = new CountDownLatch(1);
                CompletableFuture<Contract> a = respondAsync(pool, startGate, g.contractId,
                        accept(g.partyIds.get(1), "second@party"));
                CompletableFuture<Contract> b = respondAsync(pool, startGate, g.contractId,
                        accept(g.partyIds.get(2), "third@party"));
                startGate.countDown();
                CompletableFuture.allOf(a, b).get(30, TimeUnit.SECONDS);

                assertOneActivation(g, i);
                assertThat(responses.findByContractId(g.contractId)).hasSize(3);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void duplicateFinalPartyAccepts_oneResponseRow_oneActivation() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ITERATIONS; i++) {
                Graph g = graph("act-dup-" + i);
                registryService.respond(g.contractId, accept(g.partyIds.get(0), "first@party"));
                registryService.respond(g.contractId, accept(g.partyIds.get(1), "second@party"));

                UUID finalParty = g.partyIds.get(2);
                CountDownLatch startGate = new CountDownLatch(1);
                CompletableFuture<Contract> a = respondAsync(pool, startGate, g.contractId,
                        accept(finalParty, "third@party"));
                CompletableFuture<Contract> b = respondAsync(pool, startGate, g.contractId,
                        accept(finalParty, "third@party"));
                startGate.countDown();
                CompletableFuture.allOf(a, b).get(30, TimeUnit.SECONDS);

                assertOneActivation(g, i);
                // §10.2: UNIQUE(party_id) — duplicate identical accept is an
                // idempotent no-op, never a second row.
                assertThat(responses.findByPartyId(finalParty)).isPresent();
                assertThat(responses.findByContractId(g.contractId)).hasSize(3);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ support

    private void assertOneActivation(Graph g, int iteration) {
        Contract after = contracts.findById(g.contractId).orElseThrow();
        assertThat(after.getStatus())
                .as("iteration %d: exactly one ACTIVE transition (§10.1)", iteration)
                .isEqualTo(ContractStatus.ACTIVE);
        assertThat(after.getActivatedAt()).isNotNull();

        assertThat(monitors.findByContractId(g.contractId))
                .allSatisfy(m -> assertThat(m.getStatus()).isEqualTo(MonitorStatus.ACTIVE));

        long activatedEvents = events.findByContractIdOrderByOccurredAtAsc(g.contractId).stream()
                .filter(e -> ContractEventService.CONTRACT_ACTIVATED.equals(e.getEventType()))
                .count();
        assertThat(activatedEvents)
                .as("iteration %d: one CONTRACT_ACTIVATED event (§14.4)", iteration)
                .isEqualTo(1);

        List<DesignDecision> anchors = designDecisions.findByDomainId(g.domainId).stream()
                .filter(d -> d.getDecisionType() == DecisionType.CONTRACT_ACCOUNTABILITY)
                .toList();
        assertThat(anchors)
                .as("iteration %d: one ADR D14 hash anchor (§14.4)", iteration)
                .hasSize(1);
        assertThat(anchors.get(0).getTitle())
                .isEqualTo("contract:" + g.contractKey + ":CONTRACT_ACTIVATED");
        assertThat(decisionLogService.verifyChain(g.domainId).valid())
                .as("iteration %d: chain does not fork under the race", iteration)
                .isTrue();
    }

    private CompletableFuture<Contract> respondAsync(ExecutorService pool, CountDownLatch startGate,
                                                     UUID contractId,
                                                     ContractRegistryService.RespondCommand cmd) {
        return CompletableFuture.supplyAsync(() -> {
            await(startGate);
            return registryService.respond(contractId, cmd);
        }, pool);
    }

    private static ContractRegistryService.RespondCommand accept(UUID partyId, String by) {
        return new ContractRegistryService.RespondCommand(
                partyId, PartyResponse.ACCEPTED, null, null, by, ContractActor.human(by));
    }

    private record Graph(UUID contractId, UUID domainId, String contractKey, List<UUID> partyIds) {
    }

    /** Three required parties, one commitment each, one monitor — via the real create path. */
    private Graph graph(String key) {
        UUID domainId = UUID.randomUUID();
        Contract contract = registryService.create(new ContractRegistryService.CreateContractCommand(
                domainId, key, "cross-portfolio-opportunity",
                "Activation race IT " + key, null, null, null, null, null, null,
                Instant.now(), "race-it",
                List.of(
                        party("Party A"), party("Party B"), party("Party C")),
                List.of(),
                List.of(new ContractRegistryService.MonitorCommand(
                        "race-monitor-" + key, MonitorRuleType.UNSTRUCTURED, null,
                        "MONTHLY", 0, null, null, null, null)),
                List.of(),
                List.of()));
        List<UUID> partyIds = parties.findByContractIdOrderBySequenceOrderAsc(contract.getId())
                .stream().map(ContractParty::getId).toList();
        return new Graph(contract.getId(), domainId, key, partyIds);
    }

    private static ContractRegistryService.PartyCommand party(String label) {
        return new ContractRegistryService.PartyCommand(label, ContractPartyType.ORG_UNIT,
                null, true, List.of(new ContractRegistryService.CommitmentCommand(
                        "Commit to the race", null, null)));
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
