package com.example.reviewbot.review;

import com.example.reviewbot.github.CommentAnchor;
import com.example.reviewbot.model.ReviewFinding;

/**
 * A finding with its number in the review summary. {@code anchor} is set when the finding is
 * posted as an inline comment and null when it only appears in the summary (outside the diff, or
 * over the {@code max_comments} cap).
 */
public record NumberedFinding(int ordinal, ReviewFinding finding, CommentAnchor anchor, String fingerprint) {

    public boolean inline() {
        return anchor != null;
    }

    /** A GitHub suggestion block replaces exactly the anchored lines, so it needs an exact anchor. */
    public boolean suggestionApplicable() {
        return anchor != null && anchor.exact() && finding.suggestion() != null;
    }
}
