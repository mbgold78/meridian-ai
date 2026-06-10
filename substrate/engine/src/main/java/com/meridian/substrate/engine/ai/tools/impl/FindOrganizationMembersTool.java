package com.meridian.substrate.engine.ai.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.engine.ai.tools.Tool;
import com.meridian.substrate.common.repo.OrganizationMemberRepository;
import com.meridian.substrate.common.domain.OrganizationMember;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Searches for organization members by name, department, or business line.
 */
@Component
public class FindOrganizationMembersTool implements Tool {

    private final OrganizationMemberRepository memberRepository;
    private final ObjectMapper mapper;

    public FindOrganizationMembersTool(OrganizationMemberRepository memberRepository, ObjectMapper mapper) {
        this.memberRepository = memberRepository;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "find_organization_members";
    }

    @Override
    public String description() {
        return "Search for organization members by name, department, or business line. Returns a list with id, name, title, and department.";
    }

    @Override
    public JsonNode parametersSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description", "Search string to match against member name");

        ObjectNode department = props.putObject("department");
        department.put("type", "string");
        department.put("description", "Filter by department");

        ObjectNode businessLine = props.putObject("businessLine");
        businessLine.put("type", "string");
        businessLine.put("description", "Filter by business line");

        return schema;
    }

    @Override
    public JsonNode execute(JsonNode arguments, PersonaContext persona) {
        String query = textOrNull(arguments, "query");
        String department = textOrNull(arguments, "department");
        String businessLine = textOrNull(arguments, "businessLine");

        List<OrganizationMember> members = memberRepository.findAll();

        if (query != null) {
            String lowerQuery = query.toLowerCase();
            members = members.stream()
                    .filter(m -> m.getName() != null && m.getName().toLowerCase().contains(lowerQuery))
                    .collect(Collectors.toList());
        }
        if (department != null) {
            members = members.stream()
                    .filter(m -> department.equalsIgnoreCase(m.getDepartment()))
                    .collect(Collectors.toList());
        }
        if (businessLine != null) {
            members = members.stream()
                    .filter(m -> businessLine.equalsIgnoreCase(m.getBusinessLine()))
                    .collect(Collectors.toList());
        }

        ArrayNode results = mapper.createArrayNode();
        for (OrganizationMember m : members) {
            ObjectNode node = results.addObject();
            node.put("id", str(m.getId()));
            node.put("name", m.getName());
            node.put("title", m.getTitle());
            node.put("department", m.getDepartment());
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
