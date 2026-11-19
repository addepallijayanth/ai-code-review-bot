package com.example.reviewbot.webhook.events;

import com.example.reviewbot.github.dto.GitHubUser;
import com.example.reviewbot.github.dto.PullRequestInfo;

/** {@code pull_request} webhook. {@code before}/{@code after} are only set for {@code synchronize}. */
public record PullRequestEvent(
        String action,
        int number,
        PullRequestInfo pullRequest,
        String before,
        String after,
        GitHubRepository repository,
        InstallationRef installation,
        GitHubUser sender) {
}
