package com.example.reviewbot.webhook;

/** What the endpoint did with a delivery; echoed in the response so it shows up in GitHub's delivery log. */
public record WebhookOutcome(Status status, String detail) {

    public enum Status {
        /** A job was queued; answered with 202. */
        QUEUED,
        /** Handled synchronously (installation changes); answered with 200. */
        PROCESSED,
        /** Nothing to do for this event or action; answered with 200. */
        IGNORED
    }

    static WebhookOutcome queued(String detail) {
        return new WebhookOutcome(Status.QUEUED, detail);
    }

    static WebhookOutcome processed(String detail) {
        return new WebhookOutcome(Status.PROCESSED, detail);
    }

    static WebhookOutcome ignored(String detail) {
        return new WebhookOutcome(Status.IGNORED, detail);
    }
}
