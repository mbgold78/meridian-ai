package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.ReaderKind;
import com.meridian.substrate.common.domain.enums.ReaderStatus;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A registered reader in the Universe (doc 03). Each reader kind — Structured,
 * Document, Transcript, Visual, Human, MCP_Client — becomes a row when a user
 * points the product at a source. The existing Converter ETL is retrofitted as
 * a Structured reader in Phase 5; the abstraction here lets that happen without
 * touching Converter internals.
 */
@Entity
@Table(name = "reader_registrations")
public class ReaderRegistration extends BaseEntity {

    @Column(name = "domain_id", nullable = false)
    private UUID domainId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 50)
    private ReaderKind kind;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "uri", length = 2048)
    private String uri;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "auth_context", columnDefinition = "TEXT")
    private JsonNode authContext;

    @Column(name = "registered_at")
    private Instant registeredAt;

    @Column(name = "last_read_at")
    private Instant lastReadAt;

    @Column(name = "last_fingerprint_hash", length = 255)
    private String lastFingerprintHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private ReaderStatus status = ReaderStatus.ACTIVE;

    @Column(name = "coverage_score")
    private Double coverageScore;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "metadata", columnDefinition = "TEXT")
    private JsonNode metadata;

    public UUID getDomainId() { return domainId; }
    public void setDomainId(UUID domainId) { this.domainId = domainId; }

    public ReaderKind getKind() { return kind; }
    public void setKind(ReaderKind kind) { this.kind = kind; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }

    public JsonNode getAuthContext() { return authContext; }
    public void setAuthContext(JsonNode authContext) { this.authContext = authContext; }

    public Instant getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Instant registeredAt) { this.registeredAt = registeredAt; }

    public Instant getLastReadAt() { return lastReadAt; }
    public void setLastReadAt(Instant lastReadAt) { this.lastReadAt = lastReadAt; }

    public String getLastFingerprintHash() { return lastFingerprintHash; }
    public void setLastFingerprintHash(String lastFingerprintHash) { this.lastFingerprintHash = lastFingerprintHash; }

    public ReaderStatus getStatus() { return status; }
    public void setStatus(ReaderStatus status) { this.status = status; }

    public Double getCoverageScore() { return coverageScore; }
    public void setCoverageScore(Double coverageScore) { this.coverageScore = coverageScore; }

    public JsonNode getMetadata() { return metadata; }
    public void setMetadata(JsonNode metadata) { this.metadata = metadata; }
}
