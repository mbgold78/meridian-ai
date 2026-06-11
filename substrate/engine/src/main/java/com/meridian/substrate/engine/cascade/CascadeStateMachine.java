package com.meridian.substrate.engine.cascade;

import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeLevel;
import com.meridian.substrate.common.domain.CascadeLevelAction;
import com.meridian.substrate.common.domain.enums.CascadeActionType;
import com.meridian.substrate.common.domain.enums.CascadeAttentionSignal;
import com.meridian.substrate.common.domain.enums.CascadeLevelState;
import com.meridian.substrate.common.domain.enums.CascadeState;
import com.meridian.substrate.engine.cascade.exception.CascadeConflictException;
import com.meridian.substrate.engine.cascade.exception.CascadeForbiddenException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.UUID;

/**
 * The PURE cascade transition functions (engine-spec cascade.md §5, §10) —
 * deliberately package-private and shared verbatim by the live command path
 * ({@link CascadeLifecycleService}) and the replay path
 * ({@link CascadeProjectionRebuilder}), so projections derived live and
 * projections rebuilt from the append-only action log can never diverge by
 * construction (ADR-001 D1).
 *
 * <p>No repository, clock, or Spring dependency: inputs are the aggregate row,
 * the depth-ordered level projections, and the action being applied; all
 * timestamps come from the action's {@code actedAt} (recorded from the
 * injected Clock by the service, invariant I5). The effective SLA in days is
 * resolved by the caller ({@code 0} = disabled, ADR D5).
 *
 * <p>Guard violations throw
 * {@link CascadeConflictException.Code#CASCADE_ILLEGAL_TRANSITION} or
 * {@link CascadeConflictException.Code#CASCADE_TERMINAL} (409). A legitimate
 * DECLINE is a LEGAL transition here like any other — decline is never an
 * error (ADR-001 non-negotiable #1). Internal integrity violations (gapless
 * chain_seq I10, frontier sanity I6, single-LOOKING I1, contiguity I2) throw
 * {@link IllegalStateException}: assert, never silently repair.
 */
final class CascadeStateMachine {

    /**
     * Validates the action against §5.2/§5.3 guards and invariant I4 payload
     * conditionals, then applies it: mutates level projections (creating /
     * reactivating rows as the transition demands) and the aggregate's derived
     * fields ({@code currentState}, {@code chainSeq}, frontier, signal,
     * resolution block) — exactly the fields marked ⟲ in spec §3.1.
     *
     * <p>Overdue flags are cleared on any transition of the affected
     * level/cascade (spec §9) — the sweeper, never this machine, sets them.
     */
    void apply(Cascade cascade, NavigableMap<Integer, CascadeLevel> levels,
               CascadeLevelAction action, int effectiveSlaDays) {
        validatePayloadConditionals(cascade, action);

        // I10: chain_seq is gapless; the aggregate's chain_seq is the watermark.
        if (action.getChainSeq() != cascade.getChainSeq() + 1) {
            throw new IllegalStateException("chain_seq must be gapless (invariant I10): aggregate at "
                    + cascade.getChainSeq() + " but action carries " + action.getChainSeq());
        }

        // Terminal states are absorbing (§5.2): any action after RESOLVE_* conflicts.
        // ISSUE is exempt — it is the action that brings the aggregate into being.
        if (action.getAction() != CascadeActionType.ISSUE
                && cascade.getCurrentState() != CascadeState.IN_FLIGHT) {
            throw conflict(CascadeConflictException.Code.CASCADE_TERMINAL, cascade,
                    "cascade is " + cascade.getCurrentState().wireValue()
                            + " — terminal states are absorbing; reissue with predecessorCascadeId is the only loop");
        }

        switch (action.getAction()) {
            case ISSUE -> applyIssue(cascade, levels, action, effectiveSlaDays);
            case CASCADE -> applyCascade(cascade, levels, action, effectiveSlaDays);
            case ABSORB -> applyAbsorb(cascade, levels, action);
            case DECLINE -> applyDecline(cascade, levels, action);
            case RECASCADE -> applyRecascade(cascade, levels, action, effectiveSlaDays);
            case RESOLVE_LOOKS_GOOD, RESOLVE_DECLINED -> applyResolve(cascade, levels, action);
        }

        cascade.setChainSeq(action.getChainSeq());
        // Spec §9: overdue flags are cleared by the lifecycle path on any
        // transition of the affected cascade; the sweeper re-evaluates later.
        cascade.setOverdue(false);
        cascade.setAttentionSignal(deriveSignal(cascade.getCurrentState(), cascade.getFrontierState()));
        assertStructuralInvariants(cascade, levels);
    }

