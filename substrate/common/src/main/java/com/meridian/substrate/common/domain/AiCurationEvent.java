package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.CurationAction;
import com.meridian.substrate.common.domain.enums.CurationSubjectKind;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * APPEND-ONLY unified curation audit row (engine-spec diagnostic.md §3.1;
 * ADR-004 D4): the durable record of every human accept/reject decision over
 * AI output — committed findings ({@code subject_kind=FINDING}) and
 * generative drafts ({@code subject_kind=DRAFT}) — plus the system-authored
 * SUPERSEDE events written at run finalization. The accountability act
 * deserves its own attributed, timestamped record; discipline is not a
 * structure.
 *
 * <p>Rows are NEVER updated or deleted: the repository surface
 * ({@link com.meridian.substrate.common.repo.AiCurationEventRepository})
 * exposes no update/delete methods (extends {@code Repository}, not
 * {@code JpaRepository}), services never mutate loaded rows, and on Postgres
 * the V7_1 trigger raises on any UPDATE/DELETE. H2 dev is service-enforced
 * only (precedent V4_1; H2-green is never acceptance). Rejection is data: the
 * subject row stays, the projection flips, history is never deleted —
 * ACCEPT-after-REJECT is legal recourse (append another event; last event
 * wins the projection).
 *
 * <p>All references are soft (no DB FKs, app-enforced): {@code subject_id} is
 * a finding id or an AI_GENERATIVE assertion id per {@code subject_kind};
 * {@code created_entity_id} is the capability materialized by a DRAFT ACCEPT;
 * {@code synthesis_run_id} is the superseding run on SUPERSEDE events.
 *
 * <p>Actor identity comes from PersonaContext headers (spoofable until the
 * Phase 5 OIDC principal replaces the source — S9 posture, stated in any
 * compliance conversation).
 */
@Entity
@Table(name = "ai_curation_events",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_curation_idem",
                        columnNames = {"subject_kind", "subject_id", "idempotency_key"})
        },
        indexes = {
                @Index(name = "idx_curation_subject", columnList = "subject_kind, subject_id"),
                @Index(name = "idx_curation_domain", columnList = "domain_id")
        })
public class AiCurationEvent extends BaseEntity {

    /** Soft ref → domains; no DB FK. */
    @Column(name = "domain_id", nullable = false)
    private UUID domainId;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_kind", nullable = false, length = 20)
    private CurationSubjectKind subjectKind;

    /** finding.id (FINDING) | assertion.id (DRAFT). Soft ref; no DB FK. */
    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    private CurationAction action;

    /** Service-enforced NOT NULL for REJECT (400 RATIONALE_REQUIRED when absent). */
    @Column(name = "rationale", columnDefinition = "TEXT")
    private String rationale;

    /**
     * JSON delta when ACCEPT carried human edits (DRAFT path) — the human
     * edit is itself provenance (ADR-004 D9).
     */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "overridden_fields", columnDefinition = "TEXT")
    private JsonNode overriddenFields;

    /** DRAFT ACCEPT → materialized capability id. Soft ref; no DB FK. */
    @Column(name = "created_entity_id")
    private UUID createdEntityId;

    /**
     * SUPERSEDE → the superseding run. Soft ref; no DB FK.
     *
     * <p>EXTENSION POINT(diagnostic-11): supersession scope normalization.
     * v1 supersedes on exact (domainId, scope-set) match only at run
     * finalization; overlapping-scope runs can accumulate near-duplicate
     * pending findings. Operator triage feedback decides (ADR-004
     * follow-up #7). Flag, don't invent.
     */
    @Column(name = "synthesis_run_id")
    private UUID synthesisRunId;

    /** PersonaContext label now; OIDC principal Phase 5 (S9). */
    @Column(name = "actor_label", nullable = false)
    private String actorLabel;

    @Column(name = "actor_persona_id")
    private String actorPersonaId;

    /** Mandatory; replay returns the original result (UNIQUE backstop uq_curation_idem). */
    @Column(name = "idempotency_key", nullable = false, length = 100)
    private String idempotencyKey;

    public UUID getDomainId() { return domainId; }
    public void setDomainId(UUID domainId) { this.domainId = domainId; }

    public CurationSubjectKind getSubjectKind() { return subjectKind; }
    public void setSubjectKind(CurationSubjectKind subjectKind) { this.subjectKind = subjectKind; }

    public UUID getSubjectId() { return subjectId; }
    public void setSubjectId(UUID subjectId) { this.subjectId = subjectId; }

    public CurationAction getAction() { return action; }
    public void setAction(CurationAction action) { this.action = action; }

    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }

    public JsonNode getOverriddenFields() { return overriddenFields; }
    public void setOverriddenFields(JsonNode overriddenFields) { this.overriddenFields = overriddenFields; }

    public UUID getCreatedEntityId() { return createdEntityId; }
    public void setCreatedEntityId(UUID createdEntityId) { this.createdEntityId = createdEntityId; }

    public UUID getSynthesisRunId() { return synthesisRunId; }
    public void setSynthesisRunId(UUID synthesisRunId) { this.synthesisRunId = synthesisRunId; }

    public String getActorLabel() { return actorLabel; }
    public void setActorLabel(String actorLabel) { this.actorLabel = actorLabel; }

    public String getActorPersonaId() { return actorPersonaId; }
    public void setActorPersonaId(String actorPersonaId) { this.actorPersonaId = actorPersonaId; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
