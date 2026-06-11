package com.meridian.substrate.engine.contract.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.domain.Contract;
import com.meridian.substrate.common.domain.ContractAttestation;
import com.meridian.substrate.common.domain.ContractClauseGuard;
import com.meridian.substrate.common.domain.ContractCommitment;
import com.meridian.substrate.common.domain.ContractEnforcementDecision;
import com.meridian.substrate.common.domain.enums.GatewayDecision;
import com.meridian.substrate.common.domain.enums.GuardEffect;
import com.meridian.substrate.common.domain.enums.PrincipalType;
import com.meridian.substrate.common.repo.ContractAttestationRepository;
import com.meridian.substrate.common.repo.ContractClauseGuardRepository;
import com.meridian.substrate.common.repo.ContractCommitmentRepository;
import com.meridian.substrate.common.repo.ContractEnforcementDecisionRepository;
import com.meridian.substrate.common.repo.ContractMetricSampleRepository;
import com.meridian.substrate.common.repo.ContractRepository;
import com.meridian.substrate.engine.contract.ContractProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * §14.3 gateway matrix (engine-spec contracts.md §4.3; ADR-002 D4/D5/D16/D17):
 * effect precedence, REQUIRE_ATTESTATION with/without a current-period
 * attestation, shadow vs enforce vs off, fail posture per principal type,
 * decision-log level matched-only vs all, the §4.4 never-silent rule, and the
 * ADR D16 built-in non-removable waiver guard.
 *
 * <p>Fixed clock 2026-06-10T12:00Z (current MONTHLY period {@code 2026-06}).
 * The decision repository is mocked and its saves captured — REQUIRES_NEW
 * itself is proxy behavior proven on real Postgres by
 * {@code PostgresContractGatewayDenyRollbackIT} (§14.4; H2-green is never
 * acceptance for it).
 */
class ContractGatewayTest {

    private static final Instant NOW = Instant.parse("2026-06-10T12:00:00Z");

    private final ContractClauseGuardRepository guardRepo = mock(ContractClauseGuardRepository.class);
    private final ContractRepository contractRepo = mock(ContractRepository.class);
    private final ContractCommitmentRepository commitmentRepo = mock(ContractCommitmentRepository.class);
    private final ContractAttestationRepository attestationRepo = mock(ContractAttestationRepository.class);
    private final ContractMetricSampleRepository metricRepo = mock(ContractMetricSampleRepository.class);
    private final ContractEnforcementDecisionRepository decisionRepo =
            mock(ContractEnforcementDecisionRepository.class);
    private final ObjectMapper json = new ObjectMapper();
    private final List<ContractEnforcementDecision> rows = new ArrayList<>();

    private final UUID domainId = UUID.randomUUID();
    private final UUID contractId = UUID.randomUUID();

    private ContractProperties properties;
    private EnforcementDecisionWriter writer;
    private ContractGateway gateway;

    @BeforeEach
    void setUp() {
        properties = new ContractProperties();          // mode=shadow, level=matched-only defaults
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        when(decisionRepo.save(any())).thenAnswer(invocation -> {
            ContractEnforcementDecision row = invocation.getArgument(0);
            row.setId(UUID.randomUUID());
            rows.add(row);
            return row;
        });
        writer = new EnforcementDecisionWriter(decisionRepo, clock, json);
        gateway = new ContractGateway(
                new GuardCache(guardRepo, clock), contractRepo,
                new RequireAttestationGate(commitmentRepo, attestationRepo, properties, clock),
                new ContractMetricLeafResolver(metricRepo, properties, clock),
                writer, properties, json,
                new NoopAgentRegistryGate());   // 6c: the contracts-owned default gate

        Contract contract = new Contract();
        contract.setId(contractId);
        contract.setContractKey("synthetic-guard-bearing");
        when(contractRepo.findById(contractId)).thenReturn(Optional.of(contract));
        when(attestationRepo.findByCommitmentIdAndPeriodKey(any(), any()))
                .thenReturn(Optional.empty());
        when(guardRepo.findActiveByDomainId(domainId)).thenReturn(List.of());
    }

    // ------------------------------------------------------------------ support

