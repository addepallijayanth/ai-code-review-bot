package com.example.reviewbot.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** What kind of problem a finding describes. Slugs are what {@code .reviewbot.yml} and the model use. */
public enum Category {

    SECURITY("security", "Security"),
    BUG("bug", "Bug"),
    NULL_SAFETY("null-safety", "Null safety"),
    CONCURRENCY("concurrency", "Concurrency"),
    RESOURCE_LEAK("resource-leak", "Resource leak"),
    ERROR_HANDLING("error-handling", "Error handling"),
    PERFORMANCE("performance", "Performance"),
    TESTS("tests", "Tests"),
    MAINTAINABILITY("maintainability", "Maintainability");

    private final String slug;
    private final String label;

    Category(String slug, String label) {
        this.slug = slug;
        this.label = label;
    }

    public String slug() {
        return slug;
    }

    public String label() {
        return label;
    }

    public static Optional<Category> fromSlug(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return Arrays.stream(values()).filter(c -> c.slug.equals(normalized)).findFirst();
    }
}
