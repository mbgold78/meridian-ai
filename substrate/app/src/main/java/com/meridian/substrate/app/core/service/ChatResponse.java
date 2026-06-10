package com.meridian.substrate.app.core.service;

import com.meridian.substrate.engine.ai.orchestration.ToolCallTrace;

import java.util.List;

/**
 * Response from a synchronous chat interaction, including the assistant's message,
 * tool call traces, and token usage statistics.
 *
 * <p>Wire-contract note: this shape ({@code message, toolCalls, inputTokens,
 * outputTokens}) is the homepoc {@code POST /api/v1/chat} contract the ported
 * frontend depends on — do not add or rename fields without a frontend sync.
 * When the orchestrator detects a refusal, {@code message} carries the
 * configured fallback text; the refusal flag itself is persisted on
 * {@code ChatInteraction}, not exposed here.
 *
 * @param message      the assistant's response message
 * @param toolCalls    ordered list of tool invocations made during the conversation
 * @param inputTokens  total input tokens consumed
 * @param outputTokens total output tokens consumed
 */
public record ChatResponse(
    String message,
    List<ToolCallTrace> toolCalls,
    int inputTokens,
    int outputTokens
) {}