    private ContractClauseGuard guard(GuardEffect effect, String entityType, String predicate) {
        ContractClauseGuard g = new ContractClauseGuard();
        g.setId(UUID.randomUUID());
        g.setContractId(contractId);
        g.setActionType("cascade.issue");   // generic guarded action (agent.promote now
                                            // carries the 6c §8.4 built-in AGENT deny)
        g.setEntityType(entityType);
        g.setEffect(effect);
        g.setActive(true);
        g.setDescription(effect + " guard");
        try {
            g.setPredicate(predicate == null ? null : json.readTree(predicate));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return g;
    }

    private void activeGuards(ContractClauseGuard... guards) {
        when(guardRepo.findActiveByDomainId(domainId)).thenReturn(List.of(guards));
    }

    private GovernedAction action(PrincipalType principalType) {
        return action("cascade.issue", principalType);
    }

    private GovernedAction action(String actionType, PrincipalType principalType) {
        return new GovernedAction(actionType, new Principal(principalType, "p-1"),
                domainId, null, null);
    }

    private void mode(String mode) {
        properties.getEnforcement().setMode(mode);
    }

    private void level(String level) {
        properties.getDecisionLog().setLevel(level);
    }

    private ContractEnforcementDecision onlyRow() {
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    // ------------------------------------------------------------ mode ladder

    @Test
    void modeOff_gatewaySkipped_counterOnly_nothingRowed() {
        mode("off");
        long before = writer.allowUnevaluatedCount();
        Decision decision = gateway.evaluate(action(PrincipalType.AGENT));
        assertThat(decision).isEqualTo(Decision.Allow.unflagged());
        assertThat(writer.allowUnevaluatedCount()).isEqualTo(before + 1);
        assertThat(rows).isEmpty();
        verifyNoInteractions(guardRepo, decisionRepo);  // §1: gateway skipped entirely
    }

    @Test
    void shadowDeny_proceedsFlagged_rowsWouldDeny() {
        mode("shadow");
        ContractClauseGuard deny = guard(GuardEffect.DENY, null, null);
        activeGuards(deny);

        Decision decision = gateway.evaluate(action(PrincipalType.AGENT));

        Decision.Allow allow = (Decision.Allow) decision;   // shadow never blocks (ADR D4)
        assertThat(allow.flagged()).isTrue();
        assertThat(allow.matchedClauseIds()).containsExactly(deny.getId());
        ContractEnforcementDecision row = onlyRow();
        assertThat(row.getDecision()).isEqualTo(GatewayDecision.WOULD_DENY);
        assertThat(row.getMode()).isEqualTo("shadow");
        assertThat(row.getReason()).isEqualTo(ContractGateway.REASON_DENY_GUARD_MATCHED);
        assertThat(row.getLatencyMs()).isNotNull();
        assertThat(row.getActorType()).isEqualTo(PrincipalType.AGENT);
    }

    @Test
    void enforceDeny_rowsDenyThenThrows_citingKeyClauseAndDecisionId() {
        mode("enforce");
        ContractClauseGuard deny = guard(GuardEffect.DENY, null, null);
        activeGuards(deny);

        assertThatThrownBy(() -> gateway.evaluate(action(PrincipalType.AGENT)))
                .isInstanceOf(ContractDeniedException.class)
                .satisfies(thrown -> {
                    ContractDeniedException denial = (ContractDeniedException) thrown;
                    ContractEnforcementDecision row = onlyRow();
                    assertThat(row.getDecision()).isEqualTo(GatewayDecision.DENY);
                    assertThat(row.getMode()).isEqualTo("enforce");
                    // §10.4: rowed BEFORE the throw; the §4.5 citation carries the row id
                    assertThat(denial.getDecisionId()).isEqualTo(row.getId());
                    assertThat(denial.getDeny().clauseIds()).containsExactly(deny.getId());
                    assertThat(denial.getDeny().contractKeys())
                            .containsExactly("synthetic-guard-bearing");
                    // §4.5 message: contractKey + clause description + decisionId
                    assertThat(denial.getMessage())
                            .contains("synthetic-guard-bearing")
                            .contains(deny.getDescription())
                            .contains(row.getId().toString());
                });
    }

    // ------------------------------------------------------- effect precedence

    @Test
    void denyBeatsFlag_andDenyRowCitesOnlyDenyingClauses() {
        mode("enforce");
        ContractClauseGuard flag = guard(GuardEffect.FLAG, null, null);
        ContractClauseGuard deny = guard(GuardEffect.DENY, null, null);
        activeGuards(flag, deny);

        assertThatThrownBy(() -> gateway.evaluate(action(PrincipalType.AGENT)))
                .isInstanceOf(ContractDeniedException.class)
                .satisfies(thrown -> assertThat(((ContractDeniedException) thrown)
                        .getDeny().clauseIds()).containsExactly(deny.getId()));
    }

    @Test
    void unsatisfiedRequireAttestationBeatsFlag() {
        mode("enforce");
        // No commitment binding ⇒ unsatisfiable ⇒ deny ATTESTATION_MISSING (never a silent pass)
        activeGuards(guard(GuardEffect.FLAG, null, null),
                guard(GuardEffect.REQUIRE_ATTESTATION, null, null));

        assertThatThrownBy(() -> gateway.evaluate(action(PrincipalType.AGENT)))
                .isInstanceOf(ContractDeniedException.class)
                .satisfies(thrown -> assertThat(((ContractDeniedException) thrown)
                        .getDeny().reason())
                        .isEqualTo(ContractGateway.REASON_ATTESTATION_MISSING));
    }

    // -------------------------------------------------------- REQUIRE_ATTESTATION

    private ContractClauseGuard requireAttestationGuard(UUID commitmentId) {
        ContractCommitment commitment = new ContractCommitment();
        commitment.setId(commitmentId);
        commitment.setCadence("MONTHLY");
        when(commitmentRepo.findById(commitmentId)).thenReturn(Optional.of(commitment));
        ContractClauseGuard g = guard(GuardEffect.REQUIRE_ATTESTATION, null, null);
        g.setCommitmentId(commitmentId);
        return g;
    }

    @Test
    void requireAttestation_withoutCurrentPeriodAttestation_denies() {
        mode("enforce");
        activeGuards(requireAttestationGuard(UUID.randomUUID()));

        assertThatThrownBy(() -> gateway.evaluate(action(PrincipalType.AGENT)))
                .isInstanceOf(ContractDeniedException.class);
        assertThat(onlyRow().getReason()).isEqualTo(ContractGateway.REASON_ATTESTATION_MISSING);
    }

    @Test
    void requireAttestation_withCurrentPeriodAttestation_allows_rowedAsMatchedAllow() {
        mode("enforce");
        UUID commitmentId = UUID.randomUUID();
        ContractClauseGuard g = requireAttestationGuard(commitmentId);
        when(attestationRepo.findByCommitmentIdAndPeriodKey(commitmentId, "2026-06"))
                .thenReturn(Optional.of(new ContractAttestation()));
        activeGuards(g);

        Decision.Allow allow = (Decision.Allow) gateway.evaluate(action(PrincipalType.AGENT));

        assertThat(allow.flagged()).isFalse();
        assertThat(allow.matchedClauseIds()).containsExactly(g.getId());
        // D17 matched-only level: an ALLOW where >=1 guard matched IS rowed.
        ContractEnforcementDecision row = onlyRow();
        assertThat(row.getDecision()).isEqualTo(GatewayDecision.ALLOW);
        assertThat(row.getClauseIds().get(0).asText()).isEqualTo(g.getId().toString());
    }

    // --------------------------------------------------------- FLAG + never-silent

    @Test
    void flagGuard_allowWithFlag_alwaysRowed() {
        mode("shadow");
        ContractClauseGuard flag = guard(GuardEffect.FLAG, null, null);
        activeGuards(flag);

        Decision.Allow allow = (Decision.Allow) gateway.evaluate(action(PrincipalType.HUMAN));

        assertThat(allow.flagged()).isTrue();
        assertThat(allow.flagReason()).contains(flag.getDescription());
        assertThat(onlyRow().getDecision()).isEqualTo(GatewayDecision.ALLOW_WITH_FLAG);
    }

    @Test
    void indeterminatePredicate_skippedButFlagged_neverSilent() {
        mode("enforce");
        ContractClauseGuard beyondDsl = guard(GuardEffect.DENY, null,
                "{\"sustainedFor\":{\"quarters\":2}}");     // beyond-v1 node (ADR D9)
        activeGuards(beyondDsl);

        Decision.Allow allow = (Decision.Allow) gateway.evaluate(action(PrincipalType.AGENT));

        assertThat(allow.flagged()).isTrue();               // §4.4 — never a silent ALLOW
        assertThat(allow.matchedClauseIds()).isEmpty();     // skipped for matching
        assertThat(allow.flagReason()).contains(ContractGateway.FLAG_UNEVALUABLE_PREDICATE);
        ContractEnforcementDecision row = onlyRow();
        assertThat(row.getDecision()).isEqualTo(GatewayDecision.ALLOW_WITH_FLAG);
        assertThat(row.getClauseIds().get(0).asText())      // the skip is auditable
                .isEqualTo(beyondDsl.getId().toString());
    }

    @Test
    void predicateMatch_drivesDeny_overSharedEvaluationDocument() {
        mode("enforce");
        activeGuards(guard(GuardEffect.DENY, null,
                "{\"all\":[{\"field\":\"payload.scope\",\"eq\":\"hipaa-attestation-forms\"},"
                        + "{\"field\":\"payload.formCount\",\"lte\":40}]}"));
        GovernedAction matching = new GovernedAction("cascade.issue",
                Principal.agent("a-1"), domainId, null,
                json.createObjectNode().put("scope", "hipaa-attestation-forms").put("formCount", 38));

        assertThatThrownBy(() -> gateway.evaluate(matching))
                .isInstanceOf(ContractDeniedException.class);
    }

    // ----------------------------------------------------------- entity narrowing

    @Test
    void entityTypeNarrowing_nullGuardTypeIsWildcard() {
        mode("enforce");
        activeGuards(guard(GuardEffect.DENY, "PROCESS", null));

        // Different target entity type: guard does not apply.
        Decision other = gateway.evaluate(new GovernedAction("cascade.issue",
                Principal.agent("a-1"), domainId, new TargetRef("SYSTEM", "s-1"), null));
        assertThat(((Decision.Allow) other).flagged()).isFalse();

        // Matching target entity type: denied.
        assertThatThrownBy(() -> gateway.evaluate(new GovernedAction("cascade.issue",
                Principal.agent("a-1"), domainId, new TargetRef("PROCESS", "p-1"), null)))
                .isInstanceOf(ContractDeniedException.class);
    }

    // ----------------------------------------------------- decision-log levels

    @Test
    void matchedOnlyLevel_plainNoGuardAllow_isCounterNotRow() {
        mode("enforce");                                     // level default matched-only
        long before = writer.allowNoGuardCount();
        Decision.Allow allow = (Decision.Allow) gateway.evaluate(action(PrincipalType.AGENT));
        assertThat(allow.flagged()).isFalse();
        assertThat(rows).isEmpty();
        assertThat(writer.allowNoGuardCount()).isEqualTo(before + 1);
    }

    @Test
    void levelAll_plainNoGuardAllow_isRowed() {
        mode("enforce");
        level("all");
        gateway.evaluate(action(PrincipalType.AGENT));
        assertThat(onlyRow().getDecision()).isEqualTo(GatewayDecision.ALLOW);
        assertThat(onlyRow().getClauseIds()).isNull();
    }

    @Test
    void matchedOnlyLevel_guardsExistButNoneMatch_isCounterNotRow() {
        mode("enforce");
        activeGuards(guard(GuardEffect.DENY, null, "{\"field\":\"payload.scope\",\"eq\":\"other\"}"));
        long before = writer.allowUnrowedCount();

        Decision.Allow allow = (Decision.Allow) gateway.evaluate(new GovernedAction(
                "cascade.issue", Principal.agent("a-1"), domainId, null,
                json.createObjectNode().put("scope", "this")));

        assertThat(allow.flagged()).isFalse();
        assertThat(rows).isEmpty();
        assertThat(writer.allowUnrowedCount()).isEqualTo(before + 1);
    }

    @Test
    void levelAll_guardsExistButNoneMatch_isRowed() {
        mode("enforce");
        level("all");
        activeGuards(guard(GuardEffect.DENY, null, "{\"field\":\"payload.scope\",\"eq\":\"other\"}"));

        gateway.evaluate(new GovernedAction("cascade.issue", Principal.agent("a-1"),
                domainId, null, json.createObjectNode().put("scope", "this")));

        assertThat(onlyRow().getDecision()).isEqualTo(GatewayDecision.ALLOW);
    }

    // ------------------------------------------------------------- fail posture

    @Test
    void failPosture_human_failOpen_allowWithFlagGatewayError() {
        mode("enforce");
        when(guardRepo.findActiveByDomainId(domainId)).thenThrow(new IllegalStateException("boom"));

        Decision.Allow allow = (Decision.Allow) gateway.evaluate(action(PrincipalType.HUMAN));

        assertThat(allow.flagged()).isTrue();
        assertThat(allow.flagReason()).isEqualTo(ContractGateway.REASON_GATEWAY_ERROR);
        ContractEnforcementDecision row = onlyRow();
        assertThat(row.getDecision()).isEqualTo(GatewayDecision.ALLOW_WITH_FLAG);
        assertThat(row.getReason()).isEqualTo(ContractGateway.REASON_GATEWAY_ERROR);
    }

    @Test
    void failPosture_agent_enforce_failClosedDeny() {
        mode("enforce");
        when(guardRepo.findActiveByDomainId(domainId)).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> gateway.evaluate(action(PrincipalType.AGENT)))
                .isInstanceOf(ContractDeniedException.class)
                .satisfies(thrown -> assertThat(((ContractDeniedException) thrown)
                        .getDeny().reason()).isEqualTo(ContractGateway.REASON_GATEWAY_ERROR));
        assertThat(onlyRow().getDecision()).isEqualTo(GatewayDecision.DENY);
    }

    @Test
    void failPosture_service_shadow_wouldDenyButProceeds() {
        mode("shadow");
        when(guardRepo.findActiveByDomainId(domainId)).thenThrow(new IllegalStateException("boom"));

        Decision.Allow allow = (Decision.Allow) gateway.evaluate(action(PrincipalType.SERVICE));

        assertThat(allow.flagged()).isTrue();               // shadow never blocks (ADR D4)
        assertThat(onlyRow().getDecision()).isEqualTo(GatewayDecision.WOULD_DENY);
        assertThat(onlyRow().getReason()).isEqualTo(ContractGateway.REASON_GATEWAY_ERROR);
    }

    // ------------------------------------------- ADR D16 built-in waiver guard

    @Test
    void builtInWaiverGuard_agentEnforce_deniedWithoutTouchingGuardStore() {
        mode("enforce");

        assertThatThrownBy(() -> gateway.evaluate(
                action(ContractGateway.ACTION_WAIVE_BREACH, PrincipalType.AGENT)))
                .isInstanceOf(ContractDeniedException.class)
                .satisfies(thrown -> assertThat(((ContractDeniedException) thrown)
                        .getDeny().reason())
                        .isEqualTo(ContractGateway.REASON_WAIVER_HUMAN_ONLY));
        assertThat(onlyRow().getDecision()).isEqualTo(GatewayDecision.DENY);
        // Non-removable: lives in code, not in contract_clause_guards.
        verifyNoInteractions(guardRepo);
    }

    @Test
    void builtInWaiverGuard_agentShadow_rowsWouldDeny() {
        mode("shadow");

        Decision.Allow allow = (Decision.Allow) gateway.evaluate(
                action(ContractGateway.ACTION_WAIVE_BREACH, PrincipalType.AGENT));

        assertThat(allow.flagged()).isTrue();
        assertThat(onlyRow().getDecision()).isEqualTo(GatewayDecision.WOULD_DENY);
        assertThat(onlyRow().getReason()).isEqualTo(ContractGateway.REASON_WAIVER_HUMAN_ONLY);
        // The "agents may never waive" INVARIANT still holds in shadow via the
        // BreachService WaiverHumanOnlyException backstop (mode-independent).
    }

    @Test
    void builtInWaiverGuard_doesNotTouchHumanOrServiceWaivers() {
        mode("enforce");
        assertThat(((Decision.Allow) gateway.evaluate(
                action(ContractGateway.ACTION_WAIVE_BREACH, PrincipalType.HUMAN))).flagged())
                .isFalse();
        assertThat(((Decision.Allow) gateway.evaluate(
                action(ContractGateway.ACTION_WAIVE_BREACH, PrincipalType.SERVICE))).flagged())
                .isFalse();
        assertThat(rows).isEmpty();                          // no guards ⇒ counters only
    }

    // --------------------------- agents.md §8 — registry gate + built-in guards

    /** Stub-gate gateway builder for the §8.1 SPI verdicts. */
    private ContractGateway gatewayWith(AgentRegistryGate stubGate) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new ContractGateway(
                new GuardCache(guardRepo, clock), contractRepo,
                new RequireAttestationGate(commitmentRepo, attestationRepo, properties, clock),
                new ContractMetricLeafResolver(metricRepo, properties, clock),
                writer, properties, json, stubGate);
    }

