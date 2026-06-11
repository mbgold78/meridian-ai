package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.AgentNotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Transactional-outbox notification row (engine-spec agents.md §4 table 11 /
 * §13.4; ADR-003 D13; ADR-001 D10): written in the SAME transaction as the
 * triggering event — never AFTER_COMMIT, no {@code @TransactionalEventListener}
 * in {@code engine.agent} — fanning out to the ordered oversight chain.
 *
 * <p>{@code dedupeKey} NULL is legal (multiple NULLs coexist under the UNIQUE
 * backstop — trap #4, CI-pinned); deterministic keys make sweeper renotify
 * windows exactly-once. {@code dispatchedAt} is the Phase-5 channel watermark.
 * Unification with {@code cascade_notifications} is a flagged later refactor
 * (spec §17 #14).
 */
@Entity
@Table(name = "agent_notifications",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_an_dedupe", columnNames = {"dedupe_key"})
        })
public class AgentNotification extends BaseEntity {

    /** Soft ref → domains; no DB FK. */
    @Column(name = "domain_id", nullable = false)
    private UUID domainId;

    /** Soft ref → agent_registrations; no DB FK. */
    @Column(name = "agent_registration_id", nullable = false)
    private UUID agentRegistrationId;

    /** Triggering event (soft ref); NULL for sweeper rows. */
    @Column(name = "event_id")
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 40)
    private AgentNotificationType notificationType;

    /** Soft ref → organization_members; no DB FK. */
    @Column(name = "recipient_member_id")
    private UUID recipientMemberId;

    /** Oversight-chain fan-out target. */
    @Column(name = "recipient_label", nullable = false)
    private String recipientLabel;

    /** info | warn | destructive — pause/retire-shaped rows are warn/info, never error. */
    @Column(name = "tone", length = 16)
    private String tone;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "payload", columnDefinition = "TEXT")
    private JsonNode payload;

    @Column(name = "dedupe_key", length = 160)
    private String dedupeKey;

    @Column(name = "read_at")
    private Instant readAt;

    /** Phase-5 channel watermark. */
    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    public UUID getDomainId() {
        return domainId;
    }

    public void setDomainId(UUID domainId) {
        this.domainId = domainId;
    }

    public UUID getAgentRegistrationId() {
        return agentRegistrationId;
    }

    public void setAgentRegistrationId(UUID agentRegistrationId) {
        this.agentRegistrationId = agentRegistrationId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public AgentNotificationType getNotificationType() {
        return notificationType;
    }

    public void setNotificationType(AgentNotificationType notificationType) {
        this.notificationType = notificationType;
    }

    public UUID getRecipientMemberId() {
        return recipientMemberId;
    }

    public void setRecipientMemberId(UUID recipientMemberId) {
        this.recipientMemberId = recipientMemberId;
    }

    public String getRecipientLabel() {
        return recipientLabel;
    }

    public void setRecipientLabel(String recipientLabel) {
        this.recipientLabel = recipientLabel;
    }

    public String getTone() {
        return tone;
    }

    public void setTone(String tone) {
        this.tone = tone;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public void setPayload(JsonNode payload) {
        this.payload = payload;
    }

    public String getDedupeKey() {
        return dedupeKey;
    }

    public void setDedupeKey(String dedupeKey) {
        this.dedupeKey = dedupeKey;
    }

    public Instant getReadAt() {
        return readAt;
    }

    public void setReadAt(Instant readAt) {
        this.readAt = readAt;
    }

    public Instant getDispatchedAt() {
        return dispatchedAt;
    }

    public void setDispatchedAt(Instant dispatchedAt) {
        this.dispatchedAt = dispatchedAt;
    }
}
