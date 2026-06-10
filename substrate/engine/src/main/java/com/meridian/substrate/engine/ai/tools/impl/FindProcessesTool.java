package com.meridian.substrate.engine.ai.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.engine.ai.tools.Tool;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.domain.Process;
import com.meridian.substrate.common.domain.enums.ManualProcessClassification;
import com.meridian.substrate.common.domain.enums.RiskRating;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Searches for processes by classification, risk rating, or domain.
 */
@Component
public class FindProcessesTool implements Tool {

    private final ProcessRepository processRepository;
    private final ObjectMapper mapper;

    public FindProcessesTool(ProcessRepository processRepository, ObjectMapper mapper) {
        this.processRepository = processRepository;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "find_processes";
    }

    @Override
    public String description() {
        return "Search for processes by classification, riskRating, or domain. Returns a list with id, name, classification, riskRating, primaryOwner, and documentationQuality.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode classification = props.putObject("classification");
        classification.put("type", "string");
        classification.put("description", "Filter by classification: SIGN_OFF, RECONCILIATION, BRIDGE, COMPENSATING_CONTROL, SHADOW_WORKFLOW, TRIBAL_KNOWLEDGE, OTHER");

        ObjectNode riskRating = props.putObject("riskRating");
        riskRating.put("type", "string");
        riskRating.put("description", "Filter by risk rating: LOW, MEDIUM, HIGH, CRITICAL");

        ObjectNode domainId = props.putObject("domainId");
        domainId.put("type", "string");
        domainId.put("description", "Filter by domain UUID");

        return schema;
    }

    @Override
    public JsonNode execute(JsonNode arguments, PersonaContext persona) {
        String classificationStr = textOrNull(arguments, "classification");
        String riskRatingStr = textOrNull(arguments, "riskRating");
        String domainIdStr = textOrNull(arguments, "domainId");

        List<Process> processes;

        if (domainIdStr != null) {
            processes = processRepository.findByDomainId(UUID.fromString(domainIdStr));
        } else if (classificationStr != null) {
            processes = processRepository.findByClassification(
                    ManualProcessClassification.valueOf(classificationStr));
        } else {
            processes = processRepository.findAll();
        }

        if (classificationStr != null && domainIdStr != null) {
            ManualProcessClassification cls = ManualProcessClassification.valueOf(classificationStr);
            processes = processes.stream()
                    .filter(p -> cls.equals(p.getClassification()))
                    .collect(Collectors.toList());
        }
        if (riskRatingStr != null) {
            RiskRating rating = RiskRating.valueOf(riskRatingStr);
            processes = processes.stream()
                    .filter(p -> rating.equals(p.getRiskRating()))
                    .collect(Collectors.toList());
        }

        ArrayNode results = mapper.createArrayNode();
        for (Process p : processes) {
            ObjectNode node = results.addObject();
            node.put("id", str(p.getId()));
            node.put("name", p.getName());
            node.put("classification", p.getClassification() != null ? p.getClassification().name() : null);
            node.put("riskRating", p.getRiskRating() != null ? p.getRiskRating().name() : null);
            node.put("primaryOwner", p.getPrimaryOwner());
            node.put("documentationQuality",
                    p.getDocumentationQuality() != null ? p.getDocumentationQuality().name() : null);
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
