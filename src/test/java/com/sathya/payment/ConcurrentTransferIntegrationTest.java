
package com.sathya.payment;

import com.sathya.payment.dto.AccountResponse;
import com.sathya.payment.dto.CreateAccountRequest;
import com.sathya.payment.dto.DepositRequest;
import com.sathya.payment.dto.TransferRequest;
import com.sathya.payment.exception.InsufficientFundsException;
import com.sathya.payment.service.AccountService;
import com.sathya.payment.service.TransferService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class ConcurrentTransferIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    @Test
    void concurrentTransfersShouldNotAllowOverspending() throws Exception {

        // 1. Create three accounts.
        AccountResponse alice = accountService.createAccount(
                new CreateAccountRequest("alice-concurrent", "USD")
        );

        AccountResponse bob = accountService.createAccount(
                new CreateAccountRequest("bob-concurrent", "USD")
        );

        AccountResponse charlie = accountService.createAccount(
                new CreateAccountRequest("charlie-concurrent", "USD")
        );

        // 2. Give Alice $100.
        accountService.deposit(
                alice.id(),
                new DepositRequest(new BigDecimal("100"))
        );

        // 3. Prepare two transfers to start together.
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> transferA = executor.submit(() -> {
                ready.countDown();
                start.await();

                try {
                    transferService.transfer(
                            "CONCURRENT-TRANSFER-A",
                            new TransferRequest(
                                    alice.id(),
                                    bob.id(),
                                    new BigDecimal("80")
                            )
                    );
                    return true;
                } catch (InsufficientFundsException exception) {
                    return false;
                }
            });

            Future<Boolean> transferB = executor.submit(() -> {
                ready.countDown();
                start.await();

                try {
                    transferService.transfer(
                            "CONCURRENT-TRANSFER-B",
                            new TransferRequest(
                                    alice.id(),
                                    charlie.id(),
                                    new BigDecimal("80")
                            )
                    );
                    return true;
                } catch (InsufficientFundsException exception) {
                    return false;
                }
            });

            // Wait until both threads are ready.
            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "Both transfer threads should be ready"
            );

            // Release both threads.
            start.countDown();

            boolean transferASucceeded =
                    transferA.get(30, TimeUnit.SECONDS);

            boolean transferBSucceeded =
                    transferB.get(30, TimeUnit.SECONDS);

            // Exactly one transfer must succeed.
            assertTrue(
                    transferASucceeded != transferBSucceeded,
                    "Exactly one transfer should succeed"
            );

            // Alice must have exactly $20 remaining.
            AccountResponse updatedAlice =
                    accountService.getAccount(alice.id());

            assertEquals(
                    0,
                    updatedAlice.balance().compareTo(new BigDecimal("20")),
                    "Alice's final balance should be $20"
            );

        } finally {
            executor.shutdownNow();
        }
    }
}
