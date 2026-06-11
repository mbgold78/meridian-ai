package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.CascadeNotification;
import com.meridian.substrate.common.domain.enums.CascadeNotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for {@link CascadeNotification} transactional-outbox rows
 * (engine-spec cascade.md §4; ADR D10). Insert helper discipline: check
 * {@link #existsByDedupeKey} then insert; the {@code uq_cn_dedupe} UNIQUE
 * constraint is the backstop and duplicate-key violations are swallowed as
 * no-ops (sweeper races, spec §8).
 */
@Repository
public interface CascadeNotificationRepository extends JpaRepository<CascadeNotification, UUID> {

    List<CascadeNotification> findByRecipientMemberIdAndReadAtIsNullOrderByCreatedAtDesc(UUID recipientId);

    /** Full feed (read + unread) for {@code GET /cascades/notifications?unreadOnly=false} (spec §6.1). */
    List<CascadeNotification> findByRecipientMemberIdOrderByCreatedAtDesc(UUID recipientId);

    List<CascadeNotification> findByCascadeIdOrderByCreatedAtAsc(UUID cascadeId);

    List<CascadeNotification> findByCascadeIdAndNotificationType(UUID cascadeId, CascadeNotificationType type);

    boolean existsByDedupeKey(String dedupeKey);
}
