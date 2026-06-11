package com.meridian.substrate.common.domain.enums;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the engine-spec contracts.md §2 state machines AS DATA (chunk 3a-1):
 * exhaustive transition tables for CONTRACT (§2.2), BREACH (§2.4),
 * MONITOR (§2.5) and the commitment-compliance projection (§2.3), plus the
 * two binding hard rules encoded on the enums:
 * <ul>
 *   <li>WITHDRAWN / CONCLUDED exist but are transition-UNREACHABLE (ADR D7) —
 *       no transition set contains them, and they have no outbound edges;</li>
 *   <li>INDETERMINATE / NOT_DUE / ERROR monitor outcomes NEVER open or
 *       refresh a breach (ADR D10).</li>
 * </ul>
 * Full service-level transition tests (409 codes etc.) belong to the 3a/3b
 * behavior chunks (spec §14.3); this test pins the data layer they build on.
 */
class ContractStateMachineDataTest {

    // ------------------------------------------------------------ CONTRACT §2.2

    @Test
    void contractMachineHasExactlyTheSpecEdges() {
        assertThat(ContractStatus.PROPOSED.legalTargets())
                .containsExactlyInAnyOrder(ContractStatus.ACTIVE, ContractStatus.DECLINED);
        assertThat(ContractStatus.ACTIVE.legalTargets())
                .containsExactly(ContractStatus.DECLINED);
        assertThat(ContractStatus.DECLINED.legalTargets()).isEmpty();
    }

    @Test
    void declinedIsTheOnlyReachableTerminal() {
        assertThat(ContractStatus.DECLINED.isTerminal()).isTrue();
        assertThat(ContractStatus.PROPOSED.isTerminal()).isFalse();
        assertThat(ContractStatus.ACTIVE.isTerminal()).isFalse();
    }

    @Test
    void withdrawnAndConcludedAreSchemaReservedAndUnreachable() {
        // ADR D7 / spec §15 extension point #2: no inbound edge anywhere ...
        for (ContractStatus from : ContractStatus.values()) {
            assertThat(from.legalTargets())
                    .as("no transition from %s may reach a schema-reserved status", from)
                    .doesNotContain(ContractStatus.WITHDRAWN, ContractStatus.CONCLUDED);
        }
        // ... and no outbound edges either.
        assertThat(ContractStatus.WITHDRAWN.legalTargets()).isEmpty();
        assertThat(ContractStatus.CONCLUDED.legalTargets()).isEmpty();
        assertThat(ContractStatus.WITHDRAWN.isSchemaReservedUnreachable()).isTrue();
        assertThat(ContractStatus.CONCLUDED.isSchemaReservedUnreachable()).isTrue();
        assertThat(ContractStatus.PROPOSED.isSchemaReservedUnreachable()).isFalse();
        assertThat(ContractStatus.ACTIVE.isSchemaReservedUnreachable()).isFalse();
        assertThat(ContractStatus.DECLINED.isSchemaReservedUnreachable()).isFalse();
    }

    // ------------------------------------------------------------ BREACH §2.4

    @Test
    void breachMachineIsMonotonicForwardOnly() {
        assertThat(BreachStatus.DETECTED.legalTargets()).containsExactly(BreachStatus.SURFACED);
        assertThat(BreachStatus.SURFACED.legalTargets()).containsExactly(BreachStatus.ACKNOWLEDGED);
        assertThat(BreachStatus.ACKNOWLEDGED.legalTargets())
                .containsExactlyInAnyOrder(BreachStatus.REMEDIATED, BreachStatus.WAIVED);
        assertThat(BreachStatus.REMEDIATED.legalTargets()).isEmpty();
        assertThat(BreachStatus.WAIVED.legalTargets()).isEmpty();

        // forward-only: no edge ever points to an earlier (lower-ordinal) status
        for (BreachStatus from : BreachStatus.values()) {
            for (BreachStatus to : from.legalTargets()) {
                assertThat(to.ordinal())
                        .as("%s -> %s must move forward", from, to)
                        .isGreaterThan(from.ordinal());
            }
        }
    }

    @Test
    void openBreachStatusesMatchTheV5_1PartialIndexPredicate() {
        // status IN ('DETECTED','SURFACED','ACKNOWLEDGED') — keep in lockstep
        assertThat(Set.of(BreachStatus.DETECTED, BreachStatus.SURFACED, BreachStatus.ACKNOWLEDGED))
                .allMatch(BreachStatus::isOpen);
        assertThat(BreachStatus.REMEDIATED.isOpen()).isFalse();
        assertThat(BreachStatus.WAIVED.isOpen()).isFalse();
    }

    // ------------------------------------------------------------ MONITOR §2.5

    @Test
    void monitorMachineIsActiveSuspendedToggle() {
        assertThat(MonitorStatus.ACTIVE.legalTargets()).containsExactly(MonitorStatus.SUSPENDED);
        assertThat(MonitorStatus.SUSPENDED.legalTargets()).containsExactly(MonitorStatus.ACTIVE);
    }

    @Test
    void onlyBreachDetectedOutcomeMayTouchBreachState() {
        // ADR D10 hard invariant, encoded as data
        assertThat(MonitorRunOutcome.BREACH_DETECTED.mayOpenOrRefreshBreach()).isTrue();
        assertThat(MonitorRunOutcome.COMPLIANT.mayOpenOrRefreshBreach()).isFalse();
        assertThat(MonitorRunOutcome.INDETERMINATE.mayOpenOrRefreshBreach()).isFalse();
        assertThat(MonitorRunOutcome.NOT_DUE.mayOpenOrRefreshBreach()).isFalse();
        assertThat(MonitorRunOutcome.ERROR.mayOpenOrRefreshBreach()).isFalse();
    }

    // ------------------------------------- COMMITMENT COMPLIANCE projection §2.3

    @Test
    void commitmentComplianceEdgesMatchSpecDiagramPlusBreachInvariant() {
        assertThat(CommitmentComplianceState.UNEVALUATED.legalTargets())
                .containsExactlyInAnyOrder(CommitmentComplianceState.COMPLIANT,
                        CommitmentComplianceState.AT_RISK, CommitmentComplianceState.BREACHED);
        assertThat(CommitmentComplianceState.COMPLIANT.legalTargets())
                .containsExactlyInAnyOrder(CommitmentComplianceState.AT_RISK,
                        CommitmentComplianceState.BREACHED);
        assertThat(CommitmentComplianceState.AT_RISK.legalTargets())
                .containsExactlyInAnyOrder(CommitmentComplianceState.COMPLIANT,
                        CommitmentComplianceState.BREACHED);
        // breach remediated -> COMPLIANT; breach waived -> WAIVED (§2.4)
        assertThat(CommitmentComplianceState.BREACHED.legalTargets())
                .containsExactlyInAnyOrder(CommitmentComplianceState.COMPLIANT,
                        CommitmentComplianceState.WAIVED);
        assertThat(CommitmentComplianceState.WAIVED.legalTargets()).isEmpty();
    }

    // ------------------------------------------------------------ GUARD §4.3

    @Test
    void guardEffectDeclarationOrderIsEvaluationPrecedence() {
        // DENY > REQUIRE_ATTESTATION > FLAG (spec §4.3) — enum order carries it
        assertThat(GuardEffect.values()).containsExactly(
                GuardEffect.DENY, GuardEffect.REQUIRE_ATTESTATION, GuardEffect.FLAG);
    }
}
