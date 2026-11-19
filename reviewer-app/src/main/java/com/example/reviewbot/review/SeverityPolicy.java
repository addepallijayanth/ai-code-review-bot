package com.example.reviewbot.review;

import com.example.reviewbot.model.ReviewFinding;
import com.example.reviewbot.model.Severity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Applies a repository's {@code min_severity} and {@code focus}. Focus narrows minor findings and
 * suggestions to the chosen categories; critical and major findings are always kept.
 */
@Component
public class SeverityPolicy {

    /** Most severe first, then by location, so the summary and the inline-comment cap are predictable. */
    public static final Comparator<ReviewFinding> ORDER = Comparator
            .comparingInt((ReviewFinding f) -> -f.severity().rank())
            .thenComparing(ReviewFinding::file)
            .thenComparingInt(ReviewFinding::line);

    public record Result(List<ReviewFinding> kept, int belowMinimum, int outsideFocus) {
    }

    public Result apply(List<ReviewFinding> findings, RepoConfig config) {
        List<ReviewFinding> kept = new ArrayList<>();
        int belowMinimum = 0;
        int outsideFocus = 0;
        for (ReviewFinding finding : findings) {
            if (!finding.severity().isAtLeast(config.minSeverity())) {
                belowMinimum++;
            } else if (!config.focus().isEmpty() && !config.focus().contains(finding.category())
                    && !finding.severity().isAtLeast(Severity.MAJOR)) {
                outsideFocus++;
            } else {
                kept.add(finding);
            }
        }
        kept.sort(ORDER);
        return new Result(kept, belowMinimum, outsideFocus);
    }
}
