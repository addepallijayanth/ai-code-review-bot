package com.example.reviewbot.review;

/** What the reviewer knows about the pull request besides its diff. Title and description are untrusted. */
public record PullRequestContext(
        String repo,
        int number,
        String title,
        String description,
        String author,
        String headSha,
        String baseSha) {
}
