package com.meridian.substrate.app.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.domain.enums.AgentRetiredReason;
import com.meridian.substrate.common.domain.enums.ContractActorType;
import com.meridian.substrate.common.repo.AgentStatusEventRepository;
import com.meridian.substrate.engine.agent.AgentActor;
import com.meridian.substrate.engine.agent.AgentControlConfirmationService;
import com.meridian.substrate.engine.agent.AgentLifecycleService;
import com.meridian.substrate.engine.agent.exception.AgentForbiddenException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §16.2 test plan <b>#1 (state-machine tables) + #4 (idempotency + fencing)</b>
 * over the production §7.2 endpoints (engine-spec agents.md §5.1; ADR-003
 * D1/D4/D10): every legal M-row drives a real verb; every illegal combination
 * answers its spec code (verbs on RETIRED → {@code AGENT_TERMINAL}; resume
 * when not paused; promote without/with-consumed review; second deploy on the
 * same key → 409; resolve without note; reason-mandatory CHECKs;
 * DEMOTED_TO_SHADOW resets {@code shadow_entered_at}); same-key replay
 * answers the ORIGINAL 200 with no new event; a stale {@code expectedEventSeq}
 * answers 409 {@code AGENT_STALE_STATE} with the current detail in
 * {@code data}.
 *
 * <p>H2 dev-profile lane; Testcontainers-Postgres in CI is the persistence
 * acceptance gate ({@code PostgresAgentConcurrencyIT}), H2-green never is.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class AgentVerbStateMachineTest extends AgentVerbTestSupport {

    @Autowired
    AgentStatusEventRepository events;
    @Autowired
    AgentLifecycleService lifecycle;
    @Autowired
    AgentControlConfirmationService controlConfirmation;

    // ------------------------------------------------------- M6/M7 pause/resume

    @Test
    void pauseRecordsModeBeforePauseAndResumeReturnsThere() throws Exception {
        UUID domainId = UUID.randomUUID();
        AgentRegistration reg = deploy(domainId, "a-pre-visit");
        approvePromotionReview("a-pre-visit");
        postVerb("a-pre-visit/promote", verbBody())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("live")));

        postVerb(reg.getId() + "/pause", reasonedBody("Paused pending Compliance review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("paused")));
        // already paused → M6 guard
        postVerb(reg.getId() + "/pause", reasonedBody("again"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("AGENT_ILLEGAL_TRANSITION")));

        postVerb(reg.getId() + "/resume", verbBody())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("live")))            // mode_before_pause
                .andExpect(jsonPath("$.data.agent.enforcement.pendingVerb", nullValue()));
        // resume when not paused → M7 guard
        postVerb(reg.getId() + "/resume", verbBody())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("AGENT_ILLEGAL_TRANSITION")));
    }

    @Test
    void pauseWithoutReasonIsRejected() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-pre-visit");
        postVerb(reg.getId() + "/pause", verbBody()).andExpect(status().isBadRequest());
        postVerb(reg.getId() + "/kill", verbBody()).andExpect(status().isBadRequest());
        postVerb(reg.getId() + "/retire", verbBody()).andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------- M9/M10 terminal

    @Test
    void killIsTerminalAbsorbingAndExcludedFromTheDefaultFleet() throws Exception {
        UUID domainId = UUID.randomUUID();
        AgentRegistration reg = deploy(domainId, "a-pre-visit");
        deploy(domainId, "a-eligibility");

        postVerb(reg.getId() + "/kill", reasonedBody("Emergency stop — PHI exposure suspected"))
                .andExpect(status().isOk())                  // NEVER error-shaped (I5)
                .andExpect(jsonPath("$.data.status", is("retired")))
                .andExpect(jsonPath("$.data.agent.retired.reason", is("killed")))
                .andExpect(jsonPath("$.data.agent.retired.byLabel", is(OPERATOR)));

        // terminal absorption: ANY further verb conflicts
        postVerb(reg.getId() + "/pause", reasonedBody("too late"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("AGENT_TERMINAL")));
        postVerb(reg.getId() + "/promote", verbBody())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("AGENT_TERMINAL")));

        // default fleet exclusion + the ?includeRetired opt-in (ADR-003 D4)
        mvc.perform(get("/api/v1/agents").param("domainId", domainId.toString()))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id", is("a-eligibility")));
        mvc.perform(get("/api/v1/agents")
                        .param("domainId", domainId.toString())
                        .param("includeRetired", "true"))
                .andExpect(jsonPath("$.data", hasSize(2)));
    }

    @Test
    void retireRecordsTheOrderlyReason() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-pre-visit");
        postVerb(reg.getId() + "/retire", reasonedBody("Superseded by v2 — redeploying"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agent.retired.reason", is("retired")));
        assertThat(registrations.findById(reg.getId()).orElseThrow().getRetiredReason())
                .isEqualTo(AgentRetiredReason.RETIRED);
    }

    // ------------------------------------------------------ M3/M4/M5 promotion

    @Test
    void promotionIsGatedOnASingleUseApprovedReview() throws Exception {
        UUID domainId = UUID.randomUUID();
        AgentRegistration reg = deploy(domainId, "a-coding");

        // M4 without a review → 409 PROMOTION_REVIEW_REQUIRED + current detail in data
        postVerb("a-coding/promote", verbBody())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("PROMOTION_REVIEW_REQUIRED")))
                .andExpect(jsonPath("$.data.status", is("shadow")));

        // M3 request (mode stays SHADOW; payload = shadow-window summary)
        postVerb("a-coding/promote/request", verbBody())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("shadow")))
                .andExpect(jsonPath("$.data.agent.promotionRequestedAt", notNullValue()));
        // no second open request (M3 guard)
        postVerb("a-coding/promote/request", verbBody())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("AGENT_ILLEGAL_TRANSITION")));

        approvePromotionReview("a-coding");
        postVerb("a-coding/promote", verbBody())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("live")))
                .andExpect(jsonPath("$.data.agent.promotion.latestReview.consumedByEventId",
                        notNullValue()));

        // single-use: demote back to shadow, the CONSUMED review gates nothing
        ObjectNode open = reasonedBody("Drift detected — false-positive rate up 9 pts");
        postVerb("a-coding/interventions", open).andExpect(status().isCreated());
        String interventionId = json.readTree(mvc.perform(
                        get("/api/v1/agents/a-coding").param("domainId", domainId.toString()))
                .andReturn().getResponse().getContentAsByteArray())
                .path("data").path("interventions").path(0).path("id").asText();
        ObjectNode resolve = verbBody();
        resolve.put("resolution", "demoted-to-shadow");
        resolve.put("resolutionNote", "Back to observation while drift is investigated");
        postVerb("interventions/" + interventionId + "/resolve", resolve)
                .andExpect(status().isOk());
        postVerb("a-coding/promote", verbBody())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("PROMOTION_REVIEW_REQUIRED")));
    }

    @Test
    void rejectedReviewIsInfoTonedClosesTheRequestAndStaysReRequestable() throws Exception {
        UUID domainId = UUID.randomUUID();
        deploy(domainId, "a-coding");
        postVerb("a-coding/promote/request", verbBody()).andExpect(status().isOk());

        ObjectNode review = json.createObjectNode();
        review.put("reviewKind", "promotion");
        review.put("requestedByLabel", POSITION_1);
        String reviewId = json.readTree(postVerb("a-coding/reviews", review)
                        .andExpect(status().isCreated()).andReturn()
                        .getResponse().getContentAsByteArray())
                .path("data").path("id").asText();
        ObjectNode decide = json.createObjectNode();
        decide.put("decision", "rejected");
        decide.put("rationale", "shadow window too short — needs another month of telemetry");
        decide.put("reviewerLabel", POSITION_1);
        // M5: 200, info/neutral — NEVER an error shape
        postVerb("reviews/" + reviewId + "/decide", decide)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.decision", is("rejected")));

        // the open request closed (re-requestable) + PROMOTION_REJECTED evented
        mvc.perform(get("/api/v1/agents/a-coding").param("domainId", domainId.toString()))
                .andExpect(jsonPath("$.data.promotionRequestedAt", nullValue()));
        postVerb("a-coding/promote/request", verbBody()).andExpect(status().isOk());
        mvc.perform(get("/api/v1/agents/a-coding/events")
                        .param("domainId", domainId.toString()))
                .andExpect(jsonPath("$.data[2].eventType", is("promotion-rejected")));
    }

    // ----------------------------------------------------- M11–M13 interventions

    @Test
    void interventionLifecycleKeepsTheModeAndDemotedToShadowResetsTheWindow() throws Exception {
        UUID domainId = UUID.randomUUID();
        AgentRegistration reg = deploy(domainId, "a-noshow");
        approvePromotionReview("a-noshow");
        postVerb("a-noshow/promote", verbBody()).andExpect(status().isOk());
        java.time.Instant shadowBefore =
                registrations.findById(reg.getId()).orElseThrow().getShadowEnteredAt();

        postVerb("a-noshow/interventions",
                reasonedBody("Drift detected — false-positive rate up 9 pts"))
                .andExpect(status().isCreated());
        // two-dimensional model: intervention-required on the wire, LIVE underneath
        mvc.perform(get("/api/v1/agents/a-noshow").param("domainId", domainId.toString()))
                .andExpect(jsonPath("$.data.status", is("intervention-required")))
                .andExpect(jsonPath("$.data.operationalMode", is("live")));

        String interventionId = json.readTree(mvc.perform(
                        get("/api/v1/agents/a-noshow").param("domainId", domainId.toString()))
                .andReturn().getResponse().getContentAsByteArray())
                .path("data").path("interventions").path(0).path("id").asText();

        postVerb("interventions/" + interventionId + "/acknowledge", verbBody())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acknowledgedBy", is(OPERATOR)));

        // resolve without a note → M13 mandatory
        ObjectNode noNote = verbBody();
        noNote.put("resolution", "demoted-to-shadow");
        postVerb("interventions/" + interventionId + "/resolve", noNote)
                .andExpect(status().isBadRequest());

        ObjectNode resolve = verbBody();
        resolve.put("resolution", "demoted-to-shadow");
        resolve.put("resolutionNote", "Demoted while the drift is investigated");
        postVerb("interventions/" + interventionId + "/resolve", resolve)
                .andExpect(status().isOk());
        AgentRegistration after = registrations.findById(reg.getId()).orElseThrow();
        assertThat(after.getOperationalMode()).isEqualTo(AgentOperationalMode.SHADOW);
        assertThat(after.getOpenInterventionCount()).isZero();
        assertThat(after.getShadowEnteredAt()).isNotEqualTo(shadowBefore);   // window reset

        // SAME key replays the original 200 (cascade D8)…
        postVerb("interventions/" + interventionId + "/resolve", resolve)
                .andExpect(status().isOk());
        // …a FRESH key on an already-resolved intervention conflicts (M13 guard)
        ObjectNode again = verbBody();
        again.put("resolution", "no-action");
        again.put("resolutionNote", "second resolution attempt");
        postVerb("interventions/" + interventionId + "/resolve", again)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("AGENT_ILLEGAL_TRANSITION")));
    }

    // ----------------------------------------------- M8/M14/M15/M18 remaining

    @Test
    void throttleReplacesPerKindAndUnthrottleClears() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-pre-visit");
        ObjectNode throttle = verbBody();
        throttle.put("budgetKind", "actions-per-window");
        throttle.put("limitValue", 100.0);
        throttle.put("windowMinutes", 60);
        postVerb(reg.getId() + "/throttle", throttle)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agent.budgets", hasSize(1)))
                .andExpect(jsonPath("$.data.agent.budgets[0].budgetKind", is("ACTIONS_PER_WINDOW")));

        ObjectNode tighter = verbBody();
        tighter.put("budgetKind", "ACTIONS_PER_WINDOW");
        tighter.put("limitValue", 50.0);
        tighter.put("windowMinutes", 60);
        postVerb(reg.getId() + "/throttle", tighter)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agent.budgets", hasSize(1)))     // replaced, not stacked
                .andExpect(jsonPath("$.data.agent.budgets[0].limitValue", is(50.0)));

        ObjectNode invalid = verbBody();
        invalid.put("budgetKind", "REQUESTS_PER_FORTNIGHT");
        postVerb(reg.getId() + "/throttle", invalid).andExpect(status().isBadRequest());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/agents/" + reg.getId() + "/throttle/ACTIONS_PER_WINDOW")
                        .header("X-Persona-Label", OPERATOR)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(verbBody().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agent.budgets", hasSize(0)));
        // clearing an absent budget → conflict
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/agents/" + reg.getId() + "/throttle/ACTIONS_PER_WINDOW")
                        .header("X-Persona-Label", OPERATOR)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(verbBody().toString()))
                .andExpect(status().isConflict());
    }

    @Test
    void oversightReplacementEventsBeforeAndAfterChains() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-pre-visit");
        ObjectNode body = verbBody();
        var chain = body.putArray("chain");
        chain.addObject().put("label", "Curasync Compliance Officer");
        postVerb(reg.getId() + "/oversight", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agent.oversightChain", hasSize(1)))
                .andExpect(jsonPath("$.data.agent.oversightChain[0]",
                        is("Curasync Compliance Officer")));
        var changed = events.findByAgentRegistrationIdOrderByEventSeqAsc(reg.getId());
        var payload = changed.get(changed.size() - 1).getPayload();
        assertThat(payload.path("before").path(0).path("label").asText()).isEqualTo(POSITION_1);
        assertThat(payload.path("after").path(0).path("label").asText())
                .isEqualTo("Curasync Compliance Officer");
        // empty replacement chain → 400 (I1: >=1 link, always)
        postVerb(reg.getId() + "/oversight", verbBody()).andExpect(status().isBadRequest());
    }

    @Test
    void attestLivenessEventsAndUpdatesTheLivenessBlock() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-pre-visit");
        ObjectNode body = verbBody();
        body.put("statement", "Verified running in the Curasync prod tenant");
        postVerb(reg.getId() + "/attest-liveness", body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agent.liveness.lastAttestedBy", is(OPERATOR)))
                .andExpect(jsonPath("$.data.agent.liveness.lastAttestedAt", notNullValue()))
                .andExpect(jsonPath("$.data.agent.liveness.lastSeenVia", is("attestation")))
                // CUSTOMER_SYSTEM without binding: attest-only, never a green dot
                .andExpect(jsonPath("$.data.agent.liveness.observability", is("attest-only")));
    }

    @Test
    void principalBindIsUniqueAndRevocable() throws Exception {
        UUID domainId = UUID.randomUUID();
        AgentRegistration a = deploy(domainId, "a-pre-visit");
        AgentRegistration b = deploy(domainId, "a-eligibility");
        ObjectNode bind = verbBody();
        bind.put("mcpPrincipalId", "curasync-previsit-runtime");
        postVerb(a.getId() + "/principal", bind)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agent.mcpPrincipalId", is("curasync-previsit-runtime")))
                // a bound principal IS a telemetry channel (§10)
                .andExpect(jsonPath("$.data.agent.liveness.observability", is("observable")));
        ObjectNode duplicate = verbBody();
        duplicate.put("mcpPrincipalId", "curasync-previsit-runtime");
        postVerb(b.getId() + "/principal", duplicate).andExpect(status().isBadRequest());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/agents/" + a.getId() + "/principal")
                        .header("X-Persona-Label", OPERATOR)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(verbBody().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.agent.mcpPrincipalId", nullValue()));
    }

    // ------------------------------------------------- #4 idempotency + fencing

    @Test
    void sameIdempotencyKeyReplaysTheOriginalWithNoNewEvent() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-msg-router");
        ObjectNode body = reasonedBody("Paused 2026-04-19 pending Compliance review");
        postVerb(reg.getId() + "/pause", body).andExpect(status().isOk());
        long before = events.countByAgentRegistrationId(reg.getId());
        postVerb(reg.getId() + "/pause", body)                  // SAME key — replay
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("paused")));
        assertThat(events.countByAgentRegistrationId(reg.getId())).isEqualTo(before);
    }

    @Test
    void sameIdempotencyKeyOnADifferentVerbIsA409Conflict_neverA500() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-msg-router");
        ObjectNode pause = reasonedBody("Paused pending Compliance review");
        postVerb(reg.getId() + "/pause", pause).andExpect(status().isOk());
        long before = events.countByAgentRegistrationId(reg.getId());

        // SAME key reused on a DIFFERENT verb: a contradiction — conflicts as
        // AGENT_IDEMPOTENCY_REUSE (pre-fix this proceeded to appendEvent and
        // died on uq_ase_idem at flush as a raw 500).
        ObjectNode resume = verbBody();   // resume takes no reason field
        resume.put("idempotencyKey", pause.get("idempotencyKey").asText());
        postVerb(reg.getId() + "/resume", resume)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("AGENT_IDEMPOTENCY_REUSE")));
        assertThat(events.countByAgentRegistrationId(reg.getId())).isEqualTo(before);
    }

    @Test
    void staleExpectedEventSeqAnswers409WithTheCurrentDetailInData() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-msg-router");
        ObjectNode stale = reasonedBody("racing operator");
        stale.put("expectedEventSeq", 99);
        postVerb(reg.getId() + "/pause", stale)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].code", is("AGENT_STALE_STATE")))
                .andExpect(jsonPath("$.data.eventSeq", is(1)))   // refresh-from-envelope contract
                .andExpect(jsonPath("$.data.status", is("shadow")));
        // the correct token passes
        ObjectNode fresh = reasonedBody("Paused pending Compliance review");
        fresh.put("expectedEventSeq", 1);
        postVerb(reg.getId() + "/pause", fresh).andExpect(status().isOk());
    }

    // ----------------------------------------------------------------- I11

    @Test
    void lifecycleVerbsRejectAgentShapedActors() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-pre-visit");
        assertThatThrownBy(() -> lifecycle.pause(reg.getId(), key(),
                new AgentActor(null, "rogue-runtime", ContractActorType.AGENT), "self-pause"))
                .isInstanceOf(AgentForbiddenException.class);
        // …while M17 acknowledge_control is the ONE agent-shaped write (after a pause)
        lifecycle.pause(reg.getId(), key(), AgentActor.human(null, OPERATOR), "operator pause");
        controlConfirmation.acknowledgeControl(reg.getId(), key(),
                new AgentActor(null, "curasync-previsit-runtime", ContractActorType.AGENT));
        assertThat(registrations.findById(reg.getId()).orElseThrow().getControlAcknowledgedAt())
                .isNotNull();
    }
}
