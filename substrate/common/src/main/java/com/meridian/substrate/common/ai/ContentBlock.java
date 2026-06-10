package com.meridian.substrate.common.ai;

import java.util.Map;

/**
 * A provider-neutral content block — text, a tool-use invocation, or a tool result.
 *
 * <p>Used in two places:
 * <ul>
 *   <li>{@link ToolCallResponse#content()} — the assistant's response blocks
 *       ({@code text} and {@code tool_use});</li>
 *   <li>{@link CompletionRequest.Message#blocks()} — block-shaped conversation
 *       messages, enabling the native tool-calling protocol (assistant
 *       {@code tool_use} blocks echoed back verbatim, followed by user
 *       {@code tool_result} blocks) instead of text-flattened tool results.</li>
 * </ul>
 *
 * <p>Field usage by type:
 * <ul>
 *   <li>{@code text}: {@link #text()} carries the text.</li>
 *   <li>{@code tool_use}: {@link #toolUseId()}, {@link #toolName()}, {@link #toolInput()}.</li>
 *   <li>{@code tool_result}: {@link #toolUseId()} references the originating
 *       {@code tool_use} block; {@link #text()} carries the result content;
 *       {@link #isError()} optionally marks a failed tool execution.</li>
 * </ul>
 */
public record ContentBlock(
        String type,
        String text,
        String toolUseId,
        String toolName,
        Map<String, Object> toolInput,
        Boolean isError
) {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_TOOL_USE = "tool_use";
    public static final String TYPE_TOOL_RESULT = "tool_result";

    /**
     * Creates a text content block.
     */
    public static ContentBlock text(String text) {
        return new ContentBlock(TYPE_TEXT, text, null, null, null, null);
    }

    /**
     * Creates a tool-use content block.
     */
    public static ContentBlock toolUse(String id, String name, Map<String, Object> input) {
        return new ContentBlock(TYPE_TOOL_USE, null, id, name, input, null);
    }

    /**
     * Creates a tool-result content block answering the tool-use block with the given id.
     */
    public static ContentBlock toolResult(String toolUseId, String content) {
        return new ContentBlock(TYPE_TOOL_RESULT, content, toolUseId, null, null, null);
    }

    /**
     * Creates a tool-result content block, optionally marking the tool execution as failed.
     */
    public static ContentBlock toolResult(String toolUseId, String content, boolean isError) {
        return new ContentBlock(TYPE_TOOL_RESULT, content, toolUseId, null, null, isError);
    }

    public boolean isText() {
        return TYPE_TEXT.equals(type);
    }

    public boolean isToolUse() {
        return TYPE_TOOL_USE.equals(type);
    }

    public boolean isToolResult() {
        return TYPE_TOOL_RESULT.equals(type);
    }
}
