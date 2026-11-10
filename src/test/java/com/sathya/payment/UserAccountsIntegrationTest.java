package com.sathya.payment;

import com.sathya.payment.dto.CreateAccountRequest;
import com.sathya.payment.dto.DepositRequest;
import com.sathya.payment.repository.AccountRepository;
import com.sathya.payment.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class UserAccountsIntegrationTest {
    @Autowired private AccountService accountService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private WebApplicationContext context;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void listsOnlyExactUsersAccountsNewestFirstWithCurrentBalances() throws Exception {
        String userId = "user-" + UUID.randomUUID();
        var older = accountService.createAccount(new CreateAccountRequest(userId, "USD"));
        var newer = accountService.createAccount(new CreateAccountRequest(userId, "EUR"));
        accountService.createAccount(new CreateAccountRequest(userId + "-other", "USD"));
        accountService.deposit(older.id(), new DepositRequest(new BigDecimal("12.3456")));

        // Set distinct timestamps explicitly: the test must not depend on execution speed.
        var olderEntity = accountRepository.findById(older.id()).orElseThrow();
        olderEntity.setCreatedAt(OffsetDateTime.parse("2025-01-01T00:00:00Z"));
        accountRepository.saveAndFlush(olderEntity);
        var newerEntity = accountRepository.findById(newer.id()).orElseThrow();
        newerEntity.setCreatedAt(OffsetDateTime.parse("2025-01-02T00:00:00Z"));
        accountRepository.saveAndFlush(newerEntity);

        mockMvc.perform(get("/accounts").param("userId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(newer.id().toString()))
                .andExpect(jsonPath("$[0].userId").value(userId))
                .andExpect(jsonPath("$[0].currency").value("EUR"))
                .andExpect(jsonPath("$[0].balance").value(0))
                .andExpect(jsonPath("$[1].id").value(older.id().toString()))
                .andExpect(jsonPath("$[1].currency").value("USD"))
                .andExpect(jsonPath("$[1].balance").value(12.3456));
    }

    @Test
    void userWithoutAccountsGetsEmptyArray() throws Exception {
        mockMvc.perform(get("/accounts").param("userId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void missingUserIdReturns400() throws Exception {
        mockMvc.perform(get("/accounts"))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklm"})
    void invalidUserIdReturns400(String userId) throws Exception {
        mockMvc.perform(get("/accounts").param("userId", userId))
                .andExpect(status().isBadRequest());
    }

    @Test
    void maximumLengthUserIdIsAccepted() throws Exception {
        String userId = "boundary-" + UUID.randomUUID() + "x".repeat(19);
        var account = accountService.createAccount(new CreateAccountRequest(userId, "USD"));
        mockMvc.perform(get("/accounts").param("userId", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(account.id().toString()));
    }
}
