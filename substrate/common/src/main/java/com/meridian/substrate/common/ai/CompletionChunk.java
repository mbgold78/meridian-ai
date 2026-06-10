package com.meridian.substrate.common.ai;

/**
 * A single chunk in a streaming completion response.
 *
 * <p>Text chunks carry a non-null {@code delta} (and null {@code stopReason}/{@code usage}).
 * The terminal chunk of a stream carries a null {@code delta} together with the final
 * {@code stopReason} and aggregate {@code usage} — consumers must null-check {@code delta}
 * before appending.
 */
public record CompletionChunk(
        String id,
        String delta,
        String stopReason,
        TokenUsage usage
) {}
