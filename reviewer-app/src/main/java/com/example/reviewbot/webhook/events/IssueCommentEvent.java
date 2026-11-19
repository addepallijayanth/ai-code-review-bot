package com.example.reviewbot.webhook.events;

import com.example.reviewbot.github.dto.GitHubUser;
import java.util.Map;

/** {@code issue_comment} webhook; it fires for comments on issues and on pull request conversations. */
public record IssueCommentEvent(
        String action,
        Issue issue,
        Comment comment,
        GitHubRepository repository,
        InstallationRef installation) {

    /** {@code pullRequest} is present only when the issue is a pull request. */
    public record Issue(int number, Map<String, Object> pullRequest) {

        public boolean isPullRequest() {
            return pullRequest != null;
        }
    }

    public record Comment(long id, String body, GitHubUser user) {
    }
}
