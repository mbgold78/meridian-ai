package com.meridian.substrate.engine.ai.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.engine.ai.tools.Tool;
import com.meridian.substrate.common.repo.CanonicalEntityRepository;
import com.meridian.substrate.common.repo.IntegrationRepository;
import com.meridian.substrate.common.repo.SystemRepository;
import com.meridian.substrate.common.repo.ProcessSystemLinkRepository;
import com.meridian.substrate.common.domain.CanonicalEntity;
import com.meridian.substrate.common.domain.Integration;
import com.meridian.substrate.common.domain.ProcessSystemLink;
import com.meridian.substrate.common.domain.SystemEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Loads a full system profile including entity counts, integrations,
 * and process links for a given system.
 */
@Component
public class GetSystemProfileTool implements Tool {

    private final SystemRepository systemRepository;
    private final CanonicalEntityRepository entityRepository;
    private final IntegrationRepository integrationRepository;
    private final ProcessSystemLinkRepository linkRepository;
    private final ObjectMapper mapper;

    public GetSystemProfileTool(SystemRepository systemRepository,
                                CanonicalEntityRepository entityRepository,
                                IntegrationRepository integrationRepository,
                                ProcessSystemLinkRepository linkRepository,
                                ObjectMapper mapper) {
        this.systemRepository = systemRepository;
        this.entityRepository = entityRepository;
        this.integrationRepository = integrationRepository;
        this.linkRepository = linkRepository;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "get_system_profile";
    }

    @Override
    public String description() {
        return "Load a full profile for a single system, including entity counts, integrations, and process links. Requires a systemId argument.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode systemId = props.putObject("systemId");
        systemId.put("type", "string");
        systemId.put("description", "The UUID of the system to profile (required)");

        ArrayNode required = schema.putArray("required");
        required.add("systemId");

        return schema;
    }

    @Override
    public JsonNode execute(JsonNode arguments, PersonaContext persona) {
        String systemIdStr = textOrNull(arguments, "systemId");
        if (systemIdStr == null) {
            return errorNode("systemId is required");
        }

        UUID systemId = UUID.fromString(systemIdStr);
        SystemEntity system = systemRepository.findById(systemId).orElse(null);
        if (system == null) {
            return errorNode("System not found: " + systemIdStr);
        }

        // Entity counts
        List<CanonicalEntity> entities = entityRepository.findBySystemId(systemId);

        // Integrations (as source or target)
        List<Integration> integrations = integrationRepository
                .findBySourceSystemIdOrTargetSystemId(systemId, systemId);

        // Process-system links
        List<ProcessSystemLink> links = linkRepository.findBySystemId(systemId);

        ObjectNode result = mapper.createObjectNode();
        result.put("id", str(system.getId()));
        result.put("name", system.getName());
        result.put("vendor", system.getVendor());
        result.put("description", system.getDescription());
        result.put("systemType", system.getSystemType());
        result.put("deployedYear", system.getDeployedYear());
        result.put("status", system.getStatus());
        result.put("businessLine", system.getBusinessLine());
        result.put("entityCount", entities.size());

        // Integration summaries
        ArrayNode integrationArray = result.putArray("integrations");
        for (Integration i : integrations) {
            ObjectNode iNode = integrationArray.addObject();
            iNode.put("id", str(i.getId()));
            iNode.put("name", i.getName());
            iNode.put("sourceSystemId", str(i.getSourceSystemId()));
            iNode.put("targetSystemId", str(i.getTargetSystemId()));
            iNode.put("integrationType", i.getIntegrationType() != null ? i.getIntegrationType().name() : null);
            iNode.put("status", i.getIntegrationStatus() != null ? i.getIntegrationStatus().name() : null);
        }

        // Process-system link summaries
        ArrayNode linkArray = result.putArray("processLinks");
        for (ProcessSystemLink link : links) {
            ObjectNode lNode = linkArray.addObject();
            lNode.put("processId", str(link.getProcessId()));
            lNode.put("linkType", link.getLinkType() != null ? link.getLinkType().name() : null);
            lNode.put("frequency", link.getFrequency());
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
