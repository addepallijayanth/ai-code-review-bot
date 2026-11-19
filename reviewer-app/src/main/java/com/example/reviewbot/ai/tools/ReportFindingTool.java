package com.example.reviewbot.ai.tools;

import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
import com.example.reviewbot.github.FileDiff;
import com.example.reviewbot.model.Category;
import com.example.reviewbot.model.ReviewFinding;
import com.example.reviewbot.model.Severity;
import com.example.reviewbot.review.SecretRedactor;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@code report_finding(...)}: the only way a finding reaches the pull request. Findings arrive as
 * structured data (strict schema) and are validated against the diff right away, so the model can
 * correct a wrong line number in the same conversation instead of the comment being lost later.
 */
@Component
public class ReportFindingTool implements ReviewTool {

    public static final String NAME = "report_finding";
    static final int MAX_FINDINGS_PER_RUN = 25;
    static final int MAX_RANGE_LINES = 40;
    private static final int SNAP_DISTANCE = 2;
    private static final int MAX_TITLE = 120;
    private static final int MAX_MESSAGE = 2500;

    private static final List<String> SEVERITIES = Arrays.stream(Severity.values()).map(Severity::slug).toList();
    private static final List<String> CATEGORIES = Arrays.stream(Category.values()).map(Category::slug).toList();

    private static final BetaTool DEFINITION = ToolSchemas.strictTool(NAME,
            "Report one problem in the diff under review. Call it once per distinct issue, as soon as you are "
                    + "confident about it; several calls in one turn are fine. Only report problems you can tie to a "
                    + "specific line of the diff and explain concretely. Issues described in plain text instead of "
                    + "through this tool are discarded.",
            List.of("file", "line", "severity", "category", "title", "message"),
            ToolSchemas.string("file", "Path of the file under review, exactly as in the diff tag"),
            ToolSchemas.integer("line", "Line number in the new file (the left column of the diff) where the problem "
                    + "is. For a multi-line problem, the last line of the range."),
            ToolSchemas.integer("start_line", "First line of a multi-line range, inside the same hunk as line. Omit for "
                    + "a single line."),
            ToolSchemas.oneOf("severity", "critical: exploitable security hole, data loss or corruption, or a crash on a "
                    + "common path. major: wrong behaviour or a likely production failure under realistic conditions. "
                    + "minor: edge-case bug, unhandled error, or changed logic without tests. suggestion: a clearly "
                    + "better alternative with low risk; use sparingly.", SEVERITIES),
            ToolSchemas.oneOf("category", "The kind of problem", CATEGORIES),
            ToolSchemas.string("title", "Headline under 80 characters, e.g. \"SQL query built from request input\""),
            ToolSchemas.string("message", "What is wrong, when it happens (a concrete input or scenario), and how to "
                    + "fix it. Two to five sentences; Markdown allowed."),
            ToolSchemas.string("suggestion", "Optional replacement code for exactly the lines start_line..line (or just "
                    + "line): their complete new text with the original indentation. It becomes a one-click GitHub "
                    + "suggestion, so it must be a drop-in replacement. Omit it when the fix touches other lines or "
                    + "is not obvious."));

    record Input(
            String file,
            Integer line,
            @JsonProperty("start_line") Integer startLine,
            String severity,
            String category,
            String title,
            String message,
            String suggestion) {
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
        FileDiff diff = context.file();
        if (context.findingCount() >= MAX_FINDINGS_PER_RUN) {
            return ToolResult.error("You have already reported " + MAX_FINDINGS_PER_RUN + " findings for this file. "
                    + "Stop here unless a remaining issue is critical.");
        }
        String file = ToolSchemas.normalizePath(input.file());
        if (file == null || !file.equals(diff.path())) {
            return ToolResult.error("Findings can only be reported on " + diff.path() + ", the file under review. "
                    + "Other changed files are reviewed separately.");
        }
        if (input.line() == null) {
            return ToolResult.error("line is required.");
        }
        int line = input.line();
        Integer startLine = input.startLine();
        if (startLine != null && startLine > line) {
            int swap = line;
            line = startLine;
            startLine = swap;
        }
        if (startLine != null && startLine == line) {
            startLine = null;
        }
        if (!nearDiff(diff, line)) {
            return ToolResult.error("Line " + line + " is not part of the diff for " + diff.path()
                    + ". Lines you can report on: " + diff.commentableRanges()
                    + ". Use the number in the left column of the diff.");
        }
        if (startLine != null && line - startLine + 1 > MAX_RANGE_LINES) {
            return ToolResult.error("A range of " + (line - startLine + 1) + " lines is too long for a review comment. "
                    + "Report the most relevant line, or a range of at most " + MAX_RANGE_LINES + " lines.");
        }
        Optional<Severity> severity = Severity.fromSlug(input.severity());
        Optional<Category> category = Category.fromSlug(input.category());
        if (severity.isEmpty() || category.isEmpty()) {
            return ToolResult.error("severity must be one of " + SEVERITIES + " and category one of " + CATEGORIES + ".");
        }
        String title = clean(input.title(), MAX_TITLE);
        String message = clean(input.message(), MAX_MESSAGE);
        if (title.isEmpty() || message.isEmpty()) {
            return ToolResult.error("title and message must not be empty.");
        }

        String suggestion = normalizeSuggestion(input.suggestion());
        boolean suggestionDropped = false;
        if (suggestion != null && SecretRedactor.containsRedaction(suggestion)) {
            suggestion = null;
            suggestionDropped = true;
        }
        String lineContent = diff.rightSideLine(line).map(FileDiff.Line::content).orElse("");
        ReviewFinding finding = new ReviewFinding(diff.path(), line, startLine, severity.get(), category.get(),
                title, message, suggestion, lineContent, ReviewFinding.Source.AGENT);
        int number = context.addFinding(finding);
        String reply = "Recorded finding " + number + " (" + severity.get().slug() + ") at " + diff.path() + ":" + line + ".";
        if (suggestionDropped) {
            reply += " The suggestion was dropped because it contained redacted text; describe the fix in words instead.";
        }
        return ToolResult.ok(reply);
    }

    private static boolean nearDiff(FileDiff diff, int line) {
        for (int distance = 0; distance <= SNAP_DISTANCE; distance++) {
            if (diff.isCommentable(line - distance) || diff.isCommentable(line + distance)) {
                return true;
            }
        }
        return false;
    }

    private static String clean(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String stripped = value.strip();
        return stripped.length() <= maxLength ? stripped : stripped.substring(0, maxLength - 1) + "…";
    }

    /** Drops an empty suggestion and code fences the model may have wrapped around it. */
    static String normalizeSuggestion(String suggestion) {
        if (suggestion == null || suggestion.isBlank()) {
            return null;
        }
        String text = suggestion.replace("\r\n", "\n");
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int closingFence = text.lastIndexOf("```");
            if (firstNewline > 0 && closingFence > firstNewline) {
                text = text.substring(firstNewline + 1, closingFence);
            }
        }
        while (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }
        return text.isBlank() ? null : text;
    }
}
