package com.example.reviewbot.queue;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** "Do this once" keys in Redis, e.g. so a redelivered comment webhook is not answered twice. */
@Component
public class IdempotencyKeys {

    private static final String PREFIX = "reviewbot:once:";

    private final StringRedisTemplate redis;

    public IdempotencyKeys(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** True for the first caller of {@code key} within {@code ttl}. */
    public boolean claim(String key, Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(PREFIX + key, "1", ttl));
    }

    public void release(String key) {
        redis.delete(PREFIX + key);
    }
}
