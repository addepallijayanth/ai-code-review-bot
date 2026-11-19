package com.example.reviewbot.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Claude API settings: models per task, effort levels, agent limits, and the price table used to
 * turn token usage into dollars.
 */
@ConfigurationProperties(prefix = "reviewbot.ai")
public record AiProperties(
        String apiKey,
        @DefaultValue("https://api.anthropic.com") String baseUrl,
        @DefaultValue("5m") Duration requestTimeout,
        @DefaultValue("3") int maxRetries,
        @DefaultValue("true") boolean refusalFallback,
        @DefaultValue Models models,
        @DefaultValue Agent agent,
        Map<String, Price> pricing) {

    public AiProperties {
        pricing = pricing == null ? Map.of() : Map.copyOf(pricing);
    }

    /**
     * Model routing: a cheap model triages, a stronger model reviews and answers chat commands.
     */
    public record Models(
            @DefaultValue("claude-haiku-5-5") String triage,
            @DefaultValue("claude-sonnet-5-5") String review,
            @DefaultValue("claude-sonnet-5-5") String chat) {
    }

    /**
     * Limits that keep one review predictable in cost and latency.
     *
     * @param maxSteps              model calls allowed per file (or per chunk of a large file)
     * @param maxTokensPerTurn      {@code max_tokens} for each review turn, thinking included
     * @param reviewEffort          effort for the review agent
     * @param triageEffort          effort for triage
     * @param explainEffort         effort for {@code @reviewbot explain}
     * @param fixEffort             effort for {@code @reviewbot fix}
     * @param readFileMaxLines      lines returned by one {@code read_file} call
     * @param maxSearchesPerReview  GitHub code-search calls allowed per pull request (API limit is 10/min)
     * @param triageBatchSize       files triaged in one request
     * @param triageMaxLinesPerFile diff lines shown to triage per file
     * @param warmupWait            how long parallel file reviews wait for the first one to warm the prompt cache
     */
    public record Agent(
            @DefaultValue("8") int maxSteps,
            @DefaultValue("16000") long maxTokensPerTurn,
            @DefaultValue("medium") String reviewEffort,
            @DefaultValue("low") String triageEffort,
            @DefaultValue("low") String explainEffort,
            @DefaultValue("medium") String fixEffort,
            @DefaultValue("400") int readFileMaxLines,
            @DefaultValue("6") int maxSearchesPerReview,
            @DefaultValue("12") int triageBatchSize,
            @DefaultValue("150") int triageMaxLinesPerFile,
            @DefaultValue("90s") Duration warmupWait) {
    }

    /**
     * USD per million tokens. {@code longContextMultiplier} applies to every rate when the prompt
     * (uncached + cached input) is longer than {@code longContextThreshold} tokens.
     */
    public record Price(
            BigDecimal input,
            BigDecimal output,
            BigDecimal cacheWrite,
            BigDecimal cacheRead,
            Long longContextThreshold,
            BigDecimal longContextMultiplier) {
    }
}
