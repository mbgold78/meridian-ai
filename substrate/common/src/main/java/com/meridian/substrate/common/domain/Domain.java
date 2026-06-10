package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;

/**
 * Represents a business domain within MERIDIAN, serving as the top-level organizational
 * unit for systems, processes, findings, and design decisions.
 */
@Entity
@Table(name = "domains")
public class Domain extends BaseEntity {

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "taxonomy_config", columnDefinition = "TEXT")
    private JsonNode taxonomyConfig;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "regulatory_context", columnDefinition = "TEXT")
    private JsonNode regulatoryContext;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "metadata", columnDefinition = "TEXT")
    private JsonNode metadata;

    @Column(name = "is_demo", nullable = false)
    private boolean isDemo;

    public boolean isDemo() { return isDemo; }
    public void setDemo(boolean demo) { isDemo = demo; }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public JsonNode getTaxonomyConfig() {
        return taxonomyConfig;
    }

    public void setTaxonomyConfig(JsonNode taxonomyConfig) {
        this.taxonomyConfig = taxonomyConfig;
    }

    public JsonNode getRegulatoryContext() {
        return regulatoryContext;
    }

    public void setRegulatoryContext(JsonNode regulatoryContext) {
        this.regulatoryContext = regulatoryContext;
    }

    public JsonNode getMetadata() {
        return metadata;
    }

    public void setMetadata(JsonNode metadata) {
        this.metadata = metadata;
    }
}
