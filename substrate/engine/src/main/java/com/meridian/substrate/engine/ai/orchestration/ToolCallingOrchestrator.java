package com.meridian.substrate.engine.ai.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.ai.CompletionRequest;
import com.meridian.substrate.common.ai.ContentBlock;
import com.meridian.substrate.common.ai.LlmClient;
import com.meridian.substrate.common.ai.ToolCallRequest;
import com.meridian.substrate.common.ai.ToolCallResponse;
import com.meridian.substrate.common.ai.config.LlmProperties;
import com.meridian.substrate.engine.ai.cost.CostTracker;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.engine.ai.prompt.PersonaPromptLoader;
import com.meridian.substrate.engine.ai.safety.SafetyHandler;
import com.meridian.substrate.engine.ai.tools.ToolDispatcher;
import com.meridian.substrate.engine.ai.tools.ToolRegistry;
import com.meridian.substrate.engine.dna.DnaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the multi-turn tool-calling loop between the LLM and registered tools.
 * <p>
 * On each iteration the orchestrator sends the conversation (including any pending
 * tool results) to the LLM. If the response contains {@code tool_use} content blocks
 * the orchestrator dispatches each tool call via {@link ToolDispatcher}, appends the
 * results to the conversation, and loops. The loop terminates when the model produces
 * a final text response with no further tool calls, or when the maximum iteration
 * count is reached.
 *
 * <p><b>Protocol fix (port-map §2.3):</b> unlike homepoc — which flattened tool
 * results into synthetic {@code "[Tool Result: …]"} user text — this orchestrator
 * speaks the NATIVE tool-calling protocol: the assistant turn's content blocks
 * (text + {@code tool_use}) are echoed back verbatim as a block-shaped assistant
 * message, followed by a block-shaped user message of {@code tool_result} blocks
 * keyed by {@code tool_use_id}. The provider client maps these onto the SDK's
 * typed block params.
 *
 * <p><b>Budget enforcement (port-map §2.3):</b> {@code substrate.llm.tools.max-calls-per-request}
 * was configured but ignored in homepoc. It is now enforced: once the budget is
 * exhausted, further {@code tool_use} blocks are answered with an
 * {@code is_error} tool_result instructing the model to answer from what it has
 * (the native protocol requires a tool_result for every tool_use, so over-budget
 * calls cannot simply be dropped). {@code MAX_ITERATIONS} additionally bounds the
 * loop.
 *
 * <p><b>Note for future contributors:</b>
 * {@code com.meridian.substrate.engine.ai.assistive.AssistiveModeService} intentionally
 * bypasses this orchestrator and calls {@code LlmClient.complete()} directly.
 * That is not an oversight — Assistive's sub-second latency budget conflicts
 * with the multi-turn loop, and Assistive's tool budget is "minimal" by design.
 * See the class Javadoc on {@code AssistiveModeService} for the full rationale
 * before "fixing" the inconsistency by routing Assistive through here.
 */