    // ------------------------------------------------------------------ verbs

    /** §5.2 A1 mechanics: L0 (CASCADED) + L1 (LOOKING) + frontier 1, chain_seq 1. */
    private void applyIssue(Cascade cascade, NavigableMap<Integer, CascadeLevel> levels,
                            CascadeLevelAction action, int effectiveSlaDays) {
        if (!levels.isEmpty() || cascade.getChainSeq() != 0) {
            throw conflict(CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION, cascade,
                    "ISSUE is only legal as the first action of a cascade");
        }
        requireDepth(cascade, action, 0);

        CascadeLevel l0 = new CascadeLevel();
        l0.setCascadeId(action.getCascadeId());
        l0.setDepth(0);
        l0.setOwnerMemberId(action.getActorMemberId());
        l0.setOwnerLabel(action.getActorLabel());
        // L0 is created CASCADED at issuance and has no level verbs (§5.3).
        l0.setCurrentState(CascadeLevelState.CASCADED);
        l0.setRound(1);
        l0.setRefinedCritique(action.getRefinedCritique());
        l0.setActivatedAt(action.getActedAt());
        l0.setActedAt(action.getActedAt());
        l0.setResponseDeadline(null); // no demand sits on the originator's own level
        levels.put(0, l0);

        CascadeLevel l1 = new CascadeLevel();
        l1.setCascadeId(action.getCascadeId());
        l1.setDepth(1);
        activateLevel(l1, 1, action, effectiveSlaDays);
        levels.put(1, l1);

        cascade.setCurrentState(CascadeState.IN_FLIGHT);
        moveFrontier(cascade, 1, CascadeLevelState.LOOKING);
    }

    /** §5.3 L1: LOOKING → CASCADED at the frontier; activates depth k+1 (created or reactivated). */
    private void applyCascade(Cascade cascade, NavigableMap<Integer, CascadeLevel> levels,
                              CascadeLevelAction action, int effectiveSlaDays) {
        CascadeLevel level = requireActedLevel(cascade, levels, action);
        int k = action.getDepth();
        if (!(level.getCurrentState() == CascadeLevelState.LOOKING && k == cascade.getFrontierDepth())) {
            throw conflict(CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION, cascade,
                    "CASCADE is only legal for the LOOKING frontier level (L1): depth " + k
                            + " is " + level.getCurrentState().wireValue()
                            + ", frontier is " + describeFrontier(cascade));
        }
        actOnce(level, CascadeLevelState.CASCADED, action);
        level.setRefinedCritique(action.getRefinedCritique());

        CascadeLevel next = levels.get(k + 1);
        if (next == null) {
            next = new CascadeLevel();
            next.setCascadeId(action.getCascadeId());
            next.setDepth(k + 1);
            activateLevel(next, 1, action, effectiveSlaDays);
            levels.put(k + 1, next);
        } else {
            // A stale row from an earlier round (e.g. it DECLINED, the level
            // above endorsed, was itself recascaded, and now cascades again):
            // reactivate as a new round (§5.3 L1 / §5.1 round semantics).
            activateLevel(next, next.getRound() + 1, action, effectiveSlaDays);
        }
        moveFrontier(cascade, k + 1, CascadeLevelState.LOOKING);
    }

