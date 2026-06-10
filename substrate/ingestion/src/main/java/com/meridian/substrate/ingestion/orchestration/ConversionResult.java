package com.meridian.substrate.ingestion.orchestration;

import java.util.List;
import java.util.UUID;

/**
 * Summary of a conversion pipeline run.
 *
 * @param filesProcessed    files converted to staged entity JSON this run
 * @param filesSkipped      files skipped (already ingested per sha256 dedup, or unsupported)
 * @param entitiesExtracted total records written across all output files
 * @param errors            number of per-file failures
 * @param errorMessages     human-readable failure messages
 * @param ingestRunId       id of the IngestRun audit row written for this run
 */
public record ConversionResult(
    int filesProcessed,
    int filesSkipped,
    int entitiesExtracted,
    int errors,
    List<String> errorMessages,
    UUID ingestRunId
) {}
