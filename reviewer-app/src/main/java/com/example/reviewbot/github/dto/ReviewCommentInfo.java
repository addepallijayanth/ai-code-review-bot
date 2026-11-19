package com.example.reviewbot.github.dto;

public record ReviewCommentInfo(
        long id,
        String body,
        GitHubUser user,
        String path,
        Integer line,
        Integer startLine,
        Long inReplyToId) {
}
