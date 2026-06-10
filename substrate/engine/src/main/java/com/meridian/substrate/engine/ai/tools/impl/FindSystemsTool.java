package com.meridian.substrate.engine.ai.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.engine.ai.tools.Tool;
import com.meridian.substrate.common.repo.SystemRepository;
import com.meridian.substrate.common.domain.SystemEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Searches for technology systems by name, domain, or status.
 */
@Component
public class FindSystemsTool implements Tool {

    private final SystemRepository systemRepository;
    private final ObjectMapper mapper;

    public FindSystemsTool(SystemRepository systemRepository, ObjectMapper mapper) {
        this.systemRepository = systemRepository;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "find_systems";
    }

    @Override
    public String description() {
        return "Search for technology systems by name, domain, or status. Returns a list of matching systems with id, name, vendor, systemType, and status.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description", "Search string to match against system name (contains match)");

        ObjectNode domainId = props.putObject("domainId");
        domainId.put("type", "string");
        domainId.put("description", "Filter by domain UUID");

        ObjectNode status = props.putObject("status");
        status.put("type", "string");
        status.put("description", "Filter by system status (e.g. ACTIVE, DEPRECATED)");

        return schema;
    }

    @Override
    public JsonNode execute(JsonNode arguments, PersonaContext persona) {
        String query = textOrNull(arguments, "query");
        String domainIdStr = textOrNull(arguments, "domainId");
        String status = textOrNull(arguments, "status");

        List<SystemEntity> systems;
        if (domainIdStr != null) {
            systems = systemRepository.findByDomainId(UUID.fromString(domainIdStr));
        } else {
            systems = systemRepository.findAll();
        }

        // Apply in-memory filters
        if (query != null) {
            String lowerQuery = query.toLowerCase();
            systems = systems.stream()
                    .filter(s -> s.getName() != null && s.getName().toLowerCase().contains(lowerQuery))
                    .collect(Collectors.toList());
        }
        if (status != null) {
            systems = systems.stream()
                    .filter(s -> status.equalsIgnoreCase(s.getStatus()))
                    .collect(Collectors.toList());
        }

        ArrayNode results = mapper.createArrayNode();
        for (SystemEntity sys : systems) {
            ObjectNode node = results.addObject();
            node.put("id", str(sys.getId()));
            node.put("name", sys.getName());
            node.put("vendor", sys.getVendor());
            node.put("systemType", sys.getSystemType());
            node.put("status", sys.getStatus());
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
