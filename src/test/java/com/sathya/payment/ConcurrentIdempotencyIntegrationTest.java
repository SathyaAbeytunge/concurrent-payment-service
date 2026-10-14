package com.sathya.payment;

import com.sathya.payment.dto.AccountResponse;
import com.sathya.payment.dto.CreateAccountRequest;
import com.sathya.payment.dto.DepositRequest;
import com.sathya.payment.dto.TransferRequest;
import com.sathya.payment.dto.TransferResponse;
import com.sathya.payment.service.AccountService;
import com.sathya.payment.service.TransferService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ConcurrentIdempotencyIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    @Test
    void concurrentRequestsWithSameKeyShouldProcessTransferOnlyOnce()
            throws Exception {

        // 1. Create two accounts with unique names.
        String suffix = UUID.randomUUID().toString();

        AccountResponse alice = accountService.createAccount(
                new CreateAccountRequest("alice-idem-" + suffix, "USD")
        );

        AccountResponse bob = accountService.createAccount(
                new CreateAccountRequest("bob-idem-" + suffix, "USD")
        );

        // 2. Give Alice $100.
        accountService.deposit(
                alice.id(),
                new DepositRequest(new BigDecimal("100"))
        );

        // 3. Prepare two identical requests.
        String idempotencyKey = "CONCURRENT-IDEMPOTENCY-" + suffix;

        TransferRequest request = new TransferRequest(
                alice.id(),
                bob.id(),
                new BigDecimal("10")
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<TransferResponse> transferA = executor.submit(() -> {
                ready.countDown();
                start.await();

                return transferService.transfer(idempotencyKey, request);
            });

            Future<TransferResponse> transferB = executor.submit(() -> {
                ready.countDown();
                start.await();

                return transferService.transfer(idempotencyKey, request);
            });

            // Wait until both threads are ready.
            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "Both request threads should be ready"
            );

            // Let both requests start.
            start.countDown();

            TransferResponse responseA =
                    transferA.get(30, TimeUnit.SECONDS);

            TransferResponse responseB =
                    transferB.get(30, TimeUnit.SECONDS);

            // 4. Both requests must return the same transaction.
            assertNotNull(responseA);
            assertNotNull(responseB);

            assertEquals(
                    responseA.transactionId(),
                    responseB.transactionId(),
                    "Both requests should return the same transaction ID"
            );

            // 5. The transfer must happen only once.
            AccountResponse updatedAlice =
                    accountService.getAccount(alice.id());

            AccountResponse updatedBob =
                    accountService.getAccount(bob.id());

            assertEquals(
                    0,
                    updatedAlice.balance().compareTo(new BigDecimal("90")),
                    "Alice's balance should decrease by only $10"
            );

            assertEquals(
                    0,
                    updatedBob.balance().compareTo(new BigDecimal("10")),
                    "Bob's balance should increase by only $10"
            );

        } finally {
            executor.shutdownNow();
        }
    }
}
