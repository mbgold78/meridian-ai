package com.meridian.substrate.common.domain;

import jakarta.persistence.*;

import java.util.UUID;

/**
 * Links a Capability to a SystemEntity. Consumed by Phase 3's Environment
 * Designer and by the repositioned Rationalization Lens (doc 11) to identify
 * systems with no target-state capability assignments.
 */
@Entity
@Table(name = "capability_system_links")
public class CapabilitySystemLink extends BaseEntity {

    @Column(name = "capability_id", nullable = false)
    private UUID capabilityId;

    @Column(name = "system_id", nullable = false)
    private UUID systemId;

    @Column(name = "link_type", length = 50)
    private String linkType;

    @Column(name = "ai_inferred", nullable = false)
    private boolean aiInferred;

    public UUID getCapabilityId() { return capabilityId; }
    public void setCapabilityId(UUID capabilityId) { this.capabilityId = capabilityId; }

    public UUID getSystemId() { return systemId; }
    public void setSystemId(UUID systemId) { this.systemId = systemId; }

    public String getLinkType() { return linkType; }
    public void setLinkType(String linkType) { this.linkType = linkType; }

    public boolean isAiInferred() { return aiInferred; }
    public void setAiInferred(boolean aiInferred) { this.aiInferred = aiInferred; }
}
