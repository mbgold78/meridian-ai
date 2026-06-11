package com.meridian.substrate.app.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractParty;
import com.meridian.substrate.common.domain.enums.ContractPartyType;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.domain.enums.GatewayDecision;
import com.meridian.substrate.common.repo.AgentSightingRepository;
import com.meridian.substrate.common.repo.ContractEnforcementDecisionRepository;
import com.meridian.substrate.common.repo.ContractPartyRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.agent.AgentQueryService;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.contract.gateway.AgentGateDeniedException;
import com.meridian.substrate.engine.contract.gateway.AgentRegistryGate;
import com.meridian.substrate.engine.contract.gateway.ContractGateway;
import com.meridian.substrate.engine.contract.gateway.Decision;
import com.meridian.substrate.engine.contract.gateway.GovernedAction;
import com.meridian.substrate.engine.contract.gateway.GovernedActions;
import com.meridian.substrate.engine.contract.gateway.Principal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §16.2 test plan <b>#12 — gateway suite, resolution + checks</b> (engine-spec
 * agents.md §8.2/§8.3; ADR-003 D7), driven at the PDP itself
 * ({@code ContractGateway.evaluate} inside a transaction — exactly how the
 * {@code @Governed} aspect invokes it):
 *
 * <ol>
 *   <li>registered SHADOW agent → observation scope only
 *       ({@code AGENT_SHADOW_SCOPE} on anything else);</li>
 *   <li>registered LIVE {@code read_only} agent → {@code AGENT_READ_ONLY};</li>
 *   <li>writable LIVE agent → allowed + GATEWAY_ACTION sighting (liveness
 *       for free);</li>
 *   <li>ACTIONS_PER_WINDOW budget: third call over a 2-limit →
 *       {@code AGENT_BUDGET_EXHAUSTED}; surfaced in
 *       {@code /agents/signals.budgetsExhausted};</li>
 *   <li>contract-party carve-out: party-bound principal allowed
 *       party-scoped contract verbs, anything else falls to the
 *       unregistered policy (P19 — never brick a counterparty);</li>
 *   <li>unregistered policy is mode-asymmetric BY SPEC: shadow ⇒
 *       ALLOW_WITH_FLAG({@code AGENT_UNREGISTERED}) rowed, enforce ⇒ DENY —
 *       every touch emits its row ("unknown agent knocking").</li>
 * </ol>
 *
 * <p>Fail-closed-on-gate-exception is unit-pinned in
 * {@code ContractGatewayTest} (mock seam); decision rows land in
 * {@code contract_enforcement_decisions} — no parallel log. H2 fast lane;
 * {@code PostgresAgentGatewayIT} is the CI acceptance gate.
 */
