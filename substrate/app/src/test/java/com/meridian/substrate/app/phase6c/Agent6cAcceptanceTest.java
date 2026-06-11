package com.meridian.substrate.app.phase6c;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import com.meridian.substrate.common.domain.enums.GatewayDecision;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.common.repo.ContractEnforcementDecisionRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.contract.gateway.ContractDeniedException;
import com.meridian.substrate.engine.contract.gateway.GovernedActions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CI pin of the §16.1 row-<b>6c</b> REST-visible acceptance beats
 * (engine-spec agents.md §16.1; ADR-003 D7/D17) — the MCP halves live in
 * {@code AgentMcpToolsTest}; the interactive evidence run drives the same
 * endpoints with curl + an MCP client.
 *
 * <ul>
 *   <li><b>The D17 window is CLOSED — mode-honestly:</b> with
 *       {@code AgentRegistryGateImpl} live AND the shared ladder in
 *       {@code enforce}, a SUBSTRATE_MCP-locus pause answers
 *       {@code effect=gateway-block, confirmationState=not-required} and the
 *       registry-flip disclosure. In {@code shadow} (the ship default) the
 *       gateway rows WOULD_DENY and proceeds, so the SAME verb honestly
 *       answers {@code recorded-intent} with the shared-ladder disclosure —
 *       and CUSTOMER_SYSTEM stays RECORDED_INTENT in every mode (substrate
 *       never claims to have stopped what it does not stop, spec
 *       non-negotiable #1 / invariant I8);</li>
 *   <li><b>{@code agent.promote} DENY guard (the contracts-3c seeded
 *       lever)</b> blocks promote in enforce: 409 {@code CONTRACT_DENIAL},
 *       the agent STAYS shadow, the DENY decision row cites the action.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "substrate.demo.seed=true",
        "substrate.demo.contracts.guards=true",
        "substrate.contracts.monitor.sweep-interval=PT1H",
        "substrate.contracts.enforcement.mode=shadow",
        "spring.datasource.url=jdbc:h2:mem:acceptance_6c;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class Agent6cAcceptanceTest {

    static final String GUARDS_KEY = "demo-gateway-guards";
    static final String OPERATOR = "Curasync RCM team";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AgentRegistrationRepository registrations;
    @Autowired ContractRepository contracts;
    @Autowired ContractEnforcementDecisionRepository decisions;
    @Autowired ContractProperties properties;

    @AfterEach
    void restoreShadow() {
        properties.getEnforcement().setMode("shadow");
    }

    @Test
    void substrateMcpLocusPause_isAnHonestGatewayBlock_customerSystemStaysRecordedIntent()
            throws Exception {
        UUID domainId = UUID.randomUUID();
        AgentRegistration shadowModeAgent =
                deploy(domainId, "a-gw-shadow", "substrate-mcp", "live within the gateway");
        AgentRegistration enforceModeAgent =
                deploy(domainId, "a-gw-enforce", "substrate-mcp", "live within the gateway");
        AgentRegistration externalAgent = deploy(domainId, "a-ext", "customer-system", null);

        // SUBSTRATE_MCP in SHADOW (the ship default): the gateway rows
        // WOULD_DENY and PROCEEDS — claiming gateway-block here would be
        // fabricated control (spec non-negotiable #1 / I8). The honest claim
        // is recorded-intent + the shared-ladder disclosure.
        postVerb(shadowModeAgent.getId() + "/pause", reasoned("Throttling investigation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enforcement.effect", is("recorded-intent")))
                .andExpect(jsonPath("$.data.enforcement.confirmationState", is("pending")))
                .andExpect(jsonPath("$.data.enforcement.disclosure",
                        org.hamcrest.Matchers.containsString("SHADOW")))
                .andExpect(jsonPath("$.data.enforcement.disclosure",
                        org.hamcrest.Matchers.containsString("not blocked")));

        // SUBSTRATE_MCP in ENFORCE: the registry flip IS the control plane
        // (§5.3, 6c live) — the gateway denies the agent's next governed call.
        properties.getEnforcement().setMode("enforce");
        postVerb(enforceModeAgent.getId() + "/pause", reasoned("Throttling investigation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enforcement.effect", is("gateway-block")))
                .andExpect(jsonPath("$.data.enforcement.confirmationState", is("not-required")))
                .andExpect(jsonPath("$.data.enforcement.disclosure",
                        org.hamcrest.Matchers.containsString("registry flip enforced at the gateway")));

        // CUSTOMER_SYSTEM: RECORDED_INTENT in every mode — honest, never fabricated
        postVerb(externalAgent.getId() + "/pause", reasoned("Paused pending review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enforcement.effect", is("recorded-intent")))
                .andExpect(jsonPath("$.data.enforcement.confirmationState", is("pending")))
                .andExpect(jsonPath("$.data.enforcement.disclosure",
                        org.hamcrest.Matchers.containsString("substrate cannot reach")));
    }

    @Test
    void seededAgentPromoteDenyGuard_blocksPromoteInEnforce() throws Exception {
        Contract guarded = contracts.findByContractKey(GUARDS_KEY).get(0);
        assertThat(guarded.getStatus()).isEqualTo(ContractStatus.ACTIVE);
        UUID domainId = guarded.getDomainId();

        AgentRegistration reg = deploy(domainId, "a-guarded-promote", "substrate-mcp", null);
        postVerb(reg.getId() + "/promote/request", body()).andExpect(status().isOk());
        approvePromotionReview(reg);

        // shadow: the same promote WOULD be denied but proceeds … we stay in
        // enforce for the acceptance beat itself:
        properties.getEnforcement().setMode("enforce");
        postVerb(reg.getId() + "/promote", body())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is(ContractDeniedException.CODE)));

        // the agent STAYS shadow — the guard structurally blocked production entry
        AgentRegistration after = registrations.findById(reg.getId()).orElseThrow();
        assertThat(after.getOperationalMode()).isEqualTo(AgentOperationalMode.SHADOW);
        // …and the DENY decision row cites agent.promote (no parallel log)
        assertThat(decisions.findByDomainIdAndDecision(domainId, GatewayDecision.DENY))
                .anyMatch(d -> GovernedActions.AGENT_PROMOTE.equals(d.getActionType()));
    }

    // ------------------------------------------------------------------ helpers

    private AgentRegistration deploy(UUID domainId, String agentKey, String locus,
                                     String liveJustification) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("idempotencyKey", "t:" + UUID.randomUUID());
        body.put("domainId", domainId.toString());
        body.put("agentKey", agentKey);
        body.put("name", "Acceptance Agent " + agentKey);
        body.put("roleDescription", "6c acceptance vector");
        body.put("executionLocus", locus);
        body.put("deployedByLabel", OPERATOR);
        body.put("deploymentCriteria", "Operate within the governed gateway scope");
        ObjectNode gate = body.putArray("criteriaGates").addObject();
        gate.put("gateText", "Operate within the governed gateway scope");
        gate.put("ruleType", "unstructured");
        body.putArray("oversightChain").addObject().put("label", "Curasync RCM Lead");
        body.put("supervisoryModel", "human-in-loop");
        if (liveJustification != null) {
            body.put("liveJustification", liveJustification);
        }
        mvc.perform(post("/api/v1/agents")
                        .header("X-Persona-Label", OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated());
        return registrations.findByDomainIdAndAgentKey(domainId, agentKey).orElseThrow();
    }

    private org.springframework.test.web.servlet.ResultActions postVerb(String path,
                                                                        ObjectNode body)
            throws Exception {
        return mvc.perform(post("/api/v1/agents/" + path)
                .header("X-Persona-Label", OPERATOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()));
    }

    private ObjectNode body() {
        ObjectNode body = json.createObjectNode();
        body.put("idempotencyKey", "t:" + UUID.randomUUID());
        return body;
    }

    private ObjectNode reasoned(String reason) {
        ObjectNode body = body();
        body.put("reason", reason);
        return body;
    }

    private void approvePromotionReview(AgentRegistration reg) throws Exception {
        ObjectNode review = json.createObjectNode();
        review.put("reviewKind", "promotion");
        review.put("requestedByLabel", "Curasync RCM Lead");
        MvcResult recorded = postVerb(reg.getId() + "/reviews", review).andReturn();
        JsonNode envelope = json.readTree(recorded.getResponse().getContentAsByteArray());
        String reviewId = envelope.path("data").path("id").asText();
        ObjectNode decide = json.createObjectNode();
        decide.put("decision", "approved");
        decide.put("rationale", "review passed: acceptance vector");
        decide.put("reviewerLabel", "Curasync RCM Lead");
        postVerb("reviews/" + reviewId + "/decide", decide).andExpect(status().isOk());
    }
}
