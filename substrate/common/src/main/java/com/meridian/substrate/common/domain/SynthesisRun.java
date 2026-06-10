package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks a synthesis run that analyzes domain data and generates findings,
 * including execution timing, model usage, and cost metrics.
 */
@Entity
@Table(name = "synthesis_runs")
public class SynthesisRun extends BaseEntity {

    @Column(name = "domain_id")
    private UUID domainId;

    @Column(name = "run_type")
    private String runType;

    @Column(name = "status")
    private String status;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "findings_generated")
    private Integer findingsGenerated;

    @Column(name = "model")
    private String model;

    @Column(name = "total_input_tokens")
    private Integer totalInputTokens;

    @Column(name = "total_output_tokens")
    private Integer totalOutputTokens;

    @Column(name = "total_cost_estimate")
    private Double totalCostEstimate;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "metadata", columnDefinition = "TEXT")
    private JsonNode metadata;

    public UUID getDomainId() {
        return domainId;
    }

    public void setDomainId(UUID domainId) {
        this.domainId = domainId;
    }

    public String getRunType() {
        return runType;
    }

    public void setRunType(String runType) {
        this.runType = runType;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Integer getFindingsGenerated() {
        return findingsGenerated;
    }

    public void setFindingsGenerated(Integer findingsGenerated) {
        this.findingsGenerated = findingsGenerated;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getTotalInputTokens() {
        return totalInputTokens;
    }

    public void setTotalInputTokens(Integer totalInputTokens) {
        this.totalInputTokens = totalInputTokens;
    }

    public Integer getTotalOutputTokens() {
        return totalOutputTokens;
    }

    public void setTotalOutputTokens(Integer totalOutputTokens) {
        this.totalOutputTokens = totalOutputTokens;
    }

    public Double getTotalCostEstimate() {
        return totalCostEstimate;
    }

    public void setTotalCostEstimate(Double totalCostEstimate) {
        this.totalCostEstimate = totalCostEstimate;
    }

    public JsonNode getMetadata() {
        return metadata;
    }

    public void setMetadata(JsonNode metadata) {
        this.metadata = metadata;
    }
}
