
package com.sathya.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record DepositRequest(

        @NotNull(message = "amount is required")
        @DecimalMin(
                value = "0.0001",
                message = "amount must be greater than zero"
        )
        BigDecimal amount
) {
}
