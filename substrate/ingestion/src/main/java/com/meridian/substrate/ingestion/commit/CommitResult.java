package com.meridian.substrate.ingestion.commit;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Result of committing staged entities to the database.
 *
 * <p>{@code filesAlreadyCommitted} (sha256 match on an already-processed
 * staged file) is reported separately from {@code filesCommitted} because the
 * CLI's zero-yield rule treats a fully-deduplicated re-run as a legitimate
 * no-op, while a run that consumed nothing at all is a HARD FAIL
 * (no-silent-zero-yield norm).
 *
 * @param filesCommitted        staged files whose records were committed this run
 * @param filesAlreadyCommitted staged files skipped (sha256 already processed)
 * @param filesUnrecognized     staged files whose entity kind could not be resolved
 * @param entitiesCommitted     entities newly created this run
 * @param entitiesUpdated       entities matched by natural key and updated in place
 * @param entityCounts          per-kind created+updated counts
 * @param errors                number of failures (files, records, unresolved links)
 * @param errorMessages         human-readable failure messages
 * @param ingestRunId           id of the IngestRun audit row written for this run
 */
public record CommitResult(
    int filesCommitted,
    int filesAlreadyCommitted,
    int filesUnrecognized,
    int entitiesCommitted,
    int entitiesUpdated,
    Map<String, Integer> entityCounts,
    int errors,
    List<String> errorMessages,
    UUID ingestRunId
) {}
