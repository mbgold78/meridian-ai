package com.meridian.substrate.common.domain;

import jakarta.persistence.*;

import java.util.UUID;

/**
 * Links a Capability to a Process.
 * link_type is a free-form hint used by Phase 3's Environment Designer;
 * no enum constraint is enforced in Phase 1.
 */
@Entity
@Table(name = "capability_process_links")
public class CapabilityProcessLink extends BaseEntity {

    @Column(name = "capability_id", nullable = false)
    private UUID capabilityId;

    @Column(name = "process_id", nullable = false)
    private UUID processId;

    @Column(name = "link_type", length = 50)
    private String linkType;

    @Column(name = "ai_inferred", nullable = false)
    private boolean aiInferred;

    public UUID getCapabilityId() { return capabilityId; }
    public void setCapabilityId(UUID capabilityId) { this.capabilityId = capabilityId; }

    public UUID getProcessId() { return processId; }
    public void setProcessId(UUID processId) { this.processId = processId; }

    public String getLinkType() { return linkType; }
    public void setLinkType(String linkType) { this.linkType = linkType; }

    public boolean isAiInferred() { return aiInferred; }
    public void setAiInferred(boolean aiInferred) { this.aiInferred = aiInferred; }
}
