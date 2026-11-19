package com.example.reviewbot.queue;

import com.example.reviewbot.config.QueueProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * {@link JobQueue} on Redis Streams.
 * <ul>
 *   <li>Jobs are appended to a stream and read through a consumer group shared by all instances,
 *       so each job goes to one worker and stays pending until that worker acknowledges it.</li>
 *   <li>A failed job is acknowledged and parked in a sorted set until its retry time; a Lua script
 *       moves due jobs back onto the stream atomically.</li>
 *   <li>Jobs left pending by a worker that died are claimed by another one after the visibility
 *       timeout.</li>
 *   <li>Jobs that keep failing end up in a dead-letter stream, together with the error.</li>
 * </ul>
 */
@Component
public class RedisJobQueue implements JobQueue {

    static final String FIELD = "envelope";

    private static final Logger log = LoggerFactory.getLogger(RedisJobQueue.class);

    private static final RedisScript<Long> PROMOTE_DUE = RedisScript.of("""
            local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[2]))
            for _, member in ipairs(due) do
              if redis.call('ZREM', KEYS[1], member) == 1 then
                redis.call('XADD', KEYS[2], '*', 'envelope', member)
              end
            end
            return #due
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final QueueProperties properties;
    private final Clock clock;

    public RedisJobQueue(StringRedisTemplate redis, ObjectMapper mapper, QueueProperties properties, Clock clock) {
        this.redis = redis;
        this.mapper = mapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void enqueue(ReviewJob job) {
        add(properties.streamKey(), new Envelope(UUID.randomUUID().toString(), 1, job), Map.of());
    }

    @Override
    public void initialize() {
        try {
            redis.execute((RedisCallback<String>) this::createGroup);
            log.info("Created consumer group {} on {}", properties.consumerGroup(), properties.streamKey());
        } catch (RuntimeException e) {
            if (!hasMessage(e, "BUSYGROUP")) {
                throw e;
            }
        }
    }

    private String createGroup(RedisConnection connection) {
        // MKSTREAM: create the stream too; offset 0 so jobs added before the group existed are read.
        return connection.streamCommands().xGroupCreate(properties.streamKey().getBytes(StandardCharsets.UTF_8),
                properties.consumerGroup(), ReadOffset.from("0-0"), true);
    }

    @Override
    public List<QueuedJob> poll(String consumer, int count) {
        List<MapRecord<String, Object, Object>> records;
        try {
            records = redis.opsForStream().read(
                    Consumer.from(properties.consumerGroup(), consumer),
                    StreamReadOptions.empty().count(count).block(properties.pollTimeout()),
                    StreamOffset.create(properties.streamKey(), ReadOffset.lastConsumed()));
        } catch (RuntimeException e) {
            if (hasMessage(e, "NOGROUP")) {
                // Redis lost the stream or group (flushed, failed over to an empty replica): recreate it.
                log.warn("Consumer group {} is missing; recreating it", properties.consumerGroup());
                initialize();
                return List.of();
            }
            throw e;
        }
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream().map(record -> toJob(record, 1)).filter(Objects::nonNull).toList();
    }

    @Override
    public void ack(QueuedJob job) {
        RecordId id = RecordId.of(job.id());
        redis.opsForStream().acknowledge(properties.streamKey(), properties.consumerGroup(), id);
        redis.opsForStream().delete(properties.streamKey(), id);
    }

    @Override
    public void retryLater(QueuedJob job, Duration delay) {
        redis.opsForZSet().add(properties.delayedKey(), write(job.envelope().nextAttempt()),
                clock.millis() + delay.toMillis());
        ack(job);
    }

    @Override
    public void deadLetter(QueuedJob job, String reason) {
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("error", reason == null ? "unknown" : reason.substring(0, Math.min(reason.length(), 2000)));
        extra.put("failed_at", clock.instant().toString());
        add(properties.deadLetterKey(), job.envelope(), extra);
        ack(job);
    }

    @Override
    public long promoteDueRetries() {
        Long moved = redis.execute(PROMOTE_DUE, List.of(properties.delayedKey(), properties.streamKey()),
                String.valueOf(clock.millis()), "100");
        return moved == null ? 0 : moved;
    }

    @Override
    public List<QueuedJob> claimAbandoned(String consumer, int count) {
        PendingMessages pending = redis.opsForStream().pending(properties.streamKey(), properties.consumerGroup(),
                Range.unbounded(), count);
        List<PendingMessage> abandoned = pending.stream()
                .filter(message -> message.getElapsedTimeSinceLastDelivery().compareTo(properties.visibilityTimeout()) >= 0)
                .toList();
        if (abandoned.isEmpty()) {
            return List.of();
        }
        Map<String, Long> deliveries = abandoned.stream()
                .collect(Collectors.toMap(PendingMessage::getIdAsString, PendingMessage::getTotalDeliveryCount));
        List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(properties.streamKey(),
                properties.consumerGroup(), consumer, properties.visibilityTimeout(),
                abandoned.stream().map(PendingMessage::getId).toArray(RecordId[]::new));
        return claimed.stream()
                .map(record -> toJob(record, deliveries.getOrDefault(record.getId().getValue(), 1L) + 1))
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public long backlog() {
        // Acknowledged entries are deleted, so the stream holds exactly the waiting and in-progress jobs.
        Long stream = redis.opsForStream().size(properties.streamKey());
        Long delayed = redis.opsForZSet().size(properties.delayedKey());
        return (stream == null ? 0 : stream) + (delayed == null ? 0 : delayed);
    }

    private QueuedJob toJob(MapRecord<String, Object, Object> record, long deliveries) {
        Object raw = record.getValue().get(FIELD);
        try {
            return new QueuedJob(record.getId().getValue(), mapper.readValue(String.valueOf(raw), Envelope.class), deliveries);
        } catch (JsonProcessingException e) {
            // Never let one unreadable entry block the queue.
            log.error("Moving unreadable queue entry {} to the dead-letter stream: {}", record.getId(), e.getOriginalMessage());
            redis.opsForStream().add(StreamRecords.newRecord().in(properties.deadLetterKey())
                    .ofMap(Map.of(FIELD, String.valueOf(raw), "error", "unreadable: " + e.getOriginalMessage())));
            redis.opsForStream().acknowledge(properties.streamKey(), properties.consumerGroup(), record.getId());
            redis.opsForStream().delete(properties.streamKey(), record.getId());
            return null;
        }
    }

    private void add(String stream, Envelope envelope, Map<String, String> extra) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(FIELD, write(envelope));
        fields.putAll(extra);
        redis.opsForStream().add(StreamRecords.newRecord().in(stream).ofMap(fields));
    }

    private String write(Envelope envelope) {
        try {
            return mapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize job " + envelope.id(), e);
        }
    }

    /** Redis error replies are wrapped by Lettuce and Spring; look for the error code anywhere in the chain. */
    private static boolean hasMessage(Throwable error, String code) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(code)) {
                return true;
            }
        }
        return false;
    }
}
