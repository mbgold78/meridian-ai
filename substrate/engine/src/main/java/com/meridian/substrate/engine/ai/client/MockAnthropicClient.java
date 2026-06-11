package com.meridian.substrate.engine.ai.client;

import com.meridian.substrate.common.ai.CompletionChunk;
import com.meridian.substrate.common.ai.CompletionRequest;
import com.meridian.substrate.common.ai.CompletionResponse;
import com.meridian.substrate.common.ai.ContentBlock;
import com.meridian.substrate.common.ai.LlmClient;
import com.meridian.substrate.common.ai.TokenUsage;
import com.meridian.substrate.common.ai.ToolCallRequest;
import com.meridian.substrate.common.ai.ToolCallResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mock implementation of {@link LlmClient} for testing and offline development.
 * Activated when {@code substrate.llm.provider=mock} (fixture seam S8 — kept as
 * the test seam; the real path is {@code SdkAnthropicClient} + ANTHROPIC_API_KEY).
 *
 * <p><b>Opt-in diagnostic script (engine-spec diagnostic.md build step 4
 * mock-provider acceptance):</b> {@code substrate.llm.mock.diagnostic-script}
 * — default EMPTY, in which case behavior is byte-identical to the pinned
 * Phase-2A mock (prose responses; pinned-surface discipline, spec
 * non-negotiable #1). When set, {@link #completeWithTools} answers VALIDATIVE
 * prompts (detected by the prompt's own "JSON array of finding objects"
 * marker) with a deterministic candidate array instead:
 * <ul>
 *   <li>{@code structural} — one well-formed STRUCTURAL candidate (no
 *       evidence required) that survives the CandidateFilter and commits;</li>
 *   <li>{@code ungrounded-content} — one CONTENT candidate citing a
 *       nonexistent evidence id, which the differential-grounding filter MUST
 *       drop (the all-filtered lane);</li>
 *   <li>{@code grounded-content} — the §11.3 scripted tool-call responder
 *       (build step 7): on the first validative turn it issues a REAL
 *       {@code find_evidence} tool call (domainId parsed from the prompt);
 *       once the conversation carries the tool_result, it emits THREE
 *       candidates — (b) one grounded CONTENT candidate citing real Evidence
 *       ids read from the tool result (commits), (c) one ungrounded CONTENT
 *       candidate that MUST be filtered, (d) one invalid-severity candidate
 *       that MUST be filtered. An empty tool result (empty domain) yields
 *       only (c)+(d) — the zero-yield lane.</li>
 * </ul>
 * This is acceptance/CLI infrastructure for the §10.3 runner, the §10.2
 * exit-code matrix, the §11.2 dev-shared Glacier E2E, and the §11.3
 * Testcontainers gate.
 */
@Service
@ConditionalOnProperty(name = "substrate.llm.provider", havingValue = "mock")
public class MockAnthropicClient implements LlmClient {

    public static final String SCRIPT_STRUCTURAL = "structural";
    public static final String SCRIPT_UNGROUNDED_CONTENT = "ungrounded-content";
    public static final String SCRIPT_GROUNDED_CONTENT = "grounded-content";

    private static final Logger log = LoggerFactory.getLogger(MockAnthropicClient.class);

    private static final String MOCK_MODEL = "mock-model";
    private static final String MOCK_RESPONSE = "This is a mock response from MERIDIAN.";

    /** The validative prompt's own instruction line — a stable marker (spec §7.1 prompt). */
    private static final String VALIDATIVE_PROMPT_MARKER = "JSON array of finding objects";

    /** The validative prompt names its domain inline ("… entities in domain <uuid> …"). */
    private static final Pattern DOMAIN_ID_PATTERN = Pattern.compile(
            "in domain ([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    /** find_evidence tool-result rows carry {"evidenceId":"<uuid>", …}. */
    private static final Pattern EVIDENCE_ID_PATTERN = Pattern.compile(
            "\"evidenceId\"\\s*:\\s*\"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\"");

    private static final String STRUCTURAL_CANDIDATES = """
            [{"type":"STRUCTURAL","severity":"MEDIUM","title":"Scripted mock structural observation",\
            "findingType":"COVERAGE_GAP",\
            "description":"Deterministic mock-provider observation: this scope was audited by the scripted mock. Review coverage manually.",\
            "suggestedAction":"Review this scope with a human analyst.","evidenceIds":[]}]""";

    private static final String UNGROUNDED_CONTENT_CANDIDATES = """
            [{"type":"CONTENT","severity":"HIGH","title":"Scripted mock ungrounded content claim",\
            "description":"Deterministic mock-provider CONTENT claim citing a nonexistent evidence id - the grounding filter must drop it.",\
            "suggestedAction":"This candidate must be filtered, never committed.",\
            "evidenceIds":["00000000-0000-0000-0000-000000000000"]}]""";

    private volatile String diagnosticScript;

    public MockAnthropicClient(
            @org.springframework.beans.factory.annotation.Value(
                    "${substrate.llm.mock.diagnostic-script:}") String diagnosticScript) {
        this.diagnosticScript = diagnosticScript == null ? "" : diagnosticScript.trim();
    }

    /** Test/acceptance lever — lets a single context flip scripts between cases. */
    public void setDiagnosticScript(String diagnosticScript) {
        this.diagnosticScript = diagnosticScript == null ? "" : diagnosticScript.trim();
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        log.debug("MockAnthropicClient.complete() [model={}, messages={}]",
                request.model(), request.messages().size());

        return new CompletionResponse(
                generateId(),
                MOCK_RESPONSE,
                MOCK_MODEL,
                "end_turn",
                new TokenUsage(25, 12)
        );
    }

    @Override
    public Flux<CompletionChunk> completeStream(CompletionRequest request) {
        log.debug("MockAnthropicClient.completeStream() [model={}, messages={}]",
                request.model(), request.messages().size());

        String id = generateId();

        return Flux.just(
                new CompletionChunk(id, "This is a mock ", null, null),
                new CompletionChunk(id, "streaming response ", null, null),
                new CompletionChunk(id, "from MERIDIAN.", "end_turn", new TokenUsage(25, 18))
        ).delayElements(Duration.ofMillis(100));
    }

    @Override
    public ToolCallResponse completeWithTools(ToolCallRequest request) {
        log.debug("MockAnthropicClient.completeWithTools() [model={}, tools={}, messages={}]",
                request.model(), request.tools().size(), request.messages().size());

        ToolCallResponse scripted = scriptedDiagnosticResponse(request);
        if (scripted != null) {
            return scripted;
        }

        return new ToolCallResponse(
                generateId(),
                MOCK_MODEL,
                "end_turn",
                List.of(ContentBlock.text(
                        "Mock tool-calling response from MERIDIAN. " +
                        "No tools were actually invoked."
                )),
                new TokenUsage(40, 20)
        );
    }

    /** Null unless the opt-in script is armed AND this is a validative prompt. */
    private ToolCallResponse scriptedDiagnosticResponse(ToolCallRequest request) {
        if (diagnosticScript.isEmpty() || !isValidativePrompt(request)) {
            return null;
        }
        return switch (diagnosticScript) {
            case SCRIPT_STRUCTURAL -> textResponse(STRUCTURAL_CANDIDATES);
            case SCRIPT_UNGROUNDED_CONTENT -> textResponse(UNGROUNDED_CONTENT_CANDIDATES);
            case SCRIPT_GROUNDED_CONTENT -> groundedContentResponse(request);
            default -> {
                log.warn("Unknown substrate.llm.mock.diagnostic-script '{}' — ignoring (allowed: {}, {}, {})",
                        diagnosticScript, SCRIPT_STRUCTURAL, SCRIPT_UNGROUNDED_CONTENT,
                        SCRIPT_GROUNDED_CONTENT);
                yield null;
            }
        };
    }

    // ------------------------------------------------- grounded-content script

    /**
     * The §11.3 two-turn dance over the NATIVE tool protocol: first turn →
     * one {@code find_evidence} tool_use block (the orchestrator dispatches
     * the REAL tool); follow-up turn (tool_result present) → the
     * grounded/ungrounded/invalid-severity candidate array, citing evidence
     * ids actually returned by the tool — the deterministic proof that the
     * D6 citation-vocabulary fix works end-to-end.
     */
    private ToolCallResponse groundedContentResponse(ToolCallRequest request) {
        String toolResultJson = lastToolResultText(request);
        if (toolResultJson == null) {
            String domainId = extractDomainId(request);
            Map<String, Object> input = new LinkedHashMap<>();
            if (domainId != null) {
                input.put("domainId", domainId);
            }
            input.put("limit", 25);
            return new ToolCallResponse(
                    generateId(),
                    MOCK_MODEL,
                    "tool_use",
                    List.of(ContentBlock.toolUse("toolu-mock-find-evidence", "find_evidence", input)),
                    new TokenUsage(40, 20));
        }
        return textResponse(groundedCandidateArray(extractEvidenceIds(toolResultJson)));
    }

    /** Candidate array per §11.3 (b)/(c)/(d); grounded candidate only when real ids exist. */
    private static String groundedCandidateArray(List<String> evidenceIds) {
        StringBuilder sb = new StringBuilder("[");
        if (!evidenceIds.isEmpty()) {
            StringBuilder cited = new StringBuilder();
            for (int i = 0; i < evidenceIds.size() && i < 2; i++) {
                if (i > 0) {
                    cited.append(',');
                }
                cited.append('"').append(evidenceIds.get(i)).append('"');
            }
            sb.append("{\"type\":\"CONTENT\",\"severity\":\"HIGH\",")
                    .append("\"title\":\"Scripted grounded content claim\",")
                    .append("\"findingType\":\"DATA_QUALITY\",")
                    .append("\"description\":\"Deterministic mock CONTENT claim grounded in evidence discovered via find_evidence.\",")
                    .append("\"suggestedAction\":\"Verify the cited evidence semantically and curate.\",")
                    .append("\"evidenceIds\":[").append(cited).append("]},");
        }
        sb.append("{\"type\":\"CONTENT\",\"severity\":\"HIGH\",")
                .append("\"title\":\"Scripted mock ungrounded content claim\",")
                .append("\"description\":\"Deterministic mock CONTENT claim citing a nonexistent evidence id - the grounding filter must drop it.\",")
                .append("\"suggestedAction\":\"This candidate must be filtered, never committed.\",")
                .append("\"evidenceIds\":[\"00000000-0000-0000-0000-000000000000\"]},");
        sb.append("{\"type\":\"STRUCTURAL\",\"severity\":\"URGENT\",")
                .append("\"title\":\"Scripted mock invalid severity\",")
                .append("\"description\":\"Deterministic mock candidate with a hallucinated severity - the allowlist must drop it.\",")
                .append("\"suggestedAction\":\"This candidate must be filtered, never committed.\",")
                .append("\"evidenceIds\":[]}]");
        return sb.toString();
    }

    /** Text of the LAST tool_result block in the conversation, else null (first turn). */
    private static String lastToolResultText(ToolCallRequest request) {
        if (request.messages() == null) {
            return null;
        }
        String last = null;
        for (CompletionRequest.Message message : request.messages()) {
            if (message.blocks() == null) {
                continue;
            }
            for (ContentBlock block : message.blocks()) {
                if (block.isToolResult() && block.text() != null) {
                    last = block.text();
                }
            }
        }
        return last;
    }

    /** The validative prompt names its domain: "… entities in domain &lt;uuid&gt; …". */
    private static String extractDomainId(ToolCallRequest request) {
        if (request.messages() == null) {
            return null;
        }
        for (CompletionRequest.Message message : request.messages()) {
            if (!"user".equals(message.role()) || message.content() == null) {
                continue;
            }
            Matcher matcher = DOMAIN_ID_PATTERN.matcher(message.content());
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /** Pulls the {@code evidenceId} values out of the find_evidence tool-result JSON. */
    private static List<String> extractEvidenceIds(String toolResultJson) {
        List<String> ids = new ArrayList<>();
        Matcher matcher = EVIDENCE_ID_PATTERN.matcher(toolResultJson);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    private static ToolCallResponse textResponse(String text) {
        return new ToolCallResponse(
                generateId(),
                MOCK_MODEL,
                "end_turn",
                List.of(ContentBlock.text(text)),
                new TokenUsage(40, 20));
    }

    private static boolean isValidativePrompt(ToolCallRequest request) {
        if (request.messages() == null) {
            return false;
        }
        return request.messages().stream()
                .anyMatch(m -> "user".equals(m.role()) && m.content() != null
                        && m.content().contains(VALIDATIVE_PROMPT_MARKER));
    }

    private static String generateId() {
        return "mock-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
