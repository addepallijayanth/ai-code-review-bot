package com.example.reviewbot.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SuggestionTrackerTest {

    private final List<String> file = SuggestionTracker.normalizedLines("""
            public User find(String name) {
                String sql = "SELECT * FROM users WHERE name = ?";
                try (PreparedStatement s = connection.prepareStatement(sql)) {
                    s.setString(1, name);
                    return map(s.executeQuery());
                }
            }
            """);

    @Test
    void recognisesAnAppliedSuggestionRegardlessOfIndentation() {
        assertThat(SuggestionTracker.isApplied(file, """
                  try (PreparedStatement s = connection.prepareStatement(sql)) {
                      s.setString(1, name);""")).isTrue();
    }

    @Test
    void doesNotCountSuggestionsThatAreNotInTheFile() {
        assertThat(SuggestionTracker.isApplied(file, "Objects.requireNonNull(name);")).isFalse();
    }

    @Test
    void ignoresSuggestionsTooShortToTellApart() {
        assertThat(SuggestionTracker.isApplied(file, "}")).isFalse();
        assertThat(SuggestionTracker.isApplied(file, null)).isFalse();
    }
}
