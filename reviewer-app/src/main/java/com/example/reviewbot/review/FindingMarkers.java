package com.example.reviewbot.review;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hidden HTML comments the bot puts in what it posts. They tie a GitHub comment back to a stored
 * finding (for chat commands) and a review body back to a review row (for idempotent retries).
 */
public final class FindingMarkers {

    private static final Pattern FINDING = Pattern.compile("<!-- reviewbot:finding:(\\d+):(\\d+) -->");

    public record FindingRef(long reviewId, int ordinal) {
    }

    private FindingMarkers() {
    }

    public static String review(long reviewId) {
        return "<!-- reviewbot:review:" + reviewId + " -->";
    }

    public static String finding(long reviewId, int ordinal) {
        return "<!-- reviewbot:finding:" + reviewId + ":" + ordinal + " -->";
    }

    public static Optional<FindingRef> parseFinding(String body) {
        if (body == null) {
            return Optional.empty();
        }
        Matcher matcher = FINDING.matcher(body);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(new FindingRef(Long.parseLong(matcher.group(1)), Integer.parseInt(matcher.group(2))));
    }
}
