package com.example.reviewbot.eval;

import com.example.reviewbot.model.ReviewFinding;
import com.example.reviewbot.model.Severity;
import java.util.List;

/**
 * Compares what the pipeline reported with a case's seeded bugs.
 * <ul>
 *   <li>A seeded bug is caught when a finding on the same file overlaps its line (± tolerance).</li>
 *   <li>A finding is correct when it matches some seeded bug; every other finding is a false
 *       positive. Suggestions are opinions, so they count towards neither.</li>
 * </ul>
 */
final class Scorer {

    private Scorer() {
    }

    record CaseScore(int expected, int caught, int reported, int correct, int categoryMatches) {

        int falsePositives() {
            return reported - correct;
        }

        boolean passed() {
            return caught == expected && falsePositives() == 0;
        }
    }

    static CaseScore score(List<EvalCase.ExpectedIssue> expected, List<ReviewFinding> findings) {
        List<ReviewFinding> reported = findings.stream()
                .filter(f -> f.severity().isAtLeast(Severity.MINOR))
                .toList();
        boolean[] caught = new boolean[expected.size()];
        int correct = 0;
        int categoryMatches = 0;
        for (ReviewFinding finding : reported) {
            boolean matched = false;
            for (int i = 0; i < expected.size(); i++) {
                if (matches(finding, expected.get(i))) {
                    if (!caught[i] && finding.category().slug().equals(expected.get(i).category())) {
                        categoryMatches++;
                    }
                    caught[i] = true;
                    matched = true;
                }
            }
            if (matched) {
                correct++;
            }
        }
        int caughtCount = 0;
        for (boolean c : caught) {
            caughtCount += c ? 1 : 0;
        }
        return new CaseScore(expected.size(), caughtCount, reported.size(), correct, categoryMatches);
    }

    static boolean matches(ReviewFinding finding, EvalCase.ExpectedIssue issue) {
        if (!finding.file().equals(issue.file())) {
            return false;
        }
        int first = finding.startLine() == null ? finding.line() : finding.startLine();
        return issue.lines().stream()
                .anyMatch(line -> first <= line + issue.tolerance() && finding.line() >= line - issue.tolerance());
    }
}
