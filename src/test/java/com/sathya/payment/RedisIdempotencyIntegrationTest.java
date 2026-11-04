package com.sathya.payment;

import com.sathya.payment.gateway.RedisIdempotencyGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class RedisIdempotencyIntegrationTest {

    @Autowired
    private RedisIdempotencyGateway gateway;

    private final List<String> keysToDelete = new ArrayList<>();

    @AfterEach
    void cleanRedis() {
        keysToDelete.forEach(gateway::release);
        keysToDelete.clear();
    }

    @Test
    void valueShouldExpireInRealRedis() throws Exception {
        String key = newKey("expiry");

        assertTrue(gateway.setIfAbsent(key, "PROCESSING", Duration.ofMillis(300)));
        assertEquals("PROCESSING", gateway.get(key));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (gateway.get(key) != null && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }

        assertNull(gateway.get(key), "Redis must remove the key when its TTL expires");
    }

    @Test
    void rawAndPrefixedKeysMustRemainIndependentInRealRedis() {
        String rawKey = newKey("prefix");
        String prefixedKey = "idempotency:" + rawKey;
        keysToDelete.add(prefixedKey);

        assertTrue(gateway.setIfAbsent(rawKey, "FIRST", Duration.ofMinutes(1)));
        assertTrue(gateway.setIfAbsent(prefixedKey, "SECOND", Duration.ofMinutes(1)));
        assertEquals("FIRST", gateway.get(rawKey));
        assertEquals("SECOND", gateway.get(prefixedKey));
        gateway.release(rawKey);
        assertNull(gateway.get(rawKey));
        assertEquals("SECOND", gateway.get(prefixedKey));
    }

    @Test
    void absoluteExpiryInThePastMustRemoveProcessingMarker() {
        String key = newKey("expired-at-commit");
        gateway.set(key, "PROCESSING", Duration.ofMinutes(5));

        gateway.setUntil(key, "COMPLETED", Instant.now().minusSeconds(1));

        assertNull(gateway.get(key));
        assertFalse(gateway.exists(key));
    }

    @Test
    void concurrentClaimsShouldHaveExactlyOneWinnerInRealRedis() throws Exception {
        String key = newKey("concurrency");
        int contenders = 12;
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(contenders);

        try {
            List<Future<Boolean>> claims = new ArrayList<>();
            for (int i = 0; i < contenders; i++) {
                claims.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return gateway.setIfAbsent(key, "PROCESSING", Duration.ofSeconds(10));
                }));
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            long winners = 0;
            for (Future<Boolean> claim : claims) {
                if (claim.get(5, TimeUnit.SECONDS)) {
                    winners++;
                }
            }

            assertEquals(1, winners, "Redis SET NX must allow exactly one claimant");
            assertEquals("PROCESSING", gateway.get(key));
        } finally {
            executor.shutdownNow();
        }
    }

    private String newKey(String scenario) {
        String key = "integration:" + scenario + ":" + UUID.randomUUID();
        keysToDelete.add(key);
        return key;
    }
}
