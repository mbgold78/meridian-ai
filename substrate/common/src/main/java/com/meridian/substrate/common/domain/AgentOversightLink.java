package com.meridian.substrate.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

/**
 * ORDERED oversight-escalation chain entry (engine-spec agents.md §4 table 2;
 * ADR-003 D3/D13). A table — not JSON — because the chain is the notification
 * fan-out + escalation routing target; rendered "A → B → C". Every
 * registration carries ≥1 row at deploy (invariant I1; deploy-gate code
 * {@code AGENT_PROVENANCE_MISSING_OVERSIGHT}).
 *
 * <p>EXTENSION POINT(agent-16): oversight-chain VALIDATION against the org
 * chart is NOT encoded — labels + soft member refs only;
 * {@code OrganizationMember.reportsTo} is a name string, so mis-specified
 * chains are silently legitimate in v1 (named risk, spec §17 #16).
 */
@Entity
@Table(name = "agent_oversight_links",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_aol_position",
                        columnNames = {"agent_registration_id", "position"})
        })
public class AgentOversightLink extends BaseEntity {

    /** Soft ref → agent_registrations; no DB FK. */
    @Column(name = "agent_registration_id", nullable = false)
    private UUID agentRegistrationId;

    /** 1..N up the escalation chain. */
    @Column(name = "position", nullable = false)
    private int position;

    /** Soft ref → organization_members; no DB FK. */
    @Column(name = "member_id")
    private UUID memberId;

    @Column(name = "label", nullable = false)
    private String label;

    public UUID getAgentRegistrationId() {
        return agentRegistrationId;
    }

    public void setAgentRegistrationId(UUID agentRegistrationId) {
        this.agentRegistrationId = agentRegistrationId;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public UUID getMemberId() {
        return memberId;
    }

    public void setMemberId(UUID memberId) {
        this.memberId = memberId;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
