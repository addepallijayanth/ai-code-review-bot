package com.example.reviewbot.ai.tools;

/** Text returned to the model as a {@code tool_result}; {@code error} sets {@code is_error}. */
public record ToolResult(String content, boolean error) {

    public static ToolResult ok(String content) {
        return new ToolResult(content, false);
    }

    public static ToolResult error(String content) {
        return new ToolResult(content, true);
    }
}
