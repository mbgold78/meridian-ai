package com.meridian.substrate.engine.cascade;

import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeLevel;
import com.meridian.substrate.common.domain.CascadeLevelAction;
import com.meridian.substrate.common.domain.enums.CascadeActionType;
import com.meridian.substrate.common.domain.enums.CascadeAttentionSignal;
import com.meridian.substrate.common.domain.enums.CascadeAuthoringMode;
import com.meridian.substrate.common.domain.enums.CascadeLevelState;
import com.meridian.substrate.common.domain.enums.CascadeState;
import com.meridian.substrate.common.domain.enums.ProducedBy;
import com.meridian.substrate.engine.cascade.exception.CascadeConflictException;
import com.meridian.substrate.engine.cascade.exception.CascadeForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Test plan #1 (engine-spec cascade.md §12): state-machine table tests over the
 * pure {@link CascadeStateMachine} — every legal transition A1–A3 / L1–L6,
 * every illegal combination, invariant I4 payload conditionals, frontier rules
 * (§5.1) including the pending-decline-run definition, and the §2 attention
 * signal derivation table including the I6 assertion.
 *
 * <p>Pure unit lane: no Spring, no database — the same functions the live
 * service and the projection rebuilder share (ADR D1).
 */
class CascadeStateMachineTest {

    private static final Instant T0 = Instant.parse("2026-04-08T00:00:00Z");
    private static final int SLA_DAYS = 7;
    private static final String ORIGINATOR = "Operating Partner";
    private static final String L1_OWNER = "Curasync COO";
    private static final String L2_OWNER = "Curasync HIM Director";
    private static final String L3_OWNER = "Curasync Analyst";

    private final CascadeStateMachine sm = new CascadeStateMachine();
    private Cascade cascade;
    private NavigableMap<Integer, CascadeLevel> levels;
    private int actionCount;

    @BeforeEach
    void setUp() {
        cascade = new Cascade();
        cascade.setId(UUID.randomUUID());
        cascade.setDomainId(UUID.randomUUID());
        cascade.setOriginOwnerLabel(ORIGINATOR);
        cascade.setOriginatedAt(T0);
        cascade.setOriginalCritique("Original critique");
        cascade.setAcceptanceCriteria("Acceptance criteria");
        cascade.setAuthoringMode(CascadeAuthoringMode.FREE_FORM);
        cascade.setProducedBy(ProducedBy.HUMAN);
        cascade.setResponseSlaDays(SLA_DAYS);
        levels = new TreeMap<>();
        actionCount = 0;
    }

    // ------------------------------------------------------------ action builders

    private CascadeLevelAction action(CascadeActionType type, int depth, int round,
                                      Consumer<CascadeLevelAction> payload) {
        CascadeLevelAction a = new CascadeLevelAction();
        a.setId(UUID.randomUUID());
        a.setCascadeId(cascade.getId());
        a.setLevelId(UUID.randomUUID());
        a.setDepth(depth);
        a.setRound(round);
        a.setAction(type);
        a.setActorLabel("actor-" + depth);
        a.setActedAt(T0.plus(Duration.ofDays(++actionCount)));
        a.setIdempotencyKey("key-" + actionCount);
        a.setChainSeq(cascade.getChainSeq() + 1);
        payload.accept(a);
        return a;
    }

    private void apply(CascadeLevelAction a) {
        sm.apply(cascade, levels, a, SLA_DAYS);
    }

    private CascadeLevelAction issueAction() {
        return action(CascadeActionType.ISSUE, 0, 1, a -> {
            a.setActorLabel(ORIGINATOR);
            a.setRefinedCritique("Original critique");
            a.setNextOwnerLabel(L1_OWNER);
        });
    }

    private void issue() {
        apply(issueAction());
    }

    private void cascadeAt(int depth, String nextOwner) {
        apply(action(CascadeActionType.CASCADE, depth, levels.get(depth).getRound(), a -> {
            a.setRefinedCritique("Refined at L" + depth);
            a.setNextOwnerLabel(nextOwner);
        }));
    }

