package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.ProducedBy;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * A single claim made about a subject — the substrate through which AI-,
 * human-, and agent-produced statements all flow into the same Evidence chain.
 *
 * <p>Triple-shaped: {@code subject} (entity-type + ID being asserted about),
 * {@code predicate} (what is being claimed), {@code object} (the claim value,
 * stringly-typed because objects range from scalar values to JSON blobs of
 * proposed entity fields). {@link #evidenceIds} carries the grounding Universe
 * Evidence records; {@link #producedBy} + {@link #producedByContext} preserve
 * provenance so a future audit can answer "where did this claim come from?"
 *
 * <p>{@code producedByContext} is JSON for forward compatibility — Phase 2A AI
 * modes write {@code {"mode": ..., "surface": ..., "entityType": ...}}; Phase 6
 * agents will write {@code {"agentId": ..., "scope": ...}}; HUMAN writes the
 * acting persona/user.
 *
 * <p>Phase 2A writes only the three AI {@link ProducedBy} variants. The HUMAN
 * and AGENT values exist in the enum and schema today to avoid a migration
 * later (see {@link ProducedBy} Javadoc).
 */
@Entity
@Table(name = "assertions", indexes = {
        @Index(name = "idx_assertions_domain_id", columnList = "domain_id"),
        @Index(name = "idx_assertions_produced_by", columnList = "produced_by"),
        @Index(name = "idx_assertions_subject", columnList = "subject")
})
public class Assertion extends BaseEntity {

    @Column(name = "domain_id", nullable = false)
    private UUID domainId;

    @Column(name = "subject", nullable = false, length = 500)
    private String subject;

    @Column(name = "predicate", nullable = false, length = 255)
    private String predicate;

    @Column(name = "object", nullable = false, columnDefinition = "TEXT")
    private String object;

    @Column(name = "confidence")
    private Float confidence;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "evidence_ids", columnDefinition = "TEXT")
    private JsonNode evidenceIds;

    @Enumerated(EnumType.STRING)
    @Column(name = "produced_by", nullable = false, length = 50)
    private ProducedBy producedBy;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "produced_by_context", columnDefinition = "TEXT")
    private JsonNode producedByContext;

    public UUID getDomainId() { return domainId; }
    public void setDomainId(UUID domainId) { this.domainId = domainId; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getPredicate() { return predicate; }
    public void setPredicate(String predicate) { this.predicate = predicate; }

    public String getObject() { return object; }
    public void setObject(String object) { this.object = object; }

    public Float getConfidence() { return confidence; }
    public void setConfidence(Float confidence) { this.confidence = confidence; }

    public JsonNode getEvidenceIds() { return evidenceIds; }
    public void setEvidenceIds(JsonNode evidenceIds) { this.evidenceIds = evidenceIds; }

    public ProducedBy getProducedBy() { return producedBy; }
    public void setProducedBy(ProducedBy producedBy) { this.producedBy = producedBy; }

    public JsonNode getProducedByContext() { return producedByContext; }
    public void setProducedByContext(JsonNode producedByContext) { this.producedByContext = producedByContext; }
}
