package com.meridian.substrate.ingestion.source;

/**
 * A reference to a document in a source system.
 *
 * @param id          unique identifier for this document
 * @param displayName human-readable name
 * @param sourceType  origin type — "local-file" now, "tachyon-ingested" reserved
 */
public record DocumentRef(
    String id,
    String displayName,
    String sourceType
) {}
