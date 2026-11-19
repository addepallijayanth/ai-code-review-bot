package com.example.reviewbot.ai;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Spend limit for one pull request (or one chat command), shared by every model call made for it,
 * including calls running in parallel. Calls are refused once either limit is reached; a call
 * already in flight may finish slightly over.
 */
public final class TokenBudget {

    private final long maxTokens;
    private final BigDecimal maxCostUsd;
    private final List<AiUsage> usages = new ArrayList<>();
    private long tokensUsed;
    private BigDecimal costUsed = BigDecimal.ZERO;

    public TokenBudget(long maxTokens, BigDecimal maxCostUsd) {
        this.maxTokens = maxTokens;
        this.maxCostUsd = maxCostUsd;
    }

    public static TokenBudget unlimited() {
        return new TokenBudget(Long.MAX_VALUE, new BigDecimal("1000000"));
    }

    public synchronized void record(AiUsage usage) {
        usages.add(usage);
        tokensUsed += usage.billableTokens();
        costUsed = costUsed.add(usage.costUsd());
    }

    public synchronized boolean isExhausted() {
        return tokensUsed >= maxTokens || costUsed.compareTo(maxCostUsd) >= 0;
    }

    public synchronized long tokensUsed() {
        return tokensUsed;
    }

    public synchronized BigDecimal costUsed() {
        return costUsed;
    }

    public synchronized List<AiUsage> usages() {
        return List.copyOf(usages);
    }

    public synchronized String describe() {
        return tokensUsed + "/" + maxTokens + " tokens, $" + costUsed.toPlainString() + "/$" + maxCostUsd.toPlainString();
    }
}
