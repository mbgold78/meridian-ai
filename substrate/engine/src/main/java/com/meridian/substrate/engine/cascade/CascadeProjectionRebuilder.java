package com.meridian.substrate.engine.cascade;

import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeLevel;
import com.meridian.substrate.common.domain.CascadeLevelAction;
import com.meridian.substrate.common.domain.enums.CascadeLevelState;
import com.meridian.substrate.common.domain.enums.CascadeState;
import com.meridian.substrate.common.repo.CascadeLevelActionRepository;
import com.meridian.substrate.common.repo.CascadeLevelRepository;
import com.meridian.substrate.common.repo.CascadeRepository;
import com.meridian.substrate.engine.contract.gateway.Ungoverned;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Deterministic replay of the append-only action log → projections
 * (engine-spec cascade.md §10; ADR-001 D1). Under the per-cascade lock the
 * log is replayed in {@code chain_seq} order through the SAME pure transition
 * functions the live path uses ({@link CascadeStateMachine}), re-deriving all
 * {@code cascade_levels} rows and every ⟲ field on {@code cascades}
 * ({@code current_state, chain_seq, frontier_*, attention_signal, resolved_*,
 * resolution_note, accepted_decline_action_id}).
 *
 * <p>Immutable issuance artifacts are VERIFIED against the ISSUE action
 * (assert-equal), never rewritten — invariant I3 is double-anchored on the
 * cascade row and the action row. {@code response_deadline} and {@code overdue}
 * are recomputed from SLA config + the injected clock. Notifications are NOT
 * replayed: the outbox is history, not a projection.
 *
 * <p>Purpose: restore-after-restart safety, CI drift detection (live
 * projections vs a fresh rebuild), and the mechanism by which later-ratified
 * {@code looking}/{@code absorbed} semantics apply retroactively as projector
 * changes, never data migrations (ADR D1/D12).
 *
 * <p>The spec leaves the user-runnable exposure (actuator-style admin
 * operation or Boot CLI runner) to the implementer; that wiring belongs to the
 * app module and lands with the command-API chunk — this bean is the engine
 * mechanism both will invoke.
 */
@Service
public class CascadeProjectionRebuilder {

    private static final Logger log = LoggerFactory.getLogger(CascadeProjectionRebuilder.class);

    private final CascadeRepository cascadeRepository;
    private final CascadeLevelRepository levelRepository;
    private final CascadeLevelActionRepository actionRepository;
    private final CascadeProperties properties;
    private final Clock clock;
    private final CascadeStateMachine stateMachine = new CascadeStateMachine();

    public CascadeProjectionRebuilder(CascadeRepository cascadeRepository,
                                      CascadeLevelRepository levelRepository,
                                      CascadeLevelActionRepository actionRepository,
                                      CascadeProperties properties,
                                      Clock clock) {
        this.cascadeRepository = cascadeRepository;
        this.levelRepository = levelRepository;
        this.actionRepository = actionRepository;
        this.properties = properties;
        this.clock = clock;
    }

    /** Rebuilds every cascade of a domain; returns the rebuilt ids. */
    @Ungoverned(reason = "operator recovery: deterministic projection replay from the append-only"
            + " action log (engine-spec cascade.md §10, ADR-001 D1) — creates no new business"
            + " state, so it is not one of the three ADR D2 PEPs")
    @Transactional
    public List<UUID> rebuildAll(UUID domainId) {
        List<UUID> rebuilt = new ArrayList<>();
        for (Cascade cascade : cascadeRepository.findByDomainId(domainId)) {
            rebuild(cascade.getId());
            rebuilt.add(cascade.getId());
        }
        log.info("Rebuilt cascade projections for domain [domainId={}, cascades={}]",
                domainId, rebuilt.size());
        return rebuilt;
    }

