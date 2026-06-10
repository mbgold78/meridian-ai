package com.meridian.substrate.engine.ai.orchestration;

import java.util.Map;

/**
 * Records the details of a single tool invocation during an orchestrated
 * multi-turn conversation, including arguments, result, and timing.
 *
 * @param toolName  the name of the tool that was invoked
 * @param arguments the arguments passed to the tool
 * @param result    the serialized result returned by the tool
 * @param latencyMs wall-clock time in milliseconds for the tool execution
 */
public record ToolCallTrace(
    String toolName,
    Map<String, Object> arguments,
    String result,
    long latencyMs
) {}
