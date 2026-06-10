package com.meridian.substrate.common.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the provider-neutral AI DTO contracts: factory defaults, the extended
 * content-block message model, and the ContentBlock type predicates.
 */
class LlmDtoTest {

    // -------------------------------------------------------------------------
    // CompletionRequest
    // -------------------------------------------------------------------------

    @Test
    void completionRequestOfAppliesDefaults() {
        CompletionRequest request = CompletionRequest.of(
                "test-model", "system", List.of(CompletionRequest.Message.user("hi")), 512);

        assertThat(request.model()).isEqualTo("test-model");
        assertThat(request.systemPrompt()).isEqualTo("system");
        assertThat(request.maxTokens()).isEqualTo(512);
        assertThat(request.temperature()).isEqualTo(0.7);
        assertThat(request.metadata()).isEmpty();
    }

    @Test
    void twoArgMessageConstructorHasNoBlocks() {
        CompletionRequest.Message message = new CompletionRequest.Message("user", "hello");

        assertThat(message.role()).isEqualTo("user");
        assertThat(message.content()).isEqualTo("hello");
        assertThat(message.blocks()).isNull();
        assertThat(message.hasBlocks()).isFalse();
    }

    @Test
    void messageTextFactories() {
        assertThat(CompletionRequest.Message.user("q").role()).isEqualTo("user");
        assertThat(CompletionRequest.Message.user("q").content()).isEqualTo("q");
        assertThat(CompletionRequest.Message.assistant("a").role()).isEqualTo("assistant");
        assertThat(CompletionRequest.Message.assistant("a").content()).isEqualTo("a");
    }

    @Test
    void messageBlockFactoriesCarryBlocks() {
        List<ContentBlock> assistantBlocks = List.of(
                ContentBlock.text("Let me check."),
                ContentBlock.toolUse("toolu_1", "find_systems", Map.of("domainId", "d1")));
        CompletionRequest.Message assistant = CompletionRequest.Message.assistant(assistantBlocks);

        assertThat(assistant.role()).isEqualTo("assistant");
        assertThat(assistant.content()).isNull();
        assertThat(assistant.hasBlocks()).isTrue();
        assertThat(assistant.blocks()).hasSize(2);

        CompletionRequest.Message user = CompletionRequest.Message.user(
                List.of(ContentBlock.toolResult("toolu_1", "{\"count\":3}")));

        assertThat(user.role()).isEqualTo("user");
        assertThat(user.hasBlocks()).isTrue();
        assertThat(user.blocks().get(0).isToolResult()).isTrue();
    }

    @Test
    void emptyBlockListIsNotBlockShaped() {
        CompletionRequest.Message message = new CompletionRequest.Message("user", "text", List.of());
        assertThat(message.hasBlocks()).isFalse();
    }

    // -------------------------------------------------------------------------
    // ContentBlock
    // -------------------------------------------------------------------------

    @Test
    void textBlockFactory() {
        ContentBlock block = ContentBlock.text("hello");

        assertThat(block.type()).isEqualTo("text");
        assertThat(block.text()).isEqualTo("hello");
        assertThat(block.toolUseId()).isNull();
        assertThat(block.toolName()).isNull();
        assertThat(block.toolInput()).isNull();
        assertThat(block.isError()).isNull();
        assertThat(block.isText()).isTrue();
        assertThat(block.isToolUse()).isFalse();
        assertThat(block.isToolResult()).isFalse();
    }

    @Test
    void toolUseBlockFactory() {
        ContentBlock block = ContentBlock.toolUse("toolu_42", "count_by", Map.of("field", "status"));

        assertThat(block.type()).isEqualTo("tool_use");
        assertThat(block.text()).isNull();
        assertThat(block.toolUseId()).isEqualTo("toolu_42");
        assertThat(block.toolName()).isEqualTo("count_by");
        assertThat(block.toolInput()).containsEntry("field", "status");
        assertThat(block.isToolUse()).isTrue();
        assertThat(block.isText()).isFalse();
        assertThat(block.isToolResult()).isFalse();
    }

    @Test
    void toolResultBlockFactories() {
        ContentBlock ok = ContentBlock.toolResult("toolu_42", "{\"rows\":2}");

        assertThat(ok.type()).isEqualTo("tool_result");
        assertThat(ok.toolUseId()).isEqualTo("toolu_42");
        assertThat(ok.text()).isEqualTo("{\"rows\":2}");
        assertThat(ok.isError()).isNull();
        assertThat(ok.isToolResult()).isTrue();
        assertThat(ok.isText()).isFalse();
        assertThat(ok.isToolUse()).isFalse();

        ContentBlock failed = ContentBlock.toolResult("toolu_42", "boom", true);
        assertThat(failed.isError()).isTrue();
        assertThat(failed.isToolResult()).isTrue();
    }

    // -------------------------------------------------------------------------
    // ToolCallRequest / ToolCallResponse / TokenUsage
    // -------------------------------------------------------------------------

    @Test
    void toolCallRequestOfAppliesDefaults() {
        ToolCallRequest request = ToolCallRequest.of(
                "test-model", "system",
                List.of(CompletionRequest.Message.user("hi")),
                List.of(new ToolCallRequest.ToolDefinition("t", "desc", Map.of("type", "object"))),
                256);

        assertThat(request.temperature()).isEqualTo(0.7);
        assertThat(request.metadata()).isEmpty();
        assertThat(request.tools()).hasSize(1);
        assertThat(request.tools().get(0).name()).isEqualTo("t");
    }

    @Test
    void toolCallResponseCarriesSharedContentBlocks() {
        ToolCallResponse response = new ToolCallResponse(
                "msg_1", "test-model", "tool_use",
                List.of(ContentBlock.text("thinking"),
                        ContentBlock.toolUse("toolu_1", "find_processes", Map.of())),
                new TokenUsage(10, 20));

        assertThat(response.content()).hasSize(2);
        assertThat(response.content().get(0).isText()).isTrue();
        assertThat(response.content().get(1).isToolUse()).isTrue();
        assertThat(response.stopReason()).isEqualTo("tool_use");
    }

    @Test
    void tokenUsageTotals() {
        assertThat(new TokenUsage(11, 31).totalTokens()).isEqualTo(42);
        assertThat(new TokenUsage(0, 0).totalTokens()).isZero();
    }
}