    /** §5.3 L2 (first-touch) and L6 (absorb-over-decline): → ABSORBED, frontier = k. */
    private void applyAbsorb(Cascade cascade, NavigableMap<Integer, CascadeLevel> levels,
                             CascadeLevelAction action) {
        CascadeLevel level = requireActedLevel(cascade, levels, action);
        int k = action.getDepth();
        boolean firstTouch = level.getCurrentState() == CascadeLevelState.LOOKING
                && k == cascade.getFrontierDepth();
        boolean overDecline = level.getCurrentState() == CascadeLevelState.CASCADED
                && k + 1 == cascade.getFrontierDepth()
                && cascade.getFrontierState() == CascadeLevelState.DECLINED;
        if (!firstTouch && !overDecline) {
            throw conflict(CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION, cascade,
                    "ABSORB is only legal for the LOOKING frontier (L2) or for the CASCADED level"
                            + " directly above a DECLINED frontier (L6): depth " + k
                            + " is " + level.getCurrentState().wireValue()
                            + ", frontier is " + describeFrontier(cascade));
        }
        actOnce(level, CascadeLevelState.ABSORBED, action);
        level.setRefinedCritique(action.getRefinedCritique());
        // L6: depth k+1 stays DECLINED in history — its decline is answered, no
        // longer pending (§5.3); downward propagation terminates either way and
        // the cascade remains IN_FLIGHT awaiting originator resolution.
        // EXTENSION POINT(cascade-2): ABSORBED completion semantics — progress
        // tracking, completion evidence, and originator sign-off requirements on
        // absorbed work are deliberately NOT designed (spec §13.2; ADR D12).
        moveFrontier(cascade, k, CascadeLevelState.ABSORBED);
    }

    /** §5.3 L3 (first-touch decline) and L4 (endorse-decline): → DECLINED, frontier = k. */
    private void applyDecline(Cascade cascade, NavigableMap<Integer, CascadeLevel> levels,
                              CascadeLevelAction action) {
        CascadeLevel level = requireActedLevel(cascade, levels, action);
        int k = action.getDepth();
        boolean firstTouch = level.getCurrentState() == CascadeLevelState.LOOKING
                && k == cascade.getFrontierDepth();
        boolean endorse = level.getCurrentState() == CascadeLevelState.CASCADED
                && k >= 1
                && k + 1 == cascade.getFrontierDepth()
                && cascade.getFrontierState() == CascadeLevelState.DECLINED;
        if (!firstTouch && !endorse) {
            throw conflict(CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION, cascade,
                    "DECLINE is only legal for the LOOKING frontier (L3) or as endorse-decline by the"
                            + " CASCADED level directly above a DECLINED frontier (L4): depth " + k
                            + " is " + level.getCurrentState().wireValue()
                            + ", frontier is " + describeFrontier(cascade));
        }
        // A legitimate decline — a documented accountability act, never an
        // error/failure ([CASCADE-LIFECYCLE]; ADR-001 non-negotiable #1).
        actOnce(level, CascadeLevelState.DECLINED, action);
        level.setDeclineReasoning(action.getDeclineReasoning());
        moveFrontier(cascade, k, CascadeLevelState.DECLINED);
    }

