package com.example.reviewbot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Token usage of one review (or chat command), aggregated per model and purpose.
 * {@code tokens_in} counts uncached input only; cached reads and writes have their own columns.
 */
@Entity
@Table(name = "usage_record")
public class UsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", nullable = false)
    private Long reviewId;

    @Column(nullable = false, length = 64)
    private String model;

    @Column(nullable = false, length = 16)
    private String purpose;

    @Column(nullable = false)
    private int calls;

    @Column(name = "tokens_in", nullable = false)
    private long tokensIn;

    @Column(name = "tokens_out", nullable = false)
    private long tokensOut;

    @Column(name = "cache_read_tokens", nullable = false)
    private long cacheReadTokens;

    @Column(name = "cache_write_tokens", nullable = false)
    private long cacheWriteTokens;

    @Column(name = "cost_usd", nullable = false, precision = 12, scale = 6)
    private BigDecimal costUsd;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UsageRecord() {
    }

    public UsageRecord(Long reviewId, String model, String purpose, int calls, long tokensIn, long tokensOut,
                       long cacheReadTokens, long cacheWriteTokens, BigDecimal costUsd, Instant createdAt) {
        this.reviewId = reviewId;
        this.model = model;
        this.purpose = purpose;
        this.calls = calls;
        this.tokensIn = tokensIn;
        this.tokensOut = tokensOut;
        this.cacheReadTokens = cacheReadTokens;
        this.cacheWriteTokens = cacheWriteTokens;
        this.costUsd = costUsd;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getReviewId() {
        return reviewId;
    }

    public String getModel() {
        return model;
    }

    public String getPurpose() {
        return purpose;
    }

    public int getCalls() {
        return calls;
    }

    public long getTokensIn() {
        return tokensIn;
    }

    public long getTokensOut() {
        return tokensOut;
    }

    public long getCacheReadTokens() {
        return cacheReadTokens;
    }

    public long getCacheWriteTokens() {
        return cacheWriteTokens;
    }

    public BigDecimal getCostUsd() {
        return costUsd;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
