package com.example.reviewbot.queue;

import com.example.reviewbot.command.CommandSource;
import com.example.reviewbot.command.CommandType;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;

/** Work queued by the webhook endpoint and executed by {@link ReviewWorker}. Serialized as JSON in Redis. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ReviewJob.ReviewPullRequest.class, name = "review"),
        @JsonSubTypes.Type(value = ReviewJob.RunCommand.class, name = "command")
})
public sealed interface ReviewJob permits ReviewJob.ReviewPullRequest, ReviewJob.RunCommand {

    /** {@code X-GitHub-Delivery} of the webhook that created the job. */
    String deliveryId();

    long installationId();

    /** {@code owner/name}. */
    String repo();

    int prNumber();

    Instant receivedAt();

    /**
     * Review one commit of a pull request.
     *
     * @param action    {@code opened}, {@code synchronize}, {@code reopened} or {@code ready_for_review}
     * @param beforeSha previous head for {@code synchronize}, otherwise null
     */
    record ReviewPullRequest(
            String deliveryId,
            long installationId,
            String accountLogin,
            String repo,
            int prNumber,
            String headSha,
            String beforeSha,
            String action,
            Instant receivedAt) implements ReviewJob {
    }

    /**
     * Answer {@code @reviewbot explain|fix} on a finding.
     *
     * @param commentId     the comment that contains the command
     * @param inReplyToId   for a reply in a review thread, the thread's first comment
     * @param findingNumber number from the review summary ({@code @reviewbot explain 3}), if given
     */
    record RunCommand(
            String deliveryId,
            long installationId,
            String repo,
            int prNumber,
            CommandType command,
            CommandSource source,
            long commentId,
            Long inReplyToId,
            Integer findingNumber,
            String author,
            String question,
            Instant receivedAt) implements ReviewJob {
    }
}
