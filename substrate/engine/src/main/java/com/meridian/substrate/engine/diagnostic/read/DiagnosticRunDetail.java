package com.meridian.substrate.engine.diagnostic.read;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Full run detail (engine-spec diagnostic.md §10.1,
 * {@code GET /api/v1/diagnostics/runs/{id}}): status, counts, the batch
 * ledger, aggregate filter histogram, mandatory explanation when committed=0
 * (spec §10.2 lane 2), token/cost totals, and the dispatch-provenance digest
 * (orchestrated lane only — null for in-process runs, by construction;
 * absent-vs-zero stays structurally distinguishable).
 *
 * <p>{@code status} renders ZERO_YIELD / CANCELLED as PRODUCT outcomes inside
 * a 200 envelope — never through an error mapping (spec non-negotiable #3;
 * test plan #17).
 */
public record DiagnosticRunDetail(
        UUID runId,
        UUID domainId,
        String status,
        String reason,
        String explanation,
        Instant startedAt,
        Instant completedAt,
        String model,
        String executor,
        int requiredMinFindings,
        Integer candidatesReturned,
        Integer candidatesFiltered,
        Integer findingsCommitted,
        Integer totalInputTokens,
        Integer totalOutputTokens,
        Double totalCostEstimate,
        Boolean refusalDetected,
        Map<String, Integer> filterHistogram,
        Map<String, Integer> executorMix,
        Map<String, Long> batchCounts,
        List<BatchView> batches,
        List<String> events,
        JsonNode dispatchProvenance
) {

    /** One batch-ledger row (spec §3.1), render-ready. */
    public record BatchView(
            UUID id,
            int batchNo,
            String scope,
            String status,
            int attempt,
            String executor,
            Integer candidatesReturned,
            Integer candidatesFiltered,
            Integer findingsCommitted,
            JsonNode filterHistogram,
            String error,
            String dispatchTaskId,
            int targetCount,
            Instant claimedAt,
            Instant startedAt,
            Instant finishedAt
    ) {}
}
