
package com.sathya.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record DepositRequest(

        @NotNull(message = "amount is required")
        @DecimalMin(
                value = "0.0001",
                message = "amount must be at least 0.0001"
        )
        @Digits(integer = 15, fraction = 4,
                message = "amount must have at most 15 integer digits and 4 decimal places")
        BigDecimal amount
) {
}
