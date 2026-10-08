package com.sathya.payment.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String userId,
        String currency,
        BigDecimal balance,
        OffsetDateTime createdAt
) {
}