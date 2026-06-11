package com.meridian.substrate.app.agent;

import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.engine.agent.AgentControlEffects;
import com.meridian.substrate.engine.agent.AgentQueryService;
import com.meridian.substrate.engine.agent.read.AgentSignals;
import com.meridian.substrate.engine.contract.gateway.AgentRegistryGate;
import com.meridian.substrate.engine.contract.gateway.NoopAgentRegistryGate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * §16.2 test plan <b>#16 — degraded / whole-or-in-part</b> (engine-spec
 * agents.md §1 non-negotiable #7, §9.2, §16.2; ADR-003 D7/D17): with the
 * contracts gateway ABSENT — simulated by making the contracts-owned
 * {@link NoopAgentRegistryGate} the primary gate, exactly the bean state a
 * contracts-less deployment has — the agent engine boots green and:
 *
 * <ul>
 *   <li>every verb is RECORDED_INTENT and DISCLOSES the degraded posture
 *       verbatim ({@code "enforcement pending gateway"}) — even for
 *       SUBSTRATE_MCP-locus agents; claiming GATEWAY_BLOCK without a live
 *       gate would be fabricated control (invariant I8);</li>
 *   <li>STRUCTURED criteria gates stand UNEVALUATED and the signals feed
 *       carries the gateCoverage ratio — degradation is visible, never
 *       silent (no-silent-zero-yield).</li>
 * </ul>
 *
 * <p>The bean-override (not a property) is deliberate: honesty is not
 * configuration — the disclosure keys off {@code AgentRegistryGate
 * .enforcesRegistry()}, the same fact a real degraded deployment presents.
 */
@SpringBootTest(properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "substrate.contracts.enforcement.mode=shadow",
        "substrate.contracts.monitor.sweep-interval=PT1H",
        "spring.datasource.url=jdbc:h2:mem:agent_gateway_degraded;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class AgentGatewayDegradedModeTest extends AgentVerbTestSupport {

    @TestConfiguration
    static class ContractsAbsentGate {
        /** Replaces {@code AgentRegistryGateImpl} with the no-op default bean. */
        @Bean(name = "agentRegistryGateImpl")
        @Primary
        AgentRegistryGate agentRegistryGateImpl() {
            return new NoopAgentRegistryGate();
        }
    }

    @Autowired AgentQueryService queryService;

    @Test
    void verbsAreRecordedIntentWithTheVerbatimPendingGatewayDisclosure() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-msg-router");

        postVerb(reg.getId() + "/pause", reasonedBody("Paused pending review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enforcement.effect", is("recorded-intent")))
                .andExpect(jsonPath("$.data.enforcement.confirmationState", is("pending")))
                .andExpect(jsonPath("$.data.enforcement.disclosure",
                        is(AgentControlEffects.DISCLOSURE_PENDING_GATEWAY)))
                .andExpect(jsonPath("$.data.agent.controlEffects.pause.disclosure",
                        is(AgentControlEffects.DISCLOSURE_PENDING_GATEWAY)))
                .andExpect(jsonPath("$.data.agent.controlEffects.kill.effect",
                        is("recorded-intent")));

        // disclosure BEFORE the click on the read surface too (§6.1)
        mvc.perform(get("/api/v1/agents/" + reg.getId()))
                .andExpect(jsonPath("$.data.controlEffects.throttle.disclosure",
                        is(AgentControlEffects.DISCLOSURE_PENDING_GATEWAY)));
    }

    @Test
    void structuredGatesStandUnevaluated_andCoverageIsVisibleNeverSilent() throws Exception {
        AgentRegistration reg = deploy(UUID.randomUUID(), "a-pre-visit");

        mvc.perform(get("/api/v1/agents/" + reg.getId() + "/gates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].ruleType", is("structured")))
                .andExpect(jsonPath("$.data[0].standing", is("unevaluated")));

        AgentSignals signals = queryService.signals(reg.getDomainId());
        assertThat(signals.gateCoverage().structured()).isEqualTo(1);
        assertThat(signals.gateCoverage().unevaluated()).isEqualTo(1);
    }
}
