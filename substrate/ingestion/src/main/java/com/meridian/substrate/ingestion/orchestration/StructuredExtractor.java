package com.meridian.substrate.ingestion.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.meridian.substrate.common.ai.CompletionRequest;
import com.meridian.substrate.common.ai.ContentBlock;
import com.meridian.substrate.common.ai.LlmClient;
import com.meridian.substrate.common.ai.ToolCallRequest;
import com.meridian.substrate.common.ai.ToolCallResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Schema-enforced structured extraction: the LLM is asked to submit extracted
 * records by calling a single tool whose input schema requires a
 * {@code records} array — replacing homepoc's regex fence-stripping of
 * free-text completions as the primary parse path (port-map §2.4).
 *
 * <p>Fallback: if the model answers with plain text instead of a tool call,
 * the response is parsed through {@link ResponseParser} (fencing tolerated).
 *
 * <p>Temperature is pinned to 0 — extraction is a deterministic-leaning task.
 */
@Service
public class StructuredExtractor {

    public static final String TOOL_NAME = "emit_extracted_records";

    private static final Logger log = LoggerFactory.getLogger(StructuredExtractor.class);

    private static final String SYSTEM_PROMPT =
            "You are a precise data extraction assistant. Submit the extracted records by calling the "
            + TOOL_NAME + " tool exactly once with the full array. Never invent data not present in the input.";

    private final ObjectProvider<LlmClient> llmClientProvider;
    private final ResponseParser responseParser;
    private final ObjectMapper objectMapper;

    public StructuredExtractor(ObjectProvider<LlmClient> llmClientProvider,
                               ResponseParser responseParser,
                               ObjectMapper objectMapper) {
        this.llmClientProvider = llmClientProvider;
        this.responseParser = responseParser;
        this.objectMapper = objectMapper;
    }

    /**
     * Run one extraction request and return the records array.
     *
     * @throws IllegalStateException if no LLM provider is configured
     * @throws ResponseParser.ResponseParseException if neither a tool call nor
     *         parseable JSON text came back
     */
    public ArrayNode extractRecords(String model, String prompt, int maxTokens) {
        LlmClient client = llmClientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException(
                    "No LLM provider configured (substrate.llm.provider) — cannot run extraction");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "No model configured (substrate.llm.anthropic.model) — cannot run extraction");
        }

        ToolCallRequest request = new ToolCallRequest(
                model,
                SYSTEM_PROMPT,
                List.of(CompletionRequest.Message.user(prompt)),
                List.of(toolDefinition()),
                maxTokens,
                0.0,
                Map.of());

        ToolCallResponse response = client.completeWithTools(request);

        // Primary path: the tool-use block's schema-shaped input.
        for (ContentBlock block : response.content()) {
            if (block.isToolUse() && TOOL_NAME.equals(block.toolName())) {
                Object records = block.toolInput() != null ? block.toolInput().get("records") : null;
                JsonNode node = objectMapper.valueToTree(records);
                if (node != null && node.isArray()) {
                    return (ArrayNode) node;
                }
                log.warn("Tool call '{}' returned no 'records' array; falling back to text parse", TOOL_NAME);
            }
        }

        // Fallback path: plain-text response (fence-stripping parser).
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : response.content()) {
            if (block.isText() && block.text() != null) {
                text.append(block.text());
            }
        }
        if (text.length() == 0) {
            throw new ResponseParser.ResponseParseException(
                    "LLM returned neither a " + TOOL_NAME + " tool call nor text content");
        }
        JsonNode parsed = responseParser.parseRaw(text.toString());
        if (parsed.isArray()) {
            return (ArrayNode) parsed;
        }
        if (parsed.isObject() && parsed.get("records") != null && parsed.get("records").isArray()) {
            return (ArrayNode) parsed.get("records");
        }
        throw new ResponseParser.ResponseParseException(
                "LLM response parsed but is not a JSON array of records");
    }

    private ToolCallRequest.ToolDefinition toolDefinition() {
        return new ToolCallRequest.ToolDefinition(
                TOOL_NAME,
                "Submit the records extracted from the input document as a JSON array.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "records", Map.of(
                                        "type", "array",
                                        "items", Map.of("type", "object"))),
                        "required", List.of("records")));
    }
}