@SpringBootTest(properties = {
        "substrate.contracts.enforcement.mode=shadow",
        "substrate.contracts.monitor.sweep-interval=PT1H",
        "spring.datasource.url=jdbc:h2:mem:agent_gateway_6c;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AgentGatewayResolutionTest extends AgentVerbTestSupport {

    /** A governed mutating action OUTSIDE the §8.3 observation scope. */
    private static final String MUTATING_ACTION = GovernedActions.CASCADE_ISSUE;

    @Autowired ContractGateway gateway;
    @Autowired ContractProperties contractProperties;
    @Autowired ContractRepository contracts;
    @Autowired ContractPartyRepository parties;
    @Autowired ContractEnforcementDecisionRepository decisions;
    @Autowired AgentSightingRepository sightings;
    @Autowired AgentQueryService queryService;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void restoreShadow() {
        contractProperties.getEnforcement().setMode("shadow");
    }

    // ----------------------------------------------------- §8.3 check ladder

    @Test
    void shadowAgent_holdsObservationScopeOnly() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-coding");   // born SHADOW
        bind(reg, "resolution-shadow-agent");
        contractProperties.getEnforcement().setMode("enforce");

        // observation-scope action passes the scope checks
        Decision heartbeat = evaluate(action(GovernedActions.AGENT_HEARTBEAT,
                "resolution-shadow-agent", reg.getDomainId()));
        assertThat(heartbeat).isInstanceOf(Decision.Allow.class);

        // anything else is denied AGENT_SHADOW_SCOPE (P11, structural)
        assertGateDenied(action(MUTATING_ACTION, "resolution-shadow-agent", reg.getDomainId()),
                AgentRegistryGate.REASON_SHADOW_SCOPE);
    }

    @Test
    void liveReadOnlyAgent_isDeniedMutatingActions() throws Exception {
        AgentRegistration reg = deployLive(UUID.randomUUID(), "a-pre-visit", true);
        bind(reg, "resolution-readonly-agent");
        contractProperties.getEnforcement().setMode("enforce");

        assertGateDenied(action(MUTATING_ACTION, "resolution-readonly-agent", reg.getDomainId()),
                AgentRegistryGate.REASON_READ_ONLY);
    }

    @Test
    void writableLiveAgent_allowed_andSightedAtTheGateway() throws Exception {
        AgentRegistration reg = deployLive(UUID.randomUUID(), "a-eligibility", false);
        bind(reg, "resolution-writable-agent");
        contractProperties.getEnforcement().setMode("enforce");

        Decision decision = evaluate(action(MUTATING_ACTION, "resolution-writable-agent",
                reg.getDomainId()));

        assertThat(decision).isInstanceOf(Decision.Allow.class);
        // §8.3 #6 — liveness for free
        assertThat(sightings.findByAgentRegistrationIdOrderBySeenAtDesc(reg.getId()))
                .anyMatch(s -> s.getChannel()
                        == com.meridian.substrate.common.domain.enums.AgentSightingChannel.GATEWAY_ACTION);
        AgentRegistration reloaded = registrations.findById(reg.getId()).orElseThrow();
        assertThat(reloaded.getLastSeenAt()).isNotNull();
    }

    @Test
    void actionsPerWindowBudget_thirdCallExhausted_andSurfacedInSignals() throws Exception {
        AgentRegistration reg = deployLive(UUID.randomUUID(), "a-noshow", false);
        bind(reg, "resolution-budget-agent");
        ObjectNode throttle = verbBody();
        throttle.put("budgetKind", "ACTIONS_PER_WINDOW");
        throttle.put("limitValue", 2);
        throttle.put("windowMinutes", 60);
        postVerb(reg.getId() + "/throttle", throttle).andExpect(status().isOk());
        contractProperties.getEnforcement().setMode("enforce");

        GovernedAction action = action(MUTATING_ACTION, "resolution-budget-agent",
                reg.getDomainId());
        assertThat(evaluate(action)).isInstanceOf(Decision.Allow.class);   // 1
        assertThat(evaluate(action)).isInstanceOf(Decision.Allow.class);   // 2
        assertGateDenied(action, AgentRegistryGate.REASON_BUDGET_EXHAUSTED);   // 3 > limit

        // §13.1: budget exhaustion is a SIGNAL, present not silent
        assertThat(queryService.signals(reg.getDomainId()).budgetsExhausted())
                .anyMatch(b -> reg.getAgentKey().equals(b.agentKey())
                        && "ACTIONS_PER_WINDOW".equals(b.budgetKind()));
    }

    // ------------------------------------------------- §8.2 resolution order

    @Test
    void contractPartyPrincipal_allowedPartyScopedContractVerbs_unregisteredOtherwise() {
        UUID domainId = UUID.randomUUID();
        String principalId = "counterparty-system-" + UUID.randomUUID();
        partyBoundTo(domainId, principalId);
        contractProperties.getEnforcement().setMode("enforce");

        // party-scoped contract verb → allowed (P19 carve-out)
        assertThat(evaluate(action(GovernedActions.CONTRACT_ATTEST_COMMITMENT,
                principalId, domainId)))
                .isInstanceOf(Decision.Allow.class);

        // anything non-contract falls through to the unregistered policy
        assertGateDenied(action(MUTATING_ACTION, principalId, domainId),
                AgentRegistryGate.REASON_UNREGISTERED);
    }

    @Test
    void unregisteredPrincipal_shadowAllowsWithFlagAndRows_enforceDenies() {
        UUID domainId = UUID.randomUUID();
        String principalId = "unknown-agent-" + UUID.randomUUID();
        GovernedAction action = action(MUTATING_ACTION, principalId, domainId);

        // shadow ⇒ ALLOW_WITH_FLAG(AGENT_UNREGISTERED), rowed — never silent
        Decision shadow = evaluate(action);
        assertThat(shadow).isInstanceOf(Decision.Allow.class);
        assertThat(((Decision.Allow) shadow).flagged()).isTrue();
        assertThat(decisions.findByDomainIdAndDecision(domainId, GatewayDecision.ALLOW_WITH_FLAG))
                .anyMatch(d -> d.getReason() != null
                        && d.getReason().contains(AgentRegistryGate.REASON_UNREGISTERED));

        // enforce ⇒ DENY + row ("unknown agent knocking" surfaced both ways)
        contractProperties.getEnforcement().setMode("enforce");
        assertGateDenied(action, AgentRegistryGate.REASON_UNREGISTERED);
        assertThat(decisions.findByDomainIdAndDecision(domainId, GatewayDecision.DENY))
                .anyMatch(d -> AgentRegistryGate.REASON_UNREGISTERED.equals(d.getReason()));
    }

    @Test
    void readsNeverTransitTheGateway_unregisteredAgentsKeepReadAccess() throws Exception {
        // reads + check_action are ungoverned by design (§8.2): the REST read
        // surface answers an unregistered world without any gate involvement.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/agents").param("domainId", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ helpers

    private Decision evaluate(GovernedAction action) {
        return new TransactionTemplate(transactionManager)
                .execute(tx -> gateway.evaluate(action));
    }

    private void assertGateDenied(GovernedAction action, String reason) {
        assertThatThrownBy(() -> evaluate(action))
                .isInstanceOf(AgentGateDeniedException.class)
                .satisfies(thrown -> {
                    AgentGateDeniedException denial = (AgentGateDeniedException) thrown;
                    assertThat(denial.code()).isEqualTo(AgentGateDeniedException.AGENT_GATE_CODE);
                    assertThat(denial.getDeny().reason()).isEqualTo(reason);
                    assertThat(denial.getDecisionId()).isNotNull();   // rowed BEFORE the throw
                });
    }

    private static GovernedAction action(String actionType, String principalId, UUID domainId) {
        return new GovernedAction(actionType, Principal.agent(principalId), domainId, null, null);
    }

    private void bind(AgentRegistration reg, String principalId) throws Exception {
        ObjectNode body = verbBody();
        body.put("mcpPrincipalId", principalId);
        postVerb(reg.getId() + "/principal", body).andExpect(status().isOk());
    }

    /** Deploy-to-LIVE with explicit liveJustification + read-only posture (M2/§5.4). */
    private AgentRegistration deployLive(UUID domainId, String agentKey, boolean readOnly)
            throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("idempotencyKey", key());
        body.put("domainId", domainId.toString());
        body.put("agentKey", agentKey);
        body.put("name", "Eligibility Checker");
        body.put("roleDescription", "Verifies insurance eligibility ahead of visits");
        body.put("executionLocus", "substrate-mcp");
        body.put("deployedByLabel", OPERATOR);
        body.put("deploymentCriteria", "Operate within the governed gateway scope");
        ObjectNode gate = body.putArray("criteriaGates").addObject();
        gate.put("gateText", "Operate within the governed gateway scope");
        gate.put("ruleType", "unstructured");
        var chain = body.putArray("oversightChain");
        chain.addObject().put("label", POSITION_1);
        body.put("supervisoryModel", "full-auto");
        body.put("readOnly", readOnly);
        body.put("liveJustification", "gateway suite: live agent under test");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/agents")
                        .header("X-Persona-Label", OPERATOR)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated());
        return registrations.findByDomainIdAndAgentKey(domainId, agentKey).orElseThrow();
    }

    /** Test-arranged party binding (the ContractsMcpWriteTest identity idiom). */
    private void partyBoundTo(UUID domainId, String principalId) {
        Contract contract = new Contract();
        contract.setDomainId(domainId);
        contract.setContractKey("party-carveout-" + UUID.randomUUID());
        contract.setContractType("OPERATIONAL_SLA");
        contract.setTitle("P19 counterparty carve-out vector");
        contract.setStatus(ContractStatus.ACTIVE);
        contract.setProposedAt(Instant.parse("2026-06-01T00:00:00Z"));
        contract.setProposedBy("gateway-suite");
        contract = contracts.save(contract);

        ContractParty party = new ContractParty();
        party.setContractId(contract.getId());
        party.setPartyLabel("Counterparty System");
        party.setPartyType(ContractPartyType.EXTERNAL);
        party.setMcpPrincipalId(principalId);
        party.setSequenceOrder(1);
        parties.save(party);
    }
}
