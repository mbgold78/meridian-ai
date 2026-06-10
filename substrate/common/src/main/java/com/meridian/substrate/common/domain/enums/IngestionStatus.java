package com.meridian.substrate.common.domain.enums;

/**
 * Tracks the ingestion lifecycle of a document into the Tachyon search system.
 */
public enum IngestionStatus {
    LOCAL_ONLY,
    PENDING,
    INDEXING,
    READY,
    FAILED
}
