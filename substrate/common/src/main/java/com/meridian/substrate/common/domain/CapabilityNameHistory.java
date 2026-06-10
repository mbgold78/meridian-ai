package com.meridian.substrate.common.domain;

import jakarta.persistence.*;

import java.util.UUID;

/**
 * Immutable record of a capability displayName rename. Written by the
 * CapabilityService when a rename occurs. Phase 2 surfaces this in the UI
 * and feeds the governance hash chain.
 */
@Entity
@Table(name = "capability_name_history")
public class CapabilityNameHistory extends BaseEntity {

    @Column(name = "capability_id", nullable = false)
    private UUID capabilityId;

    @Column(name = "from_display_name", nullable = false)
    private String fromDisplayName;

    @Column(name = "to_display_name", nullable = false)
    private String toDisplayName;

    @Column(name = "rationale", columnDefinition = "TEXT")
    private String rationale;

    public UUID getCapabilityId() { return capabilityId; }
    public void setCapabilityId(UUID capabilityId) { this.capabilityId = capabilityId; }

    public String getFromDisplayName() { return fromDisplayName; }
    public void setFromDisplayName(String fromDisplayName) { this.fromDisplayName = fromDisplayName; }

    public String getToDisplayName() { return toDisplayName; }
    public void setToDisplayName(String toDisplayName) { this.toDisplayName = toDisplayName; }

    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }
}
