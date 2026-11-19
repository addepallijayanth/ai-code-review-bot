package com.example.reviewbot.review;

import com.example.reviewbot.ai.AiUsage;
import com.example.reviewbot.config.QueueProperties;
import com.example.reviewbot.model.Finding;
import com.example.reviewbot.model.Review;
import com.example.reviewbot.model.ReviewFinding;
import com.example.reviewbot.model.ReviewStatus;
import com.example.reviewbot.model.UsageRecord;
import com.example.reviewbot.queue.ReviewJob;
import com.example.reviewbot.repository.FindingRepository;
import com.example.reviewbot.repository.ReviewRepository;
import com.example.reviewbot.repository.UsageRecordRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persistence for reviews, their findings and token usage. */
@Service
public class ReviewStore {

    private final ReviewRepository reviews;
    private final FindingRepository findings;
    private final UsageRecordRepository usage;
    private final QueueProperties queue;
    private final Clock clock;

    public ReviewStore(ReviewRepository reviews, FindingRepository findings, UsageRecordRepository usage,
                       QueueProperties queue, Clock clock) {
        this.reviews = reviews;
        this.findings = findings;
        this.usage = usage;
        this.queue = queue;
        this.clock = clock;
    }

    /**
     * Claims the review of a commit. Returns empty when the commit was already reviewed, or another
     * delivery is reviewing it right now. A retry of the same delivery, or a run left behind by a
     * crashed worker, takes the existing row over. Not transactional on purpose: the insert must
     * fail on its own if a concurrent delivery for the same commit wins the race.
     */
    public Optional<Review> begin(ReviewJob.ReviewPullRequest job, long installationRowId) {
        Instant now = clock.instant();
        Optional<Review> existing = reviews.findByRepoAndPrNumberAndCommitSha(job.repo(), job.prNumber(), job.headSha());
        if (existing.isPresent()) {
            Review review = existing.get();
            if (review.isFinished()) {
                return Optional.empty();
            }
            boolean sameDelivery = job.deliveryId().equals(review.getDeliveryId());
            boolean abandoned = review.getStartedAt() == null
                    || review.getStartedAt().isBefore(now.minus(queue.visibilityTimeout()));
            if (review.getStatus() == ReviewStatus.RUNNING && !sameDelivery && !abandoned) {
                return Optional.empty();
            }
            review.restart(job.deliveryId(), now);
            return Optional.of(reviews.save(review));
        }
        try {
            return Optional.of(reviews.saveAndFlush(new Review(installationRowId, job.repo(), job.prNumber(),
                    job.headSha(), job.deliveryId(), job.receivedAt(), now)));
        } catch (DataIntegrityViolationException e) {
            return Optional.empty();
        }
    }

    /** Stores the numbered findings before they are posted; replaces any left by an earlier attempt. */
    @Transactional
    public void saveFindings(long reviewId, List<NumberedFinding> numbered) {
        findings.deleteByReviewId(reviewId);
        Instant now = clock.instant();
        findings.saveAll(numbered.stream().map(n -> {
            ReviewFinding f = n.finding();
            return new Finding(reviewId, n.ordinal(), f.file(), f.line(), f.startLine(), f.severity(), f.category(),
                    f.title(), f.message(), f.suggestion(), n.fingerprint(), n.inline(), now);
        }).toList());
    }

    @Transactional
    public void markFindingsNotInline(long reviewId) {
        findings.markNotInline(reviewId);
    }

    @Transactional
    public void complete(long reviewId, ReviewResult result, Long githubReviewId) {
        Review review = reviews.findById(reviewId).orElseThrow();
        review.complete(result.filesInScope(), result.skippedFiles().size(), githubReviewId, clock.instant());
        recordUsage(reviewId, result.usage());
    }

    @Transactional
    public void skip(long reviewId, String reason, List<AiUsage> usages) {
        Review review = reviews.findById(reviewId).orElseThrow();
        review.skip(reason, clock.instant());
        recordUsage(reviewId, usages);
    }

    @Transactional
    public void fail(String repo, int prNumber, String commitSha, String reason) {
        reviews.findByRepoAndPrNumberAndCommitSha(repo, prNumber, commitSha)
                .filter(review -> !review.isFinished())
                .ifPresent(review -> review.fail(reason, clock.instant()));
    }

    /** Aggregates usage per model and purpose into {@code usage_record} rows. */
    @Transactional
    public void recordUsage(long reviewId, List<AiUsage> usages) {
        if (usages.isEmpty()) {
            return;
        }
        Map<String, UsageTotals> totals = new LinkedHashMap<>();
        for (AiUsage u : usages) {
            totals.computeIfAbsent(u.model() + "|" + u.purpose(), key -> new UsageTotals(u.model(), u.purpose().name())).add(u);
        }
        Instant now = clock.instant();
        usage.saveAll(totals.values().stream()
                .map(t -> new UsageRecord(reviewId, t.model, t.purpose, t.calls, t.tokensIn, t.tokensOut,
                        t.cacheRead, t.cacheWrite, t.cost, now))
                .toList());
    }

    @Transactional(readOnly = true)
    public Set<String> postedFingerprints(String repo, int prNumber) {
        return findings.findFingerprints(repo, prNumber, ReviewStatus.COMPLETED);
    }

    @Transactional(readOnly = true)
    public Optional<Review> lastCompleted(String repo, int prNumber) {
        return reviews.findFirstByRepoAndPrNumberAndStatusOrderByCompletedAtDesc(repo, prNumber, ReviewStatus.COMPLETED);
    }

    @Transactional(readOnly = true)
    public boolean skippedSince(String repo, int prNumber, String reason, Instant since) {
        return reviews.existsByRepoAndPrNumberAndStatusAndStatusDetailAndCompletedAtAfter(repo, prNumber,
                ReviewStatus.SKIPPED, reason, since);
    }

    private static final class UsageTotals {
        private final String model;
        private final String purpose;
        private int calls;
        private long tokensIn;
        private long tokensOut;
        private long cacheRead;
        private long cacheWrite;
        private BigDecimal cost = BigDecimal.ZERO;

        UsageTotals(String model, String purpose) {
            this.model = model;
            this.purpose = purpose;
        }

        void add(AiUsage u) {
            calls++;
            tokensIn += u.inputTokens();
            tokensOut += u.outputTokens();
            cacheRead += u.cacheReadTokens();
            cacheWrite += u.cacheWriteTokens();
            cost = cost.add(u.costUsd());
        }
    }
}
