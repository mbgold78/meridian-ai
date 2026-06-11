package com.meridian.substrate.app.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.AgentIntervention;
import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.AgentSighting;
import com.meridian.substrate.common.domain.enums.AgentEventType;
import com.meridian.substrate.common.domain.enums.AgentInterventionStatus;
import com.meridian.substrate.common.domain.enums.AgentNotificationType;
import com.meridian.substrate.common.domain.enums.AgentSightingChannel;
import com.meridian.substrate.common.domain.enums.ContractActorType;
import com.meridian.substrate.common.domain.enums.GatewayDecision;
import com.meridian.substrate.common.repo.AgentInterventionRepository;
import com.meridian.substrate.common.repo.AgentNotificationRepository;
import com.meridian.substrate.common.repo.AgentSightingRepository;
import com.meridian.substrate.common.repo.AgentStatusEventRepository;
import com.meridian.substrate.common.repo.ContractEnforcementDecisionRepository;
import com.meridian.substrate.engine.agent.AgentActor;
import com.meridian.substrate.engine.agent.AgentControlConfirmationService;
import com.meridian.substrate.engine.agent.AgentGatewaySignalService;
import com.meridian.substrate.engine.agent.AgentLivenessService;
import com.meridian.substrate.engine.agent.AgentQueryService;
import com.meridian.substrate.engine.agent.read.AgentSignals;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.contract.gateway.AgentGateDeniedException;
import com.meridian.substrate.engine.contract.gateway.AgentRegistryGate;
import com.meridian.substrate.engine.contract.gateway.GovernedPrincipalScope;
import com.meridian.substrate.engine.contract.gateway.Principal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §16.2 test plan <b>#10 — the zombie flow</b> (engine-spec agents.md
 * §6.4/§8.3; ADR-003 D6 #3): a sighting of a suspended agent is recorded AND
 * escalated, never rejected-and-forgotten.
 *
 * <ul>
 *   <li>paused + heartbeat (shadow) → GATEWAY_ACTION sighting +
 *       CONTROL_VIOLATION_OBSERVED event + ACTIVE_WHILE_PAUSED intervention
 *       + WOULD_DENY decision row + destructive CONTROL_VIOLATION
 *       notification — and the heartbeat itself proceeds (shadow never
 *       blocks);</li>
 *   <li>dedupe: a second heartbeat refreshes, NO second OPEN row (I12);</li>
 *   <li>enforce: the governed write throws {@code AGENT_GATE_DENIAL}
 *       ({@code AGENT_CONTROL_SUSPENDED}) and the zombie trail SURVIVES the
 *       business rollback (REQUIRES_NEW);</li>
 *   <li>killed + call → SEEN_AFTER_KILL, the highest-urgency signal class,
 *       surfaced in {@code GET /agents/signals};</li>
 *   <li>{@code acknowledge_control} stays allowed while suspended — the
 *       compliant act (§8.3 check-1 exception).</li>
 * </ul>
 *
 * <p>Deliberately NOT {@code @Transactional}: REQUIRES_NEW survival and
 * rollback realism need real commits. H2 fast lane; the CI
 * Testcontainers-Postgres suite ({@code PostgresAgentGatewayIT}) is the
 * acceptance gate.
 */
