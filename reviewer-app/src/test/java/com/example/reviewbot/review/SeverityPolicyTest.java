package com.example.reviewbot.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.reviewbot.model.Category;
import com.example.reviewbot.model.ReviewFinding;
import com.example.reviewbot.model.Severity;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SeverityPolicyTest {

    private final SeverityPolicy policy = new SeverityPolicy();

    private final List<ReviewFinding> findings = List.of(
            finding("b.java", 5, Severity.SUGGESTION, Category.MAINTAINABILITY),
            finding("a.java", 9, Severity.MINOR, Category.PERFORMANCE),
            finding("a.java", 3, Severity.MAJOR, Category.BUG),
            finding("c.java", 1, Severity.CRITICAL, Category.SECURITY),
            finding("a.java", 7, Severity.MINOR, Category.TESTS));

    @Test
    void dropsFindingsBelowTheMinimumAndSortsMostSevereFirst() {
        SeverityPolicy.Result result = policy.apply(findings, config(Severity.MINOR, Set.of()));

        assertThat(result.belowMinimum()).isEqualTo(1);
        assertThat(result.kept()).extracting(ReviewFinding::severity)
                .containsExactly(Severity.CRITICAL, Severity.MAJOR, Severity.MINOR, Severity.MINOR);
        assertThat(result.kept().get(2).line()).isEqualTo(7);
    }

    @Test
    void focusNarrowsMinorFindingsButNeverHidesSeriousOnes() {
        SeverityPolicy.Result result = policy.apply(findings, config(Severity.SUGGESTION, Set.of(Category.TESTS)));

        assertThat(result.outsideFocus()).isEqualTo(2);
        assertThat(result.kept()).extracting(ReviewFinding::category)
                .containsExactly(Category.SECURITY, Category.BUG, Category.TESTS);
    }

    private static RepoConfig config(Severity min, Set<Category> focus) {
        return new RepoConfig(min, List.of(), focus, Map.of(), 15, List.of());
    }

    private static ReviewFinding finding(String file, int line, Severity severity, Category category) {
        return new ReviewFinding(file, line, null, severity, category, "title", "message", null, "", ReviewFinding.Source.AGENT);
    }
}
