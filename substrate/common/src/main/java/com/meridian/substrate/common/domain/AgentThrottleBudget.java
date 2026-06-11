package com.meridian.substrate.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Throttle budget row (engine-spec agents.md §4 table 8; ADR-003 D12):
 * first-class indexed columns on the gateway hot path — never JSON predicates
 * (ADR-002 D9 discipline). {@code budgetKind} is a VALIDATED STRING, not an
 * enum (contracts D15 precedent): allowlist ACTIONS_PER_WINDOW |
 * TOKENS_PER_DAY | SCHEDULE_WINDOW | SAMPLING_PCT; only ACTIONS_PER_WINDOW
 * gets an evaluator in 6c (EXTENSION POINT(agent-10), spec §17 #10).
 *
 * <p>One ACTIVE budget per (agent, kind): service-enforced + V6_1 partial
 * unique backstop on Postgres.
 */
@Entity
@Table(name = "agent_throttle_budgets")
public class AgentThrottleBudget extends BaseEntity {

    /** Soft ref → agent_registrations; no DB FK. */
    @Column(name = "agent_registration_id", nullable = false)
    private UUID agentRegistrationId;

    @Column(name = "budget_kind", nullable = false, length = 40)
    private String budgetKind;

    @Column(name = "limit_value")
    private Double limitValue;

    @Column(name = "window_minutes")
    private Integer windowMinutes;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "set_by_label", nullable = false)
    private String setByLabel;

    @Column(name = "set_at", nullable = false)
    private Instant setAt;

    @Column(name = "note", length = 512)
    private String note;

    public UUID getAgentRegistrationId() {
        return agentRegistrationId;
    }

    public void setAgentRegistrationId(UUID agentRegistrationId) {
        this.agentRegistrationId = agentRegistrationId;
    }

    public String getBudgetKind() {
        return budgetKind;
    }

    public void setBudgetKind(String budgetKind) {
        this.budgetKind = budgetKind;
    }

    public Double getLimitValue() {
        return limitValue;
    }

    public void setLimitValue(Double limitValue) {
        this.limitValue = limitValue;
    }

    public Integer getWindowMinutes() {
        return windowMinutes;
    }

    public void setWindowMinutes(Integer windowMinutes) {
        this.windowMinutes = windowMinutes;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public String getSetByLabel() {
        return setByLabel;
    }

    public void setSetByLabel(String setByLabel) {
        this.setByLabel = setByLabel;
    }

    public Instant getSetAt() {
        return setAt;
    }

    public void setSetAt(Instant setAt) {
        this.setAt = setAt;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
