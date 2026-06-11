package com.meridian.substrate.engine.cascade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.Cascade;
import com.meridian.substrate.common.domain.CascadeLevel;
import com.meridian.substrate.common.domain.CascadeLevelAction;
import com.meridian.substrate.common.domain.enums.CascadeActionType;
import com.meridian.substrate.common.domain.enums.CascadeLevelState;
import com.meridian.substrate.common.domain.enums.CascadeNotificationType;
import com.meridian.substrate.common.domain.enums.CascadeState;
import com.meridian.substrate.common.repo.CascadeLevelActionRepository;
import com.meridian.substrate.common.repo.CascadeLevelRepository;
import com.meridian.substrate.common.repo.CascadeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Escalation sweeper (engine-spec cascade.md §9; ADR-001 D5) — hourly
 * {@code @Scheduled}, <b>notification-ONLY: the sweeper NEVER transitions
 * state</b> (the system never speaks for a human in an accountability chain;
 * no synthetic accept/decline, ever).
 *
 * <p>Two dwell measurements per in-flight cascade, both windowed at
 * {@code deadline + n * overdue-renotify-days} for
 * {@code n = 0..overdue-renotify-limit}:
 * <ul>
 *   <li><b>LOOKING overdue</b> — the frontier is LOOKING past its
 *       {@code response_deadline} (computed at (re)activation): insert
 *       {@code LEVEL_OVERDUE} rows to the frontier owner + the level-above
 *       owner + the originator (§8), and set the {@code overdue} projection
 *       flags on the level + cascade rows.</li>
 *   <li><b>Decline-pending dwell</b> — the frontier is DECLINED and the latest
 *       DECLINE action is older than the SLA: insert {@code DECLINE_OVERDUE}
 *       rows to the originator + the response owner (the CASCADED level above,
 *       who holds the three §5.3 response verbs). Deliberately NO projection
 *       flag here: §10 pins {@code overdue} as <i>LOOKING-past-deadline</i>
 *       (the rebuilder recomputes it exactly so) — flagging a DECLINED
 *       frontier would be live-vs-rebuild drift (test plan #7).</li>
 * </ul>
 *
 * <p>Idempotent under restarts and double-fires by construction (D5 graft):
 * deterministic dedupe keys with the {@code uq_cn_dedupe} UNIQUE backstop —
 * at most one notification per (cascade, depth, round, window) <i>per
 * recipient</i> (§12 test #8). The spec's §9 key is per-window; with the §8
 * multi-recipient fan-out under a global UNIQUE constraint that key alone
 * would collapse the fan-out to one row, so a fixed recipient-slot suffix
 * ({@code :frontier} / {@code :above} / {@code :originator} /
 * {@code :response}) is appended — auditable deviation, preserving exactly the
 * test-#8 semantic. Recipients are additionally party-deduplicated within a
 * window (e.g. at frontier depth 1 the level-above owner IS the originator).
 *
 * <p>SLA resolution: {@code response_sla_days} is stored resolved on the
 * cascade row at issue time; explicit {@code 0} disables the SLA — such
 * cascades are never swept. {@code overdue} flags are cleared by
 * {@link CascadeLifecycleService} (via the state machine) on any transition of
 * the affected level/cascade, never by the sweeper.
 *
 * <p>Each cascade sweeps in its own transaction under the per-cascade
 * {@code PESSIMISTIC_WRITE} lock (no torn reads against a concurrent verb);
 * a lost dedupe race surfaces as {@link DataIntegrityViolationException} on
 * commit and is swallowed as a no-op for that cascade — the next sweep
 * re-evaluates.
 */
@Service
public class CascadeEscalationSweeper {

    private static final Logger log = LoggerFactory.getLogger(CascadeEscalationSweeper.class);

    /** §8 payload excerpt cap (mirrors the lifecycle fan-out). */
    private static final int EXCERPT_LENGTH = 200;

    private final CascadeRepository cascadeRepository;
    private final CascadeLevelRepository levelRepository;
    private final CascadeLevelActionRepository actionRepository;
    private final CascadeNotificationService notificationService;
    private final CascadeProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public CascadeEscalationSweeper(CascadeRepository cascadeRepository,
                                    CascadeLevelRepository levelRepository,
                                    CascadeLevelActionRepository actionRepository,
                                    CascadeNotificationService notificationService,
                                    CascadeProperties properties,
                                    Clock clock,
                                    ObjectMapper objectMapper,
                                    PlatformTransactionManager transactionManager) {
        this.cascadeRepository = cascadeRepository;
        this.levelRepository = levelRepository;
        this.actionRepository = actionRepository;
        this.notificationService = notificationService;
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Hourly by default; {@code substrate.cascade.sweep-cron} overrides (§9). */
    @Scheduled(cron = "${substrate.cascade.sweep-cron:0 0 * * * *}")
    public void sweep() {
        List<UUID> inFlight = cascadeRepository.findByCurrentState(CascadeState.IN_FLIGHT)
                .stream().map(Cascade::getId).toList();
        int swept = 0;
        for (UUID cascadeId : inFlight) {
            try {
                transactionTemplate.executeWithoutResult(status -> sweepCascade(cascadeId));
                swept++;
            } catch (DataIntegrityViolationException dedupeRace) {
                // The UNIQUE backstop won a check-then-insert race (double-fire
                // or concurrent node) — by design a no-op; next sweep re-evaluates.
                log.debug("Cascade sweep lost a dedupe race — skipping [cascadeId={}]",
                        cascadeId, dedupeRace);
            }
        }
        log.debug("Cascade escalation sweep complete [inFlight={}, swept={}]",
                inFlight.size(), swept);
    }

    /** One cascade, one transaction, under the per-cascade lock. */
    private void sweepCascade(UUID cascadeId) {
        Cascade cascade = cascadeRepository.findByIdForUpdate(cascadeId).orElse(null);
        if (cascade == null || cascade.getCurrentState() != CascadeState.IN_FLIGHT) {
            return; // resolved (or gone) since the candidate list was taken
        }
        int slaDays = cascade.getResponseSlaDays() != null
                ? cascade.getResponseSlaDays()
                : properties.getDefaultResponseSlaDays();
        if (slaDays <= 0) {
            return; // SLA disabled per cascade (ADR D5) — never swept
        }
        CascadeLevel frontier = levelRepository
                .findByCascadeIdAndDepth(cascadeId, cascade.getFrontierDepth())
                .orElse(null);
        if (frontier == null) {
            // Projection drift is the rebuilder's domain (§10) — never repair here.
            log.warn("Frontier level row missing — skipping sweep [cascadeId={}, depth={}]",
                    cascadeId, cascade.getFrontierDepth());
            return;
        }
        if (cascade.getFrontierState() == CascadeLevelState.LOOKING) {
            sweepLookingOverdue(cascade, frontier);
        } else if (cascade.getFrontierState() == CascadeLevelState.DECLINED) {
            sweepDeclineDwell(cascade, frontier, slaDays);
        }
        // Frontier ABSORBED: no demand is measured — originator review
        // expectations on absorbed work are EXTENSION POINT(cascade-2)
        // (spec §13.2; ADR D12) — flag, don't invent.

        // EXTENSION POINT(cascade-9): timeout auto-advance/auto-reassign —
        // never in v1 (ADR D5: the system never speaks for a human in an
        // accountability chain); an elapsed window only ever notifies. If
        // demanded, it lands as explicit per-cascade policy, default off
        // (spec §13.9) — not here.
    }

    // ------------------------------------------------------- LOOKING overdue

    private void sweepLookingOverdue(Cascade cascade, CascadeLevel frontier) {
        Instant deadline = frontier.getResponseDeadline();
        Instant now = clock.instant();
        if (deadline == null || now.isBefore(deadline)) {
            return;
        }
        int k = frontier.getDepth();
        CascadeLevel above = levelRepository
                .findByCascadeIdAndDepth(cascade.getId(), k - 1).orElse(null);

        String headline = "Overdue — " + frontier.getOwnerLabel() + " at L" + k
                + " past the response window";
        for (int n : elapsedWindows(deadline, now)) {
            String baseKey = "overdue:" + cascade.getId() + ":" + k + ":"
                    + frontier.getRound() + ":" + n;
            ObjectNode payload = payload(cascade.getId(), k, frontier.getRound(), null,
                    headline, cascade.getOriginalCritique());
            // §8 fan-out order: frontier owner + k−1 owner + originator,
            // party-deduplicated within the window.
            List<Recipient> recipients = new ArrayList<>();
            recipients.add(new Recipient("frontier", frontier.getOwnerMemberId(),
                    frontier.getOwnerLabel()));
            if (above != null) {
                recipients.add(new Recipient("above", above.getOwnerMemberId(),
                        above.getOwnerLabel()));
            }
            recipients.add(new Recipient("originator", cascade.getOriginOwnerMemberId(),
                    cascade.getOriginOwnerLabel()));
            insertWindowRows(cascade, k, CascadeNotificationType.LEVEL_OVERDUE,
                    payload, baseKey, recipients);
        }
        // Projection overlay (§9): set on the level + cascade rows; cleared by
        // the lifecycle path on any transition of the affected level/cascade.
        if (!frontier.isOverdue() || !cascade.isOverdue()) {
            frontier.setOverdue(true);
            cascade.setOverdue(true);
            levelRepository.save(frontier);
            cascadeRepository.save(cascade);
            log.info("Marked cascade overdue [cascadeId={}, depth={}, deadline={}]",
                    cascade.getId(), k, deadline);
        }
    }

    // -------------------------------------------------- decline-pending dwell

    private void sweepDeclineDwell(Cascade cascade, CascadeLevel frontier, int slaDays) {
        CascadeLevelAction latestDecline = latestDecline(cascade.getId());
        if (latestDecline == null) {
            throw new IllegalStateException("frontier is DECLINED but no DECLINE action exists"
                    + " in the log [cascadeId=" + cascade.getId() + "]");
        }
        Instant deadline = latestDecline.getActedAt().plus(Duration.ofDays(slaDays));
        Instant now = clock.instant();
        if (now.isBefore(deadline)) {
            return;
        }
        int k = frontier.getDepth();
        CascadeLevel responseOwner = k - 1 >= 1
                ? levelRepository.findByCascadeIdAndDepth(cascade.getId(), k - 1).orElse(null)
                : null;

        String headline = "Decline pending response — declined at L" + k + " by "
                + latestDecline.getActorLabel() + ", awaiting originator or L" + (k - 1);
        for (int n : elapsedWindows(deadline, now)) {
            String baseKey = "decline-overdue:" + cascade.getId() + ":"
                    + latestDecline.getId() + ":" + n;
            ObjectNode payload = payload(cascade.getId(), k, latestDecline.getRound(),
                    latestDecline.getId(), headline, latestDecline.getDeclineReasoning());
            List<Recipient> recipients = new ArrayList<>();
            recipients.add(new Recipient("originator", cascade.getOriginOwnerMemberId(),
                    cascade.getOriginOwnerLabel()));
            if (responseOwner != null) {
                recipients.add(new Recipient("response", responseOwner.getOwnerMemberId(),
                        responseOwner.getOwnerLabel()));
            }
            insertWindowRows(cascade, k, CascadeNotificationType.DECLINE_OVERDUE,
                    payload, baseKey, recipients);
        }
    }

    // ----------------------------------------------------------------- shared

    /**
     * Elapsed renotify windows {@code n = 0..limit} at
     * {@code deadline + n * renotify-days} (§9). A non-positive renotify
     * interval degrades to the single n=0 window (defensive — all later
     * windows would otherwise elapse simultaneously).
     */
    private List<Integer> elapsedWindows(Instant deadline, Instant now) {
        List<Integer> elapsed = new ArrayList<>();
        int renotifyDays = properties.getOverdueRenotifyDays();
        for (int n = 0; n <= properties.getOverdueRenotifyLimit(); n++) {
            if (n > 0 && renotifyDays <= 0) {
                break;
            }
            Instant windowAt = deadline.plus(Duration.ofDays((long) n * renotifyDays));
            if (now.isBefore(windowAt)) {
                break;
            }
            elapsed.add(n);
        }
        return elapsed;
    }

    /** One outbox row per recipient slot; already-notified windows no-op on the dedupe key. */
    private void insertWindowRows(Cascade cascade, int depth, CascadeNotificationType type,
                                  ObjectNode payload, String baseKey, List<Recipient> recipients) {
        Set<String> seenParties = new HashSet<>();
        for (Recipient recipient : recipients) {
            String partyKey = recipient.memberId() != null
                    ? "m:" + recipient.memberId()
                    : "l:" + recipient.label();
            if (!seenParties.add(partyKey)) {
                continue;
            }
            // action_id stays NULL for sweeper rows (spec §3.5); the decline
            // action id rides the payload/dedupe key where applicable.
            notificationService.insert(cascade.getId(), null, depth, type,
                    recipient.memberId(), recipient.label(), payload,
                    baseKey + ":" + recipient.slot());
        }
    }

    /**
     * The latest DECLINE in the log — with the frontier DECLINED this is the
     * frontier level's decline (every new decline in the run happens at the
     * new frontier, §5.1/§5.3 L4).
     */
    private CascadeLevelAction latestDecline(UUID cascadeId) {
        CascadeLevelAction latest = null;
        for (CascadeLevelAction action : actionRepository.findByCascadeIdOrderByChainSeqAsc(cascadeId)) {
            if (action.getAction() == CascadeActionType.DECLINE) {
                latest = action;
            }
        }
        return latest;
    }

    /** §8 payload shape: {cascadeId, depth, round, actionId?, headline, excerpt}. */
    private ObjectNode payload(UUID cascadeId, int depth, int round, UUID actionId,
                               String headline, String excerptSource) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("cascadeId", cascadeId.toString());
        node.put("depth", depth);
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

    /** A §8 fan-out slot: fixed slot name (dedupe-key suffix) + party identity. */
    private record Recipient(String slot, UUID memberId, String label) {
    }
}
