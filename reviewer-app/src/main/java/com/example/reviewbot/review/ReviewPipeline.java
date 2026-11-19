package com.example.reviewbot.review;

import com.example.reviewbot.ai.AiException;
import com.example.reviewbot.ai.TokenBudget;
import com.example.reviewbot.ai.TriageService;
import com.example.reviewbot.ai.TriageVerdict;
import com.example.reviewbot.ai.agent.AgentResult;
import com.example.reviewbot.ai.agent.AgentTask;
import com.example.reviewbot.ai.agent.ReviewAgent;
import com.example.reviewbot.ai.agent.ReviewSession;
import com.example.reviewbot.config.AiProperties;
import com.example.reviewbot.config.ReviewProperties;
import com.example.reviewbot.github.CommentAnchor;
import com.example.reviewbot.github.DiffParser;
import com.example.reviewbot.github.FileDiff;
import com.example.reviewbot.github.PositionMapper;
import com.example.reviewbot.github.dto.ChangedFile;
import com.example.reviewbot.model.Category;
import com.example.reviewbot.model.ReviewFinding;
import com.example.reviewbot.model.Severity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * The review itself, independent of GitHub: filter → secret scan and redaction → triage →
 * per-file agent runs (in parallel) → dedupe → severity policy → position mapping.
 */
@Service
public class ReviewPipeline {

    private static final Logger log = LoggerFactory.getLogger(ReviewPipeline.class);
    private static final int MAX_FILES_IN_CONTEXT = 100;

    private final FileFilter fileFilter;
    private final DiffParser diffParser;
    private final SecretRedactor redactor;
    private final Chunker chunker;
    private final TriageService triage;
    private final ReviewAgent agent;
    private final FindingDeduplicator deduplicator;
    private final SeverityPolicy severityPolicy;
    private final PositionMapper positionMapper;
    private final ReviewProperties reviewProperties;
    private final AiProperties aiProperties;
    private final ExecutorService executor;

    public ReviewPipeline(FileFilter fileFilter, DiffParser diffParser, SecretRedactor redactor, Chunker chunker,
                          TriageService triage, ReviewAgent agent, FindingDeduplicator deduplicator,
                          SeverityPolicy severityPolicy, PositionMapper positionMapper,
                          ReviewProperties reviewProperties, AiProperties aiProperties,
                          @Qualifier("reviewExecutor") ExecutorService executor) {
        this.fileFilter = fileFilter;
        this.diffParser = diffParser;
        this.redactor = redactor;
        this.chunker = chunker;
        this.triage = triage;
        this.agent = agent;
        this.deduplicator = deduplicator;
        this.severityPolicy = severityPolicy;
        this.positionMapper = positionMapper;
        this.reviewProperties = reviewProperties;
        this.aiProperties = aiProperties;
        this.executor = executor;
    }

    public ReviewResult run(ReviewInput input) {
        TokenBudget budget = input.budget();
        FileFilter.Result filtered = fileFilter.filter(input.files(), input.config());

        Map<String, FileDiff> diffs = new LinkedHashMap<>();
        for (ChangedFile file : filtered.accepted()) {
            if (input.scope() != null && !input.scope().contains(file.filename())) {
                continue;
            }
            FileDiff diff = diffParser.parse(file.filename(), file.patch());
            if (!diff.isEmpty()) {
                diffs.put(file.filename(), diff);
            }
        }

        // Secrets are reported directly and redacted before anything is sent to the model.
        List<ReviewFinding> findings = new ArrayList<>();
        Map<String, FileDiff> redacted = new LinkedHashMap<>();
        for (FileDiff diff : diffs.values()) {
            FileDiff clean = redactor.redact(diff);
            redacted.put(diff.path(), clean);
            for (SecretRedactor.SecretHit hit : redactor.scan(diff)) {
                findings.add(secretFinding(clean, hit));
            }
        }

        Map<String, TriageVerdict> verdicts = redacted.isEmpty() ? Map.of()
                : triage.triage(input.pullRequest(), List.copyOf(redacted.values()), budget);
        List<FileDiff> deepReview = redacted.values().stream()
                .filter(diff -> verdicts.getOrDefault(diff.path(), TriageVerdict.failOpen(diff.path())).deepReview())
                .toList();
        log.info("Triage: {} of {} file(s) need a deep review", deepReview.size(), redacted.size());

        ReviewSession session = new ReviewSession(input.repo(), budget, changedPaths(input.files()), redactor, aiProperties.agent());
        List<String> changedFiles = summarize(input.files());
        List<AgentTask> tasks = new ArrayList<>();
        for (FileDiff file : deepReview) {
            for (Chunker.Chunk chunk : chunker.split(file)) {
                tasks.add(new AgentTask(input.pullRequest(), file, chunk.diff(), chunk.part(), chunk.parts(),
                        verdicts.get(file.path()), input.config(), changedFiles));
            }
        }

        List<String> notes = new ArrayList<>();
        List<TaskOutcome> outcomes = runAgents(tasks, session);
        AiException firstUnavailable = null;
        int unavailable = 0;
        for (TaskOutcome outcome : outcomes) {
            if (outcome.result() != null) {
                findings.addAll(outcome.result().findings());
                if (outcome.result().outcome() != AgentResult.Outcome.COMPLETED && outcome.result().note() != null) {
                    notes.add("`" + outcome.result().path() + "`: " + outcome.result().note());
                }
            } else {
                notes.add("`" + outcome.task().file().path() + "` was not reviewed: " + outcome.error().getMessage());
                if (outcome.error().isRetryable()) {
                    unavailable++;
                    firstUnavailable = firstUnavailable == null ? outcome.error() : firstUnavailable;
                }
            }
        }
        if (!tasks.isEmpty() && unavailable == tasks.size()) {
            // Nothing got reviewed: fail the job so it is retried later instead of posting an empty review.
            throw new AiException("The Claude API was unavailable for every file of the review", true, firstUnavailable);
        }

        List<ReviewFinding> unique = deduplicator.dedupe(findings);
        List<ReviewFinding> fresh = new ArrayList<>();
        int repeated = 0;
        for (ReviewFinding finding : unique) {
            if (input.previousFingerprints().contains(deduplicator.fingerprint(finding))) {
                repeated++;
            } else {
                fresh.add(finding);
            }
        }
        SeverityPolicy.Result policy = severityPolicy.apply(fresh, input.config());

        List<NumberedFinding> numbered = new ArrayList<>();
        int inline = 0;
        int ordinal = 1;
        for (ReviewFinding finding : policy.kept()) {
            CommentAnchor anchor = null;
            FileDiff diff = diffs.get(finding.file());
            if (diff != null && inline < input.config().maxComments()) {
                anchor = positionMapper.map(diff, finding.line(), finding.startLine()).orElse(null);
                if (anchor != null) {
                    inline++;
                }
            }
            numbered.add(new NumberedFinding(ordinal++, finding, anchor, deduplicator.fingerprint(finding)));
        }
        if (budget.isExhausted()) {
            notes.add("The token budget for this pull request was reached, so the review may be incomplete.");
        }
        return new ReviewResult(numbered, filtered.skipped(), diffs.size(), deepReview.size(), repeated,
                policy.belowMinimum() + policy.outsideFocus(), notes, budget.usages(), budget.isExhausted());
    }

