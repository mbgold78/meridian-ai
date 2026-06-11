package com.meridian.substrate.engine.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractClauseGuard;
import com.meridian.substrate.common.domain.ContractDeclineTrigger;
import com.meridian.substrate.common.domain.ContractEvent;
import com.meridian.substrate.common.domain.ContractMonitor;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.enums.ContractPartyType;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.domain.enums.GuardEffect;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.MonitorStatus;
import com.meridian.substrate.common.domain.enums.PartyResponse;
import com.meridian.substrate.common.repo.ContractClauseGuardRepository;
import com.meridian.substrate.common.repo.ContractCommitmentRepository;
import com.meridian.substrate.common.repo.ContractDeclineTriggerRepository;
import com.meridian.substrate.common.repo.ContractEntityLinkRepository;
import com.meridian.substrate.common.repo.ContractEventRepository;
import com.meridian.substrate.common.repo.ContractMonitorRepository;
import com.meridian.substrate.common.repo.ContractPartyRepository;
import com.meridian.substrate.common.repo.ContractPartyResponseRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.contract.events.ContractActivatedEvent;
import com.meridian.substrate.engine.contract.events.ContractDeclinedEvent;
import com.meridian.substrate.engine.contract.exception.ContractConflictException;
import com.meridian.substrate.engine.contract.registry.ContractActor;
import com.meridian.substrate.engine.contract.registry.ContractEventService;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService.CommitmentCommand;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService.CreateContractCommand;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService.DeclineTriggerCommand;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService.GuardCommand;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService.InvokeDeclineCommand;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService.MonitorCommand;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService.PartyCommand;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService.PatchContractCommand;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService.RespondCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service-level §2.2 state-machine table on the H2 fast lane (engine-spec
 * contracts.md §14.3): every legal transition with its same-transaction side
 * effects, every illegal transition with its §4.5 409 code, the ADR D7
 * content-freeze-on-FIRST-response rule ({@code CONTRACT_CONTENT_FROZEN}),
 * response idempotency/conflict (§10.2), and WITHDRAWN/CONCLUDED
 * transition-unreachability.
 *
 * <p><b>DECLINE-never-error pin (port-map trap #8):</b> both decline paths
 * are asserted to return NORMALLY — the only exceptions in this suite are
 * state/freeze/conflict guards, never decline outcomes.
 *
 * <p>Verbs run inside explicit {@link TransactionTemplate} transactions
 * (PESSIMISTIC_WRITE needs an active transaction; the services are
 * hand-constructed so {@code @Transactional} proxies are not in play —
 * CascadeLifecycleServiceTest precedent). Testcontainers-Postgres remains the
 * acceptance gate for lock semantics (H2-green is never acceptance; this
 * machine cannot run Docker).
 *
 * <p>Test content is SYNTHETIC by design — the §14.1 fixture-vector verbatim
 * pins live with the DemoPack seed suite in the app module (3a-1 precedent).
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:contract-registry;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ContractRegistryServiceStateMachineTest {

    private static final Instant T0 = Instant.parse("2026-04-01T00:00:00Z");
    private static final ContractActor OPERATOR = ContractActor.human("Operating Partner");

    @Autowired private ContractRepository contractRepository;
    @Autowired private ContractPartyRepository partyRepository;
    @Autowired private ContractPartyResponseRepository responseRepository;
    @Autowired private ContractCommitmentRepository commitmentRepository;
    @Autowired private ContractDeclineTriggerRepository triggerRepository;
    @Autowired private ContractEntityLinkRepository entityLinkRepository;
    @Autowired private ContractMonitorRepository monitorRepository;
    @Autowired private ContractClauseGuardRepository guardRepository;
    @Autowired private ContractEventRepository eventRepository;
    @Autowired private com.meridian.substrate.common.repo.ContractEnforcementDecisionRepository decisionRepository;
    @Autowired private com.meridian.substrate.common.repo.ContractBreachRepository breachRepository;
    @Autowired private PlatformTransactionManager txManager;

    private ContractRegistryService service;
    private RecordingMirror mirror;
    private List<Object> publishedAfterCommit;
    private TransactionTemplate tx;
    private int keySeq;

    static final class RecordingMirror implements ContractAccountabilityMirror {
        final List<Contract> activated = new ArrayList<>();
        final List<Contract> declined = new ArrayList<>();

        @Override
        public void onActivated(Contract contract) {
            activated.add(contract);
        }

        @Override
        public void onDeclined(Contract contract) {
            declined.add(contract);
        }

        @Override
        public void onBreachWaived(Contract contract,
                                   com.meridian.substrate.common.domain.ContractBreach breach) {
            // 3b verb — not exercised by the §2.2 contract state-machine table
        }
    }

    @BeforeEach
    void setUp() {
        mirror = new RecordingMirror();
        publishedAfterCommit = new ArrayList<>();
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ContractProperties properties = new ContractProperties();
        service = new ContractRegistryService(contractRepository, partyRepository,
                responseRepository, commitmentRepository, triggerRepository,
                entityLinkRepository, monitorRepository, guardRepository,
                new ContractEventService(eventRepository, clock), mirror,
                new com.meridian.substrate.engine.contract.registry.DeclineAdvisoryFlags(
                        decisionRepository, breachRepository, properties, clock, objectMapper),
                properties, publishedAfterCommit::add, clock, objectMapper);
        tx = new TransactionTemplate(txManager);
        keySeq = 0;
    }

    private <T> T inTx(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    // ------------------------------------------------------------------ create

    @Test
    void createSeedsProposedWithSuspendedMonitorsAndInactiveGuards() {
        Contract c = inTx(() -> service.create(twoPartyCommand()));

        assertThat(c.getStatus()).isEqualTo(ContractStatus.PROPOSED);
        assertThat(c.getProposedAt()).isEqualTo(T0);
        assertThat(c.getActivatedAt()).isNull();
        assertThat(c.getResolvedAt()).isNull();

        List<ContractMonitor> monitors = monitorRepository.findByContractId(c.getId());
        assertThat(monitors).hasSize(1).allSatisfy(m -> {
            assertThat(m.getStatus()).isEqualTo(MonitorStatus.SUSPENDED); // §7.2 born SUSPENDED
            assertThat(m.getNextRunAt()).isNull();
        });
        assertThat(guardRepository.findByContractId(c.getId()))
                .hasSize(1)
                .allSatisfy(g -> assertThat(g.isActive()).isFalse());      // §7.2 born inactive
        // Spec-named event vocabulary only: no proposal event exists (§2.2
        // names transition events only — flag-not-invent).
        assertThat(eventRepository.findByContractIdOrderByOccurredAtAsc(c.getId())).isEmpty();
    }

    @Test
    void createRejectsFewerThanTwoParties() {
        CreateContractCommand cmd = commandWithParties(List.of(
                party("Solo Party", true, "one commitment")));
        assertThatThrownBy(() -> inTx(() -> service.create(cmd)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2..N parties");
    }

    @Test
    void createRejectsPartyWithoutCommitments() {
        CreateContractCommand cmd = commandWithParties(List.of(
                party("Party A", true, "a commitment"),
                new PartyCommand("Party B", ContractPartyType.DOMAIN, null, true, List.of())));
        assertThatThrownBy(() -> inTx(() -> service.create(cmd)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">=1 commitment");
    }

    @Test
    void createRejectsTypeOutsideAllowlist() {
        CreateContractCommand base = twoPartyCommand();
        CreateContractCommand cmd = new CreateContractCommand(base.domainId(), base.contractKey(),
                "not-a-real-type", base.title(), base.context(), base.monitorText(),
                base.monitorMechanism(), base.estimatedValue(), base.estimatedValueAmount(),
                base.reviewBoundary(), base.proposedAt(), base.proposedBy(), base.parties(),
                base.declineTriggers(), base.monitors(), base.guards(), base.linkedEntities());
        assertThatThrownBy(() -> inTx(() -> service.create(cmd)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowlist");
    }

    @Test
    void createRejectsDuplicateKeyInDomain() {
        CreateContractCommand cmd = twoPartyCommand();
        inTx(() -> service.create(cmd));
        assertThatThrownBy(() -> inTx(() -> service.create(cmd)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    // ------------------------------------------------- PROPOSED -> ACTIVE (legal)

    @Test
    void lastRequiredAcceptanceActivatesWithAllSideEffects() {
        Contract c = inTx(() -> service.create(twoPartyCommand()));
        List<ContractParty> parties = requiredParties(c);

        inTx(() -> service.respond(c.getId(), accept(parties.get(0))));
        assertThat(contractRepository.findById(c.getId()).orElseThrow().getStatus())
                .as("partial acceptance must not activate (§2.2 — last required party)")
                .isEqualTo(ContractStatus.PROPOSED);
        assertThat(publishedAfterCommit).isEmpty();

        inTx(() -> service.respond(c.getId(), accept(parties.get(1))));

        Contract active = contractRepository.findById(c.getId()).orElseThrow();
        assertThat(active.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        assertThat(active.getActivatedAt()).isEqualTo(T0);
        assertThat(monitorRepository.findByContractId(c.getId())).allSatisfy(m -> {
            assertThat(m.getStatus()).isEqualTo(MonitorStatus.ACTIVE);
            assertThat(m.getNextRunAt()).as("next_run_at initialized (§2.2)").isNotNull();
        });
        assertThat(guardRepository.findByContractId(c.getId()))
                .allSatisfy(g -> assertThat(g.isActive()).isTrue());
        assertThat(eventTypes(c)).containsExactly(ContractEventService.CONTRACT_ACTIVATED);
        assertThat(mirror.activated).hasSize(1);                 // ADR D14 anchor seam
        assertThat(publishedAfterCommit)                          // after-commit publication (§9)
                .singleElement().isInstanceOf(ContractActivatedEvent.class);
    }

    @Test
    void nonRequiredPartyDoesNotGateActivation() {
        Contract c = inTx(() -> service.create(commandWithParties(List.of(
                party("Required A", true, "commitment a"),
                party("Required B", true, "commitment b"),
                party("Observer", false, "observer commitment")))));
        List<ContractParty> required = requiredParties(c);
        assertThat(required).hasSize(2);

        inTx(() -> service.respond(c.getId(), accept(required.get(0))));
        inTx(() -> service.respond(c.getId(), accept(required.get(1))));

        assertThat(contractRepository.findById(c.getId()).orElseThrow().getStatus())
                .isEqualTo(ContractStatus.ACTIVE);
    }

    // ----------------------------------------------- PROPOSED -> DECLINED (legal)

    @Test
    void requiredPartyDeclineResolvesDeclinedAndIsNeverAnError() {
        Contract c = inTx(() -> service.create(twoPartyCommand()));
        ContractParty decliner = requiredParties(c).get(0);

        // Port-map trap #8: a legitimate decline RETURNS NORMALLY (wire 200).
        assertThatCode(() -> inTx(() -> service.respond(c.getId(), new RespondCommand(
                decliner.getId(), PartyResponse.DECLINED, null,
                "Regulated-role bench too thin to commit", decliner.getPartyLabel(), OPERATOR))))
                .doesNotThrowAnyException();

        Contract declined = contractRepository.findById(c.getId()).orElseThrow();
        assertThat(declined.getStatus()).isEqualTo(ContractStatus.DECLINED);
        assertThat(declined.getResolvedAt()).isEqualTo(T0);
        assertThat(eventTypes(c)).containsExactly(ContractEventService.CONTRACT_DECLINED);
        assertThat(mirror.declined).hasSize(1);
        assertThat(publishedAfterCommit).singleElement()
                .isInstanceOfSatisfying(ContractDeclinedEvent.class, e ->
                        assertThat(e.fromStatus()).isEqualTo(ContractStatus.PROPOSED));
    }

    @Test
    void declineResponseRequiresReasoning() {
        Contract c = inTx(() -> service.create(twoPartyCommand()));
        ContractParty p = requiredParties(c).get(0);
        assertThatThrownBy(() -> inTx(() -> service.respond(c.getId(), new RespondCommand(
                p.getId(), PartyResponse.DECLINED, null, "  ", p.getPartyLabel(), OPERATOR))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("declineReasoning");
    }

    // ------------------------------------------------- response idempotency (§10.2)

    @Test
    void duplicateIdenticalResponseIsIdempotent() {
        Contract c = inTx(() -> service.create(twoPartyCommand()));
        ContractParty p = requiredParties(c).get(0);
        inTx(() -> service.respond(c.getId(), accept(p)));
        assertThatCode(() -> inTx(() -> service.respond(c.getId(), accept(p))))
                .doesNotThrowAnyException();
        assertThat(responseRepository.findByContractId(c.getId())).hasSize(1);
    }

    @Test
    void conflictingResponseIs409ResponseConflict() {
        Contract c = inTx(() -> service.create(twoPartyCommand()));
        ContractParty p = requiredParties(c).get(0);
        inTx(() -> service.respond(c.getId(), accept(p)));
        assertThatThrownBy(() -> inTx(() -> service.respond(c.getId(), new RespondCommand(
                p.getId(), PartyResponse.DECLINED, null, "changed my mind",
                p.getPartyLabel(), OPERATOR))))
                .isInstanceOfSatisfying(ContractConflictException.class, ex ->
                        assertThat(ex.getCode())
                                .isEqualTo(ContractConflictException.Code.RESPONSE_CONFLICT));
        assertThat(contractRepository.findById(c.getId()).orElseThrow().getStatus())
                .isEqualTo(ContractStatus.PROPOSED);
    }

    @Test
    void respondOnTerminalContractIsInvalidContractState() {
        Contract c = declinedContract();
        ContractParty other = requiredParties(c).get(1);
        assertThatThrownBy(() -> inTx(() -> service.respond(c.getId(), accept(other))))
                .isInstanceOfSatisfying(ContractConflictException.class, ex ->
                        assertThat(ex.getCode())
                                .isEqualTo(ContractConflictException.Code.INVALID_CONTRACT_STATE));
    }

    // --------------------------------------------------- ACTIVE -> DECLINED (legal)

    @Test
    void invokeDeclineOnActiveSuspendsMonitorsDeactivatesGuardsAtomicallyAndIsNeverAnError() {
        Contract c = activatedContract();
        UUID triggerId = triggerRepository.findByContractIdOrderBySequenceOrderAsc(c.getId())
                .get(0).getId();

        assertThatCode(() -> inTx(() -> service.invokeDecline(c.getId(), new InvokeDeclineCommand(
                triggerId, "Methodology proved insufficient for our domain", null, OPERATOR))))
                .doesNotThrowAnyException();

        Contract declined = contractRepository.findById(c.getId()).orElseThrow();
        assertThat(declined.getStatus()).isEqualTo(ContractStatus.DECLINED);
        assertThat(declined.getResolvedAt()).isEqualTo(T0);
        assertThat(monitorRepository.findByContractId(c.getId()))
                .allSatisfy(m -> assertThat(m.getStatus()).isEqualTo(MonitorStatus.SUSPENDED));
        assertThat(guardRepository.findByContractId(c.getId()))
                .allSatisfy(g -> assertThat(g.isActive()).isFalse());
        assertThat(eventTypes(c)).containsExactly(
                ContractEventService.CONTRACT_ACTIVATED,
                ContractEventService.DECLINE_TRIGGER_INVOKED,
                ContractEventService.CONTRACT_DECLINED);
        assertThat(mirror.declined).hasSize(1);
        assertThat(publishedAfterCommit)
                .filteredOn(ContractDeclinedEvent.class::isInstance)
                .singleElement()
                .isInstanceOfSatisfying(ContractDeclinedEvent.class, e ->
                        assertThat(e.fromStatus()).isEqualTo(ContractStatus.ACTIVE));
    }

    @Test
    void invokeDeclineOnProposedIsInvalidContractState() {
        Contract c = inTx(() -> service.create(twoPartyCommand()));
        UUID triggerId = triggerRepository.findByContractIdOrderBySequenceOrderAsc(c.getId())
                .get(0).getId();
        assertThatThrownBy(() -> inTx(() -> service.invokeDecline(c.getId(),
                new InvokeDeclineCommand(triggerId, "too early", null, OPERATOR))))
                .isInstanceOfSatisfying(ContractConflictException.class, ex -> {
                    assertThat(ex.getCode())
                            .isEqualTo(ContractConflictException.Code.INVALID_CONTRACT_STATE);
                    assertThat(ex.getMessage()).contains("respond");
                });
    }

    @Test
    void invokeDeclineOnDeclinedTerminalIsInvalidContractState() {
        Contract c = declinedContract();
        UUID triggerId = triggerRepository.findByContractIdOrderBySequenceOrderAsc(c.getId())
                .get(0).getId();
        assertThatThrownBy(() -> inTx(() -> service.invokeDecline(c.getId(),
                new InvokeDeclineCommand(triggerId, "again", null, OPERATOR))))
                .isInstanceOfSatisfying(ContractConflictException.class, ex ->
                        assertThat(ex.getCode())
                                .isEqualTo(ContractConflictException.Code.INVALID_CONTRACT_STATE));
    }

    @Test
    void invokeDeclineGuards() {
        Contract c = activatedContract();
        UUID triggerId = triggerRepository.findByContractIdOrderBySequenceOrderAsc(c.getId())
                .get(0).getId();

        assertThatThrownBy(() -> inTx(() -> service.invokeDecline(c.getId(),
                new InvokeDeclineCommand(triggerId, " ", null, OPERATOR))))
                .as("reasoning mandatory (§6.1)")
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> inTx(() -> service.invokeDecline(c.getId(),
                new InvokeDeclineCommand(UUID.randomUUID(), "valid reasoning", null, OPERATOR))))
                .as("trigger must belong to the contract (§6.1)")
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(contractRepository.findById(c.getId()).orElseThrow().getStatus())
                .isEqualTo(ContractStatus.ACTIVE);
    }

    @Test
    void invocableByRestrictedTriggerEnforcesInvokingParty() {
        Contract c = inTx(() -> service.create(commandWithPartyRestrictedTrigger()));
        List<ContractParty> parties = requiredParties(c);
        inTx(() -> service.respond(c.getId(), accept(parties.get(0))));
        inTx(() -> service.respond(c.getId(), accept(parties.get(1))));
        ContractDeclineTrigger restricted =
                triggerRepository.findByContractIdOrderBySequenceOrderAsc(c.getId()).get(0);
        UUID allowedParty = restricted.getInvocableByPartyId();
        assertThat(allowedParty).isNotNull();

        assertThatThrownBy(() -> inTx(() -> service.invokeDecline(c.getId(),
                new InvokeDeclineCommand(restricted.getId(), "wrong party",
                        otherPartyId(parties, allowedParty), OPERATOR))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invocable only by party");

        assertThatCode(() -> inTx(() -> service.invokeDecline(c.getId(),
                new InvokeDeclineCommand(restricted.getId(), "right party",
                        allowedParty, OPERATOR))))
                .doesNotThrowAnyException();
        assertThat(contractRepository.findById(c.getId()).orElseThrow().getStatus())
                .isEqualTo(ContractStatus.DECLINED);
    }

    // ----------------------------------------------- content freeze (ADR D7, §14.3)

    @Test
    void patchAllowedWhileProposedWithZeroResponses() {
        Contract c = inTx(() -> service.create(twoPartyCommand()));
        Contract patched = inTx(() -> service.patch(c.getId(), new PatchContractCommand(
                "Revised title", null, null, null, null, null, null)));
        assertThat(patched.getTitle()).isEqualTo("Revised title");
    }

    @Test
    void patchAfterFirstResponseIsFrozenEvenWhileProposed() {
        Contract c = inTx(() -> service.create(twoPartyCommand()));
        inTx(() -> service.respond(c.getId(), accept(requiredParties(c).get(0))));
        assertThat(contractRepository.findById(c.getId()).orElseThrow().getStatus())
                .as("still PROPOSED — the freeze keys on the FIRST response, not the status")
                .isEqualTo(ContractStatus.PROPOSED);

        assertThatThrownBy(() -> inTx(() -> service.patch(c.getId(), new PatchContractCommand(
                "Shifting text under an accepted party", null, null, null, null, null, null))))
                .isInstanceOfSatisfying(ContractConflictException.class, ex ->
                        assertThat(ex.getCode())
                                .isEqualTo(ContractConflictException.Code.CONTRACT_CONTENT_FROZEN));
    }

    @Test
    void patchAfterActivationIsFrozen() {
        Contract c = activatedContract();
        assertThatThrownBy(() -> inTx(() -> service.patch(c.getId(), new PatchContractCommand(
                "Too late", null, null, null, null, null, null))))
                .isInstanceOfSatisfying(ContractConflictException.class, ex ->
                        assertThat(ex.getCode())
                                .isEqualTo(ContractConflictException.Code.CONTRACT_CONTENT_FROZEN));
    }

    // ------------------------------------- WITHDRAWN / CONCLUDED unreachability

    @Test
    void withdrawnAndConcludedAreTransitionUnreachable() {
        // (a) The §2.2 table-as-data has no inbound edge to either reserved
        // value — every status write in the service goes through it (ADR D7).
        for (ContractStatus s : ContractStatus.values()) {
            assertThat(s.legalTargets())
                    .doesNotContain(ContractStatus.WITHDRAWN, ContractStatus.CONCLUDED);
        }
        // (b) No public service verb accepts a target ContractStatus — there
        // is no arbitrary-transition API a caller could reach them through.
        for (Method m : ContractRegistryService.class.getMethods()) {
            assertThat(Arrays.asList(m.getParameterTypes()))
                    .as("public verb %s must not take a target ContractStatus", m.getName())
                    .doesNotContain(ContractStatus.class);
        }
        // (c) Every verb path exercised by this suite lands on fixture-implied
        // statuses only.
        assertThat(contractRepository.findAll())
                .allSatisfy(c -> assertThat(c.getStatus()).isIn(
                        ContractStatus.PROPOSED, ContractStatus.ACTIVE, ContractStatus.DECLINED));
    }

    // ------------------------------------------------------------------ helpers

    private CreateContractCommand twoPartyCommand() {
        return commandWithParties(List.of(
                party("Party A", true, "Party A delivers the synthetic obligation"),
                party("Party B", true, "Party B reports synthetic progress monthly")));
    }

    private CreateContractCommand commandWithParties(List<PartyCommand> parties) {
        return new CreateContractCommand(
                UUID.randomUUID(), "synthetic-" + (++keySeq) + "-" + UUID.randomUUID(),
                "cross-portfolio-opportunity", "Synthetic state-machine contract",
                "Synthetic context", "Synthetic monitor prose", "Synthetic mechanism prose",
                null, null, null, T0, "State Machine Suite",
                parties,
                List.of(new DeclineTriggerCommand("Synthetic pre-agreed ground", null, null)),
                List.of(new MonitorCommand("synthetic-monitor", MonitorRuleType.ATTESTATION_CADENCE,
                        null, "MONTHLY", 7, null, null, null, null)),
                List.of(new GuardCommand("agent.promote", null, GuardEffect.FLAG, null,
                        "Synthetic flag guard")),
                List.of());
    }

    private CreateContractCommand commandWithPartyRestrictedTrigger() {
        CreateContractCommand base = twoPartyCommand();
        return new CreateContractCommand(base.domainId(), base.contractKey(), base.type(),
                base.title(), base.context(), base.monitorText(), base.monitorMechanism(),
                base.estimatedValue(), base.estimatedValueAmount(), base.reviewBoundary(),
                base.proposedAt(), base.proposedBy(), base.parties(),
                List.of(new DeclineTriggerCommand("Only Party A may invoke", null, "Party A")),
                base.monitors(), base.guards(), base.linkedEntities());
    }

    private static PartyCommand party(String label, boolean required, String commitment) {
        return new PartyCommand(label, ContractPartyType.DOMAIN, null, required,
                List.of(new CommitmentCommand(commitment, null, null)));
    }

    private List<ContractParty> requiredParties(Contract c) {
        return partyRepository.findByContractIdOrderBySequenceOrderAsc(c.getId()).stream()
                .filter(ContractParty::isResponseRequired)
                .toList();
    }

    private RespondCommand accept(ContractParty p) {
        return new RespondCommand(p.getId(), PartyResponse.ACCEPTED, null, null,
                p.getPartyLabel(), OPERATOR);
    }

    private Contract activatedContract() {
        Contract c = inTx(() -> service.create(twoPartyCommand()));
        for (ContractParty p : requiredParties(c)) {
            inTx(() -> service.respond(c.getId(), accept(p)));
        }
        Contract active = contractRepository.findById(c.getId()).orElseThrow();
        assertThat(active.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        return active;
    }

    private Contract declinedContract() {
        Contract c = inTx(() -> service.create(twoPartyCommand()));
        ContractParty decliner = requiredParties(c).get(0);
        inTx(() -> service.respond(c.getId(), new RespondCommand(
                decliner.getId(), PartyResponse.DECLINED, null, "synthetic documented reasoning",
                decliner.getPartyLabel(), OPERATOR)));
        Contract declined = contractRepository.findById(c.getId()).orElseThrow();
        assertThat(declined.getStatus()).isEqualTo(ContractStatus.DECLINED);
        return declined;
    }

    private List<String> eventTypes(Contract c) {
        return eventRepository.findByContractIdOrderByOccurredAtAsc(c.getId()).stream()
                .map(ContractEvent::getEventType)
                .toList();
    }

    private static UUID otherPartyId(List<ContractParty> parties, UUID notThis) {
        return parties.stream().map(ContractParty::getId)
                .filter(id -> !id.equals(notThis))
                .findFirst().orElseThrow();
    }
}
