package com.meridian.substrate.engine.ai.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.engine.ai.tools.Tool;
import com.meridian.substrate.common.repo.DesignDecisionRepository;
import com.meridian.substrate.common.domain.DesignDecision;
import com.meridian.substrate.common.domain.enums.DecisionStatus;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Returns recent design decisions, optionally filtered by status and limited in count.
 */
@Component
public class GetDecisionLogTool implements Tool {

    private static final int DEFAULT_LIMIT = 20;

    private final DesignDecisionRepository decisionRepository;
    private final ObjectMapper mapper;

    public GetDecisionLogTool(DesignDecisionRepository decisionRepository, ObjectMapper mapper) {
        this.decisionRepository = decisionRepository;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "get_decision_log";
    }

    @Override
    public String description() {
        return "Retrieve recent design decisions, optionally filtered by status. Returns decisions ordered by creation date (newest first).";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode status = props.putObject("status");
        status.put("type", "string");
        status.put("description", "Filter by decision status: PROPOSED, APPROVED, REJECTED, SUPERSEDED");

        ObjectNode limit = props.putObject("limit");
        limit.put("type", "integer");
        limit.put("description", "Maximum number of decisions to return (default: 20)");

        return schema;
    }

    @Override
    public JsonNode execute(JsonNode arguments, PersonaContext persona) {
        String statusStr = textOrNull(arguments, "status");
        int limit = DEFAULT_LIMIT;
        if (arguments != null && arguments.has("limit") && !arguments.get("limit").isNull()) {
            limit = arguments.get("limit").asInt(DEFAULT_LIMIT);
        }

        List<DesignDecision> decisions;
        if (statusStr != null) {
            DecisionStatus status = DecisionStatus.valueOf(statusStr);
            decisions = decisionRepository.findAll().stream()
                    .filter(d -> status.equals(d.getStatus()))
                    .collect(Collectors.toList());
        } else {
            decisions = decisionRepository.findAll();
        }

        // Sort by created date descending, limit
        decisions = decisions.stream()
                .sorted(Comparator.comparing(DesignDecision::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limit)
                .collect(Collectors.toList());

        ArrayNode results = mapper.createArrayNode();
        for (DesignDecision d : decisions) {
            ObjectNode node = results.addObject();
            node.put("id", str(d.getId()));
            node.put("title", d.getTitle());
            node.put("decisionType", d.getDecisionType() != null ? d.getDecisionType().name() : null);
            node.put("status", d.getStatus() != null ? d.getStatus().name() : null);
            node.put("description", d.getDescription());
            node.put("rationale", d.getRationale());
            if (d.getCreatedAt() != null) {
                node.put("createdAt", d.getCreatedAt().toString());
            }
        }
        return results;
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        return node.get(field).asText();
    }

    private String str(UUID id) {
        return id == null ? null : id.toString();
    }
}
