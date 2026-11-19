package com.example.reviewbot.queue;

import com.example.reviewbot.ai.AiException;
import com.example.reviewbot.command.CommandHandler;
import com.example.reviewbot.config.QueueProperties;
import com.example.reviewbot.github.GitHubApiException;
import com.example.reviewbot.review.ReviewOrchestrator;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs queued jobs on virtual threads. Transient failures (rate limits, API outages, database or
 * Redis hiccups) are retried with exponential backoff; anything else, and anything still failing
 * after {@code max-attempts}, goes to the dead-letter stream.
 */
@Component
@ConditionalOnProperty(prefix = "reviewbot.queue", name = "workers-enabled", havingValue = "true", matchIfMissing = true)
public class ReviewWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ReviewWorker.class);
    private static final Duration JOIN_TIMEOUT = Duration.ofSeconds(30);

    private final JobQueue queue;
    private final ReviewOrchestrator orchestrator;
    private final CommandHandler commands;
    private final QueueProperties properties;
    private final String consumerPrefix;
    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running;

    public ReviewWorker(JobQueue queue, ReviewOrchestrator orchestrator, CommandHandler commands, QueueProperties properties) {
        this.queue = queue;
        this.orchestrator = orchestrator;
        this.commands = commands;
        this.properties = properties;
        this.consumerPrefix = instanceName() + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Override
    public void start() {
        queue.initialize();
        running = true;
        for (int i = 0; i < properties.concurrency(); i++) {
            String consumer = consumerPrefix + "-" + i;
            workers.add(Thread.ofVirtual().name("review-worker-" + i).start(() -> pollLoop(consumer)));
        }
        log.info("Started {} review worker(s) as {}", properties.concurrency(), consumerPrefix);
    }

    @Override
    public void stop() {
        running = false;
        for (Thread worker : workers) {
            try {
                if (!worker.join(JOIN_TIMEOUT)) {
                    log.warn("{} is still busy; its job will be picked up again after the visibility timeout", worker.getName());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        workers.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Scheduled(fixedDelayString = "${reviewbot.queue.promote-interval-ms:1000}")
    public void promoteRetries() {
        if (!running) {
            return;
        }
        try {
            long moved = queue.promoteDueRetries();
            if (moved > 0) {
                log.debug("Moved {} job(s) back onto the queue for retry", moved);
            }
        } catch (RuntimeException e) {
            log.debug("Could not promote retries: {}", e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${reviewbot.queue.recovery-interval-ms:60000}",
            initialDelayString = "${reviewbot.queue.recovery-interval-ms:60000}")
    public void recoverAbandoned() {
        if (!running) {
            return;
        }
        try {
            for (QueuedJob job : queue.claimAbandoned(consumerPrefix + "-recovery", 10)) {
                if (job.deliveries() > properties.maxAttempts() + 1L) {
                    queue.deadLetter(job, "Abandoned " + (job.deliveries() - 1) + " times; the job may crash its worker");
                    continue;
                }
                log.warn("Recovering job {} abandoned by a stopped worker", job.envelope().id());
                Thread.ofVirtual().name("review-recovery").start(() -> process(job));
            }
        } catch (RuntimeException e) {
            log.warn("Recovering abandoned jobs failed: {}", e.getMessage());
        }
    }

    void process(QueuedJob queued) {
        ReviewJob job = queued.envelope().job();
        int attempt = queued.envelope().attempt();
        try (MdcScope ignored = MdcScope.forJob(job, attempt)) {
            try {
                dispatch(job, attempt);
                queue.ack(queued);
            } catch (RuntimeException e) {
                handleFailure(queued, job, attempt, e);
            }
        }
    }

    private void dispatch(ReviewJob job, int attempt) {
        switch (job) {
            case ReviewJob.ReviewPullRequest review -> orchestrator.review(review, attempt);
            case ReviewJob.RunCommand command -> commands.handle(command);
        }
    }

    private void handleFailure(QueuedJob queued, ReviewJob job, int attempt, RuntimeException error) {
        if (isRetryable(error) && attempt < properties.maxAttempts()) {
            Duration delay = properties.retryBackoff().multipliedBy(1L << (attempt - 1));
            log.warn("Job failed on attempt {}/{}; retrying in {}s: {}", attempt, properties.maxAttempts(),
                    delay.toSeconds(), error.getMessage());
            queue.retryLater(queued, delay);
            return;
        }
        log.error("Job failed permanently after {} attempt(s)", attempt, error);
        queue.deadLetter(queued, error.toString());
        if (job instanceof ReviewJob.ReviewPullRequest review) {
            try {
                orchestrator.markFailed(review, error.getMessage());
            } catch (RuntimeException e) {
                log.warn("Could not mark the review as failed: {}", e.getMessage());
            }
        }
    }

    static boolean isRetryable(Throwable error) {
        if (error instanceof AiException ai) {
            return ai.isRetryable();
        }
        if (error instanceof GitHubApiException gitHub) {
            return gitHub.isRetryable();
        }
        // Database or Redis trouble; programming errors and bad input won't improve on retry.
        return error instanceof DataAccessException;
    }

    private void pollLoop(String consumer) {
        while (running) {
            try {
                for (QueuedJob job : queue.poll(consumer, 1)) {
                    process(job);
                }
            } catch (RuntimeException e) {
                if (running) {
                    log.warn("Polling the job queue failed: {}", e.getMessage());
                    pause(Duration.ofSeconds(2));
                }
            }
        }
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String instanceName() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            host = System.getenv("COMPUTERNAME");
        }
        return host == null || host.isBlank() ? "reviewbot" : host.toLowerCase();
    }
}
