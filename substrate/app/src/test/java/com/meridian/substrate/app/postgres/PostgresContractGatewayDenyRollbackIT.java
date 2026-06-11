package com.meridian.substrate.app.postgres;

import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractClauseGuard;
import com.meridian.substrate.common.domain.ContractEnforcementDecision;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.enums.ContractPartyType;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.domain.enums.GatewayDecision;
import com.meridian.substrate.common.domain.enums.GuardEffect;
import com.meridian.substrate.common.domain.enums.PartyResponse;
import com.meridian.substrate.common.repo.ContractClauseGuardRepository;
import com.meridian.substrate.common.repo.ContractEnforcementDecisionRepository;
import com.meridian.substrate.common.repo.ContractPartyRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.contract.gateway.ContractDeniedException;
import com.meridian.substrate.engine.contract.gateway.ContractGateway;
import com.meridian.substrate.engine.contract.gateway.Decision;
import com.meridian.substrate.engine.contract.gateway.GovernedAction;
import com.meridian.substrate.engine.contract.gateway.Principal;
import com.meridian.substrate.engine.contract.registry.ContractActor;
import com.meridian.substrate.engine.contract.registry.ContractRegistryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Engine-spec contracts.md §14.4 gateway rollback semantics on real
 * Postgres 16: <b>a governed write that DENYs in enforce mode rolls back the
 * business mutation AND persists the DENY decision row</b> — the §10.4
 * REQUIRES_NEW proof (ADR D17: "DENY decision rows persist even though the
 * business transaction rolls back"). Plus the ADR D4 shadow contrast lane:
 * same evaluation in shadow rows {@code WOULD_DENY} and the business
 * mutation COMMITS (shadow never blocks).
 *
 * <p>The business mutation and {@code ContractGateway.evaluate} run inside
 * ONE caller-owned transaction ({@code TransactionTemplate}) — exactly the
 * §4.2 PEP posture (the aspect runs inside the business transaction); the
 * synthetic {@code agent.promote} DENY guard is the §13 3c acceptance
 * scenario (both fixture contracts compile to zero guards, spec §16 — the
 * guard-bearing contract is deliberately synthetic).
 *
 * <p>Deliberately NOT {@code @Transactional} at the class level: the rollback
 * under test must be a real commit/rollback boundary, not a test-managed one
 * (cascade-concurrency IT idiom).
 *
 * <p><b>Docker-less machines (e.g. the Windows dev box):</b> self-disables via
 * {@code @Testcontainers(disabledWithoutDocker = true)}; CI (ubuntu-latest)
 * runs the real gate. H2-green is never acceptance (port-map §4.4).
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        // keep the background sweep out of the test window
        "substrate.contracts.monitor.sweep-interval=PT1H",
        // explicit start posture; the lanes flip the mutable bean per engagement (ADR D4)
        "substrate.contracts.enforcement.mode=shadow"
})
class PostgresContractGatewayDenyRollbackIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private ContractRegistryService registryService;
    @Autowired private ContractGateway gateway;
    @Autowired private ContractRepository contracts;
    @Autowired private ContractPartyRepository parties;
    @Autowired private ContractClauseGuardRepository guards;
    @Autowired private ContractEnforcementDecisionRepository decisions;
    @Autowired private ContractProperties properties;
    @Autowired private PlatformTransactionManager transactionManager;

    @AfterEach
    void restoreShadowMode() {
        properties.getEnforcement().setMode("shadow");
    }

    @Test
    void enforceDeny_rollsBackBusinessMutation_butDenyRowSurvives() {
        UUID domainId = UUID.randomUUID();
        UUID guardId = activeDenyGuard(domainId, "deny-roll");
        properties.getEnforcement().setMode("enforce");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            // The governed business mutation, same transaction as the PDP call
            // (§4.2 PEP posture): it must NOT survive the DENY.
            registryService.create(twoPartyContract(domainId, "biz-mutation-denied"));
            gateway.evaluate(new GovernedAction("agent.promote",
                    Principal.agent("it-agent"), domainId, null, null));
        })).isInstanceOf(ContractDeniedException.class)
                .satisfies(thrown -> {
                    ContractDeniedException denial = (ContractDeniedException) thrown;
                    assertThat(denial.getDeny().clauseIds()).containsExactly(guardId);
                    assertThat(denial.getDeny().contractKeys()).containsExactly("deny-roll");
                    assertThat(denial.getDecisionId()).isNotNull();

                    // Business mutation rolled back…
                    assertThat(contracts.findByDomainIdAndContractKey(domainId, "biz-mutation-denied"))
                            .as("§14.4: the denied business mutation must roll back")
                            .isEmpty();

                    // …while the DENY decision row survives (REQUIRES_NEW, §10.4).
                    List<ContractEnforcementDecision> rows =
                            decisions.findByDomainIdAndDecidedAtAfter(domainId, Instant.EPOCH);
                    assertThat(rows)
                            .as("§14.4: the DENY decision row persists across the rollback")
                            .hasSize(1);
                    ContractEnforcementDecision row = rows.get(0);
                    assertThat(row.getId()).isEqualTo(denial.getDecisionId());
                    assertThat(row.getDecision()).isEqualTo(GatewayDecision.DENY);
                    assertThat(row.getMode()).isEqualTo("enforce");
                    assertThat(row.getActionType()).isEqualTo("agent.promote");
                    assertThat(row.getClauseIds().get(0).asText())
                            .isEqualTo(guardId.toString());
                });
    }

    @Test
    void shadowWouldDeny_businessMutationCommits_andWouldDenyRowPersists() {
        UUID domainId = UUID.randomUUID();
        activeDenyGuard(domainId, "deny-shadow");
        properties.getEnforcement().setMode("shadow");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Decision decision = tx.execute(status -> {
            registryService.create(twoPartyContract(domainId, "biz-mutation-shadowed"));
            return gateway.evaluate(new GovernedAction("agent.promote",
                    Principal.agent("it-agent"), domainId, null, null));
        });

        assertThat(((Decision.Allow) decision).flagged())
                .as("ADR D4: shadow proceeds flagged, never blocks")
                .isTrue();
        assertThat(contracts.findByDomainIdAndContractKey(domainId, "biz-mutation-shadowed"))
                .as("shadow: the business mutation commits")
                .isPresent();
        List<ContractEnforcementDecision> rows =
                decisions.findByDomainIdAndDecidedAtAfter(domainId, Instant.EPOCH);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getDecision()).isEqualTo(GatewayDecision.WOULD_DENY);
        assertThat(rows.get(0).getMode()).isEqualTo("shadow");
    }

    // ------------------------------------------------------------------ support

    /**
     * Seeds the §13 synthetic guard-bearing contract and activates it (both
     * parties accept) so the {@code agent.promote} DENY guard is live; returns
     * the guard id.
     */
    private UUID activeDenyGuard(UUID domainId, String contractKey) {
        ContractRegistryService.CreateContractCommand cmd = new ContractRegistryService.CreateContractCommand(
                domainId, contractKey, "cross-portfolio-opportunity",
                "Gateway rollback IT " + contractKey, null, null, null, null, null, null,
                Instant.now(), "gateway-it",
                List.of(party("Party A"), party("Party B")),
                List.of(), List.of(),
                List.of(new ContractRegistryService.GuardCommand("agent.promote", null,
                        GuardEffect.DENY, null,
                        "Synthetic DENY guard — §13 3c acceptance posture")),
                List.of());
        Contract contract = registryService.create(cmd);
        for (ContractParty party : parties.findByContractIdOrderBySequenceOrderAsc(contract.getId())) {
            registryService.respond(contract.getId(), new ContractRegistryService.RespondCommand(
                    party.getId(), PartyResponse.ACCEPTED, null, null,
                    "accept@" + party.getPartyLabel(), ContractActor.human("gateway-it")));
        }
        Contract activated = contracts.findById(contract.getId()).orElseThrow();
        assertThat(activated.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        List<ContractClauseGuard> contractGuards = guards.findByContractId(contract.getId());
        assertThat(contractGuards).hasSize(1);
        assertThat(contractGuards.get(0).isActive()).isTrue();   // §2.2 activation flips guards live
        return contractGuards.get(0).getId();
    }

    /** The guard-free business mutation: a plain PROPOSED contract write. */
    private static ContractRegistryService.CreateContractCommand twoPartyContract(
            UUID domainId, String contractKey) {
        return new ContractRegistryService.CreateContractCommand(
                domainId, contractKey, "cross-portfolio-opportunity",
                "Business mutation " + contractKey, null, null, null, null, null, null,
                Instant.now(), "gateway-it",
                List.of(party("Party A"), party("Party B")),
                List.of(), List.of(), List.of(), List.of());
    }

    private static ContractRegistryService.PartyCommand party(String label) {
        return new ContractRegistryService.PartyCommand(label, ContractPartyType.ORG_UNIT,
                null, true, List.of(new ContractRegistryService.CommitmentCommand(
                        "Commit to the gateway IT", null, null)));
    }
}
