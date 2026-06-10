package com.meridian.substrate.common.ai;

import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.ToolUseBlockParam;

import com.meridian.substrate.common.ai.config.LlmProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the DTO -> Anthropic SDK request mapping (no API calls), plus
 * degraded-mode behavior when no SDK client bean exists (API key absent).
 */
class SdkAnthropicClientMappingTest {

    private LlmProperties properties;
    private SdkAnthropicClient client;

    @BeforeEach
    void setUp() {
        properties = new LlmProperties();
        properties.getAnthropic().setModel("claude-test-model");
        // null SDK client: mapping helpers don't need it; also exercises degraded mode
        client = new SdkAnthropicClient(properties, (com.anthropic.client.AnthropicClient) null);
    }

    // -------------------------------------------------------------------------
    // buildParams — plain-text messages
    // -------------------------------------------------------------------------

    @Test
    void buildParamsMapsModelSystemMaxTokensAndTextMessages() {
        CompletionRequest request = CompletionRequest.of(
                null, // model unset on request -> falls back to configured model
                "You are a test.",
                List.of(CompletionRequest.Message.user("hello"),
                        CompletionRequest.Message.assistant("hi there"),
                        CompletionRequest.Message.user("question")),
                512);

        MessageCreateParams params = client.buildParams(request);

        assertThat(params.model().asString()).isEqualTo("claude-test-model");
        assertThat(params.maxTokens()).isEqualTo(512L);
        assertThat(params.system()).isPresent();
        assertThat(params.system().get().string()).contains("You are a test.");

        List<MessageParam> messages = params.messages();
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).role()).isEqualTo(MessageParam.Role.USER);
        assertThat(messages.get(0).content().string()).contains("hello");
        assertThat(messages.get(1).role()).isEqualTo(MessageParam.Role.ASSISTANT);
        assertThat(messages.get(1).content().string()).contains("hi there");
        assertThat(messages.get(2).role()).isEqualTo(MessageParam.Role.USER);
    }

    @Test
    void buildParamsRequestModelOverridesConfiguredModel() {
        CompletionRequest request = CompletionRequest.of(
                "claude-other-model", null,
                List.of(CompletionRequest.Message.user("hello")), 64);

        MessageCreateParams params = client.buildParams(request);

        assertThat(params.model().asString()).isEqualTo("claude-other-model");
        assertThat(params.system()).isEmpty();
    }

    @Test
    void buildParamsSkipsUnsupportedRoles() {
        CompletionRequest request = CompletionRequest.of(
                null, null,
                List.of(new CompletionRequest.Message("system", "sneaky"),
                        CompletionRequest.Message.user("hello")),
                64);

        MessageCreateParams params = client.buildParams(request);

        assertThat(params.messages()).hasSize(1);
        assertThat(params.messages().get(0).role()).isEqualTo(MessageParam.Role.USER);
    }

    @Test
    void buildParamsFailsLoudlyWhenNoModelAnywhere() {
        properties.getAnthropic().setModel(null);
        CompletionRequest request = CompletionRequest.of(
                null, null, List.of(CompletionRequest.Message.user("hello")), 64);

        assertThatThrownBy(() -> client.buildParams(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("substrate.llm.anthropic.model");
    }

    // -------------------------------------------------------------------------
    // buildParams — content-block messages (native tool-calling protocol)
    // -------------------------------------------------------------------------

    @Test
    void buildParamsMapsAssistantToolUseAndUserToolResultBlocks() {
        CompletionRequest request = CompletionRequest.of(
                null, null,
                List.of(
                        CompletionRequest.Message.user("How many systems?"),
                        CompletionRequest.Message.assistant(List.of(
                                ContentBlock.text("Let me check."),
                                ContentBlock.toolUse("toolu_1", "find_systems",
                                        Map.of("domainId", "d-1")))),
                        CompletionRequest.Message.user(List.of(
                                ContentBlock.toolResult("toolu_1", "{\"count\":3}")))),
                256);

        MessageCreateParams params = client.buildParams(request);
        List<MessageParam> messages = params.messages();
        assertThat(messages).hasSize(3);

        // assistant turn: text + tool_use as native block params
        MessageParam assistantTurn = messages.get(1);
        assertThat(assistantTurn.role()).isEqualTo(MessageParam.Role.ASSISTANT);
        assertThat(assistantTurn.content().blockParams()).isPresent();
        List<ContentBlockParam> assistantBlocks = assistantTurn.content().blockParams().get();
        assertThat(assistantBlocks).hasSize(2);
        assertThat(assistantBlocks.get(0).isText()).isTrue();
        assertThat(assistantBlocks.get(0).asText().text()).isEqualTo("Let me check.");
        assertThat(assistantBlocks.get(1).isToolUse()).isTrue();
        ToolUseBlockParam toolUse = assistantBlocks.get(1).asToolUse();
        assertThat(toolUse.id()).isEqualTo("toolu_1");
        assertThat(toolUse.name()).isEqualTo("find_systems");
        assertThat(toolUse.input()._additionalProperties())
                .containsEntry("domainId", JsonValue.from("d-1"));

        // user turn: native tool_result block referencing the tool_use id
        MessageParam toolResultTurn = messages.get(2);
        assertThat(toolResultTurn.role()).isEqualTo(MessageParam.Role.USER);
        assertThat(toolResultTurn.content().blockParams()).isPresent();
        List<ContentBlockParam> resultBlocks = toolResultTurn.content().blockParams().get();
        assertThat(resultBlocks).hasSize(1);
        assertThat(resultBlocks.get(0).isToolResult()).isTrue();
        ToolResultBlockParam toolResult = resultBlocks.get(0).asToolResult();
        assertThat(toolResult.toolUseId()).isEqualTo("toolu_1");
        assertThat(toolResult.content()).isPresent();
        assertThat(toolResult.content().get().string()).contains("{\"count\":3}");
        assertThat(toolResult.isError()).isEmpty();
    }

    @Test
    void toSdkBlockParamsMarksErroredToolResults() {
        List<ContentBlockParam> params = SdkAnthropicClient.toSdkBlockParams(
                List.of(ContentBlock.toolResult("toolu_9", "tool blew up", true)));

        ToolResultBlockParam result = params.get(0).asToolResult();
        assertThat(result.toolUseId()).isEqualTo("toolu_9");
        assertThat(result.isError()).contains(true);
    }

    @Test
    void toSdkBlockParamsRejectsUnknownBlockType() {
        ContentBlock bogus = new ContentBlock("thinking", "x", null, null, null, null);

        assertThatThrownBy(() -> SdkAnthropicClient.toSdkBlockParams(List.of(bogus)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("thinking");
    }

    // -------------------------------------------------------------------------
    // buildToolParams — tool definitions
    // -------------------------------------------------------------------------

    @Test
    void buildToolParamsSpreadsToolSchemasAndKeepsMessages() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("domainId", Map.of("type", "string")));
        schema.put("required", List.of("domainId"));

        ToolCallRequest request = ToolCallRequest.of(
                null, "You can call tools.",
                List.of(CompletionRequest.Message.user("count systems")),
                List.of(new ToolCallRequest.ToolDefinition(
                        "find_systems", "Finds systems in a domain", schema)),
                128);

        MessageCreateParams params = client.buildToolParams(request);

        assertThat(params.model().asString()).isEqualTo("claude-test-model");
        assertThat(params.maxTokens()).isEqualTo(128L);
        assertThat(params.messages()).hasSize(1);
        assertThat(params.system().get().string()).contains("You can call tools.");

        assertThat(params.tools()).isPresent();
        List<ToolUnion> tools = params.tools().get();
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).isTool()).isTrue();
        com.anthropic.models.messages.Tool tool = tools.get(0).tool().get();
        assertThat(tool.name()).isEqualTo("find_systems");
        assertThat(tool.description()).contains("Finds systems in a domain");
        assertThat(tool.inputSchema()._additionalProperties())
                .containsEntry("type", JsonValue.from("object"))
                .containsKey("properties")
                .containsKey("required");
    }

    @Test
    void buildToolParamsDefaultsNullSchemaToObjectType() {
        ToolCallRequest request = ToolCallRequest.of(
                null, null,
                List.of(CompletionRequest.Message.user("go")),
                List.of(new ToolCallRequest.ToolDefinition("no_schema_tool", "desc", null)),
                64);

        MessageCreateParams params = client.buildToolParams(request);

        com.anthropic.models.messages.Tool tool = params.tools().get().get(0).tool().get();
        assertThat(tool.inputSchema()._additionalProperties())
                .containsEntry("type", JsonValue.from("object"));
    }

    // -------------------------------------------------------------------------
    // Degraded mode — API key absent => disabled, calls fail loudly, no boot failure
    // -------------------------------------------------------------------------

    @Test
    void degradedModeReportsUnavailable() {
        assertThat(client.isAvailable()).isFalse();
    }

    @Test
    void degradedModeCompleteThrowsLlmUnavailable() {
        CompletionRequest request = CompletionRequest.of(
                null, null, List.of(CompletionRequest.Message.user("hello")), 64);

        assertThatThrownBy(() -> client.complete(request))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("substrate.llm.anthropic.api-key");
    }

    @Test
    void degradedModeCompleteWithToolsThrowsLlmUnavailable() {
        ToolCallRequest request = ToolCallRequest.of(
                null, null, List.of(CompletionRequest.Message.user("hello")), List.of(), 64);

        assertThatThrownBy(() -> client.completeWithTools(request))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void degradedModeStreamErrorsWithLlmUnavailable() {
        CompletionRequest request = CompletionRequest.of(
                null, null, List.of(CompletionRequest.Message.user("hello")), 64);

        assertThatThrownBy(() -> client.completeStream(request).blockFirst())
                .isInstanceOf(LlmUnavailableException.class);
    }
}
