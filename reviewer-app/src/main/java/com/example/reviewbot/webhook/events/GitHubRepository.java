package com.example.reviewbot.webhook.events;

import com.example.reviewbot.github.dto.GitHubUser;

public record GitHubRepository(long id, String name, String fullName, GitHubUser owner, String defaultBranch) {
}
