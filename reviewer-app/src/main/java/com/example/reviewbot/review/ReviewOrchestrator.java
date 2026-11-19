package com.example.reviewbot.review;

import com.example.reviewbot.config.ReviewProperties;
import com.example.reviewbot.github.GitHubApiException;
import com.example.reviewbot.github.GitHubClient;
import com.example.reviewbot.github.GitHubRepoAccess;
import com.example.reviewbot.github.RepoRef;
import com.example.reviewbot.github.dto.ChangedFile;
import com.example.reviewbot.github.dto.Comparison;
import com.example.reviewbot.github.dto.CreateReviewRequest;
import com.example.reviewbot.github.dto.PullRequestInfo;
import com.example.reviewbot.github.dto.ReviewInfo;
import com.example.reviewbot.installation.InstallationService;
import com.example.reviewbot.model.Review;
import com.example.reviewbot.queue.ReviewJob;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reviews one commit of a pull request end to end: claims it (idempotency), checks it is still
 * worth reviewing, loads the repository's settings, runs the pipeline, and posts a single GitHub
 * review with inline comments and a summary.
 */
@Service
public class ReviewOrchestrator {

    static final String BUDGET_SKIP_REASON = "Daily review budget for this installation reached";

    private static final Logger log = LoggerFactory.getLogger(ReviewOrchestrator.class);

    private final GitHubClient gitHub;
    private final ReviewStore store;
    private final InstallationService installations;
    private final RepoConfigLoader configLoader;
    private final ReviewPipeline pipeline;
    private final ReviewCommentFormatter formatter;
    private final SuggestionTracker suggestions;
    private final BudgetGuard budgetGuard;
    private final ReviewProperties properties;

    public ReviewOrchestrator(GitHubClient gitHub, ReviewStore store, InstallationService installations,
                              RepoConfigLoader configLoader, ReviewPipeline pipeline, ReviewCommentFormatter formatter,
                              SuggestionTracker suggestions, BudgetGuard budgetGuard, ReviewProperties properties) {
        this.gitHub = gitHub;
        this.store = store;
        this.installations = installations;
        this.configLoader = configLoader;
        this.pipeline = pipeline;
        this.formatter = formatter;
        this.suggestions = suggestions;
        this.budgetGuard = budgetGuard;
        this.properties = properties;
    }

    public void review(ReviewJob.ReviewPullRequest job, int attempt) {
        RepoRef repo = RepoRef.parse(job.repo());
        long installationRowId = installations.ensure(job.installationId(), job.accountLogin()).getId();
        Optional<Review> claimed = store.begin(job, installationRowId);
        if (claimed.isEmpty()) {
            log.info("Commit {} is already reviewed or being reviewed; nothing to do", shortSha(job.headSha()));
            return;
        }
        long reviewId = claimed.get().getId();

        PullRequestInfo pr = gitHub.getPullRequest(job.installationId(), repo, job.prNumber());
        if (!pr.isOpen()) {
            store.skip(reviewId, "Pull request is " + pr.state(), List.of());
            return;
        }
        if (properties.skipDrafts() && pr.draft()) {
            store.skip(reviewId, "Pull request is a draft", List.of());
            return;
        }
        if (!job.headSha().equals(pr.head().sha())) {
            store.skip(reviewId, "Superseded by " + shortSha(pr.head().sha()), List.of());
            return;
        }
        if (budgetGuard.dailyLimitReached(installationRowId)) {
            if (!store.skippedSince(repo.fullName(), job.prNumber(), BUDGET_SKIP_REASON, budgetGuard.startOfDay())) {
                gitHub.createIssueComment(job.installationId(), repo, job.prNumber(), formatter.budgetNotice());
            }
            store.skip(reviewId, BUDGET_SKIP_REASON, List.of());
            return;
        }

        RepoConfig config = configLoader.load(gitHub, job.installationId(), repo, pr.base().sha());
        int applied = suggestions.markApplied(job.installationId(), repo, job.prNumber(), job.headSha());
        if (applied > 0) {
            log.info("{} earlier suggestion(s) were applied by the author", applied);
        }

        Scope scope = incrementalScope(job, repo);
        List<ChangedFile> files = gitHub.listPullRequestFiles(job.installationId(), repo, job.prNumber());
        PullRequestContext context = new PullRequestContext(repo.fullName(), job.prNumber(), pr.title(), pr.body(),
                pr.user() == null ? "" : pr.user().login(), job.headSha(), pr.base().sha());
        ReviewInput input = new ReviewInput(context, files, config,
                new GitHubRepoAccess(gitHub, job.installationId(), repo, job.headSha()),
                budgetGuard.forPullRequest(),
                store.postedFingerprints(repo.fullName(), job.prNumber()),
                scope.paths());
        ReviewResult result = pipeline.run(input);

        if (result.filesInScope() == 0) {
            store.skip(reviewId, scope.paths() == null ? "No reviewable files" : "No reviewable changes since the last review",
                    result.usage());
            return;
        }

        store.saveFindings(reviewId, result.findings());
        Long githubReviewId = null;
        if (attempt > 1) {
            // A previous attempt may have posted the review and then failed before recording it.
            githubReviewId = gitHub.findReviewContaining(job.installationId(), repo, job.prNumber(),
                    FindingMarkers.review(reviewId)).map(ReviewInfo::id).orElse(null);
        }
        if (githubReviewId == null) {
            githubReviewId = post(job, repo, reviewId, result, config, scope.note());
        }
        store.complete(reviewId, result, githubReviewId);
        log.info("Posted review {}: {} finding(s), {} inline, {} file(s) reviewed, ${}", githubReviewId,
                result.findings().size(), result.inline().size(), result.filesInScope(), result.costUsd().toPlainString());
    }

