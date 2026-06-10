package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * Records a detected overlap between two canonical entities, including the type of
 * overlap and AI confidence score.
 */
@Entity
@Table(name = "entity_overlaps")
public class EntityOverlap extends BaseEntity {

    @Column(name = "domain_id")
    private UUID domainId;

    @Column(name = "source_entity_id")
    private UUID sourceEntityId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_entity_id", insertable = false, updatable = false)
    private CanonicalEntity sourceEntity;

    @Column(name = "target_entity_id")
    private UUID targetEntityId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_entity_id", insertable = false, updatable = false)
    private CanonicalEntity targetEntity;

    @Column(name = "overlap_type")
    private String overlapType;

    @Column(name = "confidence")
    private Double confidence;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "details", columnDefinition = "TEXT")
    private JsonNode details;

    @Column(name = "ai_inferred")
    private Boolean aiInferred;

    public UUID getDomainId() {
        return domainId;
    }

    public void setDomainId(UUID domainId) {
        this.domainId = domainId;
    }

    public UUID getSourceEntityId() {
        return sourceEntityId;
    }

    public void setSourceEntityId(UUID sourceEntityId) {
        this.sourceEntityId = sourceEntityId;
    }

    public CanonicalEntity getSourceEntity() {
        return sourceEntity;
    }

    public void setSourceEntity(CanonicalEntity sourceEntity) {
        this.sourceEntity = sourceEntity;
    }

    public UUID getTargetEntityId() {
        return targetEntityId;
    }

    public void setTargetEntityId(UUID targetEntityId) {
        this.targetEntityId = targetEntityId;
    }

    public CanonicalEntity getTargetEntity() {
        return targetEntity;
    }

    public void setTargetEntity(CanonicalEntity targetEntity) {
        this.targetEntity = targetEntity;
    }

    public String getOverlapType() {
        return overlapType;
    }

    public void setOverlapType(String overlapType) {
        this.overlapType = overlapType;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public JsonNode getDetails() {
        return details;
    }

    public void setDetails(JsonNode details) {
        this.details = details;
    }

    public Boolean getAiInferred() {
        return aiInferred;
    }

    public void setAiInferred(Boolean aiInferred) {
        this.aiInferred = aiInferred;
    }
}
