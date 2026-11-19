package com.example.reviewbot.queue;

import java.time.Duration;
import java.util.List;

/**
 * At-least-once job queue between the webhook endpoint and the review workers. A job stays owned
 * by the worker that received it until it is acknowledged, retried later, or dead-lettered.
 * Production uses {@link RedisJobQueue}.
 */
public interface JobQueue {

    void enqueue(ReviewJob job);

    /** Creates whatever the queue needs (stream, consumer group) if it does not exist yet. */
    void initialize();

    /** Waits up to the configured poll timeout for up to {@code count} jobs. */
    List<QueuedJob> poll(String consumer, int count);

    /** The job is done. */
    void ack(QueuedJob job);

    /** Hands the job back to be retried after {@code delay}, as its next attempt. */
    void retryLater(QueuedJob job, Duration delay);

    /** Gives up on the job and keeps it, with the reason, for inspection. */
    void deadLetter(QueuedJob job, String reason);

    /** Makes retries whose delay has passed available to {@link #poll} again; returns how many. */
    long promoteDueRetries();

    /** Takes over jobs held too long by a worker that stopped without acknowledging them. */
    List<QueuedJob> claimAbandoned(String consumer, int count);

    /** Jobs waiting, in progress, or waiting for a retry. */
    long backlog();
}
