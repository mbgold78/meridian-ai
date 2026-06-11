package com.meridian.substrate.engine.ai.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.domain.Evidence;
import com.meridian.substrate.common.repo.EvidenceRepository;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.engine.ai.tools.Tool;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Discovery tool over the {@code evidence} table — the P6 grounding records
 * (engine-spec diagnostic.md §7.1; ADR-004 D6).
 *
 * <p>Closes the citation-vocabulary gap verified on disk:
 * {@code get_evidence_for_finding} requires an EXISTING findingId, so against
 * freshly-ingested data the model had no way to learn valid Evidence UUIDs and
 * the differential-grounding filter structurally zeroed ALL CONTENT findings.
 * This tool returns exactly the vocabulary a CONTENT candidate (or generative
 * proposal) must cite: in-domain Evidence row ids with their entity linkage,
 * label, source file, confidence, and extractor.
 *
 * <p>Same data path as {@code RelationalEvidenceRetriever}'s
 * {@code EvidenceQueryType.EVIDENCE} branch ({@code EvidenceRepository.
 * findByEntityId|findByDomainId|findByEntityIdAndEntityType}); results are
 * always constrained to the requested domain — evidence from another domain is
 * never offered as citable (the grounding filter would reject it anyway; the
 * tool must not teach the model ids that cannot survive).
 *
 * <p>// EXTENSION POINT(diagnostic-2): Evidence is document-level ("Committed
 * from systems.csv"); passage-level evidence discovery via the
 * {@code EvidenceChunk} / vector-document retriever seam replaces this
 * repository query when that retriever lands (spec §15.2; port-map §2.3).
 */
@Component
public class FindEvidenceTool implements Tool {

    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 100;

    private final EvidenceRepository evidenceRepository;
    private final ObjectMapper mapper;

    public FindEvidenceTool(EvidenceRepository evidenceRepository, ObjectMapper mapper) {
        this.evidenceRepository = evidenceRepository;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "find_evidence";
    }

    @Override
    public String description() {
        return "Discover citable Evidence records in a domain. Returns evidence ids "
                + "(the only ids valid in a CONTENT finding's or generative proposal's evidenceIds) "
                + "with entityId, entityType, label, sourceFile, confidence, and extractor. "
                + "Optionally narrow by entityId and/or entityType.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode domainId = props.putObject("domainId");
        domainId.put("type", "string");
        domainId.put("description", "The active domain UUID (required; from the request context)");

        ObjectNode entityId = props.putObject("entityId");
        entityId.put("type", "string");
        entityId.put("description", "Optional entity UUID — return only evidence attached to this entity");

        ObjectNode entityType = props.putObject("entityType");
        entityType.put("type", "string");
        entityType.put("description", "Optional entity type filter (e.g. system, process, capability)");

        ObjectNode limit = props.putObject("limit");
        limit.put("type", "integer");
        limit.put("description", "Maximum results to return (default " + DEFAULT_LIMIT
                + ", max " + MAX_LIMIT + ")");

        schema.putArray("required").add("domainId");

        return schema;
    }

    @Override
    public JsonNode execute(JsonNode arguments, PersonaContext persona) {
        String domainIdStr = textOrNull(arguments, "domainId");
        if (domainIdStr == null) {
            return errorNode("domainId is required");
        }
        UUID domainId = parseUuid(domainIdStr);
        if (domainId == null) {
            return errorNode("domainId is not a valid UUID: " + domainIdStr);
        }

        String entityIdStr = textOrNull(arguments, "entityId");
        UUID entityId = null;
        if (entityIdStr != null) {
            entityId = parseUuid(entityIdStr);
            if (entityId == null) {
                return errorNode("entityId is not a valid UUID: " + entityIdStr);
            }
        }
        String entityType = textOrNull(arguments, "entityType");
        int limit = resolveLimit(arguments);

        List<Evidence> rows;
        if (entityId != null && entityType != null) {
            rows = evidenceRepository.findByEntityIdAndEntityType(entityId, entityType);
        } else if (entityId != null) {
            rows = evidenceRepository.findByEntityId(entityId);
        } else {
            rows = evidenceRepository.findByDomainId(domainId);
        }

        ArrayNode results = mapper.createArrayNode();
        for (Evidence e : rows) {
            // In-domain constraint: never offer evidence the grounding filter
            // would reject (entityId lookups are not domain-scoped upstream).
            if (!domainId.equals(e.getDomainId())) {
                continue;
            }
            if (entityId == null && entityType != null
                    && !entityType.equals(e.getEntityType())) {
                continue;
            }
            if (results.size() >= limit) {
                break;
            }
            ObjectNode node = results.addObject();
            node.put("evidenceId", str(e.getId()));
            node.put("entityId", str(e.getEntityId()));
            node.put("entityType", e.getEntityType());
            node.put("label", e.getLabel());
            node.put("sourceFile", e.getSourceFile());
            if (e.getConfidence() != null) {
                node.put("confidence", e.getConfidence());
            } else {
                node.putNull("confidence");
            }
            node.put("extractor", e.getExtractor());
        }
        return results;
    }

    private int resolveLimit(JsonNode arguments) {
        JsonNode node = arguments != null ? arguments.get("limit") : null;
        if (node == null || node.isNull() || !node.canConvertToInt()) {
            return DEFAULT_LIMIT;
        }
        int requested = node.asInt();
        if (requested < 1) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        return node.get(field).asText();
    }

    private UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String str(UUID id) {
        return id == null ? null : id.toString();
    }

    private JsonNode errorNode(String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("error", true);
        node.put("message", message);
        return node;
    }
}
