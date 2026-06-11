package com.meridian.substrate.engine.diagnostic.read;

import java.time.Instant;
import java.util.UUID;

/**
 * Run-list row (engine-spec diagnostic.md §10.1,
 * {@code GET /api/v1/diagnostics/runs?domainId=} — newest first).
 * {@code findingsCommitted} is null until finalization: an in-flight count
 * would be a partial number masquerading as a total (no-silent-zero-yield
 * applied to read models).
 */
public record DiagnosticRunSummary(
        UUID runId,
        UUID domainId,
        String status,
        Instant startedAt,
        Instant completedAt,
        Integer findingsCommitted,
        int batchesTotal
) {}
