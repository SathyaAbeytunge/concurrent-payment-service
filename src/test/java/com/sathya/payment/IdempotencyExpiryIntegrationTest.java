
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@SpringBootTest
class IdempotencyExpiryIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void expiredIdempotencyKeyShouldBeReusable() {

        String suffix = UUID.randomUUID().toString();

        // 1. Create three accounts.
        AccountResponse alice = accountService.createAccount(
                new CreateAccountRequest("alice-expiry-" + suffix, "USD")
        );

        AccountResponse bob = accountService.createAccount(
                new CreateAccountRequest("bob-expiry-" + suffix, "USD")
        );

        AccountResponse charlie = accountService.createAccount(
                new CreateAccountRequest("charlie-expiry-" + suffix, "USD")
        );

        // 2. Give Alice $100.
        accountService.deposit(
                alice.id(),
                new DepositRequest(new BigDecimal("100"))
        );

        String key = "EXPIRY-TEST-" + suffix;

        // 3. Make the first transfer: Alice sends $10 to Bob.
        TransferResponse firstResponse = transferService.transfer(
                key,
                new TransferRequest(
                        alice.id(),
                        bob.id(),
                        new BigDecimal("10")
                )
        );

        // 4. Retry the same request before expiry.
        // It should return the original result without transferring again.
        TransferResponse retryResponse = transferService.transfer(
                key,
                new TransferRequest(
                        alice.id(),
                        bob.id(),
                        new BigDecimal("10")
                )
        );

        assertEquals(
                firstResponse.transactionId(),
                retryResponse.transactionId(),
                "A retry should return the original transaction"
        );

        assertEquals(
                0,
                accountService.getAccount(alice.id())
                        .balance().compareTo(new BigDecimal("90")),
                "Alice should not be charged twice"
        );

        // 5. Simulate expiry by moving the expiry time into the past.
        int updated = jdbcTemplate.update(
                """
                UPDATE idempotency_keys
                SET expires_at = clock_timestamp() - INTERVAL '1 second'
                WHERE key = ?
                """,
                key
        );

        assertEquals(1, updated, "The idempotency key should exist");

        // 6. Reuse the expired key for a different transfer.
        // This time Alice sends $15 to Charlie.
        TransferResponse secondResponse = transferService.transfer(
                key,
                new TransferRequest(
                        alice.id(),
                        charlie.id(),
                        new BigDecimal("15")
                )
        );

        assertNotEquals(
                firstResponse.transactionId(),
                secondResponse.transactionId(),
                "The new request should create a new transaction"
        );

        // 7. Verify the final balances.
        assertEquals(
                0,
                accountService.getAccount(alice.id())
                        .balance().compareTo(new BigDecimal("75")),
                "Alice should have $75 remaining"
        );

        assertEquals(
                0,
                accountService.getAccount(bob.id())
                        .balance().compareTo(new BigDecimal("10")),
                "Bob should receive $10 from the first transfer"
        );

        assertEquals(
                0,
                accountService.getAccount(charlie.id())
                        .balance().compareTo(new BigDecimal("15")),
                "Charlie should receive $15 from the second transfer"
        );
    }
}
