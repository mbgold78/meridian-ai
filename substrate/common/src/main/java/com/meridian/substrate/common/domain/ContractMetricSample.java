package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Push-based v1 metric feed row (engine-spec contracts.md §3.2 table 11;
 * ADR-002 D11): populated by the {@code report_metric} MCP tool and the REST
 * sibling. Idempotent upsert on
 * {@code UNIQUE(contract_id, party_id, metric_key, period_key)}.
 *
 * <p>METRIC_THRESHOLD evaluators require complete period data from ALL
 * required parties and emit {@code ADHERENCE_STALE} (INDETERMINATE) rather
 * than computing on partial reports. Pull-based feeds are the empty
 * {@code ContractMetricProvider} SPI (§15 extension point #8) — do not build.
 */
@Entity
@Table(name = "contract_metric_samples")
public class ContractMetricSample extends BaseEntity {

    /** Soft ref → contracts; no DB FK (app-enforced). */
    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", insertable = false, updatable = false)
    private Contract contract;

    /** Soft ref → contract_parties; no DB FK (app-enforced). */
    @Column(name = "party_id", nullable = false)
    private UUID partyId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "party_id", insertable = false, updatable = false)
    private ContractParty party;

    /** e.g. 'utilization_share', 'accuracy'. */
    @Column(name = "metric_key", nullable = false, length = 128)
    private String metricKey;

    @Column(name = "metric_value", nullable = false)
    private double metricValue;

    @Column(name = "period_key", nullable = false, length = 16)
    private String periodKey;

    /** Claimed reporter, recorded verbatim (advisory pre-Phase-5, §5.5). */
    @Column(name = "reported_by", nullable = false)
    private String reportedBy;

    @Column(name = "reported_at", nullable = false)
    private Instant reportedAt;

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

    public UUID getPartyId() {
        return partyId;
    }

    public void setPartyId(UUID partyId) {
        this.partyId = partyId;
    }

    public ContractParty getParty() {
        return party;
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

    public String getReportedBy() {
        return reportedBy;
    }

    public void setReportedBy(String reportedBy) {
        this.reportedBy = reportedBy;
    }

    public Instant getReportedAt() {
        return reportedAt;
    }

    public void setReportedAt(Instant reportedAt) {
        this.reportedAt = reportedAt;
    }
}