    /** §5.3 L5: CASCADED level k re-refines and reactivates declined k+1 as a new round. */
    private void applyRecascade(Cascade cascade, NavigableMap<Integer, CascadeLevel> levels,
                                CascadeLevelAction action, int effectiveSlaDays) {
        CascadeLevel level = requireActedLevel(cascade, levels, action);
        int k = action.getDepth();
        boolean legal = level.getCurrentState() == CascadeLevelState.CASCADED
                && k >= 1
                && k + 1 == cascade.getFrontierDepth()
                && cascade.getFrontierState() == CascadeLevelState.DECLINED;
        if (!legal) {
            throw conflict(CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION, cascade,
                    "RECASCADE is only legal for the CASCADED level directly above a DECLINED frontier"
                            + " (L5): depth " + k + " is " + level.getCurrentState().wireValue()
                            + ", frontier is " + describeFrontier(cascade));
        }
        if (action.getRound() != level.getRound()) {
            throw new IllegalStateException("action round " + action.getRound()
                    + " does not match level round " + level.getRound() + " at depth " + k);
        }
        // The acting level stays CASCADED but carries the revised refinement as
        // its current-round projection (§5.3 L5: new refinedCritique).
        level.setRefinedCritique(action.getRefinedCritique());
        level.setActedAt(action.getActedAt());
        level.setOverdue(false);

        CascadeLevel reactivated = levels.get(k + 1);
        if (reactivated == null) {
            throw new IllegalStateException(
                    "frontier level row missing at depth " + (k + 1) + " (invariant I2)");
        }
        // §5.1 round semantics: round+1, new owner (may differ from the prior
        // owner), projections reset, deadline recomputed, overdue cleared.
        // Prior-round facts remain only in the action log.
        activateLevel(reactivated, reactivated.getRound() + 1, action, effectiveSlaDays);
        moveFrontier(cascade, k + 1, CascadeLevelState.LOOKING);
    }

    /** §5.2 A2/A3: originator terminal resolution; both terminal states are peers. */
    private void applyResolve(Cascade cascade, NavigableMap<Integer, CascadeLevel> levels,
                              CascadeLevelAction action) {
        requireDepth(cascade, action, 0);
        if (action.getAction() == CascadeActionType.RESOLVE_DECLINED
                && cascade.getFrontierState() != CascadeLevelState.DECLINED) {
            throw conflict(CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION, cascade,
                    "RESOLVE_DECLINED requires a pending decline (frontier DECLINED);"
                            + " frontier is " + describeFrontier(cascade));
        }
        cascade.setCurrentState(action.getAction() == CascadeActionType.RESOLVE_LOOKS_GOOD
                ? CascadeState.RESOLVED_LOOKS_GOOD
                : CascadeState.RESOLVED_DECLINED);
        cascade.setResolvedAt(action.getActedAt());
        cascade.setResolvedByMemberId(action.getActorMemberId());
        cascade.setResolvedByLabel(action.getActorLabel());
        cascade.setResolutionNote(action.getResolutionNote());
        cascade.setAcceptedDeclineActionId(action.getAction() == CascadeActionType.RESOLVE_DECLINED
                ? action.getAcceptedDeclineActionId()
                : null);
        // Level rows are deliberately untouched: A2 permits an originator
        // short-circuit at any time in-flight (§5.2), including over a LOOKING
        // frontier (c-tellix continuation vector, spec §12 test #3). The LOOKING
        // row then remains as the honest historical record that the level never
        // acted before resolution — see the I1 note on assertStructuralInvariants.
    }

    // ------------------------------------------------ guards used by the live path only

    /**
     * Actor must be the current owner of the acted level (§5.3). Member-id
     * equality decides when both sides carry one; otherwise the denormalized
     * label is the identity fallback (ADR D11 — Phase 2 header trust, S9).
     */
    void requireLevelOwner(Cascade cascade, CascadeLevel level, CascadeActor actor) {
        if (!sameParty(level.getOwnerMemberId(), level.getOwnerLabel(), actor)) {
            throw new CascadeForbiddenException(CascadeForbiddenException.Code.CASCADE_NOT_LEVEL_OWNER,
                    cascade.getId(), "actor '" + actor.label() + "' is not the current owner of depth "
                    + level.getDepth() + " ('" + level.getOwnerLabel() + "')");
        }
    }

