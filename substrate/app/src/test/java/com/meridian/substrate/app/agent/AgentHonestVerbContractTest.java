package com.meridian.substrate.app.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.enums.AgentSightingChannel;
import com.meridian.substrate.common.domain.enums.ContractActorType;
import com.meridian.substrate.engine.agent.AgentActor;
import com.meridian.substrate.engine.agent.AgentControlConfirmationService;
import com.meridian.substrate.engine.agent.AgentLivenessService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §16.2 test plan <b>#8 — the PINNED honest-verb contract</b> (engine-spec
 * agents.md §5.3/§6; ADR-003 D6/D17): pause/kill/retire answer HTTP 200
 * through {@code ResponseEnvelopeAdvice} with {@code errors=[]}; every verb
 * response carries {@code enforcement.effect} + {@code confirmationState};
 * a RECORDED_INTENT suspension opens {@code pending}; the MCP-shaped runtime
 * acknowledgment moves it to {@code acknowledged}; human confirm-control to
 * {@code confirmed}; sighting PRESENCE or ABSENCE flips NOTHING; and no agent
 * enum serializes error/failure vocabulary.
 *
 * <p><b>6c note:</b> the D17 window is CLOSED — {@code AgentRegistryGateImpl}
 * is the primary gate, so a CUSTOMER_SYSTEM-locus suspension discloses the
 * honest cannot-reach-this-runtime text (still RECORDED_INTENT — substrate
 * never claims to have stopped what it cannot reach). The verbatim
 * {@code "enforcement pending gateway"} disclosure is now pinned by the
 * degraded-mode suite ({@code AgentGatewayDegradedModeTest}, test plan #16).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class AgentHonestVerbContractTest extends AgentVerbTestSupport {

    @Autowired
    AgentControlConfirmationService controlConfirmation;
    @Autowired
    AgentLivenessService liveness;

    @Test
    void pauseAnswers200WithRecordedIntentPendingAndTheWindowDisclosure() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-msg-router");
        postVerb(reg.getId() + "/pause",
                reasonedBody("Paused 2026-04-19 pending Compliance review of PHI-routing change"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.status", is("paused")))
                .andExpect(jsonPath("$.data.enforcement.effect", is("recorded-intent")))
                .andExpect(jsonPath("$.data.enforcement.confirmationState", is("pending")))
                // 6c: CUSTOMER_SYSTEM locus — honest cannot-reach disclosure (§5.3)
                .andExpect(jsonPath("$.data.enforcement.disclosure",
                        containsString("substrate cannot reach")))
                .andExpect(jsonPath("$.data.enforcement.disclosure",
                        containsString("sighting absence never auto-confirms")))
                // disclosure BEFORE the click rides the same detail (§6.1)
                .andExpect(jsonPath("$.data.agent.controlEffects.pause.effect",
                        is("recorded-intent")))
                .andExpect(jsonPath("$.data.agent.controlEffects.kill.disclosure",
                        containsString("substrate cannot reach")));

        // the audit timeline shows PAUSED with its attributed actor (6b acceptance)
        mvc.perform(get("/api/v1/agents/" + reg.getId() + "/events"))
                .andExpect(jsonPath("$.data[1].eventType", is("paused")))
                .andExpect(jsonPath("$.data[1].actorLabel", is(OPERATOR)))
                .andExpect(jsonPath("$.data[1].reason",
                        is("Paused 2026-04-19 pending Compliance review of PHI-routing change")));
    }

    @Test
    void killAndRetireAre200NeverErrorShaped() throws Exception {
        UUID domainId = UUID.randomUUID();
        AgentRegistration killed = deploy(domainId, "a-pre-visit");
        AgentRegistration retired = deploy(domainId, "a-eligibility");
        postVerb(killed.getId() + "/kill", reasonedBody("Emergency stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.enforcement.effect", is("recorded-intent")))
                .andExpect(jsonPath("$.data.enforcement.confirmationState", is("pending")));
        postVerb(retired.getId() + "/retire", reasonedBody("Orderly wind-down"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors", hasSize(0)))
                .andExpect(jsonPath("$.data.status", is("retired")));
    }

    @Test
    void acknowledgmentAndConfirmationAreDistinctLadderRungs() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-msg-router");
        postVerb(reg.getId() + "/pause", reasonedBody("Paused pending Compliance review"))
                .andExpect(jsonPath("$.data.enforcement.confirmationState", is("pending")));

        // sighting PRESENCE flips nothing: telemetry is true, confirmation is human
        liveness.recordSighting(reg.getId(), AgentSightingChannel.HEARTBEAT, null);
        mvc.perform(get("/api/v1/agents/" + reg.getId()))
                .andExpect(jsonPath("$.data.enforcement.confirmationState", is("pending")));

        // MCP-shaped runtime acknowledgment (M17) → acknowledged — a WEAKER fact
        controlConfirmation.acknowledgeControl(reg.getId(), key(),
                new AgentActor(null, "curasync-router-runtime", ContractActorType.AGENT));
        mvc.perform(get("/api/v1/agents/" + reg.getId()))
                .andExpect(jsonPath("$.data.enforcement.confirmationState", is("acknowledged")));
        mvc.perform(get("/api/v1/agents/signals")
                        .param("domainId", reg.getDomainId().toString()))
                .andExpect(jsonPath("$.data.unconfirmedControls[0].confirmationState",
                        is("acknowledged")));

        // confirm-control without evidence → rejected (M16: evidence mandatory)
        ObjectNode noEvidence = verbBody();
        postVerb(reg.getId() + "/confirm-control", noEvidence)
                .andExpect(status().isBadRequest());

        // human confirmation with evidence → confirmed; the unconfirmed signal clears
        ObjectNode confirm = verbBody();
        confirm.set("evidenceRefs", json.readTree("[\"doc:shutdown-screenshot-1\"]"));
        confirm.put("note", "Runtime verified stopped in the customer tenant");
        postVerb(reg.getId() + "/confirm-control", confirm)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enforcement.confirmationState", is("confirmed")))
                .andExpect(jsonPath("$.data.agent.enforcement.confirmedBy", is(OPERATOR)));
        mvc.perform(get("/api/v1/agents/signals")
                        .param("domainId", reg.getDomainId().toString()))
                .andExpect(jsonPath("$.data.unconfirmedControls", hasSize(0)));
    }

    @Test
    void confirmationIsNeverDerivedFromSightingAbsence() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-msg-router");
        postVerb(reg.getId() + "/pause", reasonedBody("Paused pending review"))
                .andExpect(status().isOk());
        // NO sightings exist at all — absence is not evidence of stoppage:
        // the control stays PENDING until a human (M16) or runtime (M17) acts.
        mvc.perform(get("/api/v1/agents/" + reg.getId()))
                .andExpect(jsonPath("$.data.enforcement.confirmationState", is("pending")))
                .andExpect(jsonPath("$.data.enforcement.confirmedAt").value(org.hamcrest.Matchers.nullValue()));
    }

    /** No agent enum value serializes to anything containing "error"/"fail" (non-negotiable #2). */
    @Test
    void noAgentEnumSerializesErrorOrFailureVocabulary() throws Exception {
        List<Class<? extends Enum<?>>> agentEnums = List.of(
                com.meridian.substrate.common.domain.enums.AgentOperationalMode.class,
                com.meridian.substrate.common.domain.enums.AgentWireStatus.class,
                com.meridian.substrate.common.domain.enums.AgentEventType.class,
                com.meridian.substrate.common.domain.enums.AgentExecutionLocus.class,
                com.meridian.substrate.common.domain.enums.AgentControlEffect.class,
                com.meridian.substrate.common.domain.enums.AgentControlConfirmationState.class,
                com.meridian.substrate.common.domain.enums.AgentSightingChannel.class,
                com.meridian.substrate.common.domain.enums.AgentObservability.class,
                com.meridian.substrate.common.domain.enums.AgentStaleness.class,
                com.meridian.substrate.common.domain.enums.AgentInterventionStatus.class,
                com.meridian.substrate.common.domain.enums.AgentInterventionTrigger.class,
                com.meridian.substrate.common.domain.enums.AgentInterventionResolution.class,
                com.meridian.substrate.common.domain.enums.AgentReviewKind.class,
                com.meridian.substrate.common.domain.enums.AgentReviewDecision.class,
                com.meridian.substrate.common.domain.enums.AgentGateRuleType.class,
                com.meridian.substrate.common.domain.enums.AgentGateStanding.class,
                com.meridian.substrate.common.domain.enums.AgentReadinessSource.class,
                com.meridian.substrate.common.domain.enums.AgentSupervisoryModel.class,
                com.meridian.substrate.common.domain.enums.AgentRetiredReason.class,
                com.meridian.substrate.common.domain.enums.AgentNotificationType.class);
        for (Class<? extends Enum<?>> enumType : agentEnums) {
            Method wireValue = enumType.getMethod("wireValue");
            for (Enum<?> value : enumType.getEnumConstants()) {
                String wire = (String) wireValue.invoke(value);
                assertThat(wire.toLowerCase(Locale.ROOT))
                        .as("%s.%s wire form", enumType.getSimpleName(), value.name())
                        .doesNotContain("error")
                        .doesNotContain("fail");
            }
        }
    }
}
