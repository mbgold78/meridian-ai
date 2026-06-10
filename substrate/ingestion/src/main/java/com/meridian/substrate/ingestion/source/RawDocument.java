package com.meridian.substrate.ingestion.source;

import java.util.Map;

/**
 * A fully fetched document with its extracted text content.
 */
public record RawDocument(
    DocumentRef ref,
    String extractedText,
    long sizeBytes,
    Map<String, Object> metadata
) {}
