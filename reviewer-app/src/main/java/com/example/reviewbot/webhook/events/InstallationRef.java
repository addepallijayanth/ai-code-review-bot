package com.example.reviewbot.webhook.events;

import com.example.reviewbot.github.dto.GitHubUser;

/** The {@code installation} object of a webhook payload; {@code account} is only sent on installation events. */
public record InstallationRef(long id, GitHubUser account, String repositorySelection) {
}
