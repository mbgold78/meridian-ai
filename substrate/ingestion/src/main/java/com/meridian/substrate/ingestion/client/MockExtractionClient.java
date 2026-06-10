package com.meridian.substrate.ingestion.client;

import com.meridian.substrate.common.ai.CompletionChunk;
import com.meridian.substrate.common.ai.CompletionRequest;
import com.meridian.substrate.common.ai.CompletionResponse;
import com.meridian.substrate.common.ai.ContentBlock;
import com.meridian.substrate.common.ai.LlmClient;
import com.meridian.substrate.common.ai.TokenUsage;
import com.meridian.substrate.common.ai.ToolCallRequest;
import com.meridian.substrate.common.ai.ToolCallResponse;
import com.meridian.substrate.ingestion.orchestration.StructuredExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic mock LLM provider for the ingestion module, active under
 * {@code substrate.llm.provider=mock} (fixture seam S8 — same activation key
 * as the engine's MockAnthropicClient, which is not on the ingestion
 * classpath: ingestion never depends on engine, port-map §1).
 *
 * <p>Purpose: a USER-RUNNABLE convert path with no ANTHROPIC_API_KEY — it
 * exercises every pipeline mechanism (routing, chunking, prompt loading,
 * tool-schema extraction, output/manifest writing, staging, commit) with
 * deterministic output. It does NOT perform semantic extraction: it echoes the
 * structured input rows embedded in the prompt.
 *
 * <p>Parsing contract (mirrors what the readers emit into prompts):
 * <ul>
 *   <li>CSV-derived prompts: {@code --- Row N ---} blocks of
 *       {@code header: value} lines → one record per row, snake_case headers
 *       camelCased;</li>
 *   <li>text/workflow prompts (no row markers): one record from the first
 *       {@code # Heading} (→ {@code name}) plus any {@code key: value}
 *       metadata lines.</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "substrate.llm.provider", havingValue = "mock")
public class MockExtractionClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(MockExtractionClient.class);

    private static final String ROW_MARKER = "--- Row ";
    private static final Pattern INPUT_SECTION =
            Pattern.compile("## Input (?:Data|Text)\\s*\\n(.*?)(?:\\n## |$)", Pattern.DOTALL);
    private static final Pattern KEY_VALUE_LINE =
            Pattern.compile("^([A-Za-z][A-Za-z0-9_ ]{0,60}?)\\s*:\\s*(.+)$");
    private static final Pattern HEADING_LINE = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);

    public MockExtractionClient() {
        log.info("MockExtractionClient active (substrate.llm.provider=mock) — "
                + "deterministic row-echo extraction, NO real LLM calls");
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        return new CompletionResponse(
                "mock-" + UUID.randomUUID(), "[]", request.model(), "end_turn", new TokenUsage(0, 0));
    }

    @Override
    public Flux<CompletionChunk> completeStream(CompletionRequest request) {
        return Flux.empty();
    }

    @Override
    public ToolCallResponse completeWithTools(ToolCallRequest request) {
        String prompt = request.messages().isEmpty()
                ? ""
                : String.valueOf(request.messages().get(request.messages().size() - 1).content());
        List<Map<String, Object>> records = extractRecords(prompt);
        return new ToolCallResponse(
                "mock-" + UUID.randomUUID(),
                request.model(),
                "tool_use",
                List.of(ContentBlock.toolUse(
                        "toolu_mock_1",
                        StructuredExtractor.TOOL_NAME,
                        Map.of("records", records))),
                new TokenUsage(0, 0));
    }

    private List<Map<String, Object>> extractRecords(String prompt) {
        String input = extractInputSection(prompt);
        if (input.contains(ROW_MARKER)) {
            return parseRowBlocks(input);
        }
        Map<String, Object> single = parseKeyValueDocument(input);
        return single.isEmpty() ? List.of() : List.of(single);
    }

    /** The {@code {{input_data}}} substitution sits directly under the Input section header. */
    private String extractInputSection(String prompt) {
        Matcher matcher = INPUT_SECTION.matcher(prompt);
        return matcher.find() ? matcher.group(1) : prompt;
    }

    private List<Map<String, Object>> parseRowBlocks(String input) {
        List<Map<String, Object>> records = new ArrayList<>();
        String[] blocks = input.split(Pattern.quote(ROW_MARKER));
        for (int i = 1; i < blocks.length; i++) { // blocks[0] is the pre-marker preamble
            Map<String, Object> record = new LinkedHashMap<>();
            for (String line : blocks[i].split("\n")) {
                Matcher kv = KEY_VALUE_LINE.matcher(line.trim());
                if (kv.matches()) {
                    String value = kv.group(2).trim();
                    if (!value.isEmpty()) {
                        record.put(camelCase(kv.group(1)), value);
                    }
                }
            }
            if (!record.isEmpty()) {
                records.add(record);
            }
        }
        return records;
    }

    private Map<String, Object> parseKeyValueDocument(String input) {
        Map<String, Object> record = new LinkedHashMap<>();
        Matcher heading = HEADING_LINE.matcher(input);
        if (heading.find()) {
            record.put("name", heading.group(1).trim());
        }
        for (String line : input.split("\n")) {
            Matcher kv = KEY_VALUE_LINE.matcher(line.trim());
            if (kv.matches()) {
                String value = kv.group(2).trim();
                if (!value.isEmpty()) {
                    record.putIfAbsent(camelCase(kv.group(1)), value);
                }
            }
        }
        return record;
    }

    private static String camelCase(String header) {
        String[] parts = header.trim().toLowerCase(Locale.ROOT).split("[_ ]+");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
            }
        }
        return sb.toString();
    }
}