    private static AgentRegistryGate verdictGate(AgentRegistryGate.Verdict verdict) {
        return new AgentRegistryGate() {
            @Override
            public boolean enforcesRegistry() {
                return true;
            }

            @Override
            public AgentRegistryGate.Verdict evaluate(GovernedAction action,
                                                      EnforcementMode mode) {
                return verdict;
            }
        };
    }

    @Test
    void builtInLifecycleGuard_agentEnforce_agentGateDenialWithoutTouchingGuardStore() {
        mode("enforce");
        assertThatThrownBy(() -> gateway.evaluate(action("agent.promote", PrincipalType.AGENT)))
                .isInstanceOf(AgentGateDeniedException.class)
                .satisfies(thrown -> {
                    AgentGateDeniedException denial = (AgentGateDeniedException) thrown;
                    assertThat(denial.code()).isEqualTo(AgentGateDeniedException.AGENT_GATE_CODE);
                    assertThat(denial.getDeny().reason())
                            .isEqualTo(ContractGateway.REASON_AGENT_LIFECYCLE_HUMAN_ONLY);
                });
        assertThat(onlyRow().getDecision()).isEqualTo(GatewayDecision.DENY);
        verifyNoInteractions(guardRepo);                     // built-in, non-removable (§8.4)
    }

