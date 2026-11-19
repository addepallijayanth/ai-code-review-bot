package com.example.reviewbot.ai.tools;

import static com.example.reviewbot.ai.PromptText.attribute;
import static com.example.reviewbot.ai.PromptText.untrusted;

import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** {@code read_file(path)}: full file at the head commit for context the diff does not show. */
@Component
public class ReadFileTool implements ReviewTool {

    public static final String NAME = "read_file";

    private static final BetaTool DEFINITION = ToolSchemas.strictTool(NAME,
            "Read a file from the repository at the pull request's head commit, with line numbers. Use it when "
                    + "judging the diff needs code it does not show: the rest of a method, callers, class fields, "
                    + "imports, or configuration. Long files come back in windows; pass start_line and end_line "
                    + "to read a specific range.",
            List.of("path"),
            ToolSchemas.string("path", "Repository-relative path, e.g. src/main/java/com/acme/OrderService.java"),
            ToolSchemas.integer("start_line", "First line to return (1-based). Optional."),
            ToolSchemas.integer("end_line", "Last line to return. Optional."));

    record Input(String path, @JsonProperty("start_line") Integer startLine, @JsonProperty("end_line") Integer endLine) {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public BetaTool definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(BetaToolUseBlock call, ToolContext context) {
        Input input = call.input(Input.class);
        String path = ToolSchemas.normalizePath(input.path());
        if (path == null) {
            return ToolResult.error("Invalid path. Use a repository-relative path such as src/app/Main.java.");
        }
        Optional<String> content;
        try {
            content = context.session().repo().readFile(path);
        } catch (RuntimeException e) {
            return ToolResult.error("Could not read " + path + ": " + e.getMessage());
        }
        if (content.isEmpty()) {
            return ToolResult.error("There is no file at " + path + " in the head commit. Check the path, "
                    + "or use search_code to find where the code lives.");
        }
        String text = content.get();
        if (text.indexOf('\0') >= 0) {
            return ToolResult.error(path + " is a binary file.");
        }
        List<String> lines = Arrays.asList(text.replace("\r\n", "\n").split("\n", -1));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines = lines.subList(0, lines.size() - 1);
        }
        if (lines.isEmpty()) {
            return ToolResult.ok("<file path=\"" + attribute(path) + "\" total_lines=\"0\">\n</file>\n(The file is empty.)");
        }
        List<String> redacted = context.session().redactor().redactLines(lines);
        int total = redacted.size();
        int maxLines = context.session().limits().readFileMaxLines();
        int start = clamp(input.startLine() == null ? 1 : input.startLine(), 1, total);
        int requestedEnd = input.endLine() == null ? start + maxLines - 1 : Math.max(start, input.endLine());
        int end = Math.min(Math.min(requestedEnd, total), start + maxLines - 1);

        StringBuilder out = new StringBuilder();
        out.append("<file path=\"").append(attribute(path)).append("\" lines=\"").append(start).append('-').append(end)
                .append("\" total_lines=\"").append(total).append("\">\n");
        for (int number = start; number <= end; number++) {
            String label = String.valueOf(number);
            out.append(" ".repeat(Math.max(0, 6 - label.length()))).append(label).append("| ")
                    .append(untrusted(redacted.get(number - 1))).append('\n');
        }
        out.append("</file>");
        if (start > 1 || end < total) {
            out.append("\n(Showing lines ").append(start).append('-').append(end).append(" of ").append(total)
                    .append(". Pass start_line and end_line to read another part.)");
        }
        return ToolResult.ok(out.toString());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
