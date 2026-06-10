package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.LinkStatus;
import com.meridian.substrate.common.domain.enums.LinkType;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * Link between a process and a system describing the nature of their
 * relationship (e.g., bridges, compensates, feeds into).
 */
@Entity
@Table(name = "process_system_links")
public class ProcessSystemLink extends BaseEntity {

    @Column(name = "process_id", nullable = false)
    private UUID processId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "process_id", insertable = false, updatable = false)
    private Process process;

    @Column(name = "system_id", nullable = false)
    private UUID systemId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "system_id", insertable = false, updatable = false)
    private SystemEntity system;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_type", nullable = false, length = 50)
    private LinkType linkType;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "data_elements", columnDefinition = "TEXT")
    private JsonNode dataElements;

    @Column(name = "frequency")
    private String frequency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 50)
    private LinkStatus status;

    @Column(name = "ai_inferred")
    private Boolean aiInferred;

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

    public LinkType getLinkType() {
        return linkType;
    }

    public void setLinkType(LinkType linkType) {
        this.linkType = linkType;
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

    public LinkStatus getStatus() {
        return status;
    }

    public void setStatus(LinkStatus status) {
        this.status = status;
    }

    public Boolean getAiInferred() {
        return aiInferred;
    }

    public void setAiInferred(Boolean aiInferred) {
        this.aiInferred = aiInferred;
    }
}
