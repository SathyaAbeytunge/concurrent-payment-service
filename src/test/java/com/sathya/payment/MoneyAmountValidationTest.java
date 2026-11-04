package com.sathya.payment;

import com.sathya.payment.controller.AccountController;
import com.sathya.payment.controller.TransferController;
import com.sathya.payment.dto.DepositRequest;
import com.sathya.payment.dto.TransferRequest;
import com.sathya.payment.exception.GlobalExceptionHandler;
import com.sathya.payment.service.AccountService;
import com.sathya.payment.service.TransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MoneyAmountValidationTest {

    private static final UUID SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TARGET_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String IDEMPOTENCY_KEY = "money-validation";

    private AccountService accountService;
    private TransferService transferService;
    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        transferService = mock(TransferService.class);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AccountController(accountService), new TransferController(transferService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void closeValidator() {
        validator.close();
    }

    // NUMERIC(19,4): smallest positive unit and largest representable amount,
    // plus ordinary values and exponent notation accepted by JSON.
    @ParameterizedTest
    @ValueSource(strings = {"0.0001", "1", "1.2300", "999999999999999", "999999999999999.9999", "1e-4", "1e14"})
    void depositAcceptsRepresentableAmountsWithoutRounding(String amount) throws Exception {
        mockMvc.perform(post("/accounts/{accountId}/deposit", SOURCE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + "}"))
                .andExpect(status().isOk());

        verify(accountService).deposit(SOURCE_ID, new DepositRequest(new BigDecimal(amount)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0001", "1", "1.2300", "999999999999999", "999999999999999.9999", "1e-4", "1e14"})
    void transferAcceptsRepresentableAmountsWithoutRounding(String amount) throws Exception {
        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(amount)))
                .andExpect(status().isOk());

        verify(transferService).transfer(IDEMPOTENCY_KEY,
                new TransferRequest(SOURCE_ID, TARGET_ID, new BigDecimal(amount)));
    }

    // Reject excess scale even when the extra digit is zero: requests must
    // explicitly stay within four decimal places rather than rely on rounding.
    @ParameterizedTest
    @ValueSource(strings = {"0.00001", "1.23456", "1.00000", "999999999999999.99999",
            "1000000000000000", "1000000000000000.0001", "1e15", "1e-5", "0", "-0.0001", "null"})
    void depositRejectsInvalidAmountsBeforeCallingService(String amount) throws Exception {
        mockMvc.perform(post("/accounts/{accountId}/deposit", SOURCE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").isNotEmpty());

        verifyNoInteractions(accountService, transferService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00001", "1.23456", "1.00000", "999999999999999.99999",
            "1000000000000000", "1000000000000000.0001", "1e15", "1e-5", "0", "-0.0001", "null"})
    void transferRejectsInvalidAmountsBeforeCallingService(String amount) throws Exception {
        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(amount)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").isNotEmpty());

        verifyNoInteractions(accountService, transferService);
    }

    private String transferBody(String amount) {
        return """
                {"sourceAccountId":"%s","targetAccountId":"%s","amount":%s}
                """.formatted(SOURCE_ID, TARGET_ID, amount);
    }
}