    /** Rebuilds one cascade's projections from its action log, under the per-cascade lock. */
    @Ungoverned(reason = "operator recovery: deterministic projection replay from the append-only"
            + " action log (engine-spec cascade.md §10, ADR-001 D1) — creates no new business"
            + " state, so it is not one of the three ADR D2 PEPs")
    @Transactional
    public Cascade rebuild(UUID cascadeId) {
        Cascade cascade = cascadeRepository.findByIdForUpdate(cascadeId)
                .orElseThrow(() -> new EntityNotFoundException("Cascade not found: " + cascadeId));

        List<CascadeLevelAction> actions = actionRepository.findByCascadeIdOrderByChainSeqAsc(cascadeId);
        if (actions.isEmpty()) {
            throw new IllegalStateException(
                    "cascade " + cascadeId + " has no actions — projections are not rebuildable"
                            + " without an ISSUE action (invariant I10)");
        }
        verifyImmutableIssuanceArtifacts(cascade, actions.get(0));

        // Reset the ⟲ derived fields to pre-ISSUE values; the replay re-derives
        // them all. Immutable issuance fields are never touched.
        cascade.setCurrentState(CascadeState.IN_FLIGHT);
        cascade.setChainSeq(0);
        cascade.setResolvedAt(null);
        cascade.setResolvedByMemberId(null);
        cascade.setResolvedByLabel(null);
        cascade.setResolutionNote(null);
        cascade.setAcceptedDeclineActionId(null);
        cascade.setOverdue(false);

        int effectiveSlaDays = cascade.getResponseSlaDays() != null
                ? cascade.getResponseSlaDays()
                : properties.getDefaultResponseSlaDays();

        // Fresh in-memory derivation through the shared pure transition functions.
        NavigableMap<Integer, CascadeLevel> derived = new TreeMap<>();
        for (CascadeLevelAction action : actions) {
            stateMachine.apply(cascade, derived, action, effectiveSlaDays);
        }
        recomputeOverdueOverlay(cascade, derived);

        // Reconcile onto the persisted rows BY DEPTH, preserving row identity
        // where rows survive (historical action.level_id soft refs stay valid).
        // Wiped rows are recreated with new ids — acceptable for app-enforced
        // soft references; surplus rows not derivable from the log are drift
        // and are removed.
        Map<Integer, CascadeLevel> existingByDepth = new HashMap<>();
        List<CascadeLevel> surplus = new ArrayList<>();
        for (CascadeLevel row : levelRepository.findByCascadeIdOrderByDepthAsc(cascadeId)) {
            if (derived.containsKey(row.getDepth())) {
                existingByDepth.put(row.getDepth(), row);
            } else {
                surplus.add(row);
            }
        }
        for (CascadeLevel fresh : derived.values()) {
            CascadeLevel target = existingByDepth.get(fresh.getDepth());
            if (target == null) {
                levelRepository.save(fresh);
            } else {
                copyDerivedFields(fresh, target);
                levelRepository.save(target);
            }
        }
        if (!surplus.isEmpty()) {
            log.warn("Removing {} cascade_levels row(s) not derivable from the action log"
                    + " [cascadeId={}]", surplus.size(), cascadeId);
            levelRepository.deleteAll(surplus);
        }

        Cascade saved = cascadeRepository.save(cascade);
        log.info("Rebuilt cascade projections [cascadeId={}, actions={}, state={}, signal={}]",
                cascadeId, actions.size(), saved.getCurrentState(), saved.getAttentionSignal());
        return saved;
    }

    /**
     * §10: immutable issuance fields are verified against the ISSUE action
     * payload, never rewritten. The action log anchors the issuance actor and
     * instant; a mismatch means the immutable half of the row drifted —
     * assert, never silently repair (I3).
     */
    private void verifyImmutableIssuanceArtifacts(Cascade cascade, CascadeLevelAction issue) {
        if (issue.getAction() != com.meridian.substrate.common.domain.enums.CascadeActionType.ISSUE
                || issue.getChainSeq() != 1) {
            throw new IllegalStateException("cascade " + cascade.getId()
                    + ": first logged action must be ISSUE at chain_seq 1");
        }
        if (!Objects.equals(cascade.getOriginatedAt(), issue.getActedAt())
                || !Objects.equals(cascade.getOriginOwnerLabel(), issue.getActorLabel())
                || !Objects.equals(cascade.getOriginOwnerMemberId(), issue.getActorMemberId())) {
            throw new IllegalStateException("cascade " + cascade.getId()
                    + ": immutable issuance artifacts do not match the ISSUE action"
                    + " (invariant I3) — refusing to rebuild over drifted identity fields");
        }
    }

    /**
     * §10: {@code overdue} flags are recomputed from SLA config + clock —
     * a demand is overdue iff it is still LOOKING past its recomputed deadline.
     * Setting notification-side state remains the sweeper's job (§9); this is
     * only the projection overlay.
     */
    private void recomputeOverdueOverlay(Cascade cascade, NavigableMap<Integer, CascadeLevel> levels) {
        Instant now = clock.instant();
        boolean anyOverdue = false;
        for (CascadeLevel level : levels.values()) {
            boolean overdue = cascade.getCurrentState() == CascadeState.IN_FLIGHT
                    && level.getCurrentState() == CascadeLevelState.LOOKING
                    && level.getResponseDeadline() != null
                    && now.isAfter(level.getResponseDeadline());
            level.setOverdue(overdue);
            anyOverdue |= overdue;
        }
        cascade.setOverdue(anyOverdue);
    }

    /** Copies every derived (rebuildable) field onto the surviving row. */
    private void copyDerivedFields(CascadeLevel from, CascadeLevel to) {
        to.setOwnerMemberId(from.getOwnerMemberId());
        to.setOwnerLabel(from.getOwnerLabel());
        to.setCurrentState(from.getCurrentState());
        to.setRound(from.getRound());
        to.setRefinedCritique(from.getRefinedCritique());
        to.setDeclineReasoning(from.getDeclineReasoning());
        to.setActivatedAt(from.getActivatedAt());
        to.setActedAt(from.getActedAt());
        to.setResponseDeadline(from.getResponseDeadline());
        to.setOverdue(from.isOverdue());
    }
}