    private CascadeLevelAction declineAt(int depth) {
        CascadeLevelAction a = action(CascadeActionType.DECLINE, depth, levels.get(depth).getRound(),
                x -> x.setDeclineReasoning("Documented pushback at L" + depth));
        apply(a);
        return a;
    }

    private void absorbAt(int depth) {
        apply(action(CascadeActionType.ABSORB, depth, levels.get(depth).getRound(),
                a -> a.setRefinedCritique("Absorbed at L" + depth)));
    }

    private void recascadeAt(int depth, String nextOwner) {
        apply(action(CascadeActionType.RECASCADE, depth, levels.get(depth).getRound(), a -> {
            a.setRefinedCritique("Revised refinement from L" + depth);
            a.setNextOwnerLabel(nextOwner);
        }));
    }

    private void resolveLooksGood() {
        apply(action(CascadeActionType.RESOLVE_LOOKS_GOOD, 0, 1, a -> {
            a.setActorLabel(ORIGINATOR);
            a.setResolutionNote("Outcome accepted");
        }));
    }

    private void resolveDeclined(UUID acceptedDeclineActionId) {
        apply(action(CascadeActionType.RESOLVE_DECLINED, 0, 1, a -> {
            a.setActorLabel(ORIGINATOR);
            a.setResolutionNote("Decline accepted");
            a.setAcceptedDeclineActionId(acceptedDeclineActionId);
        }));
    }

    private static void assertConflict(Throwable t, CascadeConflictException.Code code) {
        assertThat(t).isInstanceOf(CascadeConflictException.class);
        assertThat(((CascadeConflictException) t).getCode()).isEqualTo(code);
    }

    // ----------------------------------------------------------------- A1: ISSUE

    @Nested
    class IssueTransitions {

        @Test
        void issue_createsL0CascadedAndL1Looking_frontier1_escalating() {
            issue();

            assertThat(cascade.getCurrentState()).isEqualTo(CascadeState.IN_FLIGHT);
            assertThat(cascade.getChainSeq()).isEqualTo(1);
            assertThat(cascade.getFrontierDepth()).isEqualTo(1);
            assertThat(cascade.getFrontierState()).isEqualTo(CascadeLevelState.LOOKING);
            assertThat(cascade.getAttentionSignal()).isEqualTo(CascadeAttentionSignal.ESCALATING);
            assertThat(cascade.isOverdue()).isFalse();

            CascadeLevel l0 = levels.get(0);
            assertThat(l0.getCurrentState()).isEqualTo(CascadeLevelState.CASCADED);
            assertThat(l0.getOwnerLabel()).isEqualTo(ORIGINATOR);
            assertThat(l0.getRound()).isEqualTo(1);
            assertThat(l0.getRefinedCritique()).isEqualTo("Original critique");
            assertThat(l0.getActedAt()).isNotNull();
            assertThat(l0.getResponseDeadline()).isNull();

            CascadeLevel l1 = levels.get(1);
            assertThat(l1.getCurrentState()).isEqualTo(CascadeLevelState.LOOKING);
            assertThat(l1.getOwnerLabel()).isEqualTo(L1_OWNER);
            assertThat(l1.getRound()).isEqualTo(1);
            assertThat(l1.getActedAt()).isNull();
            assertThat(l1.getResponseDeadline())
                    .isEqualTo(l1.getActivatedAt().plus(Duration.ofDays(SLA_DAYS)));
        }

        @Test
        void issue_slaDisabled_leavesDeadlineNull() {
            CascadeLevelAction a = issueAction();
            sm.apply(cascade, levels, a, 0);
            assertThat(levels.get(1).getResponseDeadline()).isNull();
        }

