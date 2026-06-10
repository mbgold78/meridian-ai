package com.meridian.substrate.engine.ai.retrieval;

/**
 * The category of domain evidence to query. Each type maps to one or more
 * repository sources in the relational store.
 *
 * <p><b>Port note:</b> {@link #EVIDENCE} is new in substrate — it queries the
 * {@code evidence} table itself (the grounding records), enabling mode services
 * to assemble their citation pools through the retriever seam instead of
 * calling {@code EvidenceRepository} directly (port-map §2.3
 * RelationalEvidenceRetriever wiring decision).
 */
public enum EvidenceQueryType {
    SYSTEMS,
    ENTITIES,
    FINDINGS,
    INTEGRATIONS,
    OVERLAPS,
    ORGANIZATION,
    PROCESSES,
    DECISIONS,
    EVIDENCE
}
