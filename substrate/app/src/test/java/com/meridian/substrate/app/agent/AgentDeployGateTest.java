package com.meridian.substrate.app.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.AgentStatusEvent;
import com.meridian.substrate.common.domain.enums.AgentEventType;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.domain.enums.ContractActorType;
import com.meridian.substrate.common.repo.AgentCriteriaGateRepository;
import com.meridian.substrate.common.repo.AgentOversightLinkRepository;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.common.repo.AgentStatusEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §5.4 deploy gate over the production {@code POST /api/v1/agents} endpoint
 * (engine-spec agents.md §5.4/§5.1 M1–M2; test plan #1 deploy slice; 6a
 * acceptance: "deploy missing oversightChain → 400
 * {@code AGENT_PROVENANCE_MISSING_OVERSIGHT}"). Per-field STRUCTURED 400
 * codes; born-SHADOW default; LIVE only with justification; duplicate key →
 * 409 {@code AGENT_KEY_CONFLICT} with current detail in {@code data};
 * idempotent replay → 200, no new rows; provenance facts + frozen mandate +
 * ordered gates + DEPLOYED event (seq 1, full snapshot payload) in one
 * transaction.
 *
 * <p>Test strings are the polished Curasync {@code a-pre-visit} fixture rows
 * VERBATIM from
 * {@code C:/Users/mbgol/dev/meridian-homepoc/frontend/src/demo/beacon/subsidiaries/healthcare.ts}
 * (copied 2026-06-11 — contracts §14.1 discipline; agents.ts does NOT exist).
 *
 * <p>H2 dev-profile lane; Testcontainers-Postgres in CI is the acceptance
 * gate (PostgresAgentPersistenceIT), H2-green never is.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class AgentDeployGateTest {

    private static final String OPERATOR = "Curasync RCM team";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AgentRegistrationRepository registrations;
    @Autowired AgentOversightLinkRepository oversightLinks;
    @Autowired AgentStatusEventRepository events;
    @Autowired AgentCriteriaGateRepository gates;

    // ----------------------------------------------------------- M1: deploy

    @Test
    void deployBornShadowWithProvenanceGatesAndDeployedEvent() throws Exception {
        UUID domainId = UUID.randomUUID();

        deploy(baseRequest(domainId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.id", is("a-pre-visit")))
                .andExpect(jsonPath("$.data.registrationId", notNullValue()))
                .andExpect(jsonPath("$.data.name", is("Pre-Visit Intake Assistant")))
                .andExpect(jsonPath("$.data.role", is("Pre-fills intake forms from prior visits")))
                // born SHADOW (P11 observation-only trial), fixture wire vocabulary
                .andExpect(jsonPath("$.data.status", is("shadow")))
                .andExpect(jsonPath("$.data.operationalMode", is("shadow")))
                .andExpect(jsonPath("$.data.shadowEnteredAt", notNullValue()))
                // provenance always present post-gate ([AGENT-PROVENANCE])
                .andExpect(jsonPath("$.data.deployedBy", is("Curasync RCM team")))
                .andExpect(jsonPath("$.data.deployedAt", notNullValue()))   // server clock = the act
                .andExpect(jsonPath("$.data.deploymentCriteria",
                        is("Pre-fill intake forms from prior-visit data, target 90% pre-fill accuracy")))
                .andExpect(jsonPath("$.data.oversightChain[0]", is("Curasync RCM Lead")))
                .andExpect(jsonPath("$.data.oversightChain[1]", is("Curasync COO")))
                // spec-unification + safety defaults as data
                .andExpect(jsonPath("$.data.executionLocus", is("customer-system")))
                .andExpect(jsonPath("$.data.supervisoryModel", is("human-in-loop")))
                .andExpect(jsonPath("$.data.readOnly", is(true)))
                // frozen ordered gates, standing UNEVALUATED until evaluation lands
                .andExpect(jsonPath("$.data.criteriaGates", hasSize(1)))
                .andExpect(jsonPath("$.data.criteriaGates[0].gateText", is("target 90% pre-fill accuracy")))
                .andExpect(jsonPath("$.data.criteriaGates[0].ruleType", is("structured")))
                .andExpect(jsonPath("$.data.criteriaGates[0].standing", is("unevaluated")))
                // adherence is NULLABLE — null until metric data exists (never fabricated)
                .andExpect(jsonPath("$.data.adherence", nullValue()))
                .andExpect(jsonPath("$.data.eventSeq", is(1)))
                .andExpect(jsonPath("$.data.openInterventions", is(0)))
                .andExpect(jsonPath("$.data.recentActivity", notNullValue()));

        AgentRegistration reg = registrations.findByDomainIdAndAgentKey(domainId, "a-pre-visit").orElseThrow();
        assertThat(reg.getOperationalMode()).isEqualTo(AgentOperationalMode.SHADOW);
        assertThat(reg.getCriteriaDigest()).matches("[0-9a-f]{64}");
        assertThat(reg.getEventSeq()).isEqualTo(1);

        // DEPLOYED event: seq 1, HUMAN actor, full frozen spec snapshot payload (I2/I3)
        assertThat(events.findByAgentRegistrationIdOrderByEventSeqAsc(reg.getId()))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getEventType()).isEqualTo(AgentEventType.DEPLOYED);
                    assertThat(e.getEventSeq()).isEqualTo(1);
                    assertThat(e.getActorType()).isEqualTo(ContractActorType.HUMAN);
                    assertThat(e.getActorLabel()).isEqualTo(OPERATOR);
                    assertThat(e.getPayload().get("criteriaDigest").asText())
                            .isEqualTo(reg.getCriteriaDigest());
                    assertThat(e.getPayload().get("deploymentCriteria").asText())
                            .isEqualTo(reg.getDeploymentCriteria());
                });
        assertThat(oversightLinks.findByAgentRegistrationIdOrderByPositionAsc(reg.getId()))
                .extracting(l -> l.getPosition())
                .containsExactly(1, 2);
        assertThat(gates.findByAgentRegistrationIdOrderBySequenceOrderAsc(reg.getId())).hasSize(1);
    }

    // ------------------------------------------- M2: deploy-to-LIVE justified

    @Test
    void deployWithLiveJustificationLandsLive() throws Exception {
        ObjectNode body = baseRequest(UUID.randomUUID());
        body.put("liveJustification",
                "Operating live at the customer since 2025-09-14; registry backfill (seed provenance)");

        deploy(body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status", is("live")))
                .andExpect(jsonPath("$.data.operationalMode", is("live")))
                .andExpect(jsonPath("$.data.shadowEnteredAt", nullValue()));
    }

    // --------------------------------------- §5.4 per-field structured 400s

    @Test
    void missingDeployedByYields400ProvenanceMissingDeployedBy() throws Exception {
        ObjectNode body = baseRequest(UUID.randomUUID());
        body.remove("deployedByLabel");
        expectGateCode(deploy(body), "AGENT_PROVENANCE_MISSING_DEPLOYED_BY", "deployedByLabel");
    }

    @Test
    void missingCriteriaYields400ProvenanceMissingCriteria() throws Exception {
        ObjectNode body = baseRequest(UUID.randomUUID());
        body.put("deploymentCriteria", "   ");
        expectGateCode(deploy(body), "AGENT_PROVENANCE_MISSING_CRITERIA", "deploymentCriteria");
    }

    /** The named 6a acceptance beat (spec §16.1). */
    @Test
    void missingOversightChainYields400ProvenanceMissingOversight() throws Exception {
        ObjectNode body = baseRequest(UUID.randomUUID());
        body.remove("oversightChain");
        expectGateCode(deploy(body), "AGENT_PROVENANCE_MISSING_OVERSIGHT", "oversightChain");

        ObjectNode emptyChain = baseRequest(UUID.randomUUID());
        emptyChain.putArray("oversightChain");
        expectGateCode(deploy(emptyChain), "AGENT_PROVENANCE_MISSING_OVERSIGHT", "oversightChain");
    }

    @Test
    void missingSupervisoryModelYields400SpecMissingSupervisoryModel() throws Exception {
        ObjectNode body = baseRequest(UUID.randomUUID());
        body.remove("supervisoryModel");
        expectGateCode(deploy(body), "AGENT_SPEC_MISSING_SUPERVISORY_MODEL", "supervisoryModel");
    }

    @Test
    void missingExecutionLocusYields400SpecMissingExecutionLocus() throws Exception {
        ObjectNode body = baseRequest(UUID.randomUUID());
        body.remove("executionLocus");
        expectGateCode(deploy(body), "AGENT_SPEC_MISSING_EXECUTION_LOCUS", "executionLocus");
    }

    @Test
    void readinessScoreAndSourceMustTravelTogether() throws Exception {
        ObjectNode scoreOnly = baseRequest(UUID.randomUUID());
        scoreOnly.put("agentReadinessScore", 72);
        expectGateCode(deploy(scoreOnly), "AGENT_SPEC_MISSING_READINESS_SOURCE", "readinessSource");

        ObjectNode sourceOnly = baseRequest(UUID.randomUUID());
        sourceOnly.put("readinessSource", "operator-asserted");
        expectGateCode(deploy(sourceOnly), "AGENT_SPEC_READINESS_SOURCE_WITHOUT_SCORE", "readinessSource");
    }

    @Test
    void gatePredicatePresenceMustMatchRuleType() throws Exception {
        // STRUCTURED without predicate → flagged, not silently weakened
        ObjectNode structuredBare = baseRequest(UUID.randomUUID());
        ((ObjectNode) structuredBare.withArray("criteriaGates").get(0)).remove("predicate");
        expectGateCode(deploy(structuredBare), "AGENT_SPEC_GATE_PREDICATE_MISMATCH",
                "criteriaGates[0].predicate");

        // UNSTRUCTURED with predicate → §9.1 "predicate NULL iff UNSTRUCTURED"
        ObjectNode unstructuredWithPredicate = baseRequest(UUID.randomUUID());
        ObjectNode gate = (ObjectNode) unstructuredWithPredicate.withArray("criteriaGates").get(0);
        gate.put("ruleType", "unstructured");
        expectGateCode(deploy(unstructuredWithPredicate), "AGENT_SPEC_GATE_PREDICATE_MISMATCH",
                "criteriaGates[0].predicate");
    }

    @Test
    void missingIdempotencyKeyIsABeanValidation400() throws Exception {
        ObjectNode body = baseRequest(UUID.randomUUID());
        body.remove("idempotencyKey");
        deploy(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code", is("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.errors[0].field", is("idempotencyKey")));
    }

    // ------------------------------------ key conflict + idempotent replay

    @Test
    void duplicateAgentKeyYields409KeyConflictWithCurrentDetailInData() throws Exception {
        UUID domainId = UUID.randomUUID();
        deploy(baseRequest(domainId)).andExpect(status().isCreated());

        // same (domain, agent_key), DIFFERENT idempotency key → conflict, current detail in data
        ObjectNode second = baseRequest(domainId);
        second.put("idempotencyKey", UUID.randomUUID().toString());
        deploy(second)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("AGENT_KEY_CONFLICT")))
                .andExpect(jsonPath("$.data.id", is("a-pre-visit")))
                .andExpect(jsonPath("$.data.status", is("shadow")));

        // same key in ANOTHER domain is legal — uniqueness is per (domain, agent_key)
        deploy(baseRequest(UUID.randomUUID())).andExpect(status().isCreated());
    }

    @Test
    void idempotentReplayReturns200WithoutNewRows() throws Exception {
        UUID domainId = UUID.randomUUID();
        ObjectNode body = baseRequest(domainId);
        deploy(body).andExpect(status().isCreated());

        UUID registrationId = registrations
                .findByDomainIdAndAgentKey(domainId, "a-pre-visit").orElseThrow().getId();
        long eventsAfterFirst = events.countByAgentRegistrationId(registrationId);

        // replay of the SAME idempotencyKey → 200, original result re-projected (cascade D8)
        deploy(body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.id", is("a-pre-visit")))
                .andExpect(jsonPath("$.data.eventSeq", is(1)));

        assertThat(events.countByAgentRegistrationId(registrationId))
                .as("idempotent replay must not append an event row")
                .isEqualTo(eventsAfterFirst);
        assertThat(registrations.findByDomainId(domainId)).hasSize(1);
    }

    // ------------------------------------------------------------- helpers

    private ResultActions deploy(ObjectNode body) throws Exception {
        return mvc.perform(post("/api/v1/agents")
                .header("X-Persona-Label", OPERATOR)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()));
    }

    private static void expectGateCode(ResultActions actions, String code, String field) throws Exception {
        actions.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].code", is(code)))
                .andExpect(jsonPath("$.errors[0].field", is(field)))
                .andExpect(jsonPath("$.data", nullValue()));
    }

    /**
     * The polished Curasync a-pre-visit deploy spec, fixture strings VERBATIM
     * (healthcare.ts, copied 2026-06-11); STRUCTURED gate per spec §9.1's
     * worked example.
     */
    private ObjectNode baseRequest(UUID domainId) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("idempotencyKey", UUID.randomUUID().toString());
        body.put("domainId", domainId.toString());
        body.put("agentKey", "a-pre-visit");
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
