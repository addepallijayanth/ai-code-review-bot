package com.example.reviewbot.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.reviewbot.command.CommandParser;
import com.example.reviewbot.command.CommandSource;
import com.example.reviewbot.command.CommandType;
import com.example.reviewbot.config.GitHubAppProperties;
import com.example.reviewbot.installation.InstallationService;
import com.example.reviewbot.queue.JobQueue;
import com.example.reviewbot.queue.ReviewJob;
import com.example.reviewbot.support.TestProperties;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WebhookEventHandlerTest {

    private static final String REPOSITORY = "\"repository\":{\"id\":1,\"name\":\"shop\",\"full_name\":\"acme/shop\",\"owner\":{\"login\":\"acme\"}}";

    private final JobQueue queue = mock(JobQueue.class);
    private final InstallationService installations = mock(InstallationService.class);
    private final GitHubAppProperties github = new GitHubAppProperties("1", "reviewbot", null, null, "secret",
            URI.create("https://api.github.com"), "https://github.com", "@reviewbot");
    private final WebhookEventHandler handler = new WebhookEventHandler(queue, installations, new CommandParser(github),
            TestProperties.review(), github, Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC));

    @Test
    void queuesAReviewWhenAPullRequestIsOpened() throws Exception {
        WebhookOutcome outcome = handler.handle("pull_request", "d-1", bytes("""
                {"action":"synchronize","number":5,"before":"%s",
                 "pull_request":{"number":5,"state":"open","draft":false,"head":{"sha":"%s"},"base":{"sha":"%s"}},
                 %s,"installation":{"id":99}}
                """.formatted("0".repeat(40), "1".repeat(40), "2".repeat(40), REPOSITORY)));

        assertThat(outcome.status()).isEqualTo(WebhookOutcome.Status.QUEUED);
        ArgumentCaptor<ReviewJob> job = ArgumentCaptor.forClass(ReviewJob.class);
        verify(queue).enqueue(job.capture());
        assertThat(job.getValue()).isInstanceOfSatisfying(ReviewJob.ReviewPullRequest.class, review -> {
            assertThat(review.repo()).isEqualTo("acme/shop");
            assertThat(review.installationId()).isEqualTo(99);
            assertThat(review.headSha()).isEqualTo("1".repeat(40));
            assertThat(review.beforeSha()).isEqualTo("0".repeat(40));
            assertThat(review.action()).isEqualTo("synchronize");
        });
    }

    @Test
    void ignoresDraftsAndUninterestingActions() throws Exception {
        assertThat(handler.handle("pull_request", "d-2", bytes("""
                {"action":"opened","number":5,"pull_request":{"number":5,"draft":true,"head":{"sha":"a"}},%s,"installation":{"id":99}}
                """.formatted(REPOSITORY))).status()).isEqualTo(WebhookOutcome.Status.IGNORED);
        assertThat(handler.handle("pull_request", "d-3", bytes("{\"action\":\"labeled\"}")).status())
                .isEqualTo(WebhookOutcome.Status.IGNORED);
        assertThat(handler.handle("ping", "d-4", bytes("{}")).detail()).isEqualTo("pong");
        verify(queue, never()).enqueue(any());
    }

    @Test
    void queuesACommandRepliedInAFindingThread() throws Exception {
        WebhookOutcome outcome = handler.handle("pull_request_review_comment", "d-5", bytes("""
                {"action":"created",
                 "comment":{"id":31,"body":"@reviewbot fix","user":{"login":"alice","type":"User"},"in_reply_to_id":30},
                 "pull_request":{"number":5},%s,"installation":{"id":99}}
                """.formatted(REPOSITORY)));

        assertThat(outcome.status()).isEqualTo(WebhookOutcome.Status.QUEUED);
        ArgumentCaptor<ReviewJob> job = ArgumentCaptor.forClass(ReviewJob.class);
        verify(queue).enqueue(job.capture());
        assertThat(job.getValue()).isInstanceOfSatisfying(ReviewJob.RunCommand.class, command -> {
            assertThat(command.command()).isEqualTo(CommandType.FIX);
            assertThat(command.source()).isEqualTo(CommandSource.REVIEW_THREAD);
            assertThat(command.inReplyToId()).isEqualTo(30L);
            assertThat(command.commentId()).isEqualTo(31L);
        });
    }

    @Test
    void neverAnswersBotsIncludingItself() throws Exception {
        String payload = """
                {"action":"created","issue":{"number":5,"pull_request":{"url":"x"}},
                 "comment":{"id":40,"body":"@reviewbot explain 1","user":{"login":"reviewbot[bot]","type":"Bot"}},
                 %s,"installation":{"id":99}}
                """.formatted(REPOSITORY);

        assertThat(handler.handle("issue_comment", "d-6", bytes(payload)).status()).isEqualTo(WebhookOutcome.Status.IGNORED);
        verify(queue, never()).enqueue(any());
    }

    @Test
    void ignoresCommentsOnPlainIssues() throws Exception {
        String payload = """
                {"action":"created","issue":{"number":6},
                 "comment":{"id":41,"body":"@reviewbot explain 1","user":{"login":"alice","type":"User"}},
                 %s,"installation":{"id":99}}
                """.formatted(REPOSITORY);

        assertThat(handler.handle("issue_comment", "d-7", bytes(payload)).status()).isEqualTo(WebhookOutcome.Status.IGNORED);
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
