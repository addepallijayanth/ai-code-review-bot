package com.example.reviewbot.github.dto;

import java.util.List;

/**
 * Body of {@code POST /repos/{owner}/{repo}/pulls/{number}/reviews}. Comments are anchored with
 * {@code line}/{@code side} (and {@code start_line} for ranges) on the new version of the file.
 */
public record CreateReviewRequest(String commitId, String body, String event, List<DraftComment> comments) {

    public static final String COMMENT = "COMMENT";

    public record DraftComment(String path, int line, String side, Integer startLine, String startSide, String body) {

        public static DraftComment onNewLines(String path, int line, Integer startLine, String body) {
            return startLine == null
                    ? new DraftComment(path, line, "RIGHT", null, null, body)
                    : new DraftComment(path, line, "RIGHT", startLine, "RIGHT", body);
        }
    }
}