@Service
public class ToolCallingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ToolCallingOrchestrator.class);

    private static final int MAX_ITERATIONS = 10;

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ToolDispatcher toolDispatcher;
    private final LlmProperties llmProperties;
    private final PersonaPromptLoader personaPromptLoader;
    private final DnaService dnaService;
    private final SafetyHandler safetyHandler;
    private final CostTracker costTracker;
    private final ObjectMapper objectMapper;

    public ToolCallingOrchestrator(LlmClient llmClient,
                                   ToolRegistry toolRegistry,
                                   ToolDispatcher toolDispatcher,
                                   LlmProperties llmProperties,
                                   PersonaPromptLoader personaPromptLoader,
                                   DnaService dnaService,
                                   SafetyHandler safetyHandler,
                                   CostTracker costTracker,
                                   ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.toolDispatcher = toolDispatcher;
        this.llmProperties = llmProperties;
        this.personaPromptLoader = personaPromptLoader;
        this.dnaService = dnaService;
        this.safetyHandler = safetyHandler;
        this.costTracker = costTracker;
        this.objectMapper = objectMapper;
    }

    /**
     * Backwards-compatible overload preserving the pre-Phase-2A signature.
     * Defaults the AI mode to {@link AiMode#ASSISTIVE} and the surface context
     * to {@link SurfaceContext#analystQuery()} — matches the existing Ambient
     * Chat Bar / analyst-query behavior.
     */
    public OrchestratorResult orchestrate(String userMessage, PersonaContext persona, UUID domainId) {
        return orchestrate(userMessage, persona, domainId, AiMode.ASSISTIVE, SurfaceContext.analystQuery());
    }

    /**
     * Run the multi-turn tool-calling orchestration loop for a user message.
     *
     * @param userMessage    the user's natural-language question or request
     * @param persona        the current persona context controlling tool access and prompt
     * @param domainId       the active domain UUID for scoping queries
     * @param mode           the AI invocation mode (generative / assistive / validative)
     * @param surfaceContext the originating designer surface and optional entity context
     * @return the orchestration result including final text, tool traces, and token usage
     */
    public OrchestratorResult orchestrate(String userMessage, PersonaContext persona, UUID domainId,
                                          AiMode mode, SurfaceContext surfaceContext) {
        long startTime = System.currentTimeMillis();
        log.info("Orchestrating request [persona={}, domainId={}, mode={}, surface={}, entityType={}, entityId={}]",
                persona.getPersonaLevel(), domainId, mode,
                surfaceContext.surface(), surfaceContext.entityType(), surfaceContext.entityId());

        // 1. Load persona system prompt, prepend DNA preamble
        String systemPrompt = dnaService.composePreamble()
                + personaPromptLoader.loadSystemPrompt(persona.getPersonaLevel());

        // 2. Get tool definitions for this persona
        List<ToolCallRequest.ToolDefinition> toolDefinitions =
                toolRegistry.getToolDefinitionsForPersona(persona);

        // 3. Build initial messages
        List<CompletionRequest.Message> messages = new ArrayList<>();
        messages.add(CompletionRequest.Message.user(userMessage));

        String model = llmProperties.getAnthropic().getModel();
        int maxTokens = llmProperties.getAnthropic().getMaxTokens();
        int maxToolCalls = llmProperties.getTools().getMaxCallsPerRequest();

        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        int toolCallsUsed = 0;
        List<ToolCallTrace> toolCallTraces = new ArrayList<>();
        String finalText = "";
        String stopReason = null;

        // 4. Multi-turn loop
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            log.debug("Orchestration iteration {} [messages={}]", iteration, messages.size());

            ToolCallRequest request = ToolCallRequest.of(
                    model, systemPrompt, messages, toolDefinitions, maxTokens);

            ToolCallResponse response = llmClient.completeWithTools(request);

            if (response.usage() != null) {
                totalInputTokens += response.usage().inputTokens();
                totalOutputTokens += response.usage().outputTokens();
                costTracker.recordUsage(
                        response.model() != null ? response.model() : model,
                        response.usage().inputTokens(),
                        response.usage().outputTokens());
            }
            stopReason = response.stopReason();

            // Check for tool_use blocks
            List<ContentBlock> toolUseBlocks = response.content().stream()
                    .filter(ContentBlock::isToolUse)
                    .toList();

            if (toolUseBlocks.isEmpty()) {
                // No more tool calls — extract final text and return
                finalText = extractFinalText(response);
                log.debug("No tool calls in iteration {}, finishing with text response", iteration);
                break;
            }

            // NATIVE protocol: echo the assistant turn's content blocks verbatim
            // (text + tool_use), then answer every tool_use with a tool_result block.
            messages.add(CompletionRequest.Message.assistant(response.content()));

            List<ContentBlock> toolResultBlocks = new ArrayList<>();
            for (ContentBlock toolUse : toolUseBlocks) {
                if (toolCallsUsed >= maxToolCalls) {
                    log.warn("Tool-call budget exhausted [max={}] — refusing dispatch of '{}'",
                            maxToolCalls, toolUse.toolName());
                    toolResultBlocks.add(ContentBlock.toolResult(
                            toolUse.toolUseId(),
                            "Tool-call budget exceeded (max " + maxToolCalls
                                    + " per request). Answer using the information you already have.",
                            true));
                    continue;
                }
                toolCallsUsed++;

                long toolStart = System.currentTimeMillis();

                JsonNode argsNode = objectMapper.valueToTree(
                        toolUse.toolInput() != null ? toolUse.toolInput() : Map.of());
                JsonNode result = toolDispatcher.dispatch(toolUse.toolName(), argsNode, persona);

                long toolLatency = System.currentTimeMillis() - toolStart;
                String resultStr = result.toString();
                boolean isError = result.path("error").asBoolean(false);

                toolCallTraces.add(new ToolCallTrace(
                        toolUse.toolName(),
                        toolUse.toolInput() != null ? toolUse.toolInput() : Map.of(),
                        resultStr,
                        toolLatency));

                log.debug("Tool '{}' completed in {} ms [error={}]", toolUse.toolName(), toolLatency, isError);

                toolResultBlocks.add(ContentBlock.toolResult(toolUse.toolUseId(), resultStr, isError));
            }

            // Tool results travel as native tool_result blocks in a user message.
            messages.add(CompletionRequest.Message.user(toolResultBlocks));
        }

        // 5. Safety: classify the final answer; refusals are replaced with the
        // configured fallback and flagged so ChatInteraction.refusalDetected is truthful.
        boolean refusalDetected = safetyHandler.isRefusal(stopReason, finalText);
        if (refusalDetected) {
            log.warn("Orchestrator final response classified as refusal [stopReason={}]", stopReason);
            finalText = safetyHandler.fallbackMessage();
        }

        long totalLatency = System.currentTimeMillis() - startTime;
        log.info("Orchestration complete [iterations={}, toolCalls={}, tokens={}, latency={}ms, refusal={}]",
                toolCallTraces.size() > 0 ? "with tools" : "direct",
                toolCallTraces.size(),
                totalInputTokens + totalOutputTokens,
                totalLatency,
                refusalDetected);

        return new OrchestratorResult(
                finalText,
                toolCallTraces,
                totalInputTokens,
                totalOutputTokens,
                totalLatency,
                model,
                refusalDetected
        );
    }

    /**
     * Extract the final text content from a tool call response.
     *
     * @param response the LLM response
     * @return concatenated text from all text blocks
     */
    private String extractFinalText(ToolCallResponse response) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.content()) {
            if (block.isText() && block.text() != null) {
                sb.append(block.text());
            }
        }
        return sb.toString();
    }
}
