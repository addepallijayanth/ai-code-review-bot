package com.example.reviewbot.command;

/** Where a command was written, which decides where the answer goes. */
public enum CommandSource {
    /** A reply in an inline review thread ({@code pull_request_review_comment} webhook). */
    REVIEW_THREAD,
    /** A comment on the pull request's conversation tab ({@code issue_comment} webhook). */
    CONVERSATION
}
