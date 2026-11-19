package com.example.reviewbot.ai;

import java.math.BigDecimal;

/**
 * Usage of one model call. {@code inputTokens} is the uncached part of the prompt; the full prompt
 * size is {@code inputTokens + cacheWriteTokens + cacheReadTokens}.
 */
public record AiUsage(
        String model,
        Purpose purpose,
        long inputTokens,
        long outputTokens,
        long cacheReadTokens,
        long cacheWriteTokens,
        BigDecimal costUsd,
        long latencyMs) {

    /** Tokens billed at (or above) the base input rate, plus output. Cache reads are excluded. */
    public long billableTokens() {
        return inputTokens + cacheWriteTokens + outputTokens;
    }

    public long promptTokens() {
        return inputTokens + cacheWriteTokens + cacheReadTokens;
    }
}
