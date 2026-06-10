package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit row for a single ingestion run (convert or commit). One row is written
 * per CLI invocation of the ingestion module; {@code DocumentRegistry.processedByConverterRunId}
 * is stamped with this row's id when a source document is committed, closing the
 * provenance loop source-file → IngestRun → committed entities → Evidence.
 *
 * <p>Net-new in substrate (port-map §2.4 / §7 chunk C6): homepoc's converter had
 * no run audit and never set {@code processedByConverterRunId}.
 *
 * <p>{@code status} is a free-text lifecycle marker (see the STATUS_* constants)
 * rather than an enum, matching the canonical model's free-text-status convention
 * for run-shaped rows (cf. {@code SynthesisRun.status} — port-map §2.1).
 */
@Entity
@Table(name = "ingest_runs", indexes = {
        @Index(name = "idx_ingest_runs_domain_id", columnList = "domain_id"),
        @Index(name = "idx_ingest_runs_started_at", columnList = "started_at")
})
public class IngestRun extends BaseEntity {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_FAILED = "FAILED";

    /** Soft reference to the target domain (no DB FK — application-enforced). */
    @Column(name = "domain_id")
    private UUID domainId;

    /** CLI command that produced this run: convert | stage | commit | convert-and-stage. */
    @Column(name = "command", length = 50)
    private String command;

    /** Input directory the run operated on (source dir for convert, staging dir for commit). */
    @Column(name = "source_dir", length = 1024)
    private String sourceDir;

    @Column(name = "status", nullable = false, length = 50)
    private String status = STATUS_RUNNING;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "files_processed")
    private Integer filesProcessed;

    @Column(name = "files_skipped")
    private Integer filesSkipped;

    @Column(name = "entities_committed")
    private Integer entitiesCommitted;

    @Column(name = "entities_updated")
    private Integer entitiesUpdated;

    @Column(name = "error_count")
    private Integer errorCount;

    /** Per-entity-type counts as a JSON object, e.g. {"system": 4, "process": 3}. */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "entity_counts", columnDefinition = "TEXT")
    private JsonNode entityCounts;

    /** Error messages as a JSON array of strings (capped by the writer). */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "errors", columnDefinition = "TEXT")
    private JsonNode errors;

    public UUID getDomainId() {
        return domainId;
    }

    public void setDomainId(UUID domainId) {
        this.domainId = domainId;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getSourceDir() {
        return sourceDir;
    }

    public void setSourceDir(String sourceDir) {
        this.sourceDir = sourceDir;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public Integer getFilesProcessed() {
        return filesProcessed;
    }

    public void setFilesProcessed(Integer filesProcessed) {
        this.filesProcessed = filesProcessed;
    }

    public Integer getFilesSkipped() {
        return filesSkipped;
    }

    public void setFilesSkipped(Integer filesSkipped) {
        this.filesSkipped = filesSkipped;
    }

    public Integer getEntitiesCommitted() {
        return entitiesCommitted;
    }

    public void setEntitiesCommitted(Integer entitiesCommitted) {
        this.entitiesCommitted = entitiesCommitted;
    }

    public Integer getEntitiesUpdated() {
        return entitiesUpdated;
    }

    public void setEntitiesUpdated(Integer entitiesUpdated) {
        this.entitiesUpdated = entitiesUpdated;
    }

    public Integer getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(Integer errorCount) {
        this.errorCount = errorCount;
    }

    public JsonNode getEntityCounts() {
        return entityCounts;
    }

    public void setEntityCounts(JsonNode entityCounts) {
        this.entityCounts = entityCounts;
    }

    public JsonNode getErrors() {
        return errors;
    }

    public void setErrors(JsonNode errors) {
        this.errors = errors;
    }
}
