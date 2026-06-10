package com.meridian.substrate.engine.ai.orchestration;

import java.util.List;

/**
 * The final result produced by the {@link ToolCallingOrchestrator}, including the
 * model's natural-language answer, a trace of all tool calls made, cumulative
 * token usage, and overall latency.
 *
 * <p><b>Port note:</b> {@code refusalDetected} is new in substrate — homepoc's
 * SafetyHandler was dead code and {@code ChatInteraction.refusalDetected} was
 * hardcoded {@code false}. The orchestrator now routes its final text through
 * {@code SafetyHandler}; when a refusal is detected, {@code finalText} carries
 * the configured fallback message and {@code refusalDetected} is {@code true}
 * so the chat layer (chunk C5) can persist it truthfully.
 *
 * @param finalText         the model's final text response after all tool calls are resolved
 *                          (the configured fallback message when {@code refusalDetected})
 * @param toolCalls         ordered list of tool invocations made during the conversation
 * @param totalInputTokens  cumulative input (prompt) tokens across all LLM calls
 * @param totalOutputTokens cumulative output (completion) tokens across all LLM calls
 * @param totalLatencyMs    wall-clock time in milliseconds for the entire orchestration
 * @param model             the model identifier used for the completion
 * @param refusalDetected   true when the model's final response was classified as a refusal
 */
public record OrchestratorResult(
    String finalText,
    List<ToolCallTrace> toolCalls,
    int totalInputTokens,
    int totalOutputTokens,
    long totalLatencyMs,
    String model,
    boolean refusalDetected
) {}
