package com.example.reviewbot.model;

public enum ReviewStatus {
    /** A worker is reviewing this commit. */
    RUNNING,
    /** The review was posted to GitHub. */
    COMPLETED,
    /** Nothing was posted on purpose: draft, closed, superseded, nothing reviewable, or over budget. */
    SKIPPED,
    /** All attempts failed; see {@code status_detail}. */
    FAILED
}
