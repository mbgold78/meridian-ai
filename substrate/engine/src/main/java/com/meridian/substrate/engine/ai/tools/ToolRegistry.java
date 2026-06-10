package com.meridian.substrate.engine.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.ai.ToolCallRequest;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * Central registry that auto-discovers all {@link Tool} beans and provides
 * persona-scoped access to tool definitions and instances.
 *
 * <p><b>Port note:</b> the persona→tool allowlist is no longer a compile-time
 * static map (homepoc) — it lives in {@link ToolExecutionProperties}
 * ({@code substrate.engine.tools.persona-access.*}) with built-in defaults that
 * replicate homepoc behavior exactly when no configuration is present.
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> toolsByName;
    private final ToolExecutionProperties executionProperties;
    private final ObjectMapper mapper;

    public ToolRegistry(List<Tool> tools,
                        ToolExecutionProperties executionProperties,
                        ObjectMapper mapper) {
        this.toolsByName = tools.stream()
                .collect(Collectors.toMap(Tool::name, t -> t, (a, b) -> a, LinkedHashMap::new));
        this.executionProperties = executionProperties;
        this.mapper = mapper;
    }

    /**
     * Retrieve a tool by name.
     *
     * @param name the tool name
     * @return the tool instance
     * @throws NoSuchElementException if no tool exists with that name
     */
    public Tool getTool(String name) {
        Tool tool = toolsByName.get(name);
        if (tool == null) {
            throw new NoSuchElementException("Unknown tool: " + name);
        }
        return tool;
    }

    /**
     * Return all tools accessible to the given persona.
     *
     * @param persona the current persona context
     * @return filtered list of tools
     */
    public List<Tool> getToolsForPersona(PersonaContext persona) {
        return toolsByName.values().stream()
                .filter(t -> isAllowed(persona, t.name()))
                .collect(Collectors.toList());
    }

    /**
     * Return {@link ToolCallRequest.ToolDefinition} records suitable for sending to the LLM,
     * filtered by persona scope.
     *
     * @param persona the current persona context
     * @return tool definitions for the LLM
     */
    public List<ToolCallRequest.ToolDefinition> getToolDefinitionsForPersona(PersonaContext persona) {
        return getToolsForPersona(persona).stream()
                .map(this::toDefinition)
                .collect(Collectors.toList());
    }

    /**
     * Check whether a persona is allowed to invoke a given tool.
     *
     * @param persona  the persona context
     * @param toolName the tool name
     * @return true if access is permitted
     */
    public boolean isAllowed(PersonaContext persona, String toolName) {
        return executionProperties.isAllowed(persona.getPersonaLevel(), toolName);
    }

    private ToolCallRequest.ToolDefinition toDefinition(Tool tool) {
        // Convert the JsonNode parameter schema to a Map for the ToolDefinition record
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = mapper.convertValue(tool.parametersSchema(), Map.class);
        return new ToolCallRequest.ToolDefinition(tool.name(), tool.description(), schema);
    }
}
