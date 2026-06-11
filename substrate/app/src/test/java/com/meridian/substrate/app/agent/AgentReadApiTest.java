package com.meridian.substrate.app.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.enums.AgentInterventionTrigger;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.domain.enums.AgentRetiredReason;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.engine.agent.AgentActor;
import com.meridian.substrate.engine.agent.AgentInterventionService;
import com.meridian.substrate.engine.agent.AgentLifecycleService;
import com.meridian.substrate.engine.agent.AgentLivenessService;
import com.meridian.substrate.engine.agent.AgentMetricService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §7.1 read surface + §13.1 signals + §13.2/§13.3 attention over the
 * production endpoints (engine-spec agents.md; ADR-003 D4/D15; 6a
 * acceptance rows): fleet list with {@code retired} EXCLUDED by default
 * (pill-map crash mitigation), {@code {idOrKey}} resolution (UUID → key →
 * domain-scoped key), detail with deploymentCriteria + ordered oversight
 * chain (render rule: criteria are CLICK-THROUGH ONLY — the fleet row shape
 * carries NO {@code deploymentCriteria} field), signals with EXPLICIT ZEROS
 * on an empty domain (no-silent-zero-yield), and the composed
 * {@code GET /api/v1/attention} feed.
 *
 * <p>Fixture strings VERBATIM from
 * {@code meridian-homepoc/frontend/src/demo/beacon/subsidiaries/healthcare.ts}
 * (copied 2026-06-11). The RETIRED row is written via the repository — the
 * kill/retire verbs are 6b; reads must already honor the exclusion contract.
 *
 * <p>H2 dev-profile lane; Testcontainers-Postgres in CI is the persistence
 * acceptance gate, H2-green never is.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class AgentReadApiTest {

    private static final String OPERATOR = "Curasync RCM team";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AgentRegistrationRepository registrations;
    @Autowired AgentLifecycleService lifecycle;
    @Autowired AgentInterventionService interventions;
    @Autowired AgentMetricService metrics;
    @Autowired AgentLivenessService liveness;

    // ----------------------------------------------------------- §7.1 fleet

    @Test
    void fleetExcludesRetiredByDefaultAndFiltersOnWireStatus() throws Exception {
        UUID domainId = UUID.randomUUID();
        deploy(domainId, "a-pre-visit", "Pre-Visit Intake Assistant");
        deploy(domainId, "a-noshow", "No-Show Predictor");
        UUID noshowId = registrations.findByDomainIdAndAgentKey(domainId, "a-noshow")
                .orElseThrow().getId();
        // attention overlay: agent stays SHADOW underneath (two-dimensional model)
        interventions.open(noshowId, "t:" + UUID.randomUUID(),
                AgentActor.human(null, "Curasync Operations Lead"),
                "Drift detected — false-positive rate up 9 pts", null,
                AgentInterventionTrigger.HUMAN, null);
        retireViaProjection(domainId, "a-eligibility", "Eligibility Verifier");

        // default: retired EXCLUDED (ADR-003 D4 — the swapped pill map crashes on unknown values)
        mvc.perform(get("/api/v1/agents").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].id",
                        containsInAnyOrder("a-pre-visit", "a-noshow")))
                .andExpect(jsonPath("$.data[*].status",
                        containsInAnyOrder("shadow", "intervention-required")))
                // render rule: deploymentCriteria NEVER row-level ([AGENT-PROVENANCE])
                .andExpect(jsonPath("$.data[0].deploymentCriteria").doesNotExist())
                // provenance always renders post-swap — structural, not optional
                .andExpect(jsonPath("$.data[0].deployedBy", is(OPERATOR)))
                .andExpect(jsonPath("$.data[0].oversightChain", hasSize(2)))
                .andExpect(jsonPath("$.data[0].recentActivity", notNullValue()));

        // ?includeRetired=true is the opt-in contract
        mvc.perform(get("/api/v1/agents")
                        .param("domainId", domainId.toString())
                        .param("includeRetired", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[*].status",
                        containsInAnyOrder("shadow", "intervention-required", "retired")));

        // §5.2 wire-status filter, fixture vocabulary
        mvc.perform(get("/api/v1/agents")
                        .param("domainId", domainId.toString())
                        .param("status", "intervention-required"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id", is("a-noshow")))
                // mode stays the OTHER dimension: in intervention, still shadow underneath
                .andExpect(jsonPath("$.data[0].operationalMode", is("shadow")));
    }

    // ---------------------------------------------------- §7 idOrKey + detail

    @Test
    void idOrKeyResolvesUuidThenKeyAndDetailCarriesCriteriaAndOrderedChain() throws Exception {
        UUID domainId = UUID.randomUUID();
        deploy(domainId, "a-msg-router", "Patient Message Router");
        AgentRegistration reg = registrations
                .findByDomainIdAndAgentKey(domainId, "a-msg-router").orElseThrow();

        // UUID resolution
        mvc.perform(get("/api/v1/agents/" + reg.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id", is("a-msg-router")));

        // key resolution + the click-through detail surface
        mvc.perform(get("/api/v1/agents/a-msg-router"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.registrationId", is(reg.getId().toString())))
                .andExpect(jsonPath("$.data.deploymentCriteria",
                        is("Pre-fill intake forms from prior-visit data, target 90% pre-fill accuracy")))
                .andExpect(jsonPath("$.data.oversightChain[0]", is("Curasync RCM Lead")))
                .andExpect(jsonPath("$.data.oversightChain[1]", is("Curasync COO")))
                .andExpect(jsonPath("$.data.criteriaGates", hasSize(1)))
                .andExpect(jsonPath("$.data.deployedBy", is(OPERATOR)));

        // ambiguous unscoped key (same key, second domain) → 400 asking for scope
        UUID otherDomain = UUID.randomUUID();
        deploy(otherDomain, "a-msg-router", "Patient Message Router");
        mvc.perform(get("/api/v1/agents/a-msg-router"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/agents/a-msg-router").param("domainId", otherDomain.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.domainId", is(otherDomain.toString())));

        // §7.2: 404 AGENT_NOT_FOUND
        mvc.perform(get("/api/v1/agents/a-nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errors[0].code", is("AGENT_NOT_FOUND")));
    }

    // -------------------------------------------------- §7.3 derived fields

    @Test
    void recentActivityIsSightingFirstAndAdherenceIsLatestSample() throws Exception {
        UUID domainId = UUID.randomUUID();
        deploy(domainId, "a-pre-visit", "Pre-Visit Intake Assistant");
        UUID regId = registrations.findByDomainIdAndAgentKey(domainId, "a-pre-visit")
                .orElseThrow().getId();

        // before any sighting/sample: event-derived activity, NULL adherence (never fabricated)
        mvc.perform(get("/api/v1/agents/" + regId))
                .andExpect(jsonPath("$.data.adherence", nullValue()))
                .andExpect(jsonPath("$.data.recentActivity", notNullValue()));

        metrics.report(regId, AgentMetricService.METRIC_ADHERENCE, 96.0, "2026-04", null);
        liveness.recordAttestation(regId, "Curasync RCM Lead",
                "14,212 forms pre-filled this week");

        mvc.perform(get("/api/v1/agents/" + regId))
                .andExpect(jsonPath("$.data.adherence", is(96.0)))
                // sighting detail wins the §7.3 derivation chain, fixture string verbatim
                .andExpect(jsonPath("$.data.recentActivity", is("14,212 forms pre-filled this week")));
    }

    // --------------------------------------------------------- §13.1 signals

    @Test
    void signalsAnswerExplicitZerosOnAnEmptyDomain() throws Exception {
        mvc.perform(get("/api/v1/agents/signals").param("domainId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agentsTotal", is(0)))
                .andExpect(jsonPath("$.data.agentsLive", is(0)))
                .andExpect(jsonPath("$.data.agentsShadow", is(0)))
                .andExpect(jsonPath("$.data.agentsPaused", is(0)))
                .andExpect(jsonPath("$.data.agentsInterventionRequired", is(0)))
                .andExpect(jsonPath("$.data.interventions.open", is(0)))
                .andExpect(jsonPath("$.data.interventions.oldestOpenedAt", nullValue()))
                .andExpect(jsonPath("$.data.unconfirmedControls", hasSize(0)))
                .andExpect(jsonPath("$.data.controlViolations", hasSize(0)))
                .andExpect(jsonPath("$.data.liveness.stale", is(0)))
                .andExpect(jsonPath("$.data.liveness.neverSeen", is(0)))
                .andExpect(jsonPath("$.data.liveness.attestOnly", is(0)))
                .andExpect(jsonPath("$.data.promotionsPending", hasSize(0)))
                .andExpect(jsonPath("$.data.budgetsExhausted", hasSize(0)))
                .andExpect(jsonPath("$.data.gateCoverage.structured", is(0)))
                .andExpect(jsonPath("$.data.gateCoverage.unstructured", is(0)))
                .andExpect(jsonPath("$.data.gateCoverage.unevaluated", is(0)));
    }

    @Test
    void signalsCountTheProjectedFleetAndPauseOpensAnUnconfirmedControl() throws Exception {
        UUID domainId = UUID.randomUUID();
        deploy(domainId, "a-pre-visit", "Pre-Visit Intake Assistant");
        deploy(domainId, "a-msg-router", "Patient Message Router");
        UUID routerId = registrations.findByDomainIdAndAgentKey(domainId, "a-msg-router")
                .orElseThrow().getId();
        lifecycle.pause(routerId, "t:" + UUID.randomUUID(),
                AgentActor.human(null, "Curasync Front Office Manager"),
                "Paused 2026-04-19 pending Compliance review of PHI-routing change");

        mvc.perform(get("/api/v1/agents/signals").param("domainId", domainId.toString()))
                .andExpect(jsonPath("$.data.agentsTotal", is(2)))
                .andExpect(jsonPath("$.data.agentsShadow", is(1)))
                .andExpect(jsonPath("$.data.agentsPaused", is(1)))
                // honest control (ADR-003 D6): RECORDED_INTENT pause is PENDING until
                // runtime ack / human confirmation — sighting absence flips nothing
                .andExpect(jsonPath("$.data.unconfirmedControls", hasSize(1)))
                .andExpect(jsonPath("$.data.unconfirmedControls[0].agentKey", is("a-msg-router")))
                .andExpect(jsonPath("$.data.unconfirmedControls[0].verb", is("pause")))
                .andExpect(jsonPath("$.data.unconfirmedControls[0].confirmationState", is("pending")))
                // no telemetry channel (no principal, customer-system locus) → attest-only,
                // never stale-red (NOT_EXPECTED is not STALE)
                .andExpect(jsonPath("$.data.liveness.attestOnly", is(2)))
                .andExpect(jsonPath("$.data.liveness.stale", is(0)))
                // one STRUCTURED gate per deploy here, unevaluated until the eval pass —
                // degradation visible, never silent
                .andExpect(jsonPath("$.data.gateCoverage.structured", is(2)))
                .andExpect(jsonPath("$.data.gateCoverage.unevaluated", is(2)));
    }

    // ------------------------------------------- §13.2/§13.3 attention feeds

    @Test
    void composedAttentionMergesAgentItemsDestructiveFirst() throws Exception {
        UUID domainId = UUID.randomUUID();
        deploy(domainId, "a-noshow", "No-Show Predictor");
        deploy(domainId, "a-msg-router", "Patient Message Router");
        UUID noshowId = registrations.findByDomainIdAndAgentKey(domainId, "a-noshow")
                .orElseThrow().getId();
        UUID routerId = registrations.findByDomainIdAndAgentKey(domainId, "a-msg-router")
                .orElseThrow().getId();
        interventions.open(noshowId, "t:" + UUID.randomUUID(),
                AgentActor.human(null, "Curasync Operations Lead"),
                "Drift detected — false-positive rate up 9 pts", null,
                AgentInterventionTrigger.HUMAN, null);
        lifecycle.pause(routerId, "t:" + UUID.randomUUID(),
                AgentActor.human(null, "Curasync Front Office Manager"),
                "Paused 2026-04-19 pending Compliance review of PHI-routing change");

        // engine feed (§13.2): destructive first, then the two warn overlays
        mvc.perform(get("/api/v1/agents/attention").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].kind", is("agent-in-intervention")))
                .andExpect(jsonPath("$.data[0].tone", is("destructive")))
                .andExpect(jsonPath("$.data[0].agentKey", is("a-noshow")))
                .andExpect(jsonPath("$.data[1].tone", is("warn")))
                .andExpect(jsonPath("$.data[2].tone", is("warn")));

        // composed feed (§13.3): one list, agent + cascade sources, destructive first
        mvc.perform(get("/api/v1/attention").param("domainId", domainId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].source", is("agent")))
                .andExpect(jsonPath("$.data[0].kind", is("agent-in-intervention")))
                .andExpect(jsonPath("$.data[0].tone", is("destructive")))
                .andExpect(jsonPath("$.data[*].kind", containsInAnyOrder(
                        "agent-in-intervention", "agent-paused", "control-unconfirmed")));
    }

    // ------------------------------------------------------------- helpers

    private void deploy(UUID domainId, String agentKey, String name) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("idempotencyKey", UUID.randomUUID().toString());
        body.put("domainId", domainId.toString());
        body.put("agentKey", agentKey);
        body.put("name", name);
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
        chain.addObject().put("label", "Curasync RCM Lead");
        chain.addObject().put("label", "Curasync COO");
        body.put("supervisoryModel", "human-in-loop");
        mvc.perform(post("/api/v1/agents")
                        .header("X-Persona-Label", OPERATOR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated());
    }

    /**
     * Writes a RETIRED registration THROUGH THE PROJECTION (repository) —
     * the kill/retire verbs are 6b scope; the read contract (default
     * exclusion, opt-in include) must hold regardless of how a row reached
     * the terminal mode.
     */
    private void retireViaProjection(UUID domainId, String agentKey, String name) throws Exception {
        deploy(domainId, agentKey, name);
        AgentRegistration reg = registrations.findByDomainIdAndAgentKey(domainId, agentKey)
                .orElseThrow();
        reg.setOperationalMode(AgentOperationalMode.RETIRED);
        reg.setRetiredAt(Instant.parse("2026-04-01T00:00:00Z"));
        reg.setRetiredReason(AgentRetiredReason.RETIRED);
        reg.setRetiredByLabel(OPERATOR);
        registrations.save(reg);
    }
}
