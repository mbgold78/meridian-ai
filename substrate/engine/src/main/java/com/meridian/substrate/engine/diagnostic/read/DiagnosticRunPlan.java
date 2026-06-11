package com.meridian.substrate.engine.diagnostic.read;

import java.util.List;
import java.util.UUID;

/**
 * The {@code dryRun=true} response body (engine-spec diagnostic.md §10.1):
 * the deterministic batch plan, with nothing persisted.
 */
public record DiagnosticRunPlan(
        UUID domainId,
        List<String> scopes,
        int batchSize,
        int requiredMinFindings,
        String executor,
        int totalBatches,
        List<PlannedBatchView> batches
) {

    public record PlannedBatchView(
            int batchNo,
            String scope,
            int targetCount,
            List<UUID> targetEntityIds
    ) {}
}
