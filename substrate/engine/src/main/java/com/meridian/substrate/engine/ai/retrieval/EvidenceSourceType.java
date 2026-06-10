package com.meridian.substrate.engine.ai.retrieval;

/**
 * Classifies the origin of an evidence source record within the domain model.
 *
 * <p><b>Port note:</b> {@link #EVIDENCE_RECORD} is new in substrate — provenance
 * marker for chunks sourced from the {@code evidence} table (the P6 grounding
 * records), distinct from {@link #DOCUMENT_CHUNK} which is reserved for a future
 * document/vector retriever.
 */
public enum EvidenceSourceType {
    CANONICAL_ENTITY,
    DOCUMENT_CHUNK,
    FINDING,
    SYSTEM,
    INTEGRATION,
    ORGANIZATION_MEMBER,
    PROCESS,
    DESIGN_DECISION,
    EVIDENCE_RECORD
}
