package com.meridian.substrate.app.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.AgentStatusEvent;
import com.meridian.substrate.common.domain.DesignDecision;
import com.meridian.substrate.common.domain.Domain;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.common.repo.AgentStatusEventRepository;
import com.meridian.substrate.common.repo.DesignDecisionRepository;
import com.meridian.substrate.common.repo.DomainRepository;
import com.meridian.substrate.engine.decision.log.DecisionLogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stable actor identity under oidc (port-map S9 "hash-chain actor identity
 * becomes IAM-compatible stable user id"; ADR-001 D11 / ADR-003 D14): an agent
 * deploy driven through the production REST endpoint with a verified JWT must
 * record the token {@code sub} — NOT any header value — as the actor:
 *
 * <ul>
 *   <li>{@code agent_status_events.actor_member_id} = {@code sub} (UUID-shaped
 *       subs, the Keycloak case, bind as stable member identity);</li>
 *   <li>{@code actor_label} = the display-name claim;</li>
 *   <li>the D14 hash-chain anchor's canonical metadata carries
 *       {@code actorMemberId} = {@code sub}, and the chain verifies green —
 *       proving the existing actor fields accept the sub string with NO
 *       additive columns.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "substrate.auth.mode=oidc",
        "spring.datasource.url=jdbc:h2:mem:oidc_actor;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class OidcActorIdentityTest {

    /** Keycloak-shaped subject: a stable UUID per user. */
    private static final String SUB = "7d3f2a9c-5b1e-4c8d-9f6a-2e4b8c1d0a55";
    private static final String DISPLAY_NAME = "Jane Operator";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DomainRepository domainRepository;
    @Autowired AgentRegistrationRepository registrations;
    @Autowired AgentStatusEventRepository statusEvents;
    @Autowired DesignDecisionRepository designDecisions;
    @Autowired DecisionLogService decisionLogService;

    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void agentDeployRecordsTheTokenSubAsActorOnEventAndHashChainAnchor() throws Exception {
        Domain domain = new Domain();
        domain.setName("OIDC actor identity " + UUID.randomUUID());
        domain.setCreatedBy("test");
        UUID domainId = domainRepository.save(domain).getId();
        String agentKey = "t:oidc-actor-" + UUID.randomUUID();

        mvc.perform(post("/api/v1/agents")
                        // Spoofed persona headers ride along to prove they are inert.
                        .header("X-Persona-Id", UUID.randomUUID().toString())
                        .header("X-Persona-Label", "Spoofed Label")
                        .with(jwt().jwt(j -> j.subject(SUB)
                                .claim("name", DISPLAY_NAME)
                                .claim("roles", List.of("substrate-operator"))))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deployBody(domainId, agentKey).toString()))
                .andExpect(status().isCreated());

        AgentRegistration registration =
                registrations.findByDomainIdAndAgentKey(domainId, agentKey).orElseThrow();

        // 1. The append-only status event binds the IAM-stable actor id + label.
        List<AgentStatusEvent> events =
                statusEvents.findByAgentRegistrationIdOrderByEventSeqAsc(registration.getId());
        assertThat(events).isNotEmpty();
        AgentStatusEvent deployed = events.get(0);
        assertThat(deployed.getActorMemberId())
                .as("actor_member_id = the verified token sub (ADR-001 D11)")
                .isEqualTo(UUID.fromString(SUB));
        assertThat(deployed.getActorLabel()).isEqualTo(DISPLAY_NAME);

        // 2. The D14 hash-chain anchor carries the sub in its canonical metadata …
        DesignDecision anchor = designDecisions.findByDomainId(domainId).stream()
                .filter(d -> ("agent:" + agentKey + ":AGENT_DEPLOYED").equals(d.getTitle()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("AGENT_DEPLOYED chain anchor missing"));
        assertThat(anchor.getMetadata().path("actorMemberId").asText())
                .as("hash-chain actor identity = OIDC sub (S9 severed)")
                .isEqualTo(SUB);

        // … and the chain it joined verifies green (existing fields, no schema change).
        assertThat(decisionLogService.verifyChain(domainId).valid()).isTrue();
    }

    /** Canonical 6b deploy body (AgentVerbTestSupport vocabulary). */
    private ObjectNode deployBody(UUID domainId, String agentKey) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("idempotencyKey", "t:" + UUID.randomUUID());
        body.put("domainId", domainId.toString());
        body.put("agentKey", agentKey);
        body.put("name", "Pre-Visit Intake Assistant");
        body.put("roleDescription", "Pre-fills intake forms from prior visits");
        body.put("executionLocus", "customer-system");
        body.put("deployedByLabel", "Curasync RCM team");
        body.put("deploymentCriteria",
                "Pre-fill intake forms from prior-visit data, target 90% pre-fill accuracy");
        ObjectNode gate = body.putArray("criteriaGates").addObject();
        gate.put("gateText", "target 90% pre-fill accuracy");
        gate.put("ruleType", "structured");
        gate.set("predicate", json.readTree("{\"metric\":\"prefill_accuracy\",\"gte\":0.90}"));
        gate.put("cadence", "MONTHLY");
        var chain = body.putArray("oversightChain");
        chain.addObject().put("label", "Curasync RCM Lead");
        chain.addObject().put("label", "Curasync COO");
        body.put("supervisoryModel", "human-in-loop");
        return body;
    }
}
