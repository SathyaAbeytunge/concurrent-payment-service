package com.sathya.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateAccountRequest(

        @NotBlank(message = "userId is required")
        String userId,

        @Pattern(
                regexp = "^[A-Z]{3}$",
                message = "currency must be a 3-letter uppercase currency code"
        )
        String currency
) {
}