    /** Called when a review job ran out of attempts. */
    public void markFailed(ReviewJob.ReviewPullRequest job, String reason) {
        store.fail(job.repo(), job.prNumber(), job.headSha(), reason);
    }

    private long post(ReviewJob.ReviewPullRequest job, RepoRef repo, long reviewId, ReviewResult result,
                      RepoConfig config, String scopeNote) {
        List<CreateReviewRequest.DraftComment> comments = result.inline().stream()
                .map(numbered -> CreateReviewRequest.DraftComment.onNewLines(numbered.anchor().path(),
                        numbered.anchor().line(), numbered.anchor().startLine(), formatter.inlineComment(reviewId, numbered)))
                .toList();
        String body = formatter.summary(reviewId, repo.fullName(), job.prNumber(), result, config, scopeNote, true);
        try {
            return gitHub.createReview(job.installationId(), repo, job.prNumber(),
                    new CreateReviewRequest(job.headSha(), body, CreateReviewRequest.COMMENT, comments));
        } catch (GitHubApiException e) {
            if (e.status() != 422 || comments.isEmpty()) {
                throw e;
            }
            // GitHub rejects the whole review if any comment position is invalid; fall back to the summary.
            log.warn("GitHub rejected the inline comments ({}); posting the review as a summary only", e.getMessage());
            store.markFindingsNotInline(reviewId);
            String summaryOnly = formatter.summary(reviewId, repo.fullName(), job.prNumber(), result, config, scopeNote, false);
            return gitHub.createReview(job.installationId(), repo, job.prNumber(),
                    new CreateReviewRequest(job.headSha(), summaryOnly, CreateReviewRequest.COMMENT, List.of()));
        }
    }

    /**
     * On a new push, only files changed since the last reviewed commit need another look. Falls
     * back to a full review after a force-push or when GitHub truncates the comparison.
     */
    private Scope incrementalScope(ReviewJob.ReviewPullRequest job, RepoRef repo) {
        if (!"synchronize".equals(job.action())) {
            return Scope.FULL;
        }
        Optional<Review> last = store.lastCompleted(repo.fullName(), job.prNumber());
        if (last.isEmpty() || last.get().getCommitSha().equals(job.headSha())) {
            return Scope.FULL;
        }
        String previous = last.get().getCommitSha();
        try {
            Comparison comparison = gitHub.compare(job.installationId(), repo, previous, job.headSha());
            if (comparison.isFastForward() && comparison.complete()) {
                return new Scope(Set.copyOf(comparison.files()),
                        "Incremental review: only files changed since " + shortSha(previous) + " were reviewed.");
            }
        } catch (GitHubApiException e) {
            log.info("Could not compare {}...{} ({}); reviewing every file", shortSha(previous), shortSha(job.headSha()), e.getMessage());
        }
        return Scope.FULL;
    }

    private static String shortSha(String sha) {
        return sha == null ? "?" : sha.substring(0, Math.min(7, sha.length()));
    }

    private record Scope(Set<String> paths, String note) {
        static final Scope FULL = new Scope(null, null);
    }
}
