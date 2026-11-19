package com.example.reviewbot.command;

import com.example.reviewbot.ai.FindingAssistant;
import com.example.reviewbot.ai.FindingContext;
import com.example.reviewbot.ai.TokenBudget;
import com.example.reviewbot.config.GitHubAppProperties;
import com.example.reviewbot.github.GitHubClient;
import com.example.reviewbot.github.RepoRef;
import com.example.reviewbot.github.dto.ReviewCommentInfo;
import com.example.reviewbot.model.Finding;
import com.example.reviewbot.model.Review;
import com.example.reviewbot.model.ReviewStatus;
import com.example.reviewbot.queue.IdempotencyKeys;
import com.example.reviewbot.queue.ReviewJob;
import com.example.reviewbot.repository.FindingRepository;
import com.example.reviewbot.repository.ReviewRepository;
import com.example.reviewbot.review.BudgetGuard;
import com.example.reviewbot.review.FindingMarkers;
import com.example.reviewbot.review.ReviewCommentFormatter;
import com.example.reviewbot.review.ReviewStore;
import com.example.reviewbot.review.SecretRedactor;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Answers {@code @reviewbot explain} / {@code @reviewbot fix}. In an inline thread the finding is
 * identified by the hidden marker in the thread's first comment; on the conversation tab by its
 * number in the latest review summary. The reply goes back where the command was written.
 */
@Service
public class CommandHandler {

    private static final Logger log = LoggerFactory.getLogger(CommandHandler.class);
    private static final int CONTEXT_LINES = 25;
    private static final Duration HANDLED_TTL = Duration.ofDays(7);

    private final GitHubClient gitHub;
    private final FindingRepository findings;
    private final ReviewRepository reviews;
    private final FindingAssistant assistant;
    private final ReviewCommentFormatter formatter;
    private final ReviewStore store;
    private final BudgetGuard budgetGuard;
    private final SecretRedactor redactor;
    private final IdempotencyKeys idempotency;
    private final String botLogin;

    public CommandHandler(GitHubClient gitHub, FindingRepository findings, ReviewRepository reviews,
                          FindingAssistant assistant, ReviewCommentFormatter formatter, ReviewStore store,
                          BudgetGuard budgetGuard, SecretRedactor redactor, IdempotencyKeys idempotency,
                          GitHubAppProperties github) {
        this.gitHub = gitHub;
        this.findings = findings;
        this.reviews = reviews;
        this.assistant = assistant;
        this.formatter = formatter;
        this.store = store;
        this.budgetGuard = budgetGuard;
        this.redactor = redactor;
        this.idempotency = idempotency;
        this.botLogin = github.botLogin();
    }

    public void handle(ReviewJob.RunCommand job) {
        String key = "command:" + job.commentId();
        if (!idempotency.claim(key, HANDLED_TTL)) {
            log.info("Comment {} was already answered", job.commentId());
            return;
        }
        try {
            answer(job);
        } catch (RuntimeException e) {
            idempotency.release(key); // let the retry answer it
            throw e;
        }
    }

    private void answer(ReviewJob.RunCommand job) {
        RepoRef repo = RepoRef.parse(job.repo());
        Optional<Target> resolved = resolve(job, repo);
        if (resolved.isEmpty()) {
            reply(job, repo, formatter.commandHelp());
            return;
        }
        Target target = resolved.get();
        Finding finding = target.finding();
        List<String> lines = gitHub.getFileContent(job.installationId(), repo, finding.getFile(), target.review().getCommitSha())
                .map(content -> redactor.redactLines(Arrays.asList(content.replace("\r\n", "\n").split("\n", -1))))
                .orElse(List.of());
        FindingContext context = new FindingContext(repo.fullName(), finding.getFile(), target.endLine(),
                target.startLine() == target.endLine() ? null : target.startLine(),
                finding.getSeverity(), finding.getCategory(), finding.getTitle(), finding.getMessage(),
                finding.getSuggestion(), excerpt(lines, target.startLine(), target.endLine()),
                slice(lines, target.startLine(), target.endLine()), job.question());

        TokenBudget budget = budgetGuard.forCommand();
        try {
            String body = switch (job.command()) {
                case EXPLAIN -> assistant.explain(context, budget)
                        .map(formatter::explainReply)
                        .orElseGet(formatter::commandDeclined);
                case FIX -> assistant.fix(context, budget)
                        .map(proposal -> formatter.fixReply(proposal, job.source() == CommandSource.REVIEW_THREAD, context))
                        .orElseGet(formatter::commandDeclined);
            };
            reply(job, repo, body);
        } finally {
            store.recordUsage(target.review().getId(), budget.usages());
        }
    }

