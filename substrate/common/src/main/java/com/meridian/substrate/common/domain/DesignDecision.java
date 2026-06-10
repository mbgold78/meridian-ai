package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.DecisionStatus;
import com.meridian.substrate.common.domain.enums.DecisionType;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * Captures a design decision made in response to findings, tracking its lifecycle,
 * rationale, impact, and hash chain for auditability.
 */
@Entity
@Table(name = "design_decisions")
public class DesignDecision extends BaseEntity {

    @Column(name = "domain_id")
    private UUID domainId;

    @Enumerated(EnumType.STRING)
    @Column(name = "decision_type", nullable = false, length = 50)
    private DecisionType decisionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private DecisionStatus status;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "rationale", columnDefinition = "TEXT")
    private String rationale;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "impact_summary", columnDefinition = "TEXT")
    private JsonNode impactSummary;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "affected_system_ids", columnDefinition = "TEXT")
    private JsonNode affectedSystemIds;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "affected_process_ids", columnDefinition = "TEXT")
    private JsonNode affectedProcessIds;

    @Column(name = "previous_hash")
    private String previousHash;

    @Column(name = "current_hash")
    private String currentHash;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "metadata", columnDefinition = "TEXT")
    private JsonNode metadata;

    public UUID getDomainId() {
        return domainId;
    }

    public void setDomainId(UUID domainId) {
        this.domainId = domainId;
    }

    public DecisionType getDecisionType() {
        return decisionType;
    }

    public void setDecisionType(DecisionType decisionType) {
        this.decisionType = decisionType;
    }

    public DecisionStatus getStatus() {
        return status;
    }

    public void setStatus(DecisionStatus status) {
        this.status = status;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public JsonNode getImpactSummary() {
        return impactSummary;
    }

    public void setImpactSummary(JsonNode impactSummary) {
        this.impactSummary = impactSummary;
    }

    public JsonNode getAffectedSystemIds() {
        return affectedSystemIds;
    }

    public void setAffectedSystemIds(JsonNode affectedSystemIds) {
        this.affectedSystemIds = affectedSystemIds;
    }

    public JsonNode getAffectedProcessIds() {
        return affectedProcessIds;
    }

    public void setAffectedProcessIds(JsonNode affectedProcessIds) {
        this.affectedProcessIds = affectedProcessIds;
    }

    public String getPreviousHash() {
        return previousHash;
    }

    public void setPreviousHash(String previousHash) {
        this.previousHash = previousHash;
    }

    public String getCurrentHash() {
        return currentHash;
    }

    public void setCurrentHash(String currentHash) {
        this.currentHash = currentHash;
    }

    public JsonNode getMetadata() {
        return metadata;
    }

    public void setMetadata(JsonNode metadata) {
        this.metadata = metadata;
    }
}
