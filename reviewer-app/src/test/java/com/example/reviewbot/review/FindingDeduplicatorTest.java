package com.example.reviewbot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.reviewbot.model.Category;
import com.example.reviewbot.model.ReviewFinding;
import com.example.reviewbot.model.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

class FindingDeduplicatorTest {

    private final FindingDeduplicator deduplicator = new FindingDeduplicator();

    @Test
    void keepsTheMostSevereOfDuplicatesAndAdoptsAMissingSuggestion() {
        ReviewFinding fromScanner = finding(12, Severity.CRITICAL, Category.SECURITY, "Hardcoded AWS access key", null)
                .withSuggestion(null);
        ReviewFinding fromModel = finding(12, Severity.MAJOR, Category.SECURITY, "AWS key committed to source", "key = env()");

        List<ReviewFinding> result = deduplicator.dedupe(List.of(fromModel, fromScanner));

        assertThat(result).singleElement().satisfies(f -> {
            assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(f.suggestion()).isEqualTo("key = env()");
        });
    }

    @Test
    void mergesSimilarTitlesOnAdjacentLinesEvenAcrossCategories() {
        List<ReviewFinding> result = deduplicator.dedupe(List.of(
                finding(30, Severity.MAJOR, Category.BUG, "Null user dereferenced after lookup", null),
                finding(31, Severity.MAJOR, Category.NULL_SAFETY, "User lookup result dereferenced without null check", null)));

        assertThat(result).hasSize(1);
    }

    @Test
    void keepsDistinctProblems() {
        List<ReviewFinding> result = deduplicator.dedupe(List.of(
                finding(10, Severity.MAJOR, Category.BUG, "Off-by-one in pagination", null),
                finding(11, Severity.MINOR, Category.TESTS, "No test covers the new pagination branch", null),
                finding(40, Severity.MAJOR, Category.BUG, "Off-by-one in pagination", null)));

        assertThat(result).hasSize(3);
    }

    @Test
    void fingerprintFollowsTheLineContentNotItsNumber() {
        ReviewFinding before = finding(12, Severity.MAJOR, Category.BUG, "Wrong comparison", null);
        ReviewFinding shifted = new ReviewFinding(before.file(), 57, null, Severity.MINOR, Category.BUG,
                "Comparison uses ==", "other words", null, "  if (a == b) {  ", ReviewFinding.Source.AGENT);

        assertThat(deduplicator.fingerprint(shifted)).isEqualTo(deduplicator.fingerprint(before)).hasSize(64);
        assertThat(deduplicator.fingerprint(finding(12, Severity.MAJOR, Category.SECURITY, "x", null)))
                .isNotEqualTo(deduplicator.fingerprint(before));
    }

    private static ReviewFinding finding(int line, Severity severity, Category category, String title, String suggestion) {
        return new ReviewFinding("src/App.java", line, null, severity, category, title, "message", suggestion,
                "if (a == b) {", ReviewFinding.Source.AGENT);
    }
}
