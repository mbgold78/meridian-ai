package com.meridian.substrate.app.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.enums.AgentOperationalMode;
import com.meridian.substrate.common.domain.enums.AgentRetiredReason;
import com.meridian.substrate.common.domain.enums.AgentSightingChannel;
import com.meridian.substrate.engine.agent.AgentProjectionRebuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §16.2 test plan <b>#7 — rebuilder determinism</b> (engine-spec agents.md
 * §4 ⟲ / §16.2; ADR-003 D1): drive a rich verb stream through the REAL
 * services → snapshot the ⟲ projections → wipe them with garbage → rebuild →
 * field-equal; immutable deploy fields are ASSERT-EQUAL against the DEPLOYED
 * snapshot, never rewritten — drifted identity is a loud refusal, not a
 * silent repair.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Transactional
class AgentProjectionRebuilderTest extends AgentVerbTestSupport {

    @Autowired
    AgentProjectionRebuilder rebuilder;

    @Test
    void rebuildReproducesTheProjectionsFieldEqualAfterAWipe() throws Exception {
        UUID domainId = UUID.randomUUID();
        AgentRegistration reg = deploy(domainId, "a-noshow");

        // a rich stream: request → approve → promote → intervene → pause → confirm → attest
        postVerb("a-noshow/promote/request", verbBody()).andExpect(status().isOk());
        approvePromotionReview("a-noshow");
        postVerb("a-noshow/promote", verbBody()).andExpect(status().isOk());
        postVerb("a-noshow/interventions",
                reasonedBody("Drift detected — false-positive rate up 9 pts"))
                .andExpect(status().isCreated());
        postVerb(reg.getId() + "/pause", reasonedBody("Paused pending drift review"))
                .andExpect(status().isOk());
        ObjectNode confirm = verbBody();
        confirm.set("evidenceRefs", json.readTree("[\"doc:stop-evidence\"]"));
        postVerb(reg.getId() + "/confirm-control", confirm).andExpect(status().isOk());
        ObjectNode attest = verbBody();
        attest.put("statement", "Verified paused in the customer tenant");
        // attest on paused agent? attestation is a verb — paused is NOT terminal: legal.
        postVerb(reg.getId() + "/attest-liveness", attest).andExpect(status().isOk());

        Snapshot live = Snapshot.of(registrations.findById(reg.getId()).orElseThrow());

        // wipe the ⟲ projections with garbage (immutables stay intact — I2)
        AgentRegistration row = registrations.findById(reg.getId()).orElseThrow();
        row.setOperationalMode(AgentOperationalMode.RETIRED);
        row.setModeBeforePause(AgentOperationalMode.LIVE);
        row.setOpenInterventionCount(99);
        row.setEventSeq(424242);
        row.setShadowEnteredAt(Instant.EPOCH);
        row.setPromotionRequestedAt(Instant.EPOCH);
        row.setRetiredAt(Instant.EPOCH);
        row.setRetiredReason(AgentRetiredReason.KILLED);
        row.setRetiredByLabel("nobody");
        row.setControlPendingVerb("garbage");
        row.setControlRequestedAt(Instant.EPOCH);
        row.setControlAcknowledgedAt(Instant.EPOCH);
        row.setControlConfirmedAt(Instant.EPOCH);
        row.setControlConfirmedByLabel("nobody");
        row.setLastSeenAt(Instant.EPOCH);
        row.setLastSeenVia(AgentSightingChannel.DISPATCH_EVENT);
        row.setLastAttestedAt(Instant.EPOCH);
        row.setLastAttestedByLabel("nobody");
        registrations.save(row);

        Snapshot rebuilt = Snapshot.of(rebuilder.rebuild(reg.getId()));
        assertThat(rebuilt).isEqualTo(live);                 // byte-equal projections
    }

    @Test
    void rebuildIsExposedAsAnOperatorEndpointAndIsIdempotent() throws Exception {
        UUID domainId = UUID.randomUUID();
        AgentRegistration reg = deploy(domainId, "a-pre-visit");
        postVerb(reg.getId() + "/pause", reasonedBody("Paused pending review"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/agents/" + reg.getId() + "/rebuild-projections")
                        .header("X-Persona-Label", OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status", is("paused")))
                .andExpect(jsonPath("$.data.eventSeq", is(2)));
        // a second rebuild over already-correct projections changes nothing
        mvc.perform(post("/api/v1/agents/" + reg.getId() + "/rebuild-projections")
                        .header("X-Persona-Label", OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.eventSeq", is(2)));
    }

    @Test
    void driftedImmutableDeployFieldsRefuseToRebuild() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-pre-visit");
        reg.setDeployedByLabel("tampered-after-the-fact");
        registrations.save(reg);
        assertThatThrownBy(() -> rebuilder.rebuild(reg.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deployedByLabel")
                .hasMessageContaining("invariant I2");
    }

    /** The ⟲ projection fields (§4 table 1), snapshot-compared field-by-field. */
    private record Snapshot(
            AgentOperationalMode mode,
            AgentOperationalMode modeBeforePause,
            int openInterventions,
            int eventSeq,
            Instant shadowEnteredAt,
            Instant promotionRequestedAt,
            Instant retiredAt,
            AgentRetiredReason retiredReason,
            String retiredBy,
            String controlPendingVerb,
            Instant controlRequestedAt,
            Instant controlAcknowledgedAt,
            Instant controlConfirmedAt,
            String controlConfirmedBy,
            Instant lastSeenAt,
            AgentSightingChannel lastSeenVia,
            Instant lastAttestedAt,
            String lastAttestedBy,
            String mcpPrincipalId) {

        static Snapshot of(AgentRegistration reg) {
            return new Snapshot(reg.getOperationalMode(), reg.getModeBeforePause(),
                    reg.getOpenInterventionCount(), reg.getEventSeq(), reg.getShadowEnteredAt(),
                    reg.getPromotionRequestedAt(), reg.getRetiredAt(), reg.getRetiredReason(),
                    reg.getRetiredByLabel(), reg.getControlPendingVerb(),
                    reg.getControlRequestedAt(), reg.getControlAcknowledgedAt(),
                    reg.getControlConfirmedAt(), reg.getControlConfirmedByLabel(),
                    reg.getLastSeenAt(), reg.getLastSeenVia(), reg.getLastAttestedAt(),
                    reg.getLastAttestedByLabel(), reg.getMcpPrincipalId());
        }
    }
}