    /** Actor must be the originator (§5.2 A2/A3). */
    void requireOriginator(Cascade cascade, CascadeActor actor) {
        if (!sameParty(cascade.getOriginOwnerMemberId(), cascade.getOriginOwnerLabel(), actor)) {
            throw new CascadeForbiddenException(CascadeForbiddenException.Code.CASCADE_NOT_ORIGINATOR,
                    cascade.getId(), "actor '" + actor.label() + "' is not the originator ('"
                    + cascade.getOriginOwnerLabel() + "')");
        }
    }

    /**
     * §5.2 A3 guard: {@code acceptedDeclineActionId} must reference the LATEST
     * DECLINE action of a level inside the pending-decline run — the maximal
     * contiguous run of DECLINED levels starting at the frontier and extending
     * deeper (§5.1). "Latest" is pinned by round equality: a DECLINED level
     * declines at most once per round (act-once discipline).
     */
    void validateAcceptedDecline(Cascade cascade, NavigableMap<Integer, CascadeLevel> levels,
                                 CascadeLevelAction acceptedDecline) {
        if (acceptedDecline == null
                || acceptedDecline.getAction() != CascadeActionType.DECLINE
                || !Objects.equals(acceptedDecline.getCascadeId(), cascade.getId())) {
            throw conflict(CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION, cascade,
                    "acceptedDeclineActionId must reference a DECLINE action of this cascade");
        }
        List<Integer> run = pendingDeclineRun(cascade, levels);
        if (!run.contains(acceptedDecline.getDepth())) {
            throw conflict(CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION, cascade,
                    "accepted decline at depth " + acceptedDecline.getDepth()
                            + " is not in the pending-decline run " + run);
        }
        CascadeLevel level = levels.get(acceptedDecline.getDepth());
        if (level == null || acceptedDecline.getRound() != level.getRound()) {
            throw conflict(CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION, cascade,
                    "accepted decline is not the latest DECLINE of depth "
                            + acceptedDecline.getDepth() + " (stale round)");
        }
    }

    /**
     * The pending-decline SET (§5.1): contiguous DECLINED depths from the
     * frontier downward (deeper). Empty unless the frontier is DECLINED.
     */
    List<Integer> pendingDeclineRun(Cascade cascade, NavigableMap<Integer, CascadeLevel> levels) {
        List<Integer> run = new ArrayList<>();
        if (cascade.getFrontierState() != CascadeLevelState.DECLINED) {
            return run;
        }
        for (int depth = cascade.getFrontierDepth(); ; depth++) {
            CascadeLevel level = levels.get(depth);
            if (level == null || level.getCurrentState() != CascadeLevelState.DECLINED) {
                break;
            }
            run.add(depth);
        }
        return run;
    }

    /**
     * Attention-signal derivation table (spec §2) — a pure projection,
     * recomputed on every transition and on rebuild.
     */
    CascadeAttentionSignal deriveSignal(CascadeState state, CascadeLevelState frontierState) {
        return switch (state) {
            case RESOLVED_LOOKS_GOOD -> CascadeAttentionSignal.RESOLVED_LOOKS_GOOD;
            case RESOLVED_DECLINED -> CascadeAttentionSignal.RESOLVED_DECLINED;
            case IN_FLIGHT -> switch (frontierState) {
                // Destructive surface = leader-attention, queue #21 resolved —
                // never failure semantics (decline is a legitimate act).
                case DECLINED -> CascadeAttentionSignal.DECLINE_PENDING;
                case LOOKING -> CascadeAttentionSignal.ESCALATING;
                case ABSORBED -> CascadeAttentionSignal.ABSORBED;
                // I6: the frontier can never be CASCADED while in-flight
                // (CASCADE always advances the frontier). Assert; never repair.
                case CASCADED -> throw new IllegalStateException(
                        "invariant I6 violated: in-flight frontier is CASCADED");
            };
        };
    }

    // ----------------------------------------------------------------- helpers

