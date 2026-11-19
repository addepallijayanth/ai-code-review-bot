package com.example.reviewbot.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Redis Streams job queue.
 *
 * @param streamKey         stream that holds pending jobs
 * @param delayedKey        sorted set of jobs waiting for a retry (score = due time in epoch millis)
 * @param deadLetterKey     stream of jobs that failed permanently
 * @param consumerGroup     consumer group shared by all app instances
 * @param concurrency       worker threads per instance
 * @param maxAttempts       attempts before a job is dead-lettered
 * @param retryBackoff      delay before the first retry; doubles on each attempt
 * @param visibilityTimeout how long a delivered job may stay un-acked before another worker claims it
 * @param pollTimeout       how long one blocking read waits for new jobs
 * @param workersEnabled    set to false to run an instance that only receives webhooks
 */
@ConfigurationProperties(prefix = "reviewbot.queue")
public record QueueProperties(
        @DefaultValue("reviewbot:jobs") String streamKey,
        @DefaultValue("reviewbot:jobs:delayed") String delayedKey,
        @DefaultValue("reviewbot:jobs:dead") String deadLetterKey,
        @DefaultValue("review-workers") String consumerGroup,
        @DefaultValue("4") int concurrency,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("30s") Duration retryBackoff,
        @DefaultValue("15m") Duration visibilityTimeout,
        @DefaultValue("2s") Duration pollTimeout,
        @DefaultValue("true") boolean workersEnabled) {
}
