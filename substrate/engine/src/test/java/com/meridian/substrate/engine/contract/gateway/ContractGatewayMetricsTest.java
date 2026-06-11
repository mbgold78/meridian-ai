package com.meridian.substrate.engine.contract.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractClauseGuard;
import com.meridian.substrate.common.domain.ContractEnforcementDecision;
import com.meridian.substrate.common.domain.enums.GuardEffect;
import com.meridian.substrate.common.domain.enums.PrincipalType;
import com.meridian.substrate.common.repo.ContractAttestationRepository;
import com.meridian.substrate.common.repo.ContractClauseGuardRepository;
import com.meridian.substrate.common.repo.ContractCommitmentRepository;
import com.meridian.substrate.common.repo.ContractEnforcementDecisionRepository;
import com.meridian.substrate.common.repo.ContractMetricSampleRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.contract.ContractProperties;
import com.meridian.substrate.engine.observability.EngineMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage 3: the {@code substrate_contract_gateway_evaluations} timer and
 * {@code substrate_agent_gateway_denials} counter recorded by the REAL
 * gateway paths (engine-spec contracts.md §4.3 outcomes → decision/mode
 * tags), driven through the same mocked-repository harness as
 * {@code ContractGatewayTest}.
 */
class ContractGatewayMetricsTest {

    private static final Instant NOW = Instant.parse("2026-06-10T12:00:00Z");

    private final ContractClauseGuardRepository guardRepo = mock(ContractClauseGuardRepository.class);
    private final ContractRepository contractRepo = mock(ContractRepository.class);
    private final ContractCommitmentRepository commitmentRepo = mock(ContractCommitmentRepository.class);
    private final ContractAttestationRepository attestationRepo = mock(ContractAttestationRepository.class);
    private final ContractMetricSampleRepository metricRepo = mock(ContractMetricSampleRepository.class);
    private final ContractEnforcementDecisionRepository decisionRepo =
            mock(ContractEnforcementDecisionRepository.class);
    private final ObjectMapper json = new ObjectMapper();

    private final UUID domainId = UUID.randomUUID();
    private final UUID contractId = UUID.randomUUID();

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private ContractProperties properties;
    private ContractGateway gateway;

    @BeforeEach
    void setUp() {
        properties = new ContractProperties();          // mode=shadow, level=matched-only defaults
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        when(decisionRepo.save(any())).thenAnswer(invocation -> {
            ContractEnforcementDecision row = invocation.getArgument(0);
            row.setId(UUID.randomUUID());
            return row;
        });
        gateway = new ContractGateway(
                new GuardCache(guardRepo, clock), contractRepo,
                new RequireAttestationGate(commitmentRepo, attestationRepo, properties, clock),
                new ContractMetricLeafResolver(metricRepo, properties, clock),
                new EnforcementDecisionWriter(decisionRepo, clock, json),
                properties, json, new NoopAgentRegistryGate(),
                new EngineMetrics(registry));

        Contract contract = new Contract();
        contract.setId(contractId);
        contract.setContractKey("synthetic-guard-bearing");
        when(contractRepo.findById(contractId)).thenReturn(Optional.of(contract));
        when(attestationRepo.findByCommitmentIdAndPeriodKey(any(), any()))
                .thenReturn(Optional.empty());
        when(guardRepo.findActiveByDomainId(domainId)).thenReturn(List.of());
    }

    @Test
    void modeOff_recordsAllowUnevaluated() {
        properties.getEnforcement().setMode("off");
        gateway.evaluate(action(PrincipalType.HUMAN));
        assertThat(timerCount("allow_unevaluated", "off")).isEqualTo(1);
    }

    @Test
    void noGuards_recordsAllow() {
        gateway.evaluate(action(PrincipalType.HUMAN));
        assertThat(timerCount("allow", "shadow")).isEqualTo(1);
    }

    @Test
    void shadowDeny_recordsWouldDeny_andAgentDenialForAgentPrincipal() {
        denyGuardActive();
        gateway.evaluate(action(PrincipalType.AGENT));

        assertThat(timerCount("would_deny", "shadow")).isEqualTo(1);
        assertThat(registry.get(EngineMetrics.AGENT_GATEWAY_DENIALS)
                .tag("reason", ContractGateway.REASON_DENY_GUARD_MATCHED)
                .tag("mode", "shadow")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void enforceDeny_recordsDeny_beforeTheThrow() {
        properties.getEnforcement().setMode("enforce");
        denyGuardActive();

        assertThatThrownBy(() -> gateway.evaluate(action(PrincipalType.HUMAN)))
                .isInstanceOf(ContractDeniedException.class);

        assertThat(timerCount("deny", "enforce")).isEqualTo(1);
        // HUMAN principal: clause-guard denial is NOT an agent gateway denial
        assertThat(registry.find(EngineMetrics.AGENT_GATEWAY_DENIALS).counter()).isNull();
    }

    @Test
    void flagGuard_recordsAllowWithFlag() {
        ContractClauseGuard flag = guard(GuardEffect.FLAG);
        when(guardRepo.findActiveByDomainId(domainId)).thenReturn(List.of(flag));

        gateway.evaluate(action(PrincipalType.HUMAN));
        assertThat(timerCount("allow_with_flag", "shadow")).isEqualTo(1);
    }

    @Test
    void builtInAgentLifecycleGuard_recordsAgentDenialWithItsReason() {
        properties.getEnforcement().setMode("enforce");
        assertThatThrownBy(() -> gateway.evaluate(
                new GovernedAction(GovernedActions.AGENT_PROMOTE,
                        new Principal(PrincipalType.AGENT, "mcp-1"), domainId, null, null)))
                .isInstanceOf(AgentGateDeniedException.class);

        assertThat(timerCount("deny", "enforce")).isEqualTo(1);
        assertThat(registry.get(EngineMetrics.AGENT_GATEWAY_DENIALS)
                .tag("reason", ContractGateway.REASON_AGENT_LIFECYCLE_HUMAN_ONLY)
                .tag("mode", "enforce")
                .counter().count()).isEqualTo(1.0);
    }

    // ------------------------------------------------------------------ support

    private long timerCount(String decision, String mode) {
        return registry.get(EngineMetrics.GATEWAY_EVALUATIONS)
                .tag("decision", decision)
                .tag("mode", mode)
                .timer().count();
    }

    private GovernedAction action(PrincipalType principalType) {
        return new GovernedAction("cascade.issue", new Principal(principalType, "p-1"),
                domainId, null, null);
    }

    private void denyGuardActive() {
        when(guardRepo.findActiveByDomainId(domainId))
                .thenReturn(List.of(guard(GuardEffect.DENY)));
    }

    private ContractClauseGuard guard(GuardEffect effect) {
        ContractClauseGuard g = new ContractClauseGuard();
        g.setId(UUID.randomUUID());
        g.setContractId(contractId);
        g.setActionType("cascade.issue");
        g.setEffect(effect);
        g.setActive(true);
        g.setDescription(effect + " guard");
        g.setPredicate(null);                       // predicate-null guard always matches (§4.3)
        return g;
    }
}
