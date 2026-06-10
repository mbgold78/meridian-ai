package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.IntegrationStatus;
import com.meridian.substrate.common.domain.enums.IntegrationType;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * Represents an integration between two systems, capturing the mechanism, status,
 * data elements exchanged, and operational frequency.
 */
@Entity
@Table(name = "integrations")
public class Integration extends BaseEntity {

    @Column(name = "domain_id")
    private UUID domainId;

    @Column(name = "source_system_id")
    private UUID sourceSystemId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_system_id", insertable = false, updatable = false)
    private SystemEntity sourceSystem;

    @Column(name = "target_system_id")
    private UUID targetSystemId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_system_id", insertable = false, updatable = false)
    private SystemEntity targetSystem;

    @Column(name = "name")
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "integration_type", length = 50)
    private IntegrationType integrationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "integration_status", length = 50)
    private IntegrationStatus integrationStatus;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "data_elements", columnDefinition = "TEXT")
    private JsonNode dataElements;

    @Column(name = "frequency")
    private String frequency;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "metadata", columnDefinition = "TEXT")
    private JsonNode metadata;

    public UUID getDomainId() {
        return domainId;
    }

    public void setDomainId(UUID domainId) {
        this.domainId = domainId;
    }

    public UUID getSourceSystemId() {
        return sourceSystemId;
    }

    public void setSourceSystemId(UUID sourceSystemId) {
        this.sourceSystemId = sourceSystemId;
    }

    public SystemEntity getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(SystemEntity sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public UUID getTargetSystemId() {
        return targetSystemId;
    }

    public void setTargetSystemId(UUID targetSystemId) {
        this.targetSystemId = targetSystemId;
    }

    public SystemEntity getTargetSystem() {
        return targetSystem;
    }

    public void setTargetSystem(SystemEntity targetSystem) {
        this.targetSystem = targetSystem;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public IntegrationType getIntegrationType() {
        return integrationType;
    }

    public void setIntegrationType(IntegrationType integrationType) {
        this.integrationType = integrationType;
    }

    public IntegrationStatus getIntegrationStatus() {
        return integrationStatus;
    }

    public void setIntegrationStatus(IntegrationStatus integrationStatus) {
        this.integrationStatus = integrationStatus;
    }

    public JsonNode getDataElements() {
        return dataElements;
    }

    public void setDataElements(JsonNode dataElements) {
        this.dataElements = dataElements;
    }

    public String getFrequency() {
        return frequency;
    }

    public void setFrequency(String frequency) {
        this.frequency = frequency;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public JsonNode getMetadata() {
        return metadata;
    }

    public void setMetadata(JsonNode metadata) {
        this.metadata = metadata;
    }
}
