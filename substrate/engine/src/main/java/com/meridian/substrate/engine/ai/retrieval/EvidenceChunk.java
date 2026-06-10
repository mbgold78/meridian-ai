package com.meridian.substrate.engine.ai.retrieval;

import java.util.Map;

/**
 * A single piece of evidence retrieved from the domain model, including its
 * textual content, provenance, relevance score, and optional metadata.
 *
 * @param content        human-readable text summary of the evidence
 * @param source         provenance information identifying the evidence origin
 * @param relevanceScore score between 0.0 and 1.0 indicating match quality
 * @param metadata       additional key-value pairs for downstream consumers
 */
public record EvidenceChunk(
    String content,
    EvidenceSource source,
    Double relevanceScore,
    Map<String, Object> metadata
) {}
