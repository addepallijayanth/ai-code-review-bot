package com.example.reviewbot.ai;

import java.util.Locale;

public enum Risk {
    LOW,
    MEDIUM,
    HIGH;

    public static Risk parse(String value) {
        if (value == null) {
            return MEDIUM;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "low" -> LOW;
            case "high" -> HIGH;
            default -> MEDIUM;
        };
    }

    public String slug() {
        return name().toLowerCase(Locale.ROOT);
    }
}
