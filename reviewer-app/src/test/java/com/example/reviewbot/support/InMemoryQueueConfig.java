package com.example.reviewbot.support;

import com.example.reviewbot.queue.Envelope;
import com.example.reviewbot.queue.IdempotencyKeys;
import com.example.reviewbot.queue.JobQueue;
import com.example.reviewbot.queue.QueuedJob;
import com.example.reviewbot.queue.ReviewJob;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Stand-ins for the Redis-backed queue and idempotency keys, used only when the integration tests
 * run on a machine where neither Docker nor a native Redis binary is available. In CI the tests run
 * against real Redis.
 */
@TestConfiguration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "reviewbot.test.in-memory-queue", havingValue = "true")
public class InMemoryQueueConfig {

    @Bean
    @Primary
    JobQueue inMemoryJobQueue() {
        return new InMemoryJobQueue();
    }

    @Bean
    @Primary
    IdempotencyKeys inMemoryIdempotencyKeys() {
        return new IdempotencyKeys(null) {
            private final Map<String, Boolean> claimed = new ConcurrentHashMap<>();

            @Override
            public boolean claim(String key, Duration ttl) {
                return claimed.putIfAbsent(key, Boolean.TRUE) == null;
            }

            @Override
            public void release(String key) {
                claimed.remove(key);
            }
        };
    }

    static final class InMemoryJobQueue implements JobQueue {

        private final BlockingQueue<QueuedJob> ready = new LinkedBlockingQueue<>();
        /** Jobs enqueued and not yet acknowledged or dead-lettered. */
        private final AtomicLong outstanding = new AtomicLong();

        @Override
        public void enqueue(ReviewJob job) {
            outstanding.incrementAndGet();
            ready.add(new QueuedJob(UUID.randomUUID().toString(), new Envelope(UUID.randomUUID().toString(), 1, job), 1));
        }

        @Override
        public void initialize() {
            // nothing to create
        }

        @Override
        public List<QueuedJob> poll(String consumer, int count) {
            try {
                QueuedJob job = ready.poll(200, TimeUnit.MILLISECONDS);
                return job == null ? List.of() : List.of(job);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            }
        }

        @Override
        public void ack(QueuedJob job) {
            outstanding.decrementAndGet();
        }

        @Override
        public void retryLater(QueuedJob job, Duration delay) {
            // The retry replaces the failed delivery, so the outstanding count does not change.
            ready.add(new QueuedJob(UUID.randomUUID().toString(), job.envelope().nextAttempt(), 1));
        }

        @Override
        public void deadLetter(QueuedJob job, String reason) {
            outstanding.decrementAndGet();
        }

        @Override
        public long promoteDueRetries() {
            return 0;
        }

        @Override
        public List<QueuedJob> claimAbandoned(String consumer, int count) {
            return List.of();
        }

        @Override
        public long backlog() {
            return outstanding.get();
        }
    }
}
