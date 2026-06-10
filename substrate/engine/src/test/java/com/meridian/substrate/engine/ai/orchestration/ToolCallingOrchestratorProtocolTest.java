package com.meridian.substrate.engine.ai.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.ai.CompletionChunk;
import com.meridian.substrate.common.ai.CompletionRequest;
import com.meridian.substrate.common.ai.CompletionResponse;
import com.meridian.substrate.common.ai.ContentBlock;
import com.meridian.substrate.common.ai.LlmClient;
import com.meridian.substrate.common.ai.TokenUsage;
import com.meridian.substrate.common.ai.ToolCallRequest;
import com.meridian.substrate.common.ai.ToolCallResponse;
import com.meridian.substrate.common.ai.config.LlmProperties;
import com.meridian.substrate.engine.ai.cost.CostTracker;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import com.meridian.substrate.engine.ai.prompt.PersonaPromptLoader;
import com.meridian.substrate.engine.ai.safety.SafetyHandler;
import com.meridian.substrate.engine.ai.tools.Tool;
import com.meridian.substrate.engine.ai.tools.ToolDispatcher;
import com.meridian.substrate.engine.ai.tools.ToolExecutionProperties;
import com.meridian.substrate.engine.ai.tools.ToolRegistry;
import com.meridian.substrate.engine.dna.DnaConfig;
import com.meridian.substrate.engine.dna.DnaService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the orchestrator's NATIVE tool-calling protocol (port-map §2.3 fix) and
 * the enforced {@code maxCallsPerRequest} budget — plain unit test, no Spring.
 *
 * <p>Protocol contract: after a turn containing {@code tool_use} blocks, the
 * next request must carry (a) a block-shaped assistant message echoing the
 * model's content blocks verbatim, and (b) a block-shaped user message of
 * {@code tool_result} blocks keyed by {@code tool_use_id} — never homepoc's
 * text-flattened {@code "[Tool Result: …]"} user message.
 */
class ToolCallingOrchestratorProtocolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmProperties llmProperties = newLlmProperties();
    private final ScriptedLlmClient scripted = new ScriptedLlmClient();
    private final FakeTool fakeTool = new FakeTool(mapper);

    private static LlmProperties newLlmProperties() {
        LlmProperties props = new LlmProperties();
        props.getAnthropic().setModel("test-model");
        props.getAnthropic().setMaxTokens(512);
        props.getTools().setDispatchTimeoutMs(5000);
        return props;
    }

    private ToolCallingOrchestrator newOrchestrator(int maxCallsPerRequest, CostTracker costTracker) {
        llmProperties.getTools().setMaxCallsPerRequest(maxCallsPerRequest);
        ToolExecutionProperties executionProperties = new ToolExecutionProperties();
        ToolRegistry registry = new ToolRegistry(List.of(fakeTool), executionProperties, mapper);
        ToolDispatcher dispatcher = new ToolDispatcher(registry, llmProperties, executionProperties, mapper);
        return new ToolCallingOrchestrator(
                scripted, registry, dispatcher, llmProperties,
                new PersonaPromptLoader(), new DnaService(new DnaConfig()),
                new SafetyHandler(llmProperties), costTracker, mapper);
    }

    @Test
    void toolResults_travelAsNativeBlocks_notFlattenedText() {
        CostTracker costTracker = new CostTracker(llmProperties);
        ToolCallingOrchestrator orchestrator = newOrchestrator(5, costTracker);

        List<ContentBlock> firstTurn = List.of(
                ContentBlock.text("Let me check the data."),
                ContentBlock.toolUse("tu_1", "fake_tool", Map.of("q", "x")));
        scripted.enqueue(response(firstTurn, 10, 5));
        scripted.enqueue(response(List.of(ContentBlock.text("Final answer.")), 8, 4));

        OrchestratorResult result = orchestrator.orchestrate(
                "What systems exist?", new PersonaContext(), UUID.randomUUID());

        assertThat(result.finalText()).isEqualTo("Final answer.");
        assertThat(result.toolCalls()).hasSize(1);
        assertThat(result.toolCalls().get(0).toolName()).isEqualTo("fake_tool");
        assertThat(result.refusalDetected()).isFalse();
        assertThat(result.totalInputTokens()).isEqualTo(18);
        assertThat(result.totalOutputTokens()).isEqualTo(9);

        // Cost wiring: one recordUsage per LLM call.
        assertThat(costTracker.getSummary().totalCalls()).isEqualTo(2);
        assertThat(costTracker.getSummary().totalInputTokens()).isEqualTo(18);

        // Inspect the second request's conversation — the native protocol seam.
        assertThat(scripted.requests).hasSize(2);
        List<CompletionRequest.Message> messages = scripted.requests.get(1).messages();
        assertThat(messages).hasSize(3);

        CompletionRequest.Message original = messages.get(0);
        assertThat(original.role()).isEqualTo("user");
        assertThat(original.content()).isEqualTo("What systems exist?");

        CompletionRequest.Message assistantEcho = messages.get(1);
        assertThat(assistantEcho.role()).isEqualTo("assistant");
        assertThat(assistantEcho.hasBlocks())
                .as("assistant turn must echo the model's content blocks, not flattened text")
                .isTrue();
        assertThat(assistantEcho.blocks()).hasSize(2);
        assertThat(assistantEcho.blocks().get(0).isText()).isTrue();
        assertThat(assistantEcho.blocks().get(0).text()).isEqualTo("Let me check the data.");
        assertThat(assistantEcho.blocks().get(1).isToolUse()).isTrue();
        assertThat(assistantEcho.blocks().get(1).toolUseId()).isEqualTo("tu_1");
        assertThat(assistantEcho.blocks().get(1).toolName()).isEqualTo("fake_tool");

        CompletionRequest.Message toolResults = messages.get(2);
        assertThat(toolResults.role()).isEqualTo("user");
        assertThat(toolResults.hasBlocks())
                .as("tool results must travel as native tool_result blocks, "
                        + "never '[Tool Result: …]' text")
                .isTrue();
        assertThat(toolResults.content()).isNull();
        assertThat(toolResults.blocks()).hasSize(1);
        ContentBlock resultBlock = toolResults.blocks().get(0);
        assertThat(resultBlock.isToolResult()).isTrue();
        assertThat(resultBlock.toolUseId()).isEqualTo("tu_1");
        assertThat(resultBlock.text()).contains("\"ok\":true");
        assertThat(Boolean.TRUE.equals(resultBlock.isError())).isFalse();
    }

    @Test
    void maxCallsPerRequest_isEnforced_withinASingleTurn() {
        ToolCallingOrchestrator orchestrator = newOrchestrator(1, new CostTracker(llmProperties));

        scripted.enqueue(response(List.of(
                ContentBlock.toolUse("tu_1", "fake_tool", Map.of("q", "first")),
                ContentBlock.toolUse("tu_2", "fake_tool", Map.of("q", "second"))), 10, 5));
        scripted.enqueue(response(List.of(ContentBlock.text("Done.")), 4, 2));

        OrchestratorResult result = orchestrator.orchestrate(
                "go", new PersonaContext(), UUID.randomUUID());

        assertThat(result.toolCalls())
                .as("only the in-budget call is dispatched")
                .hasSize(1);

        // Protocol still satisfied: BOTH tool_use blocks received a tool_result.
        List<CompletionRequest.Message> messages = scripted.requests.get(1).messages();
        CompletionRequest.Message toolResults = messages.get(2);
        assertThat(toolResults.blocks()).hasSize(2);
        assertThat(toolResults.blocks().get(0).toolUseId()).isEqualTo("tu_1");
        assertThat(Boolean.TRUE.equals(toolResults.blocks().get(0).isError())).isFalse();
        ContentBlock overBudget = toolResults.blocks().get(1);
        assertThat(overBudget.toolUseId()).isEqualTo("tu_2");
        assertThat(overBudget.isError())
                .as("over-budget tool_use must be answered with an is_error tool_result")
                .isTrue();
        assertThat(overBudget.text()).contains("budget exceeded");
    }

    @Test
    void maxCallsPerRequest_isEnforced_acrossTurns() {
        ToolCallingOrchestrator orchestrator = newOrchestrator(2, new CostTracker(llmProperties));

        scripted.enqueue(response(List.of(ContentBlock.toolUse("tu_1", "fake_tool", Map.of())), 5, 2));
        scripted.enqueue(response(List.of(ContentBlock.toolUse("tu_2", "fake_tool", Map.of())), 5, 2));
        scripted.enqueue(response(List.of(ContentBlock.toolUse("tu_3", "fake_tool", Map.of())), 5, 2));
        scripted.enqueue(response(List.of(ContentBlock.text("Stopped using tools.")), 5, 2));

        OrchestratorResult result = orchestrator.orchestrate(
                "go", new PersonaContext(), UUID.randomUUID());

        assertThat(result.toolCalls())
                .as("dispatches must stop at the configured budget")
                .hasSize(2);
        assertThat(result.finalText()).isEqualTo("Stopped using tools.");

        // The third turn's tool_use was answered with a budget-exceeded error block.
        List<CompletionRequest.Message> lastConversation = scripted.requests.get(3).messages();
        CompletionRequest.Message lastToolResults = lastConversation.get(lastConversation.size() - 1);
        assertThat(lastToolResults.blocks()).hasSize(1);
        assertThat(lastToolResults.blocks().get(0).isError()).isTrue();
        assertThat(lastToolResults.blocks().get(0).text()).contains("budget exceeded");
    }

    @Test
    void refusalFinalText_isReplacedWithFallback_andFlagged() {
        ToolCallingOrchestrator orchestrator = newOrchestrator(5, new CostTracker(llmProperties));

        scripted.enqueue(response(List.of(
                ContentBlock.text("I cannot help with that request.")), 5, 2));

        OrchestratorResult result = orchestrator.orchestrate(
                "do something disallowed", new PersonaContext(), UUID.randomUUID());

        assertThat(result.refusalDetected()).isTrue();
        assertThat(result.finalText())
                .isEqualTo(llmProperties.getSafety().getRefusalFallbackMessage());
    }

    private static ToolCallResponse response(List<ContentBlock> content, int in, int out) {
        return new ToolCallResponse("id-" + UUID.randomUUID().toString().substring(0, 8),
                "test-model", "end_turn", content, new TokenUsage(in, out));
    }

    /** Scripted LlmClient: returns queued tool-call responses, records every request. */
    static class ScriptedLlmClient implements LlmClient {

        final List<ToolCallRequest> requests = new ArrayList<>();
        private final Deque<ToolCallResponse> queue = new ArrayDeque<>();

        void enqueue(ToolCallResponse response) {
            queue.add(response);
        }

        @Override
        public CompletionResponse complete(CompletionRequest request) {
            throw new UnsupportedOperationException("not used by orchestrator");
        }

        @Override
        public Flux<CompletionChunk> completeStream(CompletionRequest request) {
            throw new UnsupportedOperationException("not used by orchestrator");
        }

        @Override
        public ToolCallResponse completeWithTools(ToolCallRequest request) {
            requests.add(request);
            if (queue.isEmpty()) {
                throw new IllegalStateException("ScriptedLlmClient exhausted after "
                        + requests.size() + " requests");
            }
            return queue.poll();
        }
    }

    /** Minimal tool echoing its arguments back as JSON. */
    static class FakeTool implements Tool {

        private final ObjectMapper mapper;

        FakeTool(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public String name() {
            return "fake_tool";
        }

        @Override
        public String description() {
            return "Echoes its arguments back (test tool).";
        }

        @Override
        public JsonNode parametersSchema() {
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            schema.putObject("properties");
            return schema;
        }

        @Override
        public JsonNode execute(JsonNode arguments, PersonaContext persona) {
            ObjectNode result = mapper.createObjectNode();
            result.put("ok", true);
            result.set("echo", arguments);
            return result;
        }
    }
}
