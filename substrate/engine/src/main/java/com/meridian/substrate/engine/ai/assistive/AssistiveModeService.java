package com.meridian.substrate.engine.ai.assistive;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.ai.CompletionRequest;
import com.meridian.substrate.common.ai.CompletionResponse;
import com.meridian.substrate.common.ai.LlmClient;
import com.meridian.substrate.common.ai.config.LlmProperties;
import com.meridian.substrate.common.domain.Assertion;
import com.meridian.substrate.common.domain.enums.ProducedBy;
import com.meridian.substrate.engine.ai.assertion.AssertionService;
import com.meridian.substrate.engine.ai.cost.CostTracker;
import com.meridian.substrate.engine.ai.orchestration.AiMode;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.engine.ai.prompt.PersonaPromptLoader;
import com.meridian.substrate.engine.ai.retrieval.EvidenceChunk;
import com.meridian.substrate.engine.ai.retrieval.EvidenceQuery;
import com.meridian.substrate.engine.ai.retrieval.EvidenceQueryType;
import com.meridian.substrate.engine.ai.retrieval.EvidenceRetriever;
import com.meridian.substrate.engine.ai.safety.SafetyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Assistive AI mode: produce inline {@link Suggestion}s for a context-specific
 * query the user is interacting with. Designed for sub-second response in
 * production — single-shot {@link LlmClient#complete} call, no multi-turn
 * orchestration loop, no tool round-trips.
 *
 * <p><b>IMPORTANT — orchestrator bypass.</b> Unlike
 * {@code GenerativeModeService} and {@code ValidativeModeService}, this
 * service calls {@link LlmClient#complete} directly rather than going through
 * {@code ToolCallingOrchestrator}. This is an intentional architectural
 * split, not an oversight. Rationale: Assistive is a single-shot,
 * sub-second-latency operation invoked in tight loops from the frontend
 * (debounced typing, hover suggestions); it does not benefit from multi-turn
 * tool orchestration, and forcing it through the orchestrator would either
 * blow the latency budget (multi-turn round trips) or strip tools from the
 * existing chat / ANALYST_QUERY path that legitimately wants them (since
 * that path also defaults to {@link AiMode#ASSISTIVE} on the orchestrator's
 * 3-arg overload).
 *
 * <p>The {@link AiMode} enum value {@code ASSISTIVE} is still threaded into
 * the persisted {@code Assertion}'s producedByContext for provenance
 * uniformity across the three AI modes.
 *
 * <p>Do not "helpfully" route Assistive through the orchestrator without
 * understanding the constraints above.
 *
 * <p><b>Architectural contract:</b>
 * <ul>
 *   <li><b>Query type allowlist.</b> Only the values in
 *       {@link AssistiveQueryType} are accepted — unknown types return a
 *       structured "not yet implemented" response, not a 4xx and not a fallback
 *       generic handler.</li>
 *   <li><b>Output structure allowlist.</b> Only {@code value}, {@code confidence},
 *       and {@code evidenceIds} survive from each LLM-emitted suggestion;
 *       any other field is dropped by construction.</li>
 *   <li><b>Always dismissible.</b> Every {@link Suggestion} returned has
 *       {@code dismissible == true} regardless of LLM emission — the user
 *       always retains control. There is no path through this service that
 *       can produce a non-dismissible suggestion.</li>
 *   <li><b>Differential grounding (P6 enforced at runtime).</b> Query types
 *       whose {@link AssistiveQueryType#getGroundingRule()} is
 *       {@link GroundingRule#REQUIRED} have suggestions filtered if their
 *       evidence does not resolve to in-domain Evidence. Structural query
 *       types (collision detection, parent suggestion) declare
 *       {@link GroundingRule#NOT_REQUIRED} and are accepted with empty
 *       evidence.</li>
 *   <li><b>Assertion provenance.</b> Each surviving suggestion persists as an
 *       {@code Assertion} with {@link ProducedBy#AI_ASSISTIVE} so the
 *       downstream commit step can answer "where did this description come
 *       from?" months later.</li>
 * </ul>
 *
 * <p><b>Port wiring (port-map §2.3 fixes):</b>
 * <ul>
 *   <li>The grounding/citation pool is assembled through the
 *       {@link EvidenceRetriever} seam ({@link EvidenceQueryType#EVIDENCE})
 *       instead of a direct {@code EvidenceRepository} call — the natural
 *       extension point for a future vector/document retriever. The pool is
 *       still exactly the domain's {@code Evidence} rows, so the grounding
 *       contract is unchanged.</li>
 *   <li>Single-shot completions route through {@link SafetyHandler}; a refusal
 *       returns an empty result whose explanation is the configured fallback
 *       message — never a parsed hallucination.</li>
 *   <li>{@link CostTracker#recordUsage} fires on every single-shot call.</li>
 * </ul>
 */
@Service
public class AssistiveModeService {

    private static final Logger log = LoggerFactory.getLogger(AssistiveModeService.class);

    /** Output-shape allowlist: keys retained from each LLM-emitted suggestion object. */
    private static final Set<String> SUGGESTION_ALLOWED_KEYS = Set.of(
            "value", "confidence", "evidenceIds"
    );

    private final LlmClient llmClient;
    private final PersonaPromptLoader personaPromptLoader;
    private final LlmProperties llmProperties;
    private final AssertionService assertionService;
    private final EvidenceRetriever evidenceRetriever;
    private final SafetyHandler safetyHandler;
    private final CostTracker costTracker;
    private final ObjectMapper mapper;

    public AssistiveModeService(LlmClient llmClient,
                                PersonaPromptLoader personaPromptLoader,
                                LlmProperties llmProperties,
                                AssertionService assertionService,
                                EvidenceRetriever evidenceRetriever,
                                SafetyHandler safetyHandler,
                                CostTracker costTracker,
                                ObjectMapper mapper) {
        this.llmClient = llmClient;
        this.personaPromptLoader = personaPromptLoader;
        this.llmProperties = llmProperties;
        this.assertionService = assertionService;
        this.evidenceRetriever = evidenceRetriever;
        this.safetyHandler = safetyHandler;
        this.costTracker = costTracker;
        this.mapper = mapper;
    }

    public SuggestResponse suggest(SuggestRequest request, PersonaContext persona) {
        log.info("Assistive request [domainId={}, surface={}, queryType={}, entityType={}, entityId={}]",
                request.domainId(), request.surface(), request.queryType(),
                request.entityType(), request.entityId());

        AssistiveQueryType queryType = parseQueryType(request.queryType());
        if (queryType == null) {
            return new SuggestResponse(List.of(), 0, String.format(
                    "Query type '%s' is not implemented in Phase 2A. "
                            + "Implemented: %s.",
                    request.queryType(),
                    Arrays.toString(AssistiveQueryType.values())));
        }

        // Pre-fetch grounding evidence for query types that need it. Done in
        // service code (not via LLM tool round-trip) to preserve the
        // single-shot latency budget. Assembled through the EvidenceRetriever
        // seam — the chunks' sourceIds ARE the domain's Evidence row UUIDs.
        List<EvidenceChunk> evidencePool = queryType.getGroundingRule() == GroundingRule.REQUIRED
                ? retrieveEvidencePool(request.domainId())
                : List.of();
        Set<UUID> domainEvidenceIds = evidencePool.stream()
                .map(chunk -> UUID.fromString(chunk.source().sourceId()))
                .collect(Collectors.toSet());

        String userMessage = buildPromptForQueryType(queryType, request, evidencePool);

        CompletionResponse response = singleShot(userMessage, persona);

        // Safety: a refusal must never be parsed as suggestions — return the
        // configured fallback as the explanation.
        if (safetyHandler.isRefusal(response)) {
            return new SuggestResponse(List.of(), 0, safetyHandler.handleRefusal(response));
        }

        List<JsonNode> rawSuggestions = parseSuggestions(response.content());
        if (rawSuggestions.isEmpty()) {
            return new SuggestResponse(List.of(), 0, emptyResultExplanation(queryType));
        }

        List<Suggestion> kept = new ArrayList<>();
        int filtered = 0;

        for (JsonNode raw : rawSuggestions) {
            Suggestion suggestion = validateAndBuild(raw, queryType, domainEvidenceIds, request);
            if (suggestion == null) {
                filtered++;
                continue;
            }
            kept.add(suggestion);
        }

        log.info("Assistive produced [suggestions={}, filtered={}]", kept.size(), filtered);

        String explanation;
        if (kept.isEmpty()) {
            explanation = String.format(
                    "AI returned %d suggestion(s); all were filtered (allowlist or grounding).",
                    rawSuggestions.size());
        } else {
            explanation = null;
        }

        return new SuggestResponse(kept, filtered, explanation);
    }

    private List<EvidenceChunk> retrieveEvidencePool(UUID domainId) {
        Map<String, Object> filters = new HashMap<>();
        if (domainId != null) {
            filters.put("domainId", domainId);
        }
        return evidenceRetriever.retrieve(
                new EvidenceQuery(EvidenceQueryType.EVIDENCE, filters, null));
    }

    private CompletionResponse singleShot(String userMessage, PersonaContext persona) {
        String systemPrompt = personaPromptLoader.loadSystemPrompt(persona.getPersonaLevel());
        String model = llmProperties.getAnthropic().getModel();
        int maxTokens = llmProperties.getAnthropic().getMaxTokens();

        CompletionRequest req = CompletionRequest.of(
                model, systemPrompt,
                List.of(CompletionRequest.Message.user(userMessage)),
                maxTokens);
        CompletionResponse response = llmClient.complete(req);
        if (response.usage() != null) {
            costTracker.recordUsage(
                    response.model() != null ? response.model() : model,
                    response.usage().inputTokens(),
                    response.usage().outputTokens());
        }
        return response;
    }

    private Suggestion validateAndBuild(JsonNode raw,
                                        AssistiveQueryType queryType,
                                        Set<UUID> domainEvidenceIds,
                                        SuggestRequest request) {
        // Output-shape allowlist: only the three known keys survive.
        String value = textOrNull(raw, "value");
        if (value == null || value.isBlank()) {
            log.debug("Filtered suggestion: missing required 'value' field");
            return null;
        }
        Float confidence = numericOrNull(raw, "confidence");
        List<UUID> claimedEvidence = extractEvidenceIds(raw);

        // Differential grounding rule, declared on the query type itself.
        List<UUID> grounded = claimedEvidence.stream()
                .filter(domainEvidenceIds::contains)
                .toList();
        if (queryType.getGroundingRule() == GroundingRule.REQUIRED && grounded.isEmpty()) {
            log.debug("Filtered suggestion for query {}: claimed {} evidenceIds, {} grounded",
                    queryType, claimedEvidence.size(), grounded.size());
            return null;
        }

        Assertion assertion = persistAssertion(request, queryType, value, confidence, grounded);

        // dismissible is set unconditionally true — see class Javadoc.
        return new Suggestion(value, confidence, true, grounded, assertion.getId());
    }

    private Assertion persistAssertion(SuggestRequest request,
                                       AssistiveQueryType queryType,
                                       String value,
                                       Float confidence,
                                       List<UUID> grounded) {
        String subject = request.entityId() != null && request.entityType() != null
                ? request.entityType() + ":" + request.entityId()
                : "domain:" + request.domainId();

        ObjectNode producedByContext = mapper.createObjectNode();
        producedByContext.put("mode", AiMode.ASSISTIVE.name());
        producedByContext.put("surface", request.surface().name());
        producedByContext.put("queryType", queryType.name());

        return assertionService.record(
                request.domainId(),
                subject,
                "assistive_suggestion:" + queryType.name(),
                value,
                confidence,
                grounded,
                ProducedBy.AI_ASSISTIVE,
                producedByContext);
    }

    private String emptyResultExplanation(AssistiveQueryType queryType) {
        return switch (queryType) {
            case DETECT_NAME_COLLISION -> "No name collisions detected.";
            case SUGGEST_PARENT_CAPABILITY -> "No parent capability suggestions available.";
            case SUGGEST_DESCRIPTION -> "No description suggestions available from current Universe content.";
        };
    }

    private AssistiveQueryType parseQueryType(String raw) {
        if (raw == null) return null;
        return Stream.of(AssistiveQueryType.values())
                .filter(t -> t.name().equals(raw))
                .findFirst()
                .orElse(null);
    }

    private String textOrNull(JsonNode raw, String key) {
        JsonNode node = raw.get(key);
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private Float numericOrNull(JsonNode raw, String key) {
        JsonNode node = raw.get(key);
        return node != null && node.isNumber() ? (float) node.asDouble() : null;
    }

    private List<UUID> extractEvidenceIds(JsonNode raw) {
        JsonNode arr = raw.get("evidenceIds");
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        Set<UUID> seen = new HashSet<>();
        List<UUID> out = new ArrayList<>();
        for (JsonNode node : arr) {
            if (!node.isTextual()) continue;
            try {
                UUID id = UUID.fromString(node.asText());
                if (seen.add(id)) {
                    out.add(id);
                }
            } catch (IllegalArgumentException ignored) {
                // skip non-UUID strings
            }
        }
        return out;
    }

    private List<JsonNode> parseSuggestions(String finalText) {
        if (finalText == null || finalText.isBlank()) {
            return List.of();
        }
        int start = finalText.indexOf('[');
        int end = finalText.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return List.of();
        }
        try {
            JsonNode parsed = mapper.readTree(finalText.substring(start, end + 1));
            if (!parsed.isArray()) return List.of();
            List<JsonNode> out = new ArrayList<>();
            parsed.forEach(out::add);
            return out;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse assistive suggestions JSON: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildPromptForQueryType(AssistiveQueryType queryType,
                                           SuggestRequest request,
                                           List<EvidenceChunk> evidencePool) {
        StringBuilder sb = new StringBuilder();
        sb.append("Assistive query: ").append(queryType.name()).append("\n");
        sb.append("Domain: ").append(request.domainId()).append("\n");
        if (request.entityId() != null) {
            sb.append("Active entity: ").append(request.entityType()).append(":").append(request.entityId()).append("\n");
        }
        if (request.queryContext() != null && !request.queryContext().isNull()) {
            sb.append("Context: ").append(request.queryContext()).append("\n");
        }
        sb.append("\n");

        switch (queryType) {
            case SUGGEST_DESCRIPTION -> {
                sb.append("Suggest a one-sentence description for the active entity, drawn from Universe content. ");
                sb.append("Each suggestion MUST cite at least one Evidence ID from the available pool.\n");
                sb.append("Available Evidence (id : label):\n");
                for (EvidenceChunk chunk : evidencePool) {
                    sb.append("  ").append(chunk.source().sourceId()).append(" : ")
                            .append(chunk.source().displayName() == null ? "" : chunk.source().displayName())
                            .append("\n");
                }
            }
            case DETECT_NAME_COLLISION -> {
                sb.append("Inspect the proposed name in the context against any sibling names provided. ");
                sb.append("Return one suggestion per detected collision; return an empty array if none. ");
                sb.append("Do not invent collisions that are not supported by the context.\n");
            }
            case SUGGEST_PARENT_CAPABILITY -> {
                sb.append("Inspect the existing capability tree summary in the context and suggest a parent capability ");
                sb.append("for the proposed entity. Return one suggestion per plausible parent; empty array if none plausible.\n");
            }
        }

        sb.append("\n");
        sb.append("Respond with ONLY a JSON array. Each suggestion object MUST contain:\n");
        sb.append("  - value: the suggested string\n");
        sb.append("Optional fields:\n");
        sb.append("  - confidence: number 0.0–1.0\n");
        sb.append("  - evidenceIds: array of UUID strings");
        if (queryType.getGroundingRule() == GroundingRule.REQUIRED) {
            sb.append(" (REQUIRED for this query type)");
        } else {
            sb.append(" (optional for this query type)");
        }
        sb.append("\n");
        sb.append("Any other field will be silently dropped. Do not include prose around the JSON array.");
        return sb.toString();
    }
}
