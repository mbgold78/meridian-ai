package com.meridian.substrate.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Push-based agent metric feed (engine-spec agents.md §4 table 7 / §9.2;
 * ADR-002 D11 stance, D13 idempotent-upsert idiom on
 * {@code (agent, metric_key, period_key)}). {@code adherence} is just
 * {@code metric_key='adherence'} — severing the S6 fixture-stamped number;
 * the engine never fabricates an adherence rubric (spec §17 #6).
 */
@Entity
@Table(name = "agent_metric_samples",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_ams_sample",
                        columnNames = {"agent_registration_id", "metric_key", "period_key"})
        })
public class AgentMetricSample extends BaseEntity {

    /** Soft ref → agent_registrations; no DB FK. */
    @Column(name = "agent_registration_id", nullable = false)
    private UUID agentRegistrationId;

    /** e.g. 'prefill_accuracy', 'adherence'. */
    @Column(name = "metric_key", nullable = false, length = 128)
    private String metricKey;

    @Column(name = "metric_value", nullable = false)
    private double metricValue;

    /** Contracts §5.1 period-key conventions (caller-supplied on report). */
    @Column(name = "period_key", nullable = false, length = 16)
    private String periodKey;

    @Column(name = "reported_by_principal")
    private String reportedByPrincipal;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

    public UUID getAgentRegistrationId() {
        return agentRegistrationId;
    }

    public void setAgentRegistrationId(UUID agentRegistrationId) {
        this.agentRegistrationId = agentRegistrationId;
    }

    public String getMetricKey() {
        return metricKey;
    }

    public void setMetricKey(String metricKey) {
        this.metricKey = metricKey;
    }

    public double getMetricValue() {
        return metricValue;
    }

    public void setMetricValue(double metricValue) {
        this.metricValue = metricValue;
    }

    public String getPeriodKey() {
        return periodKey;
    }

    public void setPeriodKey(String periodKey) {
        this.periodKey = periodKey;
    }

    public String getReportedByPrincipal() {
        return reportedByPrincipal;
    }

    public void setReportedByPrincipal(String reportedByPrincipal) {
        this.reportedByPrincipal = reportedByPrincipal;
    }

    public Instant getReportedAt() {
        return reportedAt;
    }

    public void setReportedAt(Instant reportedAt) {
        this.reportedAt = reportedAt;
    }
}
