package com.meridian.substrate.engine.ai.retrieval;

import java.util.Map;

/**
 * Describes a request for evidence from the domain model, specifying the
 * evidence type to query, optional filters (e.g. domainId), and a result limit.
 *
 * @param type    the category of evidence to retrieve
 * @param filters key-value filters to narrow results (e.g. "domainId", "severity")
 * @param limit   maximum number of evidence chunks to return; null means no limit
 */
public record EvidenceQuery(
    EvidenceQueryType type,
    Map<String, Object> filters,
    Integer limit
) {}
