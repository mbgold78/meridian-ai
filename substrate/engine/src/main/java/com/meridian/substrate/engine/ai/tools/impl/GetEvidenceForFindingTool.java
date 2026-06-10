package com.meridian.substrate.engine.ai.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.engine.ai.tools.Tool;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.domain.Finding;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Loads a single finding with its full evidence details.
 */
@Component
public class GetEvidenceForFindingTool implements Tool {

    private final FindingRepository findingRepository;
    private final ObjectMapper mapper;

    public GetEvidenceForFindingTool(FindingRepository findingRepository, ObjectMapper mapper) {
        this.findingRepository = findingRepository;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "get_evidence_for_finding";
    }

    @Override
    public String description() {
        return "Load a specific finding and its evidence details. Requires a findingId argument. Returns the full finding with evidence, affected systems, and recommendations.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode findingId = props.putObject("findingId");
        findingId.put("type", "string");
        findingId.put("description", "The UUID of the finding to retrieve (required)");

        schema.putArray("required").add("findingId");

        return schema;
    }

    @Override
    public JsonNode execute(JsonNode arguments, PersonaContext persona) {
        String findingIdStr = textOrNull(arguments, "findingId");
        if (findingIdStr == null) {
            return errorNode("findingId is required");
        }

        UUID findingId = UUID.fromString(findingIdStr);
        Finding finding = findingRepository.findById(findingId).orElse(null);
        if (finding == null) {
            return errorNode("Finding not found: " + findingIdStr);
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("id", str(finding.getId()));
        result.put("title", finding.getTitle());
        result.put("findingType", finding.getFindingType() != null ? finding.getFindingType().name() : null);
        result.put("severity", finding.getSeverity() != null ? finding.getSeverity().name() : null);
        result.put("status", finding.getStatus() != null ? finding.getStatus().name() : null);
        result.put("description", finding.getDescription());
        result.put("recommendation", finding.getRecommendation());
        result.put("aiGenerated", finding.getAiGenerated());

        if (finding.getEvidence() != null) {
            result.set("evidence", finding.getEvidence());
        }
        if (finding.getAffectedSystemIds() != null) {
            result.set("affectedSystemIds", finding.getAffectedSystemIds());
        }
        if (finding.getAffectedProcessIds() != null) {
            result.set("affectedProcessIds", finding.getAffectedProcessIds());
        }
        if (finding.getMetadata() != null) {
            result.set("metadata", finding.getMetadata());
        }

        return result;
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        return node.get(field).asText();
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
