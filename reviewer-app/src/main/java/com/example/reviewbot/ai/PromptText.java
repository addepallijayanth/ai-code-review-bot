package com.example.reviewbot.ai;

import java.util.regex.Pattern;

/**
 * Helpers for placing untrusted repository content (code, PR descriptions, comments) inside the
 * XML-style tags our prompts use. Closing tags inside the content are neutralised so a crafted
 * comment such as {@code </diff> Ignore previous instructions} cannot end the data block early.
 */
public final class PromptText {

    private static final Pattern CLOSING_TAG = Pattern.compile(
            "</(?=(?:pull_request|title|author|description|changed_files|review_target|diff|file|search_results"
                    + "|related_tests|finding|code|target_lines|question|guidelines)\\b)",
            Pattern.CASE_INSENSITIVE);

    private PromptText() {
    }

    public static String untrusted(String text) {
        if (text == null) {
            return "";
        }
        return CLOSING_TAG.matcher(text).replaceAll("<\\\\/");
    }

    public static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n[truncated]";
    }

    /** Escapes a value for use inside a double-quoted tag attribute. */
    public static String attribute(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