    @Test
    void builtInLifecycleGuard_coversDeployToo_andShadowRowsWouldDeny() {
        mode("shadow");
        Decision.Allow allow = (Decision.Allow) gateway.evaluate(
                action("agent.deploy", PrincipalType.AGENT));
        assertThat(allow.flagged()).isTrue();                // shadow never blocks (ADR D4)
        assertThat(onlyRow().getDecision()).isEqualTo(GatewayDecision.WOULD_DENY);
        assertThat(onlyRow().getReason())
                .isEqualTo(ContractGateway.REASON_AGENT_LIFECYCLE_HUMAN_ONLY);
    }

    @Test
    void builtInLifecycleGuard_humanLifecycleActionsPassToTheGuardSet() {
        mode("enforce");
        assertThat(((Decision.Allow) gateway.evaluate(
                action("agent.promote", PrincipalType.HUMAN))).flagged()).isFalse();
        assertThat(rows).isEmpty();                          // no guards: counter only
    }

    @Test
    void registryGateDeny_enforce_throwsAgentGateDenial_rowedFirst() {
        mode("enforce");
        ContractGateway gated = gatewayWith(verdictGate(new AgentRegistryGate.Verdict.Deny(
                AgentRegistryGate.REASON_CONTROL_SUSPENDED, "agent is paused")));

        assertThatThrownBy(() -> gated.evaluate(action(PrincipalType.AGENT)))
                .isInstanceOf(AgentGateDeniedException.class)
                .satisfies(thrown -> {
                    AgentGateDeniedException denial = (AgentGateDeniedException) thrown;
                    assertThat(denial.getDeny().reason())
                            .isEqualTo(AgentRegistryGate.REASON_CONTROL_SUSPENDED);
                    // §10.4: rowed BEFORE the throw, decisionId cited (§8.5)
                    assertThat(denial.getDecisionId()).isEqualTo(onlyRow().getId());
                });
        assertThat(onlyRow().getDecision()).isEqualTo(GatewayDecision.DENY);
        assertThat(onlyRow().getReason()).isEqualTo(AgentRegistryGate.REASON_CONTROL_SUSPENDED);
    }

