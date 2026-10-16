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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that two accounts simultaneously transferring money to each other
 * do not deadlock.
 *
 * <p>The failure mode this test protects against is a circular lock-wait:
 * <ol>
 *   <li>Thread 1 (Alice → Bob) locks Alice, then waits for Bob.</li>
 *   <li>Thread 2 (Bob → Alice) locks Bob, then waits for Alice.</li>
 * </ol>
 * PostgreSQL detects the cycle and terminates one of the transactions
 * with SQL state {@code 40P01} (deadlock_detected), surfaced in Spring as
 * {@code CannotAcquireLockException}.
 *
 * <p>The fix is to acquire locks in a globally consistent UUID order so both
 * threads always lock the lower UUID first, eliminating the circular wait.
 */
@SpringBootTest
class DeadlockIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    @Test
    void simultaneousReciprocalTransfersShouldNotDeadlock() throws Exception {

        String suffix = UUID.randomUUID().toString();

        // 1. Create Alice and Bob with $100 each.
        AccountResponse alice = accountService.createAccount(
                new CreateAccountRequest("alice-deadlock-" + suffix, "USD")
        );

        AccountResponse bob = accountService.createAccount(
                new CreateAccountRequest("bob-deadlock-" + suffix, "USD")
        );

        accountService.deposit(
                alice.id(),
                new DepositRequest(new BigDecimal("100"))
        );

        accountService.deposit(
                bob.id(),
                new DepositRequest(new BigDecimal("100"))
        );

        // 2. Latch that makes both threads fire at exactly the same moment.
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // Thread 1: Alice sends $20 to Bob.
            Future<TransferResponse> aliceToBob = executor.submit(() -> {
                ready.countDown();
                start.await();

                return transferService.transfer(
                        "DEADLOCK-ALICE-BOB-" + suffix,
                        new TransferRequest(
                                alice.id(),
                                bob.id(),
                                new BigDecimal("20")
                        )
                );
            });

            // Thread 2: Bob sends $30 to Alice — opposite direction.
            Future<TransferResponse> bobToAlice = executor.submit(() -> {
                ready.countDown();
                start.await();

                return transferService.transfer(
                        "DEADLOCK-BOB-ALICE-" + suffix,
                        new TransferRequest(
                                bob.id(),
                                alice.id(),
                                new BigDecimal("30")
                        )
                );
            });

            // Wait until both threads are staged and ready.
            assertTrue(
                    ready.await(10, TimeUnit.SECONDS),
                    "Both transfer threads should be ready within 10 seconds"
            );

            // Release both threads simultaneously.
            start.countDown();

            // 3. Both futures must complete without any deadlock exception.
            //    assertDoesNotThrow unwraps ExecutionException for us.
            TransferResponse responseA = assertDoesNotThrow(
                    () -> aliceToBob.get(30, TimeUnit.SECONDS),
                    "Alice → Bob transfer must not throw (no deadlock)"
            );

            TransferResponse responseB = assertDoesNotThrow(
                    () -> bobToAlice.get(30, TimeUnit.SECONDS),
                    "Bob → Alice transfer must not throw (no deadlock)"
            );

            assertNotNull(responseA, "Alice → Bob transfer response must not be null");
            assertNotNull(responseB, "Bob → Alice transfer response must not be null");

            // 4. Verify the final balances reflect both transfers applied correctly.
            //
            //    Alice:  100 - 20 (sent to Bob) + 30 (received from Bob) = 110
            //    Bob:    100 + 20 (received from Alice) - 30 (sent to Alice) = 90
            AccountResponse updatedAlice = accountService.getAccount(alice.id());
            AccountResponse updatedBob   = accountService.getAccount(bob.id());

            assertEquals(
                    0,
                    updatedAlice.balance().compareTo(new BigDecimal("110")),
                    "Alice's final balance must be $110 (100 - 20 + 30)"
            );

            assertEquals(
                    0,
                    updatedBob.balance().compareTo(new BigDecimal("90")),
                    "Bob's final balance must be $90 (100 + 20 - 30)"
            );

        } finally {
            executor.shutdownNow();
        }
    }
}
