package com.sathya.payment;

import com.sathya.payment.dto.TransferRequest;
import com.sathya.payment.exception.InvalidTransferException;
import com.sathya.payment.repository.AccountRepository;
import com.sathya.payment.repository.IdempotencyKeyRepository;
import com.sathya.payment.repository.LedgerEntryRepository;
import com.sathya.payment.repository.TransactionRepository;
import com.sathya.payment.service.TransferService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransferServiceTest {

    @Test
    void shouldRejectBlankIdempotencyKey() {
        TransferService service = new TransferService(
                null,
                null,
                null,
                null,
                null
        );

        TransferRequest request = new TransferRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new java.math.BigDecimal("10.00")
        );

        InvalidTransferException exception = assertThrows(
                InvalidTransferException.class,
                () -> service.transfer("   ", request)
        );

        assertEquals(
                "Idempotency-Key must contain 1 to 128 characters",
                exception.getMessage()
        );
    }
}