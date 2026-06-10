package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.FindingStatus;
import com.meridian.substrate.common.domain.enums.FindingType;
import com.meridian.substrate.common.domain.enums.Severity;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * Represents a finding generated during domain analysis or synthesis, capturing the type,
 * severity, affected systems and processes, evidence, and recommendations.
 */
@Entity
@Table(name = "findings")
public class Finding extends BaseEntity {

    @Column(name = "domain_id")
    private UUID domainId;

    @Enumerated(EnumType.STRING)
    @Column(name = "finding_type", nullable = false, length = 50)
    private FindingType findingType;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 50)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 50)
    private FindingStatus status;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "affected_system_ids", columnDefinition = "TEXT")
    private JsonNode affectedSystemIds;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "affected_process_ids", columnDefinition = "TEXT")
    private JsonNode affectedProcessIds;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "evidence", columnDefinition = "TEXT")
    private JsonNode evidence;

    @Column(name = "recommendation", columnDefinition = "TEXT")
    private String recommendation;

    @Column(name = "ai_generated")
    private Boolean aiGenerated;

    @Column(name = "synthesis_run_id")
    private UUID synthesisRunId;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "metadata", columnDefinition = "TEXT")
    private JsonNode metadata;

    public UUID getDomainId() {
        return domainId;
    }

    public void setDomainId(UUID domainId) {
        this.domainId = domainId;
    }

    public FindingType getFindingType() {
        return findingType;
    }

    public void setFindingType(FindingType findingType) {
        this.findingType = findingType;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public FindingStatus getStatus() {
        return status;
    }

    public void setStatus(FindingStatus status) {
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

    public JsonNode getEvidence() {
        return evidence;
    }

    public void setEvidence(JsonNode evidence) {
        this.evidence = evidence;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public void setRecommendation(String recommendation) {
        this.recommendation = recommendation;
    }

    public Boolean getAiGenerated() {
        return aiGenerated;
    }

    public void setAiGenerated(Boolean aiGenerated) {
        this.aiGenerated = aiGenerated;
    }

    public UUID getSynthesisRunId() {
        return synthesisRunId;
    }

    public void setSynthesisRunId(UUID synthesisRunId) {
        this.synthesisRunId = synthesisRunId;
    }

    public JsonNode getMetadata() {
        return metadata;
    }

    public void setMetadata(JsonNode metadata) {
        this.metadata = metadata;
    }
}
