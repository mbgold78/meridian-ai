package com.meridian.substrate.ingestion.staging;

/**
 * Result of staging converted output for review.
 *
 * @param filesStaged number of JSON files copied into the staging dir
 * @param stagingDir  the staging directory used
 */
public record StagingResult(
    int filesStaged,
    String stagingDir
) {}
