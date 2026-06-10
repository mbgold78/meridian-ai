package com.meridian.substrate.engine.ai.retrieval;

import java.util.List;

/**
 * Retrieves evidence chunks from the domain model based on a structured query.
 * Implementations may source evidence from relational stores, vector databases,
 * or document indices.
 */
public interface EvidenceRetriever {

    /**
     * Retrieve evidence chunks matching the given query.
     *
     * @param query the evidence query specifying type, filters, and limit
     * @return ordered list of evidence chunks, highest relevance first
     */
    List<EvidenceChunk> retrieve(EvidenceQuery query);
}
