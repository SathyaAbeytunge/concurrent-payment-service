package com.sathya.payment;

import com.sathya.payment.dto.CreateAccountRequest;
import com.sathya.payment.dto.DepositRequest;
import com.sathya.payment.dto.TransferRequest;
import com.sathya.payment.service.AccountService;
import com.sathya.payment.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exercises the hosted schema configuration against the local test services. */
@ActiveProfiles("cloud")
@SpringBootTest(properties = {
        "DB_JDBC_URL=jdbc:postgresql://localhost:5433/paymentdb",
        "DB_USERNAME=paymentuser",
        "DB_PASSWORD=paymentpass",
        "REDIS_URL=redis://localhost:6379"
})
class CloudProfileIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void hostedProfileShouldMigrateAndTransferWithinItsOwnSchema() {
        assertEquals("payments", jdbcTemplate.queryForObject("SELECT current_schema()", String.class));
        String suffix = UUID.randomUUID().toString();
        var alice = accountService.createAccount(new CreateAccountRequest("cloud-alice-" + suffix, "USD"));
        var bob = accountService.createAccount(new CreateAccountRequest("cloud-bob-" + suffix, "USD"));
        accountService.deposit(alice.id(), new DepositRequest(new BigDecimal("100")));

        String key = "cloud-transfer-" + suffix;
        var request = new TransferRequest(alice.id(), bob.id(), new BigDecimal("10"));
        var first = transferService.transfer(key, request);
        var retry = transferService.transfer(key, request);

        assertEquals(first.transactionId(), retry.transactionId());
        assertEquals(0, accountService.getAccount(alice.id()).balance().compareTo(new BigDecimal("90")));
        assertEquals(0, accountService.getAccount(bob.id()).balance().compareTo(new BigDecimal("10")));
        assertEquals(first.transactionId(), accountService.getLedgerHistory(alice.id()).getFirst().transactionId());
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payments.ledger_entries WHERE transaction_id = ?",
                Integer.class, first.transactionId()));
    }
}
