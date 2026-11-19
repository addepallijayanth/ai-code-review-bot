package com.example.reviewbot.dashboard;

import com.example.reviewbot.model.Severity;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Everything the dashboard shows, read in one pass. */
public record DashboardStats(
        long installations,
        long repositories,
        long pullRequestsReviewed,
        long reviewsCompleted,
        long reviewsFailed,
        Map<Severity, Long> findingsBySeverity,
        long suggestionsPosted,
        long suggestionsAccepted,
        BigDecimal totalCostUsd,
        Double medianLatencyMs,
        long promptTokens,
        long cacheReadTokens,
        List<RecentReview> recentReviews) {

    public long totalFindings() {
        return findingsBySeverity.values().stream().mapToLong(Long::longValue).sum();
    }

    public BigDecimal averageCostPerReview() {
        if (reviewsCompleted == 0) {
            return BigDecimal.ZERO;
        }
        return totalCostUsd.divide(BigDecimal.valueOf(reviewsCompleted), 4, java.math.RoundingMode.HALF_UP);
    }

    /** Share of prompt tokens served from the prompt cache. */
    public double cacheHitRate() {
        return promptTokens == 0 ? 0 : (double) cacheReadTokens / promptTokens;
    }

    public double acceptanceRate() {
        return suggestionsPosted == 0 ? 0 : (double) suggestionsAccepted / suggestionsPosted;
    }

    public record RecentReview(
            long id,
            String repo,
            int prNumber,
            String commitSha,
            String status,
            String statusDetail,
            int filesReviewed,
            Long latencyMs,
            OffsetDateTime receivedAt,
            long findings,
            BigDecimal costUsd) {

        public String shortSha() {
            return commitSha == null ? "" : commitSha.substring(0, Math.min(7, commitSha.length()));
        }
    }
}
