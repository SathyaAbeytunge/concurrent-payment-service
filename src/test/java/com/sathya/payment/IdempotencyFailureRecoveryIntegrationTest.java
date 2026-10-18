package com.sathya.payment;

import com.sathya.payment.dto.AccountResponse;
import com.sathya.payment.dto.CreateAccountRequest;
import com.sathya.payment.dto.DepositRequest;
import com.sathya.payment.dto.TransferRequest;
import com.sathya.payment.dto.TransferResponse;
import com.sathya.payment.entity.IdempotencyKey;
import com.sathya.payment.repository.IdempotencyKeyRepository;
import com.sathya.payment.repository.TransactionRepository;
import com.sathya.payment.service.AccountService;
import com.sathya.payment.service.TransferService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class IdempotencyFailureRecoveryIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    
    @MockitoSpyBean
    private TransactionRepository transactionRepository;

    /** Reset the spy after every test to avoid cross-test pollution. */
    @AfterEach
    void resetSpy() {
        Mockito.reset(transactionRepository);
    }

    @Test
    void atomicRollbackMustReleaseIdempotencyKeyAllowingRetry() {

        String suffix = UUID.randomUUID().toString();
        String idemKey = "RECOVERY-ROLLBACK-" + suffix;

        // 1. Create accounts and fund Alice with $100.
        AccountResponse alice = accountService.createAccount(
                new CreateAccountRequest("alice-recovery-" + suffix, "USD")
        );
        AccountResponse bob = accountService.createAccount(
                new CreateAccountRequest("bob-recovery-" + suffix, "USD")
        );
        accountService.deposit(alice.id(), new DepositRequest(new BigDecimal("100")));

        TransferRequest request = new TransferRequest(
                alice.id(), bob.id(), new BigDecimal("40")
        );

        // 2. Make the first call to transactionRepository.save() throw an
        //    unexpected RuntimeException (simulating a DB error mid-transfer).
        //    doCallRealMethod() cannot be used on JDK interface proxies, so we
        //    stub only the throw here and reset the spy after the failure so
        //    the retry gets the real implementation.
        doThrow(new RuntimeException("Simulated unexpected database failure"))
                .when(transactionRepository).save(any());

        // 3. First transfer attempt — must propagate the simulated exception.
        assertThrows(
                RuntimeException.class,
                () -> transferService.transfer(idemKey, request),
                "The simulated database failure must propagate as RuntimeException"
        );

        // Remove the stub so subsequent calls delegate to the real implementation.
        Mockito.reset(transactionRepository);

        // 4. The idempotency key must NOT be in the database.
        //    Spring's @Transactional rolled back the entire unit of work,
        //    including the idempotency key INSERT, leaving no stuck row.
        Optional<IdempotencyKey> stuckKey =
                idempotencyKeyRepository.findById(idemKey);

        assertTrue(
                stuckKey.isEmpty(),
                "The idempotency key must NOT be stuck in PROCESSING after "
                        + "a rolled-back transaction; found: "
                        + stuckKey.map(k -> k.getStatus().name()).orElse("none")
        );

        // 5. Alice's balance must be unchanged — no debit committed.
        AccountResponse aliceAfterFailure = accountService.getAccount(alice.id());
        assertEquals(
                0,
                aliceAfterFailure.balance().compareTo(new BigDecimal("100")),
                "Alice's balance must be unchanged after the failed transfer"
        );

        // 6. Retry with the exact same key — must now succeed.
        TransferResponse retryResponse = assertDoesNotThrow(
                () -> transferService.transfer(idemKey, request),
                "The retry with the same key must succeed after a rollback"
        );

        assertNotNull(retryResponse, "The retry response must not be null");

        // 7. Now Alice must have been debited exactly once.
        AccountResponse aliceAfterRetry = accountService.getAccount(alice.id());
        AccountResponse bobAfterRetry   = accountService.getAccount(bob.id());

        assertEquals(
                0,
                aliceAfterRetry.balance().compareTo(new BigDecimal("60")),
                "Alice must be debited exactly once ($40): expected $60"
        );
        assertEquals(
                0,
                bobAfterRetry.balance().compareTo(new BigDecimal("40")),
                "Bob must have received the $40 from the successful retry"
        );
    }

    @Test
    void expiredProcessingKeyMustBeOverriddenAllowingRetry() {

        String suffix = UUID.randomUUID().toString();
        String idemKey = "RECOVERY-EXPIRED-" + suffix;

        // 1. Create accounts and fund Alice with $100.
        AccountResponse alice = accountService.createAccount(
                new CreateAccountRequest("alice-expired-" + suffix, "USD")
        );
        AccountResponse bob = accountService.createAccount(
                new CreateAccountRequest("bob-expired-" + suffix, "USD")
        );
        accountService.deposit(alice.id(), new DepositRequest(new BigDecimal("100")));

        // 2. Pre-seed an ALREADY-EXPIRED PROCESSING row.
        //    This simulates a process that committed the idempotency row,
        //    then crashed before finishing the transfer.  The row has no
        //    responseBody, so normal retry logic would return 409 — but
        //    only while the key is still within its TTL.
        String requestData = alice.id() + "|" + bob.id() + "|25";
        String requestHash;
        try {
            requestHash = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(requestData.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            );
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }

        idempotencyKeyRepository.save(
                IdempotencyKey.builder()
                        .key(idemKey)
                        .requestHash(requestHash)
                        .status(IdempotencyKey.IdempotencyStatus.PROCESSING)
                        .createdAt(OffsetDateTime.now().minusHours(25))
                        .expiresAt(OffsetDateTime.now().minusSeconds(1)) // already expired
                        .build()
        );

        // 3. Verify the stuck row exists with PROCESSING status.
        IdempotencyKey stuckRow = idempotencyKeyRepository
                .findById(idemKey)
                .orElseThrow(() -> new AssertionError("Pre-seeded key not found"));

        assertEquals(
                IdempotencyKey.IdempotencyStatus.PROCESSING,
                stuckRow.getStatus(),
                "Pre-seeded row must be in PROCESSING status"
        );

        assertTrue(
                stuckRow.getExpiresAt().isBefore(OffsetDateTime.now()),
                "Pre-seeded row must already be expired"
        );

        // 4. Retry with the same key — tryCreate sees the expired row and
        //    overwrites it (ON CONFLICT DO UPDATE WHERE expired), returning 1.
        //    TransferService treats this as a fresh insertion and runs the transfer.
        TransferRequest request = new TransferRequest(
                alice.id(), bob.id(), new BigDecimal("25")
        );

        TransferResponse response = assertDoesNotThrow(
                () -> transferService.transfer(idemKey, request),
                "Retry on an expired PROCESSING key must succeed"
        );

        assertNotNull(response, "Retry response must not be null");

        // 5. The idempotency key must now be COMPLETED.
        IdempotencyKey completedKey = idempotencyKeyRepository
                .findById(idemKey)
                .orElseThrow(() -> new AssertionError("Key must still exist after retry"));

        assertEquals(
                IdempotencyKey.IdempotencyStatus.COMPLETED,
                completedKey.getStatus(),
                "Idempotency key must be COMPLETED after a successful retry"
        );

        assertNotNull(
                completedKey.getResponseBody(),
                "Completed idempotency key must have a cached responseBody"
        );

        // 6. Verify the transfer happened exactly once.
        AccountResponse updatedAlice = accountService.getAccount(alice.id());
        AccountResponse updatedBob   = accountService.getAccount(bob.id());

        assertEquals(
                0,
                updatedAlice.balance().compareTo(new BigDecimal("75")),
                "Alice must have been debited once: $100 - $25 = $75"
        );
        assertEquals(
                0,
                updatedBob.balance().compareTo(new BigDecimal("25")),
                "Bob must have received $25 from the successful retry"
        );
    }
}
