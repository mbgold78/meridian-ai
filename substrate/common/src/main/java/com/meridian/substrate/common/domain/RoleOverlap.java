package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * Records a detected overlap in roles or responsibilities between two organization members,
 * optionally flagged as AI-inferred.
 */
@Entity
@Table(name = "role_overlaps")
public class RoleOverlap extends BaseEntity {

    @Column(name = "domain_id")
    private UUID domainId;

    @Column(name = "source_member_id")
    private UUID sourceMemberId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_member_id", insertable = false, updatable = false)
    private OrganizationMember sourceMember;

    @Column(name = "target_member_id")
    private UUID targetMemberId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_member_id", insertable = false, updatable = false)
    private OrganizationMember targetMember;

    @Column(name = "overlap_type")
    private String overlapType;

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

    public UUID getSourceMemberId() {
        return sourceMemberId;
    }

    public void setSourceMemberId(UUID sourceMemberId) {
        this.sourceMemberId = sourceMemberId;
    }

    public OrganizationMember getSourceMember() {
        return sourceMember;
    }

    public void setSourceMember(OrganizationMember sourceMember) {
        this.sourceMember = sourceMember;
    }

    public UUID getTargetMemberId() {
        return targetMemberId;
    }

    public void setTargetMemberId(UUID targetMemberId) {
        this.targetMemberId = targetMemberId;
    }

    public OrganizationMember getTargetMember() {
        return targetMember;
    }

    public void setTargetMember(OrganizationMember targetMember) {
        this.targetMember = targetMember;
    }

    public String getOverlapType() {
        return overlapType;
    }

    public void setOverlapType(String overlapType) {
        this.overlapType = overlapType;
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
