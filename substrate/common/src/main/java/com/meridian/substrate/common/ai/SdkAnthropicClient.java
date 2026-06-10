package com.meridian.substrate.common.ai;

import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.RateLimitException;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meridian.substrate.common.ai.config.LlmProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Production implementation of {@link LlmClient} backed by the Anthropic Java SDK.
 * Activated when {@code substrate.llm.provider=anthropic} (the default).
 *
 * <p>Degraded mode: when no API key is configured, no SDK client bean exists
 * (see {@link AnthropicClientConfig}); this client still registers so the
 * application boots, but every call throws {@link LlmUnavailableException}
 * and {@link #isAvailable()} returns {@code false}.
 */
@Service
@Primary
@ConditionalOnProperty(name = "substrate.llm.provider", havingValue = "anthropic", matchIfMissing = true)
public class SdkAnthropicClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(SdkAnthropicClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String DISABLED_MESSAGE =
            "Anthropic LLM client is disabled: substrate.llm.anthropic.api-key is not configured (degraded mode)";

    private final LlmProperties properties;
    private final com.anthropic.client.AnthropicClient sdkClient; // null => disabled (degraded mode)

    @Autowired
    public SdkAnthropicClient(LlmProperties properties,
                               ObjectProvider<com.anthropic.client.AnthropicClient> sdkClientProvider) {
        this(properties, sdkClientProvider.getIfAvailable());
    }

    SdkAnthropicClient(LlmProperties properties,
                       com.anthropic.client.AnthropicClient sdkClient) {
        this.properties = properties;
        this.sdkClient = sdkClient;
        if (sdkClient == null) {
            log.warn("SdkAnthropicClient DISABLED — substrate.llm.anthropic.api-key is not configured; "
                    + "LLM-backed calls will fail with LlmUnavailableException (degraded mode)");
        } else {
            log.info("Initialized SdkAnthropicClient [model={}]", properties.getAnthropic().getModel());
        }
    }

    @Override
    public boolean isAvailable() {
        return sdkClient != null;
    }

    // -------------------------------------------------------------------------
    // complete()
    // -------------------------------------------------------------------------

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        ensureAvailable();
        log.debug("complete() called [model={}, messages={}, maxTokens={}]",
                request.model(), request.messages().size(), request.maxTokens());

        try {
            MessageCreateParams params = buildParams(request);
            Message message = sdkClient.messages().create(params);

            String text = extractText(message.content());
            TokenUsage usage = mapUsage(message.usage());

            log.debug("complete() finished [id={}, stopReason={}, tokens={}]",
                    message.id(), message.stopReason(), usage.totalTokens());

            return new CompletionResponse(
                    message.id(),
                    text,
                    message.model().toString(),
                    mapStopReason(message.stopReason()),
                    usage
            );
        } catch (RateLimitException e) {
            throw new LlmRateLimitException("Anthropic rate limit exceeded", e);
        }
    }

    // -------------------------------------------------------------------------
    // completeStream()
    // -------------------------------------------------------------------------

    /**
     * Streams text deltas, then emits a terminal chunk (null {@code delta}) carrying
     * the final stop reason and aggregate token usage — input tokens from
     * {@code message_start}, output tokens from {@code message_delta} (cumulative).
     */
    @Override
    public Flux<CompletionChunk> completeStream(CompletionRequest request) {
        if (sdkClient == null) {
            return Flux.error(new LlmUnavailableException(DISABLED_MESSAGE));
        }
        log.debug("completeStream() called [model={}, messages={}, maxTokens={}]",
                request.model(), request.messages().size(), request.maxTokens());

        return Flux.create(sink -> {
            try {
                MessageCreateParams params = buildParams(request);

                AtomicReference<String> messageId = new AtomicReference<>();
                AtomicInteger inputTokens = new AtomicInteger();
                AtomicInteger outputTokens = new AtomicInteger();
                AtomicReference<String> stopReason = new AtomicReference<>();
                AtomicBoolean terminated = new AtomicBoolean(false);

                try (StreamResponse<RawMessageStreamEvent> streamResponse =
                             sdkClient.messages().createStreaming(params)) {

                    streamResponse.stream().forEach(event -> {
                        event.messageStart().ifPresent(start -> {
                            messageId.set(start.message().id());
                            inputTokens.set((int) start.message().usage().inputTokens());
                        });

                        event.contentBlockDelta().ifPresent(delta ->
                                delta.delta().text().ifPresent(textDelta ->
                                        sink.next(new CompletionChunk(
                                                messageId.get(), textDelta.text(), null, null))
                                )
                        );

                        event.messageDelta().ifPresent(delta -> {
                            // message_delta usage is cumulative for the message — set, don't add
                            outputTokens.set((int) delta.usage().outputTokens());
                            delta.usage().inputTokens()
                                    .ifPresent(tokens -> inputTokens.set(tokens.intValue()));
                            delta.delta().stopReason()
                                    .ifPresent(reason -> stopReason.set(reason.asString()));
                        });

                        event.messageStop().ifPresent(stop -> {
                            log.debug("completeStream() message_stop received");
                            emitTerminalChunk(sink, terminated, messageId.get(),
                                    stopReason.get(), inputTokens.get(), outputTokens.get());
                        });
                    });
                }

                // safety net if no message_stop was emitted
                emitTerminalChunk(sink, terminated, messageId.get(),
                        stopReason.get(), inputTokens.get(), outputTokens.get());
            } catch (RateLimitException e) {
                sink.error(new LlmRateLimitException("Anthropic rate limit exceeded", e));
            } catch (Exception e) {
                sink.error(e);
            }
        });
    }

    private void emitTerminalChunk(FluxSink<CompletionChunk> sink, AtomicBoolean terminated,
                                   String messageId, String stopReason,
                                   int inputTokens, int outputTokens) {
        if (terminated.compareAndSet(false, true)) {
            sink.next(new CompletionChunk(messageId, null, stopReason,
                    new TokenUsage(inputTokens, outputTokens)));
            sink.complete();
        }
    }

    // -------------------------------------------------------------------------
    // completeWithTools()
    // -------------------------------------------------------------------------

    @Override
    public ToolCallResponse completeWithTools(ToolCallRequest request) {
        ensureAvailable();
        log.debug("completeWithTools() called [model={}, tools={}, messages={}]",
                request.model(), request.tools().size(), request.messages().size());

        try {
            MessageCreateParams params = buildToolParams(request);
            Message message = sdkClient.messages().create(params);

            List<ContentBlock> blocks = new ArrayList<>();
            for (com.anthropic.models.messages.ContentBlock block : message.content()) {
                block.text().ifPresent(textBlock ->
                        blocks.add(ContentBlock.text(textBlock.text()))
                );
                block.toolUse().ifPresent(toolUseBlock -> {
                    Map<String, Object> input = convertJsonValue(toolUseBlock._input());
                    blocks.add(ContentBlock.toolUse(
                            toolUseBlock.id(),
                            toolUseBlock.name(),
                            input
                    ));
                });
            }

            TokenUsage usage = mapUsage(message.usage());

            log.debug("completeWithTools() finished [id={}, blocks={}, tokens={}]",
                    message.id(), blocks.size(), usage.totalTokens());

            return new ToolCallResponse(
                    message.id(),
                    message.model().toString(),
                    mapStopReason(message.stopReason()),
                    blocks,
                    usage
            );
        } catch (RateLimitException e) {
            throw new LlmRateLimitException("Anthropic rate limit exceeded", e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers (package-private where unit tests pin the DTO -> SDK mapping)
    // -------------------------------------------------------------------------

    private void ensureAvailable() {
        if (sdkClient == null) {
            throw new LlmUnavailableException(DISABLED_MESSAGE);
        }
    }

    MessageCreateParams buildParams(CompletionRequest request) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(resolveModel(request.model()))
                .maxTokens((long) request.maxTokens());

        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            builder.system(MessageCreateParams.System.ofString(request.systemPrompt()));
        }

        addMessages(builder, request.messages());

        return builder.build();
    }

    MessageCreateParams buildToolParams(ToolCallRequest request) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(resolveModel(request.model()))
                .maxTokens((long) request.maxTokens());

        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            builder.system(MessageCreateParams.System.ofString(request.systemPrompt()));
        }

        addMessages(builder, request.messages());

        // Add tool definitions
        for (ToolCallRequest.ToolDefinition toolDef : request.tools()) {
            Tool.InputSchema.Builder schemaBuilder = Tool.InputSchema.builder();
            // Spread the full schema map (type, properties, required, etc.) directly
            if (toolDef.inputSchema() != null) {
                for (Map.Entry<String, Object> entry : toolDef.inputSchema().entrySet()) {
                    schemaBuilder.putAdditionalProperty(entry.getKey(),
                            JsonValue.from(entry.getValue()));
                }
            } else {
                schemaBuilder.putAdditionalProperty("type", JsonValue.from("object"));
            }

            builder.addTool(Tool.builder()
                    .name(toolDef.name())
                    .description(toolDef.description())
                    .inputSchema(schemaBuilder.build())
                    .build());
        }

        return builder.build();
    }

    private void addMessages(MessageCreateParams.Builder builder,
                             List<CompletionRequest.Message> messages) {
        for (CompletionRequest.Message msg : messages) {
            boolean assistant = "assistant".equalsIgnoreCase(msg.role());
            boolean user = "user".equalsIgnoreCase(msg.role());
            if (!assistant && !user) {
                log.debug("Skipping message with unsupported role '{}'", msg.role());
                continue;
            }

            if (msg.hasBlocks()) {
                List<ContentBlockParam> blockParams = toSdkBlockParams(msg.blocks());
                if (assistant) {
                    builder.addAssistantMessageOfBlockParams(blockParams);
                } else {
                    builder.addUserMessageOfBlockParams(blockParams);
                }
            } else {
                if (assistant) {
                    builder.addAssistantMessage(msg.content());
                } else {
                    builder.addUserMessage(msg.content());
                }
            }
        }
    }

    /**
     * Maps provider-neutral {@link ContentBlock}s onto native SDK content block params —
     * the seam for the native tool-calling protocol (assistant {@code tool_use} blocks
     * echoed back + user {@code tool_result} blocks).
     */
    static List<ContentBlockParam> toSdkBlockParams(List<ContentBlock> blocks) {
        List<ContentBlockParam> params = new ArrayList<>(blocks.size());
        for (ContentBlock block : blocks) {
            if (block.isText()) {
                params.add(ContentBlockParam.ofText(
                        TextBlockParam.builder().text(block.text()).build()));
            } else if (block.isToolUse()) {
                ToolUseBlockParam.Input.Builder inputBuilder = ToolUseBlockParam.Input.builder();
                if (block.toolInput() != null) {
                    for (Map.Entry<String, Object> entry : block.toolInput().entrySet()) {
                        inputBuilder.putAdditionalProperty(entry.getKey(),
                                JsonValue.from(entry.getValue()));
                    }
                }
                params.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                        .id(block.toolUseId())
                        .name(block.toolName())
                        .input(inputBuilder.build())
                        .build()));
            } else if (block.isToolResult()) {
                ToolResultBlockParam.Builder resultBuilder = ToolResultBlockParam.builder()
                        .toolUseId(block.toolUseId())
                        .content(block.text() != null ? block.text() : "");
                if (block.isError() != null) {
                    resultBuilder.isError(block.isError());
                }
                params.add(ContentBlockParam.ofToolResult(resultBuilder.build()));
            } else {
                throw new IllegalArgumentException(
                        "Unsupported content block type: " + block.type());
            }
        }
        return params;
    }

    private Model resolveModel(String modelName) {
        String resolved = (modelName == null || modelName.isBlank())
                ? properties.getAnthropic().getModel()
                : modelName;
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalStateException(
                    "No LLM model configured: set substrate.llm.anthropic.model or pass a model on the request");
        }
        return Model.of(resolved);
    }

    private String extractText(List<com.anthropic.models.messages.ContentBlock> blocks) {
        StringBuilder sb = new StringBuilder();
        for (com.anthropic.models.messages.ContentBlock block : blocks) {
            block.text().ifPresent(textBlock -> sb.append(textBlock.text()));
        }
        return sb.toString();
    }

    /**
     * Maps the SDK's optional stop reason to its bare wire value (e.g. {@code tool_use},
     * {@code end_turn}). Homepoc called {@code Optional.toString()} here, leaking
     * {@code "Optional[tool_use]"} strings into the DTO — fixed during the port.
     */
    private static String mapStopReason(Optional<StopReason> stopReason) {
        return stopReason.map(StopReason::asString).orElse(null);
    }

    private TokenUsage mapUsage(com.anthropic.models.messages.Usage usage) {
        return new TokenUsage(
                (int) usage.inputTokens(),
                (int) usage.outputTokens()
        );
    }

    /**
     * Converts a JsonValue (from the SDK's tool input) into a plain Map.
     * Uses Jackson ObjectMapper for reliable conversion instead of the SDK visitor.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> convertJsonValue(JsonValue value) {
        try {
            String json = value.toString();
            Object parsed = JSON.readValue(json, Object.class);
            if (parsed instanceof Map) {
                return (Map<String, Object>) parsed;
            }
        } catch (Exception e) {
            log.warn("Failed to convert tool input JSON: {}", e.getMessage());
        }
        return Map.of();
    }
}
