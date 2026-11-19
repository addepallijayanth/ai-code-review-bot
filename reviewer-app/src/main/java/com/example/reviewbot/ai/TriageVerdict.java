package com.example.reviewbot.ai;

/**
 * Triage decision for one file. Anything but a low-risk "no" gets a deep review, and when triage
 * itself fails every file gets one (fail open: cost goes up, coverage does not go down).
 */
public record TriageVerdict(String path, boolean needsDeepReview, Risk risk, String reason) {

    public static TriageVerdict failOpen(String path) {
        return new TriageVerdict(path, true, Risk.MEDIUM, "Triage unavailable; reviewing to be safe.");
    }

    public boolean deepReview() {
        return needsDeepReview || risk != Risk.LOW;
    }
}