    private List<TaskOutcome> runAgents(List<AgentTask> tasks, ReviewSession session) {
        if (tasks.isEmpty()) {
            return List.of();
        }
        Semaphore permits = new Semaphore(Math.max(1, reviewProperties.maxConcurrentFiles()));
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        List<CompletableFuture<TaskOutcome>> futures = tasks.stream()
                .map(task -> CompletableFuture.supplyAsync(() -> runAgent(task, session, permits, mdc), executor))
                .toList();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private TaskOutcome runAgent(AgentTask task, ReviewSession session, Semaphore permits, Map<String, String> mdc) {
        if (mdc != null) {
            MDC.setContextMap(mdc);
        }
        try {
            permits.acquire();
            try {
                return new TaskOutcome(task, agent.review(task, session), null);
            } finally {
                permits.release();
            }
        } catch (AiException e) {
            log.warn("Agent failed on {}: {}", task.file().path(), e.getMessage());
            return new TaskOutcome(task, null, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new TaskOutcome(task, null, new AiException("Interrupted", true, e));
        } catch (RuntimeException e) {
            log.error("Agent crashed on {}", task.file().path(), e);
            return new TaskOutcome(task, null, new AiException("Internal error: " + e.getMessage(), false, e));
        } finally {
            MDC.clear();
        }
    }

    private static ReviewFinding secretFinding(FileDiff redactedDiff, SecretRedactor.SecretHit hit) {
        String lineContent = redactedDiff.rightSideLine(hit.line()).map(FileDiff.Line::content).orElse("");
        return new ReviewFinding(redactedDiff.path(), hit.line(), null, Severity.CRITICAL, Category.SECURITY,
                "Hardcoded " + hit.label(),
                "This line adds what looks like a real " + hit.label() + ". Anyone who can read the repository, "
                        + "including its history, can use it. Remove it, revoke and rotate the credential, and load "
                        + "it at runtime from the environment or a secret manager.",
                null, lineContent, ReviewFinding.Source.SECRET_SCAN);
    }

    private static Set<String> changedPaths(List<ChangedFile> files) {
        return files.stream().map(ChangedFile::filename).collect(Collectors.toSet());
    }

    private static List<String> summarize(List<ChangedFile> files) {
        List<String> lines = new ArrayList<>();
        for (ChangedFile file : files.subList(0, Math.min(files.size(), MAX_FILES_IN_CONTEXT))) {
            lines.add(file.filename() + " (" + file.status() + ", +" + file.additions() + " -" + file.deletions() + ")");
        }
        if (files.size() > MAX_FILES_IN_CONTEXT) {
            lines.add("... and " + (files.size() - MAX_FILES_IN_CONTEXT) + " more files");
        }
        return lines;
    }

    private record TaskOutcome(AgentTask task, AgentResult result, AiException error) {
    }
}
