package com.meridian.substrate.engine.cascade;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.CascadeNotification;
import com.meridian.substrate.common.domain.enums.CascadeNotificationType;
import com.meridian.substrate.common.repo.CascadeNotificationRepository;
import com.meridian.substrate.engine.contract.gateway.Ungoverned;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Transactional-outbox helper + in-app feed reads for
 * {@code cascade_notifications} (engine-spec cascade.md §8; ADR-001 D10).
 *
 * <p>Outbox rows are written IN the same transaction as the triggering action
 * — {@link CascadeLifecycleService} calls {@link #insert} from inside its
 * command transaction; there is deliberately NO {@code @TransactionalEventListener}
 * anywhere in {@code engine.cascade} (an AFTER_COMMIT write can silently drop
 * notifications on a crash between commit and listener — D10). Phase-5 channel
 * adapters poll rows where {@code dispatched_at IS NULL}; transports stay
 * decoupled, delivery is at-least-once.
 */
@Service
public class CascadeNotificationService {

    private static final Logger log = LoggerFactory.getLogger(CascadeNotificationService.class);

    private final CascadeNotificationRepository repository;
    private final Clock clock;

    public CascadeNotificationService(CascadeNotificationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Dedupe-key insert helper (spec §8): check {@code existsByDedupeKey} then
     * insert; the {@code uq_cn_dedupe} UNIQUE constraint is the DB backstop
     * for check-then-insert races (sweeper double-fires commit per row and
     * swallow the duplicate-key violation as a no-op at that call site).
     * A {@code null} dedupe key is legal and never dedupe-checked (NULLs are
     * distinct under UNIQUE on both vendors — port-map trap #4).
     *
     * <p>Joins the caller's transaction (MANDATORY would be truer to D10, but
     * the sweeper inserts in its own per-row transactions — REQUIRED covers both).
     *
     * @return the persisted row, or empty when the dedupe key already exists
     */
    @Ungoverned(reason = "engine-internal §8 transactional-outbox insert, written inside already-"
            + "governed command transactions (and by the notification-only sweeper) — not one of"
            + " the three ADR D2 PEPs")
    @Transactional
    public Optional<CascadeNotification> insert(UUID cascadeId, UUID actionId, Integer depth,
                                                CascadeNotificationType type,
                                                UUID recipientMemberId, String recipientLabel,
                                                JsonNode payload, String dedupeKey) {
        if (dedupeKey != null && repository.existsByDedupeKey(dedupeKey)) {
            log.debug("Skipping duplicate cascade notification [dedupeKey={}]", dedupeKey);
            return Optional.empty();
        }
        CascadeNotification row = new CascadeNotification();
        row.setCascadeId(cascadeId);
        row.setActionId(actionId);
        row.setDepth(depth);
        row.setNotificationType(type);
        row.setRecipientMemberId(recipientMemberId);
        row.setRecipientLabel(recipientLabel);
        row.setPayload(payload);
        row.setDedupeKey(dedupeKey);
        return Optional.of(repository.save(row));
    }

    /** Unread in-app feed for a recipient (newest first). */
    @Transactional(readOnly = true)
    public List<CascadeNotification> unreadFeed(UUID recipientMemberId) {
        return repository.findByRecipientMemberIdAndReadAtIsNullOrderByCreatedAtDesc(recipientMemberId);
    }

    /** Full outbox history of one cascade, oldest first. */
    @Transactional(readOnly = true)
    public List<CascadeNotification> forCascade(UUID cascadeId) {
        return repository.findByCascadeIdOrderByCreatedAtAsc(cascadeId);
    }

    /** Mark-read watermark for the in-app feed (idempotent). */
    @Ungoverned(reason = "in-app feed read watermark (idempotent readAt stamp) — UI plumbing on"
            + " the notification outbox, not governance-relevant business state")
    @Transactional
    public CascadeNotification markRead(UUID notificationId) {
        CascadeNotification row = repository.findById(notificationId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "CascadeNotification not found: " + notificationId));
        if (row.getReadAt() == null) {
            row.setReadAt(clock.instant());
            row = repository.save(row);
        }
        return row;
    }
}
