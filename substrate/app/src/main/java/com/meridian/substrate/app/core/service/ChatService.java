package com.meridian.substrate.app.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.ai.CompletionChunk;
import com.meridian.substrate.common.ai.CompletionRequest;
import com.meridian.substrate.common.ai.LlmClient;
import com.meridian.substrate.common.ai.config.LlmProperties;
import com.meridian.substrate.common.domain.ChatInteraction;
import com.meridian.substrate.common.repo.ChatInteractionRepository;
import com.meridian.substrate.engine.ai.cost.CostTracker;
import com.meridian.substrate.engine.ai.orchestration.OrchestratorResult;
import com.meridian.substrate.engine.ai.orchestration.ToolCallTrace;
import com.meridian.substrate.engine.ai.orchestration.ToolCallingOrchestrator;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.engine.ai.prompt.PersonaPromptLoader;
import com.meridian.substrate.engine.ai.safety.SafetyHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service for conducting chat interactions with the LLM, supporting both
 * synchronous (tool-calling orchestrator) and streaming modes.
 *
 * <p><b>Port notes (port-map §2.3 / S12, chunk C5):</b>
 * <ul>
 *   <li>{@code refusalDetected} is persisted truthfully: the sync path takes it
 *       from {@link OrchestratorResult#refusalDetected()} (the orchestrator routes
 *       its final text through {@code SafetyHandler}); the streaming path classifies
 *       the assembled content via {@link SafetyHandler#isRefusal(String, String)}
 *       at stream completion. Homepoc hardcoded {@code false}.</li>
 *   <li>{@link CostTracker#recordUsage} fires at streaming completion using the
 *       aggregate usage carried by the terminal {@link CompletionChunk} (per the
 *       SdkAnthropicClient streaming token-accounting fix). The sync path does NOT
 *       record here — the orchestrator already records per iteration; recording
 *       again would double-count.</li>
 *   <li>Per-interaction usage is persisted onto {@code chat_interactions} token
 *       columns, and {@code costEstimate} is computed from the externalized
 *       {@code substrate.llm.cost.*} rates (absent rates read as 0.0).</li>
 *   <li>{@code toolCalls} traces are persisted onto the entity's {@code tool_calls}
 *       JSON column — homepoc injected the {@code ObjectMapper} for this but never
 *       used it, leaving the column always NULL.</li>
 * </ul>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final double TOKENS_PER_MILLION = 1_000_000.0;

    private final ToolCallingOrchestrator toolCallingOrchestrator;
    private final LlmClient llmClient;
    private final PersonaPromptLoader personaPromptLoader;
    private final LlmProperties llmProperties;
    private final ChatInteractionRepository chatInteractionRepository;
    private final SafetyHandler safetyHandler;
    private final CostTracker costTracker;
    private final ObjectMapper objectMapper;

    public ChatService(ToolCallingOrchestrator toolCallingOrchestrator,
                       LlmClient llmClient,
                       PersonaPromptLoader personaPromptLoader,
                       LlmProperties llmProperties,
                       ChatInteractionRepository chatInteractionRepository,
                       SafetyHandler safetyHandler,
                       CostTracker costTracker,
                       ObjectMapper objectMapper) {
        this.toolCallingOrchestrator = toolCallingOrchestrator;
        this.llmClient = llmClient;
        this.personaPromptLoader = personaPromptLoader;
        this.llmProperties = llmProperties;
        this.chatInteractionRepository = chatInteractionRepository;
        this.safetyHandler = safetyHandler;
        this.costTracker = costTracker;
        this.objectMapper = objectMapper;
    }

    /**
     * Conduct a synchronous chat interaction using the tool-calling orchestrator.
     * The interaction is persisted as a {@link ChatInteraction} entity.
     *
     * @param domainId    the active domain UUID
     * @param userMessage the user's natural-language message
     * @param persona     the current persona context
     * @return the chat response including message, tool calls, and token usage
     */
    public ChatResponse chat(UUID domainId, String userMessage, PersonaContext persona) {
        log.info("Starting synchronous chat [domainId={}, persona={}]",
                domainId, persona.getPersonaLevel());

        OrchestratorResult result = toolCallingOrchestrator.orchestrate(
                userMessage, persona, domainId);

        // Persist the interaction (cost is recorded by the orchestrator per
        // iteration — recording it here too would double-count).
        persistInteraction(domainId, persona, userMessage, result.finalText(),
                result.model(), result.totalInputTokens(), result.totalOutputTokens(),
                result.totalLatencyMs(), result.refusalDetected(), result.toolCalls());

        return new ChatResponse(
                result.finalText(),
                result.toolCalls(),
                result.totalInputTokens(),
                result.totalOutputTokens()
        );
    }

    /**
     * Conduct a streaming chat interaction using the provider streaming API.
     * The full response is persisted as a {@link ChatInteraction} after stream
     * completion, and the aggregate usage from the terminal chunk is recorded
     * with the {@link CostTracker}.
     *
     * @param domainId    the active domain UUID
     * @param userMessage the user's natural-language message
     * @param persona     the current persona context
     * @return a reactive stream of completion chunks
     */
    public Flux<CompletionChunk> chatStream(UUID domainId, String userMessage, PersonaContext persona) {
        log.info("Starting streaming chat [domainId={}, persona={}]",
                domainId, persona.getPersonaLevel());

        String systemPrompt = personaPromptLoader.loadSystemPrompt(persona.getPersonaLevel());
        String model = llmProperties.getAnthropic().getModel();
        int maxTokens = llmProperties.getAnthropic().getMaxTokens();

        List<CompletionRequest.Message> messages = new ArrayList<>();
        messages.add(CompletionRequest.Message.user(userMessage));

        CompletionRequest request = CompletionRequest.of(model, systemPrompt, messages, maxTokens);

        // Signal callbacks are serialized by the Reactor spec, so plain holders
        // would suffice; atomics are kept for cross-thread visibility between the
        // emitting scheduler and the completion callback.
        StringBuilder contentBuilder = new StringBuilder();
        AtomicInteger totalInputTokens = new AtomicInteger(0);
        AtomicInteger totalOutputTokens = new AtomicInteger(0);
        AtomicReference<String> lastStopReason = new AtomicReference<>();
        AtomicReference<Boolean> usageSeen = new AtomicReference<>(false);
        long startTime = System.currentTimeMillis();

        return llmClient.completeStream(request)
                .doOnNext(chunk -> {
                    if (chunk.delta() != null) {
                        contentBuilder.append(chunk.delta());
                    }
                    if (chunk.usage() != null) {
                        // The terminal chunk carries the AGGREGATE usage for the
                        // whole stream — overwrite, don't add.
                        totalInputTokens.set(chunk.usage().inputTokens());
                        totalOutputTokens.set(chunk.usage().outputTokens());
                        usageSeen.set(true);
                    }
                    if (chunk.stopReason() != null) {
                        lastStopReason.set(chunk.stopReason());
                    }
                })
                .doOnComplete(() -> {
                    long latencyMs = System.currentTimeMillis() - startTime;
                    String assistantMessage = contentBuilder.toString();

                    if (usageSeen.get()) {
                        costTracker.recordUsage(model,
                                totalInputTokens.get(), totalOutputTokens.get());
                    } else {
                        log.warn("Stream completed without usage data — cost not recorded [domainId={}]",
                                domainId);
                    }

                    boolean refusalDetected =
                            safetyHandler.isRefusal(lastStopReason.get(), assistantMessage);
                    if (refusalDetected) {
                        log.warn("Streaming chat response classified as refusal [domainId={}, stopReason={}]",
                                domainId, lastStopReason.get());
                    }

                    persistInteraction(domainId, persona, userMessage, assistantMessage,
                            model, totalInputTokens.get(), totalOutputTokens.get(),
                            latencyMs, refusalDetected, null);
                    log.info("Streaming chat completed [domainId={}, latency={}ms]", domainId, latencyMs);
                })
                .doOnError(error -> log.error("Streaming chat failed [domainId={}]: {}",
                        domainId, error.getMessage(), error));
    }

    /**
     * Persist a chat interaction record. Failures are logged, never propagated —
     * bookkeeping must not break a chat that already succeeded.
     */
    private void persistInteraction(UUID domainId, PersonaContext persona, String userMessage,
                                    String assistantMessage, String model,
                                    int inputTokens, int outputTokens, long latencyMs,
                                    boolean refusalDetected, List<ToolCallTrace> toolCalls) {
        try {
            ChatInteraction interaction = new ChatInteraction();
            interaction.setDomainId(domainId);
            interaction.setPersonaId(persona.getPersonaId());
            interaction.setUserMessage(userMessage);
            interaction.setAssistantMessage(assistantMessage);
            interaction.setModel(model);
            interaction.setInputTokens(inputTokens);
            interaction.setOutputTokens(outputTokens);
            interaction.setLatencyMs(latencyMs);
            interaction.setRefusalDetected(refusalDetected);
            interaction.setCostEstimate(estimateCost(inputTokens, outputTokens));
            if (toolCalls != null && !toolCalls.isEmpty()) {
                interaction.setToolCalls(objectMapper.valueToTree(toolCalls));
            }

            chatInteractionRepository.save(interaction);
            log.debug("Persisted chat interaction [domainId={}]", domainId);
        } catch (Exception e) {
            log.error("Failed to persist chat interaction [domainId={}]: {}", domainId, e.getMessage(), e);
        }
    }

    /** Per-interaction USD estimate from the externalized substrate.llm.cost.* rates. */
    private double estimateCost(int inputTokens, int outputTokens) {
        double inputRate = llmProperties.getCost().getInputRatePerMillion();
        double outputRate = llmProperties.getCost().getOutputRatePerMillion();
        return (inputTokens / TOKENS_PER_MILLION) * inputRate
                + (outputTokens / TOKENS_PER_MILLION) * outputRate;
    }
}