    private Optional<Target> resolve(ReviewJob.RunCommand job, RepoRef repo) {
        if (job.source() == CommandSource.REVIEW_THREAD && job.inReplyToId() != null) {
            ReviewCommentInfo parent = gitHub.getReviewComment(job.installationId(), repo, job.inReplyToId());
            if (parent == null || parent.user() == null || !botLogin.equalsIgnoreCase(parent.user().login())) {
                return Optional.empty();
            }
            Optional<FindingMarkers.FindingRef> ref = FindingMarkers.parseFinding(parent.body());
            if (ref.isEmpty()) {
                return Optional.empty();
            }
            Optional<Finding> finding = findings.findByReviewIdAndOrdinal(ref.get().reviewId(), ref.get().ordinal());
            Optional<Review> review = finding.flatMap(f -> reviews.findById(f.getReviewId()));
            if (finding.isEmpty() || review.isEmpty() || !belongsTo(review.get(), job)) {
                return Optional.empty();
            }
            // A suggestion in a thread replaces the lines the thread is anchored to.
            int end = parent.line() != null ? parent.line() : finding.get().getLine();
            int start = parent.startLine() != null ? parent.startLine() : end;
            return Optional.of(new Target(finding.get(), review.get(), start, end));
        }
        if (job.findingNumber() != null) {
            Optional<Review> latest = reviews.findFirstByRepoAndPrNumberAndStatusOrderByCompletedAtDesc(
                    job.repo(), job.prNumber(), ReviewStatus.COMPLETED);
            return latest.flatMap(review -> findings.findByReviewIdAndOrdinal(review.getId(), job.findingNumber())
                    .map(f -> new Target(f, review, f.getStartLine() != null ? f.getStartLine() : f.getLine(), f.getLine())));
        }
        return Optional.empty();
    }

    private void reply(ReviewJob.RunCommand job, RepoRef repo, String body) {
        if (job.source() == CommandSource.REVIEW_THREAD && job.inReplyToId() != null) {
            gitHub.replyToReviewComment(job.installationId(), repo, job.prNumber(), job.inReplyToId(), body);
        } else {
            gitHub.createIssueComment(job.installationId(), repo, job.prNumber(), body);
        }
    }

    private static boolean belongsTo(Review review, ReviewJob.RunCommand job) {
        return review.getRepo().equals(job.repo()) && review.getPrNumber() == job.prNumber();
    }

    /** Numbered lines around the finding; the target lines are marked with {@code >}. */
    static String excerpt(List<String> lines, int start, int end) {
        if (lines.isEmpty()) {
            return "(file content unavailable)";
        }
        int from = Math.max(1, start - CONTEXT_LINES);
        int to = Math.min(lines.size(), end + CONTEXT_LINES);
        StringBuilder out = new StringBuilder();
        for (int number = from; number <= to; number++) {
            String label = String.valueOf(number);
            out.append(number >= start && number <= end ? '>' : ' ')
                    .append(" ".repeat(Math.max(0, 5 - label.length()))).append(label).append("| ")
                    .append(lines.get(number - 1)).append('\n');
        }
        return out.toString();
    }

    static String slice(List<String> lines, int start, int end) {
        if (lines.isEmpty() || start > lines.size()) {
            return "";
        }
        return String.join("\n", lines.subList(Math.max(0, start - 1), Math.min(lines.size(), end)));
    }

    private record Target(Finding finding, Review review, int startLine, int endLine) {
    }
}
