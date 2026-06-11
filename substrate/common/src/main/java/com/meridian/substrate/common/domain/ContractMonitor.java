package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.MonitorRuleType;
import com.meridian.substrate.common.domain.enums.MonitorRunOutcome;
import com.meridian.substrate.common.domain.enums.MonitorStatus;
import com.meridian.substrate.common.domain.enums.Severity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Scheduled compliance monitor (engine-spec contracts.md §3.2 table 8;
 * ADR-002 D10). The contract's {@code monitorMechanism} prose stays VERBATIM
 * on the aggregate; this row carries the structured {@code ruleSpec} sibling
 * that actually executes.
 *
 * <p>Created SUSPENDED; armed (ACTIVE, {@code nextRunAt} initialized) on
 * contract activation; ALL suspended atomically on decline (§2.2).
 * {@code nextRunAt} is persisted ⇒ scheduling is DB-recoverable across
 * restarts (§10.3).
 */
@Entity
@Table(name = "contract_monitors")
public class ContractMonitor extends BaseEntity {

    /** Soft ref → contracts; no DB FK (app-enforced). */
    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", insertable = false, updatable = false)
    private Contract contract;

    /** e.g. 'missing-monthly-report'. */
    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 32)
    private MonitorRuleType ruleType;

    /** JSON: {commitmentIds?, graceDays?, threshold?, metricKey?, requiredPartyIds?, readerRegistrationId?}. */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "rule_spec", columnDefinition = "TEXT")
    private JsonNode ruleSpec;

    /** MONTHLY | QUARTERLY | DAILY | WEEKLY (free-text precedent; no enum in spec §2.1). */
    @Column(name = "cadence", nullable = false, length = 16)
    private String cadence;

    @Column(name = "grace_days", nullable = false)
    private int graceDays = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MonitorStatus status = MonitorStatus.SUSPENDED;

    /** e.g. OPERATING_PARTNER_DASHBOARD. */
    @Column(name = "surface_to", length = 64)
    private String surfaceTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 16)
    private Severity severity;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_outcome", length = 32)
    private MonitorRunOutcome lastOutcome;

    /** DB-recoverable scheduling across restarts (§10.3). */
    @Column(name = "next_run_at")
    private Instant nextRunAt;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public MonitorRuleType getRuleType() {
        return ruleType;
    }

    public void setRuleType(MonitorRuleType ruleType) {
        this.ruleType = ruleType;
    }

    public JsonNode getRuleSpec() {
        return ruleSpec;
    }

    public void setRuleSpec(JsonNode ruleSpec) {
        this.ruleSpec = ruleSpec;
    }

    public String getCadence() {
        return cadence;
    }

    public void setCadence(String cadence) {
        this.cadence = cadence;
    }

    public int getGraceDays() {
        return graceDays;
    }

    public void setGraceDays(int graceDays) {
        this.graceDays = graceDays;
    }

    public MonitorStatus getStatus() {
        return status;
    }

    public void setStatus(MonitorStatus status) {
        this.status = status;
    }

    public String getSurfaceTo() {
        return surfaceTo;
    }

    public void setSurfaceTo(String surfaceTo) {
        this.surfaceTo = surfaceTo;
    }

    public Severity getSeverity() {
        return severity;
    }

    public void setSeverity(Severity severity) {
        this.severity = severity;
    }

    public Instant getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(Instant lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public MonitorRunOutcome getLastOutcome() {
        return lastOutcome;
    }

    public void setLastOutcome(MonitorRunOutcome lastOutcome) {
        this.lastOutcome = lastOutcome;
    }

    public Instant getNextRunAt() {
        return nextRunAt;
    }

    public void setNextRunAt(Instant nextRunAt) {
        this.nextRunAt = nextRunAt;
    }
}
