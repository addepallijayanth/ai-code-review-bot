package com.example.reviewbot.github;

/**
 * Where a review comment goes: {@code line} (and {@code startLine} for a range) on the right side
 * of the diff. {@code exact} is false when the reported line had to be moved or a range collapsed;
 * a suggestion block would then replace the wrong lines, so it is not offered.
 */
public record CommentAnchor(String path, int line, Integer startLine, boolean exact) {
}
