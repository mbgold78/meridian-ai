package com.meridian.substrate.ingestion.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses LLM text responses into structured Java objects, stripping markdown
 * code fencing that models sometimes include despite being told not to.
 *
 * <p>The PRIMARY extraction path in substrate is schema-enforced structured
 * output via tool use ({@link StructuredExtractor}); this parser remains as the
 * fallback for providers/responses that return plain text (port-map §2.4).
 */
@Service
public class ResponseParser {

    private static final Logger log = LoggerFactory.getLogger(ResponseParser.class);
    private static final Pattern CODE_FENCE_PATTERN =
            Pattern.compile("```(?:json)?\\s*\\n?(.*?)\\n?```", Pattern.DOTALL);

    private final ObjectMapper objectMapper;

    public ResponseParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parse the LLM response as a JSON array and deserialize each element
     * into the given type.
     *
     * @param llmResponse  raw text from the LLM
     * @param elementType  class of the list elements
     * @return list of deserialized objects
     */
    public <T> List<T> parseList(String llmResponse, Class<T> elementType) {
        String cleaned = stripFencing(llmResponse).trim();
        try {
            return objectMapper.readValue(cleaned,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (IOException e) {
            log.error("Failed to parse LLM response as List<{}>: {}", elementType.getSimpleName(), e.getMessage());
            throw new ResponseParseException("Failed to parse LLM response as JSON array", e);
        }
    }

    /**
     * Parse the LLM response into a raw {@link JsonNode}.
     *
     * @param llmResponse raw text from the LLM
     * @return parsed JSON tree
     */
    public JsonNode parseRaw(String llmResponse) {
        String cleaned = stripFencing(llmResponse).trim();
        try {
            return objectMapper.readTree(cleaned);
        } catch (IOException e) {
            log.error("Failed to parse LLM response as JSON: {}", e.getMessage());
            throw new ResponseParseException("Failed to parse LLM response as JSON", e);
        }
    }

    /**
     * Strip markdown code fencing ({@code ```json ... ```}) if present.
     */
    private String stripFencing(String text) {
        if (text == null) {
            return "";
        }
        Matcher matcher = CODE_FENCE_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return text;
    }

    /** Thrown when an LLM response cannot be parsed as the expected JSON shape. */
    public static class ResponseParseException extends RuntimeException {
        public ResponseParseException(String message, Throwable cause) {
            super(message, cause);
        }

        public ResponseParseException(String message) {
            super(message);
        }
    }
}
