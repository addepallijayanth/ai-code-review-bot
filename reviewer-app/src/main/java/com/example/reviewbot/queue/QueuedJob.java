package com.example.reviewbot.queue;

/**
 * A job delivered to this worker. {@code id} identifies this delivery in the queue (a stream entry
 * id for Redis); {@code deliveries} counts how often the queue handed it out, so a value above one
 * means a worker stopped mid-job.
 */
public record QueuedJob(String id, Envelope envelope, long deliveries) {
}
