package com.sathya.payment.dto;

import com.sathya.payment.entity.Transaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TransferResponse(
        UUID transactionId,
        UUID sourceAccountId,
        UUID targetAccountId,
        BigDecimal amount,
        String currency,
        Transaction.TransactionStatus status,
        OffsetDateTime createdAt
) {
}