    @Test
    void registryGateDeny_shadow_rowsWouldDenyAndProceeds() {
        mode("shadow");
        ContractGateway gated = gatewayWith(verdictGate(new AgentRegistryGate.Verdict.Deny(
                AgentRegistryGate.REASON_SHADOW_SCOPE, "observation scope only")));

        Decision.Allow allow = (Decision.Allow) gated.evaluate(action(PrincipalType.AGENT));

        assertThat(allow.flagged()).isTrue();
        assertThat(onlyRow().getDecision()).isEqualTo(GatewayDecision.WOULD_DENY);
        assertThat(onlyRow().getReason()).isEqualTo(AgentRegistryGate.REASON_SHADOW_SCOPE);
    }

    @Test
    void registryGateFlags_rowedAllowWithFlag_evenWithEmptyGuardSet() {
        mode("shadow");
        ContractGateway gated = gatewayWith(verdictGate(new AgentRegistryGate.Verdict.Allow(
                List.of(AgentRegistryGate.REASON_UNREGISTERED + ": unknown agent knocking"))));

        Decision.Allow allow = (Decision.Allow) gated.evaluate(action(PrincipalType.AGENT));

        assertThat(allow.flagged()).isTrue();                // never silent (§8.2)
        assertThat(onlyRow().getDecision()).isEqualTo(GatewayDecision.ALLOW_WITH_FLAG);
        assertThat(onlyRow().getReason())
                .contains(AgentRegistryGate.REASON_UNREGISTERED);
    }

