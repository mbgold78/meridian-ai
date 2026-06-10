package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.common.domain.enums.DocumentType;
import com.meridian.substrate.common.domain.enums.IngestionStatus;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Registry of documents uploaded or discovered in the system, tracking their type,
 * ingestion status into the Tachyon search platform, and processing metadata.
 */
@Entity
@Table(name = "document_registry")
public class DocumentRegistry extends BaseEntity {

    @Column(name = "source_file", nullable = false)
    private String sourceFile;

    @Column(name = "source_file_sha256", nullable = false)
    private String sourceFileSha256;

    @Column(name = "source_file_size")
    private Long sourceFileSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", length = 50)
    private DocumentType documentType;

    @Column(name = "source_system")
    private String sourceSystem;

    @Column(name = "ingested_to_tachyon", nullable = false)
    private Boolean ingestedToTachyon = false;

    @Column(name = "tachyon_file_id")
    private String tachyonFileId;

    @Column(name = "tachyon_collection")
    private String tachyonCollection;

    @Enumerated(EnumType.STRING)
    @Column(name = "ingestion_status", nullable = false, length = 50)
    private IngestionStatus ingestionStatus = IngestionStatus.LOCAL_ONLY;

    @Column(name = "ingested_at")
    private Instant ingestedAt;

    @Column(name = "extracted_text_bytes")
    private Long extractedTextBytes;

    @Column(name = "processed_by_converter_run_id")
    private UUID processedByConverterRunId;

    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "metadata", columnDefinition = "TEXT")
    private JsonNode metadata;

    public String getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(String sourceFile) {
        this.sourceFile = sourceFile;
    }

    public String getSourceFileSha256() {
        return sourceFileSha256;
    }

    public void setSourceFileSha256(String sourceFileSha256) {
        this.sourceFileSha256 = sourceFileSha256;
    }

    public Long getSourceFileSize() {
        return sourceFileSize;
    }

    public void setSourceFileSize(Long sourceFileSize) {
        this.sourceFileSize = sourceFileSize;
    }

    public DocumentType getDocumentType() {
        return documentType;
    }

    public void setDocumentType(DocumentType documentType) {
        this.documentType = documentType;
    }

    public String getSourceSystem() {
        return sourceSystem;
    }

    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
    }

    public Boolean getIngestedToTachyon() {
        return ingestedToTachyon;
    }

    public void setIngestedToTachyon(Boolean ingestedToTachyon) {
        this.ingestedToTachyon = ingestedToTachyon;
    }

    public String getTachyonFileId() {
        return tachyonFileId;
    }

    public void setTachyonFileId(String tachyonFileId) {
        this.tachyonFileId = tachyonFileId;
    }

    public String getTachyonCollection() {
        return tachyonCollection;
    }

    public void setTachyonCollection(String tachyonCollection) {
        this.tachyonCollection = tachyonCollection;
    }

    public IngestionStatus getIngestionStatus() {
        return ingestionStatus;
    }

    public void setIngestionStatus(IngestionStatus ingestionStatus) {
        this.ingestionStatus = ingestionStatus;
    }

    public Instant getIngestedAt() {
        return ingestedAt;
    }

    public void setIngestedAt(Instant ingestedAt) {
        this.ingestedAt = ingestedAt;
    }

    public Long getExtractedTextBytes() {
        return extractedTextBytes;
    }

    public void setExtractedTextBytes(Long extractedTextBytes) {
        this.extractedTextBytes = extractedTextBytes;
    }

    public UUID getProcessedByConverterRunId() {
        return processedByConverterRunId;
    }

    public void setProcessedByConverterRunId(UUID processedByConverterRunId) {
        this.processedByConverterRunId = processedByConverterRunId;
    }

    public JsonNode getMetadata() {
        return metadata;
    }

    public void setMetadata(JsonNode metadata) {
        this.metadata = metadata;
    }
}
