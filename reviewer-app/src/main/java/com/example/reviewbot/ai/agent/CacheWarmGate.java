package com.example.reviewbot.ai.agent;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lets the first agent of a pull request write the prompt cache before the others start.
 * <p>
 * All agents of one review share the same cached prefix (tools + system prompt + repository
 * rules). A cache entry is only readable once the request that writes it has started returning, so
 * agents that start at the same moment would each pay for a cache write and read nothing. The
 * first caller goes straight through; the rest wait (bounded) until its first response is back.
 */
public final class CacheWarmGate {

    private final AtomicBoolean leaderChosen = new AtomicBoolean();
    private final CountDownLatch warmed = new CountDownLatch(1);
    private final Duration maxWait;

    public CacheWarmGate(Duration maxWait) {
        this.maxWait = maxWait;
    }

    /** Call before an agent's first request. */
    public void awaitTurn() {
        if (leaderChosen.compareAndSet(false, true)) {
            return;
        }
        try {
            warmed.await(maxWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Call after an agent's first request returned, successfully or not. Idempotent. */
    public void release() {
        warmed.countDown();
    }
}
