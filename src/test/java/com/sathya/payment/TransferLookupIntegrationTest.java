package com.sathya.payment;

import com.sathya.payment.dto.CreateAccountRequest;
import com.sathya.payment.dto.DepositRequest;
import com.sathya.payment.dto.TransferRequest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class TransferLookupIntegrationTest {
    @Autowired private AccountService accountService;
    @Autowired private TransferService transferService;
    @Autowired private WebApplicationContext context;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void lookupReturnsStoredReceiptWithoutMovingMoneyAgain() throws Exception {
        var source = accountService.createAccount(new CreateAccountRequest("lookup-" + UUID.randomUUID(), "USD"));
        var target = accountService.createAccount(new CreateAccountRequest("lookup-" + UUID.randomUUID(), "USD"));
        accountService.deposit(source.id(), new DepositRequest(new BigDecimal("10")));
        var receipt = transferService.transfer("lookup-" + UUID.randomUUID(),
                new TransferRequest(source.id(), target.id(), new BigDecimal("1.2345")));

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(get("/transfers/{id}", receipt.transactionId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.transactionId").value(receipt.transactionId().toString()))
                    .andExpect(jsonPath("$.sourceAccountId").value(source.id().toString()))
                    .andExpect(jsonPath("$.targetAccountId").value(target.id().toString()))
                    .andExpect(jsonPath("$.amount").value(1.2345))
                    .andExpect(jsonPath("$.currency").value("USD"))
                    .andExpect(jsonPath("$.status").value("SUCCESS"))
                    .andExpect(jsonPath("$.createdAt").isNotEmpty());
        }
        assertEquals(0, new BigDecimal("8.7655").compareTo(accountService.getAccount(source.id()).balance()));
        assertEquals(0, new BigDecimal("1.2345").compareTo(accountService.getAccount(target.id()).balance()));
        assertEquals(1, accountService.getLedgerHistory(source.id()).size());
        assertEquals(1, accountService.getLedgerHistory(target.id()).size());
    }

    @Test
    void unknownTransferReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        mockMvc.perform(get("/transfers/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Transfer not found: " + id));
    }

    @Test
    void malformedIdReturns400() throws Exception {
        mockMvc.perform(get("/transfers/not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
}
