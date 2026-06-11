package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * Navigation back-ref from a contract to a substrate entity (engine-spec
 * contracts.md §3.2 table 6) — severs the fixture
 * {@code linkedSubsidiarySlugs} seam.
 *
 * <p>{@code linkedEntityId} is nullable during the pre-D5 window; {@code slug}
 * is the fixture slug fallback until subsidiary canonical modeling lands.
 * Soft refs throughout — no DB FKs, application-enforced.
 */
@Entity
@Table(name = "contract_entity_links")
public class ContractEntityLink extends BaseEntity {

    /** Soft ref → contracts; no DB FK (app-enforced). */
    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", insertable = false, updatable = false)
    private Contract contract;

    /** DOMAIN | SYSTEM | PROCESS | CASCADE | AGENT_REGISTRATION | SUBSIDIARY_SLUG. */
    @Column(name = "linked_entity_type", nullable = false, length = 64)
    private String linkedEntityType;

    /** Nullable during the pre-D5 window (slug-only link). */
    @Column(name = "linked_entity_id")
    private UUID linkedEntityId;

    /** Fixture slug fallback until subsidiary canonical modeling (D5). */
    @Column(name = "slug", length = 160)
    private String slug;

    @Column(name = "label")
    private String label;

    // -------------------------------------------------------------- accessors

    public UUID getContractId() {
        return contractId;
    }

    public void setContractId(UUID contractId) {
        this.contractId = contractId;
    }

    public Contract getContract() {
        return contract;
    }

    public String getLinkedEntityType() {
        return linkedEntityType;
    }

    public void setLinkedEntityType(String linkedEntityType) {
        this.linkedEntityType = linkedEntityType;
    }

    public UUID getLinkedEntityId() {
        return linkedEntityId;
    }

    public void setLinkedEntityId(UUID linkedEntityId) {
        this.linkedEntityId = linkedEntityId;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