@SpringBootTest(properties = {
        "substrate.contracts.enforcement.mode=shadow",
        "substrate.contracts.monitor.sweep-interval=PT1H",
        "spring.datasource.url=jdbc:h2:mem:agent_gateway_6c;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AgentGatewayZombieFlowTest extends AgentVerbTestSupport {

    @Autowired AgentLivenessService liveness;
    @Autowired AgentControlConfirmationService controlConfirmation;
    @Autowired AgentQueryService queryService;
    @Autowired AgentSightingRepository sightings;
    @Autowired AgentInterventionRepository interventions;
    @Autowired AgentStatusEventRepository events;
    @Autowired AgentNotificationRepository notifications;
    @Autowired ContractEnforcementDecisionRepository decisions;
    @Autowired ContractProperties contractProperties;

    @AfterEach
    void restoreShadow() {
        contractProperties.getEnforcement().setMode("shadow");
    }

    @Test
    void pausedHeartbeatInShadow_sightedWouldDeniedAndEscalated_secondTouchDedupes()
            throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-msg-router");
        String principalId = bindPrincipal(reg, "zombie-shadow-agent");
        pause(reg, "Paused pending Compliance review of PHI-routing change");

        // shadow heartbeat PROCEEDS (shadow never blocks, ADR-002 D4) …
        GovernedPrincipalScope.callAs(Principal.agent(principalId),
                () -> liveness.heartbeat(reg.getId(), "nightly batch ping"));

        // … the touch was SIGHTED (GATEWAY_ACTION — it is true, §6.4) …
        List<AgentSighting> sighted =
                sightings.findByAgentRegistrationIdOrderBySeenAtDesc(reg.getId());
        assertThat(sighted).extracting(AgentSighting::getChannel)
                .contains(AgentSightingChannel.GATEWAY_ACTION, AgentSightingChannel.HEARTBEAT);

        // … the violation is EVENTED …
        assertThat(events.findByAgentRegistrationIdOrderByEventSeqAsc(reg.getId()))
                .extracting(e -> e.getEventType())
                .contains(AgentEventType.CONTROL_VIOLATION_OBSERVED,
                        AgentEventType.INTERVENTION_OPENED);

        // … the engine intervention is OPEN with the §6.4 source key …
        List<AgentIntervention> open = interventions
                .findByAgentRegistrationIdAndStatus(reg.getId(), AgentInterventionStatus.OPEN)
                .stream()
                .filter(i -> AgentGatewaySignalService.SOURCE_ACTIVE_WHILE_PAUSED
                        .equals(i.getSourceKey()))
                .toList();
        assertThat(open).hasSize(1);

        // … WOULD_DENY rowed into contract_enforcement_decisions (no parallel log) …
        assertThat(decisions.findByDomainIdAndDecision(reg.getDomainId(),
                GatewayDecision.WOULD_DENY))
                .anyMatch(d -> AgentRegistryGate.REASON_CONTROL_SUSPENDED.equals(d.getReason()));

        // … the chain got the destructive CONTROL_VIOLATION fan-out …
        assertThat(notifications.findByAgentRegistrationIdAndNotificationType(reg.getId(),
                AgentNotificationType.CONTROL_VIOLATION))
                .isNotEmpty()
                .allMatch(n -> "destructive".equals(n.getTone()));
        assertThat(queryService.signals(reg.getDomainId()).controlViolations())
                .anyMatch(v -> "ACTIVE_WHILE_PAUSED".equals(v.kind())
                        && reg.getAgentKey().equals(v.agentKey()));

        // dedupe: the second heartbeat refreshes — NO second OPEN row (I12).
        GovernedPrincipalScope.callAs(Principal.agent(principalId),
                () -> liveness.heartbeat(reg.getId(), "nightly batch ping again"));
        assertThat(interventions
                .findByAgentRegistrationIdAndStatus(reg.getId(), AgentInterventionStatus.OPEN)
                .stream()
                .filter(i -> AgentGatewaySignalService.SOURCE_ACTIVE_WHILE_PAUSED
                        .equals(i.getSourceKey())))
                .hasSize(1);
    }

    @Test
    void pausedGovernedWriteInEnforce_structuredDenial_zombieTrailSurvivesRollback()
            throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-msg-router");
        String principalId = bindPrincipal(reg, "zombie-enforce-agent");
        pause(reg, "Paused pending Compliance review");
        contractProperties.getEnforcement().setMode("enforce");

        assertThatThrownBy(() -> GovernedPrincipalScope.callAs(Principal.agent(principalId),
                () -> liveness.heartbeat(reg.getId(), "still alive")))
                .isInstanceOf(AgentGateDeniedException.class)
                .satisfies(thrown -> {
                    AgentGateDeniedException denial = (AgentGateDeniedException) thrown;
                    assertThat(denial.code())
                            .isEqualTo(AgentGateDeniedException.AGENT_GATE_CODE);
                    assertThat(denial.getDeny().reason())
                            .isEqualTo(AgentRegistryGate.REASON_CONTROL_SUSPENDED);
                    assertThat(denial.getDecisionId()).isNotNull();
                });

        // The denied business write rolled back: NO heartbeat sighting …
        List<AgentSighting> sighted =
                sightings.findByAgentRegistrationIdOrderBySeenAtDesc(reg.getId());
        assertThat(sighted).extracting(AgentSighting::getChannel)
                .doesNotContain(AgentSightingChannel.HEARTBEAT);
        // … but the zombie trail COMMITTED in REQUIRES_NEW (§6.4/§10.4 posture).
        assertThat(sighted).extracting(AgentSighting::getChannel)
                .contains(AgentSightingChannel.GATEWAY_ACTION);
        assertThat(decisions.findByDomainIdAndDecision(reg.getDomainId(), GatewayDecision.DENY))
                .anyMatch(d -> AgentRegistryGate.REASON_CONTROL_SUSPENDED.equals(d.getReason()));
        assertThat(interventions
                .findByAgentRegistrationIdAndStatus(reg.getId(), AgentInterventionStatus.OPEN))
                .anyMatch(i -> AgentGatewaySignalService.SOURCE_ACTIVE_WHILE_PAUSED
                        .equals(i.getSourceKey()));
    }

    @Test
    void killedThenHeartbeat_seenAfterKill_destructiveAndSurfacedInSignals() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-noshow");
        String principalId = bindPrincipal(reg, "zombie-killed-agent");
        postVerb(reg.getId() + "/kill", reasonedBody("Emergency stop"))
                .andExpect(status().isOk());
        contractProperties.getEnforcement().setMode("enforce");

        assertThatThrownBy(() -> GovernedPrincipalScope.callAs(Principal.agent(principalId),
                () -> liveness.heartbeat(reg.getId(), "process never stopped")))
                .isInstanceOf(AgentGateDeniedException.class);

        // SEEN_AFTER_KILL — intervention raised on the RETIRED registration
        // (deliberate terminal carve-out, §6.4) and surfaced in signals.
        assertThat(interventions
                .findByAgentRegistrationIdAndStatus(reg.getId(), AgentInterventionStatus.OPEN))
                .anyMatch(i -> AgentGatewaySignalService.SOURCE_SEEN_AFTER_KILL
                        .equals(i.getSourceKey()));
        AgentSignals signals = queryService.signals(reg.getDomainId());
        assertThat(signals.controlViolations())
                .anyMatch(v -> "SEEN_AFTER_KILL".equals(v.kind())
                        && reg.getAgentKey().equals(v.agentKey()));
        // destructive fan-out reached the chain
        assertThat(notifications.findByAgentRegistrationIdAndNotificationType(reg.getId(),
                AgentNotificationType.CONTROL_VIOLATION))
                .isNotEmpty()
                .allMatch(n -> "destructive".equals(n.getTone()));
    }

    @Test
    void acknowledgeControlStaysAllowedWhileSuspended_theCompliantAct() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-msg-router");
        String principalId = bindPrincipal(reg, "compliant-paused-agent");
        pause(reg, "Paused pending review");
        contractProperties.getEnforcement().setMode("enforce");

        AgentRegistration after = GovernedPrincipalScope.callAs(Principal.agent(principalId),
                () -> controlConfirmation.acknowledgeControl(reg.getId(), key(),
                        new AgentActor(null, principalId, ContractActorType.AGENT)));

        assertThat(after.getControlAcknowledgedAt()).isNotNull();
        // the compliant act raises NO zombie intervention
        assertThat(interventions
                .findByAgentRegistrationIdAndStatus(reg.getId(), AgentInterventionStatus.OPEN))
                .noneMatch(i -> i.getSourceKey() != null);
        // …and was still sighted (liveness for free, §8.3 #6)
        assertThat(sightings.findByAgentRegistrationIdOrderBySeenAtDesc(reg.getId()))
                .extracting(AgentSighting::getChannel)
                .contains(AgentSightingChannel.GATEWAY_ACTION);
    }

    // ------------------------------------------------------------------ helpers

    private String bindPrincipal(AgentRegistration reg, String principalId) throws Exception {
        ObjectNode body = verbBody();
        body.put("mcpPrincipalId", principalId);
        postVerb(reg.getId() + "/principal", body).andExpect(status().isOk());
        return principalId;
    }

    private void pause(AgentRegistration reg, String reason) throws Exception {
        postVerb(reg.getId() + "/pause", reasonedBody(reason)).andExpect(status().isOk());
    }
}
