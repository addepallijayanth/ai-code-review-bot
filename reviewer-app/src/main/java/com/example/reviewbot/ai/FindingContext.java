package com.example.reviewbot.ai;

import com.example.reviewbot.model.Category;
import com.example.reviewbot.model.Severity;

/**
 * Everything a chat command needs to talk about one finding.
 *
 * @param codeExcerpt numbered lines around the finding, already redacted
 * @param targetLines current text of the lines a suggestion would replace, already redacted
 * @param question    the comment that invoked the command (untrusted)
 */
public record FindingContext(
        String repo,
        String file,
        int line,
        Integer startLine,
        Severity severity,
        Category category,
        String title,
        String message,
        String suggestion,
        String codeExcerpt,
        String targetLines,
        String question) {

    public int firstLine() {
        return startLine != null ? startLine : line;
    }
}
