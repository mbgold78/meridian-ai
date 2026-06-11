package com.meridian.substrate.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * DB-backed window counter for ACTIONS_PER_WINDOW budget enforcement
 * (engine-spec agents.md §4 table 9; ADR-003 D12). Explicit S12 lesson:
 * NEVER in-memory atomics (CostTracker's restart-lossy, multi-node-silent
 * counters) — increments are atomic {@code @Modifying} UPDATEs inside the
 * gateway check transaction (6c), restart-safe and multi-instance-safe.
 */
@Entity
@Table(name = "agent_rate_counters",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_arc_window",
                        columnNames = {"agent_registration_id", "window_start"})
        })
public class AgentRateCounter extends BaseEntity {

    /** Soft ref → agent_registrations; no DB FK. */
    @Column(name = "agent_registration_id", nullable = false)
    private UUID agentRegistrationId;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "action_count", nullable = false)
    private int actionCount = 0;

    public UUID getAgentRegistrationId() {
        return agentRegistrationId;
    }

    public void setAgentRegistrationId(UUID agentRegistrationId) {
        this.agentRegistrationId = agentRegistrationId;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public void setWindowStart(Instant windowStart) {
        this.windowStart = windowStart;
    }

    public int getActionCount() {
        return actionCount;
    }

    public void setActionCount(int actionCount) {
        this.actionCount = actionCount;
    }
}
