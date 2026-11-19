package com.example.reviewbot.github.dto;

/** The fields of a pull request the reviewer needs; shared by webhook payloads and REST responses. */
public record PullRequestInfo(
        int number,
        String state,
        boolean draft,
        String title,
        String body,
        GitHubUser user,
        GitRef head,
        GitRef base) {

    public boolean isOpen() {
        return "open".equals(state);
    }
}
