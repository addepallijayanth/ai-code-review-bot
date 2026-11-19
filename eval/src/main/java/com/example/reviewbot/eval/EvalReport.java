package com.example.reviewbot.eval;

import com.example.reviewbot.model.ReviewFinding;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Aggregates case results into the headline metrics and renders them as Markdown. */
record EvalReport(Instant startedAt, String triageModel, String reviewModel, List<CaseResult> cases) {

    record CaseResult(String id, String title, Scorer.CaseScore score, BigDecimal costUsd, long latencyMs,
                      List<ReviewFinding> findings, String error) {

        boolean failed() {
            return error != null;
        }
    }

    int expected() {
        return cases.stream().filter(c -> !c.failed()).mapToInt(c -> c.score().expected()).sum();
    }

    int caught() {
        return cases.stream().filter(c -> !c.failed()).mapToInt(c -> c.score().caught()).sum();
    }

    int reported() {
        return cases.stream().filter(c -> !c.failed()).mapToInt(c -> c.score().reported()).sum();
    }

    int correct() {
        return cases.stream().filter(c -> !c.failed()).mapToInt(c -> c.score().correct()).sum();
    }

    long cleanCases() {
        return cases.stream().filter(c -> !c.failed() && c.score().expected() == 0).count();
    }

    long quietCleanCases() {
        return cases.stream().filter(c -> !c.failed() && c.score().expected() == 0 && c.score().reported() == 0).count();
    }

    double recall() {
        return expected() == 0 ? 0 : (double) caught() / expected();
    }

    double precision() {
        return reported() == 0 ? 1 : (double) correct() / reported();
    }

    BigDecimal totalCost() {
        return cases.stream().map(CaseResult::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    BigDecimal averageCost() {
        long completed = cases.stream().filter(c -> !c.failed()).count();
        return completed == 0 ? BigDecimal.ZERO : totalCost().divide(BigDecimal.valueOf(completed), 4, RoundingMode.HALF_UP);
    }

    long averageLatencyMs() {
        return Math.round(cases.stream().filter(c -> !c.failed()).mapToLong(CaseResult::latencyMs).average().orElse(0));
    }

    String headline() {
        return String.format(Locale.ENGLISH, "Catches %d/%d seeded bugs (%d%% recall), %d%% precision, avg $%s and %ds per PR.",
                caught(), expected(), Math.round(recall() * 100), Math.round(precision() * 100),
                averageCost().setScale(3, RoundingMode.HALF_UP).toPlainString(), Math.round(averageLatencyMs() / 1000.0));
    }

    String toMarkdown() {
        StringBuilder out = new StringBuilder("# ReviewBot evaluation\n\n");
        out.append(headline()).append("\n\n");
        out.append("| Metric | Value |\n|---|---|\n")
                .append("| Recall | ").append(caught()).append(" / ").append(expected()).append(" (").append(percent(recall())).append(") |\n")
                .append("| Precision | ").append(correct()).append(" / ").append(reported()).append(" (").append(percent(precision())).append(") |\n")
                .append("| Clean diffs with no findings | ").append(quietCleanCases()).append(" / ").append(cleanCases()).append(" |\n")
                .append("| Average cost per PR | $").append(averageCost().toPlainString()).append(" |\n")
                .append("| Average latency per PR | ").append(averageLatencyMs() / 1000.0).append("s |\n")
                .append("| Total cost | $").append(totalCost().setScale(4, RoundingMode.HALF_UP).toPlainString()).append(" |\n")
                .append("| Models | triage `").append(triageModel).append("`, review `").append(reviewModel).append("` |\n")
                .append("| Run started | ").append(startedAt).append(" |\n\n");

        out.append("## Cases\n\n| Case | Seeded | Caught | Findings | False positives | Cost | Latency | Result |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        for (CaseResult result : cases) {
            if (result.failed()) {
                out.append("| `").append(result.id()).append("` | | | | | | | ⚠️ error: ")
                        .append(result.error().replace("|", "\\|")).append(" |\n");
                continue;
            }
            Scorer.CaseScore score = result.score();
            out.append("| `").append(result.id()).append("` | ").append(score.expected())
                    .append(" | ").append(score.caught())
                    .append(" | ").append(score.reported())
                    .append(" | ").append(score.falsePositives())
                    .append(" | $").append(result.costUsd().setScale(4, RoundingMode.HALF_UP).toPlainString())
                    .append(" | ").append(result.latencyMs() / 1000.0).append("s")
                    .append(" | ").append(score.passed() ? "✅" : "❌").append(" |\n");
        }
        out.append("\n## Findings\n\n");
        for (CaseResult result : cases) {
            if (result.failed() || result.findings().isEmpty()) {
                continue;
            }
            out.append("### ").append(result.id()).append("\n\n");
            for (ReviewFinding finding : result.findings()) {
                out.append("- ").append(finding.severity().emoji()).append(" `").append(finding.file()).append(':')
                        .append(finding.line()).append("` ").append(finding.category().slug()).append(" — ")
                        .append(finding.title()).append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static String percent(double ratio) {
        return Math.round(ratio * 100) + "%";
    }
}
