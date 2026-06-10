package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;

import java.util.UUID;

/**
 * Represents a technology system registered within a domain. Maps to the "systems" table
 * to avoid collision with SQL reserved words.
 */
@Entity
@Table(name = "systems")
public class SystemEntity extends BaseEntity {

    @Column(name = "domain_id")
    private UUID domainId;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "domain_id", insertable = false, updatable = false)
    private Domain domain;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "vendor")
    private String vendor;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "system_type")
    private String systemType;

    @Column(name = "deployed_year")
    private Integer deployedYear;

    @Column(name = "status")
    private String status;

    @Column(name = "business_line")
    private String businessLine;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "metadata", columnDefinition = "TEXT")
    private JsonNode metadata;

    public UUID getDomainId() {
        return domainId;
    }

    public void setDomainId(UUID domainId) {
        this.domainId = domainId;
    }

    public Domain getDomain() {
        return domain;
    }

    public void setDomain(Domain domain) {
        this.domain = domain;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVendor() {
        return vendor;
    }

    public void setVendor(String vendor) {
        this.vendor = vendor;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSystemType() {
        return systemType;
    }

    public void setSystemType(String systemType) {
        this.systemType = systemType;
    }

    public Integer getDeployedYear() {
        return deployedYear;
    }

    public void setDeployedYear(Integer deployedYear) {
        this.deployedYear = deployedYear;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getBusinessLine() {
        return businessLine;
    }

    public void setBusinessLine(String businessLine) {
        this.businessLine = businessLine;
    }

    public JsonNode getMetadata() {
        return metadata;
    }

    public void setMetadata(JsonNode metadata) {
        this.metadata = metadata;
    }
}
