package com.sathya.payment.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisIdempotencyGatewayTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisIdempotencyGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new RedisIdempotencyGateway(redisTemplate);
    }

    @Test
    void shouldReturnTrueWhenKeySuccessfullySet() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("idempotency:test-key"), eq("PROCESSING"), eq(Duration.ofMinutes(5))))
                .thenReturn(Boolean.TRUE);

        boolean result = gateway.setIfAbsent("test-key", "PROCESSING", Duration.ofMinutes(5));

        assertTrue(result);
        verify(valueOperations).setIfAbsent("idempotency:test-key", "PROCESSING", Duration.ofMinutes(5));
    }

    @Test
    void shouldReturnFalseWhenKeyAlreadyExists() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("idempotency:test-key"), eq("PROCESSING"), eq(Duration.ofMinutes(5))))
                .thenReturn(Boolean.FALSE);

        boolean result = gateway.setIfAbsent("test-key", "PROCESSING", Duration.ofMinutes(5));

        assertFalse(result);
    }

    @Test
    void getShouldReturnStoredValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("idempotency:test-key")).thenReturn("{\"id\":\"123\"}");

        String value = gateway.get("test-key");

        assertEquals("{\"id\":\"123\"}", value);
    }

    @Test
    void getShouldReturnNullWhenKeyDoesNotExist() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("idempotency:non-existent")).thenReturn(null);

        String value = gateway.get("non-existent");

        assertNull(value);
    }

    @Test
    void setShouldStoreValueWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        gateway.set("test-key", "COMPLETED", Duration.ofHours(24));

        verify(valueOperations).set("idempotency:test-key", "COMPLETED", Duration.ofHours(24));
    }

    @Test
    void deleteShouldRemoveKey() {
        when(redisTemplate.delete("idempotency:test-key")).thenReturn(Boolean.TRUE);

        boolean deleted = gateway.delete("test-key");

        assertTrue(deleted);
        verify(redisTemplate).delete("idempotency:test-key");
    }

    @Test
    void existsShouldCheckPresence() {
        when(redisTemplate.hasKey("idempotency:test-key")).thenReturn(Boolean.TRUE);

        boolean exists = gateway.exists("test-key");

        assertTrue(exists);
        verify(redisTemplate).hasKey("idempotency:test-key");
    }

    @Test
    void getExpireShouldReturnRemainingTtl() {
        when(redisTemplate.getExpire("idempotency:test-key")).thenReturn(300L);

        Long expire = gateway.getExpire("test-key");

        assertEquals(300L, expire);
        verify(redisTemplate).getExpire("idempotency:test-key");
    }

    @Test
    void domainConvenienceMethodsShouldDelegateCorrectly() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("idempotency:key-1"), eq("IN_FLIGHT"), eq(Duration.ofMinutes(2))))
                .thenReturn(Boolean.TRUE);
        when(valueOperations.get("idempotency:key-1")).thenReturn("PAYLOAD");
        when(redisTemplate.delete("idempotency:key-1")).thenReturn(Boolean.TRUE);

        assertTrue(gateway.acquire("key-1", "IN_FLIGHT", Duration.ofMinutes(2)));
        gateway.saveResponse("key-1", "NEW_PAYLOAD", Duration.ofHours(1));
        assertEquals("PAYLOAD", gateway.getCachedResponse("key-1"));
        assertTrue(gateway.release("key-1"));

        verify(valueOperations).set("idempotency:key-1", "NEW_PAYLOAD", Duration.ofHours(1));
    }

    @Test
    void buildKeyShouldAvoidDoublePrefixing() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("idempotency:already-prefixed")).thenReturn("VAL");

        String val = gateway.get("idempotency:already-prefixed");

        assertEquals("VAL", val);
        verify(valueOperations).get("idempotency:already-prefixed");
    }

    @Test
    void invalidKeysShouldThrowIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> gateway.get(null));
        assertThrows(IllegalArgumentException.class, () -> gateway.get("   "));
    }
}