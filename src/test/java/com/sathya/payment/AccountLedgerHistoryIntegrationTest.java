package com.sathya.payment;

import com.sathya.payment.dto.AccountResponse;
import com.sathya.payment.dto.CreateAccountRequest;
import com.sathya.payment.dto.DepositRequest;
import com.sathya.payment.dto.TransferRequest;
import com.sathya.payment.dto.TransferResponse;
import com.sathya.payment.service.AccountService;
import com.sathya.payment.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Defines the ledger history API before its implementation (the red phase of TDD). */
@SpringBootTest
class AccountLedgerHistoryIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private WebApplicationContext applicationContext;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
    }

    @Test
    void historyShouldShowOnlyTheAccountsEntriesNewestFirstWithoutRetryDuplicates() throws Exception {
        AccountResponse alice = createAccount("alice");
        AccountResponse bob = createAccount("bob");
        accountService.deposit(alice.id(), new DepositRequest(new BigDecimal("100")));

        TransferResponse first = transferService.transfer("history-first-" + UUID.randomUUID(),
                new TransferRequest(alice.id(), bob.id(), new BigDecimal("25")));
        String secondKey = "history-second-" + UUID.randomUUID();
        TransferRequest secondRequest = new TransferRequest(alice.id(), bob.id(), new BigDecimal("10"));
        TransferResponse second = transferService.transfer(secondKey, secondRequest);
        transferService.transfer(secondKey, secondRequest);

        mockMvc.perform(get("/accounts/{accountId}/ledger", alice.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].transactionId").value(second.transactionId().toString()))
                .andExpect(jsonPath("$[0].type").value("DEBIT"))
                .andExpect(jsonPath("$[0].amount").value(10.0))
                .andExpect(jsonPath("$[0].balanceAfter").value(65.0))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[1].transactionId").value(first.transactionId().toString()))
                .andExpect(jsonPath("$[1].type").value("DEBIT"))
                .andExpect(jsonPath("$[1].amount").value(25.0))
                .andExpect(jsonPath("$[1].balanceAfter").value(75.0));

        mockMvc.perform(get("/accounts/{accountId}/ledger", bob.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].transactionId").value(second.transactionId().toString()))
                .andExpect(jsonPath("$[0].type").value("CREDIT"))
                .andExpect(jsonPath("$[0].amount").value(10.0))
                .andExpect(jsonPath("$[0].balanceAfter").value(35.0))
                .andExpect(jsonPath("$[1].transactionId").value(first.transactionId().toString()))
                .andExpect(jsonPath("$[1].type").value("CREDIT"))
                .andExpect(jsonPath("$[1].amount").value(25.0))
                .andExpect(jsonPath("$[1].balanceAfter").value(25.0));
    }

    @Test
    void accountWithOnlyDemoFundingShouldHaveEmptyHistory() throws Exception {
        AccountResponse account = createAccount("funded");
        accountService.deposit(account.id(), new DepositRequest(new BigDecimal("100")));

        mockMvc.perform(get("/accounts/{accountId}/ledger", account.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void unknownAccountShouldReturnAccountNotFound() throws Exception {
        UUID missingId = UUID.randomUUID();

        mockMvc.perform(get("/accounts/{accountId}/ledger", missingId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Account not found: " + missingId));
    }

    private AccountResponse createAccount(String name) {
        return accountService.createAccount(new CreateAccountRequest("history-" + name + "-" + UUID.randomUUID(), "USD"));
    }
}
