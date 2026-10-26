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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = {
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1",
        "spring.data.redis.connect-timeout=250ms",
        "spring.data.redis.timeout=250ms"
})
class RedisOutageIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    @Test
    void transferShouldRemainIdempotentWhileRedisIsUnavailable() {
        String suffix = UUID.randomUUID().toString();
        AccountResponse source = accountService.createAccount(
                new CreateAccountRequest("redis-down-source-" + suffix, "USD"));
        AccountResponse target = accountService.createAccount(
                new CreateAccountRequest("redis-down-target-" + suffix, "USD"));
        accountService.deposit(source.id(), new DepositRequest(new BigDecimal("100")));

        String key = "REDIS-DOWN-" + suffix;
        TransferRequest request = new TransferRequest(
                source.id(), target.id(), new BigDecimal("30"));

        TransferResponse first = transferService.transfer(key, request);
        TransferResponse retry = transferService.transfer(key, request);

        assertNotNull(first);
        assertEquals(first.transactionId(), retry.transactionId(),
                "PostgreSQL must return the original response during a Redis outage");
        assertEquals(0, accountService.getAccount(source.id()).balance()
                .compareTo(new BigDecimal("70")));
        assertEquals(0, accountService.getAccount(target.id()).balance()
                .compareTo(new BigDecimal("30")));
    }
}
