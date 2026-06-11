package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.InitiativeComplexity;
import com.meridian.substrate.common.domain.enums.InitiativeStatus;
import com.meridian.substrate.common.domain.enums.InitiativeType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Transformation initiative — D5 thin slice of the doc-06 schema (engine-spec
 * diagnostic.md §3.3; ADR-004 D13; V8). Progress is OPERATOR-MAINTAINED via
 * {@code PATCH /api/v1/initiatives/{id}/progress}; the ONLY computed rule in
 * the whole slice is {@code status=COMPLETE => rendered 100} (spec §12.5).
 * Deriving progress from cascade/phase activity was rejected as fabricated
 * analytics — the named demo-pressure failure mode (ADR-004 D13).
 *
 * <p>Soft refs ({@code planId}, {@code targetCapabilityId},
 * {@code targetProcessId}, {@code targetSystemId}, {@code prerequisites},
 * {@code evidenceIds}) carry NO DB FKs — app-enforced per the canonical-model
 * convention. {@code prerequisites} is an unvalidated JSON UUID array in v1
 * (dependency-graph semantics are planning-engine work — spec §15.9,
 * EXTENSION POINT(diagnostic-9)).
 */
@Entity
@Table(name = "transformation_initiatives")
public class TransformationInitiative extends BaseEntity {

    /** Soft ref → transformation_plans (no DB FK — app-enforced). */
    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "initiative_type", nullable = false, length = 30)
    private InitiativeType initiativeType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InitiativeStatus status = InitiativeStatus.PLANNED;

    @Column(name = "target_capability_id")
    private UUID targetCapabilityId;

    @Column(name = "target_process_id")
    private UUID targetProcessId;

    @Column(name = "target_system_id")
    private UUID targetSystemId;

    @Column(name = "wave")
    private Integer wave;

    @Column(name = "owner_name")
    private String ownerName;

    @Column(name = "estimated_months")
    private Integer estimatedMonths;

    @Enumerated(EnumType.STRING)
    @Column(name = "complexity", length = 10)
    private InitiativeComplexity complexity;

    @Column(name = "annual_savings_usd")
    private Long annualSavingsUsd;

    /** JSON UUID array — dependency graph, unvalidated v1 (spec §3.3). */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "prerequisites", columnDefinition = "TEXT")
    private JsonNode prerequisites;

    /** JSON UUID array — soft refs into the evidence table. */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "evidence_ids", columnDefinition = "TEXT")
    private JsonNode evidenceIds;

    /** Operator-maintained 0..100; COMPLETE renders 100 regardless (spec §12.5). */
    @Column(name = "progress_pct", nullable = false)
    private int progressPct = 0;

    @Column(name = "progress_note", columnDefinition = "TEXT")
    private String progressNote;

    /** Staleness of this instant is surfaced in the DTO, never hidden (spec §12.5). */
    @Column(name = "progress_updated_at")
    private Instant progressUpdatedAt;

    public UUID getPlanId() { return planId; }
    public void setPlanId(UUID planId) { this.planId = planId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public InitiativeType getInitiativeType() { return initiativeType; }
    public void setInitiativeType(InitiativeType initiativeType) { this.initiativeType = initiativeType; }

    public InitiativeStatus getStatus() { return status; }
    public void setStatus(InitiativeStatus status) { this.status = status; }

    public UUID getTargetCapabilityId() { return targetCapabilityId; }
    public void setTargetCapabilityId(UUID targetCapabilityId) { this.targetCapabilityId = targetCapabilityId; }

    public UUID getTargetProcessId() { return targetProcessId; }
    public void setTargetProcessId(UUID targetProcessId) { this.targetProcessId = targetProcessId; }

    public UUID getTargetSystemId() { return targetSystemId; }
    public void setTargetSystemId(UUID targetSystemId) { this.targetSystemId = targetSystemId; }

    public Integer getWave() { return wave; }
    public void setWave(Integer wave) { this.wave = wave; }

    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }

    public Integer getEstimatedMonths() { return estimatedMonths; }
    public void setEstimatedMonths(Integer estimatedMonths) { this.estimatedMonths = estimatedMonths; }

    public InitiativeComplexity getComplexity() { return complexity; }
    public void setComplexity(InitiativeComplexity complexity) { this.complexity = complexity; }

    public Long getAnnualSavingsUsd() { return annualSavingsUsd; }
    public void setAnnualSavingsUsd(Long annualSavingsUsd) { this.annualSavingsUsd = annualSavingsUsd; }

    public JsonNode getPrerequisites() { return prerequisites; }
    public void setPrerequisites(JsonNode prerequisites) { this.prerequisites = prerequisites; }

    public JsonNode getEvidenceIds() { return evidenceIds; }
    public void setEvidenceIds(JsonNode evidenceIds) { this.evidenceIds = evidenceIds; }

    public int getProgressPct() { return progressPct; }
    public void setProgressPct(int progressPct) { this.progressPct = progressPct; }

    public String getProgressNote() { return progressNote; }
    public void setProgressNote(String progressNote) { this.progressNote = progressNote; }

    public Instant getProgressUpdatedAt() { return progressUpdatedAt; }
    public void setProgressUpdatedAt(Instant progressUpdatedAt) { this.progressUpdatedAt = progressUpdatedAt; }
}
