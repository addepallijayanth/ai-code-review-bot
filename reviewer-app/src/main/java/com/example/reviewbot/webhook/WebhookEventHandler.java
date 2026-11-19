package com.example.reviewbot.webhook;

import com.example.reviewbot.command.CommandParser;
import com.example.reviewbot.command.CommandSource;
import com.example.reviewbot.config.GitHubAppProperties;
import com.example.reviewbot.config.ReviewProperties;
import com.example.reviewbot.github.GitHubJson;
import com.example.reviewbot.github.dto.GitHubUser;
import com.example.reviewbot.github.dto.PullRequestInfo;
import com.example.reviewbot.installation.InstallationService;
import com.example.reviewbot.queue.JobQueue;
import com.example.reviewbot.queue.ReviewJob;
import com.example.reviewbot.webhook.events.InstallationEvent;
import com.example.reviewbot.webhook.events.InstallationRepositoriesEvent;
import com.example.reviewbot.webhook.events.IssueCommentEvent;
import com.example.reviewbot.webhook.events.PullRequestEvent;
import com.example.reviewbot.webhook.events.ReviewCommentEvent;
import java.io.IOException;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Turns a verified webhook delivery into work. Anything slow (talking to GitHub or Claude) is queued
 * so the endpoint can answer within GitHub's 10-second delivery timeout.
 */
@Service
public class WebhookEventHandler {

    private static final Set<String> REVIEW_ACTIONS = Set.of("opened", "synchronize", "reopened", "ready_for_review");

    private final JobQueue queue;
    private final InstallationService installations;
    private final CommandParser commands;
    private final ReviewProperties review;
    private final GitHubAppProperties github;
    private final Clock clock;

    public WebhookEventHandler(JobQueue queue, InstallationService installations, CommandParser commands,
                               ReviewProperties review, GitHubAppProperties github, Clock clock) {
        this.queue = queue;
        this.installations = installations;
        this.commands = commands;
        this.review = review;
        this.github = github;
        this.clock = clock;
    }

    public WebhookOutcome handle(String event, String deliveryId, byte[] payload) throws IOException {
        return switch (event) {
            case "ping" -> WebhookOutcome.ignored("pong");
            case "pull_request" -> onPullRequest(read(payload, PullRequestEvent.class), deliveryId);
            case "issue_comment" -> onIssueComment(read(payload, IssueCommentEvent.class), deliveryId);
            case "pull_request_review_comment" -> onReviewComment(read(payload, ReviewCommentEvent.class), deliveryId);
            case "installation" -> {
                InstallationEvent installation = read(payload, InstallationEvent.class);
                installations.onInstallation(installation);
                yield WebhookOutcome.processed("installation " + installation.action());
            }
            case "installation_repositories" -> {
                InstallationRepositoriesEvent repositories = read(payload, InstallationRepositoriesEvent.class);
                installations.onRepositoriesChanged(repositories);
                yield WebhookOutcome.processed("installation repositories " + repositories.action());
            }
            default -> WebhookOutcome.ignored("event '" + event + "' is not handled");
        };
    }

    private WebhookOutcome onPullRequest(PullRequestEvent event, String deliveryId) {
        if (!REVIEW_ACTIONS.contains(event.action())) {
            return WebhookOutcome.ignored("pull_request action '" + event.action() + "'");
        }
        PullRequestInfo pr = event.pullRequest();
        if (pr == null || pr.head() == null || event.installation() == null || event.repository() == null) {
            return WebhookOutcome.ignored("payload has no pull request, repository or installation");
        }
        if (review.skipDrafts() && pr.draft()) {
            return WebhookOutcome.ignored("draft pull request; it will be reviewed when marked ready");
        }
        String account = event.repository().owner() == null ? null : event.repository().owner().login();
        queue.enqueue(new ReviewJob.ReviewPullRequest(deliveryId, event.installation().id(), account,
                event.repository().fullName(), pr.number(), pr.head().sha(), event.before(), event.action(), clock.instant()));
        return WebhookOutcome.queued("review of " + event.repository().fullName() + "#" + pr.number());
    }

    private WebhookOutcome onIssueComment(IssueCommentEvent event, String deliveryId) {
        if (!"created".equals(event.action()) || event.issue() == null || !event.issue().isPullRequest()
                || event.comment() == null || event.installation() == null) {
            return WebhookOutcome.ignored("not a new pull request comment");
        }
        if (isBot(event.comment().user())) {
            return WebhookOutcome.ignored("comment by a bot");
        }
        Optional<CommandParser.ParsedCommand> command = commands.parse(event.comment().body());
        if (command.isEmpty()) {
            return WebhookOutcome.ignored("no command");
        }
        queue.enqueue(new ReviewJob.RunCommand(deliveryId, event.installation().id(), event.repository().fullName(),
                event.issue().number(), command.get().type(), CommandSource.CONVERSATION, event.comment().id(), null,
                command.get().findingNumber(), event.comment().user().login(), event.comment().body(), clock.instant()));
        return WebhookOutcome.queued(command.get().type().name().toLowerCase() + " command");
    }

    private WebhookOutcome onReviewComment(ReviewCommentEvent event, String deliveryId) {
        if (!"created".equals(event.action()) || event.comment() == null || event.pullRequest() == null
                || event.installation() == null) {
            return WebhookOutcome.ignored("not a new review comment");
        }
        if (isBot(event.comment().user())) {
            return WebhookOutcome.ignored("comment by a bot");
        }
        Optional<CommandParser.ParsedCommand> command = commands.parse(event.comment().body());
        if (command.isEmpty()) {
            return WebhookOutcome.ignored("no command");
        }
        CommandSource source = event.comment().inReplyToId() != null ? CommandSource.REVIEW_THREAD : CommandSource.CONVERSATION;
        queue.enqueue(new ReviewJob.RunCommand(deliveryId, event.installation().id(), event.repository().fullName(),
                event.pullRequest().number(), command.get().type(), source, event.comment().id(),
                event.comment().inReplyToId(), command.get().findingNumber(), event.comment().user().login(),
                event.comment().body(), clock.instant()));
        return WebhookOutcome.queued(command.get().type().name().toLowerCase() + " command");
    }

    private boolean isBot(GitHubUser user) {
        return user == null || user.isBot() || github.botLogin().equalsIgnoreCase(user.login());
    }

    private static <T> T read(byte[] payload, Class<T> type) throws IOException {
        return GitHubJson.mapper().readValue(payload, type);
    }
}
