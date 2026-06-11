package com.meridian.substrate.app.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.AgentStatusEvent;
import com.meridian.substrate.common.domain.enums.AgentEventType;
import com.meridian.substrate.common.domain.enums.AgentInterventionStatus;
import com.meridian.substrate.common.domain.enums.AgentSightingChannel;
import com.meridian.substrate.common.domain.enums.GatewayDecision;
import com.meridian.substrate.common.repo.AgentInterventionRepository;
import com.meridian.substrate.common.repo.AgentSightingRepository;
import com.meridian.substrate.common.repo.AgentStatusEventRepository;
import com.meridian.substrate.common.repo.ContractEnforcementDecisionRepository;
import com.meridian.substrate.engine.agent.AgentDispatchPreflight;
import com.meridian.substrate.engine.agent.AgentGatewaySignalService;
import com.meridian.substrate.engine.contract.gateway.AgentRegistryGate;
import com.meridian.substrate.engine.contract.gateway.GovernedActions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §16.2 test plan <b>#15 — dispatch preflight + result quarantine</b>
 * (engine-spec agents.md §14; ADR-003 D2/D8):
 *
 * <ul>
 *   <li>preflight REFUSES submission for a paused agent (a governance
 *       outcome carried as a result, never an error shape);</li>
 *   <li>{@code onSubmitted} rows the DISPATCH event with the provenance
 *       digest + a DISPATCH_EVENT sighting, idempotent per task;</li>
 *   <li>{@code apply_dispatch_result} for a KILLED agent's task → refused +
 *       DENY decision row ({@code dispatch_id} stamped, no parallel log) +
 *       CONTROL_VIOLATION-class signal;</li>
 *   <li>a LIVE agent's results apply untouched.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "substrate.contracts.enforcement.mode=shadow",
        "substrate.contracts.monitor.sweep-interval=PT1H",
        "spring.datasource.url=jdbc:h2:mem:agent_gateway_6c;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AgentDispatchPreflightTest extends AgentVerbTestSupport {

    @Autowired AgentDispatchPreflight preflight;
    @Autowired AgentStatusEventRepository events;
    @Autowired AgentSightingRepository sightings;
    @Autowired AgentInterventionRepository interventions;
    @Autowired ContractEnforcementDecisionRepository decisions;

    @Test
    void preflightRefusesSubmissionForPausedAgent_asAGovernanceOutcome() throws Exception {
        UUID domainId = UUID.randomUUID();
        AgentRegistration reg = deploy(domainId, "a-pre-visit");
        postVerb(reg.getId() + "/pause", reasonedBody("Paused pending review"))
                .andExpect(status().isOk());

        AgentDispatchPreflight.Preflight outcome =
                preflight.beforeSubmit(domainId, "a-pre-visit");

        assertThat(outcome.allowed()).isFalse();
        assertThat(outcome.reasonCode()).isEqualTo(AgentRegistryGate.REASON_CONTROL_SUSPENDED);
        assertThat(outcome.disclosure())
                .contains("no new dispatches")
                .contains("cannot be aborted");   // five-tool seam honesty (§14)
    }

    @Test
    void onSubmittedRowsTheDispatchDigestAndSighting_idempotentPerTask() throws Exception {
        UUID domainId = UUID.randomUUID();
        AgentRegistration reg = deploy(domainId, "a-eligibility");
        assertThat(preflight.beforeSubmit(domainId, "a-eligibility").allowed()).isTrue();

        ObjectNode digest = json.createObjectNode();
        digest.put("taskId", "task-42");
        digest.put("model", "claude-fable-5");
        digest.put("eventsDigest", "sha256:abc");
        digest.put("requestedAt", "2026-06-11T00:00:00Z");
        digest.put("landedAt", "2026-06-11T00:00:05Z");
        preflight.onSubmitted(reg.getId(), digest);
        preflight.onSubmitted(reg.getId(), digest);   // replay — no second event

        var dispatchEvents = events.findByAgentRegistrationIdOrderByEventSeqAsc(reg.getId())
                .stream().filter(e -> e.getEventType() == AgentEventType.DISPATCH).toList();
        assertThat(dispatchEvents).hasSize(1);
        AgentStatusEvent dispatch = dispatchEvents.get(0);
        assertThat(dispatch.getPayload().path("taskId").asText()).isEqualTo("task-42");
        assertThat(dispatch.getPayload().path("eventsDigest").asText()).isEqualTo("sha256:abc");
        assertThat(sightings.findByAgentRegistrationIdOrderBySeenAtDesc(reg.getId()))
                .anyMatch(s -> s.getChannel() == AgentSightingChannel.DISPATCH_EVENT);
    }

    @Test
    void resultQuarantine_refusesKilledAgentsResults_withDecisionRowAndSignal() throws Exception {
        UUID domainId = UUID.randomUUID();
        AgentRegistration reg = deploy(domainId, "a-noshow");
        postVerb(reg.getId() + "/kill", reasonedBody("Emergency stop"))
                .andExpect(status().isOk());

        AgentDispatchPreflight.Quarantine quarantine =
                preflight.guardResultApply(domainId, "a-noshow", "dispatch-7");

        assertThat(quarantine.applyAllowed()).isFalse();
        assertThat(quarantine.reasonCode())
                .isEqualTo(AgentDispatchPreflight.REASON_RESULT_QUARANTINED);
        assertThat(quarantine.decisionId()).isNotNull();

        // DENY-shaped row in contract_enforcement_decisions, dispatch id stamped
        var row = decisions.findById(quarantine.decisionId()).orElseThrow();
        assertThat(row.getDecision()).isEqualTo(GatewayDecision.DENY);
        assertThat(row.getActionType()).isEqualTo(GovernedActions.APPLY_DISPATCH_RESULT);
        assertThat(row.getDispatchId()).isEqualTo("dispatch-7");
        assertThat(row.getReason()).isEqualTo(AgentDispatchPreflight.REASON_RESULT_QUARANTINED);

        // CONTROL_VIOLATION-class signal: seen-after-kill intervention raised…
        assertThat(interventions
                .findByAgentRegistrationIdAndStatus(reg.getId(), AgentInterventionStatus.OPEN))
                .anyMatch(i -> AgentGatewaySignalService.SOURCE_SEEN_AFTER_KILL
                        .equals(i.getSourceKey()));
        // …but NO sighting fabricated — a quarantined result is not liveness
        // evidence of the agent itself.
        assertThat(sightings.findByAgentRegistrationIdOrderBySeenAtDesc(reg.getId())).isEmpty();
    }

    @Test
    void liveAgentsResultsApplyUntouched() throws Exception {
        UUID domainId = UUID.randomUUID();
        AgentRegistration reg = deploy(domainId, "a-msg-router");
        // promote through the production endpoints (SHADOW → LIVE, M3/M4)
        postVerb(reg.getId() + "/promote/request", verbBody()).andExpect(status().isOk());
        approvePromotionReview(reg.getId().toString());
        postVerb(reg.getId() + "/promote", verbBody()).andExpect(status().isOk());

        AgentDispatchPreflight.Quarantine quarantine =
                preflight.guardResultApply(domainId, "a-msg-router", "dispatch-8");

        assertThat(quarantine.applyAllowed()).isTrue();
        assertThat(quarantine.reasonCode()).isNull();
        assertThat(quarantine.decisionId()).isNull();
    }
}