    /** I4 payload conditionals — DB CHECK constraints back-stop these (§3.3). */
    private void validatePayloadConditionals(Cascade cascade, CascadeLevelAction action) {
        CascadeActionType type = action.getAction();
        boolean wantsRefined = type == CascadeActionType.ISSUE || type == CascadeActionType.CASCADE
                || type == CascadeActionType.ABSORB || type == CascadeActionType.RECASCADE;
        boolean wantsDecline = type == CascadeActionType.DECLINE;
        boolean wantsResolution = type == CascadeActionType.RESOLVE_LOOKS_GOOD
                || type == CascadeActionType.RESOLVE_DECLINED;
        boolean wantsNextOwner = type == CascadeActionType.ISSUE || type == CascadeActionType.CASCADE
                || type == CascadeActionType.RECASCADE;

        requireIff(cascade, wantsRefined, action.getRefinedCritique(), type, "refinedCritique");
        requireIff(cascade, wantsDecline, action.getDeclineReasoning(), type, "declineReasoning");
        requireIff(cascade, wantsResolution, action.getResolutionNote(), type, "resolutionNote");
        requireIff(cascade, wantsNextOwner, action.getNextOwnerLabel(), type, "nextOwnerLabel");

        // accepted_decline_action_id is RESOLVE_DECLINED only (§3.3).
        boolean wantsAccepted = type == CascadeActionType.RESOLVE_DECLINED;
        if (wantsAccepted && action.getAcceptedDeclineActionId() == null) {
            throw conflict(CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION, cascade,
                    "resolve-declined requires acceptedDeclineActionId referencing a DECLINE"
                            + " in the pending run (§5.2 A3)");
        }
        if (!wantsAccepted && action.getAcceptedDeclineActionId() != null) {
            throw conflict(CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION, cascade,
                    type.wireValue() + " must not carry acceptedDeclineActionId (§3.3)");
        }

        if (action.getActorLabel() == null || action.getActorLabel().isBlank()
                || action.getActedAt() == null
                || action.getIdempotencyKey() == null || action.getIdempotencyKey().isBlank()) {
            throw new IllegalStateException(
                    "every action carries actor label, actedAt and idempotencyKey (invariants I5, D8)");
        }
    }

    private void requireIff(Cascade cascade, boolean required, String value,
                            CascadeActionType type, String field) {
        boolean present = value != null && !value.isBlank();
        if (required && !present) {
            throw conflict(CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION, cascade,
                    type.wireValue() + " requires a non-blank " + field + " (invariant I4)");
        }
        if (!required && value != null) {
            throw conflict(CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION, cascade,
                    type.wireValue() + " must not carry " + field + " (invariant I4)");
        }
    }

    /** Level verbs act on an existing depth ≥ 1; L0 has no level verbs (§5.3). */
    private CascadeLevel requireActedLevel(Cascade cascade, NavigableMap<Integer, CascadeLevel> levels,
                                           CascadeLevelAction action) {
        int k = action.getDepth();
        if (k < 1) {
            throw conflict(CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION, cascade,
                    "L0 has no level verbs — originator verbs are resolve only (§5.3)");
        }
        CascadeLevel level = levels.get(k);
        if (level == null) {
            throw conflict(CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION, cascade,
                    "no level exists at depth " + k);
        }
        return level;
    }

    /** First-touch act-once mechanics shared by CASCADE/ABSORB/DECLINE. */
    private void actOnce(CascadeLevel level, CascadeLevelState to, CascadeLevelAction action) {
        if (action.getRound() != level.getRound()) {
            throw new IllegalStateException("action round " + action.getRound()
                    + " does not match level round " + level.getRound()
                    + " at depth " + level.getDepth());
        }
        level.setCurrentState(to);
        level.setActedAt(action.getActedAt());
        level.setOverdue(false);
    }

