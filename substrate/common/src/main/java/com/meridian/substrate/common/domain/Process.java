package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.*;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * A process within a domain — the unit of work the substrate tracks regardless
 * of its current automation state. Carries classification, ownership,
 * documentation quality, risk profile, and relationship to the three lines of defense.
 *
 * The {@link ManualProcessClassification} field describes the *pattern* of
 * manually-performed work (shadow workflow, compensating control, etc.) that
 * this process represents — it is retained after the Stage B rename because
 * those semantics are load-bearing: a Process may carry that classification
 * regardless of whether it is still performed manually.
 */
@Entity
@Table(name = "processes")
public class Process extends BaseEntity {

    @Column(name = "domain_id")
    private UUID domainId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "domain_id", insertable = false, updatable = false)
    private Domain domain;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "classification", nullable = false, length = 50)
    private ManualProcessClassification classification;

    @Column(name = "frequency")
    private String frequency;

    @Column(name = "primary_owner")
    private String primaryOwner;

    @Column(name = "business_line")
    private String businessLine;

    @Enumerated(EnumType.STRING)
    @Column(name = "defense_line", length = 50)
    private DefenseLine defenseLine;

    @Enumerated(EnumType.STRING)
    @Column(name = "documentation_quality", length = 50)
    private DocumentationQuality documentationQuality;

    @Column(name = "estimated_fte")
    private Double estimatedFte;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_rating", length = 50)
    private RiskRating riskRating;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "supporting_artifacts", columnDefinition = "TEXT")
    private JsonNode supportingArtifacts;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "metadata", columnDefinition = "TEXT")
    private JsonNode metadata;

    public UUID getDomainId() {
        return domainId;
    }

    public void setDomainId(UUID domainId) {
        this.domainId = domainId;
    }

    public Domain getDomain() {
        return domain;
    }

    public void setDomain(Domain domain) {
        this.domain = domain;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public ManualProcessClassification getClassification() {
        return classification;
    }

    public void setClassification(ManualProcessClassification classification) {
        this.classification = classification;
    }

    public String getFrequency() {
        return frequency;
    }

    public void setFrequency(String frequency) {
        this.frequency = frequency;
    }

    public String getPrimaryOwner() {
        return primaryOwner;
    }

    public void setPrimaryOwner(String primaryOwner) {
        this.primaryOwner = primaryOwner;
    }

    public String getBusinessLine() {
        return businessLine;
    }

    public void setBusinessLine(String businessLine) {
        this.businessLine = businessLine;
    }

    public DefenseLine getDefenseLine() {
        return defenseLine;
    }

    public void setDefenseLine(DefenseLine defenseLine) {
        this.defenseLine = defenseLine;
    }

    public DocumentationQuality getDocumentationQuality() {
        return documentationQuality;
    }

    public void setDocumentationQuality(DocumentationQuality documentationQuality) {
        this.documentationQuality = documentationQuality;
    }

    public Double getEstimatedFte() {
        return estimatedFte;
    }

    public void setEstimatedFte(Double estimatedFte) {
        this.estimatedFte = estimatedFte;
    }

    public RiskRating getRiskRating() {
        return riskRating;
    }

    public void setRiskRating(RiskRating riskRating) {
        this.riskRating = riskRating;
    }

    public JsonNode getSupportingArtifacts() {
        return supportingArtifacts;
    }

    public void setSupportingArtifacts(JsonNode supportingArtifacts) {
        this.supportingArtifacts = supportingArtifacts;
    }

    public JsonNode getMetadata() {
        return metadata;
    }

    public void setMetadata(JsonNode metadata) {
        this.metadata = metadata;
    }
}