    @Test
    void registryGateFailure_inheritsAdrD5FailClosedForAgents() {
        mode("enforce");
        ContractGateway gated = gatewayWith(new AgentRegistryGate() {
            @Override
            public boolean enforcesRegistry() {
                return true;
            }

            @Override
            public AgentRegistryGate.Verdict evaluate(GovernedAction action,
                                                      EnforcementMode mode) {
                throw new IllegalStateException("registry unreachable");
            }
        });

        assertThatThrownBy(() -> gated.evaluate(action(PrincipalType.AGENT)))
                .isInstanceOf(ContractDeniedException.class)
                .satisfies(thrown -> assertThat(((ContractDeniedException) thrown)
                        .getDeny().reason())
                        .isEqualTo(ContractGateway.REASON_GATEWAY_ERROR));
        assertThat(onlyRow().getDecision()).isEqualTo(GatewayDecision.DENY);
    }

    @Test
    void registryGate_neverInvokedForHumanOrServicePrincipals() {
        mode("enforce");
        ContractGateway gated = gatewayWith(new AgentRegistryGate() {
            @Override
            public boolean enforcesRegistry() {
                return true;
            }

            @Override
            public AgentRegistryGate.Verdict evaluate(GovernedAction action,
                                                      EnforcementMode mode) {
                throw new AssertionError("gate must only see AGENT principals (§8.1)");
            }
        });

        assertThat(((Decision.Allow) gated.evaluate(action(PrincipalType.HUMAN))).flagged())
                .isFalse();
        assertThat(((Decision.Allow) gated.evaluate(action(PrincipalType.SERVICE))).flagged())
                .isFalse();
    }

    // ------------------------------------------------------------- row stamping

    @Test
    void dispatchIdAndPrincipalAreStampedOnRows() {
        mode("shadow");
        ContractClauseGuard deny = guard(GuardEffect.DENY, null, null);
        deny.setActionType("apply_dispatch_result");         // §4.2 PEP 3 action
        activeGuards(deny);

        gateway.evaluate(new GovernedAction("apply_dispatch_result", Principal.service(),
                domainId, null, null, "dispatch-42"));

        ContractEnforcementDecision row = onlyRow();
        assertThat(row.getDispatchId()).isEqualTo("dispatch-42");
        assertThat(row.getActorType()).isEqualTo(PrincipalType.SERVICE);
        assertThat(row.getActorId()).isEqualTo(Principal.SUBSTRATE_SERVICE_ID);
        assertThat(row.getDomainId()).isEqualTo(domainId);
        assertThat(row.getDecidedAt()).isEqualTo(NOW);
    }
}
