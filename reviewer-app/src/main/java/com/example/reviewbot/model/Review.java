package com.example.reviewbot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;

/**
 * One review of one commit of a pull request. {@code (repo, pr_number, commit_sha)} is unique, which
 * is what makes webhook redeliveries and job retries safe.
 */
@Entity
@Table(name = "review")
public class Review {

    private static final int MAX_DETAIL_LENGTH = 2000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "installation_id", nullable = false)
    private Long installationId;

    @Column(nullable = false)
    private String repo;

    @Column(name = "pr_number", nullable = false)
    private int prNumber;

    @Column(name = "commit_sha", nullable = false, length = 64)
    private String commitSha;

    @Column(name = "delivery_id", length = 64)
    private String deliveryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReviewStatus status;

    @Column(name = "status_detail", columnDefinition = "text")
    private String statusDetail;

    @Column(name = "files_reviewed", nullable = false)
    private int filesReviewed;

    @Column(name = "files_skipped", nullable = false)
    private int filesSkipped;

    @Column(name = "github_review_id")
    private Long githubReviewId;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Review() {
    }

    public Review(Long installationId, String repo, int prNumber, String commitSha, String deliveryId,
                  Instant receivedAt, Instant startedAt) {
        this.installationId = installationId;
        this.repo = repo;
        this.prNumber = prNumber;
        this.commitSha = commitSha;
        this.deliveryId = deliveryId;
        this.receivedAt = receivedAt;
        this.startedAt = startedAt;
        this.status = ReviewStatus.RUNNING;
        this.attempts = 1;
    }

    /** Takes over a review left RUNNING by a crashed worker, or one that failed earlier. */
    public void restart(String deliveryId, Instant now) {
        this.deliveryId = deliveryId;
        this.status = ReviewStatus.RUNNING;
        this.statusDetail = null;
        this.startedAt = now;
        this.attempts++;
    }

    public void complete(int filesReviewed, int filesSkipped, Long githubReviewId, Instant now) {
        this.status = ReviewStatus.COMPLETED;
        this.filesReviewed = filesReviewed;
        this.filesSkipped = filesSkipped;
        this.githubReviewId = githubReviewId;
        this.completedAt = now;
        this.latencyMs = Duration.between(receivedAt, now).toMillis();
    }

    public void skip(String reason, Instant now) {
        this.status = ReviewStatus.SKIPPED;
        this.statusDetail = truncate(reason);
        this.completedAt = now;
    }

    public void fail(String reason, Instant now) {
        this.status = ReviewStatus.FAILED;
        this.statusDetail = truncate(reason);
        this.completedAt = now;
    }

    public boolean isFinished() {
        return status == ReviewStatus.COMPLETED || status == ReviewStatus.SKIPPED;
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_DETAIL_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_DETAIL_LENGTH);
    }

    public Long getId() {
        return id;
    }

    public Long getInstallationId() {
        return installationId;
    }

    public String getRepo() {
        return repo;
    }

    public int getPrNumber() {
        return prNumber;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public String getDeliveryId() {
        return deliveryId;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public String getStatusDetail() {
        return statusDetail;
    }

    public int getFilesReviewed() {
        return filesReviewed;
    }

    public int getFilesSkipped() {
        return filesSkipped;
    }

    public Long getGithubReviewId() {
        return githubReviewId;
    }

    public int getAttempts() {
        return attempts;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
