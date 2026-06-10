package com.meridian.substrate.ingestion.source;

import java.util.List;

/**
 * Query parameters for listing documents from a source.
 */
public record SourceQuery(
    String directory,
    List<String> extensions,
    String sourceType
) {}
