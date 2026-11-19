package com.example.reviewbot.github.dto;

import java.util.List;

/**
 * Result of comparing two commits.
 *
 * @param status   {@code ahead}, {@code behind}, {@code diverged} or {@code identical}
 * @param files    paths changed between the two commits
 * @param complete false when GitHub truncated the file list (it returns at most 300 files)
 */
public record Comparison(String status, List<String> files, boolean complete) {

    public boolean isFastForward() {
        return "ahead".equals(status) || "identical".equals(status);
    }
}
