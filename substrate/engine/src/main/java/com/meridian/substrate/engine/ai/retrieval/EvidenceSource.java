package com.meridian.substrate.engine.ai.retrieval;

/**
 * Describes the origin of an evidence chunk, including its type, unique identifier,
 * display name, and optional URL for drill-down.
 *
 * @param type        the category of the source entity
 * @param sourceId    unique identifier (typically a UUID string) of the source record
 * @param displayName human-readable label for the source
 * @param url         optional URL for navigating to the source in the UI
 */
public record EvidenceSource(
    EvidenceSourceType type,
    String sourceId,
    String displayName,
    String url
) {}
