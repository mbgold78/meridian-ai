package com.meridian.substrate.common.domain;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "evidence", indexes = {
        @Index(name = "idx_evidence_entity_id", columnList = "entity_id"),
        @Index(name = "idx_evidence_domain_id", columnList = "domain_id")
})
public class Evidence extends BaseEntity {

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "entity_type", nullable = false, length = 100)
    private String entityType;

    @Column(name = "domain_id")
    private UUID domainId;

    @Column(name = "document_registry_id")
    private UUID documentRegistryId;

    @Column(name = "source_file")
    private String sourceFile;

    @Column(name = "confidence")
    private Float confidence;

    @Column(name = "label", length = 500)
    private String label;

    @Column(name = "extractor", length = 100)
    private String extractor;

    public UUID getEntityId() { return entityId; }
    public void setEntityId(UUID entityId) { this.entityId = entityId; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public UUID getDomainId() { return domainId; }
    public void setDomainId(UUID domainId) { this.domainId = domainId; }

    public UUID getDocumentRegistryId() { return documentRegistryId; }
    public void setDocumentRegistryId(UUID documentRegistryId) { this.documentRegistryId = documentRegistryId; }

    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }

    public Float getConfidence() { return confidence; }
    public void setConfidence(Float confidence) { this.confidence = confidence; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getExtractor() { return extractor; }
    public void setExtractor(String extractor) { this.extractor = extractor; }
}
