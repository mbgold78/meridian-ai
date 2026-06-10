package com.meridian.substrate.engine.ai.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.engine.ai.tools.Tool;
import com.meridian.substrate.common.repo.EntityOverlapRepository;
import com.meridian.substrate.common.repo.RoleOverlapRepository;
import com.meridian.substrate.common.domain.EntityOverlap;
import com.meridian.substrate.common.domain.RoleOverlap;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Searches for entity overlaps and role overlaps, optionally filtered by type or domain.
 */
@Component
public class FindOverlapsTool implements Tool {

    private final EntityOverlapRepository entityOverlapRepository;
    private final RoleOverlapRepository roleOverlapRepository;
    private final ObjectMapper mapper;

    public FindOverlapsTool(EntityOverlapRepository entityOverlapRepository,
                            RoleOverlapRepository roleOverlapRepository,
                            ObjectMapper mapper) {
        this.entityOverlapRepository = entityOverlapRepository;
        this.roleOverlapRepository = roleOverlapRepository;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "find_overlaps";
    }

    @Override
    public String description() {
        return "Search for entity or role overlaps. Use overlapType 'entity' or 'role' to filter. Returns overlap details including source, target, type, and confidence.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode overlapType = props.putObject("overlapType");
        overlapType.put("type", "string");
        overlapType.put("description", "Filter by overlap category: 'entity' or 'role'. Omit for both.");

        ObjectNode domainId = props.putObject("domainId");
        domainId.put("type", "string");
        domainId.put("description", "Filter by domain UUID");

        return schema;
    }

    @Override
    public JsonNode execute(JsonNode arguments, PersonaContext persona) {
        String overlapType = textOrNull(arguments, "overlapType");
        String domainIdStr = textOrNull(arguments, "domainId");

        ArrayNode results = mapper.createArrayNode();

        boolean includeEntity = overlapType == null || "entity".equalsIgnoreCase(overlapType);
        boolean includeRole = overlapType == null || "role".equalsIgnoreCase(overlapType);

        if (includeEntity) {
            List<EntityOverlap> entityOverlaps = domainIdStr != null
                    ? entityOverlapRepository.findByDomainId(UUID.fromString(domainIdStr))
                    : entityOverlapRepository.findAll();

            for (EntityOverlap eo : entityOverlaps) {
                ObjectNode node = results.addObject();
                node.put("category", "entity");
                node.put("id", str(eo.getId()));
                node.put("sourceEntityId", str(eo.getSourceEntityId()));
                node.put("targetEntityId", str(eo.getTargetEntityId()));
                node.put("overlapType", eo.getOverlapType());
                node.put("confidence", eo.getConfidence());
                node.put("aiInferred", eo.getAiInferred());
            }
        }

        if (includeRole) {
            List<RoleOverlap> roleOverlaps = domainIdStr != null
                    ? roleOverlapRepository.findByDomainId(UUID.fromString(domainIdStr))
                    : roleOverlapRepository.findAll();

            for (RoleOverlap ro : roleOverlaps) {
                ObjectNode node = results.addObject();
                node.put("category", "role");
                node.put("id", str(ro.getId()));
                node.put("sourceMemberId", str(ro.getSourceMemberId()));
                node.put("targetMemberId", str(ro.getTargetMemberId()));
                node.put("overlapType", ro.getOverlapType());
                node.put("aiInferred", ro.getAiInferred());
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
