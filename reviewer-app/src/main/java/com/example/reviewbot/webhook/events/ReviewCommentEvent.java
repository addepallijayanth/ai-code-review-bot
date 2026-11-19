package com.example.reviewbot.webhook.events;

import com.example.reviewbot.github.dto.GitHubUser;

/** {@code pull_request_review_comment} webhook; replies in an inline thread carry {@code inReplyToId}. */
public record ReviewCommentEvent(
        String action,
        Comment comment,
        PullRequestRef pullRequest,
        GitHubRepository repository,
        InstallationRef installation) {

    public record Comment(long id, String body, GitHubUser user, Long inReplyToId, String path, Integer line) {
    }

    public record PullRequestRef(int number) {
    }
}
