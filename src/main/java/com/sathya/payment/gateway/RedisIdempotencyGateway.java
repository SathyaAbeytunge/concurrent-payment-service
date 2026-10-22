package com.sathya.payment.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisIdempotencyGateway {

    private static final String KEY_PREFIX = "idempotency:";

    private final StringRedisTemplate redisTemplate;

    // Atomically claim the key (SET NX) with TTL to prevent concurrent duplicate processing
    public boolean setIfAbsent(String key, String value, Duration timeout) {
        String redisKey = buildKey(key);
        log.debug("Attempting setIfAbsent for key: {} with TTL: {}", redisKey, timeout);
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, value, timeout);
        return Boolean.TRUE.equals(acquired);
    }

    public String get(String key) {
        String redisKey = buildKey(key);
        log.debug("Fetching value for key: {}", redisKey);
        return redisTemplate.opsForValue().get(redisKey);
    }

    public void set(String key, String value, Duration timeout) {
        String redisKey = buildKey(key);
        log.debug("Setting value for key: {} with TTL: {}", redisKey, timeout);
        redisTemplate.opsForValue().set(redisKey, value, timeout);
    }

    // Release lock on failure or rollback so client can retry
    public boolean delete(String key) {
        String redisKey = buildKey(key);
        log.debug("Deleting key: {}", redisKey);
        Boolean deleted = redisTemplate.delete(redisKey);
        return Boolean.TRUE.equals(deleted);
    }

    public boolean exists(String key) {
        String redisKey = buildKey(key);
        Boolean hasKey = redisTemplate.hasKey(redisKey);
        return Boolean.TRUE.equals(hasKey);
    }

    public Long getExpire(String key) {
        String redisKey = buildKey(key);
        return redisTemplate.getExpire(redisKey);
    }

    public boolean acquire(String key, String value, Duration timeout) {
        return setIfAbsent(key, value, timeout);
    }

    public void saveResponse(String key, String responseBody, Duration timeout) {
        set(key, responseBody, timeout);
    }

    public String getCachedResponse(String key) {
        return get(key);
    }

    public boolean release(String key) {
        return delete(key);
    }

    private String buildKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency key must not be null or blank");
        }
        if (key.startsWith(KEY_PREFIX)) {
            return key;
        }
        return KEY_PREFIX + key;
    }
}
