package com.meridian.substrate.engine.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.meridian.substrate.engine.ai.persona.PersonaContext;

/**
 * Contract for an AI-callable tool within the substrate engine.
 * Each implementation exposes a name, description, parameter schema (for the LLM),
 * and an execute method that returns structured JSON results.
 */
public interface Tool {

    /** Unique tool name used in LLM tool-call requests (snake_case). */
    String name();

    /** Human-readable description sent to the LLM so it knows when to invoke this tool. */
    String description();

    /** JSON Schema describing the accepted arguments ({@code type: "object", properties: ...}). */
    JsonNode parametersSchema();

    /**
     * Execute the tool with the given arguments in the context of the specified persona.
     *
     * @param arguments JSON arguments provided by the LLM
     * @param persona   the current request persona
     * @return structured JSON result
     */
    JsonNode execute(JsonNode arguments, PersonaContext persona);
}
