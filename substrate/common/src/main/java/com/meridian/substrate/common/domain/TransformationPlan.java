package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.meridian.substrate.common.domain.enums.ReductionMode;
import com.meridian.substrate.common.domain.enums.TransformationPlanStatus;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Transformation plan — D5 thin slice of the doc-06 schema (engine-spec
 * diagnostic.md §3.3; ADR-004 D13; V8). DECLARED S4 DEVIATION: fields ported
 * faithfully; planning-engine semantics (wave sequencing rules, plan
 * state-machine gates, rationalization lens) are NOT implemented in v1 —
 * status transitions are unenforced, {@code reductionMode} is a recorded lens
 * parameter with no lens behind it. EXTENSION POINT(diagnostic-9): the S4
 * planning-engine design session may reshape this schema; ripple is priced
 * cheap under the fresh-DB posture and flagged at slice close. The fixture
 * {@code GET /transformation/plan} endpoint stays behind its flag, untouched.
 */
@Entity
@Table(name = "transformation_plans")
public class TransformationPlan extends BaseEntity {

    @Column(name = "domain_id", nullable = false)
    private UUID domainId;

    /** Soft ref navigation twin (no DB FK — app-enforced, canonical convention). */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "domain_id", insertable = false, updatable = false)
    private Domain domain;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private TransformationPlanStatus status = TransformationPlanStatus.DRAFT;

    @Column(name = "target_horizon_months")
    private Integer targetHorizonMonths;

    @Enumerated(EnumType.STRING)
    @Column(name = "reduction_mode", length = 20)
    private ReductionMode reductionMode;

    @Column(name = "approved_by")
    private String approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    public UUID getDomainId() { return domainId; }
    public void setDomainId(UUID domainId) { this.domainId = domainId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public TransformationPlanStatus getStatus() { return status; }
    public void setStatus(TransformationPlanStatus status) { this.status = status; }

    public Integer getTargetHorizonMonths() { return targetHorizonMonths; }
    public void setTargetHorizonMonths(Integer targetHorizonMonths) { this.targetHorizonMonths = targetHorizonMonths; }

    public ReductionMode getReductionMode() { return reductionMode; }
    public void setReductionMode(ReductionMode reductionMode) { this.reductionMode = reductionMode; }

    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }

    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
}
