package com.meridian.substrate.engine.cascade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeDraft;
import com.meridian.substrate.common.domain.CascadeLevel;
import com.meridian.substrate.common.domain.CascadeLevelAction;
import com.meridian.substrate.common.domain.enums.CascadeActionType;
import com.meridian.substrate.common.domain.enums.CascadeDraftStatus;
import com.meridian.substrate.common.domain.enums.CascadeNotificationType;
import com.meridian.substrate.common.domain.enums.CascadeState;
import com.meridian.substrate.common.domain.enums.CascadeSuggestionState;
import com.meridian.substrate.common.domain.enums.ProducedBy;
import com.meridian.substrate.common.repo.CascadeDraftRepository;
import com.meridian.substrate.common.repo.CascadeLevelActionRepository;
import com.meridian.substrate.common.repo.CascadeLevelRepository;
import com.meridian.substrate.common.repo.CascadeRepository;
import com.meridian.substrate.engine.cascade.exception.CascadeConflictException;
import com.meridian.substrate.engine.contract.gateway.Governed;
import com.meridian.substrate.engine.contract.gateway.GovernedActions;
import com.meridian.substrate.engine.contract.gateway.Ungoverned;
import com.meridian.substrate.engine.observability.EngineMetrics;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * All cascade writes: state machine + locking + transactional outbox
 * (engine-spec cascade.md §5; ADR-001 D8/D10). One {@code @Transactional}
 * method per verb, each following the §5.5 per-command sequence:
 *
 * <ol>
 *   <li>{@code findByIdForUpdate} — PESSIMISTIC_WRITE serializes the cascade
 *       (PG {@code SELECT … FOR UPDATE}; honored by H2 PG-mode — the
 *       CapabilityService/BrandingConfigService locking precedent);</li>
 *   <li>idempotency check under the lock — the same {@code idempotencyKey}
 *       replayed on the SAME verb/depth returns the original result
 *       re-projected (no new action row); reused on a DIFFERENT verb/depth it
 *       conflicts (409 {@code CASCADE_IDEMPOTENCY_REUSE} — a silent replay
 *       there would mask a dropped command as success); the
 *       {@code uq_cla_idem} UNIQUE constraint is the backstop;</li>
 *   <li>{@code expectedChainSeq} fencing → 409 {@code CASCADE_STALE_STATE};</li>
 *   <li>guard validation (§5.2/§5.3) → 409 {@code CASCADE_ILLEGAL_TRANSITION}
 *       / {@code CASCADE_TERMINAL} or 403 via {@link CascadeStateMachine};</li>
 *   <li>append action (chain_seq+1), update projections, write outbox
 *       notifications, invoke {@link CascadeAttestationHook} on RESOLVE_*,
 *       commit — ONE transaction (invariant I9).</li>
 * </ol>
 *
 * <p>Issuance anchors on the draft row lock instead (no cascade row exists
 * yet); re-issuing an ISSUED draft returns the existing cascade (200,
 * idempotent). No retry loops live in the engine; racing losers either replay
 * idempotently or 409 with current state and the client refreshes.
 *
 * <p>DECLINE is a first-class legitimate transition: it flows through this
 * service exactly like CASCADE/ABSORB and surfaces as HTTP 200 — never an
 * error (ADR-001 non-negotiable #1).
 */
@Service
public class CascadeLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(CascadeLifecycleService.class);

    /** Excerpt length for notification payloads (§8). */
    private static final int EXCERPT_LENGTH = 200;

    private final CascadeRepository cascadeRepository;
    private final CascadeLevelRepository levelRepository;
    private final CascadeLevelActionRepository actionRepository;
    private final CascadeDraftRepository draftRepository;
    private final CascadeNotificationService notificationService;
    private final CascadeAttestationHook attestationHook;
    private final CascadeProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final EngineMetrics metrics;
    private final CascadeStateMachine stateMachine = new CascadeStateMachine();

    /** Spring constructor (Stage 3 observability — metrics optional so partial slices wire). */
    @Autowired
    public CascadeLifecycleService(CascadeRepository cascadeRepository,
                                   CascadeLevelRepository levelRepository,
                                   CascadeLevelActionRepository actionRepository,
                                   CascadeDraftRepository draftRepository,
                                   CascadeNotificationService notificationService,
                                   CascadeAttestationHook attestationHook,
                                   CascadeProperties properties,
                                   Clock clock,
                                   ObjectMapper objectMapper,
                                   ObjectProvider<EngineMetrics> metrics) {
        this(cascadeRepository, levelRepository, actionRepository, draftRepository,
                notificationService, attestationHook, properties, clock, objectMapper,
                metrics.getIfAvailable(EngineMetrics::noop));
    }

    public CascadeLifecycleService(CascadeRepository cascadeRepository,
                                   CascadeLevelRepository levelRepository,
                                   CascadeLevelActionRepository actionRepository,
                                   CascadeDraftRepository draftRepository,
                                   CascadeNotificationService notificationService,
                                   CascadeAttestationHook attestationHook,
                                   CascadeProperties properties,
                                   Clock clock,
                                   ObjectMapper objectMapper,
                                   EngineMetrics metrics) {
        this.cascadeRepository = cascadeRepository;
        this.levelRepository = levelRepository;
        this.actionRepository = actionRepository;
        this.draftRepository = draftRepository;
        this.notificationService = notificationService;
        this.attestationHook = attestationHook;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /** Pre-Stage-3 signature, kept for hand-constructed services (tests, demo seeders). */
    public CascadeLifecycleService(CascadeRepository cascadeRepository,
                                   CascadeLevelRepository levelRepository,
                                   CascadeLevelActionRepository actionRepository,
                                   CascadeDraftRepository draftRepository,
                                   CascadeNotificationService notificationService,
                                   CascadeAttestationHook attestationHook,
                                   CascadeProperties properties,
                                   Clock clock,
                                   ObjectMapper objectMapper) {
        this(cascadeRepository, levelRepository, actionRepository, draftRepository,
                notificationService, attestationHook, properties, clock, objectMapper,
                EngineMetrics.noop());
    }

    // ------------------------------------------------------------------ commands

    /** Resolution choice for {@link ResolveCommand} (§6.2: "looks-good" | "declined"). */
    public enum Resolution { LOOKS_GOOD, DECLINED }

    /** §6.3 {@code /issue} body (engine-internal form; controller records land in chunk 5). */
    public record IssueCommand(String idempotencyKey, CascadeActor actor,
                               String level1OwnerLabel, UUID level1OwnerMemberId,
                               String originatorRefinedCritique) {
    }

    /** §6.2 {@code /levels/{depth}/cascade} body. */
    public record CascadeCommand(String idempotencyKey, Integer expectedChainSeq, CascadeActor actor,
                                 String refinedCritique, UUID nextOwnerMemberId, String nextOwnerLabel) {
    }

    /** §6.2 {@code /levels/{depth}/absorb} body. */
    public record AbsorbCommand(String idempotencyKey, Integer expectedChainSeq, CascadeActor actor,
                                String refinedCritique) {
    }

    /** §6.2 {@code /levels/{depth}/decline} body — 200 ALWAYS for a legitimate decline. */
    public record DeclineCommand(String idempotencyKey, Integer expectedChainSeq, CascadeActor actor,
                                 String declineReasoning) {
    }

    /** §6.2 {@code /levels/{depth}/recascade} body. */
    public record RecascadeCommand(String idempotencyKey, Integer expectedChainSeq, CascadeActor actor,
                                   String refinedCritique, UUID nextOwnerMemberId, String nextOwnerLabel) {
    }

    /** §6.2 {@code /resolve} body; {@code acceptedDeclineActionId} required iff declined. */
    public record ResolveCommand(String idempotencyKey, Integer expectedChainSeq, CascadeActor actor,
                                 Resolution resolution, String resolutionNote,
                                 UUID acceptedDeclineActionId) {
    }

    // ------------------------------------------------------------------ verbs

    /**
     * §5.2 A1 — issue a cascade from an OPEN draft, in one transaction: the
     * cascade row (born IN_FLIGHT, ADR D6), L0 + L1 level rows, the ISSUE
     * action (chain_seq 1), the §8 notifications, and the draft's
     * OPEN→ISSUED flip with {@code issuedCascadeId}. Re-issuing an ISSUED
     * draft returns the existing cascade (idempotent, draft-row anchored).
     */
    @Ungoverned(reason = "single-evaluation: reached only through the governed"
            + " CascadeDraftService.issue PEP (which takes the draft lock first);"
            + " annotating both layers would double-evaluate and double-row the PDP")
    @Transactional
    public Cascade issue(UUID draftId, IssueCommand cmd) {
        requireIdempotencyKey(cmd.idempotencyKey());
        CascadeDraft draft = draftRepository.findByIdForUpdate(draftId)
                .orElseThrow(() -> new EntityNotFoundException("CascadeDraft not found: " + draftId));

        if (draft.getStatus() == CascadeDraftStatus.ISSUED) {
            return cascadeRepository.findById(draft.getIssuedCascadeId())
                    .orElseThrow(() -> new IllegalStateException(
                            "draft " + draftId + " is ISSUED but cascade "
                                    + draft.getIssuedCascadeId() + " is missing"));
        }
        if (draft.getStatus() != CascadeDraftStatus.OPEN) {
            throw new CascadeConflictException(
                    CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION, null,
                    "draft " + draftId + " is " + draft.getStatus().wireValue()
                            + " — only OPEN drafts can issue");
        }
        if (isBlank(draft.getOriginalCritique()) || isBlank(draft.getAcceptanceCriteria())) {
            throw new CascadeConflictException(
                    CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION, null,
                    "issue requires the draft to carry a non-blank critique and acceptance criteria (A1)");
        }
        if (isBlank(cmd.level1OwnerLabel())) {
            throw new IllegalArgumentException("issue requires level1OwnerLabel (A1: level-1 owner named)");
        }

        Instant now = clock.instant();
        Cascade cascade = new Cascade();
        cascade.setDomainId(draft.getDomainId());
        cascade.setCascadeGroupId(draft.getCascadeGroupId());
        cascade.setOriginOwnerMemberId(cmd.actor().memberId());
        cascade.setOriginOwnerLabel(cmd.actor().label());
        cascade.setOriginatedAt(now);
        cascade.setOriginalCritique(draft.getOriginalCritique());
        cascade.setAcceptanceCriteria(draft.getAcceptanceCriteria());
        cascade.setSummary(draft.getSummary());
        cascade.setAffectedDomainId(draft.getAffectedDomainId());
        cascade.setOriginFindingId(draft.getOriginFindingId());
        cascade.setAuthoringMode(draft.getAuthoringMode());
        cascade.setProducedBy(deriveProducedBy(draft));
        cascade.setDraftId(draft.getId());
        // SLA resolution at issue time (§9, ADR D5): absent → engagement
        // default, stored resolved on the row; explicit 0 → disabled.
        cascade.setResponseSlaDays(draft.getResponseSlaDays() != null
                ? draft.getResponseSlaDays()
                : properties.getDefaultResponseSlaDays());
        cascade.setCurrentState(CascadeState.IN_FLIGHT);

        CascadeLevelAction action = newAction(cascade, CascadeActionType.ISSUE, 0, 1,
                cmd.actor(), cmd.idempotencyKey(), now);
        action.setRefinedCritique(!isBlank(cmd.originatorRefinedCritique())
                ? cmd.originatorRefinedCritique()
                : draft.getOriginalCritique());
        action.setNextOwnerMemberId(cmd.level1OwnerMemberId());
        action.setNextOwnerLabel(cmd.level1OwnerLabel());

        // Apply the state machine BEFORE the first persist: Hibernate captures
        // the insert snapshot at persist() time, so every NOT NULL projection
        // field (frontier, signal) must be populated before save.
        NavigableMap<Integer, CascadeLevel> levels = new TreeMap<>();
        stateMachine.apply(cascade, levels, action, effectiveSlaDays(cascade));

        cascade = cascadeRepository.save(cascade);
        action.setCascadeId(cascade.getId());
        for (CascadeLevel level : levels.values()) {
            level.setCascadeId(cascade.getId());
        }
        levelRepository.saveAll(levels.values());
        action.setLevelId(levels.get(0).getId());
        action = actionRepository.save(action);
        metrics.cascadeAction(action.getAction().wireValue());

        draft.setStatus(CascadeDraftStatus.ISSUED);
        draft.setIssuedCascadeId(cascade.getId());
        draftRepository.save(draft);

        fanOut(cascade, levels, action);
        log.info("Issued cascade [id={}, domainId={}, draftId={}, l1Owner={}]",
                cascade.getId(), cascade.getDomainId(), draftId, cmd.level1OwnerLabel());
        return cascade;
    }

    /** §5.3 L1 — the frontier owner refines and routes the critique one level deeper. */
    @Governed(actionType = GovernedActions.CASCADE_CASCADE)
    @Transactional
    public Cascade cascade(UUID cascadeId, int depth, CascadeCommand cmd) {
        return execute(cascadeId, cmd.idempotencyKey(), cmd.expectedChainSeq(),
                CascadeActionType.CASCADE, depth, ctx -> {
            CascadeLevel level = requireLevel(ctx, depth);
            stateMachine.requireLevelOwner(ctx.cascade, level, cmd.actor());
            CascadeLevelAction action = newAction(ctx.cascade, CascadeActionType.CASCADE, depth,
                    level.getRound(), cmd.actor(), cmd.idempotencyKey(), clock.instant());
            action.setLevelId(level.getId());
            action.setRefinedCritique(cmd.refinedCritique());
            action.setNextOwnerMemberId(cmd.nextOwnerMemberId());
            action.setNextOwnerLabel(cmd.nextOwnerLabel());
            return action;
        });
    }

    /** §5.3 L2/L6 — the owner takes the work; downward propagation terminates. */
    @Governed(actionType = GovernedActions.CASCADE_ABSORB)
    @Transactional
    public Cascade absorb(UUID cascadeId, int depth, AbsorbCommand cmd) {
        return execute(cascadeId, cmd.idempotencyKey(), cmd.expectedChainSeq(),
                CascadeActionType.ABSORB, depth, ctx -> {
            CascadeLevel level = requireLevel(ctx, depth);
            stateMachine.requireLevelOwner(ctx.cascade, level, cmd.actor());
            CascadeLevelAction action = newAction(ctx.cascade, CascadeActionType.ABSORB, depth,
                    level.getRound(), cmd.actor(), cmd.idempotencyKey(), clock.instant());
            action.setLevelId(level.getId());
            action.setRefinedCritique(cmd.refinedCritique());
            return action;
        });
    }

    /**
     * §5.3 L3/L4 — documented pushback, first-touch or endorse. A legitimate
     * decline ALWAYS surfaces as success (HTTP 200); only stale fencing,
     * illegal transitions or a non-owner actor conflict (ADR-001 #1).
     *
     * <p>3c PEP note: a legitimate cascade decline still surfaces as 200 —
     * the {@code @Governed} evaluation can only deny via an operator-authored
     * guard on {@code cascade.decline}; the gateway itself never error-codes
     * decline (port-map trap #8 holds at both engines).
     */
    @Governed(actionType = GovernedActions.CASCADE_DECLINE)
    @Transactional
    public Cascade decline(UUID cascadeId, int depth, DeclineCommand cmd) {
        return execute(cascadeId, cmd.idempotencyKey(), cmd.expectedChainSeq(),
                CascadeActionType.DECLINE, depth, ctx -> {
            CascadeLevel level = requireLevel(ctx, depth);
            stateMachine.requireLevelOwner(ctx.cascade, level, cmd.actor());
            CascadeLevelAction action = newAction(ctx.cascade, CascadeActionType.DECLINE, depth,
                    level.getRound(), cmd.actor(), cmd.idempotencyKey(), clock.instant());
            action.setLevelId(level.getId());
            action.setDeclineReasoning(cmd.declineReasoning());
            return action;
        });
    }

    /** §5.3 L5 — revised refinement reactivates the declined level below as a new round. */
    @Governed(actionType = GovernedActions.CASCADE_RECASCADE)
    @Transactional
    public Cascade recascade(UUID cascadeId, int depth, RecascadeCommand cmd) {
        return execute(cascadeId, cmd.idempotencyKey(), cmd.expectedChainSeq(),
                CascadeActionType.RECASCADE, depth, ctx -> {
            CascadeLevel level = requireLevel(ctx, depth);
            stateMachine.requireLevelOwner(ctx.cascade, level, cmd.actor());
            CascadeLevelAction action = newAction(ctx.cascade, CascadeActionType.RECASCADE, depth,
                    level.getRound(), cmd.actor(), cmd.idempotencyKey(), clock.instant());
            action.setLevelId(level.getId());
            action.setRefinedCritique(cmd.refinedCritique());
            action.setNextOwnerMemberId(cmd.nextOwnerMemberId());
            action.setNextOwnerLabel(cmd.nextOwnerLabel());
            return action;
        });
    }

    /**
     * §5.2 A2/A3 — originator terminal resolution. Resolve-declined requires a
     * pending decline and an {@code acceptedDeclineActionId} referencing the
     * latest DECLINE of a level in the pending run; intermediate non-response
     * stays honestly visible in the log (originator short-circuit, ADR D3).
     */
    @Governed(actionType = GovernedActions.CASCADE_RESOLVE)
    @Transactional
    public Cascade resolve(UUID cascadeId, ResolveCommand cmd) {
        if (cmd.resolution() == null) {
            throw new IllegalArgumentException("resolution is required: looks-good | declined");
        }
        boolean declined = cmd.resolution() == Resolution.DECLINED;
        CascadeActionType verb = declined
                ? CascadeActionType.RESOLVE_DECLINED : CascadeActionType.RESOLVE_LOOKS_GOOD;
        return execute(cascadeId, cmd.idempotencyKey(), cmd.expectedChainSeq(), verb, 0, ctx -> {
            stateMachine.requireOriginator(ctx.cascade, cmd.actor());
            if (declined) {
                CascadeLevelAction acceptedDecline = cmd.acceptedDeclineActionId() == null ? null
                        : actionRepository.findById(cmd.acceptedDeclineActionId()).orElse(null);
                stateMachine.validateAcceptedDecline(ctx.cascade, ctx.levels, acceptedDecline);
            }
            CascadeLevel l0 = requireLevel(ctx, 0);
            CascadeLevelAction action = newAction(ctx.cascade,
                    declined ? CascadeActionType.RESOLVE_DECLINED : CascadeActionType.RESOLVE_LOOKS_GOOD,
                    0, l0.getRound(), cmd.actor(), cmd.idempotencyKey(), clock.instant());
            action.setLevelId(l0.getId());
            action.setResolutionNote(cmd.resolutionNote());
            action.setAcceptedDeclineActionId(declined ? cmd.acceptedDeclineActionId() : null);
            return action;
        });
    }

    // -------------------------------------------------------- §5.5 command template

    /** Per-command working set handed to the verb-specific action builder. */
    private static final class CommandContext {
        final Cascade cascade;
        final NavigableMap<Integer, CascadeLevel> levels;

        CommandContext(Cascade cascade, NavigableMap<Integer, CascadeLevel> levels) {
            this.cascade = cascade;
            this.levels = levels;
        }
    }

    @FunctionalInterface
    private interface ActionBuilder {
        CascadeLevelAction build(CommandContext ctx);
    }

    private Cascade execute(UUID cascadeId, String idempotencyKey, Integer expectedChainSeq,
                            CascadeActionType verb, int depth, ActionBuilder builder) {
        requireIdempotencyKey(idempotencyKey);

        // 1. The per-cascade serialization point (ADR D8).
        Cascade cascade = cascadeRepository.findByIdForUpdate(cascadeId)
                .orElseThrow(() -> new EntityNotFoundException("Cascade not found: " + cascadeId));

        // 2. Idempotency replay under the lock: a SAME-verb/depth hit means
        //    the original action's result is re-projected as success — no new
        //    row, regardless of how the chain has moved since (the original
        //    200 semantics, D8). A hit on a DIFFERENT verb/depth is a
        //    contradiction and conflicts: replaying the original 200 there
        //    would silently drop the new command while reporting success
        //    (e.g. a DECLINE masked as a CASCADE acknowledgment — decline is
        //    never lost, ADR-001 #1). Cross-engine idiom: diagnostic
        //    CURATION_CONFLICT; recorded deviation from the cascade.md §5.5
        //    letter (no verb pin there), flagged for spec ratification.
        Optional<CascadeLevelAction> replay =
                actionRepository.findByCascadeIdAndIdempotencyKey(cascadeId, idempotencyKey);
        if (replay.isPresent()) {
            CascadeLevelAction original = replay.get();
            if (original.getAction() != verb || original.getDepth() != depth) {
                throw new CascadeConflictException(
                        CascadeConflictException.Code.CASCADE_IDEMPOTENCY_REUSE, cascadeId,
                        "idempotency key '" + idempotencyKey + "' was already spent on "
                                + original.getAction().wireValue() + " at depth "
                                + original.getDepth() + " of this cascade — it cannot replay as "
                                + verb.wireValue() + " at depth " + depth
                                + "; mint a fresh key per command (§5.5)");
            }
            log.debug("Idempotent replay [cascadeId={}, key={}, chainSeq={}]",
                    cascadeId, idempotencyKey, original.getChainSeq());
            return cascade;
        }

        // 3. Fencing token (chain moved → client refreshes, not flakiness).
        if (expectedChainSeq != null && expectedChainSeq != cascade.getChainSeq()) {
            throw new CascadeConflictException(
                    CascadeConflictException.Code.CASCADE_STALE_STATE, cascadeId,
                    "expectedChainSeq " + expectedChainSeq + " is stale; cascade is at chain_seq "
                            + cascade.getChainSeq());
        }

        // 4./5. Guards + append + projections + outbox, one transaction (I9).
        NavigableMap<Integer, CascadeLevel> levels = loadLevels(cascadeId);
        CommandContext ctx = new CommandContext(cascade, levels);
        CascadeLevelAction action = builder.build(ctx);
        stateMachine.apply(cascade, levels, action, effectiveSlaDays(cascade));

        levelRepository.saveAll(levels.values());
        if (action.getLevelId() == null) {
            throw new IllegalStateException("action must reference the acted level row");
        }
        action = actionRepository.save(action);
        cascade = cascadeRepository.save(cascade);
        metrics.cascadeAction(action.getAction().wireValue());
        fanOut(cascade, levels, action);

        if (action.getAction() == CascadeActionType.RESOLVE_LOOKS_GOOD
                || action.getAction() == CascadeActionType.RESOLVE_DECLINED) {
            attestationHook.onResolved(cascade, action);
        }
        // EXTENSION POINT(cascade-12): Monitor/Automate phase-entry interplay —
        // whether cascade activity (issuance, declines, terminal resolution)
        // feeds lifecycle-phase entry criteria is UNKNOWN ([LIFECYCLE-MACHINE],
        // spec §13.12); no coupling is encoded on any transition here.
        log.info("Applied cascade action [cascadeId={}, action={}, depth={}, chainSeq={}, signal={}]",
                cascadeId, action.getAction(), action.getDepth(), action.getChainSeq(),
                cascade.getAttentionSignal());
        return cascade;
    }

    // ----------------------------------------------------- §8 notification fan-out

    /**
     * Transactional-outbox fan-out (spec §8) — rows written IN the action
     * transaction, never AFTER_COMMIT (ADR D10). Only SIBLING_DECLINE rows
     * carry a dedupe key in the lifecycle path; sweeper rows (§9) get theirs
     * in the sweeper chunk.
     */
    private void fanOut(Cascade cascade, NavigableMap<Integer, CascadeLevel> levels,
                        CascadeLevelAction action) {
        int k = action.getDepth();
        switch (action.getAction()) {
            case ISSUE -> {
                notify(cascade, action, 0, CascadeNotificationType.CASCADE_ISSUED,
                        cascade.getOriginOwnerMemberId(), cascade.getOriginOwnerLabel(),
                        "Cascade issued — L1: " + action.getNextOwnerLabel(),
                        action.getRefinedCritique(), null);
                notifyLevelActivated(cascade, action, levels.get(1));
            }
            case CASCADE -> {
                notifyLevelActivated(cascade, action, levels.get(k + 1));
                notify(cascade, action, k, CascadeNotificationType.LEVEL_CASCADED,
                        cascade.getOriginOwnerMemberId(), cascade.getOriginOwnerLabel(),
                        action.getActorLabel() + " cascaded to " + action.getNextOwnerLabel()
                                + " at L" + k,
                        action.getRefinedCritique(), null);
            }
            case ABSORB -> {
                String headline = action.getActorLabel() + " absorbed the work at L" + k;
                notify(cascade, action, k, CascadeNotificationType.LEVEL_ABSORBED,
                        cascade.getOriginOwnerMemberId(), cascade.getOriginOwnerLabel(),
                        headline, action.getRefinedCritique(), null);
                if (k >= 2) {
                    CascadeLevel above = levels.get(k - 1);
                    notify(cascade, action, k, CascadeNotificationType.LEVEL_ABSORBED,
                            above.getOwnerMemberId(), above.getOwnerLabel(),
                            headline, action.getRefinedCritique(), null);
                }
            }
            case DECLINE -> {
                // Leader-attention semantics (queue #21) — never failure tone.
                String headline = "Decline pending — " + action.getActorLabel()
                        + " declined at L" + k;
                if (k - 1 >= 1) {
                    CascadeLevel responseOwner = levels.get(k - 1);
                    notify(cascade, action, k, CascadeNotificationType.LEVEL_DECLINED,
                            responseOwner.getOwnerMemberId(), responseOwner.getOwnerLabel(),
                            headline, action.getDeclineReasoning(), null);
                }
                notify(cascade, action, k, CascadeNotificationType.DECLINE_PENDING_ORIGINATOR,
                        cascade.getOriginOwnerMemberId(), cascade.getOriginOwnerLabel(),
                        headline, action.getDeclineReasoning(), null);
                notifySiblings(cascade, action);
            }
            case RECASCADE -> notifyLevelActivated(cascade, action, levels.get(k + 1));
            case RESOLVE_LOOKS_GOOD, RESOLVE_DECLINED -> {
                boolean looksGood = action.getAction() == CascadeActionType.RESOLVE_LOOKS_GOOD;
                CascadeNotificationType type = looksGood
                        ? CascadeNotificationType.CASCADE_RESOLVED_LOOKS_GOOD
                        : CascadeNotificationType.CASCADE_RESOLVED_DECLINED;
                String headline = looksGood
                        ? "Cascade resolved — looks good"
                        : "Cascade resolved — decline accepted";
                Set<String> seen = new HashSet<>();
                for (CascadeLevel level : levels.tailMap(1, true).values()) {
                    String key = level.getOwnerMemberId() != null
                            ? "m:" + level.getOwnerMemberId()
                            : "l:" + level.getOwnerLabel();
                    if (seen.add(key)) {
                        notify(cascade, action, level.getDepth(), type,
                                level.getOwnerMemberId(), level.getOwnerLabel(),
                                headline, action.getResolutionNote(), null);
                    }
                }
            }
        }
    }

    /** LEVEL_ACTIVATED → the newly activated level's owner (ISSUE/CASCADE/RECASCADE). */
    private void notifyLevelActivated(Cascade cascade, CascadeLevelAction action,
                                      CascadeLevel activated) {
        notify(cascade, action, activated.getDepth(), CascadeNotificationType.LEVEL_ACTIVATED,
                activated.getOwnerMemberId(), activated.getOwnerLabel(),
                "Action needed — cascade awaiting your response at L" + activated.getDepth()
                        + (activated.getRound() > 1 ? " (round " + activated.getRound() + ")" : ""),
                action.getRefinedCritique(), null);
    }

    /**
     * ADR D2 advisory rows: one SIBLING_DECLINE per declining-cascade/sibling
     * pair regardless of how many levels decline — the dedupe key is
     * {@code "sibling:"+siblingCascadeId+":"+decliningCascadeId} (§8). Zero
     * state change on siblings; their detail DTOs surface these read-only.
     */
    private void notifySiblings(Cascade declining, CascadeLevelAction action) {
        if (declining.getCascadeGroupId() == null) {
            return;
        }
        List<Cascade> siblings = cascadeRepository.findByCascadeGroupIdAndCurrentState(
                declining.getCascadeGroupId(), CascadeState.IN_FLIGHT);
        for (Cascade sibling : siblings) {
            if (Objects.equals(sibling.getId(), declining.getId())) {
                continue;
            }
            ObjectNode payload = payload(declining.getId(), action.getDepth(), action.getRound(),
                    action.getId(),
                    "Sibling cascade declined at L" + action.getDepth() + " — advisory only",
                    action.getDeclineReasoning());
            payload.put("decliningCascadeId", declining.getId().toString());
            payload.put("declinedAt", action.getActedAt().toString());
            // Row anchored to the SIBLING cascade so its detail view can derive
            // the advisory (spec §6.1 siblingDeclineAdvisories).
            notificationService.insert(sibling.getId(), action.getId(), action.getDepth(),
                    CascadeNotificationType.SIBLING_DECLINE,
                    sibling.getOriginOwnerMemberId(), sibling.getOriginOwnerLabel(),
                    payload,
                    "sibling:" + sibling.getId() + ":" + declining.getId());
        }
    }

    private void notify(Cascade cascade, CascadeLevelAction action, Integer depth,
                        CascadeNotificationType type, UUID recipientMemberId, String recipientLabel,
                        String headline, String excerptSource, String dedupeKey) {
        notificationService.insert(cascade.getId(), action.getId(), depth, type,
                recipientMemberId, recipientLabel,
                payload(cascade.getId(), depth, action.getRound(), action.getId(),
                        headline, excerptSource),
                dedupeKey);
    }

    /** §8 payload shape: {cascadeId, depth, round, actionId?, headline, excerpt}. */
    private ObjectNode payload(UUID cascadeId, Integer depth, int round, UUID actionId,
                               String headline, String excerptSource) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("cascadeId", cascadeId.toString());
        if (depth != null) {
            node.put("depth", depth);
        }
        node.put("round", round);
        if (actionId != null) {
            node.put("actionId", actionId.toString());
        }
        node.put("headline", headline);
        if (excerptSource != null) {
            node.put("excerpt", excerptSource.length() <= EXCERPT_LENGTH
                    ? excerptSource
                    : excerptSource.substring(0, EXCERPT_LENGTH) + "…");
        }
        return node;
    }

    // ----------------------------------------------------------------- helpers

    private NavigableMap<Integer, CascadeLevel> loadLevels(UUID cascadeId) {
        NavigableMap<Integer, CascadeLevel> levels = new TreeMap<>();
        for (CascadeLevel level : levelRepository.findByCascadeIdOrderByDepthAsc(cascadeId)) {
            levels.put(level.getDepth(), level);
        }
        return levels;
    }

    private CascadeLevel requireLevel(CommandContext ctx, int depth) {
        CascadeLevel level = ctx.levels.get(depth);
        if (level == null) {
            throw new CascadeConflictException(
                    CascadeConflictException.Code.CASCADE_ILLEGAL_TRANSITION,
                    ctx.cascade.getId(), "no level exists at depth " + depth);
        }
        return level;
    }

    private CascadeLevelAction newAction(Cascade cascade, CascadeActionType type, int depth,
                                         int round, CascadeActor actor, String idempotencyKey,
                                         Instant actedAt) {
        CascadeLevelAction action = new CascadeLevelAction();
        action.setCascadeId(cascade.getId());
        action.setDepth(depth);
        action.setRound(round);
        action.setAction(type);
        action.setActorMemberId(actor.memberId());
        action.setActorLabel(actor.label());
        action.setActedAt(actedAt);
        action.setIdempotencyKey(idempotencyKey);
        action.setChainSeq(cascade.getChainSeq() + 1);
        return action;
    }

    /**
     * Effective SLA in days for level deadlines ({@code 0} = disabled). Stored
     * resolved on the cascade row at issue time (§9); the default fallback
     * covers only pre-resolution rows defensively.
     */
    int effectiveSlaDays(Cascade cascade) {
        return cascade.getResponseSlaDays() != null
                ? cascade.getResponseSlaDays()
                : properties.getDefaultResponseSlaDays();
    }

    /**
     * §6.3: {@code produced_by} = AI_ASSISTIVE iff the issued critique/criteria
     * text derives from a landed suggestion — pinned conservatively as exact
     * text equality with the landed {@code suggested_*} columns; an issued
     * cascade is never AI_GENERATIVE (ADR D6, P6 grounding).
     */
    private ProducedBy deriveProducedBy(CascadeDraft draft) {
        boolean landed = draft.getSuggestionState() == CascadeSuggestionState.READY;
        boolean derives = landed
                && (Objects.equals(draft.getOriginalCritique(), draft.getSuggestedCritique())
                || Objects.equals(draft.getAcceptanceCriteria(), draft.getSuggestedAcceptanceCriteria()));
        return derives ? ProducedBy.AI_ASSISTIVE : ProducedBy.HUMAN;
    }

    private static void requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(
                    "idempotencyKey is mandatory on every state-changing cascade request (ADR D8)");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
