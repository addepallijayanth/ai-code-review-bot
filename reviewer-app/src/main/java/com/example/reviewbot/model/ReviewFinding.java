package com.example.reviewbot.model;

/**
 * A problem found during a review, before it is numbered, posted, and stored.
 *
 * @param file       path in the new version of the file
 * @param line       last (or only) line the finding is about, in the new file
 * @param startLine  first line of a multi-line range, or null
 * @param suggestion replacement code for lines {@code startLine..line} (or {@code line}), or null
 * @param lineContent text of {@code line} in the diff; part of the fingerprint so a finding is
 *                    recognised again after unrelated edits shift its line number
 */
public record ReviewFinding(
        String file,
        int line,
        Integer startLine,
        Severity severity,
        Category category,
        String title,
        String message,
        String suggestion,
        String lineContent,
        Source source) {

    public enum Source { AGENT, SECRET_SCAN }

    public ReviewFinding withSuggestion(String newSuggestion) {
        return new ReviewFinding(file, line, startLine, severity, category, title, message, newSuggestion, lineContent, source);
    }
}
