package com.example.reviewbot.github.dto;

/**
 * One entry of {@code GET /repos/{owner}/{repo}/pulls/{number}/files}. GitHub omits {@code patch}
 * for binary files and for diffs that are too large to show.
 */
public record ChangedFile(
        String filename,
        String status,
        int additions,
        int deletions,
        int changes,
        String patch,
        String previousFilename) {

    public boolean isRemoved() {
        return "removed".equals(status);
    }

    public boolean isRenamed() {
        return "renamed".equals(status);
    }

    public boolean hasPatch() {
        return patch != null && !patch.isBlank();
    }
}
