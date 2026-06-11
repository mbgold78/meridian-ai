package com.meridian.substrate.app.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared REST-driving fixtures for the 6b agent verb suites (engine-spec
 * agents.md §16.2): one canonical deploy body (fixture strings VERBATIM from
 * {@code meridian-homepoc/frontend/src/demo/beacon/subsidiaries/healthcare.ts},
 * copied 2026-06-11) + thin verb-POST helpers. Tests drive the PRODUCTION
 * endpoints — acceptance-must-be-user-runnable applies to test transports too.
 */
public abstract class AgentVerbTestSupport {

    protected static final String OPERATOR = "Curasync RCM team";
    protected static final String POSITION_1 = "Curasync RCM Lead";
    protected static final String POSITION_2 = "Curasync COO";

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected AgentRegistrationRepository registrations;

    protected String key() {
        return "t:" + UUID.randomUUID();
    }

    /** Canonical deploy: SHADOW, customer-system locus, 2-deep chain, one STRUCTURED gate. */
    protected AgentRegistration deploy(UUID domainId, String agentKey) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("idempotencyKey", key());
        body.put("domainId", domainId.toString());
        body.put("agentKey", agentKey);
        body.put("name", "Pre-Visit Intake Assistant");
        body.put("roleDescription", "Pre-fills intake forms from prior visits");
        body.put("executionLocus", "customer-system");
        body.put("deployedByLabel", OPERATOR);
        body.put("deploymentCriteria",
                "Pre-fill intake forms from prior-visit data, target 90% pre-fill accuracy");
        ObjectNode gate = body.putArray("criteriaGates").addObject();
        gate.put("gateText", "target 90% pre-fill accuracy");
        gate.put("ruleType", "structured");
        gate.set("predicate", json.readTree("{\"metric\":\"prefill_accuracy\",\"gte\":0.90}"));
        gate.put("cadence", "MONTHLY");
        var chain = body.putArray("oversightChain");
        chain.addObject().put("label", POSITION_1);
        chain.addObject().put("label", POSITION_2);
        body.put("supervisoryModel", "human-in-loop");
        mvc.perform(post("/api/v1/agents")
                        .header("X-Persona-Label", OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isCreated());
        return registrations.findByDomainIdAndAgentKey(domainId, agentKey).orElseThrow();
    }

    /** POST a verb body to {@code /api/v1/agents/<path>}; returns the ResultActions. */
    protected ResultActions postVerb(String path, ObjectNode body) throws Exception {
        return mvc.perform(post("/api/v1/agents/" + path)
                .header("X-Persona-Label", OPERATOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()));
    }

    protected ObjectNode verbBody() {
        ObjectNode body = json.createObjectNode();
        body.put("idempotencyKey", key());
        return body;
    }

    protected ObjectNode reasonedBody(String reason) {
        ObjectNode body = verbBody();
        body.put("reason", reason);
        return body;
    }

    /** Records + APPROVES a PROMOTION review through the production endpoints. */
    protected void approvePromotionReview(String agentKey) throws Exception {
        ObjectNode review = json.createObjectNode();
        review.put("reviewKind", "promotion");
        review.put("requestedByLabel", POSITION_1);
        MvcResult recorded = postVerb(agentKey + "/reviews", review).andReturn();
        JsonNode envelope = json.readTree(recorded.getResponse().getContentAsByteArray());
        String reviewId = envelope.path("data").path("id").asText();
        ObjectNode decide = json.createObjectNode();
        decide.put("decision", "approved");
        decide.put("rationale", "review passed: fixture agent operating live");
        decide.put("reviewerLabel", POSITION_1);
        postVerb("reviews/" + reviewId + "/decide", decide)
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk());
    }
}
