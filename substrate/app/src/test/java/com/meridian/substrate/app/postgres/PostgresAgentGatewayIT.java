package com.meridian.substrate.app.postgres;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.app.agent.AgentVerbTestSupport;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.AgentSighting;
import com.meridian.substrate.common.domain.enums.AgentInterventionStatus;
import com.meridian.substrate.common.domain.enums.AgentSightingChannel;
import com.meridian.substrate.common.domain.enums.GatewayDecision;
import com.meridian.substrate.common.repo.AgentInterventionRepository;
import com.meridian.substrate.common.repo.AgentRateCounterRepository;
import com.meridian.substrate.common.repo.AgentSightingRepository;
import com.meridian.substrate.common.repo.ContractEnforcementDecisionRepository;
import com.meridian.substrate.engine.agent.AgentGatewaySignalService;
import com.meridian.substrate.engine.agent.AgentLivenessService;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.contract.gateway.AgentGateDeniedException;
import com.meridian.substrate.engine.contract.gateway.AgentRegistryGate;
import com.meridian.substrate.engine.contract.gateway.ContractGateway;
import com.meridian.substrate.engine.contract.gateway.Decision;
import com.meridian.substrate.engine.contract.gateway.GovernedAction;
import com.meridian.substrate.engine.contract.gateway.GovernedActions;
import com.meridian.substrate.engine.contract.gateway.GovernedPrincipalScope;
import com.meridian.substrate.engine.contract.gateway.Principal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Engine-spec agents.md §16.2 test plan <b>#12 — the gateway suite on REAL
 * Postgres 16</b> (the CI acceptance gate; H2-green is never acceptance):
 * REQUIRES_NEW zombie-trail survival through an enforce-mode rollback,
 * DB-backed ACTIONS_PER_WINDOW counters (denied increments roll back with
 * the denied transaction), the unregistered policy per mode, decision rows
 * in {@code contract_enforcement_decisions}, and the §8.3 hot-path latency
 * budget (contracts p50 &lt; 5 ms now covering BOTH lookups — registry
 * snapshot cache + sighting-bucket pre-check).
 *
 * <p>Deliberately NOT {@code @Transactional}; self-disables without Docker
 * (the Windows dev box) — CI (ubuntu-latest) runs the real gate.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "substrate.contracts.enforcement.mode=shadow",
        "substrate.contracts.monitor.sweep-interval=PT1H"
})
@AutoConfigureMockMvc
class PostgresAgentGatewayIT extends AgentVerbTestSupport {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired ContractGateway gateway;
    @Autowired ContractProperties contractProperties;
    @Autowired AgentLivenessService liveness;
    @Autowired AgentSightingRepository sightings;
    @Autowired AgentInterventionRepository interventions;
    @Autowired AgentRateCounterRepository counters;
    @Autowired ContractEnforcementDecisionRepository decisions;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void restoreShadow() {
        contractProperties.getEnforcement().setMode("shadow");
    }

    @Test
    void zombieTrailSurvivesTheEnforceRollback_onRealRequiresNew() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-msg-router");
        bind(reg, "pg-zombie-agent");
        postVerb(reg.getId() + "/pause", reasonedBody("Paused pending review"))
                .andExpect(status().isOk());
        contractProperties.getEnforcement().setMode("enforce");

        assertThatThrownBy(() -> GovernedPrincipalScope.callAs(Principal.agent("pg-zombie-agent"),
                () -> liveness.heartbeat(reg.getId(), "still alive")))
                .isInstanceOf(AgentGateDeniedException.class);

