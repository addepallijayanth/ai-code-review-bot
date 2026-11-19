package com.example.reviewbot.eval;

import com.example.reviewbot.ai.AiUsage;
import com.example.reviewbot.ai.TokenBudget;
import com.example.reviewbot.github.dto.ChangedFile;
import com.example.reviewbot.model.ReviewFinding;
import com.example.reviewbot.model.Severity;
import com.example.reviewbot.review.NumberedFinding;
import com.example.reviewbot.review.PullRequestContext;
import com.example.reviewbot.review.RepoConfig;
import com.example.reviewbot.review.ReviewInput;
import com.example.reviewbot.review.ReviewResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs every dataset case through the real review pipeline (triage, agent, dedupe, severity
 * policy) against the Claude API and writes {@code report.md} and {@code results.json}.
 *
 * <pre>
 * java -jar eval/target/eval-1.0.0.jar [--dataset eval/dataset] [--out eval/results] [--case 002-sql-injection] [--parallel 4]
 * </pre>
 */
public final class EvalRunner {

    private EvalRunner() {
    }

    public static void main(String[] args) throws Exception {
        Path dataset = Path.of(option(args, "--dataset", "eval/dataset"));
        Path out = Path.of(option(args, "--out", "eval/results"));
        String only = option(args, "--case", null);
        int parallel = Integer.parseInt(option(args, "--parallel", "4"));

        List<EvalCase> cases = EvalCase.loadAll(dataset, only);
        if (cases.isEmpty()) {
            System.err.println("No cases found in " + dataset.toAbsolutePath());
            System.exit(2);
        }
        Instant startedAt = Instant.now();
        System.out.printf("Running %d case(s) from %s with %d in parallel%n", cases.size(), dataset, parallel);

        List<EvalReport.CaseResult> results = new ArrayList<>();
        try (PipelineFactory factory = new PipelineFactory();
             ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, parallel))) {
            List<Future<EvalReport.CaseResult>> futures = new ArrayList<>();
            for (EvalCase evalCase : cases) {
                futures.add(pool.submit(() -> run(factory, evalCase)));
            }
            for (Future<EvalReport.CaseResult> future : futures) {
                EvalReport.CaseResult result = get(future);
                results.add(result);
                System.out.println(describe(result));
            }
            EvalReport report = new EvalReport(startedAt, factory.ai().models().triage(), factory.ai().models().review(), results);
            write(out, report);
            System.out.println();
            System.out.println(report.headline());
            System.out.println("Report: " + out.resolve("report.md").toAbsolutePath());
            System.exit(results.stream().anyMatch(EvalReport.CaseResult::failed) ? 1 : 0);
        }
    }

    static EvalReport.CaseResult run(PipelineFactory factory, EvalCase evalCase) {
        long started = System.nanoTime();
        try {
            List<String> paths = evalCase.files().stream().map(ChangedFile::filename).toList();
            PullRequestContext pr = new PullRequestContext("eval/" + evalCase.id(), 1, evalCase.title(),
                    evalCase.description(), "eval", "head", "base");
            RepoConfig config = RepoConfig.defaults(Severity.MINOR, factory.review().defaults().maxComments());
            TokenBudget budget = new TokenBudget(factory.review().budget().maxTokensPerPr(),
                    factory.review().budget().maxCostPerPrUsd());
            ReviewInput input = new ReviewInput(pr, evalCase.files(), config,
                    new LocalRepoAccess(evalCase.repoDir(), paths), budget, Set.of(), null);

            ReviewResult result = factory.pipeline().run(input);

            List<ReviewFinding> findings = result.findings().stream().map(NumberedFinding::finding).toList();
            BigDecimal cost = result.usage().stream().map(AiUsage::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
            long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
            return new EvalReport.CaseResult(evalCase.id(), evalCase.title(), Scorer.score(evalCase.expected(), findings),
                    cost, latency, findings, null);
        } catch (RuntimeException e) {
            long latency = Duration.ofNanos(System.nanoTime() - started).toMillis();
            return new EvalReport.CaseResult(evalCase.id(), evalCase.title(), null, BigDecimal.ZERO, latency, List.of(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static EvalReport.CaseResult get(Future<EvalReport.CaseResult> future) throws InterruptedException {
        try {
            return future.get();
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    private static void write(Path out, EvalReport report) throws Exception {
        Files.createDirectories(out);
        Files.writeString(out.resolve("report.md"), report.toMarkdown());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("headline", report.headline());
        summary.put("startedAt", report.startedAt());
        summary.put("triageModel", report.triageModel());
        summary.put("reviewModel", report.reviewModel());
        summary.put("seededBugs", report.expected());
        summary.put("caught", report.caught());
        summary.put("recall", report.recall());
        summary.put("findings", report.reported());
        summary.put("correctFindings", report.correct());
        summary.put("precision", report.precision());
        summary.put("cleanCases", report.cleanCases());
        summary.put("quietCleanCases", report.quietCleanCases());
        summary.put("averageCostUsd", report.averageCost());
        summary.put("averageLatencyMs", report.averageLatencyMs());
        summary.put("totalCostUsd", report.totalCost());
        summary.put("cases", report.cases());
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
        json.writeValue(out.resolve("results.json").toFile(), summary);
    }

    private static String describe(EvalReport.CaseResult result) {
        if (result.failed()) {
            return String.format("  %-34s ERROR %s", result.id(), result.error());
        }
        Scorer.CaseScore score = result.score();
        return String.format("  %-34s %s caught %d/%d, %d finding(s), %d false positive(s), $%s, %.1fs",
                result.id(), score.passed() ? "PASS" : "MISS", score.caught(), score.expected(), score.reported(),
                score.falsePositives(), result.costUsd().toPlainString(), result.latencyMs() / 1000.0);
    }

    private static String option(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }
}
