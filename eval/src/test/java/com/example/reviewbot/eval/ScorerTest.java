package com.example.reviewbot.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.reviewbot.model.Category;
import com.example.reviewbot.model.ReviewFinding;
import com.example.reviewbot.model.Severity;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScorerTest {

    private final List<EvalCase.ExpectedIssue> expected = List.of(
            new EvalCase.ExpectedIssue("src/UserDao.java", 18, List.of(), 3, "security", "SQL injection"));

    @Test
    void acceptsAnAlternativeLineForTheSameProblem() {
        List<EvalCase.ExpectedIssue> deadlock = List.of(
                new EvalCase.ExpectedIssue("src/Transfer.java", 10, List.of(30), 2, "concurrency", "lock order"));

        assertThat(Scorer.score(deadlock, List.of(finding("src/Transfer.java", 31, null, Severity.MAJOR))).caught()).isEqualTo(1);
        assertThat(Scorer.score(deadlock, List.of(finding("src/Transfer.java", 20, null, Severity.MAJOR))).caught()).isZero();
    }

    @Test
    void countsAFindingNearTheSeededLineAsCaught() {
        Scorer.CaseScore score = Scorer.score(expected, List.of(finding("src/UserDao.java", 20, null, Severity.CRITICAL)));

        assertThat(score.caught()).isEqualTo(1);
        assertThat(score.falsePositives()).isZero();
        assertThat(score.categoryMatches()).isEqualTo(1);
        assertThat(score.passed()).isTrue();
    }

    @Test
    void acceptsARangeThatCoversTheSeededLine() {
        Scorer.CaseScore score = Scorer.score(expected, List.of(finding("src/UserDao.java", 30, 12, Severity.MAJOR)));

        assertThat(score.caught()).isEqualTo(1);
    }

    @Test
    void countsOtherFindingsAsFalsePositivesButIgnoresSuggestions() {
        Scorer.CaseScore score = Scorer.score(expected, List.of(
                finding("src/UserDao.java", 40, null, Severity.MINOR),
                finding("src/Other.java", 18, null, Severity.MAJOR),
                finding("src/UserDao.java", 50, null, Severity.SUGGESTION)));

        assertThat(score.caught()).isZero();
        assertThat(score.reported()).isEqualTo(2);
        assertThat(score.falsePositives()).isEqualTo(2);
        assertThat(score.passed()).isFalse();
    }

    @Test
    void aCleanCasePassesOnlyWithoutFindings() {
        assertThat(Scorer.score(List.of(), List.of()).passed()).isTrue();
        assertThat(Scorer.score(List.of(), List.of(finding("a.py", 1, null, Severity.MINOR))).passed()).isFalse();
    }

    private static ReviewFinding finding(String file, int line, Integer startLine, Severity severity) {
        return new ReviewFinding(file, line, startLine, severity, Category.SECURITY, "t", "m", null, "", ReviewFinding.Source.AGENT);
    }
}