    /**
     * (Re)activation of a level into LOOKING for a given round: owner routed
     * from the action's nextOwner, projections reset, deadline recomputed from
     * the effective SLA (0 = disabled → NULL deadline, ADR D5).
     *
     * <p>EXTENSION POINT(cascade-1): LOOKING dwell/reassignment/expiry — only
     * the initial state on activation plus the sweeper's overdue notification
     * are encoded; reassignment verbs, expiry and delegation are deliberately
     * NOT designed (spec §13.1; port-map §6 ⚠ never fixture-exercised).
     */
    private void activateLevel(CascadeLevel level, int round, CascadeLevelAction action,
                               int effectiveSlaDays) {
        level.setOwnerMemberId(action.getNextOwnerMemberId());
        level.setOwnerLabel(action.getNextOwnerLabel());
        level.setCurrentState(CascadeLevelState.LOOKING);
        level.setRound(round);
        level.setRefinedCritique(null);
        level.setDeclineReasoning(null);
        level.setActivatedAt(action.getActedAt());
        level.setActedAt(null);
        level.setResponseDeadline(effectiveSlaDays > 0
                ? action.getActedAt().plus(Duration.ofDays(effectiveSlaDays))
                : null);
        level.setOverdue(false);
    }

    private String describeFrontier(Cascade cascade) {
        return "L" + cascade.getFrontierDepth() + " " + cascade.getFrontierState().wireValue();
    }

    private void moveFrontier(Cascade cascade, int depth, CascadeLevelState state) {
        cascade.setFrontierDepth(depth);
        cascade.setFrontierState(state);
    }

    private void requireDepth(Cascade cascade, CascadeLevelAction action, int expected) {
        if (action.getDepth() != expected) {
            throw conflict(CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION, cascade,
                    action.getAction().wireValue() + " acts at depth " + expected
                            + ", not depth " + action.getDepth());
        }
    }

    private boolean sameParty(UUID expectedMemberId, String expectedLabel, CascadeActor actor) {
        if (expectedMemberId != null && actor.memberId() != null) {
            return expectedMemberId.equals(actor.memberId());
        }
        return expectedLabel != null && expectedLabel.equals(actor.label());
    }

    /**
     * Structural invariants asserted after every applied action: I1 (at most
     * one LOOKING; zero LOOKING when the frontier is DECLINED/ABSORBED) and I2
     * (depths contiguous 0..N). Violations are integrity failures, not client
     * conflicts — assert, never silently repair.
     *
     * <p>Note on I1's terminal clause: an originator short-circuit
     * (RESOLVE_* while the frontier is LOOKING, legal per A2 "any time
     * in-flight") leaves the LOOKING row in place as historical truth — no
     * action by that level exists in the log, and the binding level-state set
     * has no synthetic "stood-down" value to fabricate. The demand itself is
     * extinguished by the terminal aggregate state (the read model derives
     * attention from {@code currentState} first). The terminal clause is
     * therefore not asserted against stored rows.
     */
    private void assertStructuralInvariants(Cascade cascade, NavigableMap<Integer, CascadeLevel> levels) {
        long looking = levels.values().stream()
                .filter(l -> l.getCurrentState() == CascadeLevelState.LOOKING)
                .count();
        if (looking > 1) {
            throw new IllegalStateException("invariant I1 violated: " + looking + " levels LOOKING");
        }
        if (cascade.getCurrentState() == CascadeState.IN_FLIGHT
                && (cascade.getFrontierState() == CascadeLevelState.DECLINED
                || cascade.getFrontierState() == CascadeLevelState.ABSORBED)
                && looking != 0) {
            throw new IllegalStateException(
                    "invariant I1 violated: LOOKING level exists while frontier is "
                            + cascade.getFrontierState().wireValue());
        }
        int expectedDepth = 0;
        for (Integer depth : levels.keySet()) {
            if (depth != expectedDepth++) {
                throw new IllegalStateException(
                        "invariant I2 violated: depths not contiguous at " + depth);
            }
        }
    }

    private CascadeConflictException conflict(CascadeConflictException.Code code,
                                              Cascade cascade, String message) {
        return new CascadeConflictException(code, cascade.getId(), message);
    }
}
