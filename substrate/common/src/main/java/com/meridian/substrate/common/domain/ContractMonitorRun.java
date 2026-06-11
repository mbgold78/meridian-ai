package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.MonitorRunOutcome;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * APPEND-ONLY record of a single monitor run (engine-spec contracts.md §3.2
 * table 9; ADR-002 D10 evaluation-trace graft). The repository surface is
 * insert-only by construction
 * ({@link com.meridian.substrate.common.repo.ContractMonitorRunRepository}
 * extends {@code Repository}, spec §10.9; ArchUnit rule lands with 3c).
 *
 * <p>{@code details} is a bounded JSON trace (rule, inputs resolved,
 * counts/values), capped at {@code substrate.contracts.trace.max-bytes} with a
 * {@code "truncated": true} marker (spec §1).
 */
@Entity
@Table(name = "contract_monitor_runs")
public class ContractMonitorRun extends BaseEntity {

    /** Soft ref → contract_monitors; no DB FK (app-enforced). */
    @Column(name = "monitor_id", nullable = false)
    private UUID monitorId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "monitor_id", insertable = false, updatable = false)
    private ContractMonitor monitor;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 32)
    private MonitorRunOutcome outcome;

    /** Evaluated period, e.g. '2026-07' / '2026-Q3'. */
    @Column(name = "period_key", length = 16)
    private String periodKey;

    /** Bounded JSON trace; capped per substrate.contracts.trace.max-bytes (§1). */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "details", columnDefinition = "TEXT")
    private JsonNode details;

    // -------------------------------------------------------------- accessors

    public UUID getMonitorId() {
        return monitorId;
    }

    public void setMonitorId(UUID monitorId) {
        this.monitorId = monitorId;
    }

    public ContractMonitor getMonitor() {
        return monitor;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public MonitorRunOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(MonitorRunOutcome outcome) {
        this.outcome = outcome;
    }

    public String getPeriodKey() {
        return periodKey;
    }

    public void setPeriodKey(String periodKey) {
        this.periodKey = periodKey;
    }

    public JsonNode getDetails() {
        return details;
    }

    public void setDetails(JsonNode details) {
        this.details = details;
    }
}
