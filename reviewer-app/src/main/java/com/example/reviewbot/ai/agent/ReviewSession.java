package com.example.reviewbot.ai.agent;

import com.example.reviewbot.ai.TokenBudget;
import com.example.reviewbot.ai.tools.RepoAccess;
import com.example.reviewbot.config.AiProperties;
import com.example.reviewbot.review.SecretRedactor;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** State shared by all agent runs of one pull request review. Thread-safe. */
public final class ReviewSession {

    private final RepoAccess repo;
    private final TokenBudget budget;
    private final Set<String> changedPaths;
    private final SecretRedactor redactor;
    private final AiProperties.Agent limits;
    private final CacheWarmGate warmGate;
    private final AtomicInteger searchesUsed = new AtomicInteger();

    public ReviewSession(RepoAccess repo, TokenBudget budget, Set<String> changedPaths, SecretRedactor redactor,
                         AiProperties.Agent limits) {
        this.repo = repo;
        this.budget = budget;
        this.changedPaths = Set.copyOf(changedPaths);
        this.redactor = redactor;
        this.limits = limits;
        this.warmGate = new CacheWarmGate(limits.warmupWait());
    }

    /** Code search is rate-limited by GitHub, so the whole review shares a small allowance. */
    public boolean tryUseSearch() {
        return searchesUsed.incrementAndGet() <= limits.maxSearchesPerReview();
    }

    public RepoAccess repo() {
        return repo;
    }

    public TokenBudget budget() {
        return budget;
    }

    public Set<String> changedPaths() {
        return changedPaths;
    }

    public SecretRedactor redactor() {
        return redactor;
    }

    public AiProperties.Agent limits() {
        return limits;
    }

    public CacheWarmGate warmGate() {
        return warmGate;
    }
}
