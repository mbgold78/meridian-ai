package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.ContractStatus;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Coordination-contract aggregate root (engine-spec contracts.md §3.2 table 1;
 * ADR-002 D7/D8).
 *
 * <p>Identity is dual (ADR D8): UUID is the PK/FK currency; {@code contractKey}
 * is the stable slug (unique per domain) used by MCP tools, UI deep links and
 * the fixture {@code monitorMechanism} text.
 *
 * <p>{@code monitorText} / {@code monitorMechanism} are stored VERBATIM and
 * rendered unchanged by the UI mono panel (ADR D10 — display never silently
 * drifts from prose, prose never pretends to execute).
 *
 * <p><b>Content freeze (ADR D7):</b> parties, commitments, decline triggers,
 * estimatedValue and enforcement text are mutable only while
 * {@code status=PROPOSED} AND zero {@code contract_party_responses} rows exist.
 * Service-enforced; violation → 409 {@code CONTRACT_CONTENT_FROZEN}.
 *
 * <p>All cross-entity references are soft references — no DB FKs,
 * application-enforced (canonical-model convention, port-map §2.1).
 */
@Entity
@Table(name = "contracts")
public class Contract extends BaseEntity {

    /** Engagement org scope. Soft ref → domains; no DB FK. */
    @Column(name = "domain_id", nullable = false)
    private UUID domainId;

    /** Stable slug; external/MCP/UI id (ADR D8). Unique per domain. */
    @Column(name = "contract_key", nullable = false, length = 160)
    private String contractKey;

    /** Validated against {@code substrate.contracts.types.allowlist} (ADR D15). */
    @Column(name = "contract_type", nullable = false, length = 64)
    private String contractType;

    @Column(name = "title", nullable = false, length = 512)
    private String title;

    @Column(name = "context", columnDefinition = "TEXT")
    private String context;

    /** enforcement.monitor plain language, VERBATIM. */
    @Column(name = "monitor_text", columnDefinition = "TEXT")
    private String monitorText;

    /** enforcement.monitorMechanism, VERBATIM (UI mono panel renders unchanged). */
    @Column(name = "monitor_mechanism", columnDefinition = "TEXT")
    private String monitorMechanism;

    /** Display string, e.g. '$1.2M annually across four subsidiaries'. */
    @Column(name = "estimated_value")
    private String estimatedValue;

    /** Nullable structured sibling of {@link #estimatedValue}. */
    @Column(name = "estimated_value_amount", precision = 18, scale = 2)
    private BigDecimal estimatedValueAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ContractStatus status;

    @Column(name = "proposed_at", nullable = false)
    private Instant proposedAt;

    @Column(name = "proposed_by", nullable = false)
    private String proposedBy;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /**
     * Nullable JSON {@code {"cadence","windowDays","anchor"}} — federation
     * review boundary, UNENFORCED WHEN NULL (spec §6.2; §15 extension point #1:
     * boundary semantics appear in no homepoc doc — flag-not-invent).
     */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "review_boundary", columnDefinition = "TEXT")
    private JsonNode reviewBoundary;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "metadata", columnDefinition = "TEXT")
    private JsonNode metadata;

    // -------------------------------------------------------------- accessors

    public UUID getDomainId() {
        return domainId;
    }

    public void setDomainId(UUID domainId) {
        this.domainId = domainId;
    }

    public String getContractKey() {
        return contractKey;
    }

    public void setContractKey(String contractKey) {
        this.contractKey = contractKey;
    }

    public String getContractType() {
        return contractType;
    }

    public void setContractType(String contractType) {
        this.contractType = contractType;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }

    public String getMonitorText() {
        return monitorText;
    }

    public void setMonitorText(String monitorText) {
        this.monitorText = monitorText;
    }

    public String getMonitorMechanism() {
        return monitorMechanism;
    }

    public void setMonitorMechanism(String monitorMechanism) {
        this.monitorMechanism = monitorMechanism;
    }

    public String getEstimatedValue() {
        return estimatedValue;
    }

    public void setEstimatedValue(String estimatedValue) {
        this.estimatedValue = estimatedValue;
    }

    public BigDecimal getEstimatedValueAmount() {
        return estimatedValueAmount;
    }

    public void setEstimatedValueAmount(BigDecimal estimatedValueAmount) {
        this.estimatedValueAmount = estimatedValueAmount;
    }

    public ContractStatus getStatus() {
        return status;
    }

    public void setStatus(ContractStatus status) {
        this.status = status;
    }

    public Instant getProposedAt() {
        return proposedAt;
    }

    public void setProposedAt(Instant proposedAt) {
        this.proposedAt = proposedAt;
    }

    public String getProposedBy() {
        return proposedBy;
    }

    public void setProposedBy(String proposedBy) {
        this.proposedBy = proposedBy;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public void setActivatedAt(Instant activatedAt) {
        this.activatedAt = activatedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public JsonNode getReviewBoundary() {
        return reviewBoundary;
    }

    public void setReviewBoundary(JsonNode reviewBoundary) {
        this.reviewBoundary = reviewBoundary;
    }

    public JsonNode getMetadata() {
        return metadata;
    }

    public void setMetadata(JsonNode metadata) {
        this.metadata = metadata;
    }
}
