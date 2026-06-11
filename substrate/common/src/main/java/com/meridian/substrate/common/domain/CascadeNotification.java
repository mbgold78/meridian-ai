package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.CascadeNotificationType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Transactional-outbox notification row, written IN the same transaction as
 * the triggering action (engine-spec cascade.md §3.5; ADR D10 — never an
 * AFTER_COMMIT listener).
 *
 * <p>{@code dedupeKey} is deterministic per fan-out rule (spec §8/§9) with a
 * DB UNIQUE backstop; NULL keys are legal and non-unique-checked on both
 * vendors (NULLs distinct — verified in the Testcontainers suite, port-map
 * trap #4). {@code readAt} drives the in-app feed; {@code dispatchedAt} is
 * the Phase-5 channel-adapter watermark.
 *
 * <p>All cross-entity references are soft (no DB FKs, app-enforced).
 */
@Entity
@Table(name = "cascade_notifications",
        uniqueConstraints = @UniqueConstraint(name = "uq_cn_dedupe", columnNames = "dedupe_key"))
public class CascadeNotification extends BaseEntity {

    /** Soft ref → cascades; no DB FK. */
    @Column(name = "cascade_id", nullable = false)
    private UUID cascadeId;

    /** Triggering action (soft ref → cascade_level_actions); NULL for sweeper rows. */
    @Column(name = "action_id")
    private UUID actionId;

    @Column(name = "depth")
    private Integer depth;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 40)
    private CascadeNotificationType notificationType;

    /** Soft ref → organization_members; no DB FK. */
    @Column(name = "recipient_member_id")
    private UUID recipientMemberId;

    @Column(name = "recipient_label", nullable = false)
    private String recipientLabel;

    /** JSON payload: {cascadeId, depth, round, actionId?, headline, excerpt} (spec §8). */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "payload", columnDefinition = "TEXT")
    private JsonNode payload;

    /** Deterministic dedupe key; NULL legal and non-unique-checked on both vendors. */
    @Column(name = "dedupe_key", length = 160)
    private String dedupeKey;

    /** In-app feed read watermark. */
    @Column(name = "read_at")
    private Instant readAt;

    /** Phase-5 channel-adapter dispatch watermark (at-least-once delivery). */
    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    // -------------------------------------------------------------- accessors

    public UUID getCascadeId() {
        return cascadeId;
    }

    public void setCascadeId(UUID cascadeId) {
        this.cascadeId = cascadeId;
    }

    public UUID getActionId() {
        return actionId;
    }

    public void setActionId(UUID actionId) {
        this.actionId = actionId;
    }

    public Integer getDepth() {
        return depth;
    }

    public void setDepth(Integer depth) {
        this.depth = depth;
    }

    public CascadeNotificationType getNotificationType() {
        return notificationType;
    }

    public void setNotificationType(CascadeNotificationType notificationType) {
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
