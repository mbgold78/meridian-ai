package com.meridian.substrate.common.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Resumability ledger row for a diagnostic run (engine-spec diagnostic.md
 * §3.1/§4.3; ADR-004 D2): the batch is the atomicity unit. LLM generation
 * happens OUTSIDE any transaction; finding commits + this ledger update + run
 * counters happen in ONE transaction. A crash between generation and commit
 * leaves the batch RUNNING with a stale {@code claimed_at}; resume treats
 * stale RUNNING as FAILED and re-executes — duplicates are impossible because
 * a failed batch committed nothing (the tx boundary IS the dedupe).
 *
 * <p>{@code status} is a free-text lifecycle marker (STATUS_* constants)
 * matching the canonical free-text-status convention for run-shaped rows
 * (cf. {@link SynthesisRun}/{@link IngestRun}); {@code STATUS_SKIPPED_BUDGET}
 * renders neutral — budget refusal is a governance non-event, never
 * error-colored, and SKIPPED_BUDGET batches don't block run COMPLETED.
 *
 * <p>No new run table exists: {@code synthesis_runs} IS the run record
 * ({@code synthesisRunId} soft ref, no DB FK — app-enforced).
 *
 * <p>EXTENSION POINT(diagnostic-14): run/batch retention + run archival is
 * deliberately unbounded in v1 — first-engagement calibration item (ADR-004
 * follow-up #8). Flag, don't invent.
 */
@Entity
@Table(name = "diagnostic_run_batches",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_run_batch",
                        columnNames = {"synthesis_run_id", "batch_no"})
        },
        indexes = {
                @Index(name = "idx_diag_batches_run", columnList = "synthesis_run_id")
        })
public class DiagnosticRunBatch extends BaseEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_SKIPPED_BUDGET = "SKIPPED_BUDGET";

    /** Soft ref → synthesis_runs; no DB FK. */
    @Column(name = "synthesis_run_id", nullable = false)
    private UUID synthesisRunId;

    @Column(name = "batch_no", nullable = false)
    private int batchNo;

    /** systems | processes | canonical-entities | domain (the cross-entity batch). */
    @Column(name = "scope", nullable = false, length = 50)
    private String scope;

    /** JSON array of entity UUIDs; NULL for the domain-wide batch. */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "target_entity_ids", columnDefinition = "TEXT")
    private JsonNode targetEntityIds;

    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_PENDING;

    @Column(name = "attempt", nullable = false)
    private int attempt = 0;

    /** in-process | orchestrator | in-process-fallback. */
    @Column(name = "executor", length = 20)
    private String executor;

    @Column(name = "candidates_returned")
    private Integer candidatesReturned;

    @Column(name = "candidates_filtered")
    private Integer candidatesFiltered;

    @Column(name = "findings_committed")
    private Integer findingsCommitted;

    /** JSON: {badType, badSeverity, missingField, ungrounded} (CandidateFilter outcomes). */
    @Convert(converter = JsonNodeConverter.class)
    @Column(name = "filter_histogram", columnDefinition = "TEXT")
    private JsonNode filterHistogram;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    /** Orchestrated lane only — the submit_task dispatch id. */
    @Column(name = "dispatch_task_id", length = 100)
    private String dispatchTaskId;

    /** Stale-claim detection input: RUNNING older than stale-batch-minutes is resumable as FAILED. */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public UUID getSynthesisRunId() { return synthesisRunId; }
    public void setSynthesisRunId(UUID synthesisRunId) { this.synthesisRunId = synthesisRunId; }

    public int getBatchNo() { return batchNo; }
    public void setBatchNo(int batchNo) { this.batchNo = batchNo; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public JsonNode getTargetEntityIds() { return targetEntityIds; }
    public void setTargetEntityIds(JsonNode targetEntityIds) { this.targetEntityIds = targetEntityIds; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }

    public String getExecutor() { return executor; }
    public void setExecutor(String executor) { this.executor = executor; }

    public Integer getCandidatesReturned() { return candidatesReturned; }
    public void setCandidatesReturned(Integer candidatesReturned) { this.candidatesReturned = candidatesReturned; }

    public Integer getCandidatesFiltered() { return candidatesFiltered; }
    public void setCandidatesFiltered(Integer candidatesFiltered) { this.candidatesFiltered = candidatesFiltered; }

    public Integer getFindingsCommitted() { return findingsCommitted; }
    public void setFindingsCommitted(Integer findingsCommitted) { this.findingsCommitted = findingsCommitted; }

    public JsonNode getFilterHistogram() { return filterHistogram; }
    public void setFilterHistogram(JsonNode filterHistogram) { this.filterHistogram = filterHistogram; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public String getDispatchTaskId() { return dispatchTaskId; }
    public void setDispatchTaskId(String dispatchTaskId) { this.dispatchTaskId = dispatchTaskId; }

    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
