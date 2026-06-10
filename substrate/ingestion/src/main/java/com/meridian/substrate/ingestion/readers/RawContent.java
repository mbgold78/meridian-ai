package com.meridian.substrate.ingestion.readers;

import java.util.Map;

/**
 * Holds the raw extracted content from a source file.
 */
public record RawContent(
    String fileName,
    String contentType,
    String extractedText,
    long fileSizeBytes,
    Map<String, Object> metadata
) {}
