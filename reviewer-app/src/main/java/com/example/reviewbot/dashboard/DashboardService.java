package com.example.reviewbot.dashboard;

import com.example.reviewbot.model.Severity;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Read-only aggregate queries for the dashboard. */
@Service
public class DashboardService {

    private static final int RECENT_REVIEWS = 25;

    private final JdbcClient jdbc;

    public DashboardService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public DashboardStats load() {
        long installations = count("SELECT COUNT(*) FROM installation WHERE uninstalled_at IS NULL");
        long repositories = count("""
                SELECT COUNT(*) FROM (
                    SELECT r.full_name FROM installation_repository r
                    JOIN installation i ON i.id = r.installation_id AND i.uninstalled_at IS NULL
                    UNION
                    SELECT repo FROM review) repos
                """);
        long pullRequests = count("SELECT COUNT(*) FROM (SELECT DISTINCT repo, pr_number FROM review WHERE status = 'COMPLETED') prs");
        long completed = count("SELECT COUNT(*) FROM review WHERE status = 'COMPLETED'");
        long failed = count("SELECT COUNT(*) FROM review WHERE status = 'FAILED'");

        Map<Severity, Long> bySeverity = new EnumMap<>(Severity.class);
        for (Severity severity : Severity.values()) {
            bySeverity.put(severity, 0L);
        }
        jdbc.sql("SELECT severity, COUNT(*) AS findings FROM finding GROUP BY severity")
                .query((rs, row) -> Map.entry(Severity.valueOf(rs.getString("severity")), rs.getLong("findings")))
                .list()
                .forEach(entry -> bySeverity.put(entry.getKey(), entry.getValue()));

        long suggestions = count("SELECT COUNT(*) FROM finding WHERE suggestion IS NOT NULL AND posted_inline");
        long accepted = count("SELECT COUNT(*) FROM finding WHERE accepted");

        UsageTotals usage = jdbc.sql("""
                SELECT COALESCE(SUM(cost_usd), 0)                                       AS cost_usd,
                       COALESCE(SUM(tokens_in + cache_read_tokens + cache_write_tokens), 0) AS prompt_tokens,
                       COALESCE(SUM(cache_read_tokens), 0)                              AS cache_read_tokens
                FROM usage_record
                """).query(UsageTotals.class).single();

        Double medianLatency = jdbc.sql("""
                SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms)
                FROM review WHERE status = 'COMPLETED' AND latency_ms IS NOT NULL
                """).query(Double.class).optional().orElse(null);

        var recent = jdbc.sql("""
                SELECT r.id, r.repo, r.pr_number, r.commit_sha, r.status, r.status_detail, r.files_reviewed,
                       r.latency_ms, r.received_at,
                       (SELECT COUNT(*) FROM finding f WHERE f.review_id = r.id)                      AS findings,
                       (SELECT COALESCE(SUM(u.cost_usd), 0) FROM usage_record u WHERE u.review_id = r.id) AS cost_usd
                FROM review r
                ORDER BY r.received_at DESC
                LIMIT :limit
                """).param("limit", RECENT_REVIEWS).query(DashboardStats.RecentReview.class).list();

        return new DashboardStats(installations, repositories, pullRequests, completed, failed, bySeverity,
                suggestions, accepted, usage.costUsd(), medianLatency, usage.promptTokens(), usage.cacheReadTokens(), recent);
    }

    private long count(String sql) {
        Long value = jdbc.sql(sql).query(Long.class).single();
        return value == null ? 0 : value;
    }

    record UsageTotals(BigDecimal costUsd, long promptTokens, long cacheReadTokens) {
    }
}
