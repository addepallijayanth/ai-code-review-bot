package com.example.reviewbot.queue;

import org.slf4j.MDC;

/** Puts the job's repository, pull request and delivery id on every log line written while it runs. */
final class MdcScope implements AutoCloseable {

    private static final String[] KEYS = {"delivery", "repo", "pr", "attempt"};

    private MdcScope() {
    }

    static MdcScope forJob(ReviewJob job, int attempt) {
        MDC.put("delivery", job.deliveryId());
        MDC.put("repo", job.repo());
        MDC.put("pr", "#" + job.prNumber());
        MDC.put("attempt", String.valueOf(attempt));
        return new MdcScope();
    }

    @Override
    public void close() {
        for (String key : KEYS) {
            MDC.remove(key);
        }
    }
}