        // business write rolled back, zombie trail + DENY row committed (REQUIRES_NEW)
        List<AgentSighting> sighted =
                sightings.findByAgentRegistrationIdOrderBySeenAtDesc(reg.getId());
        assertThat(sighted).extracting(AgentSighting::getChannel)
                .contains(AgentSightingChannel.GATEWAY_ACTION)
                .doesNotContain(AgentSightingChannel.HEARTBEAT);
        assertThat(interventions
                .findByAgentRegistrationIdAndStatus(reg.getId(), AgentInterventionStatus.OPEN))
                .anyMatch(i -> AgentGatewaySignalService.SOURCE_ACTIVE_WHILE_PAUSED
                        .equals(i.getSourceKey()));
        assertThat(decisions.findByDomainIdAndDecision(reg.getDomainId(), GatewayDecision.DENY))
                .anyMatch(d -> AgentRegistryGate.REASON_CONTROL_SUSPENDED.equals(d.getReason()));
    }

    @Test
    void actionsPerWindowCounters_deniedIncrementsRollBack_budgetStaysExhausted()
            throws Exception {
        AgentRegistration reg = deployWritableLive(UUID.randomUUID(), "a-budget");
        bind(reg, "pg-budget-agent");
        ObjectNode throttle = verbBody();
        throttle.put("budgetKind", "ACTIONS_PER_WINDOW");
        throttle.put("limitValue", 2);
        throttle.put("windowMinutes", 60);
        postVerb(reg.getId() + "/throttle", throttle).andExpect(status().isOk());
        contractProperties.getEnforcement().setMode("enforce");

        GovernedAction action = agentAction(GovernedActions.CASCADE_ISSUE, "pg-budget-agent",
                reg.getDomainId());
        assertThat(evaluate(action)).isInstanceOf(Decision.Allow.class);    // 1
        assertThat(evaluate(action)).isInstanceOf(Decision.Allow.class);    // 2
        assertThatThrownBy(() -> evaluate(action))                          // 3 > limit
                .isInstanceOf(AgentGateDeniedException.class)
                .satisfies(t -> assertThat(((AgentGateDeniedException) t).getDeny().reason())
                        .isEqualTo(AgentRegistryGate.REASON_BUDGET_EXHAUSTED));

        // the DENIED increment rolled back with its transaction (D12 honesty:
        // a refused call consumes no budget) — and the 4th call still denies.
        int committed = counters.findAll().stream()
                .filter(c -> c.getAgentRegistrationId().equals(reg.getId()))
                .mapToInt(c -> c.getActionCount()).sum();
        assertThat(committed).isEqualTo(2);
        assertThatThrownBy(() -> evaluate(action)).isInstanceOf(AgentGateDeniedException.class);
    }

    @Test
    void unregisteredPolicyPerMode_everyTouchRowed() {
        UUID domainId = UUID.randomUUID();
        GovernedAction action = agentAction(GovernedActions.CASCADE_ISSUE,
                "pg-unknown-agent", domainId);

        Decision shadow = evaluate(action);
        assertThat(((Decision.Allow) shadow).flagged()).isTrue();
        assertThat(decisions.findByDomainIdAndDecision(domainId, GatewayDecision.ALLOW_WITH_FLAG))
                .anyMatch(d -> d.getReason() != null
                        && d.getReason().contains(AgentRegistryGate.REASON_UNREGISTERED));

        contractProperties.getEnforcement().setMode("enforce");
        assertThatThrownBy(() -> evaluate(action))
                .isInstanceOf(AgentGateDeniedException.class);
        assertThat(decisions.findByDomainIdAndDecision(domainId, GatewayDecision.DENY))
                .anyMatch(d -> AgentRegistryGate.REASON_UNREGISTERED.equals(d.getReason()));
    }

    /**
     * §8.3 hot-path budget: the contracts p50 &lt; 5 ms now covers both
     * lookups. Steady state for a registered LIVE agent = cached registry
     * snapshot + one indexed sighting-bucket pre-check; measured around
     * {@code ContractGateway.evaluate} exactly as §10.4 measures latency.
     */
    @Test
    void hotPathP50StaysInsideTheFiveMillisecondBudget() throws Exception {
        AgentRegistration reg = deployWritableLive(UUID.randomUUID(), "a-perf");
        bind(reg, "pg-perf-agent");
        GovernedAction action = agentAction(GovernedActions.CASCADE_ISSUE, "pg-perf-agent",
                reg.getDomainId());

        for (int i = 0; i < 25; i++) {                       // warm: cache + bucket
            evaluate(action);
        }
        List<Long> nanos = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            long started = System.nanoTime();
            evaluate(action);
            nanos.add(System.nanoTime() - started);
        }
        nanos.sort(Long::compareTo);
        double p50Ms = nanos.get(nanos.size() / 2) / 1_000_000.0;
        assertThat(p50Ms)
                .as("§8.3: registry lookup rides the contracts p50 < 5 ms budget")
                .isLessThan(5.0);
    }

    // ------------------------------------------------------------------ helpers

    private Decision evaluate(GovernedAction action) {
        return new TransactionTemplate(transactionManager)
                .execute(tx -> gateway.evaluate(action));
    }

    private static GovernedAction agentAction(String actionType, String principalId,
                                              UUID domainId) {
        return new GovernedAction(actionType, Principal.agent(principalId), domainId, null, null);
    }

    private void bind(AgentRegistration reg, String principalId) throws Exception {
        ObjectNode body = verbBody();
        body.put("mcpPrincipalId", principalId);
        postVerb(reg.getId() + "/principal", body).andExpect(status().isOk());
    }

    private AgentRegistration deployWritableLive(UUID domainId, String agentKey)
            throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("idempotencyKey", key());
        body.put("domainId", domainId.toString());
        body.put("agentKey", agentKey);
        body.put("name", "Gateway IT Agent");
        body.put("roleDescription", "gateway CI vector");
        body.put("executionLocus", "substrate-mcp");
        body.put("deployedByLabel", OPERATOR);
        body.put("deploymentCriteria", "Operate within the governed gateway scope");
        ObjectNode gate = body.putArray("criteriaGates").addObject();
        gate.put("gateText", "Operate within the governed gateway scope");
        gate.put("ruleType", "unstructured");
        body.putArray("oversightChain").addObject().put("label", POSITION_1);
        body.put("supervisoryModel", "full-auto");
        body.put("readOnly", false);
        body.put("liveJustification", "gateway IT: live writable agent under test");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/agents")
                        .header("X-Persona-Label", OPERATOR)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated());
        return registrations.findByDomainIdAndAgentKey(domainId, agentKey).orElseThrow();
    }
}
