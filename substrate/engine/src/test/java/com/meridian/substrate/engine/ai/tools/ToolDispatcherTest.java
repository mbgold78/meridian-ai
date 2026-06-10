package com.meridian.substrate.engine.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meridian.substrate.common.ai.config.LlmProperties;
import com.meridian.substrate.engine.ai.persona.PersonaContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolDispatcherTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ToolDispatcher dispatcherFor(long timeoutMs, Tool... tools) {
        LlmProperties llmProperties = new LlmProperties();
        llmProperties.getTools().setDispatchTimeoutMs(timeoutMs);
        ToolExecutionProperties executionProperties = new ToolExecutionProperties();
        ToolRegistry registry = new ToolRegistry(List.of(tools), executionProperties, mapper);
        return new ToolDispatcher(registry, llmProperties, executionProperties, mapper);
    }

    @Test
    void successfulDispatch_returnsToolResult() {
        ToolDispatcher dispatcher = dispatcherFor(5000, tool("good_tool",
                (args, persona) -> mapper.createObjectNode().put("answer", 42)));

        JsonNode result = dispatcher.dispatch("good_tool",
                mapper.createObjectNode(), new PersonaContext());

        assertThat(result.path("answer").asInt()).isEqualTo(42);
        assertThat(result.has("error")).isFalse();
    }

    @Test
    void unknownTool_returnsErrorNode() {
        ToolDispatcher dispatcher = dispatcherFor(5000, tool("good_tool",
                (args, persona) -> mapper.createObjectNode()));

        JsonNode result = dispatcher.dispatch("missing_tool",
                mapper.createObjectNode(), new PersonaContext());

        assertThat(result.path("error").asBoolean()).isTrue();
        assertThat(result.path("message").asText()).contains("Unknown tool");
    }

    @Test
    void personaAccessDenied_returnsErrorNode_withoutExecuting() {
        boolean[] executed = {false};
        ToolDispatcher dispatcher = dispatcherFor(5000, tool("count_by",
                (args, persona) -> {
                    executed[0] = true;
                    return mapper.createObjectNode();
                }));

        // data_owner's default allowlist contains only get_system_profile.
        JsonNode result = dispatcher.dispatch("count_by",
                mapper.createObjectNode(), PersonaContext.ofLevel("data_owner"));

        assertThat(result.path("error").asBoolean()).isTrue();
        assertThat(result.path("message").asText()).contains("Access denied");
        assertThat(executed[0]).isFalse();
    }

    @Test
    void slowTool_timesOut_withErrorNode() {
        ToolDispatcher dispatcher = dispatcherFor(150, tool("slow_tool",
                (args, persona) -> {
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return mapper.createObjectNode();
                }));

        JsonNode result = dispatcher.dispatch("slow_tool",
                mapper.createObjectNode(), new PersonaContext());

        assertThat(result.path("error").asBoolean()).isTrue();
        assertThat(result.path("message").asText()).contains("timed out");
    }

    @Test
    void throwingTool_returnsExecutionFailureErrorNode() {
        ToolDispatcher dispatcher = dispatcherFor(5000, tool("broken_tool",
                (args, persona) -> {
                    throw new IllegalStateException("boom");
                }));

        JsonNode result = dispatcher.dispatch("broken_tool",
                mapper.createObjectNode(), new PersonaContext());

        assertThat(result.path("error").asBoolean()).isTrue();
        assertThat(result.path("message").asText()).contains("boom");
    }

    private interface Execution {
        JsonNode apply(JsonNode args, PersonaContext persona);
    }

    private Tool tool(String name, Execution execution) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "test tool " + name;
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
                return execution.apply(arguments, persona);
            }
        };
    }
}
