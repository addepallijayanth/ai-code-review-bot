package com.example.reviewbot.github;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Converts file line numbers reported by the model into positions GitHub accepts. A review comment
 * can only sit on a line that is part of the diff (an added or context line of some hunk), and a
 * multi-line comment must start and end inside the same hunk.
 * <ul>
 *   <li>A line inside the diff maps as is.</li>
 *   <li>A line one or two lines off a hunk snaps to the nearest commentable line (off-by-one
 *       reports are common); the anchor is then marked inexact.</li>
 *   <li>Anything else has no position and is listed in the review summary instead.</li>
 * </ul>
 */
@Component
public class PositionMapper {

    static final int MAX_SNAP_DISTANCE = 2;

    public Optional<CommentAnchor> map(FileDiff diff, int line, Integer startLine) {
        Integer target = diff.isCommentable(line) ? Integer.valueOf(line) : nearestCommentable(diff, line);
        if (target == null) {
            return Optional.empty();
        }
        boolean exact = target == line;
        Integer start = null;
        if (startLine != null && startLine < line) {
            if (exact && diff.isCommentable(startLine) && sameHunk(diff, startLine, line)) {
                start = startLine;
            } else {
                exact = false;
            }
        }
        return Optional.of(new CommentAnchor(diff.path(), target, start, exact));
    }

    private static Integer nearestCommentable(FileDiff diff, int line) {
        for (int distance = 1; distance <= MAX_SNAP_DISTANCE; distance++) {
            if (diff.isCommentable(line - distance)) {
                return line - distance;
            }
            if (diff.isCommentable(line + distance)) {
                return line + distance;
            }
        }
        return null;
    }

    private static boolean sameHunk(FileDiff diff, int first, int last) {
        return diff.hunkWithNewLine(first)
                .map(hunk -> hunk.containsNewLine(last))
                .orElse(false);
    }
}
