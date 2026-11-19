package com.acme.inventory;

import java.time.Duration;

/**
 * Exponential backoff for publishing stock events: the delay doubles after every failed attempt,
 * starting at {@code initialDelay}, and the event is given up after {@code maxAttempts}.
 */
public final class RetryPolicy {

    private final int maxAttempts;
    private final Duration initialDelay;

    public RetryPolicy(int maxAttempts, Duration initialDelay) {
        this.maxAttempts = maxAttempts;
        this.initialDelay = initialDelay;
    }

    /** Delay before the given attempt (1-based): initialDelay, 2x, 4x, ... */
    public Duration delayBefore(int attempt) {
        return initialDelay.multipliedBy(1L << (attempt - 1));
    }

    /** Whether another attempt is allowed after {@code attempt} attempts have failed. */
    public boolean shouldRetry(int attempt) {
        return attempt < maxAttempts;
    }
}
