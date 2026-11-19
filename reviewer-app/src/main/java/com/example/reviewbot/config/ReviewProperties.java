package com.example.reviewbot.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Service-wide review limits. Per-repository behaviour comes from {@code .reviewbot.yml}.
 *
 * @param maxFiles               files reviewed per pull request; the rest are listed as skipped
 * @param maxChangedLinesPerFile files with more changed lines than this are skipped as too large
 * @param maxChunkChars          rendered diff size sent to the agent in one run; larger diffs are split
 * @param maxConcurrentFiles     agent runs in flight per pull request
 * @param skipDrafts             wait for "ready for review" before reviewing draft pull requests
 */
@ConfigurationProperties(prefix = "reviewbot.review")
public record ReviewProperties(
        @DefaultValue("50") int maxFiles,
        @DefaultValue("1500") int maxChangedLinesPerFile,
        @DefaultValue("24000") int maxChunkChars,
        @DefaultValue("4") int maxConcurrentFiles,
        @DefaultValue("true") boolean skipDrafts,
        @DefaultValue Budget budget,
        @DefaultValue Defaults defaults) {

    /**
     * Spend limits. Token limits count uncached input, cache writes and output; cache reads are
     * covered by the dollar limits.
     */
    public record Budget(
            @DefaultValue("600000") long maxTokensPerPr,
            @DefaultValue("0.60") BigDecimal maxCostPerPrUsd,
            @DefaultValue("5.00") BigDecimal maxCostPerInstallationPerDayUsd,
            @DefaultValue("60000") long maxTokensPerCommand,
            @DefaultValue("0.10") BigDecimal maxCostPerCommandUsd) {
    }

    /** Defaults applied when a repository has no {@code .reviewbot.yml} or omits a key. */
    public record Defaults(
            @DefaultValue("minor") String minSeverity,
            @DefaultValue("15") int maxComments) {
    }
}
