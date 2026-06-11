package com.meridian.substrate.common.repo;

import com.meridian.substrate.common.domain.AgentNotification;
import com.meridian.substrate.common.domain.enums.AgentNotificationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for the {@link AgentNotification} transactional outbox
 * (engine-spec agents.md §2/§4 table 11 / §13.4; ADR-003 D13; ADR-001 D10 —
 * rows are written in the SAME transaction as the triggering event).
 */
@Repository
public interface AgentNotificationRepository extends JpaRepository<AgentNotification, UUID> {

    /** {@code GET /agents/notifications?recipientId&unreadOnly} lane (idx_an_recipient). */
    List<AgentNotification> findByRecipientMemberIdAndReadAtIsNullOrderByCreatedAtDesc(UUID recipientMemberId);

    List<AgentNotification> findByRecipientMemberIdOrderByCreatedAtDesc(UUID recipientMemberId);

    List<AgentNotification> findByAgentRegistrationIdOrderByCreatedAtAsc(UUID agentRegistrationId);

    List<AgentNotification> findByAgentRegistrationIdAndNotificationType(UUID agentRegistrationId,
                                                                         AgentNotificationType type);

    /** Sweeper exactly-once pre-check (uq_an_dedupe is the backstop; NULL keys legal). */
    boolean existsByDedupeKey(String dedupeKey);
}
