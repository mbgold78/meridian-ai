package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.ExecutionMode;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * A canonical business entity identified within a domain, optionally linked to
 * a specific system and/or process. Represents normalized concepts that
 * may span multiple systems.
 */
@Entity
@Table(name = "canonical_entities")
public class CanonicalEntity extends BaseEntity {

    @Column(name = "domain_id")
    private UUID domainId;

    @Column(name = "system_id")
    private UUID systemId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "system_id", insertable = false, updatable = false)
    private SystemEntity system;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "attributes", columnDefinition = "TEXT")
    private JsonNode attributes;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_mode", length = 50)
    private ExecutionMode executionMode;

    @Column(name = "process_id")
    private UUID processId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "process_id", insertable = false, updatable = false)
    private Process process;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "metadata", columnDefinition = "TEXT")
    private JsonNode metadata;

    public UUID getDomainId() {
        return domainId;
    }

    public void setDomainId(UUID domainId) {
        this.domainId = domainId;
    }

    public UUID getSystemId() {
        return systemId;
    }

    public void setSystemId(UUID systemId) {
        this.systemId = systemId;
    }

    public SystemEntity getSystem() {
        return system;
    }

    public void setSystem(SystemEntity system) {
        this.system = system;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public JsonNode getAttributes() {
        return attributes;
    }

    public void setAttributes(JsonNode attributes) {
        this.attributes = attributes;
    }

    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(ExecutionMode executionMode) {
        this.executionMode = executionMode;
    }

    public UUID getProcessId() {
        return processId;
    }

    public void setProcessId(UUID processId) {
        this.processId = processId;
    }

    public Process getProcess() {
        return process;
    }

    public void setProcess(Process process) {
        this.process = process;
    }

    public JsonNode getMetadata() {
        return metadata;
    }

    public void setMetadata(JsonNode metadata) {
        this.metadata = metadata;
    }
}
