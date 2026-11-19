package com.example.reviewbot.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum Severity {

    CRITICAL("critical", "Critical", "🔴", 4),
    MAJOR("major", "Major", "🟠", 3),
    MINOR("minor", "Minor", "🟡", 2),
    SUGGESTION("suggestion", "Suggestion", "💡", 1);

    private final String slug;
    private final String label;
    private final String emoji;
    private final int rank;

    Severity(String slug, String label, String emoji, int rank) {
        this.slug = slug;
        this.label = label;
        this.emoji = emoji;
        this.rank = rank;
    }

    public String slug() {
        return slug;
    }

    public String label() {
        return label;
    }

    public String emoji() {
        return emoji;
    }

    public int rank() {
        return rank;
    }

    public boolean isAtLeast(Severity other) {
        return rank >= other.rank;
    }

    public static Optional<Severity> fromSlug(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(s -> s.slug.equals(normalized)).findFirst();
    }
}
