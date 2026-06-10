package com.meridian.substrate.engine.ai.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.engine.ai.tools.Tool;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import com.meridian.substrate.common.repo.ProcessRepository;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.Process;
import com.meridian.substrate.common.domain.SystemEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Groups and counts entities by a specified attribute.
 * Supports entity types: systems, findings, processes.
 */
@Component
public class CountByTool implements Tool {

    private final SystemRepository systemRepository;
    private final FindingRepository findingRepository;
    private final ProcessRepository processRepository;
    private final ObjectMapper mapper;

    public CountByTool(SystemRepository systemRepository,
                       FindingRepository findingRepository,
                       ProcessRepository processRepository,
                       ObjectMapper mapper) {
        this.systemRepository = systemRepository;
        this.findingRepository = findingRepository;
        this.processRepository = processRepository;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "count_by";
    }

    @Override
    public String description() {
        return "Group and count entities by a specified attribute. Supports entityType: 'systems', 'findings', 'processes'. groupBy examples: 'severity', 'findingType', 'status', 'classification', 'systemType', 'riskRating'.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode entityType = props.putObject("entityType");
        entityType.put("type", "string");
        entityType.put("description", "Entity type to count: 'systems', 'findings', or 'processes'");

        ObjectNode groupBy = props.putObject("groupBy");
        groupBy.put("type", "string");
        groupBy.put("description", "Attribute to group by (e.g. 'severity', 'findingType', 'status', 'classification', 'systemType', 'riskRating')");

        schema.putArray("required").add("entityType").add("groupBy");

        return schema;
    }

    @Override
    public JsonNode execute(JsonNode arguments, PersonaContext persona) {
        String entityType = textOrNull(arguments, "entityType");
        String groupBy = textOrNull(arguments, "groupBy");

        if (entityType == null || groupBy == null) {
            return errorNode("Both entityType and groupBy are required");
        }

        Map<String, Long> counts;

        switch (entityType.toLowerCase()) {
            case "systems":
                counts = countSystems(groupBy);
                break;
            case "findings":
                counts = countFindings(groupBy);
                break;
            case "processes":
                counts = countProcesses(groupBy);
                break;
            default:
                return errorNode("Unknown entityType: " + entityType
                        + ". Supported: systems, findings, processes");
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("entityType", entityType);
        result.put("groupBy", groupBy);
        ObjectNode countsNode = result.putObject("counts");
        counts.forEach((k, v) -> countsNode.put(k, v));
        result.put("total", counts.values().stream().mapToLong(Long::longValue).sum());

        return result;
    }

    private Map<String, Long> countSystems(String groupBy) {
        List<SystemEntity> systems = systemRepository.findAll();
        Function<SystemEntity, String> classifier;

        switch (groupBy.toLowerCase()) {
            case "status":
                classifier = s -> safe(s.getStatus());
                break;
            case "systemtype":
                classifier = s -> safe(s.getSystemType());
                break;
            case "vendor":
                classifier = s -> safe(s.getVendor());
                break;
            case "businessline":
                classifier = s -> safe(s.getBusinessLine());
                break;
            default:
                classifier = s -> safe(s.getStatus());
                break;
        }
        return systems.stream().collect(Collectors.groupingBy(classifier, LinkedHashMap::new, Collectors.counting()));
    }

    private Map<String, Long> countFindings(String groupBy) {
        List<Finding> findings = findingRepository.findAll();
        Function<Finding, String> classifier;

        switch (groupBy.toLowerCase()) {
            case "severity":
                classifier = f -> f.getSeverity() != null ? f.getSeverity().name() : "UNKNOWN";
                break;
            case "findingtype":
                classifier = f -> f.getFindingType() != null ? f.getFindingType().name() : "UNKNOWN";
                break;
            case "status":
                classifier = f -> f.getStatus() != null ? f.getStatus().name() : "UNKNOWN";
                break;
            default:
                classifier = f -> f.getSeverity() != null ? f.getSeverity().name() : "UNKNOWN";
                break;
        }
        return findings.stream().collect(Collectors.groupingBy(classifier, LinkedHashMap::new, Collectors.counting()));
    }

    private Map<String, Long> countProcesses(String groupBy) {
        List<Process> processes = processRepository.findAll();
        Function<Process, String> classifier;

        switch (groupBy.toLowerCase()) {
            case "classification":
                classifier = p -> p.getClassification() != null ? p.getClassification().name() : "UNKNOWN";
                break;
            case "riskrating":
                classifier = p -> p.getRiskRating() != null ? p.getRiskRating().name() : "UNKNOWN";
                break;
            case "documentationquality":
                classifier = p -> p.getDocumentationQuality() != null ? p.getDocumentationQuality().name() : "UNKNOWN";
                break;
            case "defenseline":
                classifier = p -> p.getDefenseLine() != null ? p.getDefenseLine().name() : "UNKNOWN";
                break;
            default:
                classifier = p -> p.getClassification() != null ? p.getClassification().name() : "UNKNOWN";
                break;
        }
        return processes.stream().collect(Collectors.groupingBy(classifier, LinkedHashMap::new, Collectors.counting()));
    }

    private String safe(String value) {
        return value != null ? value : "UNKNOWN";
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) return null;
        return node.get(field).asText();
    }

    private JsonNode errorNode(String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("error", true);
        node.put("message", message);
        return node;
    }
}