        @Test
        void issue_isOnlyLegalAsFirstAction() {
            issue();
            assertThatThrownBy(CascadeStateMachineTest.this::issue)
                    .satisfies(t -> assertConflict(t,
                            CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
        }
    }

    // -------------------------------------------------------- L1–L6 level verbs

    @Nested
    class LevelTransitions {

        @Test
        void l1_cascadeAtLookingFrontier_advancesFrontier() {
            issue();
            cascadeAt(1, L2_OWNER);

            assertThat(levels.get(1).getCurrentState()).isEqualTo(CascadeLevelState.CASCADED);
            assertThat(levels.get(1).getRefinedCritique()).isEqualTo("Refined at L1");
            assertThat(levels.get(2).getCurrentState()).isEqualTo(CascadeLevelState.LOOKING);
            assertThat(levels.get(2).getRound()).isEqualTo(1);
            assertThat(levels.get(2).getOwnerLabel()).isEqualTo(L2_OWNER);
            assertThat(cascade.getFrontierDepth()).isEqualTo(2);
            assertThat(cascade.getAttentionSignal()).isEqualTo(CascadeAttentionSignal.ESCALATING);
            assertThat(cascade.getChainSeq()).isEqualTo(2);
        }

        @Test
        void l2_absorbAtLookingFrontier_terminatesDownwardPropagation_staysInFlight() {
            issue();
            cascadeAt(1, L2_OWNER);
            absorbAt(2);

            assertThat(levels.get(2).getCurrentState()).isEqualTo(CascadeLevelState.ABSORBED);
            assertThat(levels.get(2).getRefinedCritique()).isEqualTo("Absorbed at L2");
            assertThat(cascade.getFrontierDepth()).isEqualTo(2);
            assertThat(cascade.getFrontierState()).isEqualTo(CascadeLevelState.ABSORBED);
            assertThat(cascade.getCurrentState()).isEqualTo(CascadeState.IN_FLIGHT);
            assertThat(cascade.getAttentionSignal()).isEqualTo(CascadeAttentionSignal.ABSORBED);
        }

        @Test
        void l3_declineAtLookingFrontier_beginsDeclinePending() {
            issue();
            cascadeAt(1, L2_OWNER);
            declineAt(2);

            assertThat(levels.get(2).getCurrentState()).isEqualTo(CascadeLevelState.DECLINED);
            assertThat(levels.get(2).getDeclineReasoning()).isEqualTo("Documented pushback at L2");
            assertThat(cascade.getFrontierDepth()).isEqualTo(2);
            assertThat(cascade.getFrontierState()).isEqualTo(CascadeLevelState.DECLINED);
            assertThat(cascade.getCurrentState()).isEqualTo(CascadeState.IN_FLIGHT);
            assertThat(cascade.getAttentionSignal()).isEqualTo(CascadeAttentionSignal.DECLINE_PENDING);
        }

        @Test
        void l4_endorseDecline_growsTheRunUpward() {
            issue();
            cascadeAt(1, L2_OWNER);
            declineAt(2);
            declineAt(1); // endorse: CASCADED → DECLINED with own reasoning

            assertThat(levels.get(1).getCurrentState()).isEqualTo(CascadeLevelState.DECLINED);
            assertThat(levels.get(1).getDeclineReasoning()).isEqualTo("Documented pushback at L1");
            // The endorser's earlier refinement remains its current-round projection.
            assertThat(levels.get(1).getRefinedCritique()).isEqualTo("Refined at L1");
            assertThat(cascade.getFrontierDepth()).isEqualTo(1);
            assertThat(cascade.getFrontierState()).isEqualTo(CascadeLevelState.DECLINED);
            assertThat(sm.pendingDeclineRun(cascade, levels)).containsExactly(1, 2);
        }

        @Test
        void l5_recascade_reactivatesDeclinedLevelAsNewRound() {
            issue();
            cascadeAt(1, L2_OWNER);
            declineAt(2);
            recascadeAt(1, "Curasync HIM Director (round 2)");

            CascadeLevel l2 = levels.get(2);
            assertThat(l2.getCurrentState()).isEqualTo(CascadeLevelState.LOOKING);
            assertThat(l2.getRound()).isEqualTo(2);
            assertThat(l2.getOwnerLabel()).isEqualTo("Curasync HIM Director (round 2)");
            assertThat(l2.getRefinedCritique()).isNull();
            assertThat(l2.getDeclineReasoning()).isNull();
            assertThat(l2.getActedAt()).isNull();
            assertThat(l2.getResponseDeadline())
                    .isEqualTo(l2.getActivatedAt().plus(Duration.ofDays(SLA_DAYS)));
            // The recascading level carries the revised refinement.
            assertThat(levels.get(1).getRefinedCritique()).isEqualTo("Revised refinement from L1");
            assertThat(levels.get(1).getCurrentState()).isEqualTo(CascadeLevelState.CASCADED);
            assertThat(cascade.getFrontierDepth()).isEqualTo(2);
            assertThat(cascade.getFrontierState()).isEqualTo(CascadeLevelState.LOOKING);
            assertThat(cascade.getAttentionSignal()).isEqualTo(CascadeAttentionSignal.ESCALATING);
        }

        @Test
        void deepeningAfterRecascade_round2LevelCascades_createsRound1LevelBelow() {
            issue();
            cascadeAt(1, L2_OWNER);
            declineAt(2);
            recascadeAt(1, L2_OWNER);
            cascadeAt(2, L3_OWNER); // round-2 L2 acts: deepening after recascade

            assertThat(levels.get(2).getCurrentState()).isEqualTo(CascadeLevelState.CASCADED);
            assertThat(levels.get(2).getRound()).isEqualTo(2);
            assertThat(levels.get(3).getCurrentState()).isEqualTo(CascadeLevelState.LOOKING);
            assertThat(levels.get(3).getRound()).isEqualTo(1);
            assertThat(cascade.getFrontierDepth()).isEqualTo(3);
        }

        @Test
        void recascadeTwice_roundIncrementsOnEveryReactivation() {
            issue();
            cascadeAt(1, L2_OWNER);
            declineAt(2);
            recascadeAt(1, L2_OWNER);
            declineAt(2);
            recascadeAt(1, L2_OWNER);

            assertThat(levels.get(2).getRound()).isEqualTo(3);
            assertThat(levels.get(2).getCurrentState()).isEqualTo(CascadeLevelState.LOOKING);
        }

        @Test
        void l1_cascadeOverStaleDeclinedRow_reactivatesItAsNewRound() {
            // Build a stale L3 row: L3 declines, L2 endorses, L1 recascades L2
            // (round 2); when round-2 L2 cascades again, the old DECLINED L3 row
            // must be REACTIVATED (round+1), not recreated (§5.3 L1).
            issue();
            cascadeAt(1, L2_OWNER);
            cascadeAt(2, L3_OWNER);
            declineAt(3);
            declineAt(2); // endorse — frontier 2, run [2, 3]
            recascadeAt(1, "Replacement L2 owner");
            cascadeAt(2, "Replacement L3 owner");

            CascadeLevel l3 = levels.get(3);
            assertThat(l3.getRound()).isEqualTo(2);
            assertThat(l3.getCurrentState()).isEqualTo(CascadeLevelState.LOOKING);
            assertThat(l3.getOwnerLabel()).isEqualTo("Replacement L3 owner");
            assertThat(l3.getDeclineReasoning()).isNull();
            assertThat(cascade.getFrontierDepth()).isEqualTo(3);
        }

        @Test
        void l6_absorbOverDecline_leavesDeclinedLevelInHistory_notPending() {
            issue();
            cascadeAt(1, L2_OWNER);
            declineAt(2);
            absorbAt(1); // CASCADED L1 takes the work over declined L2

            assertThat(levels.get(1).getCurrentState()).isEqualTo(CascadeLevelState.ABSORBED);
            assertThat(levels.get(1).getRefinedCritique()).isEqualTo("Absorbed at L1");
            // k+1 stays DECLINED historically but is no longer pending (§5.3 L6).
            assertThat(levels.get(2).getCurrentState()).isEqualTo(CascadeLevelState.DECLINED);
            assertThat(cascade.getFrontierDepth()).isEqualTo(1);
            assertThat(cascade.getFrontierState()).isEqualTo(CascadeLevelState.ABSORBED);
            assertThat(cascade.getAttentionSignal()).isEqualTo(CascadeAttentionSignal.ABSORBED);
            assertThat(sm.pendingDeclineRun(cascade, levels)).isEmpty();
        }
    }

    // ------------------------------------------------------- A2/A3: resolution

    @Nested
    class ResolveTransitions {

        @Test
        void a2_resolveLooksGood_overLookingFrontier_originatorShortCircuit() {
            issue();
            cascadeAt(1, L2_OWNER);
            resolveLooksGood(); // frontier L2 still LOOKING — legal "any time in-flight"

            assertThat(cascade.getCurrentState()).isEqualTo(CascadeState.RESOLVED_LOOKS_GOOD);
            assertThat(cascade.getAttentionSignal())
                    .isEqualTo(CascadeAttentionSignal.RESOLVED_LOOKS_GOOD);
            assertThat(cascade.getResolutionNote()).isEqualTo("Outcome accepted");
            assertThat(cascade.getResolvedAt()).isNotNull();
            assertThat(cascade.getAcceptedDeclineActionId()).isNull();
            // Historical truth: the LOOKING row never acted before resolution.
            assertThat(levels.get(2).getCurrentState()).isEqualTo(CascadeLevelState.LOOKING);
        }

        @Test
        void a2_resolveLooksGood_overPendingDecline_cVendorCostPattern() {
            issue();
            cascadeAt(1, L2_OWNER);
            declineAt(2);
            resolveLooksGood(); // acceptanceCriteria enumerated a documented Decline as acceptable

            assertThat(cascade.getCurrentState()).isEqualTo(CascadeState.RESOLVED_LOOKS_GOOD);
        }

        @Test
        void a3_resolveDeclined_acceptsLatestDeclineInPendingRun() {
            issue();
            cascadeAt(1, L2_OWNER);
            CascadeLevelAction decline = declineAt(2);
            sm.validateAcceptedDecline(cascade, levels, decline);
            resolveDeclined(decline.getId());

            assertThat(cascade.getCurrentState()).isEqualTo(CascadeState.RESOLVED_DECLINED);
            assertThat(cascade.getAttentionSignal())
                    .isEqualTo(CascadeAttentionSignal.RESOLVED_DECLINED);
            assertThat(cascade.getAcceptedDeclineActionId()).isEqualTo(decline.getId());
            assertThat(cascade.getResolvedByLabel()).isEqualTo(ORIGINATOR);
        }

        @Test
        void a3_acceptedDecline_anyMemberOfTheRunIsAcceptable() {
            issue();
            cascadeAt(1, L2_OWNER);
            CascadeLevelAction deeper = declineAt(2);
            declineAt(1); // run is now [1, 2]
            sm.validateAcceptedDecline(cascade, levels, deeper); // deeper member: legal
        }

        @Test
        void a3_withoutPendingDecline_isIllegal() {
            issue();
            assertThatThrownBy(() -> resolveDeclined(UUID.randomUUID()))
                    .satisfies(t -> assertConflict(t,
                            CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
        }

        @Test
        void a3_staleRoundDecline_isNotTheLatest_rejected() {
            issue();
            cascadeAt(1, L2_OWNER);
            CascadeLevelAction round1Decline = declineAt(2);
            recascadeAt(1, L2_OWNER);
            declineAt(2); // round-2 decline; round-1 decline is stale

            assertThatThrownBy(() -> sm.validateAcceptedDecline(cascade, levels, round1Decline))
                    .satisfies(t -> assertConflict(t,
                            CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
        }

        @Test
        void a3_nonDeclineAction_rejected() {
            issue();
            cascadeAt(1, L2_OWNER);
            declineAt(2);
            CascadeLevelAction notADecline = new CascadeLevelAction();
            notADecline.setId(UUID.randomUUID());
            notADecline.setCascadeId(cascade.getId());
            notADecline.setAction(CascadeActionType.CASCADE);
            assertThatThrownBy(() -> sm.validateAcceptedDecline(cascade, levels, notADecline))
                    .satisfies(t -> assertConflict(t,
                            CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
        }

        @Test
        void terminalStatesAreAbsorbing_anyFurtherActionConflicts() {
            issue();
            cascadeAt(1, L2_OWNER);
            resolveLooksGood();

            assertThatThrownBy(() -> declineAt(2))
                    .satisfies(t -> assertConflict(t, CascadeConflictException.Code.CASCADE_TERMINAL));
            assertThatThrownBy(CascadeStateMachineTest.this::resolveLooksGood)
                    .satisfies(t -> assertConflict(t, CascadeConflictException.Code.CASCADE_TERMINAL));
        }
    }

    // ------------------------------------------------------ illegal combinations

    @Nested
    class IllegalTransitions {

        @Test
        void actOnNonFrontier_isIllegal() {
            issue();
            cascadeAt(1, L2_OWNER);
            // L1 is CASCADED and the frontier (L2) is LOOKING, not DECLINED:
            // no decline-response verb is available — acting again is illegal.
            assertThatThrownBy(() -> absorbAt(1)).satisfies(t ->
                    assertConflict(t, CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
            assertThatThrownBy(() -> declineAt(1)).satisfies(t ->
                    assertConflict(t, CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
            assertThatThrownBy(() -> recascadeAt(1, L2_OWNER)).satisfies(t ->
                    assertConflict(t, CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
            assertThatThrownBy(() -> cascadeAt(1, L2_OWNER)).satisfies(t ->
                    assertConflict(t, CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
        }

        @Test
        void actTwice_lookingLevelLeavesLookingExactlyOnce() {
            issue();
            cascadeAt(1, L2_OWNER);
            absorbAt(2);
            assertThatThrownBy(() -> absorbAt(2)).satisfies(t ->
                    assertConflict(t, CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
        }

        @Test
        void verbsOnAbsorbedOrDeclinedLevels_neverActAgain() {
            issue();
            cascadeAt(1, L2_OWNER);
            declineAt(2);
            // DECLINED level never acts again:
            assertThatThrownBy(() -> cascadeAt(2, L3_OWNER)).satisfies(t ->
                    assertConflict(t, CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
            assertThatThrownBy(() -> declineAt(2)).satisfies(t ->
                    assertConflict(t, CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
            absorbAt(1);
            // ABSORBED level never acts again:
            assertThatThrownBy(() -> declineAt(1)).satisfies(t ->
                    assertConflict(t, CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
            assertThatThrownBy(() -> cascadeAt(1, L2_OWNER)).satisfies(t ->
                    assertConflict(t, CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
        }

        @Test
        void endorseDecline_whenFrontierNotDeclined_isIllegal() {
            issue();
            cascadeAt(1, L2_OWNER);
            absorbAt(2); // frontier ABSORBED, not DECLINED
            assertThatThrownBy(() -> declineAt(1)).satisfies(t ->
                    assertConflict(t, CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
        }

        @Test
        void recascade_whenFrontierNotDeclined_isIllegal() {
            issue();
            cascadeAt(1, L2_OWNER);
            assertThatThrownBy(() -> recascadeAt(1, L3_OWNER)).satisfies(t ->
                    assertConflict(t, CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
        }

        @Test
        void l0HasNoLevelVerbs() {
            issue();
            // L1 declines first-touch → frontier 1 DECLINED; depth 0 sits above
            // the run but the originator's verbs are resolve-only (§5.3).
            declineAt(1);
            assertThatThrownBy(() -> apply(action(CascadeActionType.ABSORB, 0, 1,
                    a -> a.setRefinedCritique("L0 absorb"))))
                    .satisfies(t -> assertConflict(t,
                            CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
            assertThatThrownBy(() -> apply(action(CascadeActionType.DECLINE, 0, 1,
                    a -> a.setDeclineReasoning("L0 decline"))))
                    .satisfies(t -> assertConflict(t,
                            CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
            assertThatThrownBy(() -> apply(action(CascadeActionType.RECASCADE, 0, 1, a -> {
                a.setRefinedCritique("L0 recascade");
                a.setNextOwnerLabel(L1_OWNER);
            }))).satisfies(t -> assertConflict(t,
                    CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
        }

        @Test
        void actOnNonexistentDepth_isIllegal() {
            issue();
            assertThatThrownBy(() -> apply(action(CascadeActionType.CASCADE, 5, 1, a -> {
                a.setRefinedCritique("ghost");
                a.setNextOwnerLabel("ghost owner");
            }))).satisfies(t -> assertConflict(t,
                    CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
        }
    }

    // -------------------------------------------------- I4 payload conditionals

    @Nested
    class PayloadConditionals {

        @Test
        void refinedCritique_requiredForIssueCascadeAbsorbRecascade() {
            assertThatThrownBy(() -> apply(action(CascadeActionType.ISSUE, 0, 1,
                    a -> a.setNextOwnerLabel(L1_OWNER))))
                    .satisfies(t -> assertConflict(t,
                            CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
            issue();
            assertThatThrownBy(() -> apply(action(CascadeActionType.CASCADE, 1, 1,
                    a -> a.setNextOwnerLabel(L2_OWNER))))
                    .satisfies(t -> assertConflict(t,
                            CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
            assertThatThrownBy(() -> apply(action(CascadeActionType.ABSORB, 1, 1, a -> {
            }))).satisfies(t -> assertConflict(t,
                    CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
        }

        @Test
        void declineReasoning_requiredForDecline_andOnlyForDecline() {
            issue();
            assertThatThrownBy(() -> apply(action(CascadeActionType.DECLINE, 1, 1, a -> {
            }))).satisfies(t -> assertConflict(t,
                    CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
            // forbidden on cascade:
            assertThatThrownBy(() -> apply(action(CascadeActionType.CASCADE, 1, 1, a -> {
                a.setRefinedCritique("Refined");
                a.setNextOwnerLabel(L2_OWNER);
                a.setDeclineReasoning("smuggled");
            }))).satisfies(t -> assertConflict(t,
                    CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
        }

        @Test
        void refinedCritique_forbiddenOnDecline() {
            issue();
            assertThatThrownBy(() -> apply(action(CascadeActionType.DECLINE, 1, 1, a -> {
                a.setDeclineReasoning("Reasoned");
                a.setRefinedCritique("smuggled");
            }))).satisfies(t -> assertConflict(t,
                    CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
        }

        @Test
        void resolutionNote_requiredForResolve_andForbiddenElsewhere() {
            issue();
            assertThatThrownBy(() -> apply(action(CascadeActionType.RESOLVE_LOOKS_GOOD, 0, 1, a -> {
            }))).satisfies(t -> assertConflict(t,
                    CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
            assertThatThrownBy(() -> apply(action(CascadeActionType.CASCADE, 1, 1, a -> {
                a.setRefinedCritique("Refined");
                a.setNextOwnerLabel(L2_OWNER);
                a.setResolutionNote("smuggled");
            }))).satisfies(t -> assertConflict(t,
                    CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
        }

        @Test
        void nextOwner_requiredForIssueCascadeRecascade_forbiddenOnAbsorb() {
            issue();
            assertThatThrownBy(() -> apply(action(CascadeActionType.CASCADE, 1, 1,
                    a -> a.setRefinedCritique("Refined"))))
                    .satisfies(t -> assertConflict(t,
                            CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
            assertThatThrownBy(() -> apply(action(CascadeActionType.ABSORB, 1, 1, a -> {
                a.setRefinedCritique("Absorbed");
                a.setNextOwnerLabel("smuggled");
            }))).satisfies(t -> assertConflict(t,
                    CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
        }

        @Test
        void acceptedDeclineActionId_requiredIffResolveDeclined() {
            issue();
            declineAt(1);
            assertThatThrownBy(() -> apply(action(CascadeActionType.RESOLVE_DECLINED, 0, 1,
                    a -> a.setResolutionNote("note"))))
                    .satisfies(t -> assertConflict(t,
                            CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
            assertThatThrownBy(() -> apply(action(CascadeActionType.RESOLVE_LOOKS_GOOD, 0, 1, a -> {
                a.setResolutionNote("note");
                a.setAcceptedDeclineActionId(UUID.randomUUID());
            }))).satisfies(t -> assertConflict(t,
                    CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION));
        }
    }

    // ------------------------------------------- signal derivation + invariants

    @Nested
    class SignalDerivationAndInvariants {

        @Test
        void signalTable_perSpecSection2() {
            assertThat(sm.deriveSignal(CascadeState.IN_FLIGHT, CascadeLevelState.DECLINED))
                    .isEqualTo(CascadeAttentionSignal.DECLINE_PENDING);
            assertThat(sm.deriveSignal(CascadeState.IN_FLIGHT, CascadeLevelState.LOOKING))
                    .isEqualTo(CascadeAttentionSignal.ESCALATING);
            assertThat(sm.deriveSignal(CascadeState.IN_FLIGHT, CascadeLevelState.ABSORBED))
                    .isEqualTo(CascadeAttentionSignal.ABSORBED);
            assertThat(sm.deriveSignal(CascadeState.RESOLVED_LOOKS_GOOD, CascadeLevelState.LOOKING))
                    .isEqualTo(CascadeAttentionSignal.RESOLVED_LOOKS_GOOD);
            assertThat(sm.deriveSignal(CascadeState.RESOLVED_DECLINED, CascadeLevelState.DECLINED))
                    .isEqualTo(CascadeAttentionSignal.RESOLVED_DECLINED);
        }

        @Test
        void i6_frontierCascadedWhileInFlight_assertsNeverRepairs() {
            assertThatThrownBy(() -> sm.deriveSignal(CascadeState.IN_FLIGHT, CascadeLevelState.CASCADED))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("I6");
        }

        @Test
        void i10_chainSeqMustBeGapless() {
            issue();
            CascadeLevelAction gap = action(CascadeActionType.CASCADE, 1, 1, a -> {
                a.setRefinedCritique("Refined");
                a.setNextOwnerLabel(L2_OWNER);
            });
            gap.setChainSeq(5);
            assertThatThrownBy(() -> apply(gap))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("I10");
        }

        @Test
        void pendingDeclineRun_isEmptyUnlessFrontierDeclined() {
            issue();
            assertThat(sm.pendingDeclineRun(cascade, levels)).isEmpty();
            cascadeAt(1, L2_OWNER);
            declineAt(2);
            assertThat(sm.pendingDeclineRun(cascade, levels)).containsExactly(2);
        }

        @Test
        void ownershipGuards_memberIdWinsWhenBothPresent_labelIsFallback() {
            issue();
            CascadeLevel l1 = levels.get(1);
            UUID ownerId = UUID.randomUUID();
            l1.setOwnerMemberId(ownerId);

            // id match wins regardless of label
            sm.requireLevelOwner(cascade, l1, new CascadeActor(ownerId, "Anything"));
            // id mismatch is forbidden even with a matching label
            assertThatThrownBy(() -> sm.requireLevelOwner(cascade, l1,
                    new CascadeActor(UUID.randomUUID(), L1_OWNER)))
                    .isInstanceOf(CascadeForbiddenException.class);
            // label fallback when the actor carries no id
            sm.requireLevelOwner(cascade, l1, CascadeActor.of(L1_OWNER));
            assertThatThrownBy(() -> sm.requireLevelOwner(cascade, l1, CascadeActor.of("Impostor")))
                    .isInstanceOf(CascadeForbiddenException.class)
                    .satisfies(t -> assertThat(((CascadeForbiddenException) t).getCode())
                            .isEqualTo(CascadeForbiddenException.Code.CASCADE_NOT_LEVEL_OWNER));

            assertThatThrownBy(() -> sm.requireOriginator(cascade, CascadeActor.of("Impostor")))
                    .isInstanceOf(CascadeForbiddenException.class)
                    .satisfies(t -> assertThat(((CascadeForbiddenException) t).getCode())
                            .isEqualTo(CascadeForbiddenException.Code.CASCADE_NOT_ORIGINATOR));
            sm.requireOriginator(cascade, CascadeActor.of(ORIGINATOR));
        }
    }
}
