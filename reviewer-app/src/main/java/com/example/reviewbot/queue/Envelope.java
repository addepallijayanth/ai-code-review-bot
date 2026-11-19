package com.example.reviewbot.queue;

/** A job as stored in Redis: a stable id, the attempt number, and the job itself. */
public record Envelope(String id, int attempt, ReviewJob job) {

    public Envelope nextAttempt() {
        return new Envelope(id, attempt + 1, job);
    }
}
