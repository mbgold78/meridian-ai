package com.meridian.substrate.engine.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.AgentNotification;
import com.meridian.substrate.common.domain.AgentOversightLink;
import com.meridian.substrate.common.domain.enums.AgentNotificationType;
import com.meridian.substrate.common.repo.AgentNotificationRepository;
import com.meridian.substrate.engine.contract.gateway.Ungoverned;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Transactional-outbox helper for {@code agent_notifications} (engine-spec
 * agents.md §13.4; ADR-003 D13; ADR-001 D10): rows are written IN the same
 * transaction as the triggering event — verbs call {@link #fanOut} from
 * inside their command transaction; there is deliberately NO
 * {@code @TransactionalEventListener} anywhere in {@code engine.agent}
 * (architecture assertion, test plan #14). Phase-5 channel adapters poll
 * {@code dispatched_at IS NULL}; delivery is at-least-once.
 *
 * <p>§13.4 fan-out routing is the CALLER's decision (full chain vs position
 * 1) — this helper just writes the rows it is handed, one per recipient.
 * Dedupe keys are the 6b sweeper's exactly-once mechanism; event-triggered
 * rows pass {@code null} (legal, never dedupe-checked — trap #4).
 */
@Service
public class AgentNotificationService {

    private static final Logger log = LoggerFactory.getLogger(AgentNotificationService.class);

    private final AgentNotificationRepository repository;

    public AgentNotificationService(AgentNotificationRepository repository) {
        this.repository = repository;
    }

    /** {@code GET /agents/notifications?recipientId&unreadOnly} read lane (spec §7.1). */
    @Transactional(readOnly = true)
    public List<AgentNotification> forRecipient(UUID recipientMemberId, boolean unreadOnly) {
        return unreadOnly
                ? repository.findByRecipientMemberIdAndReadAtIsNullOrderByCreatedAtDesc(recipientMemberId)
                : repository.findByRecipientMemberIdOrderByCreatedAtDesc(recipientMemberId);
    }

    /** {@code POST /agents/notifications/{id}/read} — idempotent read watermark (spec §7.1). */
    @Ungoverned(reason = "recipient-side read watermark on an outbox row — presentation"
            + " bookkeeping, not a lifecycle verb or ADR D2 PEP (engine-spec agents.md §7.1)")
    @Transactional
    public AgentNotification markRead(UUID notificationId, java.time.Instant readAt) {
        AgentNotification row = repository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "notification not found: " + notificationId));
        if (row.getReadAt() == null) {
            row.setReadAt(readAt);
            row = repository.save(row);
        }
        return row;
    }

    /**
     * One outbox row per recipient link, same transaction as the caller's
     * event append (invariant I9).
     */
    @Ungoverned(reason = "engine-internal §13.4 transactional-outbox insert, written inside"
            + " already-governed/attributed verb transactions — not one of the three ADR D2 PEPs"
            + " (cascade D10 idiom)")
    @Transactional
    public List<AgentNotification> fanOut(UUID domainId, UUID agentRegistrationId, UUID eventId,
                                          AgentNotificationType type, String tone,
                                          List<AgentOversightLink> recipients, JsonNode payload,
                                          String dedupeKey) {
        if (dedupeKey != null && repository.existsByDedupeKey(dedupeKey)) {
            log.debug("Skipping duplicate agent notification [dedupeKey={}]", dedupeKey);
            return List.of();
        }
        List<AgentNotification> rows = new ArrayList<>(recipients.size());
        for (AgentOversightLink recipient : recipients) {
            AgentNotification row = new AgentNotification();
            row.setDomainId(domainId);
            row.setAgentRegistrationId(agentRegistrationId);
            row.setEventId(eventId);
            row.setNotificationType(type);
            row.setRecipientMemberId(recipient.getMemberId());
            row.setRecipientLabel(recipient.getLabel());
            row.setTone(tone);
            row.setPayload(payload);
            // The UNIQUE(dedupe_key) backstop tolerates only one row per key —
            // multi-recipient fan-outs carry the key on the FIRST row only.
            row.setDedupeKey(rows.isEmpty() ? dedupeKey : null);
            rows.add(repository.save(row));
        }
        return rows;
    }
}
