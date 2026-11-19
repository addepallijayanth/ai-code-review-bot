package com.example.reviewbot.review;

import com.example.reviewbot.ai.AiUsage;
import com.example.reviewbot.model.Severity;
import java.math.BigDecimal;
import java.util.List;

/**
 * Outcome of the review pipeline for one pull request.
 *
 * @param filesInScope               files that passed the filter and had a diff to review
 * @param filesDeepReviewed          files the agent reviewed after triage
 * @param repeatedFindingsSuppressed findings already posted on an earlier commit
 * @param findingsFilteredByPolicy   findings dropped by {@code min_severity} / {@code focus}
 * @param notes                      per-file problems worth telling the author about
 */
public record ReviewResult(
        List<NumberedFinding> findings,
        List<FileFilter.SkippedFile> skippedFiles,
        int filesInScope,
        int filesDeepReviewed,
        int repeatedFindingsSuppressed,
        int findingsFilteredByPolicy,
        List<String> notes,
        List<AiUsage> usage,
        boolean budgetExhausted) {

    public ReviewResult {
        findings = List.copyOf(findings);
        skippedFiles = List.copyOf(skippedFiles);
        notes = List.copyOf(notes);
        usage = List.copyOf(usage);
    }

    public long count(Severity severity) {
        return findings.stream().filter(f -> f.finding().severity() == severity).count();
    }

    public List<NumberedFinding> inline() {
        return findings.stream().filter(NumberedFinding::inline).toList();
    }

    public List<NumberedFinding> summaryOnly() {
        return findings.stream().filter(f -> !f.inline()).toList();
    }

    public BigDecimal costUsd() {
        return usage.stream().map(AiUsage::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
