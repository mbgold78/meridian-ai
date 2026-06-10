package com.meridian.substrate.engine.ai.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.engine.ai.tools.Tool;
import com.meridian.substrate.common.repo.CanonicalEntityRepository;
import com.meridian.substrate.common.domain.CanonicalEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Searches for canonical business entities by name, system, or entity type.
 */
@Component
public class FindCanonicalEntitiesTool implements Tool {

    private final CanonicalEntityRepository entityRepository;
    private final ObjectMapper mapper;

    public FindCanonicalEntitiesTool(CanonicalEntityRepository entityRepository, ObjectMapper mapper) {
        this.entityRepository = entityRepository;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "find_canonical_entities";
    }

    @Override
    public String description() {
        return "Search for canonical business entities by name, system, or entity type. Returns a list of entities with id, name, entityType, and systemId.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description", "Search string to match against entity name");

        ObjectNode systemId = props.putObject("systemId");
        systemId.put("type", "string");
        systemId.put("description", "Filter by system UUID");

        ObjectNode entityType = props.putObject("entityType");
        entityType.put("type", "string");
        entityType.put("description", "Filter by entity type");

        return schema;
    }

    @Override
    public JsonNode execute(JsonNode arguments, PersonaContext persona) {
        String query = textOrNull(arguments, "query");
        String systemIdStr = textOrNull(arguments, "systemId");
        String entityType = textOrNull(arguments, "entityType");

        List<CanonicalEntity> entities;
        if (systemIdStr != null) {
            entities = entityRepository.findBySystemId(UUID.fromString(systemIdStr));
        } else {
            entities = entityRepository.findAll();
        }

        if (query != null) {
            String lowerQuery = query.toLowerCase();
            entities = entities.stream()
                    .filter(e -> e.getName() != null && e.getName().toLowerCase().contains(lowerQuery))
                    .collect(Collectors.toList());
        }
        if (entityType != null) {
            entities = entities.stream()
                    .filter(e -> entityType.equalsIgnoreCase(e.getEntityType()))
                    .collect(Collectors.toList());
        }

        ArrayNode results = mapper.createArrayNode();
        for (CanonicalEntity ce : entities) {
            ObjectNode node = results.addObject();
            node.put("id", str(ce.getId()));
            node.put("name", ce.getName());
            node.put("entityType", ce.getEntityType());
            node.put("systemId", str(ce.getSystemId()));
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
