package com.example.reviewbot.ai;

/**
 * A model call that failed after the SDK's own retries. {@code retryable} says whether running the
 * job again later could succeed (rate limits, overload, network) or not (bad request, auth).
 */
public class AiException extends RuntimeException {

    private final boolean retryable;

    public AiException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
