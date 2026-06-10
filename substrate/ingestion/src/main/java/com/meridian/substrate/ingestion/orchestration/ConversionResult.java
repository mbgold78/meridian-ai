package com.meridian.substrate.ingestion.orchestration;

import java.util.List;
import java.util.UUID;

/**
 * Summary of a conversion pipeline run.
 *
 * <p>{@code filesAlreadyIngested} and {@code filesUnsupported} are split (not a
 * single "skipped" bucket) because the CLI's zero-yield rule treats them
 * differently: a directory of already-committed inputs is a legitimate no-op,
 * a directory yielding nothing new is a HARD FAIL (no-silent-zero-yield norm).
 *
 * @param filesProcessed      files converted to entity JSON this run
 * @param filesAlreadyIngested files skipped because their sha256 matched an
 *                            already-committed DocumentRegistry row
 * @param filesUnsupported    files skipped (no reader / empty content)
 * @param entitiesExtracted   total records written across all output files
 * @param errors              number of per-file failures
 * @param errorMessages       human-readable failure messages
 * @param ingestRunId         id of the IngestRun audit row written for this run
 */
public record ConversionResult(
    int filesProcessed,
    int filesAlreadyIngested,
    int filesUnsupported,
    int entitiesExtracted,
    int errors,
    List<String> errorMessages,
    UUID ingestRunId
) {}
