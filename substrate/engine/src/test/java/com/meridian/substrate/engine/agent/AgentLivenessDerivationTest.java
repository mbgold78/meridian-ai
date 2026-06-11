package com.meridian.substrate.engine.agent;

import com.meridian.substrate.common.domain.AgentRegistration;
import com.meridian.substrate.common.domain.enums.AgentExecutionLocus;
import com.meridian.substrate.common.domain.enums.AgentObservability;
import com.meridian.substrate.common.domain.enums.AgentSightingChannel;
import com.meridian.substrate.common.domain.enums.AgentStaleness;
import com.meridian.substrate.common.repo.AgentRegistrationRepository;
import com.meridian.substrate.common.repo.AgentSightingRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * §10 liveness derivation table (engine-spec agents.md §10; ADR-003 D11;
 * test plan #9): channel × expectation × elapsed ⇒ observability/staleness —
 * including the two binding negatives: NOT_EXPECTED never renders stale-red,
 * and registration {@code updated_at} is NEVER conflated with liveness
 * (invariant I7). Pure derivations over a fixed clock — repositories are
 * never touched on the read path.
 */
class AgentLivenessDerivationTest {

    private static final Instant NOW = Instant.parse("2026-06-11T12:00:00Z");

    private final AgentLivenessService service = new AgentLivenessService(
            mock(AgentRegistrationRepository.class), mock(AgentSightingRepository.class),
            new AgentProperties(), Clock.fixed(NOW, ZoneOffset.UTC));

    // ---------------------------------------------------------- observability

    @Test
    void boundPrincipalOrOrchestratorLocusIsObservableCustomerSystemWithoutBindingIsAttestOnly() {
        assertThat(service.observabilityOf(agent(AgentExecutionLocus.CUSTOMER_SYSTEM, null)))
                .isEqualTo(AgentObservability.ATTEST_ONLY);
        assertThat(service.observabilityOf(agent(AgentExecutionLocus.CUSTOMER_SYSTEM, "mcp-x")))
                .isEqualTo(AgentObservability.OBSERVABLE);
        assertThat(service.observabilityOf(agent(AgentExecutionLocus.ORCHESTRATOR, null)))
                .isEqualTo(AgentObservability.OBSERVABLE);
        assertThat(service.observabilityOf(agent(AgentExecutionLocus.SUBSTRATE_MCP, "mcp-y")))
                .isEqualTo(AgentObservability.OBSERVABLE);
    }

    // -------------------------------------------------------------- staleness

    @Test
    void noChannelNoExpectationNeverSeenIsNotExpected_neverStaleRed() {
        AgentRegistration reg = agent(AgentExecutionLocus.CUSTOMER_SYSTEM, null);
        assertThat(service.stalenessOf(reg)).isEqualTo(AgentStaleness.NOT_EXPECTED);
    }

    @Test
    void channelExistsButNoSightingEverIsNeverSeen() {
        assertThat(service.stalenessOf(agent(AgentExecutionLocus.ORCHESTRATOR, null)))
                .isEqualTo(AgentStaleness.NEVER_SEEN);
        // an expectation without a channel also expects reports — NEVER_SEEN, not NOT_EXPECTED
        AgentRegistration expecting = agent(AgentExecutionLocus.CUSTOMER_SYSTEM, null);
        expecting.setHeartbeatExpectedMinutes(60);
        assertThat(service.stalenessOf(expecting)).isEqualTo(AgentStaleness.NEVER_SEEN);
    }

    @Test
    void explicitHeartbeatExpectationDrivesStaleVersusFresh() {
        AgentRegistration reg = agent(AgentExecutionLocus.CUSTOMER_SYSTEM, "mcp-x");
        reg.setHeartbeatExpectedMinutes(30);
        reg.setLastSeenAt(NOW.minus(Duration.ofMinutes(29)));
        reg.setLastSeenVia(AgentSightingChannel.HEARTBEAT);
        assertThat(service.stalenessOf(reg)).isEqualTo(AgentStaleness.FRESH);
        reg.setLastSeenAt(NOW.minus(Duration.ofMinutes(31)));
        assertThat(service.stalenessOf(reg)).isEqualTo(AgentStaleness.STALE);
    }

    @Test
    void unsetExpectationWithChannelFallsBackToStaleAfterHours() {
        AgentRegistration reg = agent(AgentExecutionLocus.ORCHESTRATOR, null);
        reg.setLastSeenAt(NOW.minus(Duration.ofHours(71)));
        reg.setLastSeenVia(AgentSightingChannel.DISPATCH_EVENT);
        assertThat(service.stalenessOf(reg)).isEqualTo(AgentStaleness.FRESH);   // default 72h
        reg.setLastSeenAt(NOW.minus(Duration.ofHours(73)));
        assertThat(service.stalenessOf(reg)).isEqualTo(AgentStaleness.STALE);
    }

    @Test
    void attestOnlyAgentWithAnOldSightingStaysFresh_noChannelMeansNoStaleRed() {
        AgentRegistration reg = agent(AgentExecutionLocus.CUSTOMER_SYSTEM, null);
        reg.setLastSeenAt(NOW.minus(Duration.ofDays(30)));
        reg.setLastSeenVia(AgentSightingChannel.ATTESTATION);
        assertThat(service.stalenessOf(reg)).isEqualTo(AgentStaleness.FRESH);
    }

    /** I7: row recency is NOT liveness — a fresh updated_at flips nothing. */
    @Test
    void updatedAtIsNotLiveness() {
        AgentRegistration reg = agent(AgentExecutionLocus.ORCHESTRATOR, null);
        reg.setUpdatedAt(NOW);                  // row touched seconds ago…
        assertThat(service.stalenessOf(reg))    // …but no sighting EVER
                .isEqualTo(AgentStaleness.NEVER_SEEN);
    }

    private static AgentRegistration agent(AgentExecutionLocus locus, String principal) {
        AgentRegistration reg = new AgentRegistration();
        reg.setExecutionLocus(locus);
        reg.setMcpPrincipalId(principal);
        return reg;
    }
}
