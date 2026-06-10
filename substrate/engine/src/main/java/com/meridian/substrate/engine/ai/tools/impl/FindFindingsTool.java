package com.meridian.substrate.engine.ai.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.engine.ai.tools.Tool;
import com.meridian.substrate.common.repo.FindingRepository;
import com.meridian.substrate.common.domain.Finding;
import com.meridian.substrate.common.domain.enums.FindingType;
import com.meridian.substrate.common.domain.enums.Severity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Searches for findings by severity, finding type, or domain.
 * Executive persona is restricted to HIGH+ severity findings only.
 */
@Component
public class FindFindingsTool implements Tool {

    private static final Set<Severity> HIGH_PLUS = Set.of(Severity.HIGH, Severity.CRITICAL);

    private final FindingRepository findingRepository;
    private final ObjectMapper mapper;

    public FindFindingsTool(FindingRepository findingRepository, ObjectMapper mapper) {
        this.findingRepository = findingRepository;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "find_findings";
    }

    @Override
    public String description() {
        return "Search for findings by severity, findingType, or domain. Returns a list of findings with id, title, findingType, severity, status, and description.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode severity = props.putObject("severity");
        severity.put("type", "string");
        severity.put("description", "Filter by severity: LOW, MEDIUM, HIGH, CRITICAL");

        ObjectNode findingType = props.putObject("findingType");
        findingType.put("type", "string");
        findingType.put("description", "Filter by finding type");

        ObjectNode domainId = props.putObject("domainId");
        domainId.put("type", "string");
        domainId.put("description", "Filter by domain UUID");

        return schema;
    }

    @Override
    public JsonNode execute(JsonNode arguments, PersonaContext persona) {
        String severityStr = textOrNull(arguments, "severity");
        String findingTypeStr = textOrNull(arguments, "findingType");
        String domainIdStr = textOrNull(arguments, "domainId");

        List<Finding> findings;

        if (domainIdStr != null && findingTypeStr != null) {
            findings = findingRepository.findByDomainIdAndFindingType(
                    UUID.fromString(domainIdStr), FindingType.valueOf(findingTypeStr));
        } else if (findingTypeStr != null) {
            findings = findingRepository.findByFindingType(FindingType.valueOf(findingTypeStr));
        } else if (domainIdStr != null) {
            findings = findingRepository.findByDomainId(UUID.fromString(domainIdStr));
        } else {
            findings = findingRepository.findAll();
        }

        // Filter by severity if specified
        if (severityStr != null) {
            Severity sev = Severity.valueOf(severityStr);
            findings = findings.stream()
                    .filter(f -> sev.equals(f.getSeverity()))
                    .collect(Collectors.toList());
        }

        // Executive persona: restrict to HIGH+ only
        if ("executive".equals(persona.getPersonaLevel())) {
            findings = findings.stream()
                    .filter(f -> f.getSeverity() != null && HIGH_PLUS.contains(f.getSeverity()))
                    .collect(Collectors.toList());
        }

        ArrayNode results = mapper.createArrayNode();
        for (Finding f : findings) {
            ObjectNode node = results.addObject();
            node.put("id", str(f.getId()));
            node.put("title", f.getTitle());
            node.put("findingType", f.getFindingType() != null ? f.getFindingType().name() : null);
            node.put("severity", f.getSeverity() != null ? f.getSeverity().name() : null);
            node.put("status", f.getStatus() != null ? f.getStatus().name() : null);
            node.put("description", f.getDescription());
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
